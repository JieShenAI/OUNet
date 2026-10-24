"""Intent-based serial connection workflow. Public probes never imply portal identity."""
from __future__ import annotations
from dataclasses import dataclass, field
import re
import threading
import time
import auto_login as core
from .request_policy import RequestClient, RequestFailure, RequestsPaused
from .portal_status import PortalClient
from .auth_error import credential_error


@dataclass
class CheckResult:
    status: str
    message: str
    failures: int = 0
    blocked: bool = False
    wifi: str = ""
    authenticated: bool = False
    online: bool | None = None
    detected_offline: bool = False
    recovery_method: str = ""
    duration: float = 0
    session: dict = field(default_factory=dict)
    session_error: str = ""
    credential_rejected: bool = False
    credential_message: str = ""
    credential_confirmed: bool = False
    network_key: str = ""
    login_state: str = "unknown"


def check_external(client):
    offset = getattr(client.policy, "probe_offset", 0)
    client.policy.probe_offset = (offset + 1) % len(core.PROBES)
    sources = list(core.PROBES)
    for url, kind in sources[offset:] + sources[:offset]:
        def decode(text, response):
            if response.status_code != 200: return False
            text = text.strip()
            if kind == "microsoft": return text == "Microsoft Connect Test"
            return bool(re.search(r"<title>\s*Success\s*</title>", text, re.I)
                        and re.search(r"<body>\s*Success\s*</body>", text, re.I))
        try:
            if client.get(url, "检测外网", decode, probe=True): return True
        except (RequestFailure,RequestsPaused):
            if client.cancel.is_set(): raise
            if client.policy.scope('external').paused: break
    return False


