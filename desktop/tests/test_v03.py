"""v0.3 acceptance contracts, entirely synthetic transport and temporary storage."""
from dataclasses import replace
import json
from unittest.mock import Mock, patch
import requests
from test_desktop import TempTest
from test_v02 import Session, Response
import auto_login as core
from ouc_desktop.engine import MonitorEngine
from ouc_desktop.request_policy import RequestPolicy, RequestClient, RequestFailure, RequestsPaused, RequestStats
from ouc_desktop.portal_status import PortalClient
from ouc_desktop.storage import Settings, CredentialStore, write_json, StorageError, EventStore
from ouc_desktop.auth_error import credential_error
from ouc_desktop.network import NetworkContext, BoundAdapter, current_context
from ouc_desktop.update import validate_metadata, check_update


class WorkflowV03(TempTest):
    def setUp(self):
        super().setUp()
        self.policy=RequestPolicy(self.directory/'request.json')
        self.portal=Mock();self.portal.login_status.return_value={'state':'offline','result':0}
        self.portal.authenticate.return_value=core.AuthenticationResult(True,'ok')
        self.portal.fetch_session.return_value={'account':'actual-online','availability':'ready'}
        self.cancel=Mock();self.cancel.is_set.return_value=False;self.cancel.wait.return_value=False
        self.probe=Mock(return_value=True)
        self.engine=MonitorEngine(self.policy,session_factory=lambda:Session([]),probe=self.probe,
                                  portal_factory=lambda c:self.portal,cancel=self.cancel)
        self.config=core.Config('demo','001234-fake','','',1)

    def test_600_healthy_ticks_make_zero_portal_calls(self):
        for _ in range(600):self.assertTrue(self.engine.check(self.config).online)
        self.portal.login_status.assert_not_called();self.portal.authenticate.assert_not_called()
        self.portal.fetch_session.assert_not_called();self.assertEqual(self.policy.stats.view()['total'],0)

    def test_auto_online_never_refreshes_snapshot(self):
        self.engine.check(self.config)
        self.portal.fetch_session.assert_not_called()

    def test_manual_online_queries_identity_never_authenticates(self):
        self.portal.login_status.return_value={'state':'online','uid':'actual-online'}
        result=self.engine.check(self.config,intent='reconnect')
        self.assertTrue(result.online);self.assertEqual(result.session['account'],'actual-online')
        self.portal.discover.assert_called_once();self.portal.authenticate.assert_not_called()

    def test_read_only_refresh_of_offline_status_never_authenticates(self):
        result=self.engine.check(self.config,intent='refresh')
        self.assertEqual(result.session['login_state'],'offline');self.portal.authenticate.assert_not_called()

    def test_online_but_public_failure_does_not_login(self):
        self.portal.login_status.return_value={'state':'online','uid':'actual-online'};self.probe.return_value=False
        self.assertFalse(self.engine.check(self.config).online);self.portal.authenticate.assert_not_called()

    def test_unknown_status_never_submits_password(self):
        self.probe.return_value=False;self.portal.login_status.return_value={'state':'unknown'}
        self.assertEqual(self.engine.check(self.config).status,'unknown');self.portal.authenticate.assert_not_called()

    def test_password_error_ends_immediately_and_20_ticks_skip_all_xha(self):
        self.probe.return_value=False;self.portal.authenticate.return_value=core.AuthenticationResult(False,'密码错误')
        result=self.engine.check(self.config)
        self.assertEqual(result.credential_message,'密码错误');self.assertEqual(self.probe.call_count,1)
        self.portal.reset_mock()
        for _ in range(20):self.engine.check(self.config,credential_allowed=False)
        self.portal.login_status.assert_not_called();self.portal.authenticate.assert_not_called();self.portal.discover.assert_not_called()

    def test_successful_auth_requires_stable_probe_and_no_xha_tail(self):
        self.probe.side_effect=[False,True,True]
        result=self.engine.check(self.config)
        self.assertEqual(result.recovery_method,'校园网认证恢复');self.portal.authenticate.assert_called_once()
        self.portal.fetch_session.assert_not_called()

    def test_unstable_probe_is_not_counted(self):
        self.probe.side_effect=[False,True,False]
        result=self.engine.check(self.config)
        self.assertEqual(result.recovery_method,'');self.assertFalse(result.online)

    def test_no_target_ssid_or_wifi_connect_required(self):
        self.probe.side_effect=[False,True,True];wifi=Mock();self.engine.wifi=wifi
        self.assertTrue(self.engine.check(self.config).online);wifi.current.assert_not_called();wifi.connect.assert_not_called()

    def test_ambiguous_context_blocks_all_portal_operations(self):
        self.probe.return_value=False
        result=self.engine.check(self.config,context=NetworkContext(reason='ambiguous'))
        self.assertEqual(result.status,'network');self.portal.login_status.assert_not_called()

    def test_unreachable_campus_is_not_misreported_as_wrong_password(self):
        self.probe.return_value=False;self.portal.login_status.side_effect=RequestFailure('TLS error')
        result=self.engine.check(self.config)
        self.assertEqual(result.status,'noncampus');self.assertFalse(result.credential_rejected)
        self.portal.authenticate.assert_not_called()

    def test_paused_all_intents_send_zero_requests(self):
        for _ in range(3):self.policy.failure('fixture')
        for intent in ('auto','refresh','reconnect'):self.assertTrue(self.engine.check(self.config,intent=intent).blocked)
        self.probe.assert_not_called();self.portal.login_status.assert_not_called()


