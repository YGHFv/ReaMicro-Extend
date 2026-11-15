import asyncio
import json
import secrets
import threading
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from typing import Any

from fastapi import HTTPException

from app import runtime
from app.audit import audit_event, task_log
from app.backups import create_server_snapshot
from app.retention import run_retention
from app.config_store import bounded_config_int, load_config
from app.crypto import decrypt_secret
from app.executors import execute_task, redact_message
from app.releases import sync_module_release
from app.responses import response
from app.state import (
    enqueue_task_notification,
    find_duplicate_task,
    load_tasks,
    save_tasks,
    task_credential_id,
)


YESHE_DRAW_TRIGGER_EVENT = "yeshe_checkin_reward_claimed"

TASK_CONFIGURATION_FIELDS = {
    "id", "owner", "taskType", "credentialId", "requestEncrypted", "executionMode",
    "schedule", "createdAt", "updatedAt", "maxRetries", "runRequestedAt",
    "enabled", "status", "nextRunAt",
}


def upsert_task_configuration(tasks: dict[str, dict[str, Any]], proposed: dict[str, Any],
                              now: int) -> tuple[dict[str, Any], bool]:
    """Called inside a task transaction so a reconfiguration cannot reset worker state."""
    duplicate = find_duplicate_task(tasks, proposed)
    if duplicate is None:
        tasks[str(proposed["id"])] = proposed
        return proposed, False
    task_id, previous = duplicate
    task = deepcopy(previous)
    for key in TASK_CONFIGURATION_FIELDS - {"id", "createdAt", "status", "nextRunAt"}:
        if key in proposed:
            task[key] = proposed[key]
    task["id"] = task_id
    task["updatedAt"] = max(now, bounded_config_int(previous.get("updatedAt", 0), 0, 0) + 1)
    if not task.get("enabled", True):
        task["status"] = "paused"
        task["nextRunAt"] = 0
    elif previous.get("status") != "running":
        task["status"] = "scheduled"
        task["nextRunAt"] = proposed.get("nextRunAt", now)
    tasks[task_id] = task
    return task, True


def normalized_task_schedule(task_type: str, schedule: dict[str, Any]) -> dict[str, Any]:

    if task_type == "yeshe_draw_card":
        return {"event": YESHE_DRAW_TRIGGER_EVENT}
    if task_type == "traveling_merchant":
        interval = 4 * 3_600
        if isinstance(schedule, dict):
            try:
                interval = max(int(schedule.get("intervalSeconds", interval)), 60)
            except (TypeError, ValueError):
                pass
        return {"intervalSeconds": interval}
    value = dict(schedule)
    if value.get("timeOfDay"):
        value["timeOfDay"] = normalized_time_of_day(value.get("timeOfDay"))
    return value


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
    if task.get("taskType") == "yeshe_draw_card":
        return 0
    now_ms = now_ms or int(datetime.now(timezone.utc).timestamp() * 1000)
    override = bounded_config_int(task.pop("nextRunAtOverride", 0), 0, 0)
    if override > 0:
        return override if override > now_ms else now_ms + 60_000
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
    attempts = bounded_config_int(task.get("consecutiveFailures", 0), 1, 1)
    return min(300 * (2 ** min(attempts - 1, 7)), 21_600)


def recover_interrupted_tasks() -> int:
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    recovered = runtime.get_state_store().recover_interrupted_tasks(now)
    if recovered:
        audit_event("interrupted_tasks_recovered", metadata={"count": recovered})
    return recovered


