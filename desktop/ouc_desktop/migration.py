"""Explicit, fail-closed import. Never deletes the source .env or unrelated tasks."""
from dataclasses import replace
import json
from pathlib import Path

import auto_login as core
from .storage import write_json, StorageError, configuration_transaction
from .windows import legacy_tasks, disable_legacy_tasks
from .request_policy import RequestPolicy


def merge_budget(destination: Path, source: Path):
    current, current_blocked = core.LoginRetryGuard(destination)._read()
    previous, previous_blocked = core.LoginRetryGuard(source)._read()
    timestamps = sorted(set(current + previous))
    latest = list(timestamps)
    for guard_path in (destination, source):
        timing = guard_path.with_name("auth-timing.json")
        if timing.exists():
            core.LoginRetryGuard(guard_path, minimum_interval=120).remaining_delay()
            latest.append(json.loads(timing.read_text(encoding="utf-8"))["timestamp"])
    blocked = current_blocked or previous_blocked or len(timestamps) >= 3
    write_json(destination, {"version": 1, "attempts": timestamps[-3:], "blocked": blocked})
    if latest:
        write_json(destination.with_name("auth-timing.json"), {"version": 1, "timestamp": max(latest)})


def import_config(path, data_directory, settings, credentials):
    path = Path(path).resolve()
    directory = path.parent
    if not (directory / "auto_login.py").is_file():
        raise StorageError("请选择原 auto_login_ouc 文件夹中的 .env，以便核对保护状态与旧任务。")
    config = core.load_config(path, use_environment=False)
    # No authentication permitted until the source is locked and tasks are handled.
    updated = replace(settings, configured=True, ssid=config.ssid, profile=config.profile,
                      legacy_directory=str(directory), migration_pending=True, monitor=False,
                      interval=max(1, int(config.interval)), interval_ms=max(1, int(config.interval)) * 1000)
    with configuration_transaction(data_directory):
        try:
            merge_budget(data_directory / "retry-state.json", directory / ".login_retry_state.json")
        except core.RetryStateError:
            write_json(data_directory / "retry-state.json", {"version": 1, "attempts": [], "blocked": True})
        updated.active_account_id = credentials.save(config.username, config.password)
        policy = RequestPolicy(data_directory / "request-state.json")
        if core.LoginRetryGuard(data_directory / "retry-state.json").is_blocked(): policy.import_legacy_pause()
        updated.save(data_directory / "settings.json")
    try:
        tasks, error = legacy_tasks(directory), ""
    except RuntimeError as exc:
        tasks, error = [], str(exc)
    return {"settings": updated, "username": config.username, "tasks": tasks, "scan_error": error}


def complete_migration(data_directory, settings, selected, existing_lock=None):
    directory = Path(settings.legacy_directory).resolve()
    if not (directory / "auto_login.py").is_file():
        raise StorageError("原脚本目录无法核实，迁移尚未完成。")
    if selected:
        disable_legacy_tasks(directory, selected)
    elif any(t["enabled"] for t in legacy_tasks(directory)):
        raise StorageError("仍有旧任务启用，请勾选停用匹配任务或手动停用后重新扫描。")
    lock = existing_lock
    if lock is None:
        try:
            lock = core.InstanceLock(directory / ".auto_login.lock")
            lock.__enter__()
        except RuntimeError:
            raise StorageError("原脚本仍在运行。请停止原脚本窗口或正在执行的旧任务，再完成迁移。") from None
    try:
        # Re-read under the legacy lifetime lock; the old process may have advanced.
        merge_budget(data_directory / "retry-state.json", directory / ".login_retry_state.json")
        if core.LoginRetryGuard(data_directory / "retry-state.json").is_blocked():
            RequestPolicy(data_directory / "request-state.json").import_legacy_pause()
        write_json(directory / "desktop-managed.json", {"version": 2, "mode": "desktop-managed", "data_directory": str(data_directory.resolve())})
        updated = replace(settings, migration_pending=False, monitor=True)
        updated.save(data_directory / "settings.json")
        return {"settings": updated, "lock": lock}
    except Exception:
        if existing_lock is None: lock.__exit__(None, None, None)
        raise
