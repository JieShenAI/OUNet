"""Controller races and upgrade rules; all workers and adapters are mocked."""
import threading
import time
from unittest.mock import patch
from PySide6.QtCore import QCoreApplication,QEvent
from test_desktop import TempTest
from ouc_desktop.controller import Controller
from ouc_desktop.storage import CredentialStore,Settings
from ouc_desktop.engine import CheckResult

app=QCoreApplication.instance() or QCoreApplication([])


class ControllerV03(TempTest):
    def setUp(self):
        super().setUp()
        self.credentials=CredentialStore(self.directory/'credentials.dat')
        self.first=self.credentials.put('','demo','001234','replace','first')
        self.second=self.credentials.put('','another','002345','replace','second')
        Settings(configured=True,active_account_id=self.first).save(self.directory/'settings.json')
        self.patches=[patch('ouc_desktop.controller.StartupManager'),patch('ouc_desktop.controller.MonitorEngine'),patch('ouc_desktop.controller.current_context')]
        _,self.engine,network=[p.start() for p in self.patches]
        self.engine.return_value.check.return_value=CheckResult('online','fixture',online=True)
        context=network.return_value;context.key='fake';context.ssid='Fake-WiFi';context.valid=True;context.kind='Wi-Fi'
        self.controller=Controller(self.directory)

    def tearDown(self):
        self.controller.cancel.set();self.controller.timer.stop();self.controller.debounce.stop()
        self.controller.pool.waitForDone(3000);self.controller.cleanup();self.controller.deleteLater()
        QCoreApplication.sendPostedEvents(None,QEvent.Type.DeferredDelete)
        for p in self.patches:p.stop()
        super().tearDown()

    def wait(self):
        deadline=time.monotonic()+3
        while time.monotonic()<deadline:
            app.processEvents();time.sleep(.003)
            if not self.controller.busy and not self.controller.pending_intent:
                app.processEvents()
                if not self.controller.busy:break
        self.assertFalse(self.controller.busy)

    def test_network_change_discards_old_identity_and_does_not_start_http_when_monitor_off(self):
        self.engine.return_value.check.side_effect=lambda *a,**k:(time.sleep(.04) or CheckResult('online','late',online=True,session={'account':'stale-account','availability':'ready'}))
        self.controller.check();self.controller._network_event();self.wait()
        self.assertNotEqual(self.controller.session.get('account'),'stale-account')
        self.assertEqual(self.engine.return_value.check.call_count,1);self.assertIsNone(self.controller.online)

    def test_auto_can_be_followed_by_only_one_pending_user_intent(self):
        self.engine.return_value.check.side_effect=lambda *a,**k:(time.sleep(.03) or CheckResult('online','fake',online=True))
        self.controller.check();self.controller.reconnect();self.controller.reconnect();self.wait()
        calls=self.engine.return_value.check.call_args_list
        self.assertEqual([c.kwargs['intent'] for c in calls],['auto','reconnect'])

    def test_repeated_user_clicks_do_not_queue_another_login(self):
        self.engine.return_value.check.side_effect=lambda *a,**k:(time.sleep(.03) or CheckResult('online','fake',online=True))
        self.controller.reconnect();self.controller.reconnect();self.controller.reconnect();self.wait()
        self.assertEqual(self.engine.return_value.check.call_count,1)

    def test_delete_selected_account_does_not_select_another(self):
        self.controller.deleteAccount(self.first);self.wait()
        self.assertEqual(self.controller.settings.active_account_id,'')
        self.assertTrue(any(a['id']==self.second for a in self.controller.accounts))
        self.assertFalse(self.controller.settings.monitor)

    def test_same_password_and_rename_do_not_clear_credential_block(self):
        revision=self.controller.accounts[0]['revision']
        self.controller.policy.reject_credential(self.first,revision,'密码错误')
        self.controller.saveAccount(self.first,'demo','001234','replace','renamed',True);self.wait()
        self.assertEqual(self.controller.data['credentialError'],'密码错误')
        self.controller.saveAccount(self.first,'demo','new-secret','replace','renamed',True);self.wait()
        self.assertEqual(self.controller.data['credentialError'],'')

    def test_fractional_validation_keeps_last_value(self):
        self.controller.setIntervalText('0.3');self.assertEqual(self.controller.settings.interval_ms,300)
        for invalid in ('0.2999','nan','Infinity','2147483.001','not-number','0'):
            self.controller.setIntervalText(invalid);self.assertEqual(self.controller.settings.interval_ms,300)

    def test_snapshot_time_unchanged_after_auto_success(self):
        self.controller.network_key='fake'
        self.controller.session={'account':'actual','availability':'ready','network_key':'fake','fetched_at':'old-time'}
        self.controller.check();self.wait()
        self.assertEqual(self.controller.session['fetched_at'],'old-time')

    def test_paused_read_only_entries_cannot_dispatch(self):
        for _ in range(3):self.controller.policy.failure('fixture')
        self.controller.refreshSession();self.controller.loadDevices(False);self.controller.checkUpdate()
        self.assertFalse(self.controller.busy);self.engine.assert_not_called()