def migrate_device_tasks_to_server() -> int:

    tasks = load_tasks()
    migrated = 0
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    for task in tasks.values():
        if task.get("executionMode", "server") != "device":
            continue
        task["executionMode"] = "server"
        task.pop("deviceLeaseToken", None)
        task.pop("deviceLeaseUntil", None)
        if task.get("taskType") != "yeshe_draw_card" and int(task.get("nextRunAt", 0) or 0) <= 0:
            task["nextRunAt"] = now + 60_000
        if task.get("status") == "running":
            task["status"] = "scheduled"
            task["lastMessage"] = "设备执行模式已废弃，任务已迁回服务器执行"
        migrated += 1
    if migrated:
        save_tasks(tasks)
        audit_event("device_tasks_migrated_to_server", metadata={"count": migrated})
        print(f"migrated {migrated} device task(s) back to server execution", flush=True)
    return migrated


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
    if action not in {"run", "pause", "resume", "cancel"}:
        raise ValueError("不支持的任务操作")
    task["updatedAt"] = max(now, bounded_config_int(task.get("updatedAt", 0), 0, 0) + 1)
    if action == "run":
        task["runRequestedAt"] = max(now, bounded_config_int(task.get("runRequestedAt", 0), 0, 0) + 1)
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
        task["nextRunAt"] = 0 if task.get("taskType") == "yeshe_draw_card" and not task.get("triggeredByCheckinReward") else now
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


def schedule_linked_draw_task(tasks: dict[str, dict[str, Any]], checkin_task: dict[str, Any], started_at: int) -> tuple[str, str] | None:

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
        and task.get("status") not in {"paused", "cancelled", "running"}
    ]
    if not candidates:
        return None
    draw_task = max(candidates, key=lambda item: bounded_config_int(item.get("updatedAt", item.get("createdAt", 0)), 0, 0))
    draw_task["triggeredByCheckinReward"] = True
    draw_task["status"] = "scheduled"
    draw_task["nextRunAt"] = started_at
    return "scheduled", "签到奖励已领取，祈愿已排入执行队列"


def requires_automation_recovery(task: dict[str, Any]) -> bool:
    return (task.get("taskType") in {"yeshe_checkin", "traveling_merchant"}
            and task.get("enabled", True) and task.get("status") not in {"paused", "cancelled", "running"}
            and bounded_config_int(task.get("automationStateVersion", 0), 0, 0) < 1)


def recover_automation_task(task: dict[str, Any], now_ms: int) -> bool:
    if not requires_automation_recovery(task):
        return False
    task["automationStateVersion"] = 1
    task["nextRunAt"] = now_ms
    if task.get("taskType") == "traveling_merchant":
        task["merchantSettledTripId"] = 0
        task["merchantRestartedAfterTripId"] = 0
    return True


def complete_task_execution(current: dict[str, Any], original: dict[str, Any], executed: dict[str, Any],
                            result: str, message: str, started_at: int, finished_at: int) -> dict[str, Any]:
    task = dict(current)
    for field in (original.keys() | executed.keys()) - TASK_CONFIGURATION_FIELDS:
        if field in executed:
            task[field] = executed[field]
        else:
            task.pop(field, None)
    task["status"] = result
    task["lastMessage"] = message
    task["runCount"] = bounded_config_int(task.get("runCount", 0), 0, 0) + 1
    if result == "success":
        task["consecutiveFailures"] = 0
        task["nextRunAt"] = next_task_run(task, finished_at)
    elif result == "failed":
        task["consecutiveFailures"] = bounded_config_int(task.get("consecutiveFailures", 0), 0, 0) + 1
        max_retries = min(bounded_config_int(task.get("maxRetries", 3), 3, 0), 10)
        if task["consecutiveFailures"] <= max_retries:
            task["status"] = "scheduled"
            task["nextRunAt"] = finished_at + retry_delay_seconds(task) * 1000
            task["lastMessage"] = f"{message}；将在退避后重试"
        else:
            task["status"] = "paused"
            task["enabled"] = False
            task["nextRunAt"] = 0
            task["lastMessage"] = f"{message}；连续失败超过上限，任务已暂停"
    else:
        task["nextRunAt"] = 0
    controls_changed = any(current.get(field) != original.get(field) for field in ("enabled", "status", "nextRunAt", "runRequestedAt"))
    if controls_changed:
        for field in ("enabled", "status", "nextRunAt"):
            if field in current:
                task[field] = current[field]
        if current.get("runRequestedAt") != original.get("runRequestedAt") and task.get("taskType") == "yeshe_draw_card":
            task["triggeredByCheckinReward"] = True
    if not task.get("enabled", True) or task.get("status") in {"paused", "cancelled"}:
        task["nextRunAt"] = 0
    record_task_execution(task, result, message, started_at, finished_at)
    return task


