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

    def test_only_explicit_reconnect_changes_reconnect_button_progress(self):
        self.engine.return_value.check.side_effect=lambda *a,**k:(time.sleep(.03) or CheckResult('online','fixture',online=True))
        for action,expected in ((self.controller.check,False),(self.controller.refreshSession,False),(self.controller.reconnect,True)):
            action()
            self.assertTrue(self.controller.data['busy'])
            self.assertEqual(self.controller.data['reconnecting'],expected)
            self.wait()
            self.assertFalse(self.controller.data['reconnecting'])

    def test_delete_selected_account_does_not_select_another(self):
        self.controller.deleteAccount(self.first);self.wait()
        self.assertEqual(self.controller.settings.active_account_id,'')
        self.assertTrue(any(a['id']==self.second for a in self.controller.accounts))
        self.assertFalse(self.controller.settings.monitor)

    def test_only_configuration_work_locks_account_editing(self):
        # Block the worker deliberately so assertions observe a real in-flight task.
        released=threading.Event()
        try:
            self.assertTrue(self.controller._submit(lambda progress:released.wait(2),lambda result:None))
            self.assertTrue(self.controller.data['busy'])
            self.assertFalse(self.controller.data['configurationBusy'])
            released.set();self.wait();released.clear()
            self.assertTrue(self.controller._submit(lambda progress:released.wait(2),lambda result:None,configuration=True))
            self.assertTrue(self.controller.data['configurationBusy'])
            released.set();self.wait()
            self.assertFalse(self.controller.data['configurationBusy'])
        finally:released.set()

    def test_cooldown_keeps_timer_and_resumes_without_network_event(self):
        self.controller.initialized=True;self.controller.settings.monitor=True
        for _ in range(3):self.controller.policy.failure('router unavailable')
        self.controller._schedule()
        self.assertTrue(self.controller.timer.isActive())
        self.assertGreater(self.controller.timer.interval(),13000)
        self.controller.check();self.engine.assert_not_called()
        self.controller.policy.deadline=time.monotonic()-1
        def restored(*args,**kwargs):
            self.controller.policy.success();return CheckResult('online','restored',online=True)
        self.engine.return_value.check.side_effect=restored
        self.controller.check();self.wait()
        self.engine.return_value.check.assert_called_once()
        self.assertFalse(self.controller.blocked);self.assertEqual(self.controller.timer.interval(),1000)

    def test_network_events_allow_one_early_trial_but_disabled_monitor_stays_off(self):
        self.controller.initialized=True;self.controller.settings.monitor=True
        for _ in range(3):self.controller.policy.failure('router unavailable')
        def still_offline(*args,**kwargs):
            self.controller.policy.before_request();self.controller.policy.failure('still offline')
            return CheckResult('protected','waiting',blocked=True)
        self.engine.return_value.check.side_effect=still_offline
        self.controller._event_check();self.wait()
        self.controller._event_check();self.wait()
        self.assertEqual(self.engine.return_value.check.call_count,1)
        self.controller.settings.monitor=False;self.controller._schedule()
        self.controller._event_check();self.wait()
        self.assertEqual(self.engine.return_value.check.call_count,1);self.assertFalse(self.controller.timer.isActive())

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

    def test_offline_refresh_preserves_last_account_fields_and_persistent_snapshot(self):
        previous={'account':'last-online','availability':'ready','network_key':'previous-network',
                  'fetched_at':'previous-time','online_since':'previous-login','source_ipv4':'192.0.2.8',
                  'source_ipv6':'2001:db8::8','source_mac':'02:00:00:00:00:08','mobile_identifier':'PC端（0）'}
        self.controller.session=dict(previous);self.controller.snapshots.save(previous)
        self.engine.return_value.check.return_value=CheckResult('offline','offline',online=False,
            session={'availability':'unavailable','login_state':'offline','message':'XHA 确认未登录。'})
        self.controller.refreshSession();self.wait()
        for key,value in previous.items():
            if key!='availability':self.assertEqual(self.controller.session[key],value)
        self.assertTrue(self.controller.data['sessionStale'])
        self.assertEqual(self.controller.snapshots.read(),previous)
        restarted=Controller(self.directory)
        try:
            self.assertEqual(restarted.session['account'],'last-online')
            self.assertEqual(restarted.session['fetched_at'],'previous-time')
        finally:restarted.cleanup();restarted.deleteLater()

    def test_successful_new_identity_replaces_previous_snapshot(self):
        self.controller.session={'account':'previous','source_ipv4':'192.0.2.1','fetched_at':'previous-time'}
        current={'account':'new-identity','availability':'ready','source_ipv4':'192.0.2.2','fetched_at':'new-time'}
        self.engine.return_value.check.return_value=CheckResult('online','online',online=True,session=current)
        self.controller.refreshSession();self.wait()
        self.assertEqual(self.controller.session,dict(current,network_key='fake'))
        self.assertEqual(self.controller.snapshots.read(),self.controller.session)

    def test_cooling_campus_reads_wait_but_update_is_independent(self):
        for _ in range(3):self.controller.policy.failure('fixture')
        self.controller.refreshSession();self.controller.loadDevices(False)
        self.assertFalse(self.controller.busy);self.engine.assert_not_called()
        with patch('ouc_desktop.update.check_update',return_value={'state':'empty'}) as lookup:
            self.controller.checkUpdate();self.wait();lookup.assert_called_once()

    def test_public_project_links_work_while_paused_and_report_handler_failure(self):
        for _ in range(3):self.controller.policy.failure('fixture')
        notices=[];self.controller.notice.connect(notices.append)
        with patch('ouc_desktop.controller.QDesktopServices.openUrl',return_value=False) as opener:
            self.controller.openProjectLink('email');self.controller.openProjectLink('source');self.controller.openProjectLink('releases')
            self.assertEqual(opener.call_count,3)
            self.assertEqual(opener.call_args.args[0].toString(),'https://github.com/JieShenAI/OUNet/releases')
        self.assertTrue(self.controller.blocked);self.assertTrue(any('复制邮箱' in n for n in notices))

    def test_update_check_is_coalesced_and_cooled_down(self):
        with patch('ouc_desktop.update.check_update',return_value={'state':'empty','message':'fixture'}) as checker:
            self.controller.checkUpdate();self.controller.checkUpdate();self.wait()
            self.controller.checkUpdate();self.assertEqual(checker.call_count,1)
