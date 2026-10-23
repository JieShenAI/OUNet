from pathlib import Path
import json
import os
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import auto_login as app


def response(body, status=200):
    result = requests.Response()
    result.status_code = status
    result._content = body.encode("utf-8")
    result._content_consumed = True
    result.url = "https://example.test/"
    return result


def portal_responses():
    return [
        response("<html><script>v46ip='10.190.1.2';ss4='000000000000';vlanid='0';</script></html>"),
        response("var enHTTPSPort=802; var page={name:'campus_program'};"),
        response('dr1003({"data":{"login_method":"1","account_prefix":"0","account_suffix":""}});'),
    ]


class ConfigTests(unittest.TestCase):
    def test_password_leading_zero_and_environment_override(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as directory:
            path = Path(directory) / ".env"
            path.write_text("OUC_USERNAME=test\nOUC_PASSWORD=001234\n", encoding="utf-8-sig")
            with patch.dict(os.environ, {"OUC_USERNAME": "override"}, clear=True):
                config = app.load_config(path)
            self.assertEqual(config.username, "override")
            self.assertEqual(config.password, "001234")
            self.assertEqual(config.interval, 60)

    def test_missing_credentials_and_bad_timing(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(ValueError):
                app.load_config(Path("not-existing-test.env"))
        for interval in ("0", "-1", "nan", "inf"):
            with patch.dict(os.environ, {"OUC_USERNAME": "test", "OUC_PASSWORD": "test",
                                         "OUC_INTERVAL": interval}, clear=True):
                with self.assertRaises(ValueError):
                    app.load_config(Path("not-existing-test.env"))


class ProbeTests(unittest.TestCase):
    def test_expected_response_is_online(self):
        session = Mock()
        session.get.return_value = response("Microsoft Connect Test")
        self.assertTrue(app.has_internet(session, 2))
        self.assertEqual(session.get.call_count, 1)
        self.assertFalse(session.get.call_args.kwargs["allow_redirects"])

    def test_captive_html_and_redirect_are_offline(self):
        session = Mock()
        session.get.side_effect = [response("<html>Login</html>"), response("", 302)]
        self.assertFalse(app.has_internet(session, 2))

    def test_second_provider_recovers_from_timeout(self):
        session = Mock()
        session.get.side_effect = [requests.Timeout(), response(
            "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>")]
        self.assertTrue(app.has_internet(session, 2))

    def test_unreachable_and_server_error_are_offline(self):
        session = Mock()
        session.get.side_effect = [requests.ConnectionError(), response("Success", 500)]
        self.assertFalse(app.has_internet(session, 2))

    def test_session_does_not_use_proxy_environment(self):
        with app.create_session() as session:
            self.assertFalse(session.trust_env)
            self.assertTrue(session.verify)


class WifiTests(unittest.TestCase):
    def setUp(self):
        self.config = app.Config("test-user", "test-password")

    def test_ssid_parser_handles_localization_and_excludes_bssid(self):
        self.assertEqual(app.parse_ssids("  SSID : HUAWEI-Jie_5G\n  BSSID : 11:22:33\n"),
                         ["HUAWEI-Jie_5G"])
        self.assertEqual(app.parse_ssids("  SSID ： HUAWEI-Jie_5G\n"), ["HUAWEI-Jie_5G"])

    @patch.object(app, "run_netsh")
    def test_target_wifi_does_not_trigger_reconnect(self, netsh):
        netsh.return_value = "SSID : HUAWEI-Jie_5G\n"
        self.assertTrue(app.ensure_wifi(self.config))
        netsh.assert_called_once_with("show", "interfaces")

    @patch.object(app.time, "sleep")
    @patch.object(app, "run_netsh")
    def test_disconnected_wifi_is_reconnected_and_verified(self, netsh, sleep):
        netsh.side_effect = ["", "Connection request accepted", "SSID : HUAWEI-Jie_5G\n"]
        self.assertTrue(app.ensure_wifi(self.config))
        self.assertEqual(netsh.call_args_list[1].args,
                         ("connect", "name=HUAWEI-Jie_5G", "ssid=HUAWEI-Jie_5G"))

    @patch.object(app.time, "sleep")
    @patch.object(app, "run_netsh")
    def test_other_wifi_after_connection_request_is_not_accepted(self, netsh, sleep):
        netsh.return_value = "SSID : OtherWiFi\n"
        self.assertFalse(app.ensure_wifi(self.config))


class LoginTests(unittest.TestCase):
    def setUp(self):
        self.config = app.Config("test-user", "test-secret")

    def test_observed_login_fields_and_success_response(self):
        session = Mock()
        session.get.side_effect = portal_responses() + [response('dr1003({"result":1});')]
        self.assertTrue(app.login(session, self.config))
        arguments = session.get.call_args
        self.assertEqual(arguments.args, ("https://xha.ouc.edu.cn:802/eportal/portal/login",))
        self.assertEqual(arguments.kwargs["params"]["user_account"], "test-user")
        self.assertEqual(arguments.kwargs["params"]["user_password"], "test-secret")
        self.assertEqual(arguments.kwargs["params"]["login_method"], "1")
        self.assertEqual(arguments.kwargs["params"]["wlan_user_ip"], "10.190.1.2")
        self.assertNotIn("DDDDD", arguments.kwargs["params"])
        self.assertEqual(session.get.call_count, 4)
        self.assertFalse(arguments.kwargs["allow_redirects"])

    def test_exception_and_response_never_log_password_or_url(self):
        for result in (requests.ConnectionError("https://portal/login?upass=test-secret"),
                       response('{"result":0,"msg":"test-secret https://portal/?upass=test-secret"}'),
                       response('<html>test-secret</html>'), response("", 302)):
            session = Mock()
            session.get.side_effect = portal_responses() + [result]
            with self.assertLogs(app.LOGGER, level="WARNING") as logs:
                self.assertFalse(app.login(session, self.config))
            self.assertNotIn("test-secret", " ".join(logs.output))
            self.assertNotIn("https://", " ".join(logs.output))

    def test_203_html_error_has_actionable_diagnostic(self):
        session = Mock()
        session.get.side_effect = portal_responses() + [response(
            "<html><body>Error code: 203 Bad request(2)</body></html>", 203)]
        with self.assertLogs(app.LOGGER, level="WARNING") as logs:
            self.assertFalse(app.login(session, self.config))
        self.assertIn("提交校园网登录", " ".join(logs.output))
        self.assertIn("HTTP 203", " ".join(logs.output))

    def test_certificate_and_timeout_errors_are_distinguished(self):
        for error, message in [(requests.exceptions.SSLError("test-secret"), "证书校验失败"),
                               (requests.Timeout("test-secret"), "请求超时")]:
            session = Mock()
            session.get.side_effect = [error]
            with self.assertLogs(app.LOGGER, level="WARNING") as logs:
                self.assertFalse(app.login(session, self.config))
            self.assertIn(message, " ".join(logs.output))
            self.assertNotIn("test-secret", " ".join(logs.output))

    def test_already_online_is_followed_by_external_check(self):
        session = Mock()
        session.get.side_effect = portal_responses() + [response(
            'dr1003({"result":0,"msg":"IP: 10.190.1.2 已经在线！","ret_code":2});')]
        self.assertTrue(app.login(session, self.config))

    def test_ip_and_prefix_are_read_again_for_each_login(self):
        session = Mock()
        first = portal_responses()
        second = portal_responses()
        second[0] = response("v46ip='10.190.3.4';ss4='01:02:03:04:05:06';")
        second[2] = response('{"data":{"login_method":1,"account_prefix":1,"account_suffix":"@campus"}}')
        session.get.side_effect = first + [response('{"result":1}')] + second + [response('{"result":1}')]
        self.assertTrue(app.login(session, self.config))
        self.assertTrue(app.login(session, self.config))
        parameters = session.get.call_args.kwargs["params"]
        self.assertEqual(parameters["wlan_user_ip"], "10.190.3.4")
        self.assertEqual(parameters["wlan_user_mac"], "010203040506")
        self.assertEqual(parameters["user_account"], ",0,test-user@campus")

    def test_unsupported_configuration_does_not_send_password(self):
        session = Mock()
        fixtures = portal_responses()
        fixtures[2] = response('{"data":{"login_method":"14"}}')
        session.get.side_effect = fixtures
        self.assertFalse(app.login(session, self.config))
        for call in session.get.call_args_list:
            self.assertNotIn("user_password", call.kwargs.get("params") or {})

    def test_jsonp_whitespace_and_malformed_data(self):
        self.assertEqual(app.parse_jsonp(' \n dr1003({"result":"ok"}) \n '), {"result": "ok"})
        for body in ("alert(1)", '<html>Login</html>', 'dr1003([]);'):
            with self.assertRaises(ValueError):
                app.parse_jsonp(body)

    def test_redacts_encoded_credentials(self):
        config = app.Config("student", "a&b!c")
        message = "student a&b!c a%26b%21c a&amp;b!c https://portal/?password=a%26b%21c"
        safe = app.safe_message(message, config)
        for secret in ("student", "a&b!c", "a%26b%21c", "a&amp;b!c", "https://"):
            self.assertNotIn(secret, safe)


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.config = app.Config("test-user", "test-secret")
        self.session = Mock()
        directory = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent)
        self.addCleanup(directory.cleanup)
        state_patch = patch.object(app, "RETRY_STATE_FILE", Path(directory.name) / "state.json")
        state_patch.start()
        self.addCleanup(state_patch.stop)

    @patch.object(app, "login")
    @patch.object(app, "ensure_wifi")
    @patch.object(app, "has_internet", return_value=True)
    def test_online_never_reconnects_or_logs_in(self, internet, wifi, login):
        self.assertTrue(app.check_once(self.session, self.config))
        wifi.assert_not_called()
        login.assert_not_called()

    @patch.object(app, "login")
    @patch.object(app, "ensure_wifi", return_value=False)
    @patch.object(app, "has_internet", return_value=False)
    def test_wrong_wifi_never_receives_credentials(self, internet, wifi, login):
        self.assertFalse(app.check_once(self.session, self.config))
        login.assert_not_called()

    @patch.object(app.time, "sleep")
    @patch.object(app, "login", return_value=True)
    @patch.object(app, "ensure_wifi", return_value=True)
    @patch.object(app, "has_internet", side_effect=[False, False, False, True])
    def test_recovery_checks_external_access_after_login(self, internet, wifi, login, sleep):
        self.assertTrue(app.check_once(self.session, self.config))
        login.assert_called_once_with(self.session, self.config)
        self.assertEqual(internet.call_count, 4)

    @patch.object(app.time, "sleep")
    @patch.object(app, "login", return_value=True)
    @patch.object(app, "ensure_wifi", return_value=True)
    @patch.object(app, "has_internet", return_value=False)
    def test_login_success_without_internet_is_failure(self, internet, wifi, login, sleep):
        self.assertFalse(app.check_once(self.session, self.config))

    @patch.object(app, "login")
    @patch.object(app, "ensure_wifi", return_value=True)
    @patch.object(app, "has_internet", side_effect=[False, True])
    def test_reconnection_can_restore_internet_without_login(self, internet, wifi, login):
        self.assertTrue(app.check_once(self.session, self.config))
        login.assert_not_called()

    @patch.object(app.time, "sleep")
    @patch.object(app, "login", return_value=True)
    @patch.object(app, "ensure_wifi", return_value=True)
    @patch.object(app, "has_internet", return_value=True)
    def test_force_login_submits_even_with_working_internet(self, internet, wifi, login, sleep):
        self.assertTrue(app.check_once(self.session, self.config, force_login=True))
        wifi.assert_called_once()
        login.assert_called_once()
        internet.assert_called_once()  # Only the check after submitting.

    @patch.object(app, "login")
    @patch.object(app, "discover_portal")
    @patch.object(app, "run_netsh", return_value="SSID : HUAWEI-Jie_5G\n")
    @patch.object(app, "has_internet", return_value=False)
    def test_offline_diagnostics_never_submit_credentials(self, internet, netsh, discover, login):
        self.assertTrue(app.diagnose(self.session, self.config))
        discover.assert_called_once()
        login.assert_not_called()


class RetryLimitTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent)
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "state.json"
        patcher = patch.object(app, "RETRY_STATE_FILE", self.path)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.config = app.Config("test-user", "test-secret")
        self.session = Mock()
        for name, value in (("has_internet", False), ("ensure_wifi", True)):
            patcher = patch.object(app, name, return_value=value)
            setattr(self, name, patcher.start())
            self.addCleanup(patcher.stop)
        patcher = patch.object(app.time, "sleep")
        patcher.start()
        self.addCleanup(patcher.stop)

    def block(self):
        for _ in range(3):
            app.LoginRetryGuard().begin_attempt(600)

    def test_three_failed_runs_stop_all_further_portal_requests(self):
        # Each failure is an actual request through login(), not a stubbed
        # login result. Fresh guards inside each call simulate scheduled runs.
        self.session.get.side_effect = [response("unavailable", 503)] * 3
        for _ in range(5):
            self.assertFalse(app.check_once(self.session, self.config))
        self.assertEqual(self.session.get.call_count, 3)
        self.assertEqual(self.ensure_wifi.call_count, 3)
        self.assertTrue(app.LoginRetryGuard().is_blocked())
        state = self.path.read_text(encoding="utf-8")
        self.assertNotIn(self.config.username, state)
        self.assertNotIn(self.config.password, state)

    def test_failures_outside_window_do_not_accumulate_before_limit(self):
        with patch.object(app.time, "time", side_effect=[1000, 1100, 1701]):
            guard = app.LoginRetryGuard()
            self.assertEqual(guard.begin_attempt(600), 1)
            self.assertEqual(guard.begin_attempt(600), 2)
            self.assertEqual(guard.begin_attempt(600), 1)
            self.assertFalse(guard.is_blocked())

    def test_block_does_not_expire_with_window_or_new_guard(self):
        with patch.object(app.time, "time", return_value=1000):
            self.block()
        with patch.object(app.time, "time", return_value=100000):
            self.assertFalse(app.check_once(self.session, self.config))
        self.session.get.assert_not_called()
        self.ensure_wifi.assert_not_called()

    def test_online_probe_clears_block_and_allows_future_recovery(self):
        self.block()
        self.has_internet.return_value = True
        self.assertTrue(app.check_once(self.session, self.config))
        self.assertFalse(self.path.exists())
        self.has_internet.return_value = False
        self.session.get.return_value = response("unavailable", 503)
        self.assertFalse(app.check_once(self.session, self.config))
        self.assertEqual(len(json.loads(self.path.read_text())["attempts"]), 1)

    @patch.object(app, "login", return_value=True)
    def test_login_success_without_restored_internet_still_uses_budget(self, login):
        for _ in range(4):
            self.assertFalse(app.check_once(self.session, self.config))
        self.assertEqual(login.call_count, 3)

    def test_wifi_failures_do_not_use_login_budget(self):
        self.ensure_wifi.return_value = False
        for _ in range(5):
            self.assertFalse(app.check_once(self.session, self.config))
        self.assertFalse(self.path.exists())
        self.session.get.assert_not_called()

    @patch.object(app, "login", return_value=False)
    def test_online_after_submission_clears_budget_even_if_response_failed(self, login):
        app.LoginRetryGuard().begin_attempt(600)
        self.has_internet.side_effect = [False, False, True]
        self.assertTrue(app.check_once(self.session, self.config))
        self.assertFalse(self.path.exists())

    def test_force_login_cannot_bypass_block(self):
        self.block()
        self.assertFalse(app.check_once(self.session, self.config, force_login=True))
        self.session.get.assert_not_called()
        self.ensure_wifi.assert_not_called()

    @patch.object(app, "discover_portal")
    @patch.object(app, "run_netsh", return_value="SSID : HUAWEI-Jie_5G\n")
    def test_diagnose_does_not_contact_portal_while_blocked(self, netsh, discover):
        self.block()
        self.assertFalse(app.diagnose(self.session, self.config))
        discover.assert_not_called()

    def test_corrupt_state_blocks_requests_instead_of_resetting_budget(self):
        for value in ("not json", '{"version":1,"attempts":[NaN],"blocked":false}',
                      '{"version":1,"attempts":[1,2,3],"blocked":false}'):
            self.path.write_text(value, encoding="utf-8")
            with self.assertRaises(app.RetryStateError):
                app.check_once(self.session, self.config)
        self.session.get.assert_not_called()

    def test_state_cannot_be_saved_means_no_requests_sent(self):
        with patch.object(app.os, "replace", side_effect=PermissionError):
            with self.assertRaises(app.RetryStateError):
                app.check_once(self.session, self.config)
        self.session.get.assert_not_called()

    @patch.object(app, "create_session")
    @patch.object(app, "load_config")
    @patch.object(app, "InstanceLock")
    @patch.object(app, "configure_logging")
    def test_reset_command_needs_no_credentials_and_sends_no_requests(self, logging, lock, load, session):
        self.block()
        with patch.object(sys, "argv", ["auto_login.py", "--reset-failures"]):
            self.assertEqual(app.main(), 0)
        self.assertFalse(self.path.exists())
        load.assert_not_called()
        session.assert_not_called()

    def test_failure_window_configuration_is_validated(self):
        for value in ("0", "-1", "nan", "inf"):
            with patch.dict(os.environ, {"OUC_USERNAME": "user", "OUC_PASSWORD": "secret",
                                         "OUC_FAILURE_WINDOW": value}, clear=True):
                with self.assertRaises(ValueError):
                    app.load_config(self.path.parent / "missing.env")


if __name__ == "__main__":
    unittest.main()
