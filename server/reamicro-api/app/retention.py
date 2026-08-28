"""数据保留策略。

服务器有三处数据此前**无限增长**，长期运行会把数据卷占满：

1. 审计日志 `events.jsonl` 只追加，永不轮转；
2. 任务日志 `{task_id}.log` 只追加，而且任务删掉之后文件还留着；
3. 内容包 `history/` 每次编辑都归档一份完整 payload + 清单，编辑一百次就是一百份副本。

只有服务器快照（默认 30 份）和任务消息（每用户 200 条）原先有上限。

这里统一收口，并在调度循环里定期执行。**清理只删数据、不改语义**：审计轮转保留切割后的
历史文件而不是直接截断，内容包历史按版本时间保留最近若干份，删任务时连带清掉它的日志。
"""
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from app import runtime
from app.audit import audit_event
from app.config_store import bounded_config_int, load_config

# 默认阈值。都可以在服务器设置里改。
DEFAULT_AUDIT_MAX_MB = 32
DEFAULT_AUDIT_KEEP_FILES = 3
DEFAULT_TASK_LOG_MAX_KB = 512
DEFAULT_PACKAGE_HISTORY_KEEP = 10


def retention_settings(config: dict[str, Any] | None = None) -> dict[str, int]:
    config = config if config is not None else load_config()
    return {
        "auditMaxMb": bounded_config_int(config.get("auditMaxMb", DEFAULT_AUDIT_MAX_MB), DEFAULT_AUDIT_MAX_MB, 1),
        "auditKeepFiles": bounded_config_int(config.get("auditKeepFiles", DEFAULT_AUDIT_KEEP_FILES), DEFAULT_AUDIT_KEEP_FILES, 0),
        "taskLogMaxKb": bounded_config_int(config.get("taskLogMaxKb", DEFAULT_TASK_LOG_MAX_KB), DEFAULT_TASK_LOG_MAX_KB, 16),
        "packageHistoryKeep": bounded_config_int(config.get("packageHistoryKeep", DEFAULT_PACKAGE_HISTORY_KEEP), DEFAULT_PACKAGE_HISTORY_KEEP, 1),
    }


def rotate_audit_log(max_bytes: int, keep_files: int) -> bool:
    """审计日志超过阈值时切一份历史文件。

    切割而不是截断：审计记录是安全事件，直接丢掉最早的部分不合适。
    `events.jsonl.1` 是最近一次切下来的，编号越大越旧，超出 keep_files 的删掉。
    """
    path = runtime.AUDIT_PATH
    if not path.is_file():
        return False
    try:
        if path.stat().st_size < max_bytes:
            return False
    except OSError:
        return False
    if keep_files <= 0:
        # 明确配置为不保留历史时才截断。
        try:
            path.unlink()
        except OSError:
            return False
        return True
    try:
        # 从最旧往新挪，避免覆盖。
        oldest = path.with_suffix(path.suffix + f".{keep_files}")
        if oldest.exists():
            oldest.unlink()
        for index in range(keep_files - 1, 0, -1):
            source = path.with_suffix(path.suffix + f".{index}")
            if source.exists():
                source.replace(path.with_suffix(path.suffix + f".{index + 1}"))
        path.replace(path.with_suffix(path.suffix + ".1"))
    except OSError:
        return False
    return True


