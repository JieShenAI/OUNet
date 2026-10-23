"""Check exported brand assets and release hashes without running the apps."""
import hashlib
import json
from pathlib import Path
import struct
from PySide6.QtGui import QImage

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parents[1]


def main():
    report = {}
    for name in ("mobile", "desktop", "logo"):
        path = ROOT / f"ounet-{name}.png"
        image = QImage(str(path))
        assert not image.isNull(), path
        if name != "mobile":
            assert image.hasAlphaChannel() and image.pixelColor(0, 0).alpha() == 0, path
        report[name] = {"width": image.width(), "height": image.height(), "alpha": image.hasAlphaChannel()}
    ico = (PROJECT / "desktop" / "assets" / "app.ico").read_bytes()
    assert struct.unpack_from('<HHH', ico) == (0, 1, 9)
    sizes = []
    for index in range(9):
        width, height, _, _, planes, depth, count, offset = struct.unpack_from('<BBBBHHII', ico, 6 + index * 16)
        assert width == height and planes == 1 and depth == 32
        assert ico[offset:offset+8] == b'\x89PNG\r\n\x1a\n' and offset + count <= len(ico)
        sizes.append(width or 256)
    assert sizes == [16, 20, 24, 32, 40, 48, 64, 128, 256]
    report['icoSizes'] = sizes
    android = PROJECT / "android" / "dist"
    metadata = json.loads((android / "android-update.json").read_text(encoding="utf-8"))
    version = metadata['versionName']
    primary = android / f"OUNet-{version}-release.apk"
    alias = android / metadata['apkUrl'].rsplit('/', 1)[1]
    digest = hashlib.sha256(primary.read_bytes()).hexdigest()
    assert digest == hashlib.sha256(alias.read_bytes()).hexdigest() == metadata['sha256']
    assert metadata['applicationId'] == 'cn.jieshen.oucautologin'
    report['androidAliasIdentical'] = True
    report['androidVersion'] = version
    report['androidSha256'] = digest
    installer = PROJECT / "desktop" / "dist" / "OUNet-0.2.0-Setup-x64.exe"
    assert hashlib.sha256(installer.read_bytes()).hexdigest() == installer.with_suffix('.exe.sha256').read_text().split()[0]
    report['windowsInstallerHashVerified'] = True
    (ROOT / 'verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
