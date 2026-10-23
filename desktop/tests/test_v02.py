"""HTTP-level and workflow regression tests; no real network or user credentials."""
import json
from pathlib import Path
from unittest.mock import Mock, patch
import requests
from test_desktop import TempTest
import auto_login as core
from ouc_desktop.request_policy import RequestPolicy, RequestClient, RequestFailure, RequestsPaused
from ouc_desktop.portal_status import PortalClient, valid_object
from ouc_desktop.engine import MonitorEngine, check_external
from ouc_desktop.recovery import RecoveryStore
from ouc_desktop.storage import CredentialStore, Settings, StorageError, write_json
from ouc_desktop.windows import profile_ssid

class Response:
    def __init__(self, text='', status=200): self.body=text.encode();self.status_code=status
    def __enter__(self):return self
    def __exit__(self,*args):pass
    def iter_content(self,chunk_size):yield self.body
class Session:
    def __init__(self,values):self.values=list(values);self.calls=[]
    def __enter__(self):return self
    def __exit__(self,*args):pass
    def mount(self,*args):pass
    def get(self,url,**kwargs):
        self.calls.append((url,kwargs))
        value=self.values.pop(0)
        if isinstance(value,Exception):raise value
        return value

class RequestTests(TempTest):
    def setUp(self):super().setUp();self.policy=RequestPolicy(self.directory/'request-state.json')
    def test_third_actual_failure_blocks_remaining_requests_and_reload(self):
        s=Session([requests.Timeout() for _ in range(4)]);client=RequestClient(s,self.policy)
        for _ in range(3):
            with self.assertRaises(RequestFailure):client.get('https://example.test','fixture')
        with self.assertRaises(RequestsPaused):client.get('https://example.test','fixture')
        self.assertEqual(len(s.calls),3)
        self.assertTrue(RequestPolicy(self.policy.path).paused)
    def test_invalid_http_200_responses_settle_once_after_decode(self):
        s=Session([Response('<html>bad</html>') for _ in range(3)])
        client=RequestClient(s,self.policy)
        for count in range(1,4):
            with self.assertRaises(RequestFailure):client.get('https://example.test','fixture',valid_object(('result',)))
            self.assertEqual(self.policy.failures,count)
        self.assertTrue(self.policy.paused)
    def test_success_interrupts_consecutive_failures(self):
        s=Session([requests.Timeout(),requests.Timeout(),Response('{}'),requests.Timeout()]);client=RequestClient(s,self.policy)
        for _ in range(2):
            with self.assertRaises(RequestFailure):client.get('https://example.test','fixture')
        client.get('https://example.test','fixture')
        with self.assertRaises(RequestFailure):client.get('https://example.test','fixture')
        self.assertEqual(self.policy.failures,1)
    def test_captive_redirect_or_html_is_offline_not_failed_http(self):
        s=Session([Response('',302),Response('<html>login</html>')])
        self.assertFalse(check_external(RequestClient(s,self.policy)))
        self.assertEqual(self.policy.failures,0);self.assertFalse(self.policy.paused)
        self.assertTrue(all(not opts['allow_redirects'] for _,opts in s.calls))
    def test_half_open_failure_stops_after_single_request(self):
        for _ in range(3):self.policy.failure('fixture')
        self.policy.begin_trial();s=Session([requests.Timeout(),Response('{}')])
        with self.assertRaises(RequestFailure):RequestClient(s,self.policy).get('https://example.test','fixture')
        with self.assertRaises(RequestsPaused):RequestClient(s,self.policy).get('https://example.test','fixture')
        self.assertEqual(len(s.calls),1);self.assertTrue(self.policy.paused)
    def test_half_open_success_reopens_normal_flow(self):
        for _ in range(3):self.policy.failure('fixture')
        self.policy.begin_trial();client=RequestClient(Session([Response('{}'),Response('{}')]),self.policy)
        client.get('https://example.test','fixture');client.get('https://example.test','fixture')
        self.assertFalse(self.policy.paused);self.assertEqual(self.policy.failures,0)
    def test_paused_engine_sends_no_probe_or_status_requests(self):
        for _ in range(3):self.policy.failure('fixture')
        s=Session([])
        r=MonitorEngine(self.policy,Mock(),lambda:s).check(fetch_info=True)
        self.assertTrue(r.blocked);self.assertEqual(s.calls,[])
    def test_three_probe_failures_end_current_flow_at_third_request(self):
        s=Session([requests.Timeout() for _ in range(10)]);wifi=Mock();wifi.current.return_value=['Test']
        r=MonitorEngine(self.policy,wifi,lambda:s).check(core.Config('fake','001234','Test','Profile'))
        self.assertTrue(r.blocked);self.assertEqual(len(s.calls),3)
        self.assertFalse(any('login' in url for url,_ in s.calls))
    def test_state_write_failure_closes_gate(self):
        with patch('ouc_desktop.request_policy.write_json',side_effect=StorageError('fixture')):
            with self.assertRaises(StorageError):self.policy.failure('fixture')
        self.assertTrue(self.policy.paused)
    def test_business_rejection_is_valid_http_success(self):
        self.policy.failure('fixture')
        data=RequestClient(Session([Response('{"result":0,"msg":"password error"}')]),self.policy).get('https://example.test','fixture',valid_object(('result',)))
        self.assertEqual(data['result'],0);self.assertEqual(self.policy.failures,0)

