"""One outcome per actual HTTP request; persistent three-failure circuit breaker."""
from __future__ import annotations

from datetime import datetime
import json
from pathlib import Path
import threading
import time
from collections import deque
from urllib.parse import urlsplit

import requests
from requests.adapters import HTTPAdapter

from .storage import StorageError, write_json


def now_text():
    return datetime.now().astimezone().isoformat(timespec="seconds")


class RequestsPaused(RuntimeError):
    pass


class RequestFailure(RuntimeError):
    pass


class RequestStats:
    """Process scope, monotonic rolling window. Count dispatch, including errors."""
    def __init__(self, clock=time.monotonic):
        self.clock, self.total, self.started_at = clock, 0, now_text()
        self.recent = deque()
        self.lock = threading.Lock()

    def record(self, url):
        if urlsplit(url).hostname != "xha.ouc.edu.cn": return
        with self.lock:
            self.total += 1
            self.recent.append(self.clock())

    def view(self):
        with self.lock:
            cutoff = self.clock() - 60
            while self.recent and self.recent[0] <= cutoff: self.recent.popleft()
            return {"total": self.total, "recent": len(self.recent), "startedAt": self.started_at}


class RequestPolicy:
    def __init__(self, path: Path):
        self.path = path
        self.trial = False
        self.state = {"version": 1, "failures": 0, "paused": False,
                      "last_failure_at": "", "last_error": "", "legacy_pause": False,
                      "rejected_accounts": []}
        self.stats = RequestStats()
        if path.exists():
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
                if (data.get("version") != 1 or type(data.get("failures")) is not int
                        or not 0 <= data["failures"] <= 3 or type(data.get("paused")) is not bool
                        or (data["failures"] == 3 and not data["paused"])
                        or not isinstance(data.get("rejected_accounts", []), list)
                        or any(not isinstance(v, str) for v in data.get("rejected_accounts", []))
                        or any(not isinstance(data.get(k, ""), str) for k in ("last_failure_at", "last_error"))):
                    raise ValueError
                self.state.update(data)
                errors = self.state.get("credential_errors", {})
                if not isinstance(errors, dict) or any(not isinstance(k, str) or not isinstance(v, dict)
                       or not all(isinstance(v.get(f), str) for f in ("revision", "message")) for k,v in errors.items()):
                    raise ValueError
            except (OSError, ValueError, TypeError, AttributeError):
                raise StorageError("网络请求状态无法读取，已停止请求。请检查本地数据。") from None

    @property
    def failures(self): return self.state["failures"]

    @property
    def paused(self): return self.state["paused"]

    def _save(self, **updates):
        data = dict(self.state, **updates)
        try:
            write_json(self.path, data)
        except StorageError:
            self.state["paused"] = True
            self.trial = False
            raise
        self.state = data

    def before_request(self):
        if self.paused and not self.trial:
            raise RequestsPaused("网络请求已暂停，请手动重试。")

    def begin_trial(self):
        self.trial = self.paused

    def success(self):
        if self.failures or self.paused:
            self._save(failures=0, paused=False, legacy_pause=False)
        self.trial = False

    def failure(self, message):
        failures = 3 if self.trial else min(3, self.failures + 1)
        self.trial = False
        self._save(failures=failures, paused=failures == 3,
                   last_failure_at=now_text(), last_error=message)

    def import_legacy_pause(self):
        self._save(paused=True, legacy_pause=True)

    def reject_account(self, account_id):
        rejected = set(self.state["rejected_accounts"])
        rejected.add(account_id)
        self._save(rejected_accounts=sorted(rejected))

    def allow_account(self, account_id):
        if account_id in self.state["rejected_accounts"]:
            self._save(rejected_accounts=[a for a in self.state["rejected_accounts"] if a != account_id])

    def credential_error(self, account_id, revision):
        entry = self.state.get("credential_errors", {}).get(account_id, {})
        if entry.get("revision") == revision: return entry.get("message", "该账号曾被拒绝，请检查凭据。")
        if account_id in self.state["rejected_accounts"]: return "该账号曾被拒绝，请检查凭据。"
        return ""

    def reject_credential(self, account_id, revision, message):
        errors = dict(self.state.get("credential_errors", {}))
        errors[account_id] = {"revision": revision, "message": message}
        self._save(credential_errors=errors)

    def clear_credential(self, account_id):
        errors = dict(self.state.get("credential_errors", {}))
        errors.pop(account_id, None)
        self._save(credential_errors=errors, rejected_accounts=[a for a in self.state["rejected_accounts"] if a != account_id])


class RequestClient:
    """Only the serial worker owns this client. Decode BEFORE settling a response."""
    def __init__(self, session, policy, timeout=8, cancel=None, adapter=None, network_guard=None):
        self.session, self.policy, self.timeout = session, policy, timeout
        self.cancel = cancel or threading.Event()
        self.network_guard = network_guard
        self.session.trust_env = False
        self.session.mount("https://", adapter or HTTPAdapter(max_retries=0))
        self.session.mount("http://", adapter or HTTPAdapter(max_retries=0))

    def get(self, url, stage, decoder=lambda text, response: text, params=None, probe=False, headers=None, accept_redirect=False):
        self.policy.before_request()
        if self.cancel.is_set():
            raise RequestsPaused("当前检查已停止。")
        if self.network_guard and not self.network_guard():
            raise RequestsPaused("连接接口已经变化，本轮结果已丢弃。")
        try:
            headers = dict(headers or {}, **{"Cache-Control": "no-cache"})
            if urlsplit(url).hostname == "xha.ouc.edu.cn":
                headers["Referer"] = "https://xha.ouc.edu.cn/a79.htm"
            self.policy.stats.record(url)
            with self.session.get(url, params=params, timeout=self.timeout, allow_redirects=False,
                                  stream=True, headers=headers) as response:
                if not (response.status_code == 200 or (probe and 300 <= response.status_code < 400)
                        or (accept_redirect and response.status_code in (301,302,303,307,308))):
                    raise ValueError("http")
                chunks, size = [], 0
                for chunk in response.iter_content(chunk_size=16384):
                    size += len(chunk)
                    if size > 1_000_000:
                        raise ValueError("size")
                    chunks.append(chunk)
                raw = b"".join(chunks)
                # Portal pages/scripts are GBK, while JSONP is UTF-8.
                try: text = raw.decode("utf-8-sig")
                except UnicodeDecodeError: text = raw.decode("gb18030", "replace")
                value = decoder(text, response)
        except requests.exceptions.SSLError:
            message = f"{stage}：HTTPS 证书校验失败。"
        except requests.Timeout:
            message = f"{stage}：请求超时。"
        except (requests.RequestException, OSError):
            message = f"{stage}：网络连接失败。"
        except (ValueError, TypeError, KeyError, AttributeError, OverflowError):
            message = f"{stage}：HTTP 服务异常或响应格式无效。"
        else:
            self.policy.success()
            return value
        self.policy.failure(message)
        raise RequestFailure(message)
