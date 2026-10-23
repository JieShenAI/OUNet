from importlib.metadata import distribution
from pathlib import Path
import shutil
import sys

root=Path(__file__).resolve().parents[1]
target=root/'licenses'
target.mkdir(exist_ok=True)
packages=['PySide6-Essentials','shiboken6','requests','urllib3','certifi','charset-normalizer','idna','PyInstaller']
lines=['# 第三方组件与视觉素材\n','本文件记录本构建使用的第三方组件和来源。依赖许可证正文位于 licenses/；Qt 以可替换的独立 DLL 随程序分发。\n']
for package in packages:
    dist=distribution(package)
    lines.append(f'- {package} {dist.version}：{dist.metadata.get("License-Expression") or dist.metadata.get("License") or "见随附许可文件"}')
    for item in dist.files or []:
        if any(key in str(item).lower() for key in ('license','copying','notice')) and not str(item).endswith('.py'):
            source=Path(dist.locate_file(item))
            if source.is_file():
                destination=target/package/str(item)
                destination.parent.mkdir(parents=True,exist_ok=True)
                shutil.copyfile(source,destination)
python_license=Path(sys.base_prefix)/'LICENSE.txt'
if python_license.exists(): shutil.copyfile(python_license,target/'Python-LICENSE.txt')
lines.extend(['\nQt / PySide6 开源源代码及许可资料：',
'- https://code.qt.io/cgit/pyside/pyside-setup.git/?h=v6.10.2',
'- https://code.qt.io/cgit/qt/qtbase.git/?h=v6.10.2',
'- https://code.qt.io/cgit/qt/qtdeclarative.git/?h=v6.10.2',
'- https://code.qt.io/cgit/qt/qtsvg.git/?h=v6.10.2',
'- https://doc.qt.io/qt-6/lgpl.html',
'\n学校标识、标准色、校训与校园摄影来自中国海洋大学官网；详情见 assets/SOURCES.md。本工具非学校官方软件。应用图标与界面为本项目设计。'])
(root/'THIRD_PARTY_NOTICES.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('Third-party notices collected.')
