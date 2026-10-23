"""Fail before packaging if the app, PE resource and installer versions differ."""
from pathlib import Path
import re
import sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from ouc_desktop import __version__


def check():
    info=(ROOT/'version_info.txt').read_text(encoding='utf-8')
    script=(ROOT/'installer/OUCNetworkAssistant.iss').read_text(encoding='utf-8')
    if re.search(r'#define AppVersion "([^"]+)"',script).group(1)!=__version__:raise ValueError('Installer version mismatch')
    for field in ('FileVersion','ProductVersion'):
        if re.search(r"StringStruct\('"+field+r"','([^']+)'\)",info).group(1)!=__version__:raise ValueError('PE version mismatch')
    parts=tuple(map(int,__version__.split('.')))+(0,)
    for field in ('filevers','prodvers'):
        found=tuple(map(int,re.search(field+r'=\(([^)]+)\)',info).group(1).split(',')))
        if found!=parts:raise ValueError('Numeric PE version mismatch')
    return __version__


if __name__=='__main__':print(check())
