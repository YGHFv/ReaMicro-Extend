"""审计事件与任务日志。

审计只记不含敏感值的管理与安全事件，落 `events.jsonl`（每行一条 JSON）。
任务日志按任务 ID 分文件，供 /v1/tasks/{id}/logs 和后台日志页读取。
"""
import json
from datetime import datetime, timezone
from typing import Any

from app import runtime


def audit_event(action: str, actor: str = "system", request_id: str = "", success: bool = True, metadata: dict[str, Any] | None = None) -> None:
    """记录不含敏感值的管理与安全事件。"""
    try:
        runtime.AUDIT_ROOT.mkdir(parents=True, exist_ok=True)
        event = {
            "at": int(datetime.now(timezone.utc).timestamp() * 1000),
            "action": action,
            "actor": actor,
            "requestId": request_id,
            "success": success,
            "metadata": metadata or {},
        }
        with runtime.AUDIT_PATH.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")
    except OSError:
        pass


def audit_actor(actor: dict[str, Any] | None) -> str:
    if not actor:
        return "system"
    return f"admin:{actor.get('username', 'unknown')}"


def task_log(task_id: str, message: str, level: str = "INFO") -> None:
    runtime.TASK_LOG_ROOT.mkdir(parents=True, exist_ok=True)
    path = runtime.TASK_LOG_ROOT / f"{task_id}.log"
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps({
            "at": int(datetime.now(timezone.utc).timestamp() * 1000),
            "taskId": task_id,
            "level": level,
            "message": message,
        }, ensure_ascii=False) + "\n")