def claim_due_task(tasks: dict[str, dict[str, Any]], task_id: str, now: int,
                   execution_id: str = "") -> dict[str, Any] | None:
    """Called under the store transaction, after acquiring this worker's task lease."""
    task = tasks.get(task_id)
    if task is None or not task.get("enabled", True) or task.get("status") in {"paused", "cancelled", "running"}:
        return None
    if task.get("taskType") == "yeshe_draw_card" and not task.get("triggeredByCheckinReward"):
        task["status"] = "scheduled"
        task["nextRunAt"] = 0
        task["schedule"] = {"event": YESHE_DRAW_TRIGGER_EVENT}
        return None
    if bounded_config_int(task.get("nextRunAt", 0), 0, 0) > now and not requires_automation_recovery(task):
        return None
    recover_automation_task(task, now)
    task["status"] = "running"
    task["lastRunAt"] = now
    task["executionId"] = execution_id or secrets.token_hex(12)
    return deepcopy(task)


def commit_task_execution(tasks: dict[str, dict[str, Any]], task_id: str, original: dict[str, Any],
                          executed: dict[str, Any], result: str, message: str,
                          started_at: int, finished_at: int) -> dict[str, Any] | None:
    current = tasks.get(task_id)
    if (current is None or current.get("executionId") != original.get("executionId")
            or current.get("lastRunAt") != original.get("lastRunAt")):
        return None
    task = complete_task_execution(current, original, executed, result, message, started_at, finished_at)
    tasks[task_id] = task
    # A user may have changed this task's credential while its old check-in ran.
    # Trigger the reward's original account, then consume the transient marker.
    schedule_linked_draw_task(tasks, deepcopy(executed), finished_at)
    task.pop("claimJustCompleted", None)
    return task


def run_due_task_once(task_id: str, worker_id: str) -> None:
    """Own the lease in the actual worker thread, including its final commit/release.

    Cancelling asyncio.to_thread only cancels its awaiter. It cannot free a lease for
    a still-running HTTP operation. Another process sees the lease until this ends.
    """
    store = runtime.get_state_store()
    lease_owner = worker_id + "_" + secrets.token_hex(6)
    claimed = False
    original = None
    renewal_done = threading.Event()
    renewal = None
    try:
        now = int(datetime.now(timezone.utc).timestamp() * 1000)
        claimed = store.acquire_task_lock(task_id, lease_owner, now + 15 * 60_000, now)
        if not claimed:
            return
        task = store.mutate_namespace("tasks", lambda tasks: claim_due_task(tasks, task_id, now, lease_owner))
        if task is None:
            return
        original = deepcopy(task)

        def renew_lease() -> None:
            while not renewal_done.wait(60):
                try:
                    expiry = int(datetime.now(timezone.utc).timestamp() * 1000) + 15 * 60_000
                    if not store.renew_task_lock(task_id, lease_owner, expiry):
                        return
                except Exception as error:
                    print(f"task lease renewal failed: {type(error).__name__}", flush=True)
        renewal = threading.Thread(target=renew_lease, name="reamicro-task-lease", daemon=True)
        renewal.start()
        started_at = int(datetime.now(timezone.utc).timestamp() * 1000)
        result, message = execute_task(task)
        finished_at = int(datetime.now(timezone.utc).timestamp() * 1000)
        task = store.mutate_namespace("tasks", lambda tasks: commit_task_execution(
            tasks, task_id, original, task, result, message, started_at, finished_at,
        ))
        if task is not None:
            enqueue_task_notification(task, result, message, finished_at)
        task_log(task_id, message, "ERROR" if result == "failed" else "WARN" if result == "paused" else "INFO")
    except Exception as error:
        message = redact_message(str(error))
        print(f"task scheduler failed for {task_id}: {message}", flush=True)
        if original is not None:
            def release_attempt(tasks):
                current = tasks.get(task_id)
                if (current is not None and current.get("status") == "running"
                        and current.get("executionId") == original.get("executionId")):
                    current["status"] = "paused"
                    current["enabled"] = False
                    current["nextRunAt"] = 0
                    current["lastMessage"] = "执行结果保存失败，已暂停；请核对任务结果后手动恢复"
            try:
                store.mutate_namespace("tasks", release_attempt)
            except Exception as recovery_error:
                print(f"task recovery failed: {type(recovery_error).__name__}", flush=True)
    finally:
        renewal_done.set()
        if renewal is not None and renewal.ident is not None:
            renewal.join(timeout=1)
        if claimed:
            try:
                store.release_task_lock(task_id, lease_owner)
            except Exception as error:
                print(f"task lease release failed: {type(error).__name__}", flush=True)