class WorkflowTests(TempTest):
    def setUp(self):
        super().setUp();self.policy=RequestPolicy(self.directory/'request-state.json')
        self.wifi=Mock();self.wifi.current.return_value=['Test']
        self.portal=Mock();self.portal.authenticate.return_value=core.AuthenticationResult(True,'ok')
        self.portal.fetch_session.return_value={'availability':'ready','account':'site-user'}
        self.portal.login_status.return_value={'state':'offline','result':0}
        self.probe=Mock(return_value=True);self.cancel=Mock();self.cancel.is_set.return_value=False;self.cancel.wait.return_value=False
        self.engine=MonitorEngine(self.policy,self.wifi,lambda:Session([]),self.probe,lambda client:self.portal,cancel=self.cancel)
        self.config=core.Config('fake','001234','Test','Profile',1)
    def test_online_never_authenticates_or_initializes_wlan(self):
        r=self.engine.check(self.config)
        self.assertTrue(r.online);self.portal.authenticate.assert_not_called();self.wifi.current.assert_not_called()
        self.portal.fetch_session.assert_not_called()
    def test_missing_credentials_still_probes(self):
        self.assertTrue(self.engine.check(None).online)
        self.probe.return_value=False;r=self.engine.check(None)
        self.assertEqual(r.status,'setup');self.portal.authenticate.assert_not_called()
    def test_recovery_requires_verification_and_counts_method(self):
        self.probe.side_effect=[False,False,True,True]
        r=self.engine.check(self.config)
        self.assertTrue(r.online);self.assertEqual(r.recovery_method,'校园网认证恢复')
        self.portal.fetch_session.assert_not_called()
    def test_acceptance_without_connectivity_is_not_recovery(self):
        self.probe.return_value=False;r=self.engine.check(self.config)
        self.assertFalse(r.online);self.assertEqual(r.recovery_method,'')
    def test_selected_wifi_goes_directly_to_auth_without_redundant_probe(self):
        self.probe.side_effect=[False,True,True]
        result=self.engine.check(self.config)
        self.portal.authenticate.assert_called_once()
        self.assertTrue(result.online);self.assertEqual(self.probe.call_count,3)
    def test_reconnection_only_does_not_authenticate(self):
        self.portal.login_status.return_value={'state':'online','uid':'site-user'}
        r=self.engine.check(self.config,connect_requested=True)
        self.assertTrue(r.online);self.assertEqual(r.recovery_method,'');self.portal.authenticate.assert_not_called();self.wifi.connect.assert_not_called()
    def test_migration_blocks_authentication_but_allows_probe(self):
        self.probe.return_value=False
        self.assertEqual(self.engine.check(self.config,authenticate_allowed=False).status,'migration')
        self.wifi.current.assert_not_called();self.portal.authenticate.assert_not_called()
    def test_metadata_failure_does_not_undo_external_connectivity(self):
        self.portal.fetch_session.side_effect=RequestFailure('fixture')
        self.portal.login_status.return_value={'state':'online','uid':'site-user'}
        r=self.engine.check(self.config,fetch_info=True)
        self.assertTrue(r.online);self.assertEqual(r.session_error,'fixture')
    def test_explicit_password_rejection_stops_repeat_credentials(self):
        self.portal.authenticate.return_value=core.AuthenticationResult(False,'密码错误');self.probe.return_value=False
        r=self.engine.check(self.config);self.assertTrue(r.credential_rejected)
        self.portal.authenticate.reset_mock()
        self.engine.check(self.config,credential_allowed=False);self.portal.authenticate.assert_not_called()
    def test_no_fixed_120_second_cooldown(self):
        self.probe.side_effect=[False,False,True,True,False,False,True,True]
        self.engine.check(self.config);self.engine.check(self.config)
        self.assertEqual(self.portal.authenticate.call_count,2)

