"""Package this exact installer and portable directory; no upload or installation."""
from pathlib import Path
import hashlib
import json
import sys
import zipfile
import pefile
from check_versions import check

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from ouc_desktop import __version__
from ouc_desktop.update import REPOSITORY, validate_metadata


def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
    return h.hexdigest()


def main():
    check()
    dist=ROOT/'dist';installer=dist/f'OUNet-{__version__}-Setup-x64.exe'
    if not installer.exists():raise SystemExit('Build the current Windows installer first.')
    exe=dist/'OUNet/OUNet.exe'
    with pefile.PE(str(exe)) as pe:
        fixed=pe.VS_FIXEDFILEINFO[0]
        actual=(fixed.FileVersionMS>>16,fixed.FileVersionMS&65535,fixed.FileVersionLS>>16,fixed.FileVersionLS&65535)
        if actual!=tuple(map(int,__version__.split('.')))+(0,):raise ValueError('Built EXE version mismatch')
    notes=(ROOT.parent/'docs'/f'WINDOWS_V{__version__.replace(".","_")}_RELEASE_NOTES.md').read_text(encoding='utf-8')
    portable=dist/f'OUNet-{__version__}-Windows-x64-portable.zip'
    with zipfile.ZipFile(portable,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        for path in sorted((dist/'OUNet').rglob('*')):
            if path.is_file():archive.write(path,path.relative_to(dist).as_posix())
    for path in (installer,portable):
        path.with_name(path.name+'.sha256').write_text(sha(path)+'  '+path.name+'\n',encoding='ascii')
    metadata={'platform':'windows-x64','version':__version__,
              'url':f'https://github.com/{REPOSITORY}/releases/download/windows-v{__version__}/{installer.name}',
              'sha256':sha(installer),'notes':notes}
    validate_metadata(metadata)
    (dist/'windows-update.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (dist/'WINDOWS_RELEASE_NOTES.md').write_text(notes,encoding='utf-8')
    print('Windows installer, portable ZIP and update metadata ready; not uploaded.')


if __name__=='__main__':main()
