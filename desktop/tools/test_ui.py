"""Real QML checks with synthetic data; no network, settings or credentials touched."""
import json
import os
from pathlib import Path
import sys
import tempfile
os.environ["QT_QPA_PLATFORM"]="offscreen"
os.environ["QT_QUICK_BACKEND"]="software"
ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT),str(ROOT.parent)]
from PySide6.QtCore import QObject,QUrl,QCoreApplication,QEvent,QMetaObject,Q_ARG
from PySide6.QtGui import QFontDatabase
from PySide6.QtWidgets import QApplication
from PySide6.QtQml import QQmlApplicationEngine
from PySide6.QtQuick import QQuickWindow
from PySide6.QtTest import QTest
from ouc_desktop.controller import Controller


def main():
    app=QApplication([])
    for name in ("msyh.ttc","msyhbd.ttc","segoeui.ttf"):
        QFontDatabase.addApplicationFont(str(Path(os.environ["WINDIR"])/"Fonts"/name))
    output=ROOT/"screenshots"/"v0.3-ui";output.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output) as directory:
        backend=Controller(Path(directory),demo=True)
        engine=QQmlApplicationEngine();warnings=[]
        engine.warnings.connect(lambda values:warnings.extend(v.toString() for v in values))
        engine.rootContext().setContextProperty("Backend",backend)
        engine.load(QUrl.fromLocalFile(str(ROOT/"qml"/"Main.qml")))
        assert engine.rootObjects(),"QML failed to load"
        window=engine.rootObjects()[0];window.show()
        def settle():
            for _ in range(8):app.processEvents()
        def find(name):
            value=window.findChild(QObject,name)
            if value is None:
                stack=[window.contentItem()]
                while stack:
                    node=stack.pop()
                    if node.objectName()==name:value=node;break
                    stack.extend(node.childItems())
            assert value is not None,name
            return value
        def invoke(target,method,*args):
            assert QMetaObject.invokeMethod(target,method,*[Q_ARG("QVariant",a) for a in args]),method
            settle()
        def capture(name):
            settle();QTest.qWait(240)
            find("noticeBox").setProperty("visible",False);settle()
            assert window.grabWindow().save(str(output/(name+".png")))
        settle()
        window.setWidth(1440);window.setHeight(1120);capture("overview-desktop")
        window.setWidth(1200);window.setHeight(820);settle()
        choice=find("accountChoice");choice.setProperty("currentIndex",1)
        QMetaObject.invokeMethod(choice,"activated",Q_ARG(int,1));settle()
        assert backend.data["activeAccountId"]=="demo2"
        assert backend.session['account']=='demo_2026001',"Local selection changed actual online identity"
        backend.busy=True;backend.changed.emit();settle();assert not choice.property("enabled")
        backend.busy=False;backend.changed.emit();settle()
        window.setProperty("page","account");settle()
        invoke(find("accountEditor"),"load","demo2")
        assert find("accountUsername").property("text")=="demo_2026002"
        assert find("accountPassword").property("text")==""
        assert find("accountPassword").property("placeholderText")=="********"
        # Editing stays untouched while background status notifications refresh.
        find("accountLabel").setProperty("text","unsaved remark")
        backend.changed.emit();settle();assert find("accountLabel").property("text")=="unsaved remark"
        invoke(find("accountEditor"),"load","demo2")
        window.setProperty("page","settings");settle()
        find("intervalInput").setProperty("text","0.3")
        QMetaObject.invokeMethod(find("applyIntervalButton"),"clicked");settle()
        assert backend.settings.interval_ms==300
        backend.last_duration=2.4;backend.changed.emit();capture("short-interval")
        backend.setAppearance("dark");settle();assert window.property("color").name()=="#101925"
        capture("dark-settings");backend.setAppearance("light")
        window.setProperty("page","overview");settle()
        button=find("reconnectButton")
        labels=[obj for obj in button.findChildren(QObject) if obj.property("text")=="重新检测并连接"]
        assert any(obj.property("color").name()=="#ffffff" for obj in labels if obj.property("color") is not None),"Primary button text contrast"
        backend.policy.reject_credential("demo2","demo2","密码错误");backend.changed.emit();capture("password-error")
        for _ in range(3):backend.policy.failure("模拟网络超时")
        backend.status="protected";backend.changed.emit();settle();assert find("retryRequestsButton").property("visible")
        backend.toggleMonitor();backend.toggleMonitor();assert backend.blocked
        capture("requests-paused")
        backend.policy.success();backend.policy.clear_credential("demo2");backend.changed.emit()
        invoke(window,"openDetails","devices")
        assert backend.devices.get('items'),"Device drawer not connected to backend"
        capture("online-devices")
        drawers=[obj for obj in window.findChildren(QObject) if 'Drawer' in obj.metaObject().className() and obj.property('position') is not None]
        assert any(obj.property('position')>.99 for obj in drawers),'Device drawer did not become visibly open'
        # Closing on route change keeps four primary pages and no history navigation.
        invoke(window,"go","help")
        backend._log('info','first synthetic record');backend._log('warning','latest synthetic record')
        invoke(window,"openDetails","diagnostics")
        entries=window.property('logEntries')
        if hasattr(entries,'toVariant'):entries=entries.toVariant()
        assert entries[0]['message']=='latest synthetic record'
        capture("diagnostics")
        invoke(window,"go","overview")
        backend.session["source_ipv6"]="2001:0db8:1234:5678:90ab:cdef:1234:5678";backend.changed.emit()
        for width in (1440,1200,1008,820,640):
            window.setWidth(width);window.setHeight(980 if width>=1200 else 780)
            find("noticeBox").setProperty("visible",False)
            capture("overview-"+str(width))
            field=find("session_source_ipv6")
            assert field.property('width')>100 and field.property('width')<width
        assert not warnings,'\n'.join(warnings)
        report={"accountSelectionDoesNotChangeOnlineIdentity":True,"passwordPlaceholderOnly":True,
                "busyDisablesAccount":True,"unsavedInputSurvivesRefresh":True,"fractionalSeconds":True,
                "pausePersists":True,"primaryTextContrast":True,"darkTheme":True,"deviceDrawer":True,
                "latestLogsFirst":True,"widths":[1440,1200,1008,820,640],"qmlWarnings":warnings,
                "realNetwork":False,"realUserData":False}
        (output/'ui-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        window.deleteLater();engine.deleteLater()
        QCoreApplication.sendPostedEvents(None,QEvent.Type.DeferredDelete);backend.cleanup()
    print('Real QML v0.3 interaction checks passed (isolated, no network).')


if __name__=='__main__':main()
