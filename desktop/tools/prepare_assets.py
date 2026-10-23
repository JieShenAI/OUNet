"""Export OUNet raster icons and retain the official source assets unchanged."""
from pathlib import Path
import shutil
import struct
import sys
import xml.etree.ElementTree as ET

from PySide6.QtGui import QGuiApplication, QImage, QPainter, QColor
from PySide6.QtCore import QByteArray, QBuffer, QIODevice, Qt


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT.parent / "design" / "windows"


def ico(source: QImage, target: Path, dot: str = ""):
    sizes = (16, 20, 24, 32, 40, 48, 64, 128, 256)
    entries, blobs = [], []
    offset = 6 + 16 * len(sizes)
    for size in sizes:
        image = source.scaled(size, size, Qt.AspectRatioMode.IgnoreAspectRatio, Qt.TransformationMode.SmoothTransformation)
        if dot:
            painter = QPainter(image)
            painter.setRenderHint(QPainter.RenderHint.Antialiasing)
            painter.setPen(QColor("white"))
            painter.setBrush(QColor(dot))
            painter.drawEllipse(int(size * .69), int(size * .69), max(4, int(size * .27)), max(4, int(size * .27)))
            painter.end()
        data = QByteArray()
        buffer = QBuffer(data)
        buffer.open(QIODevice.OpenModeFlag.WriteOnly)
        if not image.save(buffer, "PNG"):
            raise RuntimeError(f"Cannot export {target}")
        blob = bytes(data)
        entries.append(struct.pack('<BBBBHHII', size % 256, size % 256, 0, 0, 1, 32, len(blob), offset))
        blobs.append(blob)
        offset += len(blob)
    target.write_bytes(struct.pack('<HHH', 0, 1, len(sizes)) + b''.join(entries) + b''.join(blobs))


def main():
    app = QGuiApplication.instance() or QGuiApplication(sys.argv[:1])
    assets = ROOT / "assets"
    (assets / "icons").mkdir(parents=True, exist_ok=True)
    brand = ROOT.parent / "design" / "ounet"
    for name in ("ounet-desktop.png", "ounet-logo.png"):
        shutil.copyfile(brand / name, assets / name)
    shutil.copyfile(SOURCE / "SOURCES.md", assets / "SOURCES.md")
    with (assets / "SOURCES.md").open('a', encoding='utf-8') as sources:
        sources.write('\n\n## OUNet brand assets\n\nThe current app icon and logo are original PNG assets generated with built-in image_gen. Masters, prompts, and export instructions: `../../design/ounet/README.md`. Historical SVG app-icon entries above describe the previous design only.\n')
    import re
    html = (SOURCE / "index.html").read_text(encoding="utf-8")
    sprite = ET.fromstring(re.search(r'<svg class="sprite".*?</svg>',html,re.S)[0])
    v03 = (SOURCE / "v0.3" / "index.html").read_text(encoding="utf-8")
    current = ET.fromstring(re.search(r'<svg class="svg-definitions".*?</svg>',v03,re.S)[0])
    sprite.extend([node for node in current.iter() if node.tag.endswith('symbol')])
    for node in sprite.iter():
        if node.tag.endswith('symbol'):
            name = node.attrib['id'].removeprefix('i-')
            for tone, color in {"blue":"#004098", "white":"#FFFFFF", "muted":"#8399B2"}.items():
                svg = ET.Element('svg',{'xmlns':'http://www.w3.org/2000/svg','viewBox':'0 0 24 24','fill':'none','stroke':color,'stroke-width':'1.7','stroke-linecap':'round','stroke-linejoin':'round'})
                for child in node:
                    copy = ET.fromstring(ET.tostring(child))
                    for part in copy.iter(): part.tag = part.tag.split('}')[-1]
                    svg.append(copy)
                (assets/'icons'/f'{name}-{tone}.svg').write_bytes(ET.tostring(svg))
    (assets/'waves.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 920 100" preserveAspectRatio="none"><path d="M0 75C130 20 220 110 390 55S640 0 920 60V100H0Z" fill="#edf5fc"/><path d="M0 90C190 35 260 100 440 70S700 10 920 83V100H0Z" fill="#e6f0fb"/></svg>',encoding='utf-8')
    image = QImage(str(assets / 'ounet-desktop.png'))
    if image.isNull():
        raise RuntimeError('OUNet icon master is missing or invalid')
    ico(image,assets/'app.ico')
    for name,color in {'online':'#168063','protected':'#b5822a','waiting':'#00a0e9','paused':'#8b9db2'}.items():
        ico(image,assets/f'tray-{name}.ico',color)
    print('Desktop assets prepared.')

if __name__ == '__main__': main()
