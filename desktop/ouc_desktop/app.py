from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import logging
import os
from pathlib import Path
import sys
import tempfile

import auto_login as core


def main():
    parser = argparse.ArgumentParser(description="OUNet Windows 桌面版")
    parser.add_argument("--background", action="store_true", help="配置完成后收起到托盘启动")
    parser.add_argument("--demo", action="store_true", help="隔离的演示模式，不读取真实配置、不认证")
    parser.add_argument("--smoke-test", action="store_true", help="离屏加载真实 QML 并导出测试截图，不认证")
    parser.add_argument("--smoke-output", type=Path, help="离屏测试截图输出目录")
    arguments = parser.parse_args()
    if os.name != "nt":
        raise SystemExit("本程序仅支持 Windows 10/11。")
    if arguments.smoke_test:
        os.environ["QT_QPA_PLATFORM"] = "offscreen"
        os.environ["QT_QUICK_BACKEND"] = "software"
        os.environ["QT_FORCE_STDERR_LOGGING"] = "1"
    from PySide6.QtCore import QTimer, QUrl, QTranslator, QLibraryInfo, QAbstractNativeEventFilter
    from PySide6.QtGui import QIcon, QAction, QFontDatabase, QFont
    from PySide6.QtWidgets import QApplication, QSystemTrayIcon, QMenu, QMessageBox
    from PySide6.QtQml import QQmlApplicationEngine
    from PySide6.QtQuick import QQuickWindow
    from PySide6.QtNetwork import QLocalServer, QLocalSocket
    from .controller import Controller
    from . import __version__

    app = QApplication(sys.argv[:1])
    if arguments.smoke_test:
        # The offscreen platform has no Windows font enumeration; load system fonts
        # for render tests only. Native Windows deployment uses its normal font DB.
        for filename in ("segoeui.ttf", "msyh.ttc", "msyhbd.ttc"):
            font_path = Path(os.environ.get("WINDIR", r"C:\Windows")) / "Fonts" / filename
            if font_path.exists(): QFontDatabase.addApplicationFont(str(font_path))
    app.setFont(QFont("Microsoft YaHei UI", 10))
    app.setApplicationName("OUNet")
    app.setApplicationDisplayName("OUNet")
    app.setOrganizationName("OUCNetworkAssistant")
    app.setApplicationVersion(__version__)
    app.setQuitOnLastWindowClosed(False)
    resources = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[1]))
    icon = QIcon(str(resources / "assets" / "app.ico"))
    app.setWindowIcon(icon)
    translator = QTranslator(app)
    # The umbrella catalog can depend on optional Qt modules not deployed here.
    # The base catalog supplies Chinese native dialog/button labels independently.
    if translator.load("qtbase_zh_CN", QLibraryInfo.path(QLibraryInfo.TranslationsPath)):
        app.installTranslator(translator)
    demo = arguments.demo or arguments.smoke_test
    test_parent = None
    if arguments.smoke_test and arguments.smoke_output:
        test_parent = arguments.smoke_output.resolve()
        test_parent.mkdir(parents=True, exist_ok=True)
    temporary = tempfile.TemporaryDirectory(prefix="ouc-desktop-test-", dir=test_parent) if demo else None
    directory = Path(temporary.name) if demo else Path(os.environ["LOCALAPPDATA"]) / "OUCNetworkAssistant"
    directory.mkdir(parents=True, exist_ok=True)
    name = "OUCNetworkAssistant." + hashlib.sha256(str(directory).casefold().encode()).hexdigest()[:20]

    def notify_existing():
        socket = QLocalSocket()
        socket.connectToServer(name)
        if socket.waitForConnected(800):
            socket.write(b"show")
            socket.flush()
            socket.waitForBytesWritten(500)
            socket.disconnectFromServer()
            return True
        return False

    if not demo and notify_existing(): return 0
    lock = core.InstanceLock(directory / ".auto_login.lock")
    try: lock.__enter__()
    except RuntimeError:
        if not demo and notify_existing(): return 0
        QMessageBox.information(None, "OUNet", "已有桌面程序或旧脚本持有运行锁，请先停止原入口。")
        return 1
    # Lets the installer detect a running instance without terminating Python processes.
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_wchar_p]
    kernel.CreateMutexW.restype = ctypes.c_void_p
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    mutex = None if demo else kernel.CreateMutexW(None, False, "Local\\OUCNetworkAssistant")
    core.LOGGER.addHandler(logging.NullHandler())
    backend = Controller(directory, demo=demo)
    backend.quitReady.connect(app.quit)
    tray = QSystemTrayIcon(icon, app)
    backend.trayAvailable = QSystemTrayIcon.isSystemTrayAvailable() and not arguments.smoke_test
    menu = QMenu()
    open_action = QAction("打开主界面", menu)
    check_action = QAction("重新检测并连接", menu)
    pause_action = QAction("暂停自动守护", menu)
    exit_action = QAction("退出", menu)
    for action in (open_action, check_action, pause_action): menu.addAction(action)
    menu.addSeparator(); menu.addAction(exit_action)
    tray.setContextMenu(menu)
    tray.setToolTip("OUNet")
    if backend.trayAvailable: tray.show()
    engine = QQmlApplicationEngine()
    qml_warnings = []
    if arguments.smoke_test:
        def collect_warnings(warnings):
            values = [warning.toString() for warning in warnings]
            qml_warnings.extend(values)
            print("\n".join(values), flush=True)
        engine.warnings.connect(collect_warnings)
    engine.rootContext().setContextProperty("Backend", backend)
    engine.load(QUrl.fromLocalFile(str(resources / "qml" / "Main.qml")))
    if not engine.rootObjects():
        lock.__exit__(None, None, None)
        if mutex: kernel.CloseHandle(mutex)
        if not arguments.smoke_test: QMessageBox.critical(None, "无法打开界面", "桌面界面加载失败，请重新安装完整程序目录。")
        return 2
    window = engine.rootObjects()[0]

    def show_window():
        window.showNormal(); window.raise_(); window.requestActivate()

    def update_tray():
        data = backend.data
        pause_action.setText("暂停自动守护" if data["monitor"] else "恢复自动守护")
        pause_action.setEnabled(not data["busy"])
        check_action.setText("手动重试网络请求" if data["blocked"] else "重新检测并连接")
        check_action.setEnabled(not data["busy"])
        tray.setToolTip("OUNet · " + data["title"] + (" · 守护中" if data["monitor"] else " · 守护暂停"))
        color_name = "protected" if data["blocked"] else "paused" if not data["monitor"] else "online" if data["status"] == "online" else "waiting"
        state_icon = resources / "assets" / ("tray-" + color_name + ".ico")
        if state_icon.exists(): tray.setIcon(QIcon(str(state_icon)))

    open_action.triggered.connect(show_window)
    check_action.triggered.connect(lambda: backend.retryRequests() if backend.blocked else backend.reconnect())
    pause_action.triggered.connect(backend.toggleMonitor)
    exit_action.triggered.connect(backend.quit)
    tray.activated.connect(lambda reason: show_window() if reason in (QSystemTrayIcon.ActivationReason.DoubleClick, QSystemTrayIcon.ActivationReason.Trigger) else None)
    backend.notification.connect(lambda title, message: tray.showMessage(title, message, QSystemTrayIcon.MessageIcon.Information, 6000) if backend.trayAvailable else None)
    backend.changed.connect(update_tray)
    backend.hiddenToTray.connect(lambda: tray.showMessage("已收起到系统托盘", "网络助手仍在运行。完全停止请从托盘选择“退出”。", QSystemTrayIcon.MessageIcon.Information, 4000))
    update_tray()
    server = QLocalServer(app)
    server.setSocketOptions(QLocalServer.SocketOption.UserAccessOption)
    if not server.listen(name):
        # Never remove a live endpoint. The file lifetime lock already excludes rivals.
        QLocalServer.removeServer(name)
        if not server.listen(name):
            backend.startup_error = "本地单实例通信不可用，已停止自动认证。"
            backend.status, backend.message = "error", backend.startup_error
    connections = []
    def incoming():
        while server.hasPendingConnections():
            connection = server.nextPendingConnection()
            connections.append(connection)
            connection.readyRead.connect(lambda c=connection: (c.readAll(), show_window(), c.disconnectFromServer()))
            if connection.bytesAvailable():
                connection.readAll(); show_window(); connection.disconnectFromServer()
            connection.disconnected.connect(lambda c=connection: (connections.remove(c) if c in connections else None, c.deleteLater()))
    server.newConnection.connect(incoming)

    class PowerEvents(QAbstractNativeEventFilter):
        def nativeEventFilter(self, event_type, message):
            from ctypes import wintypes
            msg = ctypes.cast(int(message), ctypes.POINTER(wintypes.MSG)).contents
            if msg.message == 0x218 and msg.wParam in (7, 18): backend.networkEvent.emit()
            if msg.message in (0x1A,0x31A): backend.refreshSystemAppearance()
            return False, 0
    native_events = PowerEvents()
    app.installNativeEventFilter(native_events)
    report = {"version": __version__, "qmlLoaded": True, "screenshots": [], "qmlWarnings": qml_warnings}
    if arguments.smoke_test:
        import ssl, decimal
        import certifi
        from .storage import CredentialStore
        credential_test = CredentialStore(directory / "synthetic-credential.dat")
        credential_test.save("synthetic-only", "001234-only-for-testing")
        report["dpapiRoundTrip"] = credential_test.read() == ("synthetic-only", "001234-only-for-testing")
        report["decimalAvailable"] = str(decimal.Decimal("1.25") * 2) == "2.50"
        report["sslAvailable"] = bool(ssl.OPENSSL_VERSION)
        tls_context = ssl.create_default_context(cafile=certifi.where())
        report["tlsVerificationEnabled"] = tls_context.check_hostname and tls_context.verify_mode == ssl.CERT_REQUIRED
        output = (arguments.smoke_output or Path.cwd() / "screenshots").resolve()
        output.mkdir(parents=True, exist_ok=True)
        window.show()
        pages = [("overview","overview"),("account","account"),("settings","settings"),("help","help")]
        def capture(index=0):
            if index == len(pages):
                (output / "smoke-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
                app.exit(0 if len(report["screenshots"]) == len(pages) and not qml_warnings and report["dpapiRoundTrip"] else 3)
                return
            page, filename = pages[index]
            window.setProperty("page", page)
            def save():
                if index == 0:
                    from PySide6.QtCore import QObject
                    report["layout"] = []
                    for item in window.findChildren(QObject):
                        text = item.property("text")
                        if isinstance(text, str) and ("自动守护" in text or "失败保护" in text):
                            chain = []
                            ancestor = item
                            while ancestor is not None and len(chain) < 7:
                                chain.append({"type": ancestor.metaObject().className(), **{key: ancestor.property(key) for key in ("x","y","width","height","opacity","visible")}})
                                ancestor = ancestor.parent()
                            report["layout"].append({"text": text[:40], "chain": chain})
                image = window.grabWindow()
                if not image.isNull() and image.save(str(output / (filename + ".png"))): report["screenshots"].append(filename)
                QTimer.singleShot(80, lambda: capture(index + 1))
            QTimer.singleShot(160, save)
        QTimer.singleShot(400, capture)
    else:
        if not arguments.background or not backend.settings.configured or backend.settings.migration_pending or backend.startup_error or not backend.trayAvailable:
            show_window()
        QTimer.singleShot(0, backend.initialize)
    try:
        return app.exec()
    finally:
        backend.cancel.set()
        backend.pool.waitForDone(120000)
        backend.cleanup()
        tray.hide()
        server.close()
        lock.__exit__(None, None, None)
        if mutex: kernel.CloseHandle(mutex)
        from PySide6.QtCore import QCoreApplication, QEvent
        window.deleteLater()
        engine.deleteLater()
        QCoreApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete)
        if temporary: temporary.cleanup()
