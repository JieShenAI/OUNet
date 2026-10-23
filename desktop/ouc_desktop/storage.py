"""Versioned, atomic user data and current-user Windows DPAPI credentials."""
from __future__ import annotations

import ctypes
from ctypes import wintypes
from dataclasses import asdict, dataclass, fields
from contextlib import contextmanager
from datetime import datetime
import json
import os
from pathlib import Path
import re
import threading
import time
import uuid


class StorageError(RuntimeError):
    pass


def atomic_write(path: Path, content: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    try:
        with temporary.open("wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        # Defender/indexing can briefly hold the destination without delete-share.
        # Retry only the atomic rename, never fall back to an in-place plaintext write.
        for attempt, delay in enumerate((.02, .05, .1, .2, 0)):
            try:
                os.replace(temporary, path)
                break
            except PermissionError:
                if attempt == 4: raise
                time.sleep(delay)
    except OSError:
        raise StorageError("无法保存本地数据，请检查磁盘空间和当前用户目录权限。") from None


def write_json(path: Path, data: dict):
    atomic_write(path, json.dumps(data, ensure_ascii=False, allow_nan=False, indent=2).encode("utf-8"))


@contextmanager
def configuration_transaction(directory: Path):
    """Two files (encrypted credentials/settings) must never be used half-updated."""
    marker = directory / "configuration.pending.json"
    write_json(marker, {"version": 1, "pending": True})
    yield
    try:
        marker.unlink()
    except OSError:
        raise StorageError("配置提交尚未完成，已停止认证。请检查目录权限后重新保存配置。") from None


@dataclass
class Settings:
    version: int = 3
    configured: bool = False
    ssid: str = ""
    profile: str = ""
    interval: int = 1
    interval_ms: int = 0
    appearance: str = "system"
    monitor: bool = False
    notifications: bool = True
    close_to_tray: bool = True
    auto_switch: bool = False
    legacy_directory: str = ""
    migration_pending: bool = False
    active_account_id: str = ""
    interface_id: str = ""

    def __post_init__(self):
        if not self.interval_ms:
            self.interval_ms = self.interval * 1000

    @classmethod
    def read(cls, path: Path):
        if not path.exists():
            return cls()
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(data, dict) or data.get("version") not in (1, 2, 3):
                raise ValueError
            if data.get("version")==3 and (type(data.get("interval_ms")) is not int or not 300<=data["interval_ms"]<=2_147_483_000):raise ValueError
            result = cls(**{f.name: data[f.name] for f in fields(cls) if f.name in data})
            result.version = 3
            for name in ("configured", "monitor", "notifications", "close_to_tray", "auto_switch", "migration_pending"):
                if type(getattr(result, name)) is not bool:
                    raise ValueError
            for name in ("ssid", "profile", "legacy_directory", "active_account_id", "interface_id"):
                if not isinstance(getattr(result, name), str):
                    raise ValueError
            if data.get("version") in (1, 2):
                if type(data.get("interval", 120)) is not int or not 1 <= data.get("interval", 120) <= 2_147_483:
                    raise ValueError
                result.interval_ms = data.get("interval", 120) * 1000
                result.auto_switch = False
            if type(result.interval_ms) is not int or not 300 <= result.interval_ms <= 2_147_483_000:
                raise ValueError
            result.interval = result.interval_ms / 1000
            if result.appearance not in ("system", "light", "dark"):
                raise ValueError
            return result
        except (OSError, ValueError, TypeError):
            raise StorageError("设置文件无法读取或格式损坏，已停止自动认证。请从备份恢复设置。") from None

    def save(self, path: Path):
        write_json(path, asdict(self))


class DataBlob(ctypes.Structure):
    _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_ubyte))]


