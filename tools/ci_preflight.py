"""Validate workflow inputs without inserting user input into shell commands."""
import os
from pathlib import Path
import re
import subprocess


def main():
    platform=os.environ['RELEASE_PLATFORM'];version=os.environ['RELEASE_VERSION'];sha=os.environ['RELEASE_SHA']
    if platform not in ('android','windows') or not re.fullmatch(r'\d+\.\d+\.\d+',version) or not re.fullmatch('[a-f0-9]{40}',sha):
        raise SystemExit('Invalid platform, version or source SHA')
    actual=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    if actual!=sha:raise SystemExit('Checkout does not match frozen source SHA')
    subprocess.run(['git','merge-base','--is-ancestor',sha,'origin/main'],check=True)
    root=Path(__file__).resolve().parents[1]
    source=(root/'android/app/build.gradle' if platform=='android' else root/'desktop/ouc_desktop/__init__.py').read_text(encoding='utf-8')
    pattern=r"versionName '([^']+)'" if platform=='android' else r'__version__ = "([^"]+)"'
    if re.search(pattern,source).group(1)!=version:raise SystemExit('Requested version differs from source')
    notes=root/'docs'/f'{"ANDROID" if platform=="android" else "WINDOWS"}_V{version.replace(".","_")}_RELEASE_NOTES.md'
    if not notes.is_file():raise SystemExit('Version release notes missing')
    print('Release preflight passed for',platform,version,sha)


if __name__=='__main__':main()
