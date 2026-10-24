"""Qt bridge: single worker, explicit intents, versioned data and current network."""
from __future__ import annotations
from dataclasses import replace
from decimal import Decimal, InvalidOperation
from pathlib import Path
import threading
import time
from PySide6.QtCore import QObject, Property, Signal, Slot, QTimer, QRunnable, QThreadPool, QUrl, Qt
from PySide6.QtGui import QDesktopServices, QGuiApplication
from PySide6.QtWidgets import QFileDialog
import auto_login as core
from .engine import MonitorEngine, CheckResult
from .request_policy import RequestPolicy, RequestClient, RequestFailure, RequestsPaused, now_text
from .portal_status import PortalClient
from .network import current_context, NetworkContext, BoundAdapter
from .recovery import RecoveryStore
from .storage import Settings, CredentialStore, EventStore, SnapshotStore, StorageError, configuration_transaction, write_json
from .windows import WifiAdapter, StartupManager, WindowsError, legacy_tasks, boot_session, high_contrast
from .migration import import_config, complete_migration
from . import __version__
from .project_info import AUTHOR_EMAIL, PROJECT_URL, RELEASES_URL, contact_url


class WorkerSignals(QObject):
    completed = Signal(object, object)
    progress = Signal(str, str)


class Worker(QRunnable):
    def __init__(self, function):
        super().__init__(); self.function = function; self.signals = WorkerSignals()
    def run(self):
        try: self.signals.completed.emit(self.function(self.signals.progress.emit), None)
        except Exception as error:
            safe = str(error) if isinstance(error, (StorageError, WindowsError, core.RetryStateError, RequestFailure, RequestsPaused)) else "当前操作未完成，请检查网络、权限或本地配置。"
            self.signals.completed.emit(None, safe)