class PersistenceV03(TempTest):
    def test_old_interval_and_paused_preferences_are_preserved(self):
        path=self.directory/'settings.json'
        write_json(path,{'version':2,'interval':120,'monitor':False,'auto_switch':True,'ssid':'old','profile':'old'})
        settings=Settings.read(path)
        self.assertEqual(settings.interval_ms,120000);self.assertFalse(settings.monitor);self.assertFalse(settings.auto_switch)
        settings.save(path);self.assertEqual(Settings.read(path).interval_ms,120000)

    def test_new_default_and_fractional_reload(self):
        self.assertEqual(Settings().interval_ms,1000)
        path=self.directory/'settings.json';replace(Settings(),interval_ms=300).save(path)
        self.assertEqual(Settings.read(path).interval,.3)

    def test_corrupt_millisecond_setting_is_rejected(self):
        path=self.directory/'settings.json'
        for invalid in (299,True,'300',2147483001):
            write_json(path,{'version':3,'interval_ms':invalid})
            with self.assertRaises(StorageError):Settings.read(path)

    def test_account_remark_keeps_revision_real_password_changes_it(self):
        store=CredentialStore(self.directory/'credentials.dat');key=store.put('','demo','001234','replace','label')
        first=store.summaries()[0]['revision']
        store.put(key,'demo','','keep','renamed');self.assertEqual(store.summaries()[0]['revision'],first)
        store.put(key,'demo','001234','replace');self.assertEqual(store.summaries()[0]['revision'],first)
        store.put(key,'demo','different','replace');self.assertNotEqual(store.summaries()[0]['revision'],first)
        self.assertNotIn('password',str(store.summaries()))

    def test_credential_block_independent_of_success_and_global_pause(self):
        path=self.directory/'request.json';policy=RequestPolicy(path)
        policy.reject_credential('a','r1','密码错误');policy.failure('one');policy.success()
        self.assertEqual(policy.credential_error('a','r1'),'密码错误');self.assertEqual(policy.credential_error('a','r2'),'')
        self.assertEqual(policy.credential_error('b','r1'),'')
        for _ in range(3):policy.failure('fixture')
        policy.clear_credential('a');self.assertTrue(policy.paused)
        self.assertTrue(RequestPolicy(path).paused)

    def test_legacy_rejection_remains_and_damaged_errors_fail_closed(self):
        path=self.directory/'r.json';p=RequestPolicy(path);p.reject_account('a')
        self.assertTrue(RequestPolicy(path).credential_error('a','legacy'))
        p._save(credential_errors={'a':None})
        with self.assertRaises(StorageError):RequestPolicy(path)

    def test_same_second_log_order_uses_append_sequence(self):
        log=EventStore(self.directory)
        log.add('info','first');log.add('info','second');log.add('info','third')
        entries=EventStore(self.directory).recent()
        self.assertEqual([e['message'] for e in entries],['third','second','first'])
        self.assertEqual([e['sequence'] for e in entries],[3,2,1])


