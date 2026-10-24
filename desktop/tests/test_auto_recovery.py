"""Recovery deadlines, persistent state and real dispatch counts; no network."""
from unittest.mock import Mock
import requests
from test_desktop import TempTest
from test_v02 import Session,Response
import auto_login as core
from ouc_desktop.request_policy import RequestPolicy,RequestClient,RequestFailure,RequestsPaused
from ouc_desktop.engine import MonitorEngine
from ouc_desktop.storage import write_json


class AutomaticRecovery(TempTest):
    def setUp(self):
        super().setUp();self.now=1000.;self.wall=1000.
        self.policy=RequestPolicy(self.directory/'requests.json',lambda:self.wall,lambda:self.now)
    def advance(self,seconds):self.now+=seconds;self.wall+=seconds
    def fail_three(self,policy=None):
        policy=policy or self.policy
        for _ in range(3):policy.failure('temporary timeout')

    def test_one_trial_per_deadline_backoff_caps_and_never_permanently_stops(self):
        self.fail_three();session=Session([requests.Timeout() for _ in range(9)])
        client=RequestClient(session,self.policy)
        for delay in (15,30,60,120,300,300,300):
            self.assertAlmostEqual(self.policy.remaining,delay)
            with self.assertRaises(RequestsPaused):client.get('https://xha.ouc.edu.cn/test','fixture')
            self.advance(delay)
            count=len(session.calls)
            with self.assertRaises(RequestFailure):client.get('https://xha.ouc.edu.cn/test','fixture')
            with self.assertRaises(RequestsPaused):client.get('https://xha.ouc.edu.cn/test','fixture')
            self.assertEqual(len(session.calls),count+1)
        self.advance(300)
        RequestClient(Session([Response('{}')]),self.policy).get('https://xha.ouc.edu.cn/test','fixture')
        self.assertFalse(self.policy.paused);self.assertEqual(self.policy.state['retry_level'],0)

    def test_restart_retains_deadline_and_old_pause_migrates_to_first_cooldown(self):
        self.fail_three();self.advance(5)
        restored=RequestPolicy(self.policy.path,lambda:self.wall,lambda:self.now)
        self.assertAlmostEqual(restored.remaining,10)
        self.advance(10);restored.before_request();self.assertTrue(restored.trial)
        path=self.directory/'old.json'
        write_json(path,{'version':1,'failures':3,'paused':True})
        old=RequestPolicy(path,lambda:self.wall,lambda:self.now)
        self.assertAlmostEqual(old.remaining,15)
        self.advance(15);old.before_request();self.assertTrue(old.trial)

    def test_clock_rollback_does_not_extend_running_deadline_or_restart_past_cap(self):
        self.fail_three();self.wall-=100000
        self.now+=15;self.policy.before_request();self.assertTrue(self.policy.trial)
        restored=RequestPolicy(self.policy.path,lambda:self.wall,lambda:self.now)
        self.assertLessEqual(restored.remaining,15)

    def test_network_events_expedite_once_and_preserve_password_rejection(self):
        self.fail_three();self.policy.reject_credential('a','v1','密码错误')
        self.policy.expedite();self.policy.before_request();self.policy.failure('still offline')
        self.assertAlmostEqual(self.policy.remaining,30)
        self.policy.expedite();self.assertAlmostEqual(self.policy.remaining,30)
        self.advance(15);self.policy.expedite();self.assertEqual(self.policy.remaining,0)
        self.assertEqual(self.policy.credential_error('a','v1'),'密码错误')

    def test_legacy_safety_pause_never_automatically_expires(self):
        self.policy.import_legacy_pause();self.advance(100000);self.policy.expedite()
        with self.assertRaises(RequestsPaused):self.policy.before_request()
        self.policy.begin_trial();self.policy.before_request();self.policy.success()
        self.assertFalse(self.policy.paused)

    def test_event_budget_is_shared_when_failure_moves_between_services(self):
        self.fail_three();self.policy.expedite();self.policy.success()
        authentication=self.policy.scope('authentication');self.fail_three(authentication)
        self.policy.expedite();self.assertEqual(authentication.remaining,15)
        self.advance(15);self.policy.expedite();self.assertEqual(authentication.remaining,0)

    def test_github_and_public_failures_do_not_pause_xha(self):
        session=Session([requests.Timeout() for _ in range(6)]+[Response('{}')])
        client=RequestClient(session,self.policy)
        for _ in range(3):
            with self.assertRaises(RequestFailure):client.get('https://api.github.com/repos/test/releases','update')
        for _ in range(3):
            with self.assertRaises(RequestFailure):client.get('http://probe.test/check','probe',probe=True)
        self.assertFalse(self.policy.recovery_paused)
        client.get('https://xha.ouc.edu.cn/test','status')
        self.assertTrue(self.policy.scope('update').paused);self.assertTrue(self.policy.scope('external').paused)

    def engine(self):
        portal=Mock();portal.login_status.return_value={'state':'offline'}
        portal.authenticate.return_value=core.AuthenticationResult(False,'暂时无法认证')
        probe=Mock(return_value=False);cancel=Mock();cancel.is_set.return_value=False;cancel.wait.return_value=False
        engine=MonitorEngine(self.policy,session_factory=lambda:Session([]),portal_factory=lambda client:portal,probe=probe,cancel=cancel)
        return engine,portal,probe

    def test_successful_status_does_not_reset_login_cooldown_but_online_status_does(self):
        engine,portal,probe=self.engine();config=core.Config('synthetic','fake-secret','','',1)
        for _ in range(3):engine.check(config)
        self.assertTrue(self.policy.scope('authentication').paused)
        for _ in range(5):engine.check(config)
        self.assertEqual(portal.authenticate.call_count,3)
        self.advance(15);engine.check(config)
        self.assertEqual(portal.authenticate.call_count,4)
        self.assertAlmostEqual(self.policy.scope('authentication').remaining,30)
        self.advance(30);portal.login_status.return_value={'state':'online'}
        engine.check(config);self.assertFalse(self.policy.recovery_paused)
        self.assertEqual(portal.authenticate.call_count,4)

    def test_router_restores_without_network_event_and_authentication_resumes(self):
        engine,portal,probe=self.engine();config=core.Config('synthetic','fake-secret','','',1)
        self.fail_three()
        engine.check(config);portal.login_status.assert_not_called()
        self.advance(15)
        portal.authenticate.return_value=core.AuthenticationResult(True,'accepted');probe.side_effect=[False,True,True]
        result=engine.check(config)
        self.assertTrue(result.online);portal.authenticate.assert_called_once()
        self.assertFalse(self.policy.recovery_paused)

    def test_public_recovery_clears_temporary_pause_but_not_bad_credentials(self):
        engine,portal,probe=self.engine();self.fail_three();self.fail_three(self.policy.scope('authentication'))
        self.policy.reject_credential('a','r1','密码错误');self.advance(15);probe.return_value=True
        self.assertTrue(engine.check(credential_allowed=False).online)
        self.assertFalse(self.policy.recovery_paused);portal.authenticate.assert_not_called()
        self.assertEqual(self.policy.credential_error('a','r1'),'密码错误')
