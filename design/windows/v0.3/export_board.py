"""Export a code-native SVG design board and PNG. This is not an HTML screenshot."""
from pathlib import Path
import base64
import html
import os

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
from PySide6.QtCore import QRectF
from PySide6.QtGui import QGuiApplication, QImage, QPainter, QFontDatabase
from PySide6.QtSvg import QSvgRenderer

ROOT = Path(__file__).resolve().parent
INK, MUTED, BLUE, BORDER = "#172d46", "#52677e", "#004098", "#dfe7f1"
parts = []


def rect(x, y, w, h, fill="white", r=0, stroke=None):
    parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill}"' + (f' stroke="{stroke}"' if stroke else '') + '/>')


def text(x, y, value, size=14, color=INK, weight=400, family="Microsoft YaHei"):
    parts.append(f'<text x="{x}" y="{y}" fill="{color}" font-family="{family}" font-size="{size}" font-weight="{weight}">{html.escape(str(value))}</text>')


def line(x1, y1, x2, y2, color=BORDER):
    parts.append(f'<path d="M{x1} {y1}H{x2}" stroke="{color}"/>' if y1 == y2 else f'<path d="M{x1} {y1}L{x2} {y2}" stroke="{color}"/>')


def circle(x, y, r, color):
    parts.append(f'<circle cx="{x}" cy="{y}" r="{r}" fill="{color}"/>')


def card(x, y, w, h):
    rect(x, y, w, h, "white", 16, BORDER)


def icon(x, y, size):
    data = base64.b64encode((ROOT.parent.parent / "ounet/ounet-desktop.png").read_bytes()).decode()
    parts.append(f'<image x="{x}" y="{y}" width="{size}" height="{size}" xlink:href="data:image/png;base64,{data}"/>')


