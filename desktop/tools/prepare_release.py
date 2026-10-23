"""Create Windows-only release metadata for the exact locally built installer."""
from pathlib import Path
import hashlib
import json
import sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from ouc_desktop import __version__
from ouc_desktop.update import REPOSITORY, validate_metadata

installer=ROOT/'dist'/f'OUNet-{__version__}-Setup-x64.exe'
if not installer.exists():raise SystemExit('Build the current Windows installer before generating release metadata.')
metadata={'platform':'windows-x64','version':__version__,
          'url':f'https://github.com/{REPOSITORY}/releases/download/windows-v{__version__}/{installer.name}',
          'sha256':hashlib.sha256(installer.read_bytes()).hexdigest(),
          'notes':'全新桌面界面；外网通过不请求XHA；小数秒间隔；凭据错误独立提示；只读在线设备与诊断。'}
validate_metadata(metadata)
(ROOT/'dist/windows-update.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Windows release metadata generated locally; no upload performed.')
