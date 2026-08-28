"""任务调度循环与执行记账。

调度**不依赖模块在线**：签到、抽卡、云端阅读全在服务器跑完，模块被唤醒只为收消息
和发通知。所以手机离线不影响任务执行，只影响结果通知的时效。
"""
import asyncio
import json
import secrets
from datetime import datetime, timedelta, timezone
from typing import Any

from fastapi import HTTPException

from app import runtime
from app.audit import audit_event, task_log
from app.backups import create_server_snapshot, prune_server_snapshots
from app.config_store import bounded_config_int, load_config
from app.crypto import decrypt_secret, encrypt_secret
from app.executors import execute_reamicro_task, execute_task, redact_message
from app.releases import sync_module_release
from app.responses import response
from app.state import (
    enqueue_task_notification,
    load_tasks,
    save_tasks,
    task_credential_id,
)


def task_interval(task: dict[str, Any]) -> int:
    schedule = task.get("schedule", {})
    if isinstance(schedule, dict):
        try:
            return max(int(schedule.get("intervalSeconds", 86_400)), 60)
        except (TypeError, ValueError):
            return 86_400
    if schedule == "@hourly":
        return 3_600
    if schedule == "@daily":
        return 86_400
    return 86_400


def next_task_run(task: dict[str, Any], now_ms: int | None = None) -> int:
    now_ms = now_ms or int(datetime.now(timezone.utc).timestamp() * 1000)
    override = bounded_config_int(task.pop("nextRunAtOverride", 0), 0, 0)
    if override > now_ms:
        return override
    schedule = task.get("schedule", {})
    if not isinstance(schedule, dict) or not schedule.get("timeOfDay"):
        return now_ms + task_interval(task) * 1000
    try:
        hour_text, minute_text = str(schedule.get("timeOfDay")).split(":", 1)
        offset = int(schedule.get("timezoneOffsetMinutes", 480))
        local_zone = timezone(timedelta(minutes=max(-720, min(offset, 840))))
        local_now = datetime.fromtimestamp(now_ms / 1000, local_zone)
        candidate = local_now.replace(hour=int(hour_text), minute=int(minute_text), second=0, microsecond=0)
        if candidate <= local_now:
            candidate += timedelta(days=1)
        return int(candidate.timestamp() * 1000)
    except (ValueError, TypeError, OverflowError):
        return now_ms + task_interval(task) * 1000


def retry_delay_seconds(task: dict[str, Any]) -> int:
    attempts = max(int(task.get("consecutiveFailures", 0)), 1)
    return min(300 * (2 ** (attempts - 1)), 21_600)


def recover_interrupted_tasks() -> int:
    tasks = load_tasks()
    recovered = 0
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    for task in tasks.values():
        if task.get("status") != "running":
            continue
        task["status"] = "scheduled"
        task["lastMessage"] = "服务器重启后已恢复中断任务"
        task["nextRunAt"] = now + 60_000
        recovered += 1
    if recovered:
        save_tasks(tasks)
        audit_event("interrupted_tasks_recovered", metadata={"count": recovered})
    return recovered


def normalized_time_of_day(value: Any) -> str:
    text = str(value or "").strip()
    try:
        hour_text, minute_text = text.split(":", 1)
        hour = int(hour_text)
        minute = int(minute_text)
        if hour not in range(24) or minute not in range(60):
            raise ValueError
        return f"{hour:02d}:{minute:02d}"
    except (ValueError, TypeError):
        raise ValueError("每日执行时间必须使用 HH:MM 格式")