class Controller(QObject):
    changed = Signal()
    notice = Signal(str)
    accountSaved = Signal()
    migrationReady = Signal()
    migrationCompleted = Signal()
    networkEvent = Signal()
    quitReady = Signal()
    notification = Signal(str, str)
    hiddenToTray = Signal()

    def __init__(self, directory: Path, demo=False, parent=None):
        super().__init__(parent)
        self.directory, self.demo = directory, demo
        self.pool = QThreadPool(self); self.pool.setMaxThreadCount(1)
        self.cancel = threading.Event()
        self.credentials = CredentialStore(directory / "credentials.dat")
        self.history = EventStore(directory)
        self.snapshots = SnapshotStore(directory / "portal-snapshot.dat")
        self.startup = StartupManager()
        self.legacy_lock = self.wifi = self.worker = self.completion = None
        self.busy = self.quitting = self.pending = self.pending_check = False
        self.pending_initialize = self.configuration_operation = False
        self.initialized = self.boot_attempted = False
        self.trayAvailable = self.close_explained = self.autostart = False
        self.tasks, self.profiles, self.accounts = [], [], []
        self.username = self.current_wifi = self.last_notification = self.startup_error = self.system_warning = ""
        self.connection_kind="Wi-Fi"
        self.system_high_contrast=high_contrast()
        self.status, self.message = "checking", "启动后立即检测外网连接。"
        self.online, self.network_epoch, self.network_key = None, 0, ""
        self.pending_intent = ""
        self.active_intent = ""
        self.pending_outage_at, self.pending_recovery = "", None
        self.pending_outage_key = ""
        self.last_check, self.last_duration = "尚未检查", 0.0
        self.session, self.devices, self.update_info = {}, {}, {}
        self.last_update_check = None
        self.policy = self.recoveries = None
        self.timer = QTimer(self); self.timer.setSingleShot(True); self.timer.timeout.connect(self.check)
        self.debounce = QTimer(self); self.debounce.setSingleShot(True); self.debounce.setInterval(250)
        self.debounce.timeout.connect(self._event_check)
        self.networkEvent.connect(self._network_event, Qt.ConnectionType.QueuedConnection)
        self.settings = Settings()
        try:
            self.settings = Settings.read(directory / "settings.json")
            self.policy = RequestPolicy(directory / "request-state.json")
            self.policy.scope('authentication')
            old = directory / "retry-state.json"
            if not self.policy.path.exists() and old.exists() and core.LoginRetryGuard(old).is_blocked(): self.policy.import_legacy_pause()
            if self.credentials.path.exists():
                self.accounts = self.credentials.summaries()
                # Only legacy single-account settings get a one-time explicit migration.
                if self.settings.active_account_id and self.settings.active_account_id not in [a["id"] for a in self.accounts]:
                    self.settings.active_account_id = ""
                self._selected_name()
            self.session = self.snapshots.read()
            if self.session: self.session["availability"] = "stale"
            if (directory / "configuration.pending.json").exists(): self.startup_error = "上次配置保存未完成，已停止认证，请重新保存账号。"
            if not demo: self.autostart = self.startup.enabled()
        except (StorageError, core.RetryStateError, OSError):
            self.policy=None
            self.startup_error = "本地设置、凭据或请求状态异常，请检查数据目录并重新保存配置。"
        try: self.recoveries = RecoveryStore(directory / "recoveries.json")
        except StorageError as error: self.system_warning = str(error)
        if demo: self._demo_setup()
        elif self.blocked: self.status, self.message = "protected", self._pause_message()

    def _demo_setup(self):
        self.settings = Settings(configured=True, active_account_id="demo1", monitor=True)
        self.accounts = [{"id":"demo1","username":"demo_2026001","label":"常用账号","revision":"demo1","hasSavedPassword":True},
                         {"id":"demo2","username":"demo_2026002","label":"备用账号","revision":"demo2","hasSavedPassword":True}]
        self._selected_name(); self.current_wifi = "OUC-Campus-Demo"
        self.network_key = "demo-network"
        self.online, self.status, self.message = True, "online", "演示模式：外网可用，未发送真实请求。"
        self.last_check = now_text()
        self.session = {"availability":"ready","account":"demo_2026001","online_since":"2026-10-23 08:42:16","source_ipv4":"192.0.2.18",
                        "source_ipv6":"2001:db8:85a3::8a2e:370:7334","source_mac":"02:00:5E:10:00:18","mobile_identifier":"PC端（0）",
                        "fetched_at":"2026-10-23T12:20:08+08:00","network_key":"demo-network","message":"模拟会话信息"}
        if self.recoveries:
            self.recoveries.set_boot({"id":"demo-boot","started_at":"2026-10-23T08:30:00+08:00"})
            for duration in (2.8, 3.2):
                self.recoveries.observe_offline(); self.recoveries.online(True,"校园网认证恢复",duration)

    @property
    def blocked(self): return self.policy is None or self.policy.recovery_paused
    @property
    def failures(self): return max(self.policy.failures,self.policy.scope('authentication').failures) if self.policy else 0
    def _pause_message(self):
        if self.policy is None: return "网络请求状态无法读取，已停止请求，请检查本地数据。"
        if self.policy.hard_paused:return self.policy.pause_message()
        if not self.settings.monitor and not self.policy.state['legacy_pause']:return '暂时暂停；自动守护已关闭，开启后将自动复检。'
        return self.policy.pause_message()
    def _selected_name(self):
        self.username = next((a["username"] for a in self.accounts if a["id"] == self.settings.active_account_id), "")
    def _credential_error(self):
        a = next((a for a in self.accounts if a["id"] == self.settings.active_account_id), {})
        return self.policy.credential_error(a.get("id", ""), a.get("revision", "legacy")) if self.policy else ""
    def _schedule(self):
        self.timer.stop()
        if (self.initialized and self.settings.monitor and not self.busy and not self.demo and not self.quitting
                and self.policy is not None and not self.policy.hard_paused):
            self.timer.start(max(1000,int(self.policy.retry_seconds*1000)+1) if self.blocked else self.settings.interval_ms)

    @Property("QVariantMap", notify=changed)
    def data(self):
        titles = {"checking":"正在检查连接…","authenticating":"正在恢复校园网…","session":"正在读取校园会话…",
                  "online":"外网可用","offline":"外网暂不可用","protected":"临时暂停，等待自动复检","setup":"请添加并选用账号",
                  "credentials":"需要更新账号密码","migration":"旧入口待处理","error":"需要处理设置","network":"请确认当前连接","unknown":"登录状态待确认","noncampus":"非校园网 · XHA 不可达","campusUnavailable":"校园服务暂不可用"}
        stale = self.session.get("availability") != "ready" or self.session.get("network_key") != self.network_key or self.online is not True or self.blocked
        policy_state = self.policy.state if self.policy else {}
        accounts = [dict(a, error=self.policy.credential_error(a["id"], a.get("revision", "legacy"))) for a in self.accounts] if self.policy else self.accounts
        return {"version":__version__,"authorEmail":AUTHOR_EMAIL,"projectUrl":PROJECT_URL,"releasesUrl":RELEASES_URL,"status":self.status,"title":titles.get(self.status,"连接概览"),"message":self.message,
                "busy":self.busy,"configurationBusy":self.busy and self.configuration_operation,
                "reconnecting":self.busy and self.active_intent=="reconnect","configured":self.settings.configured,"monitor":self.settings.monitor,
                "blocked":self.blocked,"failures":self.failures,"legacyPause":policy_state.get("legacy_pause",False),
                "retryAutomatic":bool(self.policy and not self.policy.hard_paused and self.settings.monitor),
                "pauseMessage":self._pause_message() if self.blocked else "", "lastFailureAt":policy_state.get("last_failure_at",""),
                "lastRequestError":policy_state.get("last_error",""),"online":self.online is True,"onlineKnown":self.online is not None,
                "ssid":"","profile":"","interfaceId":"","wifi":self.current_wifi or "正在确认当前连接","networkKind":self.connection_kind,
                "username":self.username,"accounts":accounts,"activeAccountId":self.settings.active_account_id,
                "accountNames":[a["username"] for a in self.accounts],"hasSavedPassword":bool(self.username),
                "credentialError":self._credential_error(),"interval":self.settings.interval_ms/1000,
                "appearance":self.settings.appearance,"systemHighContrast":self.system_high_contrast,"lastDuration":round(self.last_duration,2),
                "intervalTooShort":self.last_duration*1000>self.settings.interval_ms,"lastCheck":self.last_check,
                "session":self.session,"sessionStale":stale,"sessionLoading":self.status=="session",
                "recovery":self.recoveries.view() if self.recoveries else {"known":False,"count":0,"events":[],"startedAt":""},
                "requestStats":self.policy.stats.view() if self.policy else {"total":0,"recent":0,"startedAt":""},
                "devices":self.devices,"updateInfo":self.update_info,
                "systemWarning":self.system_warning,"notifications":self.settings.notifications,"closeToTray":self.settings.close_to_tray,
                "autostart":self.autostart,"tasks":self.tasks,"migrationPending":self.settings.migration_pending,
                "legacyDirectory":self.settings.legacy_directory,"demo":self.demo,"trayAvailable":self.trayAvailable,"dataDirectory":str(self.directory)}

    @Slot(result="QVariantList")
    def diagnosticEntries(self): return self.history.recent()
    @Slot()
    def refreshLocalView(self): self.changed.emit()
    @Slot()
    def refreshSystemAppearance(self):
        self.system_high_contrast=high_contrast();self.changed.emit()
    def _log(self, kind, message):
        try: self.history.add(kind,message)
        except StorageError: self.notice.emit("排障日志无法保存，请检查本地数据。")
    def _submit(self,function,completed,configuration=False):
        if self.busy or self.quitting: return False
        self.busy=True; self.cancel.clear(); self.timer.stop(); self.debounce.stop()
        self.completion,self.configuration_operation=completed,configuration
        self.worker=Worker(function)
        self.worker.signals.progress.connect(self._progress,Qt.ConnectionType.QueuedConnection)
        self.worker.signals.completed.connect(self._completed,Qt.ConnectionType.QueuedConnection)
        self.changed.emit();self.pool.start(self.worker);return True
    @Slot(str,str)
    def _progress(self,status,message):
        if self.cancel.is_set(): return
        self.status,self.message=status,message;self.changed.emit();self._log("stage",message)
    @Slot(object,object)
    def _completed(self,result,error):
        completed,configuration=self.completion,self.configuration_operation
        self.completion=None;self.worker=None;self.busy=False
        try:
            if error:
                if configuration:self.startup_error=error
                if self.active_intent=="devices":self.devices=dict(self.devices,message=error)
                if self.active_intent=="update":self.update_info={"message":error}
                self.status,self.message="error",error;self.notice.emit(error);self._log("warning",error)
            else:completed(result)
        except (RuntimeError,OSError,ValueError):
            self.startup_error="本地状态更新失败，已停止认证，请检查本地数据。"
            self.status,self.message="error",self.startup_error;self.notice.emit(self.message)
        self.changed.emit()
        if self.quitting:self.quitReady.emit();return
        if self.pending_initialize:
            self.pending_initialize=False;QTimer.singleShot(0,self._load_system);return
        if self.pending_intent:
            intent,self.pending_intent=self.pending_intent,""
            QTimer.singleShot(0,self._event_check if intent=="local" else lambda:self._run_check(intent));return
        if self.pending_check and not self.blocked:
            self.pending_check=False;QTimer.singleShot(0,self.check);return
        self.pending=self.pending_check=False
        self._schedule()

    @Slot()
    def initialize(self):
        if self.initialized or self.demo:return
        self.initialized=True;self.pending_initialize=True
        if self.blocked:
            self.status,self.message="protected",self._pause_message();self.pending_initialize=False;self._load_system()
        else:self.check()
    def _ensure_boot(self):
        if self.boot_attempted:return
        self.boot_attempted=True
        if self.recoveries:
            try:self.recoveries.set_boot(boot_session())
            except StorageError as error:self.system_warning=str(error)
    def _offline(self,network_key=""):
        if self.recoveries and self.recoveries.boot_known:self.recoveries.observe_offline(network_key=network_key)
        elif not self.pending_outage_at or self.pending_outage_key!=network_key:
            self.pending_outage_at=now_text();self.pending_outage_key=network_key
    def _load_system(self):
        def work(progress):
            self._ensure_boot()
            values={"tasks":[],"warning":"","lock":None,"adapter":None}
            try:
                wifi=WifiAdapter();values["adapter"]=wifi;wifi.subscribe(self.networkEvent.emit)
            except (RuntimeError,OSError):values["warning"]="Wi-Fi 名称或通知不可用，可在系统设置检查 WLAN 与位置权限。"
            if self.settings.legacy_directory:
                try:
                    values["tasks"]=legacy_tasks(Path(self.settings.legacy_directory))
                    if not self.settings.migration_pending and not any(t["enabled"] for t in values["tasks"]):
                        lock=core.InstanceLock(Path(self.settings.legacy_directory)/".auto_login.lock");lock.__enter__();values["lock"]=lock
                except (RuntimeError,OSError):values["warning"]="旧入口尚未核对，校园认证保持暂停。"
            return values
        def done(value):
            self.tasks,self.wifi=value["tasks"],value["adapter"]
            self.system_warning=value["warning"] or self.system_warning;self.legacy_lock=value["lock"]
            if self.recoveries and self.recoveries.boot_known:
                if self.pending_outage_at:self.recoveries.observe_offline(self.pending_outage_at,self.pending_outage_key)
                if self.online:
                    r=self.pending_recovery or {};self.recoveries.online(bool(r),r.get("method",""),r.get("duration",0),self.network_key)
                self.pending_outage_at="";self.pending_recovery=None
            if self.settings.legacy_directory and not self.legacy_lock:
                self.settings=replace(self.settings,migration_pending=True,monitor=False)
            self.settings.save(self.directory/"settings.json")
            if self.legacy_lock:
                write_json(Path(self.settings.legacy_directory)/"desktop-managed.json",{"version":2,"mode":"desktop-managed","data_directory":str(self.directory.resolve())})
                if self.online is False:self.pending_check=True
        self._submit(work,done)

    @Slot()
    def check(self):self._run_check("auto")
    @Slot()
    def reconnect(self):self._run_check("reconnect",retry=self.blocked)
    @Slot()
    def connectTarget(self):self.reconnect()
    @Slot()
    def refreshSession(self):self._run_check("refresh")
    @Slot()
    def retryRequests(self):
        if not self.busy:self._run_check("reconnect",retry=True)
    def _check(self,connect=False,fetch_info=False,retry=False):
        self._run_check("reconnect" if connect or retry else "refresh" if fetch_info else "auto",retry)
    def _run_check(self,intent,retry=False):
        if self.demo:self.notice.emit("演示模式不发送真实请求。");return
        if self.busy:
            if intent!="auto" and self.active_intent=="auto" and not self.pending_intent:self.pending_intent=intent
            self.pending=True;return
        if self.blocked and not retry and (self.policy is None or self.policy.hard_paused or self.policy.retry_seconds>0):
            self.status,self.message="protected",self._pause_message();self.changed.emit();self._schedule();return
        if self.policy is None:return
        options,epoch=replace(self.settings),self.network_epoch
        account=next((a for a in self.accounts if a["id"]==options.active_account_id),{})
        allow_auth=not self.startup_error and not options.migration_pending and (not options.legacy_directory or self.legacy_lock is not None)
        credential_allowed=not self._credential_error()
        self.active_intent=intent
        def work(progress):
            try:context=current_context()
            except (WindowsError,OSError):context=NetworkContext(reason="无法确认当前接口，请检查网络服务与权限。")
            config=None
            if options.active_account_id:
                try:
                    username,password=self.credentials.read(options.active_account_id)
                    config=core.Config(username,password,"","",max(1,options.interval_ms//1000))
                except StorageError:pass
            if retry:
                self.policy.begin_trial();self.policy.scope('authentication').begin_trial()
            try:
                result=MonitorEngine(self.policy,cancel=self.cancel,on_offline=lambda:self._offline(context.key)).check(
                    config,False,False,allow_auth,progress,False,"",credential_allowed,intent=intent,context=context)
                return result,context
            finally:
                self.policy.trial=False;self.policy.scope('authentication').trial=False
        def done(value):
            result,context=value
            if epoch!=self.network_epoch or self.cancel.is_set():
                self.message="连接已变化，旧操作结果已丢弃。";return
            if context.key!=self.network_key:
                self.devices={};self.network_key=context.key
                if self.session:self.session=dict(self.session,availability="stale")
            self.current_wifi=context.ssid or context.name if context.valid else context.reason
            self.connection_kind=context.kind
            self.status,self.message=result.status,result.message
            if result.status=="noncampus" and self.session.get("network_key")==context.key and self.session.get("account"):
                self.status="campusUnavailable";self.message="校园服务暂不可用，请稍后手动重试。"
            if result.online is not None:self.online=result.online
            self.last_check,self.last_duration=now_text(),result.duration
            if result.credential_rejected:
                self.policy.reject_credential(options.active_account_id,account.get("revision","legacy"),result.credential_message)
            elif result.credential_confirmed:self.policy.clear_credential(options.active_account_id)
            if self.recoveries and result.online:
                if self.recoveries.boot_known:self.recoveries.online(bool(result.recovery_method),result.recovery_method,result.duration,self.network_key)
                elif result.recovery_method:self.pending_recovery={"method":result.recovery_method,"duration":result.duration}
            if result.session:
                if result.session.get("account")!=self.session.get("account"):self.devices={}
                self.session=dict(result.session,network_key=self.network_key)
                self.snapshots.save(self.session)
            if result.session_error:
                if self.session:self.session=dict(self.session,message=result.session_error)
                else:self.session={"availability":"unavailable","message":result.session_error}
            if result.online is False and self.session:self.session=dict(self.session,availability="stale")
            if self.blocked:self.pending=self.pending_check=False;self.pending_intent=""
            self._log("success" if result.online else "warning",result.message)
            title=result.credential_message if result.credential_rejected else "网络暂时暂停，将自动复检" if self.blocked else "网络已恢复" if result.recovery_method else ""
            event_key=title+options.active_account_id+account.get("revision","")
            if result.recovery_method:
                events=self.recoveries.view()["events"] if self.recoveries else []
                event_key+=events[0]["id"] if events else now_text()
            elif self.blocked:event_key+=self.policy.state.get("last_failure_at","")
            if title and self.settings.notifications and event_key!=self.last_notification:
                self.notification.emit(title,"打开 OUNet 查看原因与下一步。");self.last_notification=event_key
        self._submit(work,done)

    @Slot()
    def toggleMonitor(self):
        if self.busy:return
        try:
            updated=replace(self.settings,monitor=not self.settings.monitor)
            if not self.demo:updated.save(self.directory/"settings.json")
            self.settings=updated;self.pending=False;self._schedule();self.changed.emit()
        except StorageError as error:self.notice.emit(str(error))
    @Slot(str)
    def selectAccount(self,account_id):
        if self.busy or account_id not in [a["id"] for a in self.accounts]:return
        try:
            updated=replace(self.settings,active_account_id=account_id)
            if not self.demo:updated.save(self.directory/"settings.json")
            self.settings=updated;self._selected_name();self.changed.emit()
            if not self.blocked:self.check()
        except StorageError as error:self.notice.emit(str(error))
    @Slot(str,str,str,str,str,bool)
    def saveAccount(self,account_id,username,password,action,label,acknowledged):
        if self.busy:return
        if self.demo:self.notice.emit("演示模式不保存凭据。");return
        if not self.settings.configured and not acknowledged and not self.settings.legacy_directory:
            self.notice.emit("请确认旧入口已停止，或导入旧配置完成迁移。");return
        updated=replace(self.settings,configured=True)
        def work(progress):
            old=next((a for a in self.accounts if a["id"]==account_id),{})
            with configuration_transaction(self.directory):
                saved=self.credentials.put(account_id,username,password,action,label)
                # Editing does not silently select a different account.
                if not updated.active_account_id and len(self.accounts)==0:updated.active_account_id=saved
                updated.save(self.directory/"settings.json")
            accounts=self.credentials.summaries()
            new=next(a for a in accounts if a["id"]==saved)
            if old.get("revision","legacy")!=new["revision"]:self.policy.clear_credential(saved)
            return accounts
        def done(accounts):
            self.accounts,self.settings=accounts,updated;self._selected_name();self.startup_error=""
            self.accountSaved.emit();self.notice.emit("账号已加密保存。选择账号只影响下次认证。")
        self._submit(work,done,configuration=True)
    @Slot(str)
    def deleteAccount(self,account_id):
        if self.busy or self.demo:return
        updated=replace(self.settings)
        def work(progress):
            with configuration_transaction(self.directory):
                self.credentials.remove(account_id)
                if account_id==updated.active_account_id:updated.active_account_id=""
                updated.configured=bool(updated.active_account_id)
                if not updated.active_account_id:updated.monitor=False
                updated.save(self.directory/"settings.json")
            self.policy.clear_credential(account_id)
            return self.credentials.summaries()
        def done(accounts):
            self.accounts,self.settings=accounts,updated;self._selected_name();self.accountSaved.emit()
            self.notice.emit("已删除本机账号，网站当前会话不受影响。")
        self._submit(work,done,configuration=True)
    @Slot(str,bool)
    def setOption(self,key,value):
        if self.busy:return
        try:
            if key=="autostart":
                if not self.demo:self.startup.set(value)
                self.autostart=value
            else:
                mapping={"notifications":"notifications","closeToTray":"close_to_tray"}
                if key not in mapping:return
                updated=replace(self.settings,**{mapping[key]:value})
                if not self.demo:updated.save(self.directory/"settings.json")
                self.settings=updated
            self.changed.emit()
        except (StorageError,RuntimeError) as error:self.notice.emit(str(error));self.changed.emit()
    @Slot(str)
    def setAppearance(self,value):
        if self.busy:return
        if value not in ("system","light","dark"):return
        try:
            updated=replace(self.settings,appearance=value)
            if not self.demo:updated.save(self.directory/"settings.json")
            self.settings=updated;self.changed.emit()
        except StorageError as error:self.notice.emit(str(error))
    @Slot(str)
    def setIntervalText(self,value):
        if self.busy:return
        try:
            number=Decimal(value)
            if not number.is_finite() or not Decimal("0.3")<=number<=Decimal("2147483") or number*1000!=(number*1000).to_integral_value():raise ValueError
            updated=replace(self.settings,interval_ms=int(number*1000),interval=float(number))
            if not self.demo:updated.save(self.directory/"settings.json")
            self.settings=updated
            if not self.busy:self._schedule()
            self.changed.emit()
        except (InvalidOperation,ValueError):self.notice.emit("请输入 0.3–2147483 秒，最多三位小数；原设置保持。")
        except StorageError as error:self.notice.emit(str(error))
    @Slot(float)
    def setInterval(self,seconds):self.setIntervalText(str(seconds))

    @Slot(bool)
    def loadDevices(self,more=False):
        if self.busy:return
        if self.blocked:self.notice.emit(self._pause_message());return
        if self.demo:
            self.devices={"account":"demo_2026001","fetchedAt":now_text(),"complete":True,"next":3,
                          "items":[{"key":"demo-pc","owner":True,"ip":"192.0.2.18","ipv6":"2001:db8::18","mac":"02:00:5E:10:00:18","since":"今天 08:42","mobile":"PC 端"},
                                   {"key":"demo-phone","owner":False,"ip":"192.0.2.26","ipv6":"页面未提供","mac":"页面未提供","since":"今天 10:15","mobile":"移动端"}],"message":"全部为演示数据。"}
            self.changed.emit();return
        epoch=self.network_epoch
        self.active_intent="devices"
        previous=dict(self.devices) if more else {}
        if not more:self.devices={}
        def work(progress):
            context=current_context()
            if not context.valid:raise WindowsError(context.reason)
            with core.create_session() as session:
                portal=PortalClient(RequestClient(session,self.policy,cancel=self.cancel,adapter=BoundAdapter(context),network_guard=context.unchanged))
                status=portal.login_status()
                if status["state"]!="online" or not status.get("uid"):
                    return {"items":[],"complete":False,"message":"尚未确认 XHA 在线身份，请先刷新信息。"}
                uid=str(status["uid"])
                if more and (previous.get("account")!=uid or previous.get("networkKey")!=context.key):
                    return {"items":[],"complete":False,"message":"在线身份或连接已改变，请刷新设备列表。"}
                start=previous.get("next",1)
                rows,ip,mac=portal.records(status,start,20)
                total=portal.records_total
                if not rows and total>=start:raise WindowsError("设备分页尚未完整，请重试。")
                items=list(previous.get("items",[]))
                owner_rows=[r for r in rows if str(r.get("is_owner_ip"))=="1"]
                for row in rows:
                    value=lambda key:str(row.get(key) or "页面未提供")[:256]
                    key="|".join(value(k) for k in ("login_ip","mac_address","login_time"))
                    if any(r["key"]==key for r in items):continue
                    items.append({"key":key,"owner":len(owner_rows)==1 and row is owner_rows[0],"ip":value("login_ip"),
                                  "ipv6":value("login_ip_v6"),"mac":value("mac_address"),"since":value("login_time"),
                                  "mobile":{"0":"PC 端（0）","1":"移动端（1）"}.get(str(row.get("phone_flag")),value("phone_flag"))})
                if more and rows and len(items)==len(previous.get("items",[])):
                    raise WindowsError("设备分页未推进，已保留已读取记录，请刷新后重试。")
                next_row=start+len(rows)
                complete=next_row-1>=total if total>=0 else len(rows)<20
                incomplete=complete and total>=0 and len(items)<total
                return {"account":uid,"networkKey":context.key,"items":sorted(items,key=lambda i:not i["owner"]),
                        "next":0 if incomplete else next_row,"complete":complete and not incomplete,"fetchedAt":now_text(),
                        "message":"列表在分页期间发生变化，尚未全部读取，请刷新。" if incomplete else ""}
        def done(value):
            if epoch==self.network_epoch and not self.cancel.is_set():self.devices=value
        self._submit(work,done)

    @Slot()
    def checkUpdate(self):
        if self.busy:return
        if self.policy is None:self.notice.emit('本地请求状态异常，可打开 GitHub 下载页。');return
        if self.demo:self.update_info={"message":"演示模式不检查远程版本。"};self.changed.emit();return
        now=time.monotonic()
        if self.last_update_check is not None and now-self.last_update_check<60:
            self.notice.emit(f"请在 {max(1,int(60-(now-self.last_update_check)))} 秒后重新检查。")
            return
        self.last_update_check=now
        from .update import check_update
        self.active_intent="update";self.update_info={"state":"loading","message":"正在检查 Windows 正式版本…"}
        def work(progress):
            try:
                with core.create_session() as session:return check_update(RequestClient(session,self.policy,cancel=self.cancel))
            except (RequestFailure,RequestsPaused) as error:
                return {"state":"error","message":str(error)+" 可打开 GitHub 下载页。","checkedAt":now_text()}
        def done(value):self.update_info=value
        self._submit(work,done)

    @Slot()
    def openUpdate(self):
        from .update import validate_metadata
        try:
            value=validate_metadata(dict(self.update_info,platform="windows-x64"))
            if value["newer"] and not QDesktopServices.openUrl(QUrl(value["url"])):
                self.notice.emit("未能打开浏览器，可从 GitHub 下载页获取安装包。")
        except ValueError:self.notice.emit("尚无可验证的 Windows 下载地址，请先检查更新。")

    @Slot(str)
    def openProjectLink(self, name):
        targets={"source":PROJECT_URL,"releases":RELEASES_URL,"email":contact_url(__version__)}
        if name not in targets:return
        if not QDesktopServices.openUrl(QUrl(targets[name])):
            self.notice.emit("未能打开邮件应用，可复制邮箱后联系作者。" if name=="email" else "未能打开浏览器，可复制地址后访问。")

    @Slot(str)
    def copyProjectInfo(self, name):
        values={"email":AUTHOR_EMAIL,"source":PROJECT_URL,"releases":RELEASES_URL}
        if name not in values:return
        clipboard=QGuiApplication.clipboard()
        if clipboard is not None:clipboard.setText(values[name]);self.notice.emit("已复制。")

    @Slot()
    def importLegacy(self):
        if self.demo or self.busy:return
        path,_=QFileDialog.getOpenFileName(None,"选择原脚本目录中的 .env","","环境配置 (.env);;所有文件 (*)")
        if not path:return
        def done(value):
            self.settings,self.tasks=value["settings"],value["tasks"]
            self.accounts=self.credentials.summaries();self._selected_name()
            self.policy=RequestPolicy(self.directory/"request-state.json");self.startup_error=""
            self.status,self.message="migration","配置已导入，请核对旧入口。"
            if value["scan_error"]:self.notice.emit(value["scan_error"])
            self.migrationReady.emit()
        self._submit(lambda progress:import_config(path,self.directory,self.settings,self.credentials),done,True)
    @Slot()
    def rescanLegacy(self):
        if self.demo or not self.settings.legacy_directory:return
        def done(tasks):self.tasks=tasks;self.migrationReady.emit()
        self._submit(lambda progress:legacy_tasks(Path(self.settings.legacy_directory)),done)
    @Slot(bool)
    def completeMigration(self,disable):
        if self.demo or not self.settings.legacy_directory:return
        selected=[t for t in self.tasks if t["enabled"]] if disable else []
        def done(value):
            self.settings,self.legacy_lock=value["settings"],value["lock"]
            self.policy=RequestPolicy(self.directory/"request-state.json");self.startup_error=""
            self.tasks=[dict(t,enabled=False) for t in self.tasks];self.migrationCompleted.emit();self.pending_check=not self.blocked
        self._submit(lambda progress:complete_migration(self.directory,self.settings,selected,self.legacy_lock),done,True)
    @Slot()
    def exportLogs(self):
        path,_=QFileDialog.getSaveFileName(None,"导出脱敏排障信息","OUNet-排障信息.txt","文本文件 (*.txt)")
        if not path:return
        try:
            secrets=[self.settings.ssid,self.settings.profile]
            if self.credentials.path.exists():
                for account in self.credentials.read_all().values():secrets.extend(str(v) for v in account.values())
            self.history.export(Path(path),tuple(secrets));self.notice.emit("已导出脱敏排障信息。")
        except StorageError as error:self.notice.emit(str(error))
    @Slot(str)
    def openLocation(self,name):
        targets={"data":QUrl.fromLocalFile(str(self.directory)),"network":QUrl("ms-settings:network-wifi"),"location":QUrl("ms-settings:privacy-location"),"startup":QUrl("ms-settings:startupapps")}
        if name in targets:QDesktopServices.openUrl(targets[name])
    @Slot()
    def _network_event(self):
        self.network_epoch+=1;self.devices={};self.online=None;self.network_key=""
        if self.session:self.session=dict(self.session,availability="stale")
        if self.busy:
            self.cancel.set();self.pending_intent="local"
        else:self.timer.stop();self.debounce.start()
        self.changed.emit()
    @Slot()
    def _event_check(self):
        if self.settings.monitor and self.policy is not None and not self.policy.hard_paused:
            try:self.policy.expedite()
            except StorageError as error:
                self.status,self.message='error',str(error);self.notice.emit(self.message);self.changed.emit();return
            self.check()
        else:
            def done(context):self.current_wifi=context.ssid or context.name if context.valid else context.reason
            self._submit(lambda progress:current_context(),done)
    @Slot()
    def quit(self):
        self.quitting=True;self.timer.stop();self.debounce.stop();self.pending=self.pending_check=False;self.pending_intent="";self.cancel.set()
        if not self.busy:self.quitReady.emit()
    @Slot()
    def windowHidden(self):
        if not self.close_explained and self.trayAvailable:self.close_explained=True;self.hiddenToTray.emit()
    def cleanup(self):
        if self.wifi:self.wifi.close()
        if self.legacy_lock:self.legacy_lock.__exit__(None,None,None);self.legacy_lock=None
