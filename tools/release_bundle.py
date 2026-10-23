"""Stage only this version's public assets; validate before any GitHub upload."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REPO = "https://github.com/JieShenAI/OUNet"
CERT = "6a5eedfcb02875007673e86772bc9da399e48e80f24dd88b85976126296fd91f"


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''): h.update(block)
    return h.hexdigest()


def asset_names(platform, version):
    if not re.fullmatch(r'\d+\.\d+\.\d+', version): raise ValueError('Invalid version')
    if platform == 'android':
        packages = [f'OUNet-{version}-release.apk', f'ouc-autologin-{version}-release.apk']
        return packages + [p + '.sha256' for p in packages] + ['android-update.json', 'ANDROID_RELEASE_NOTES.md']
    if platform != 'windows': raise ValueError('Invalid platform')
    packages = [f'OUNet-{version}-Setup-x64.exe', f'OUNet-{version}-Windows-x64-portable.zip']
    return packages + [p + '.sha256' for p in packages] + ['windows-update.json', 'WINDOWS_RELEASE_NOTES.md']


def validate_files(directory, platform, version):
    names = asset_names(platform, version)
    for name in names:
        path = directory / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size == 0: raise ValueError('Missing asset: ' + name)
        if name.endswith(('.apk', '.exe', '.zip')):
            expected = digest(path) + '  ' + name
            if (directory / (name + '.sha256')).read_text(encoding='utf-8-sig').strip() != expected:
                raise ValueError('Checksum mismatch: ' + name)
    meta = json.loads((directory / f'{platform}-update.json').read_text(encoding='utf-8'))
    tag = f'{platform}-v{version}'
    if platform == 'android':
        brand = directory / f'OUNet-{version}-release.apk'
        legacy = directory / f'ouc-autologin-{version}-release.apk'
        if digest(brand) != digest(legacy): raise ValueError('Android compatibility APK differs')
        if (meta.get('applicationId') != 'cn.jieshen.oucautologin' or meta.get('versionName') != version
                or type(meta.get('versionCode')) is not int or meta['versionCode'] < 1
                or meta.get('minSdk') != 28 or meta.get('signingCertSha256') != CERT
                or meta.get('releaseUrl') != f'{REPO}/releases/tag/{tag}'
                or meta.get('apkUrl') != f'{REPO}/releases/download/{tag}/{legacy.name}'
                or meta.get('sha256') != digest(brand)):
            raise ValueError('Android metadata does not describe the exact package')
    else:
        setup = directory / f'OUNet-{version}-Setup-x64.exe'
        if (meta.get('platform') != 'windows-x64' or meta.get('version') != version
                or meta.get('url') != f'{REPO}/releases/download/{tag}/{setup.name}'
                or meta.get('sha256') != digest(setup)):
            raise ValueError('Windows metadata does not describe the exact installer')
    return names


def source_state():
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    # License catalogs are generated from the locked packages on the build host;
    # official CPython also adds its license where a Conda venv may not have one.
    # Their exact public bytes are covered by the installer/ZIP file digests.
    paths=['.',':(exclude)desktop/licenses',':(exclude)desktop/THIRD_PARTY_NOTICES.md']
    diff=subprocess.run(['git','diff','--quiet','HEAD','--']+paths,cwd=ROOT,capture_output=True)
    if diff.returncode not in (0,1):raise RuntimeError('Cannot inspect source state')
    dirty=diff.returncode==1
    untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard','--']+paths, cwd=ROOT, text=True).strip()
    return sha, dirty or bool(untracked)


def stage(platform, version, checks_passed=False):
    source = ROOT / ('android' if platform == 'android' else 'desktop') / 'dist'
    names = validate_files(source, platform, version)
    target = ROOT / 'dist' / 'releases' / f'{platform}-v{version}'
    target.mkdir(parents=True, exist_ok=True)
    unexpected = {p.name for p in target.iterdir()} - set(names) - {'release-manifest.json'}
    if unexpected: raise ValueError('Unexpected files in release staging directory')
    for name in names: shutil.copyfile(source / name, target / name)
    sha, dirty = source_state()
    manifest = {'platform': platform, 'version': version, 'tag': f'{platform}-v{version}',
                'sourceCommit': sha, 'sourceDirty': dirty, 'automatedChecksPassed': checks_passed,
                'files': [{'name': n, 'size': (target / n).stat().st_size, 'sha256': digest(target / n)} for n in names]}
    (target / 'release-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    verify(target)
    print('Verified release bundle:', target)
    return target


def verify(directory):
    manifest = json.loads((directory / 'release-manifest.json').read_text(encoding='utf-8'))
    names = validate_files(directory, manifest['platform'], manifest['version'])
    if manifest.get('tag') != f"{manifest['platform']}-v{manifest['version']}": raise ValueError('Tag mismatch')
    if not re.fullmatch('[a-f0-9]{40}', manifest.get('sourceCommit', '')): raise ValueError('Source commit missing')
    if set(p.name for p in directory.iterdir()) != set(names + ['release-manifest.json']): raise ValueError('Unexpected release file')
    if sorted(f['name'] for f in manifest['files']) != sorted(names): raise ValueError('Manifest asset list mismatch')
    for entry in manifest['files']:
        path = directory / entry['name']
        if entry['size'] != path.stat().st_size or entry['sha256'] != digest(path): raise ValueError('Manifest digest mismatch')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform', choices=['android', 'windows'])
    parser.add_argument('--version')
    parser.add_argument('--checks-passed', action='store_true')
    parser.add_argument('--verify', type=Path)
    args = parser.parse_args()
    if args.verify: verify(args.verify); print('Bundle validation passed.')
    elif args.platform and args.version: stage(args.platform, args.version, args.checks_passed)
    else: parser.error('Use --verify or --platform and --version')


if __name__ == '__main__': main()
