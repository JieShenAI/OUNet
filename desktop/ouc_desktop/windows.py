"""Windows integrations. No shell interpolation of user data or visible consoles."""
from __future__ import annotations

import ctypes as c
from ctypes import wintypes as w
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import winreg
import uuid
import xml.etree.ElementTree as ET


class WindowsError(RuntimeError):
    pass


def high_contrast():
    class HighContrast(c.Structure):
        _fields_=[("size",w.UINT),("flags",w.DWORD),("scheme",w.LPWSTR)]
    value=HighContrast();value.size=c.sizeof(value)
    return bool(c.windll.user32.SystemParametersInfoW(0x42,value.size,c.byref(value),0) and value.flags&1)


class GUID(c.Structure):
    _fields_ = [("a", w.DWORD), ("b", w.WORD), ("d", w.WORD), ("e", c.c_ubyte * 8)]


class Interface(c.Structure):
    _fields_ = [("guid", GUID), ("description", w.WCHAR * 256), ("state", c.c_int)]


class SSID(c.Structure):
    _fields_ = [("length", w.ULONG), ("bytes", c.c_ubyte * 32)]


class Association(c.Structure):
    _fields_ = [("ssid", SSID), ("bss_type", c.c_int), ("bssid", c.c_ubyte * 6),
                ("phy_type", c.c_int), ("phy_index", w.ULONG), ("quality", w.ULONG),
                ("rx", w.ULONG), ("tx", w.ULONG)]


class Connection(c.Structure):
    _fields_ = [("state", c.c_int), ("mode", c.c_int), ("profile", w.WCHAR * 256),
                ("association", Association), ("security", w.DWORD * 4)]


class Profile(c.Structure):
    _fields_ = [("name", w.WCHAR * 256), ("flags", w.DWORD)]


class Parameters(c.Structure):
    _fields_ = [("mode", c.c_int), ("profile", w.LPCWSTR), ("ssid", c.POINTER(SSID)),
                ("bssid_list", c.c_void_p), ("bss_type", c.c_int), ("flags", w.DWORD)]


