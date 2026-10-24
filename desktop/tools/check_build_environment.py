"""Record and enforce the release runtime and exact dependency versions."""
import argparse
import hashlib
from importlib import metadata
import json
from pathlib import Path
import platform
import re
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    lock = root / 'requirements-release.lock.txt'
    if sys.version_info[:3] != (3, 13, 9) or platform.machine().upper() != 'AMD64':
        raise ValueError('Release builds require CPython 3.13.9 x64.')
    if (Path(sys.base_prefix) / 'conda-meta').exists() or sys.prefix == sys.base_prefix:
        raise ValueError('Use an isolated venv made from official CPython for a release.')
    packages = {}
    for line in lock.read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        match = re.fullmatch(r'([\w.-]+)==([\w.]+) --hash=sha256:[0-9a-f]{64}', line)
        if not match:
            raise ValueError('Unhashed or unpinned release dependency')
        name, expected = match.groups()
        actual = metadata.version(name)
        if actual != expected:
            raise ValueError(f'Wrong installed version for {name}: {actual}')
        packages[name] = actual
    normalize = lambda name: re.sub(r'[-_.]+', '-', name).lower()
    allowed = {normalize(name) for name in packages} | {'pip'}
    extras = {dist.metadata['Name'] for dist in metadata.distributions()
              if normalize(dist.metadata['Name']) not in allowed}
    if extras:
        raise ValueError('Unexpected packages in release venv: ' + ', '.join(sorted(extras)))
    report = {'pythonVersion': platform.python_version(), 'architecture': platform.machine(),
              'basePythonSha256': hashlib.sha256(Path(sys._base_executable).read_bytes()).hexdigest(),
              'dependencyLockSha256': hashlib.sha256(lock.read_bytes()).hexdigest(), 'packages': packages}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('Official runtime and exact release dependency versions verified.')


if __name__ == '__main__':
    main()