def apply_task_action(task: dict[str, Any], action: str, now: int | None = None) -> str:
    now = now or int(datetime.now(timezone.utc).timestamp() * 1000)
    task_id = str(task.get("id", ""))
    if action == "run":
        if task.get("taskType") == "yeshe_draw_card":
            task["triggeredByCheckinReward"] = True
        task["status"] = "scheduled"
        task["enabled"] = True
        task["nextRunAt"] = now
        return f"任务 {task_id} 已安排立即执行"
    if action == "pause":
        task["status"] = "paused"
        task["enabled"] = False
        task["nextRunAt"] = 0
        return f"任务 {task_id} 已暂停"
    if action == "resume":
        task["status"] = "scheduled"
        task["enabled"] = True
        task["nextRunAt"] = now
        return f"任务 {task_id} 已恢复"
    if action == "cancel":
        task["status"] = "cancelled"
        task["enabled"] = False
        task["nextRunAt"] = 0
        return f"任务 {task_id} 已取消"
    raise ValueError("不支持的任务操作")


def record_task_execution(task: dict[str, Any], result: str, message: str, started_at: int, finished_at: int) -> None:
    history = task.get("executionHistory", [])
    if not isinstance(history, list):
        history = []
    history.append({
        "at": finished_at,
        "startedAt": started_at,
        "finishedAt": finished_at,
        "durationMs": max(0, finished_at - started_at),
        "result": result,
        "message": message,
        "runCount": int(task.get("runCount", 0)),
        "consecutiveFailures": int(task.get("consecutiveFailures", 0)),
    })
    task["executionHistory"] = history[-100:]
    task["lastExecution"] = history[-1]


def execute_linked_draw_task(tasks: dict[str, dict[str, Any]], checkin_task: dict[str, Any], started_at: int) -> tuple[str, str] | None:
    """签到奖励领取成功后，立即运行同账号已启用的抽卡任务。"""
    if not checkin_task.pop("claimJustCompleted", False):
        return None
    owner = str(checkin_task.get("owner", ""))
    credential_id = task_credential_id(checkin_task)
    candidates = [
        task for task in tasks.values()
        if task.get("owner") == owner
        and task.get("taskType") == "yeshe_draw_card"
        and task_credential_id(task) == credential_id
        and task.get("enabled", True)
        and task.get("status") not in {"paused", "cancelled"}
    ]
    if not candidates:
        return None
    draw_task = max(candidates, key=lambda item: bounded_config_int(item.get("updatedAt", item.get("createdAt", 0)), 0, 0))
    draw_task["triggeredByCheckinReward"] = True
    draw_task["status"] = "running"
    draw_task["lastRunAt"] = started_at
    result, message = execute_reamicro_task(draw_task)
    finished_at = int(datetime.now(timezone.utc).timestamp() * 1000)
    draw_task["status"] = result
    draw_task["lastMessage"] = message
    draw_task["runCount"] = int(draw_task.get("runCount", 0)) + 1
    draw_task["consecutiveFailures"] = 0 if result == "success" else int(draw_task.get("consecutiveFailures", 0)) + 1
    draw_task["nextRunAt"] = 0
    record_task_execution(draw_task, result, message, started_at, finished_at)
    task_log(str(draw_task.get("id", "")), message, "ERROR" if result == "failed" else "WARN" if result == "paused" else "INFO")
    enqueue_task_notification(draw_task, result, message, finished_at)
    return result, message


