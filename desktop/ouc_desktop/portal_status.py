"""Read-only online records, verified against xha a41.js/a43.js on 2026-10-22."""
from __future__ import annotations

from dataclasses import dataclass
import ipaddress
import re

import auto_login as core
from .request_policy import now_text


def json_object(text, response):
    return core.parse_jsonp(text)


def valid_object(required=()):
    def decode(text, response):
        data = core.parse_jsonp(text)
        if not all(key in data for key in required): raise ValueError("schema")
        return data
    return decode


def source_ip(data):
    for key in ("v46ip", "ss5", "v4ip"):
        try:
            address = ipaddress.ip_address(str(data.get(key, "")))
            if not address.is_unspecified: return str(address)
        except ValueError: pass
    return ""


def mac(value):
    return re.sub(r"[:-]", "", str(value)).lower()


@dataclass
class PortalConfig:
    api: str
    program: str
    page_index: str
    ip: str
    client_mac: str
    method: int
    prefix: str
    suffix: str


class PortalClient:
    def __init__(self, client):
        self.client, self.context = client, None

    def discover(self):
        if self.context is not None: return self.context
        def page_decode(text, response):
            if not re.search(r"<html\b", text, re.I): raise ValueError("page")
            return text
        page = self.client.get(core.PORTAL + "a79.htm", "读取认证页面", page_decode)
        def script_decode(text, response):
            port = re.search(r"\benHTTPSPort\s*=\s*(\d+)\b", text)
            program = re.search(r"\bname\s*:\s*['\"]([\w-]+)['\"]", text)
            if not port or not program or not 1 <= int(port[1]) <= 65535: raise ValueError("script")
            return int(port[1]), program[1]
        port, program = self.client.get(core.PORTAL + "a41.js", "读取认证脚本", script_decode)
        ip = source_ip({k: core.js_string(page, k) for k in ("v46ip", "ss5", "v4ip")})
        client_mac = mac(core.js_string(page, "ss4") or core.js_string(page, "olmac"))
        api = f"https://xha.ouc.edu.cn:{port}/eportal/portal/"
        def config_decode(text, response):
            data = core.parse_jsonp(text).get("data")
            if not isinstance(data, dict) or "login_method" not in data: raise ValueError("config")
            data["login_method"] = int(data["login_method"])
            return data
        data = self.client.get(api + "page/loadConfig", "读取认证配置", config_decode, {
            "program_index": program, "wlan_user_ip": ip or "0.0.0.0", "wlan_user_ipv6": "",
            "wlan_vlan_id": core.js_string(page, "vlanid", "1"),
            "callback": "dr1003", "jsVersion": "4.1", "lang": "zh-cn"})
        self.context = PortalConfig(api, program, str(data.get("page_index", "")), ip, client_mac,
                            int(data["login_method"]), ",0," if str(data.get("account_prefix")) == "1" else "",
                            str(data.get("account_suffix") or ""))
        return self.context

    def authenticate(self, config):
        context = self.discover()
        if context.method not in (1, 9):
            return core.AuthenticationResult(False, "网站当前认证方式不受支持，请检查学校系统变化。")
        result = self.client.get(context.api + "login", "提交校园网登录", valid_object(("result",)), {
            "callback": "dr1003", "jsVersion": "4.1", "lang": "zh-cn", "login_method": str(context.method),
            "user_account": context.prefix + config.username + context.suffix, "user_password": config.password,
            "wlan_user_ip": context.ip or "0.0.0.0", "wlan_user_ipv6": "",
            "wlan_user_mac": context.client_mac or "000000000000", "wlan_ac_ip": "",
            "wlan_ac_name": "", "terminal_type": "1"})
        message = core.safe_message(result.get("msg", "未提供错误说明"), config)
        accepted = str(result.get("result")) in ("1", "ok") or (str(result.get("ret_code")) == "2" and "已经在线" in message)
        return core.AuthenticationResult(accepted, "认证已响应，正在复查外网。" if accepted else message)

    def login_status(self):
        status = self.client.get(core.PORTAL + "drcom/chkstatus", "读取当前在线状态",
                                 valid_object(("result",)), {"callback": "dr1003", "jsVersion": "4.1"})
        status["state"] = {"1": "online", "0": "offline"}.get(str(status["result"]), "unknown")
        return status

    def records(self, status, start=1, page_size=20):
        if status.get("state", "online") != "online" or not status.get("uid"):
            raise ValueError("当前在线身份尚未确认。")
        context = self.discover()
        ip = source_ip(status) or context.ip
        current_mac = mac(status.get("ss4") or status.get("olmac") or context.client_mac)
        def records_decode(text, response):
            data = core.parse_jsonp(text)
            if (not isinstance(data.get("records"), list) or len(data["records"])>page_size
                    or any(not isinstance(r, dict) for r in data["records"])):
                raise ValueError("records")
            for record in data["records"]:
                if any(record.get(k) and str(record[k])!=str(status["uid"]) for k in ("uid","user_account")):
                    raise ValueError("identity")
            if "count" in data and (type(data["count"]) not in (int,str) or not str(data["count"]).isdigit()):
                raise ValueError("count")
            return data
        data = self.client.get(context.api + "page/loadOnlineRecord", "读取在线会话详情", records_decode, {
            "callback": "dr1003", "jsVersion": "4.1", "lang": "zh-cn",
            "program_index": context.program, "page_index": context.page_index,
            "user_account": str(status["uid"]), "wlan_user_ip": ip,
            "wlan_user_mac": current_mac, "start_time": "2010-01-01", "end_time": "2100-01-01",
            "start_rn": start, "end_rn": start + page_size - 1})
        records = data["records"]
        self.records_total = int(data["count"]) if "count" in data else -1
        return records, ip, current_mac

    def fetch_session(self, status=None):
        status = status if status is not None else self.login_status()
        if status.get("state") != "online":
            return {"availability": "unavailable", "login_state": status.get("state", "unknown"),
                    "message": "XHA 确认未登录。" if status.get("state") == "offline" else "当前登录状态无法确认。"}
        if not status.get("uid"):
            return {"availability": "unavailable", "login_state": "online", "message": "XHA 确认在线，但未提供账号详情。"}
        records, ip, current_mac = self.records(status, page_size=100)
        owners = [r for r in records if str(r.get("is_owner_ip")) == "1"]
        if not owners and ip:
            owners = [r for r in records if str(r.get("login_ip")) == ip]
        if len(owners) > 1 and current_mac:
            owners = [r for r in owners if mac(r.get("mac_address")) == current_mac]
        if len(owners) != 1:
            return {"availability": "unavailable", "account": str(status["uid"])[:128],
                    "login_state": "online", "message": "无法唯一确认当前设备的在线记录。"}
        record = owners[0]
        def field(key):
            value = record.get(key)
            return str(value)[:256] if value is not None and value != "" else "页面未提供"
        phone = field("phone_flag")
        return {"availability": "ready", "account": str(status["uid"])[:128],
                "online_since": field("login_time"), "source_ipv4": field("login_ip"),
                "source_ipv6": field("login_ip_v6"), "source_mac": field("mac_address"),
                "mobile_identifier": {"0": "PC端（0）", "1": "移动端（1）"}.get(phone, phone),
                "fetched_at": now_text(), "message": "信息来自校园认证网站当前设备的在线记录。"}
