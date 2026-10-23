"""Isolated Qt controller tests: every network worker is replaced by a fake engine."""
from pathlib import Path
import threading
import time
from unittest.mock import patch, Mock
from PySide6.QtCore import QCoreApplication, QEvent
from test_desktop import TempTest
from ouc_desktop.storage import Settings, CredentialStore, StorageError
from ouc_desktop.engine import CheckResult
from ouc_desktop.controller import Controller
from ouc_desktop.request_policy import RequestPolicy

app=QCoreApplication.instance() or QCoreApplication([])
class ControllerTests(TempTest):
    def setUp(self):
        super().setUp()
        self.store=CredentialStore(self.directory/'credentials.dat')
        self.account=self.store.save('fake-user','001234-fake')
        Settings(configured=True,ssid='Fake-WiFi',profile='Fake-Profile',active_account_id=self.account,monitor=False).save(self.directory/'settings.json')
        self.startup_patch=patch('ouc_desktop.controller.StartupManager');self.startup_patch.start()
        self.engine_patch=patch('ouc_desktop.controller.MonitorEngine');self.engine=self.engine_patch.start()
        self.context_patch=patch('ouc_desktop.controller.current_context')
        context=self.context_patch.start().return_value
        context.key='synthetic-network';context.valid=True;context.ssid='Fake-WiFi'
        self.engine.return_value.check.return_value=CheckResult('online','synthetic online',online=True)
        self.controller=Controller(self.directory)
        self.controller.profiles=[{'id':'fake-wifi','ssid':'Fake-WiFi','profile':'Fake-Profile','interfaceId':'','label':'Fake-WiFi'}]
    def tearDown(self):
        self.controller.cancel.set();self.controller.timer.stop();self.controller.debounce.stop()
        self.controller.pool.waitForDone(3000);self.controller.cleanup();self.controller.deleteLater()
        QCoreApplication.sendPostedEvents(None,QEvent.Type.DeferredDelete)
        self.engine_patch.stop();self.startup_patch.stop();self.context_patch.stop();super().tearDown()
    def wait(self):
        deadline=time.monotonic()+3
        while time.monotonic()<deadline:
            app.processEvents()
            if not self.controller.busy and not self.controller.pending_check and not self.controller.pending_initialize:
                app.processEvents()
                if not self.controller.busy:break
            time.sleep(.005)
        self.assertFalse(self.controller.busy)
    def test_repeated_sources_share_one_worker_and_emit_on_gui_thread(self):
        main=threading.get_ident();worker_threads=[];gui_threads=[]
        self.controller.changed.connect(lambda:gui_threads.append(threading.get_ident()))
        def check(*args,**kwargs):
            worker_threads.append(threading.get_ident());time.sleep(.04)
            return CheckResult('online','fake',online=True,duration=8)
        self.engine.return_value.check.side_effect=check
        self.controller.check();self.controller.check();self.controller._network_event();self.wait()
        self.assertEqual(self.engine.return_value.check.call_count,1)
        self.assertNotEqual(worker_threads[0],main);self.assertTrue(all(t==main for t in gui_threads))
        self.assertFalse(self.controller.pending)
    def test_startup_detects_even_when_monitor_off_before_system_enumeration(self):
        order=[]
        self.engine.return_value.check.side_effect=lambda *args,**kwargs:(order.append('probe') or CheckResult('online','fake',online=True))
        with patch.object(self.controller,'_load_system',side_effect=lambda:order.append('system')):
            self.controller.initialize();self.wait()
        self.assertEqual(order,['probe','system']);self.assertFalse(self.controller.timer.isActive())
    def test_paused_startup_never_creates_engine(self):
        for _ in range(3):self.controller.policy.failure('fixture')
        with patch.object(self.controller,'_load_system'):
            self.controller.initialize()
        self.engine.assert_not_called();self.assertTrue(self.controller.blocked)
    def test_boot_lookup_does_not_block_recovery_and_record_is_applied_afterward(self):
        with patch('ouc_desktop.controller.boot_session') as boot:
            self.controller._offline()
            boot.assert_not_called()
        self.engine.return_value.check.return_value=CheckResult('online','restored',online=True,detected_offline=True,recovery_method='校园网认证恢复',duration=2)
        self.controller.check();self.wait()
        self.assertEqual(self.controller.data['recovery']['count'],0)
        with patch('ouc_desktop.controller.boot_session',return_value={'id':'boot','started_at':'2026-10-22T08:00:00+08:00'}),patch('ouc_desktop.controller.WifiAdapter') as wifi:
            wifi.return_value.profile_items.return_value=[];wifi.return_value.current.return_value=[]
            self.controller._load_system();self.wait()
        self.assertEqual(self.controller.data['recovery']['count'],1)
    def test_guard_toggle_and_account_selection_preserve_request_pause(self):
        for _ in range(3):self.controller.policy.failure('fixture')
        self.controller.toggleMonitor();self.controller.toggleMonitor();self.controller.selectAccount(self.account)
        self.assertTrue(self.controller.blocked);self.assertFalse(self.controller.timer.isActive())
    def test_busy_manual_retry_cannot_start_another_trial(self):
        self.engine.return_value.check.side_effect=lambda *args,**kwargs:(time.sleep(.04) or CheckResult('online','fake',online=True))
        self.controller.check();self.controller.retryRequests();self.wait()
        self.assertEqual(self.engine.return_value.check.call_count,1)
    def test_second_intervals_and_completion_based_timer(self):
        self.controller.initialized=True
        self.controller.toggleMonitor();self.controller.setInterval(1)
        self.engine.return_value.check.return_value=CheckResult('online','fake',online=True,duration=8)
        self.controller.check();self.wait()
        self.assertTrue(self.controller.timer.isSingleShot());self.assertGreater(self.controller.timer.remainingTime(),850)
        self.assertTrue(self.controller.data['intervalTooShort']);self.assertEqual(self.controller.data['interval'],1)
    def test_multi_account_password_retention_and_no_secret_in_ui(self):
        second=self.store.save('second','00234-secret')
        self.controller.accounts=self.store.summaries();self.controller.selectAccount(second)
        self.controller.saveAccount(second,'second','','keep','fake-wifi',True);self.wait()
        self.assertEqual(self.store.read(second),('second','00234-secret'))
        self.assertEqual(self.store.read(self.account),('fake-user','001234-fake'))
        self.assertNotIn('00234-secret',str(self.controller.data));self.assertTrue(self.controller.data['hasSavedPassword'])
    def test_save_password_does_not_clear_paused_policy_or_enable_monitor(self):
        for _ in range(3):self.controller.policy.failure('fixture')
        self.controller.saveAccount(self.account,'fake-user','new-secret','replace','fake-wifi',True);self.wait()
        self.assertTrue(self.controller.blocked);self.assertFalse(self.controller.settings.monitor)
    def test_partial_configuration_commit_blocks_authentication_after_restart(self):
        with patch.object(Settings,'save',side_effect=StorageError('synthetic write failure')):
            self.controller.saveAccount(self.account,'fake-user','new-secret','replace','fake-wifi',True);self.wait()
        self.assertTrue((self.directory/'configuration.pending.json').exists())
        restarted=Controller(self.directory)
        try:
            self.assertTrue(restarted.startup_error)
            restarted.check()
            deadline=time.monotonic()+2
            while restarted.busy and time.monotonic()<deadline:app.processEvents();time.sleep(.005)
            self.assertFalse(self.engine.return_value.check.call_args.args[3])
        finally:
            restarted.timer.stop();restarted.pool.waitForDone(3000);restarted.cleanup();restarted.deleteLater()
    def test_delete_last_account_disables_auth_configuration(self):
        self.controller.deleteAccount(self.account);self.wait()
        self.assertEqual(self.controller.accounts,[]);self.assertFalse(self.controller.settings.configured)
        self.assertFalse(self.controller.settings.monitor);self.assertEqual(self.store.read_all(),{})
    def test_metadata_pause_preserves_successful_recovery_statistics(self):
        self.controller.recoveries.set_boot({'id':'test','started_at':'2026-10-22T08:00:00+08:00'})
        self.controller.recoveries.observe_offline()
        self.engine.return_value.check.return_value=CheckResult('protected','fake',3,True,online=True,recovery_method='校园网认证恢复',duration=2,session_error='metadata timeout')
        self.controller.check();self.wait()
        self.assertTrue(self.controller.online);self.assertEqual(self.controller.data['recovery']['count'],1)
