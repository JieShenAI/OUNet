"""Read-only Windows adapter context and interface-bound IPv4 HTTP transport.

GetAdaptersAddresses and IP_UNICAST_IF are Windows APIs; no SSID/profile switching.
The source IP plus outgoing interface are both pinned for the whole operation.
"""
from dataclasses import dataclass
import ctypes as c
from ctypes import wintypes as w
import hashlib
import socket

from requests.adapters import HTTPAdapter
from urllib3.connection import HTTPConnection
from .windows import WifiAdapter, WindowsError


class SocketAddress(c.Structure):
    _fields_ = [("address", c.c_void_p), ("length", c.c_int)]


class Unicast(c.Structure):
    pass
Unicast._fields_ = [("length", w.ULONG), ("flags", w.DWORD), ("next", c.POINTER(Unicast)), ("socket", SocketAddress)]


class Adapter(c.Structure):
    pass
Adapter._fields_ = [("length", w.ULONG), ("index", w.DWORD), ("next", c.POINTER(Adapter)),
                    ("name", c.c_char_p), ("unicast", c.POINTER(Unicast)),
                    ("anycast", c.c_void_p), ("multicast", c.c_void_p), ("dns", c.c_void_p),
                    ("dns_suffix", c.c_wchar_p), ("description", c.c_wchar_p), ("friendly", c.c_wchar_p),
                    ("physical", c.c_ubyte * 8), ("physical_length", w.DWORD), ("flags", w.DWORD),
                    ("mtu", w.DWORD), ("kind", w.DWORD), ("status", c.c_int), ("ipv6_index", w.DWORD)]


def adapter_addresses():
    api = c.WinDLL("iphlpapi")
    api.GetAdaptersAddresses.argtypes = [w.ULONG, w.ULONG, c.c_void_p, c.c_void_p, c.POINTER(w.ULONG)]
    size = w.ULONG(16_384)
    for _ in range(3):
        buffer = c.create_string_buffer(size.value)
        code = api.GetAdaptersAddresses(socket.AF_UNSPEC, 14, None, buffer, c.byref(size))
        if code == 111: continue
        if code: raise WindowsError("无法读取当前网络接口，请检查 Windows 网络服务。")
        result, pointer = [], c.cast(buffer, c.POINTER(Adapter))
        while pointer:
            a = pointer.contents
            ips, u = [], a.unicast
            while u:
                value = u.contents
                raw = c.string_at(value.socket.address, value.socket.length)
                if len(raw) >= 8 and int.from_bytes(raw[:2], "little") == socket.AF_INET:
                    ip = socket.inet_ntop(socket.AF_INET, raw[4:8])
                    if not ip.startswith(("127.", "169.254.")) and ip != "0.0.0.0": ips.append(ip)
                u = value.next
            result.append({"id": (a.name or b"").decode("ascii", "replace").strip("{}").lower(),
                           "index": a.index, "kind": a.kind, "up": a.status == 1,
                           "name": a.friendly or a.description or "网络接口", "ips": sorted(set(ips))})
            pointer = a.next
        return result
    raise WindowsError("网络接口正在变化，请稍后重新检测。")


@dataclass(frozen=True)
class NetworkContext:
    interface: str = ""
    index: int = 0
    source: str = ""
    name: str = "当前网络"
    ssid: str = ""
    bssid: str = ""
    reason: str = ""
    kind: str = "Wi-Fi"

    @property
    def key(self):
        return hashlib.sha256(f"{self.interface}:{self.index}:{self.source}:{self.bssid}".encode()).hexdigest() if self.source else ""

    @property
    def valid(self): return bool(self.source and self.index and not self.reason)

    def unchanged(self):
        return any(a["up"] and a["id"] == self.interface and a["index"] == self.index
                   and self.source in a["ips"] for a in adapter_addresses())


def current_context():
    adapters = adapter_addresses()
    wifi = [a for a in adapters if a["up"] and a["kind"] == 71 and a["ips"]]
    wired = [a for a in adapters if a["up"] and a["kind"] == 6 and a["ips"]]
    if not wifi and len(wired)==1:
        a=wired[0]
        return NetworkContext(a["id"],a["index"],a["ips"][0],a["name"],kind="Ethernet")
    if len(wifi) != 1:
        return NetworkContext(reason="正在确认当前连接：多个无线接口，未提交凭据。" if len(wifi) > 1 else
                              "未发现可唯一确认的 IPv4 网络接口，请检查 Windows 网络设置。")
    a = wifi[0]
    ssid, bssid = "", ""
    try:
        wlan = WifiAdapter()
        try:
            item = next((v for v in wlan.connected_items() if v["id"] == a["id"]), {})
            ssid, bssid = item.get("ssid", ""), item.get("bssid", "")
        finally: wlan.close()
    except (WindowsError, OSError):
        pass  # Adapter identity and IPv4 remain independently confirmed.
    return NetworkContext(a["id"], a["index"], a["ips"][0], a["name"], ssid, bssid)


class BoundAdapter(HTTPAdapter):
    def __init__(self, context):
        self.context = context
        super().__init__(max_retries=0)

    def init_poolmanager(self, connections, maxsize, block=False, **kwargs):
        kwargs["source_address"] = (self.context.source, 0)
        kwargs["socket_options"] = list(HTTPConnection.default_socket_options) + [
            (socket.IPPROTO_IP, 31, socket.htonl(self.context.index))]
        super().init_poolmanager(connections, maxsize, block=block, **kwargs)
