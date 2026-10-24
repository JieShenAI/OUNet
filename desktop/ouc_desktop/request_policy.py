"""Scoped HTTP outcomes and persistent, bounded recovery cooldowns."""
from __future__ import annotations

from datetime import datetime
import json
from pathlib import Path
import threading
import time
import math
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


class HttpStatusFailure(ValueError):
    """A safe service message which settles the actual request as failed."""
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
    DELAYS = (15, 30, 60, 120, 300)

    def __init__(self, path: Path, clock=time.time, monotonic=time.monotonic):
        self.path = path
        self.clock, self.monotonic = clock, monotonic
        self.deadline = 0
        self.last_early = float('-inf')
        self.children = {}
        self.storage_failed = False
        self.trial = False
        self.state = {"version": 1, "failures": 0, "paused": False,
                      "last_failure_at": "", "last_error": "", "legacy_pause": False,
                      "rejected_accounts": [], "retry_level": 0, "retry_at": 0}
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
                if (type(self.state['retry_level']) is not int or not 0 <= self.state['retry_level'] <= len(self.DELAYS)
                        or type(self.state['retry_at']) not in (int,float) or not math.isfinite(self.state['retry_at'])
                        or self.state['retry_at'] < 0):
                    raise ValueError
                errors = self.state.get("credential_errors", {})
                if not isinstance(errors, dict) or any(not isinstance(k, str) or not isinstance(v, dict)
                       or not all(isinstance(v.get(f), str) for f in ("revision", "message")) for k,v in errors.items()):
                    raise ValueError
            except (OSError, ValueError, TypeError, AttributeError):
                raise StorageError("网络请求状态无法读取，已停止请求。请检查本地数据。") from None
        if self.paused and not self.state['legacy_pause']:
            # Old persistent pauses become a bounded first cooldown on upgrade.
            if not self.state['retry_level']:
                self._save(retry_level=1,retry_at=self.clock()+self.DELAYS[0])
            limit=self.DELAYS[self.state['retry_level']-1]
            self.deadline=self.monotonic()+min(limit,max(0,self.state['retry_at']-self.clock()))

    def scope(self, name):
        if name not in ('external','update','authentication'): raise ValueError('Unknown request scope')
        if name not in self.children:
            try:self.children[name]=RequestPolicy(self.path.with_name(self.path.stem+'-'+name+self.path.suffix),self.clock,self.monotonic)
            except StorageError:
                self.storage_failed=True;raise
        return self.children[name]

    @property
    def recovery_paused(self): return self.hard_paused or self.paused or self.scope('authentication').paused

    @property
    def hard_paused(self):
        auth=self.children.get('authentication')
        return self.storage_failed or self.state['legacy_pause'] or bool(auth and auth.storage_failed)

    @property
    def retry_seconds(self):
        return max(self.remaining,self.scope('authentication').remaining)

    @property
    def remaining(self):
        return max(0,self.deadline-self.monotonic()) if self.paused and not self.state['legacy_pause'] else 0

    def pause_message(self):
        if self.hard_paused and not self.state['legacy_pause']:return '本地复检状态无法保存，已停止请求，请检查本地数据。'
        if self.state['legacy_pause']: return '旧版暂停待处理，请手动重试。'
        auth=self.children.get('authentication')
        seconds=max(self.remaining,auth.remaining if auth else 0)
        return f'暂时暂停，{max(1,math.ceil(seconds))} 秒后自动复检；恢复后继续守护。'

    def expedite(self):
        now=self.monotonic()
        if now-self.last_early<15:return
        if not self.paused and not any(child.paused for child in self.children.values()):return
        self.last_early=now
        if self.paused and not self.state['legacy_pause'] and not self.storage_failed:
            self.deadline=now
            self._save(retry_at=self.clock())
        for child in self.children.values(): child.expedite()

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
            self.storage_failed = True
            raise
        self.state = data

    def before_request(self):
        if self.storage_failed:raise RequestsPaused('本地复检状态异常，已停止请求。')
        if self.paused and not self.trial:
            if not self.state['legacy_pause'] and self.remaining<=0:self.begin_trial()
            else:raise RequestsPaused(self.pause_message())

    def begin_trial(self):
        self.trial = self.paused

    def success(self):
        if self.failures or self.paused:
            self._save(failures=0, paused=False, legacy_pause=False,retry_level=0,retry_at=0)
        self.deadline=0
        self.trial = False

    def failure(self, message):
        was_paused=self.paused
        failures = 3 if self.trial else min(3, self.failures + 1)
        self.trial = False
        level=min(len(self.DELAYS),self.state['retry_level']+1) if was_paused else 1 if failures==3 else 0
        delay=self.DELAYS[level-1] if level else 0
        self._save(failures=failures, paused=failures == 3,retry_level=level,retry_at=self.clock()+delay if delay else 0,
                   last_failure_at=now_text(), last_error=message)
        self.deadline=self.monotonic()+delay

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

    def get(self, url, stage, decoder=lambda text, response: text, params=None, probe=False, headers=None, accept_redirect=False, deadline=None):
        if self.cancel.is_set():
            raise RequestsPaused("当前检查已停止。")
        if self.network_guard and not self.network_guard():
            raise RequestsPaused("连接接口已经变化，本轮结果已丢弃。")
        host=urlsplit(url).hostname or ''
        policy=self.policy.scope('update') if host in ('api.github.com','github.com') or host.endswith('.githubusercontent.com') else self.policy.scope('external') if probe else self.policy
        policy.before_request()
        try:
            timeout = self.timeout
            if deadline is not None:
                remaining = deadline - time.monotonic()
                if remaining <= 0: raise requests.Timeout()
                timeout = min(timeout, remaining)
            headers = dict(headers or {}, **{"Cache-Control": "no-cache"})
            if urlsplit(url).hostname == "xha.ouc.edu.cn":
                headers["Referer"] = "https://xha.ouc.edu.cn/a79.htm"
            self.policy.stats.record(url)
            with self.session.get(url, params=params, timeout=timeout, allow_redirects=False,
                                  stream=True, headers=headers) as response:
                if response.status_code in (403, 429) and urlsplit(url).hostname == "api.github.com":
                    retry = str(response.headers.get("Retry-After", ""))
                    detail = f"，建议 {retry} 秒后重试" if retry.isdigit() and len(retry) <= 8 else "，请稍后重试"
                    raise HttpStatusFailure(f"GitHub 拒绝请求或请求受限（HTTP {response.status_code}）{detail}；也可打开发布页。")
                if not (response.status_code == 200 or (probe and 300 <= response.status_code < 400)
                        or (accept_redirect and response.status_code in (301,302,303,307,308))):
                    raise ValueError("http")
                chunks, size = [], 0
                for chunk in response.iter_content(chunk_size=16384):
                    if deadline is not None and time.monotonic() >= deadline: raise requests.Timeout()
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
        except HttpStatusFailure as error:
            message = f"{stage}：{error}"
        except (ValueError, TypeError, KeyError, AttributeError, OverflowError):
            message = f"{stage}：HTTP 服务异常或响应格式无效。"
        else:
            try:policy.success()
            except StorageError:
                self.policy.storage_failed=True;raise
            return value
        try:policy.failure(message)
        except StorageError:
            self.policy.storage_failed=True;raise
        raise RequestFailure(message)