class WifiAdapter:
    def __init__(self):
        self.api = c.WinDLL("wlanapi", use_last_error=True)
        self.api.WlanOpenHandle.argtypes = [w.DWORD, c.c_void_p, c.POINTER(w.DWORD), c.POINTER(w.HANDLE)]
        self.api.WlanCloseHandle.argtypes = [w.HANDLE, c.c_void_p]
        self.api.WlanEnumInterfaces.argtypes = [w.HANDLE, c.c_void_p, c.POINTER(c.c_void_p)]
        self.api.WlanFreeMemory.argtypes = [c.c_void_p]
        self.api.WlanQueryInterface.argtypes = [w.HANDLE, c.POINTER(GUID), c.c_int, c.c_void_p,
                                               c.POINTER(w.DWORD), c.POINTER(c.c_void_p), c.POINTER(c.c_int)]
        self.api.WlanGetProfileList.argtypes = [w.HANDLE, c.POINTER(GUID), c.c_void_p, c.POINTER(c.c_void_p)]
        self.api.WlanGetProfile.argtypes = [w.HANDLE, c.POINTER(GUID), w.LPCWSTR, c.c_void_p,
                                           c.POINTER(c.c_void_p), c.POINTER(w.DWORD), c.POINTER(w.DWORD)]
        self.api.WlanConnect.argtypes = [w.HANDLE, c.POINTER(GUID), c.POINTER(Parameters), c.c_void_p]
        self.handle = w.HANDLE()
        negotiated = w.DWORD()
        self._check(self.api.WlanOpenHandle(2, None, c.byref(negotiated), c.byref(self.handle)))
        self.callback = None

    @staticmethod
    def _check(code):
        if code:
            if code == 5:
                raise WindowsError("无法读取 Wi-Fi：访问被系统拒绝。请检查 Windows 位置服务及桌面应用的位置访问权限。")
            if code == 1062:
                raise WindowsError("WLAN 服务未运行，请在 Windows 网络设置中启用 Wi-Fi。")
            raise WindowsError(f"Windows 无线网络操作失败（代码 {code}），请检查 Wi-Fi 和已保存配置。")

    def interfaces(self):
        pointer = c.c_void_p()
        self._check(self.api.WlanEnumInterfaces(self.handle, None, c.byref(pointer)))
        try:
            count = c.cast(pointer, c.POINTER(w.DWORD))[0]
            array = c.cast(pointer.value + 8, c.POINTER(Interface))
            return [Interface.from_buffer_copy(array[i]) for i in range(count)]
        finally:
            self.api.WlanFreeMemory(pointer)

    def current(self, interface_id=""):
        result = []
        for interface in self.interfaces():
            if interface_id and str(uuid.UUID(bytes_le=bytes(interface.guid))) != interface_id:
                continue
            if interface.state != 1:
                continue
            pointer, size, opcode = c.c_void_p(), w.DWORD(), c.c_int()
            self._check(self.api.WlanQueryInterface(self.handle, c.byref(interface.guid), 7, None,
                                                   c.byref(size), c.byref(pointer), c.byref(opcode)))
            try:
                connection = c.cast(pointer, c.POINTER(Connection)).contents
                ssid = connection.association.ssid
                name = bytes(ssid.bytes[:min(ssid.length, 32)]).decode("utf-8", "replace")
                result.append(name)
            finally:
                self.api.WlanFreeMemory(pointer)
        return result

    def profiles(self):
        result = set()
        for interface in self.interfaces():
            pointer = c.c_void_p()
            self._check(self.api.WlanGetProfileList(self.handle, c.byref(interface.guid), None, c.byref(pointer)))
            try:
                count = c.cast(pointer, c.POINTER(w.DWORD))[0]
                profiles = c.cast(pointer.value + 8, c.POINTER(Profile))
                result.update(profiles[i].name for i in range(count))
            finally:
                self.api.WlanFreeMemory(pointer)
        return sorted(result)

    def connected_items(self):
        result = []
        for interface in self.interfaces():
            if interface.state != 1: continue
            pointer, size, opcode = c.c_void_p(), w.DWORD(), c.c_int()
            self._check(self.api.WlanQueryInterface(self.handle, c.byref(interface.guid), 7, None,
                                                   c.byref(size), c.byref(pointer), c.byref(opcode)))
            try:
                connection = c.cast(pointer, c.POINTER(Connection)).contents
                ssid = connection.association.ssid
                result.append({"id": str(uuid.UUID(bytes_le=bytes(interface.guid))),
                               "ssid": bytes(ssid.bytes[:min(ssid.length, 32)]).decode("utf-8", "replace"),
                               "bssid": bytes(connection.association.bssid).hex()})
            finally: self.api.WlanFreeMemory(pointer)
        return result

    def profile_items(self):
        result = []
        for interface in self.interfaces():
            interface_id = str(uuid.UUID(bytes_le=bytes(interface.guid)))
            pointer = c.c_void_p()
            self._check(self.api.WlanGetProfileList(self.handle, c.byref(interface.guid), None, c.byref(pointer)))
            try:
                count = c.cast(pointer, c.POINTER(w.DWORD))[0]
                profiles = c.cast(pointer.value + 8, c.POINTER(Profile))
                names = [profiles[i].name for i in range(count)]
            finally: self.api.WlanFreeMemory(pointer)
            for name in names:
                xml_pointer, flags, access = c.c_void_p(), w.DWORD(0), w.DWORD()
                # flags=0: never request WLAN_PROFILE_GET_PLAINTEXT_KEY.
                self._check(self.api.WlanGetProfile(self.handle, c.byref(interface.guid), name, None,
                                                   c.byref(xml_pointer), c.byref(flags), c.byref(access)))
                try:
                    ssid = profile_ssid(c.wstring_at(xml_pointer))
                finally: self.api.WlanFreeMemory(xml_pointer)
                if not ssid: continue
                result.append({"id": interface_id + ":" + name, "interfaceId": interface_id,
                               "profile": name, "ssid": ssid, "label": ssid,
                               "adapter": interface.description})
        for item in result:
            if sum(x["ssid"] == item["ssid"] for x in result) > 1:
                item["label"] = f'{item["ssid"]} · {item["adapter"]} · {item["profile"]}'
        return sorted(result, key=lambda item: item["label"])

    def connect(self, profile: str, ssid: str, cancelled, interface_id="") -> bool:
        # Profile names are passed directly to wlanapi, never a command shell.
        encoded = ssid.encode("utf-8")
        if not encoded or len(encoded) > 32 or profile not in self.profiles():
            raise WindowsError("未找到目标 Wi-Fi 的已保存配置，请先在 Windows 中手动连接一次。")
        dot = SSID(len(encoded), (c.c_ubyte * 32)(*encoded))
        parameters = Parameters(0, profile, c.pointer(dot), None, 3, 0)
        matching = [item for item in self.profile_items() if item["profile"] == profile and item["ssid"] == ssid
                    and (not interface_id or item["interfaceId"] == interface_id)]
        if len(matching) != 1:
            raise WindowsError("无线配置无法唯一匹配，请在账号与网络中重新选择 Wi-Fi。")
        selected_id = matching[0]["interfaceId"]
        interfaces = [i for i in self.interfaces() if str(uuid.UUID(bytes_le=bytes(i.guid))) == selected_id]
        if not interfaces:
            raise WindowsError("没有可用的无线网卡，请检查 Wi-Fi 是否已启用。")
        self._check(self.api.WlanConnect(self.handle, c.byref(interfaces[0].guid), c.byref(parameters), None))
        for _ in range(6):
            if cancelled.wait(2):
                return False
            if ssid in self.current(selected_id):
                return True
        return False

    def subscribe(self, callback):
        # Notifications are coalesced by the UI controller; callback only queues a signal.
        callback_type = c.WINFUNCTYPE(None, c.c_void_p, c.c_void_p)
        self.callback = callback_type(lambda data, context: callback())
        self.api.WlanRegisterNotification.argtypes = [w.HANDLE, w.DWORD, w.BOOL, callback_type,
                                                      c.c_void_p, c.c_void_p, c.POINTER(w.DWORD)]
        previous = w.DWORD()
        self._check(self.api.WlanRegisterNotification(self.handle, 8, True, self.callback, None, None, c.byref(previous)))

    def close(self):
        if self.handle:
            self.api.WlanCloseHandle(self.handle, None)
            self.handle = None
        self.callback = None