class CredentialStore:
    def __init__(self, path: Path):
        self.path = path

    @staticmethod
    def _crypt(data: bytes, decrypt: bool = False) -> bytes:
        if os.name != "nt":
            raise StorageError("凭据加密只支持 Windows 当前用户。")
        buffer = ctypes.create_string_buffer(data)
        source = DataBlob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)))
        target = DataBlob()
        crypt = ctypes.WinDLL("crypt32", use_last_error=True)
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        function = crypt.CryptUnprotectData if decrypt else crypt.CryptProtectData
        function.argtypes = [ctypes.POINTER(DataBlob), ctypes.c_void_p, ctypes.c_void_p,
                             ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(DataBlob)]
        function.restype = wintypes.BOOL
        kernel.LocalFree.argtypes = [ctypes.c_void_p]
        kernel.LocalFree.restype = ctypes.c_void_p
        # UI_FORBIDDEN; deliberately never CRYPTPROTECT_LOCAL_MACHINE.
        if not function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
            raise StorageError("Windows 无法加密或解密凭据，请使用保存凭据的原 Windows 用户。")
        try:
            return ctypes.string_at(target.data, target.size)
        finally:
            kernel.LocalFree(target.data)

    def save(self, username: str, password: str):
        """Compatibility for import/smoke tests; preserve all previously saved accounts."""
        accounts = self.read_all() if self.path.exists() else {}
        account_id = next((key for key, value in accounts.items() if value["username"] == username.strip()), "")
        return self.put(account_id, username, password, "replace")

    def put(self, account_id, username, password="", action="keep", label=None):
        if not username.strip() or not password or len(username) > 128 or len(password) > 512:
            if not (username.strip() and len(username) <= 128 and action == "keep" and account_id):
                raise StorageError("请输入有效的校园网账号和密码。")
        if action not in ("keep", "replace"):
            raise StorageError("密码操作无效。")
        accounts = self.read_all() if self.path.exists() else {}
        if any(key != account_id and value["username"] == username.strip() for key, value in accounts.items()):
            raise StorageError("该账号已经保存，请在下拉框中选择。")
        if action == "keep":
            if account_id not in accounts: raise StorageError("新账号需要填写密码。")
            password = accounts[account_id]["password"]
        if not password or len(password) > 512: raise StorageError("请输入有效的校园网密码。")
        account_id = account_id or uuid.uuid4().hex
        previous = accounts.get(account_id, {})
        changed = previous.get("password") != password or previous.get("username") != username.strip()
        revision = previous.get("revision", "legacy") if not changed else uuid.uuid4().hex
        accounts[account_id] = {"username": username.strip(), "password": password,
                                "label": (label if label is not None else previous.get("label", ""))[:80],
                                "revision": revision}
        self._write_all(accounts)
        return account_id

    def _write_all(self, accounts):
        data = json.dumps({"version": 2, "accounts": accounts}, ensure_ascii=False).encode("utf-8")
        atomic_write(self.path, self._crypt(data))

    def remove(self, account_id):
        accounts = self.read_all()
        if account_id not in accounts: raise StorageError("该账号已不存在。")
        del accounts[account_id]
        self._write_all(accounts)
        return next(iter(accounts), "")

    def read_all(self):
        try:
            data = json.loads(self._crypt(self.path.read_bytes(), decrypt=True).decode("utf-8"))
            if data.get("version") == 1:
                account_id = uuid.uuid5(uuid.NAMESPACE_URL, "ouc-account:" + data["username"]).hex
                accounts = {account_id: {"username": data["username"], "password": data["password"]}}
            elif data.get("version") == 2:
                accounts = data["accounts"]
            else: raise ValueError
            if not isinstance(accounts, dict): raise ValueError
            for key, value in accounts.items():
                if (not isinstance(key, str) or not key or not isinstance(value, dict)
                        or any(not isinstance(value.get(k), str) or not value[k] for k in ("username", "password"))):
                    raise ValueError
                value.setdefault("label", "")
                value.setdefault("revision", "legacy")
                if not isinstance(value["label"], str) or not isinstance(value["revision"], str):
                    raise ValueError
            return accounts
        except (OSError, ValueError, KeyError, TypeError, UnicodeError):
            raise StorageError("保存的凭据无法读取，请在账号与网络页重新保存。") from None

    def read(self, account_id=None) -> tuple[str, str]:
        accounts = self.read_all()
        if account_id is None: account_id = next(iter(accounts), "")
        if account_id not in accounts: raise StorageError("请先添加并选择校园网账号。")
        return accounts[account_id]["username"], accounts[account_id]["password"]

    def summaries(self):
        return [{"id": key, "username": value["username"], "label": value["label"],
                 "revision": value["revision"], "hasSavedPassword": True}
                for key, value in self.read_all().items()]


class SnapshotStore:
    def __init__(self, path): self.path = path

    def read(self):
        if not self.path.exists(): return {}
        try:
            value = json.loads(CredentialStore._crypt(self.path.read_bytes(), True).decode("utf-8"))
            if not isinstance(value, dict) or any(not isinstance(v, str) for v in value.values()): raise ValueError
            return value
        except (StorageError, OSError, ValueError, UnicodeError): return {}

    def save(self, value):
        atomic_write(self.path, CredentialStore._crypt(json.dumps(value, ensure_ascii=False).encode("utf-8")))


class EventStore:
    """Small bounded JSONL history; accepts only locally built, sanitized text."""
    def __init__(self, directory: Path):
        self.path = directory / "logs" / "activity.jsonl"
        self.lock = threading.Lock()
        self.events = []
        if self.path.exists():
            try:
                if self.path.stat().st_size <= 1_000_000:
                    for line in self.path.read_text(encoding="utf-8").splitlines()[-300:]:
                        try:
                            entry = json.loads(line)
                            if (isinstance(entry, dict) and all(isinstance(entry.get(k), str) for k in ("time","kind","message"))
                                    and type(entry.get("sequence",0)) is int):
                                self.events.append(entry)
                        except ValueError:
                            continue
            except OSError:
                pass

    def add(self, kind: str, message: str):
        with self.lock:
            entry = {"sequence":max((e.get("sequence",0) for e in self.events),default=0)+1,
                     "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "kind": kind,
                     "message": re.sub(r"https?://\S+", "[链接已隐藏]", " ".join(message.split()))[:400]}
            self.events.append(entry)
            self.events = self.events[-300:]
            self.path.parent.mkdir(parents=True, exist_ok=True)
            atomic_write(self.path, ("\n".join(json.dumps(e, ensure_ascii=False) for e in self.events) + "\n").encode("utf-8"))

    def recent(self):
        with self.lock:
            return list(reversed(self.events))

    def export(self, target: Path, secrets: tuple[str, ...] = ()):
        entries = self.recent()
        content = "OUNet · 脱敏排障信息\n" + "\n".join(f'{e["time"]} [{e["kind"]}] {e["message"]}' for e in entries)
        for secret in sorted((s for s in secrets if s), key=len, reverse=True):
            content = content.replace(secret, "[已隐藏]")
        content = re.sub(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", "[IP 已隐藏]", content)
        content = re.sub(r"\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b", "[MAC 已隐藏]", content)
        atomic_write(target, ("\ufeff" + content).encode("utf-8"))
