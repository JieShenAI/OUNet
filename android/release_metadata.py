"""Prepare public Android Release assets. Never reads keystore/password files."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

CERT = "6a5eedfcb02875007673e86772bc9da399e48e80f24dd88b85976126296fd91f"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--apk-info", type=Path, required=True)
    parser.add_argument("--apksigner", type=Path, required=True)
    parser.add_argument("--notes", type=Path, required=True)
    args = parser.parse_args()
    info = json.loads(args.apk_info.read_text(encoding="utf-8"))
    item = info["elements"][0]
    version = item["versionName"]
    if info["applicationId"] != "cn.jieshen.oucautologin" or not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise SystemExit("Only the production Android APK can produce update metadata")
    result = subprocess.run([str(args.apksigner), "verify", "--print-certs", str(args.apk)], check=True, capture_output=True, text=True)
    certs = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]{64})", result.stdout)
    if certs != [CERT]:
        raise SystemExit("Signing certificate differs from the original production APK")
    aapt = args.apksigner.parent / ('aapt.exe' if (args.apksigner.parent / 'aapt.exe').exists() else 'aapt')
    badging = subprocess.run([str(aapt), 'dump', 'badging', str(args.apk)], check=True, capture_output=True, text=True, encoding='utf-8', errors='replace').stdout
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    sdk = re.search(r"sdkVersion:'([0-9]+)'", badging)
    if not package or not sdk or package.groups() != (info['applicationId'], str(item['versionCode']), version) or int(sdk.group(1)) != 28:
        raise SystemExit('Actual APK package, version or minimum SDK differs from build metadata')
    if args.apk.name != f'OUNet-{version}-release.apk':
        raise SystemExit('Unexpected public APK filename')
    tag = f"android-v{version}"
    repo = "https://github.com/JieShenAI/OUNet"
    notes = args.notes.read_text(encoding="utf-8")
    metadata = {
        "applicationId": info["applicationId"], "versionCode": item["versionCode"],
        "versionName": version, "minSdk": int(sdk.group(1)),
        "releaseUrl": f"{repo}/releases/tag/{tag}",
        "apkUrl": f"{repo}/releases/download/{tag}/{args.apk.name}",
        "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        "signingCertSha256": CERT, "releaseNotes": notes,
    }
    output = args.apk.parent
    # Existing installed clients require the original exact asset URL. Publish
    # a byte-identical compatibility alias alongside the new public OUNet APK.
    legacy = output / f"ouc-autologin-{version}-release.apk"
    if args.apk.resolve() != legacy.resolve():
        shutil.copyfile(args.apk, legacy)
    digest = metadata["sha256"]
    args.apk.with_suffix('.apk.sha256').write_text(f'{digest}  {args.apk.name}\n', encoding='ascii')
    legacy.with_suffix(".apk.sha256").write_text(f"{digest}  {legacy.name}\n", encoding="ascii")
    metadata["apkUrl"] = f"{repo}/releases/download/{tag}/{legacy.name}"
    (output / "android-update.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "ANDROID_RELEASE_NOTES.md").write_text(notes, encoding="utf-8")
    print(f"Android Release metadata ready: {tag} (not published)")


if __name__ == "__main__":
    main()