def export():
    parts.append('<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1440" height="1010" viewBox="0 0 1440 1010">')
    rect(0, 0, 1440, 1010, "#e8edf4")
    text(28, 30, "OUNET / WINDOWS DESKTOP", 11, BLUE, 600)
    text(1070, 30, "v0.3 设计效果图 · 全部为虚构数据", 12, MUTED)
    rect(24, 56, 1392, 916, "#f4f7fb", 12, BORDER)
    rect(25, 57, 1390, 39, "#f8fafd", 11)
    icon(40, 66, 20)
    text(71, 81, "OUNet", 12)
    text(127, 81, "Windows · UI 设计提案", 11, MUTED)
    text(1295, 81, "—    □    ×", 14, MUTED)
    rect(25, 96, 203, 875, "#f8fafd")
    line(228, 96, 228, 971)
    icon(51, 130, 43)
    text(106, 149, "OUNet", 25, INK, 600)
    text(106, 170, "校园网络助手", 11, MUTED)
    text(53, 222, "工作空间", 11, MUTED)
    for n, label in enumerate(["连接概览", "账号管理", "偏好设置", "帮助与关于"]):
        y = 238 + n * 50
        if not n:
            rect(38, y, 176, 44, "#e8f0fc", 8)
            rect(38, y + 13, 3, 18, BLUE, 1)
        # Simple consistent code-native outlined glyphs.
        parts.append(f'<rect x="54" y="{y+13}" width="17" height="17" rx="3" fill="none" stroke="{BLUE if not n else MUTED}" stroke-width="1.5"/>')
        text(86, y + 28, label, 14, BLUE if not n else INK, 600 if not n else 400)
    circle(53, 894, 3, "#087a60")
    text(64, 898, "在你的电脑上运行", 11, MUTED)
    text(50, 924, "免费软件，仅用于学习交流", 10, MUTED)
    text(50, 943, "OUNet · 独立开发", 10, MUTED)
    rect(229, 96, 1186, 52, "white")
    line(229, 148, 1416, 148)
    parts.append('<path d="M257 118q9-7 18 0m-15 4q6-5 12 0m-9 4q3-2 6 0" fill="none" stroke="#004098" stroke-width="1.6"/>')
    text(289, 126, "OUC-Campus-Demo", 12)
    text(434, 126, "Wi-Fi · 当前连接", 11, MUTED)
    text(1300, 126, "网络设置  ↗", 12, BLUE)
    text(256, 181, "YOUR CAMPUS, CONNECTED", 9, BLUE, 600)
    text(252, 220, "连接概览", 29, INK, 600)
    text(252, 247, "连接状态、在线身份与恢复记录，清楚呈现。", 12, MUTED)
    text(1237, 213, "10 月 23 日，星期五", 11, MUTED)

    # Session: six fields remain the main information on the screen.
    card(252, 270, 810, 338)
    text(276, 304, "校园网会话", 16, INK, 600)
    rect(951, 287, 87, 27, "#e9f6f0", 6)
    circle(965, 300, 3, "#087a60")
    text(976, 305, "外网可用", 11, "#087a60")
    text(276, 335, "当前在线身份", 11, MUTED)
    text(276, 371, "demo_2026001", 29, INK, 600)
    text(276, 394, "来自 XHA 上次实际读取的信息", 11, MUTED)
    line(276, 411, 1038, 411)
    fields = [("账号", "demo_2026001"), ("上线时间", "2026-10-23 08:42:16"), ("移动端标识", "PC 端（0）"), ("源 IPv4", "192.0.2.18"), ("源 IPv6", "2001:db8:85a3::8a2e:370:7334"), ("源 MAC", "02:00:5E:10:00:18")]
    for n, (label, value) in enumerate(fields):
        x, y = 276 + (n % 3) * 256, 438 + (n // 3) * 57
        text(x, y, label, 11, MUTED)
        text(x, y + 23, value, 11 if n == 4 else 12)
    line(276, 535, 1038, 535)
    text(276, 559, "上次读取 12:20:08 · 当前连接", 10, MUTED)
    text(967, 559, "刷新信息", 12, BLUE)
    rect(276, 574, 762, 22, "#f8fafd", 6)
    text(287, 590, "查看在线设备", 11, BLUE)
    text(1014, 590, "→", 13, BLUE)

    # Actions grouped in a dedicated desktop column.
    card(1080, 270, 312, 315)
    text(1104, 304, "自动监测", 16, INK, 600)
    rect(1332, 287, 36, 20, BLUE, 10)
    circle(1358, 297, 7.5, "white")
    text(1104, 329, "守护当前连接，断网后按需认证。", 11, MUTED)
    rect(1104, 350, 264, 59, "#e8f0fc", 10)
    parts.append('<path d="m1124 366 11-4 11 4v9c0 7-11 13-11 13s-11-6-11-13v-9m5 9 4 4 8-9" fill="none" stroke="#004098" stroke-width="1.7"/>')
    text(1161, 385, "监测已开启", 14, BLUE, 600)
    for y, label, value in [(444, "检测间隔", "完成后等待 1 秒"), (476, "上次检测", "12:26:32"), (508, "本轮耗时", "0.24 秒")]:
        text(1104, y, label, 11, MUTED)
        text(1250 if y == 444 else 1315, y, value, 11)
    text(1104, 553, "调整检测间隔 →", 12, BLUE)
    card(1080, 603, 312, 226)
    text(1104, 636, "下次认证使用账号", 13, INK, 600)
    rect(1104, 652, 264, 38, "white", 8, BORDER)
    text(1116, 676, "常用账号 · demo_2026001", 12)
    text(1344, 676, "v", 11, MUTED)
    text(1104, 713, "切换账号后，在需要认证时使用。", 11, MUTED)
    rect(1104, 735, 264, 42, BLUE, 8)
    text(1154, 762, "重新检测并连接", 14, "white", 600)
    text(1113, 802, "主动查询 XHA；已在线时不重复登录。", 10, MUTED)

    card(252, 626, 810, 187)
    text(276, 661, "本次开机恢复", 16, INK, 600)
    text(276, 685, "开机于今天 08:30 · 同一故障只计一次", 11, MUTED)
    text(929, 663, "2", 30, BLUE, 600)
    text(956, 661, "次协助恢复", 11, MUTED)
    for y, time, duration in [(717, "11:48:06", "3.2 秒"), (761, "09:16:24", "2.8 秒")]:
        circle(280, y - 4, 3, "#087a60")
        text(296, y, time, 11, MUTED)
        text(375, y, "校园网认证恢复", 12)
        text(375, y + 17, "外网稳定复检通过", 10, MUTED)
        text(996, y, duration, 11, MUTED)
    text(276, 798, "展开恢复详情 ↓", 11, BLUE)
    card(252, 831, 810, 103)
    text(276, 861, "XHA 调用统计", 13, INK, 600)
    text(276, 882, "仅统计实际派发的校园网请求尝试，含失败。", 11, MUTED)
    text(879, 863, "12", 25, INK, 600)
    text(879, 882, "本次进程", 10, MUTED)
    text(982, 863, "0", 25, INK, 600)
    text(975, 882, "最近 60 秒", 10, MUTED)
    line(276, 897, 1038, 897)
    text(276, 918, "进程始于 08:42:00 · 公共探测不计入 · 当前联网监测不访问 XHA", 10, MUTED)
    line(229, 941, 1416, 941)
    circle(254, 956, 3, "#087a60")
    text(266, 960, "自动监测运行中", 10, MUTED)
    text(1164, 960, "本地保存 · HTTPS 认证 · 设计示意", 10, MUTED)
    text(28, 994, "设计效果图由独立 SVG 绘制，不是 HTML 浏览器截图或已实现的 QML 界面。", 11, MUTED)
    parts.append('</svg>')
    target = ROOT / "previews"
    target.mkdir(exist_ok=True)
    svg = target / "overview.svg"
    svg.write_text('\n'.join(parts), encoding="utf-8")
    app = QGuiApplication.instance() or QGuiApplication([])
    for filename in ("msyh.ttc", "msyhbd.ttc", "segoeui.ttf"):
        font_path = Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts" / filename
        if font_path.exists():
            QFontDatabase.addApplicationFont(str(font_path))
    renderer = QSvgRenderer(str(svg))
    if not renderer.isValid():
        raise RuntimeError("Invalid SVG")
    image = QImage(1440, 1010, QImage.Format.Format_ARGB32_Premultiplied)
    image.fill(0)
    painter = QPainter(image)
    renderer.render(painter, QRectF(0, 0, 1440, 1010))
    painter.end()
    if not image.save(str(target / "overview.png")):
        raise RuntimeError("PNG export failed")
    print("Exported SVG design board and PNG: 1440 x 1010")


if __name__ == "__main__":
    export()