class AccountAndRecoveryTests(TempTest):
    def test_v1_credential_upgrade_and_multiple_passwords_are_isolated(self):
        store=CredentialStore(self.directory/'credentials.dat')
        store.path.write_bytes(store._crypt(json.dumps({'version':1,'username':'old','password':'00123'}).encode()))
        first=store.summaries()[0]['id'];second=store.put('','second','00234','replace')
        store.put(first,'old','','keep')
        self.assertEqual(store.read(first),('old','00123'));self.assertEqual(store.read(second),('second','00234'))
        self.assertNotIn(b'00123',store.path.read_bytes());self.assertNotIn('password',store.summaries()[0])
    def test_seconds_validate_and_legacy_values_migrate(self):
        path=self.directory/'settings.json'
        for interval in (1,2,3,7,120):
            Settings(interval=interval).save(path);self.assertEqual(Settings.read(path).interval,interval)
        write_json(path,{'version':1,'interval':60});self.assertEqual(Settings.read(path).version,3)
    def test_recovery_deduplicates_and_survives_app_restart_not_new_boot(self):
        path=self.directory/'recoveries.json';store=RecoveryStore(path);boot={'id':'boot1','started_at':'2026-10-22T08:00:00+08:00'}
        store.set_boot(boot);store.observe_offline();store.observe_offline();store.online(True,'校园网认证恢复',2)
        store.online(True,'校园网认证恢复',2);self.assertEqual(store.view()['count'],1)
        second=RecoveryStore(path);second.set_boot(boot);self.assertEqual(second.view()['count'],1)
        second.set_boot({'id':'boot2','started_at':'2026-10-23T08:00:00+08:00'});self.assertEqual(second.view()['count'],0)
    def test_external_recovery_or_first_online_does_not_count(self):
        store=RecoveryStore(self.directory/'r.json');store.set_boot({'id':'boot','started_at':'start'})
        store.online(True,'method',2);store.observe_offline();store.online(False)
        self.assertEqual(store.view()['count'],0)
    def test_profile_name_is_not_assumed_to_be_ssid(self):
        xml='<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1"><name>Profile alias</name><SSIDConfig><SSID><hex>546573742D57694669</hex><name>Test-WiFi</name></SSID></SSIDConfig></WLANProfile>'
        self.assertEqual(profile_ssid(xml),'Test-WiFi')
    def test_managed_cli_stops_before_creating_session(self):
        with patch.object(core,'DIRECTORY',self.directory):
            write_json(self.directory/'desktop-managed.json',{'version':2,'mode':'desktop-managed','data_directory':str(self.directory)})
            with self.assertRaises(core.RetryStateError):core.runtime_paths()

class PortalTests(TempTest):
    def portal(self,records):
        status={'result':1,'uid':'actual-online','v46ip':'192.0.2.24','ss4':'02005e100024'}
        page='<html><script>v4ip="192.0.2.24";</script></html>'
        script='var enHTTPSPort=802;var page={name:"program"};'
        config={'data':{'login_method':'1','page_index':'page'}}
        s=Session([Response(json.dumps(status)),Response(page),Response(script),Response(json.dumps(config)),Response(json.dumps({'records':records}))])
        return PortalClient(RequestClient(s,RequestPolicy(self.directory/'request.json'))),s
    def test_verified_field_mapping_selects_current_device_not_first(self):
        other={'is_owner_ip':0,'login_ip':'192.0.2.30'}
        owner={'is_owner_ip':1,'login_time':'2026-10-22 08:00:00','login_ip':'192.0.2.24','login_ip_v6':'2001:db8::24','mac_address':'02005e100024','phone_flag':0}
        client,s=self.portal([other,owner]);result=client.fetch_session()
        self.assertEqual(result['account'],'actual-online');self.assertEqual(result['source_ipv4'],'192.0.2.24')
        self.assertEqual(result['online_since'],owner['login_time']);self.assertEqual(result['mobile_identifier'],'PC端（0）')
        self.assertTrue(all('user_password' not in (opts.get('params') or {}) for _,opts in s.calls))
    def test_ambiguous_records_are_not_arbitrarily_chosen(self):
        client,_=self.portal([{'is_owner_ip':1,'login_ip':'192.0.2.24'},{'is_owner_ip':1,'login_ip':'192.0.2.24'}])
        self.assertEqual(client.fetch_session()['availability'],'unavailable')
    def test_missing_fields_are_not_fabricated(self):
        client,_=self.portal([{'is_owner_ip':1,'login_ip':'192.0.2.24'}]);data=client.fetch_session()
        self.assertEqual(data['source_ipv6'],'页面未提供');self.assertEqual(data['online_since'],'页面未提供')