class MonitorEngine:
    def __init__(self, policy, wifi=None, session_factory=core.create_session,
                 probe=check_external, portal_factory=PortalClient, clock=time.monotonic,
                 cancel=None, on_offline=lambda: None):
        self.policy, self.wifi = policy, wifi
        self.session_factory, self.probe, self.portal_factory = session_factory, probe, portal_factory
        self.clock, self.cancel, self.on_offline = clock, cancel or threading.Event(), on_offline

    def check(self, config=None, auto_switch=False, connect_requested=False,
              authenticate_allowed=True, progress=lambda status, message: None,
              fetch_info=False, interface_id="", credential_allowed=True, *, intent="auto", context=None):
        start = self.clock()
        if fetch_info: intent = "refresh"
        if connect_requested: intent = "reconnect"
        result = CheckResult("checking", "正在检查外网连接。", network_key=context.key if context else "")
        valid_context = context is None or context.valid
        portal_phase, campus_confirmed = False, False
        try:
            if self.policy.storage_failed:self.policy.before_request()
            if self.policy.state['legacy_pause'] and not self.policy.trial:self.policy.before_request()
            if (self.policy.recovery_paused and self.policy.retry_seconds>0
                    and not self.policy.trial and not self.policy.scope('authentication').trial):
                raise RequestsPaused(self.policy.pause_message())
            with self.session_factory() as session:
                adapter, guard = None, None
                if context and context.valid:
                    from .network import BoundAdapter
                    adapter, guard = BoundAdapter(context), context.unchanged
                    result.wifi = context.ssid or context.name + "（名称暂不可见）"
                client = RequestClient(session, self.policy, config.timeout if config else 8,
                                       self.cancel, adapter, guard)
                portal = self.portal_factory(client)
                if intent == "auto":
                    progress("checking", "正在验证外网。")
                    if self.probe(client):
                        self.policy.success();self.policy.scope('authentication').success()
                        result.status, result.online = "online", True
                        result.message = "外网验证通过，本轮未访问 XHA。"
                        return self._finish(result, start)
                    result.detected_offline, result.online = True, False
                    self.on_offline()
                    if not credential_allowed:
                        result.status, result.message = "credentials", "已停止这组错误凭据的自动 XHA 请求，请修改密码。"
                        return self._finish(result, start)
                if not valid_context:
                    result.status, result.message = "network", context.reason
                    return self._finish(result, start)
                progress("session", "正在读取 XHA 登录状态。")
                portal_phase = True
                if intent == "reconnect": portal.discover()
                status = portal.login_status()
                campus_confirmed = True
                result.login_state = status["state"]
                if status["state"] == "online":
                    self.policy.scope('authentication').success()
                    if intent in ("refresh", "reconnect"):
                        try: result.session = portal.fetch_session(status)
                        except RequestFailure as error: result.session_error = str(error)
                    result.online = self.probe(client)
                    result.status = "online" if result.online else "offline"
                    result.message = "XHA 已在线，外网验证通过。" if result.online else "XHA 已在线，但外网尚未通过验证；未重复登录。"
                    return self._finish(result, start)
                if status["state"] != "offline":
                    result.status, result.message = "unknown", "XHA 登录状态无法确认，未提交密码。"
                    return self._finish(result, start)
                if intent == "refresh":
                    result.session = {"availability": "unavailable", "login_state": "offline", "message": "XHA 确认未登录。"}
                    result.status, result.message = "offline", "XHA 确认未登录；信息刷新不提交密码。"
                    return self._finish(result, start)
                if not authenticate_allowed:
                    result.status, result.message = "migration", "设置或旧入口尚未核实，已停止校园认证。"
                elif config is None or not config.username or not config.password:
                    result.status, result.message = "setup", "请先添加并选用校园网账号。"
                else:
                    if intent=="reconnect":
                        result.online=self.probe(client)
                        if not result.online:
                            result.detected_offline=True;self.on_offline()
                    authentication=self.policy.scope('authentication')
                    authentication.before_request()
                    progress("authenticating", "XHA 确认未登录，正在认证。")
                    try: response = portal.authenticate(config)
                    except RequestFailure:
                        authentication.failure('认证连接暂不可用。');raise
                    result.authenticated = True
                    error = credential_error(response.message) if not response.accepted else ""
                    if error:
                        authentication.success()
                        result.credential_rejected, result.credential_message = True, error
                        result.status, result.message = "credentials", error
                        return self._finish(result, start)
                    if not response.accepted:
                        authentication.failure(response.message)
                        result.status, result.message = "offline", response.message
                        return self._finish(result, start)
                    authentication.success();result.credential_confirmed = True
                    progress("checking", "认证已响应，正在验证外网。")
                    for _ in range(3):
                        if self.cancel.wait(2): raise RequestsPaused("本轮已停止。")
                        if self.probe(client):
                            result.online, result.status = True, "online"
                            result.message = "外网已恢复，正在确认稳定连接。"
                            if not self.cancel.wait(2) and self.probe(client):
                                result.recovery_method = "校园网认证恢复" if result.detected_offline else ""
                            else:
                                result.online, result.status = False, "offline"
                            if intent == "reconnect" and result.online:
                                try: result.session = portal.fetch_session()
                                except RequestFailure as error: result.session_error = str(error)
                            break
                    if result.online: result.message = "外网验证通过，未重复提交认证。"
        except (RequestFailure, RequestsPaused) as error:
            result.status = "protected" if self.policy.paused else "offline"
            result.message = str(error)
            if isinstance(error, RequestFailure) and portal_phase and not campus_confirmed and not self.policy.paused:
                result.status = "noncampus"
                result.message = "当前连接无法访问校园认证服务，未提交账号信息。" + str(error)
        return self._finish(result, start)

    def _finish(self, result, start):
        if result.online:
            self.policy.success();self.policy.scope('authentication').success()
        result.failures = max(self.policy.failures,self.policy.scope('authentication').failures)
        result.blocked = self.policy.recovery_paused
        result.duration = max(0, self.clock() - start)
        if result.blocked and result.status!='credentials' and not result.online:
            result.status = "protected"
            result.message = self.policy.pause_message()
        return result