def trim_task_log(task_id: str, max_bytes: int) -> bool:
    """单个任务日志超限时只保留后半段。

    任务日志是排查用的流水，最早的行价值最低，这里直接丢弃前半部分，
    不像审计那样切历史文件——每个任务都留历史文件会产生大量小文件。
    """
    path = runtime.TASK_LOG_ROOT / f"{task_id}.log"
    if not path.is_file():
        return False
    try:
        size = path.stat().st_size
        if size < max_bytes:
            return False
        with path.open("rb") as stream:
            stream.seek(max(0, size - max_bytes // 2))
            tail = stream.read()
    except OSError:
        return False
    # 丢掉可能被截断的首行。
    newline = tail.find(b"\n")
    if newline >= 0:
        tail = tail[newline + 1:]
    try:
        temp = path.with_suffix(".tmp")
        notice = "--- 更早的日志已因超出保留上限被裁剪 ---\n".encode("utf-8")
        temp.write_bytes(notice + tail)
        temp.replace(path)
    except OSError:
        return False
    return True


def purge_orphan_task_logs(known_task_ids: set[str]) -> int:
    """删掉已不存在任务的日志文件。任务删了日志还留着是纯粹的垃圾。"""
    root = runtime.TASK_LOG_ROOT
    if not root.is_dir():
        return 0
    removed = 0
    for path in root.glob("*.log"):
        if path.stem in known_task_ids:
            continue
        try:
            path.unlink()
            removed += 1
        except OSError:
            continue
    return removed


def _history_sort_key(directory: Path) -> tuple[int, str]:
    """历史目录名形如 `1.2.0-1740000000000`，按尾部时间戳排序，缺失时退化按名字。"""
    marker = directory.name.rsplit("-", 1)
    try:
        return (int(marker[-1]), directory.name)
    except (IndexError, ValueError):
        return (0, directory.name)


def prune_package_history(kind: str, package_id: str, keep: int) -> int:
    """内容包历史只保留最近 keep 份。返回删掉的份数。"""
    history = runtime.PACKAGE_ROOT / kind / package_id / "history"
    if not history.is_dir():
        return 0
    versions = sorted(
        (item for item in history.iterdir() if item.is_dir()),
        key=_history_sort_key,
        reverse=True,
    )
    removed = 0
    for expired in versions[max(keep, 1):]:
        try:
            shutil.rmtree(expired)
            removed += 1
        except OSError:
            continue
    return removed


def prune_all_package_history(keep: int) -> int:
    total = 0
    for kind in sorted(runtime.PACKAGE_KINDS):
        root = runtime.PACKAGE_ROOT / kind
        if not root.is_dir():
            continue
        for package_dir in root.iterdir():
            if package_dir.is_dir():
                total += prune_package_history(kind, package_dir.name, keep)
    return total


def run_retention(config: dict[str, Any] | None = None) -> dict[str, Any]:
    """执行全部保留策略。调度循环定期调用，也可由管理员手动触发。"""
    settings = retention_settings(config)
    from app.state import load_tasks

    report = {
        "auditRotated": rotate_audit_log(settings["auditMaxMb"] * 1024 * 1024, settings["auditKeepFiles"]),
        "taskLogsTrimmed": 0,
        "orphanLogsRemoved": 0,
        "historyRemoved": prune_all_package_history(settings["packageHistoryKeep"]),
    }
    task_ids = set(load_tasks())
    for task_id in task_ids:
        if trim_task_log(task_id, settings["taskLogMaxKb"] * 1024):
            report["taskLogsTrimmed"] += 1
    report["orphanLogsRemoved"] = purge_orphan_task_logs(task_ids)
    if any(bool(value) for value in report.values()):
        audit_event("retention_applied", metadata=report)
    return report


def data_usage() -> dict[str, Any]:
    """各类数据的当前占用，供后台展示，便于判断阈值是否合适。"""

    def directory_size(path: Path) -> int:
        if not path.is_dir():
            return 0
        total = 0
        for item in path.rglob("*"):
            if item.is_file():
                try:
                    total += item.stat().st_size
                except OSError:
                    continue
        return total

    def file_size(path: Path) -> int:
        try:
            return path.stat().st_size if path.is_file() else 0
        except OSError:
            return 0

    audit_history = 0
    if runtime.AUDIT_ROOT.is_dir():
        audit_history = sum(file_size(item) for item in runtime.AUDIT_ROOT.glob("events.jsonl.*"))
    history_size = 0
    for kind in sorted(runtime.PACKAGE_KINDS):
        root = runtime.PACKAGE_ROOT / kind
        if not root.is_dir():
            continue
        for package_dir in root.iterdir():
            history_size += directory_size(package_dir / "history")
    return {
        "audit": file_size(runtime.AUDIT_PATH),
        "auditHistory": audit_history,
        "taskLogs": directory_size(runtime.TASK_LOG_ROOT),
        "packages": directory_size(runtime.PACKAGE_ROOT),
        "packageHistory": history_size,
        "backups": directory_size(runtime.BACKUP_ROOT),
        "snapshots": directory_size(runtime.SERVER_BACKUP_ROOT),
    }