async def task_scheduler_loop() -> None:
    worker_id = "worker_" + secrets.token_hex(6)
    while True:
        try:
            recover_interrupted_tasks()
            task_ids = list(load_tasks())
        except Exception as error:
            print(f"task scheduler state read failed: {redact_message(str(error))}", flush=True)
            task_ids = []
        for task_id in task_ids:
            await asyncio.to_thread(run_due_task_once, task_id, worker_id)
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


        try:
            await asyncio.to_thread(run_retention, config)
        except Exception as error:
            audit_event("retention_failed", success=False, metadata={"type": type(error).__name__})
        await asyncio.sleep(max(int(config.get("serverSnapshotSeconds", 86400)), 3600))


def public_task(task: dict[str, Any], include_request: bool = False) -> dict[str, Any]:
    value = {key: item for key, item in task.items() if key not in {"request", "requestEncrypted"}}
    value["credentialId"] = task_credential_id(task)
    plaintext_request = task.get("request")
    request_value: dict[str, Any] = plaintext_request if isinstance(plaintext_request, dict) else {}
    if task.get("requestEncrypted"):
        try:
            decrypted = decrypt_secret(str(task["requestEncrypted"]))
            request_value = decrypted if isinstance(decrypted, dict) else {}
        except Exception:
            request_value = {}
    if include_request:
        value["request"] = request_value
    elif task.get("taskType") == "yeshe_checkin":
        value["configuration"] = {"blessingType": str(request_value.get("blessingType", "")).strip().upper()[:64]}
    elif task.get("taskType") == "yeshe_draw_card":
        value["configuration"] = {
            "dailyLimit": min(20, bounded_config_int(request_value.get("dailyLimit", 3), 3, 0)),
        }
    elif task.get("taskType") == "cloud_auto_read":
        books = request_value.get("books", [])
        value["configuration"] = {
            "durationMinutes": min(720, bounded_config_int(request_value.get("durationMinutes", 30), 30, 1)),
            "books": [
                {
                    "bookId": bounded_config_int(book.get("bookId", book.get("cloudBookId", 0)), 0, 0),
                    "name": str(book.get("name", ""))[:200],
                }
                for book in books
                if isinstance(book, dict) and bounded_config_int(book.get("bookId", book.get("cloudBookId", 0)), 0, 0) > 0
            ],
        }
    elif task.get("taskType") == "traveling_merchant":
        value["configuration"] = {
            "blessingType": str(request_value.get("blessingType", "")).strip().upper()[:64],
            "merchantAutoComplete": bool(request_value.get("merchantAutoComplete", False)),
            "merchantCityCode": str(request_value.get("merchantCityCode", ""))[:64],
            "merchantPrincipal": bounded_config_int(request_value.get("merchantPrincipal", 0), 0, 0),
            "merchantTransportId": bounded_config_int(request_value.get("merchantTransportId", 0), 0, 0),
        }
    return value


def find_owned_task(task_id: str, owner: str) -> tuple[dict[str, dict[str, Any]], dict[str, Any]]:
    tasks = load_tasks()
    task = tasks.get(task_id)
    if not task or task.get("owner") != owner:
        raise HTTPException(status_code=404, detail=response(code="TASK_NOT_FOUND", message="任务不存在"))
    return tasks, task