class LazyWifiAdapter:
    """Internet may already work over Ethernet even if WLAN service is disabled."""
    def __init__(self): self.adapter = None
    def _get(self):
        if self.adapter is None: self.adapter = WifiAdapter()
        return self.adapter
    def current(self, *args): return self._get().current(*args)
    def connect(self, *args): return self._get().connect(*args)
    def close(self):
        if self.adapter: self.adapter.close()


def profile_ssid(xml):
    try:
        root = ET.fromstring(xml)
        for node in root.iter():
            if node.tag.rsplit("}", 1)[-1] == "SSID":
                values = {child.tag.rsplit("}", 1)[-1]: child.text for child in node}
                if values.get("hex"):
                    return bytes.fromhex(values["hex"]).decode("utf-8")
                return values.get("name") or ""
    except (ET.ParseError, ValueError, UnicodeError): pass
    return ""


def boot_session():
    """Boot event 27 distinguishes cold/hybrid boots from full hibernate resume."""
    script = r'''[Console]::OutputEncoding=[Text.UTF8Encoding]::new(); $ErrorActionPreference='Stop'
$events=Get-WinEvent -FilterHashtable @{LogName='System';ProviderName='Microsoft-Windows-Kernel-Boot';Id=27} -MaxEvents 64
foreach($event in $events) {
  $xml=[xml]$event.ToXml()
  $type=($xml.Event.EventData.Data | Where-Object {$_.Name -eq 'BootType'}).'#text'
  if($type -in @('0','1','0x0','0x1')) {
    @{id=('boot27:'+[string]$event.RecordId+':'+$event.TimeCreated.ToUniversalTime().ToString('o'));started_at=$event.TimeCreated.ToString('o')} | ConvertTo-Json -Compress
    break
  }
}'''
    try:
        data = json.loads(powershell(script))
        if isinstance(data.get("id"), str) and isinstance(data.get("started_at"), str): return data
    except (WindowsError, ValueError, TypeError, AttributeError, subprocess.SubprocessError): pass
    return None


