"""Recovery episodes belong to a Windows boot, not an application process or log buffer."""
from datetime import datetime
import json
from pathlib import Path
import uuid

from .storage import StorageError, write_json


class RecoveryStore:
    def __init__(self, path: Path):
        self.path = path
        self.boot_known = False
        self.data = {"version": 1, "boot_id": "", "boot_started_at": "", "active": None, "events": []}
        if path.exists():
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
                if (data.get("version") != 1 or not isinstance(data.get("events"), list)
                        or not isinstance(data.get("boot_id"), str)
                        or not isinstance(data.get("boot_started_at"), str)
                        or data.get("active") is not None and not isinstance(data["active"], dict)):
                    raise ValueError
                if any(not isinstance(e, dict) or not all(k in e for k in
                       ("id", "recovered_at", "detected_at", "method", "duration")) for e in data["events"]):
                    raise ValueError
                self.data = data
            except (OSError, ValueError, TypeError, AttributeError):
                raise StorageError("恢复台账无法读取，已保留原文件，请检查本地数据。") from None

    def set_boot(self, boot):
        if not boot or not boot.get("id"): return
        if boot["id"] != self.data["boot_id"]:
            self.data = {"version": 1, "boot_id": boot["id"], "boot_started_at": boot["started_at"],
                         "active": None, "events": []}
        self.boot_known = True
        self._save()

    def _save(self):
        write_json(self.path, self.data)

    def observe_offline(self, detected_at=None, network_key=""):
        if not self.boot_known: return
        if self.data["active"] is None or network_key and self.data["active"].get("network_key")!=network_key:
            self.data["active"] = {"id": uuid.uuid4().hex,"network_key":network_key,"detected_at": detected_at or datetime.now().astimezone().isoformat(timespec="seconds")}
            self._save()

    def online(self, restored_by_app=False, method="", duration=0, network_key=""):
        if not self.boot_known: return
        active = self.data["active"]
        if active is None: return
        if network_key and active.get("network_key") and active["network_key"]!=network_key:
            self.data["active"]=None;self._save();return
        if restored_by_app and not any(e["id"] == active["id"] for e in self.data["events"]):
            self.data["events"].append(dict(active, recovered_at=datetime.now().astimezone().isoformat(timespec="seconds"),
                                             method=method, duration=round(duration, 2)))
        self.data["active"] = None
        self._save()

    def view(self):
        return {"known": self.boot_known, "startedAt": self.data["boot_started_at"] if self.boot_known else "",
                "count": len(self.data["events"]) if self.boot_known else 0,
                "events": list(reversed(self.data["events"])) if self.boot_known else []}