class TransportV03(TempTest):
    def test_attempt_counter_includes_failure_excludes_blocked_and_public(self):
        p=RequestPolicy(self.directory/'r.json');s=Session([requests.Timeout(),Response('{}')])
        client=RequestClient(s,p)
        with self.assertRaises(RequestFailure):client.get('https://xha.ouc.edu.cn:802/test','fixture')
        client.get('https://example.test/','public')
        self.assertEqual(p.stats.view()['total'],1)
        p.failure('two');p.failure('three');p.failure('four')
        with self.assertRaises(RequestsPaused):client.get('https://xha.ouc.edu.cn/test','fixture')
        self.assertEqual(p.stats.view()['total'],1)

    def test_network_guard_and_cancellation_are_not_attempts(self):
        p=RequestPolicy(self.directory/'r.json');s=Session([])
        with self.assertRaises(RequestsPaused):RequestClient(s,p,network_guard=lambda:False).get('https://xha.ouc.edu.cn/a','fixture')
        self.assertEqual(s.calls,[]);self.assertEqual(p.failures,0);self.assertEqual(p.stats.total,0)

    def test_monotonic_rolling_window_and_process_scope(self):
        clock=Mock(return_value=0);s=RequestStats(clock)
        s.record('https://xha.ouc.edu.cn/a');clock.return_value=59.9
        self.assertEqual(s.view()['recent'],1);clock.return_value=60;self.assertEqual(s.view()['recent'],0)
        self.assertEqual(s.view()['total'],1);self.assertEqual(RequestStats().view()['total'],0)

    def test_bound_adapter_pins_source_and_outgoing_interface(self):
        import socket
        a=BoundAdapter(NetworkContext('fake',12,'192.0.2.18'))
        self.assertEqual(a.poolmanager.connection_pool_kw['source_address'],('192.0.2.18',0))
        self.assertIn((socket.IPPROTO_IP,31,socket.htonl(12)),a.poolmanager.connection_pool_kw['socket_options'])

    def test_single_ethernet_and_ambiguous_wifi_contexts(self):
        adapter={'id':'wired','index':4,'ips':['192.0.2.8'],'name':'Ethernet','kind':6,'up':True}
        with patch('ouc_desktop.network.adapter_addresses',return_value=[adapter]):
            context=current_context();self.assertTrue(context.valid);self.assertEqual(context.kind,'Ethernet')
        with patch('ouc_desktop.network.adapter_addresses',return_value=[dict(adapter,kind=71),dict(adapter,kind=71,id='second')]):
            self.assertFalse(current_context().valid)

    def test_status_online_without_uid_is_not_offline(self):
        p=RequestPolicy(self.directory/'r.json');s=Session([Response('{"result":1}')])
        info=PortalClient(RequestClient(s,p)).fetch_session()
        self.assertEqual(info['login_state'],'online');self.assertEqual(len(s.calls),1)

    def test_error_wording_does_not_guess_network_failures(self):
        for text in ('超时','验证码错误','欠费','password field missing','网络连接失败','未知错误 e2531'):
            self.assertEqual(credential_error(text),'')
        self.assertEqual(credential_error('账号或密码错误'),'账号或密码错误')
        self.assertEqual(credential_error('密码校验失败'),'密码错误')


class UpdateV03(TempTest):
    def release(self,version):
        filename=f'OUNet-{version}-Setup-x64.exe'
        return {'tag_name':'windows-v'+version,'assets':[{'name':'windows-update.json','id':1},
                {'name':filename,'browser_download_url':f'https://github.com/JieShenAI/OUNet/releases/download/windows-v{version}/{filename}'}]}
    def metadata(self):
        return {'platform':'windows-x64','version':'9.0.0','sha256':'a'*64,
                'url':'https://github.com/JieShenAI/OUNet/releases/download/windows-v9.0.0/OUNet-9.0.0-Setup-x64.exe'}
    def test_windows_metadata_requires_exact_repository_installer_and_hash(self):
        m=self.metadata();self.assertTrue(validate_metadata(m)['newer'])
        for data in (dict(m,platform='android'),dict(m,url=m['url']+'?x=1'),dict(m,url='https://example.test/app.exe'),dict(m,sha256='bad')):
            with self.assertRaises(ValueError):validate_metadata(data)
    def test_android_release_is_ignored(self):
        p=RequestPolicy(self.directory/'r.json')
        s=Session([Response(json.dumps([{'tag_name':'android-v99.0.0','assets':[]}]))])
        data=check_update(RequestClient(s,p));self.assertNotIn('url',data);self.assertEqual(p.stats.total,0)
    def test_global_pause_blocks_update_before_dispatch(self):
        p=RequestPolicy(self.directory/'r.json')
        for _ in range(3):p.failure('fixture')
        s=Session([])
        with self.assertRaises(RequestsPaused):check_update(RequestClient(s,p))
        self.assertEqual(s.calls,[])

    def test_valid_windows_asset_and_version_match(self):
        release=self.release('9.0.0')
        p=RequestPolicy(self.directory/'r.json')
        s=Session([Response(json.dumps([release])),Response(json.dumps(self.metadata()))])
        self.assertTrue(check_update(RequestClient(s,p))['newer']);self.assertEqual(len(s.calls),2)

    def test_release_metadata_version_mismatch_and_external_redirect_are_rejected(self):
        p=RequestPolicy(self.directory/'r.json')
        release=self.release('8.0.0')
        s=Session([Response(json.dumps([release])),Response(json.dumps(self.metadata()))])
        with self.assertRaises(RequestFailure):check_update(RequestClient(s,p))
        response=Response('',302);response.headers={'Location':'https://example.test/private'}
        s=Session([Response(json.dumps([release])),response])
        with self.assertRaises(RequestFailure):check_update(RequestClient(s,p))
        self.assertEqual(len(s.calls),2)