async def task_scheduler_loop() -> None:
    worker_id = "worker_" + secrets.token_hex(6)
    while True:
        try:
            tasks = load_tasks()
            now = int(datetime.now(timezone.utc).timestamp() * 1000)
            changed = False
            for task_id, task in tasks.items():
                if not task.get("enabled", True) or task.get("status") in {"paused", "cancelled", "running"}:
                    continue
                if task.get("taskType") == "yeshe_draw_card" and not task.get("triggeredByCheckinReward"):
                    task["status"] = "scheduled"
                    task["nextRunAt"] = 0
                    continue
                next_run = int(task.get("nextRunAt", 0))
                if next_run > now:
                    continue
                if not runtime.get_state_store().acquire_task_lock(task_id, worker_id, now + 15 * 60 * 1000, now):
                    continue
                task["status"] = "running"
                task["lastRunAt"] = now
                changed = True
                save_tasks(tasks)
                try:
                    started_at = int(datetime.now(timezone.utc).timestamp() * 1000)
                    result, message = await asyncio.to_thread(execute_task, task)
                    finished_at = int(datetime.now(timezone.utc).timestamp() * 1000)
                    task_log(task_id, message, "ERROR" if result == "failed" else "WARN" if result == "paused" else "INFO")
                    task["status"] = result
                    task["lastMessage"] = message
                    task["runCount"] = int(task.get("runCount", 0)) + 1
                    if result == "success":
                        task["consecutiveFailures"] = 0
                        task["nextRunAt"] = next_task_run(task, now)
                    elif result == "failed":
                        task["consecutiveFailures"] = int(task.get("consecutiveFailures", 0)) + 1
                        max_retries = max(0, min(int(task.get("maxRetries", 3)), 10))
                        if task["consecutiveFailures"] <= max_retries:
                            task["status"] = "scheduled"
                            task["nextRunAt"] = now + retry_delay_seconds(task) * 1000
                            task["lastMessage"] = f"{message}；将在退避后重试"
                        else:
                            task["status"] = "paused"
                            task["enabled"] = False
                            task["nextRunAt"] = 0
                            task["lastMessage"] = f"{message}；连续失败超过上限，任务已暂停"
                    else:
                        task["nextRunAt"] = 0
                    record_task_execution(task, result, message, started_at, finished_at)
                    enqueue_task_notification(task, result, message, finished_at)
                    linked_draw = await asyncio.to_thread(execute_linked_draw_task, tasks, task, finished_at)
                    if linked_draw:
                        task["lastMessage"] = f"{message}；{linked_draw[1]}"
                    changed = True
                    save_tasks(tasks)
                finally:
                    runtime.get_state_store().release_task_lock(task_id, worker_id)
            if changed:
                save_tasks(tasks)
        except Exception as error:
            print(f"task scheduler failed: {error}", flush=True)
        await asyncio.sleep(15)


async def release_sync_loop() -> None:
    while True:
        try:
            await asyncio.to_thread(sync_module_release)
        except Exception as error:
            runtime.RELEASE_STATUS_PATH.parent.mkdir(parents=True, exist_ok=True)
            runtime.RELEASE_STATUS_PATH.write_text(json.dumps({"status": "error", "failedAt": int(datetime.now(timezone.utc).timestamp() * 1000), "message": redact_message(str(error))}, ensure_ascii=False, indent=2), encoding="utf-8")
            print(f"module release sync failed: {error}", flush=True)
        await asyncio.sleep(load_config()["releaseSyncSeconds"])


async def server_snapshot_loop() -> None:
    while True:
        config = load_config()
        if config.get("serverSnapshotEnabled", True):
            try:
                snapshot = await asyncio.to_thread(create_server_snapshot)
                audit_event("scheduled_server_snapshot", metadata={"filename": snapshot.name})
            except Exception as error:
                audit_event("scheduled_server_snapshot_failed", success=False, metadata={"type": type(error).__name__})
        await asyncio.sleep(max(int(config.get("serverSnapshotSeconds", 86400)), 3600))


def public_task(task: dict[str, Any], include_request: bool = False) -> dict[str, Any]:
    value = {key: item for key, item in task.items() if key != "requestEncrypted"}
    value["credentialId"] = task_credential_id(task)
    if include_request and task.get("requestEncrypted"):
        try:
            value["request"] = decrypt_secret(str(task["requestEncrypted"]))
        except Exception:
            value["request"] = {}
    return value


def find_owned_task(task_id: str, owner: str) -> tuple[dict[str, dict[str, Any]], dict[str, Any]]:
    tasks = load_tasks()
    task = tasks.get(task_id)
    if not task or task.get("owner") != owner:
        raise HTTPException(status_code=404, detail=response(code="TASK_NOT_FOUND", message="任务不存在"))
    return tasks, task