def powershell(script: str):
    import base64
    encoded = base64.b64encode(script.encode("utf-16le")).decode("ascii")
    result = subprocess.run(["powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded],
                            capture_output=True, timeout=25, creationflags=subprocess.CREATE_NO_WINDOW)
    if result.returncode:
        raise WindowsError("无法读取或修改匹配的任务计划；请在任务计划程序中检查权限并手动处理。")
    return result.stdout.decode("utf-8-sig", "replace").strip()


def action_matches(action: dict, directory: Path) -> bool:
    """Match actual script file paths, not a substring or a task name."""
    directory = directory.resolve()
    allowed = {str((directory / name).resolve()).casefold() for name in
               ("auto_login.py", "start.cmd", "login_once.cmd", "diagnose.cmd")}
    working = str(action.get("WorkingDirectory") or "").strip('"')
    tokens = re.findall(r'"[^"]*"|\S+', str(action.get("Arguments") or ""))
    executable = str(action.get("Execute") or "").strip('"')
    executable_name = Path(executable).name.casefold()
    if Path(executable).suffix.casefold() in (".py", ".cmd"):
        tokens = [executable]
    elif executable_name in ("python.exe", "pythonw.exe", "py.exe", "python", "pythonw", "py"):
        if any(t.casefold() in ("-c", "-m") for t in tokens): return False
    elif executable_name in ("cmd.exe", "cmd"):
        if len(tokens) < 2 or tokens[0].casefold() not in ("/c", "/k"): return False
        tokens = tokens[1:2]
    else:
        return False
    for token in tokens:
        token = os.path.expandvars(token.strip('"'))
        if Path(token).suffix.lower() not in (".py", ".cmd"):
            continue
        path = Path(token)
        if not path.is_absolute():
            if not working:
                continue
            path = Path(os.path.expandvars(working)) / path
        if str(path.resolve()).casefold() in allowed:
            return True
    return False


def legacy_tasks(directory: Path):
    raw = powershell("[Console]::OutputEncoding=[Text.UTF8Encoding]::new(); $ErrorActionPreference='Stop'; "
                     "@(Get-ScheduledTask | Select-Object TaskName,TaskPath,State,Actions) | ConvertTo-Json -Depth 6 -Compress")
    try:
        items = json.loads(raw or "[]")
        if isinstance(items, dict): items = [items]
        return [{"name": item["TaskName"], "path": item["TaskPath"], "enabled": int(item["State"]) != 1}
                for item in items if any(action_matches(a, directory) for a in item.get("Actions", []))]
    except (ValueError, TypeError, KeyError):
        raise WindowsError("任务计划结果无法识别，已保留迁移待处理状态。") from None


def disable_legacy_tasks(directory: Path, selected: list[dict]):
    # Re-discover and re-match every selected task before modifying it.
    actual = {(t["path"], t["name"]): t for t in legacy_tasks(directory)}
    for task in selected:
        key = (task["path"], task["name"])
        if key not in actual:
            raise WindowsError("旧任务已变化，请重新扫描后再确认停用。")
        payload = json.dumps({"name": key[1], "path": key[0]}, ensure_ascii=False).encode("utf-8")
        import base64
        value = base64.b64encode(payload).decode("ascii")
        powershell("$ErrorActionPreference='Stop'; $t=([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + value + "')) | ConvertFrom-Json); "
                   "Disable-ScheduledTask -TaskName $t.name -TaskPath $t.path | Out-Null")
    if any(t["enabled"] for t in legacy_tasks(directory)):
        raise WindowsError("仍有匹配的旧任务处于启用状态，新守护暂不开启。")


class StartupManager:
    KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
    NAME = "OUCNetworkAssistant"

    @staticmethod
    def command():
        if getattr(sys, "frozen", False):
            parts = [sys.executable, "--background"]
        else:
            pythonw = Path(sys.executable).with_name("pythonw.exe")
            parts = [str(pythonw), str(Path(__file__).resolve().parents[1] / "run.py"), "--background"]
        return subprocess.list2cmdline(parts)

    def enabled(self):
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, self.KEY) as key:
                return winreg.QueryValueEx(key, self.NAME)[0] == self.command()
        except FileNotFoundError:
            return False

    def set(self, enabled: bool):
        try:
            with winreg.CreateKey(winreg.HKEY_CURRENT_USER, self.KEY) as key:
                if enabled:
                    winreg.SetValueEx(key, self.NAME, 0, winreg.REG_SZ, self.command())
                else:
                    try: winreg.DeleteValue(key, self.NAME)
                    except FileNotFoundError: pass
        except OSError:
            raise WindowsError("无法保存登录后自启设置，请检查当前用户权限。") from None
