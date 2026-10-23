# -*- mode: python ; coding: utf-8 -*-
from pathlib import Path
import sys
root = Path(SPECPATH)
extra_binaries = []
# A venv built from Conda needs these additional CPython DLLs; ordinary CPython
# builds do not have them. Keep the bundle independent of the developer's PATH.
for filename in ('ffi.dll', 'libmpdec-4.dll'):
    source = Path(sys.base_prefix) / 'Library' / 'bin' / filename
    if source.exists(): extra_binaries.append((str(source), '.'))
for filename in ('MSVCP140.dll','MSVCP140_1.dll','MSVCP140_2.dll','VCRUNTIME140.dll','VCRUNTIME140_1.dll','concrt140.dll'):
    source = root / '.venv' / 'Lib' / 'site-packages' / 'shiboken6' / filename
    if source.exists(): extra_binaries.append((str(source), '.'))
a = Analysis(
    [str(root / 'run.py')],
    pathex=[str(root), str(root.parent)],
    binaries=extra_binaries,
    datas=[(str(root.parent / 'LICENSE'), '.'), (str(root / 'qml'), 'qml'),
           (str(root / 'licenses'), 'licenses'), (str(root / 'THIRD_PARTY_NOTICES.md'), '.')]
          + [(str(asset), 'assets/' + asset.name if asset.is_dir() else 'assets') for asset in (root / 'assets').iterdir()
             if asset.name not in ('ouc-logo.svg','ouc-campus.jpg','waves.svg')],
    hiddenimports=['PySide6.QtSvg', 'PySide6.QtQuickControls2'],
    hookspath=[], runtime_hooks=[],
    excludes=['tkinter','matplotlib','numpy','pandas','IPython','PySide6.QtWebEngineCore',
              'PySide6.QtWebEngineWidgets','PySide6.QtWebEngineQuick','PySide6.QtPdf','PySide6.QtPdfWidgets'],
    noarchive=False,
)
# Qt uses Windows' built-in ICU (unversioned exports). Conda's similarly named
# ICU DLL has version-suffixed exports and must not shadow the operating system.
a.binaries = [item for item in a.binaries if not (
    Path(item[0]).name.lower() in ('icuuc.dll','icuin.dll') or
    (Path(item[0]).name.lower().startswith('icudt') and item[0].lower().endswith('.dll')))]
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name='OUNet',
          debug=False, bootloader_ignore_signals=False, strip=False, upx=False,
          console=False, disable_windowed_traceback=False,
          icon=str(root / 'assets' / 'app.ico'),
          version=str(root / 'version_info.txt'))
coll = COLLECT(exe,a.binaries,a.datas,strip=False,upx=False,name='OUNet')
