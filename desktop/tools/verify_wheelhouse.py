"""Verify downloaded Windows wheels against PyPI before installing them."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('wheelhouse', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    requirements = []
    for line in (root / 'requirements.lock.txt').read_text().splitlines():
        if line and not line.startswith('#'):
            match = re.fullmatch(r'([\w.-]+)==([\w.]+)', line)
            if not match:
                raise ValueError('Requirement must be an exact version: ' + line)
            requirements.append(match.groups())
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    expected_files = set()
    lines = ['# Windows x64 / CPython 3.13.9; hashes verified against PyPI.']
    for name, version in requirements:
        url = f'https://pypi.org/pypi/{name}/{version}/json'
        with opener.open(url, timeout=30) as response:
            metadata = json.load(response)
        candidates = []
        for entry in metadata['urls']:
            path = args.wheelhouse / entry['filename']
            if path.is_file() and path.suffix == '.whl':
                digest = hashlib.sha256(path.read_bytes()).hexdigest()
                if digest != entry['digests']['sha256']:
                    raise ValueError('PyPI digest mismatch: ' + path.name)
                candidates.append((path.name, digest))
        if len(candidates) != 1:
            raise ValueError('Expected one verified wheel for ' + name)
        filename, digest = candidates[0]
        expected_files.add(filename)
        lines.append(f'{name}=={version} --hash=sha256:{digest}')
        print('Verified PyPI wheel:', filename, flush=True)
    if {path.name for path in args.wheelhouse.iterdir()} != expected_files:
        raise ValueError('Unexpected files in wheelhouse')
    args.output.write_text('\n'.join(lines) + '\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
