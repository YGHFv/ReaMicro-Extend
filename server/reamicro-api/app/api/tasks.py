from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request

from app import runtime
import json
import secrets
from datetime import datetime, timezone
from app.audit import (
    audit_event,
    task_log,
)
from app.config_store import bounded_config_int
from app.crypto import encrypt_secret
from app.responses import response
from app.scheduler import (
    apply_task_action,
    find_owned_task,
    next_task_run,
    normalized_task_schedule,
    public_task,
    upsert_task_configuration,
)
from app.security import task_owner
from app.state import owner_host_account_id
from app.retention import purge_orphan_task_logs
from app.users import touch_user
from app.state import (
    find_duplicate_task,
    load_credentials,
    load_notifications,
    load_tasks,
    merge_duplicate_tasks,
    save_tasks,
)

router = APIRouter()


def validated_schedule(task_type: str, value: dict[str, Any]) -> dict[str, Any]:
    try:
        return normalized_task_schedule(task_type, value)
    except (TypeError, ValueError, OverflowError) as error:
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message=str(error))) from error


def change_owned_task(task_id: str, owner: str, action: str) -> dict[str, Any]:
    def change(tasks):
        task = tasks.get(task_id)
        if not task or task.get("owner") != owner:
            raise HTTPException(status_code=404, detail=response(code="TASK_NOT_FOUND", message="任务不存在"))
        apply_task_action(task, action)
        return task
    return runtime.get_state_store().mutate_namespace("tasks", change)


@router.post("/v1/tasks")
async def create_task(request: Request, owner: str = Depends(task_owner)) -> dict[str, Any]:
    try:
        payload = await request.json()
    except ValueError:
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务 JSON 无效"))
    if not isinstance(payload, dict) or not payload.get("taskType"):
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="缺少 taskType"))
    idempotency_key = request.headers.get("Idempotency-Key", "").strip()
    if idempotency_key:
        if len(idempotency_key) > 128:
            raise HTTPException(status_code=400, detail=response(code="IDEMPOTENCY_INVALID", message="Idempotency-Key 过长"))
        previous = runtime.get_state_store().get_idempotency(owner, "create_task", idempotency_key)
        if previous:
            return previous
    task_type = str(payload.get("taskType", "")).strip()
    if task_type not in {"http", "yeshe_checkin", "yeshe_draw_card", "cloud_auto_read", "traveling_merchant", "pawn"}:
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="不支持的任务类型"))


    execution_mode = "server"
    request_value = payload.get("request", {})
    if not isinstance(request_value, dict):
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务 request 必须是对象"))
    credential_id = str(request_value.get("credentialId", "")).strip()
    if task_type != "http":
        credential = load_credentials().get(credential_id)
        if not credential or credential.get("owner") != owner:
            raise HTTPException(status_code=400, detail=response(code="CREDENTIAL_NOT_FOUND", message="阅微凭据不存在或不属于当前账号"))
    schedule = payload.get("schedule", {})
    if not isinstance(schedule, dict):
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务 schedule 必须是对象"))
    schedule = validated_schedule(task_type, schedule)
    task_id = "task_" + secrets.token_hex(10)
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    task = {
        "id": task_id,
        "taskType": task_type,
        "schedule": schedule,
        "owner": owner,
        "status": "scheduled",
        "enabled": bool(payload.get("enabled", True)),
        "createdAt": now,
        "nextRunAt": 0 if task_type == "yeshe_draw_card" else bounded_config_int(payload.get("nextRunAt", now), now, 0),
        "runCount": 0,
        "automationStateVersion": 1,
        "maxRetries": min(bounded_config_int(payload.get("maxRetries", 3), 3, 0), 10),
        "consecutiveFailures": 0,
        "executionMode": execution_mode,
    }
    task["credentialId"] = credential_id
    def create(tasks):
        task["requestEncrypted"] = encrypt_secret(request_value)
        stored, _ = upsert_task_configuration(tasks, task, now)
        return response(public_task(stored))
    result = runtime.get_state_store().mutate_namespace(
        "tasks", create, idempotency=(owner, "create_task", idempotency_key) if idempotency_key else None,
    )
    task_id = str(result["data"]["id"])
    task_log(task_id, "任务已创建")
    return result


@router.get("/v1/tasks")
async def list_tasks(owner: str = Depends(task_owner)) -> dict[str, Any]:
    merge_duplicate_tasks()
    tasks = [public_task(task, include_request=False) for task in load_tasks().values() if task.get("owner") == owner]
    return response({"items": tasks})


@router.get("/v1/notifications")
async def list_notifications(owner: str = Depends(task_owner)) -> dict[str, Any]:
    items = [item for item in load_notifications().values() if item.get("owner") == owner and not item.get("deliveredAt")]
    items.sort(key=lambda item: int(item.get("createdAt", 0)))
    return response({"items": items[-50:]})


@router.post("/v1/presence/heartbeat")
async def presence_heartbeat(request: Request, owner: str = Depends(task_owner)) -> dict[str, Any]:
    """记录模块在线租约，并返回下次应唤醒的时间。"""
    try:
        payload = await request.json()
    except ValueError:
        payload = {}
    payload = payload if isinstance(payload, dict) else {}
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    lease_until = now + 10 * 60_000

    touch_user(owner_host_account_id(owner))
    heartbeat = {
        "owner": owner,
        "lastSeenAt": now,
        "onlineUntil": lease_until,
        "moduleVersion": str(payload.get("moduleVersion", ""))[:64],
        "buildTime": str(payload.get("buildTime", ""))[:64],
        "source": str(payload.get("source", "module"))[:32],
    }
    def record_heartbeat(presence):
        if bounded_config_int(presence.get(owner, {}).get("lastSeenAt", 0), 0, 0) <= now:
            presence[owner] = heartbeat
    runtime.get_state_store().mutate_namespace("presence", record_heartbeat)
    all_tasks = load_tasks()
    migrated = False
    for task in all_tasks.values():

        if task.get("executionMode", "server") == "device":
            task["executionMode"] = "server"
            task.pop("deviceLeaseToken", None)
            task.pop("deviceLeaseUntil", None)
            if task.get("status") == "running":
                task["status"] = "scheduled"
            migrated = True
    if migrated:
        save_tasks(all_tasks)
    tasks = [task for task in all_tasks.values() if task.get("owner") == owner and task.get("enabled", True) and task.get("status") not in {"paused", "cancelled"}]
    next_task_at = min((bounded_config_int(task.get("nextRunAt", 0), 0, 0) for task in tasks if bounded_config_int(task.get("nextRunAt", 0), 0, 0) > now), default=0)
    pending = [item for item in load_notifications().values() if item.get("owner") == owner and not item.get("deliveredAt")]
    pending.sort(key=lambda item: int(item.get("createdAt", 0)))
    return response({"onlineUntil": lease_until, "nextTaskAt": next_task_at, "pendingCount": len(pending), "notifications": pending[-50:]})


@router.post("/v1/notifications/ack")
async def acknowledge_notifications(request: Request, owner: str = Depends(task_owner)) -> dict[str, Any]:
    try:
        payload = await request.json()
    except ValueError:
        raise HTTPException(status_code=400, detail=response(code="NOTIFICATION_INVALID", message="消息 JSON 无效"))
    ids = payload.get("ids", []) if isinstance(payload, dict) else []
    if not isinstance(ids, list) or len(ids) > 100:
        raise HTTPException(status_code=400, detail=response(code="NOTIFICATION_INVALID", message="消息 ID 列表无效"))
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    def acknowledge(items):
        updated = 0
        for notification_id in {str(item) for item in ids}:
            item = items.get(notification_id)
            if item and item.get("owner") == owner and not item.get("deliveredAt"):
                item["deliveredAt"] = now
                updated += 1
        return updated
    updated = runtime.get_state_store().mutate_namespace("notifications", acknowledge)
    return response({"acknowledged": updated})


@router.get("/v1/tasks/{task_id}")
async def get_task(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    _, task = find_owned_task(task_id, owner)
    return response(public_task(task))


@router.post("/v1/tasks/{task_id}/pause")
async def pause_task(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    task = change_owned_task(task_id, owner, "pause")
    task_log(task_id, "任务已暂停")
    return response(public_task(task))


@router.post("/v1/tasks/{task_id}/resume")
async def resume_task(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    task = change_owned_task(task_id, owner, "resume")
    task_log(task_id, "任务已恢复")
    return response(public_task(task))


@router.post("/v1/tasks/{task_id}/cancel")
async def cancel_task(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    task = change_owned_task(task_id, owner, "cancel")
    task_log(task_id, "任务已取消")
    return response(public_task(task))


@router.post("/v1/tasks/{task_id}/run")
async def run_task_now(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    task = change_owned_task(task_id, owner, "run")
    task_log(task_id, "任务已安排立即执行")
    return response(public_task(task))


@router.post("/v1/tasks/{task_id}/configure")
async def configure_task(task_id: str, request: Request, owner: str = Depends(task_owner)) -> dict[str, Any]:
    try:
        payload = await request.json()
    except ValueError:
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务 JSON 无效"))
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务配置无效"))
    _, existing = find_owned_task(task_id, owner)
    changes = {}
    if "schedule" in payload:
        schedule = payload.get("schedule")
        if not isinstance(schedule, dict):
            raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务 schedule 必须是对象"))
        changes["schedule"] = validated_schedule(str(existing.get("taskType", "")), schedule)
    if "request" in payload:
        request_value = payload.get("request") or {}
        if not isinstance(request_value, dict):
            raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务 request 必须是对象"))
        credential_id = str(request_value.get("credentialId", "")).strip()
        if existing.get("taskType") != "http":
            credential = load_credentials().get(credential_id)
            if not credential or credential.get("owner") != owner:
                raise HTTPException(status_code=400, detail=response(code="CREDENTIAL_NOT_FOUND", message="阅微凭据不存在或不属于当前账号"))
        changes["credentialId"] = credential_id
    def configure(tasks):
        task = tasks.get(task_id)
        if task is None or task.get("owner") != owner:
            raise HTTPException(status_code=404, detail=response(code="TASK_NOT_FOUND", message="任务不存在"))
        task.update(changes)
        if "request" in payload:
            task["requestEncrypted"] = encrypt_secret(request_value)
        if "enabled" in payload:
            task["enabled"] = bool(payload.get("enabled"))
            task["status"] = "scheduled" if task["enabled"] else "paused"
            task["nextRunAt"] = next_task_run(task) if task["enabled"] else 0
        if "executionMode" in payload:
            task["executionMode"] = "server"
            task.pop("deviceLeaseToken", None)
            task.pop("deviceLeaseUntil", None)
        now = int(datetime.now(timezone.utc).timestamp() * 1000)
        task["updatedAt"] = max(now, bounded_config_int(task.get("updatedAt", 0), 0, 0) + 1)
        duplicate = find_duplicate_task(tasks, task, exclude_id=task_id)
        if duplicate:
            tasks.pop(duplicate[0], None)
        return task, duplicate[0] if duplicate else ""
    task, removed_id = runtime.get_state_store().mutate_namespace("tasks", configure)
    if removed_id:
        audit_event("task_merged", f"owner:{owner}", metadata={"keptTaskId": task_id, "removedTaskId": removed_id, "reason": "unique_task_key"})
    task_log(task_id, "任务配置已更新")
    return response(public_task(task))


@router.delete("/v1/tasks/{task_id}")
async def delete_task(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    def delete(tasks):
        task = tasks.get(task_id)
        if task is None or task.get("owner") != owner:
            raise HTTPException(status_code=404, detail=response(code="TASK_NOT_FOUND", message="任务不存在"))
        tasks.pop(task_id)
    runtime.get_state_store().mutate_namespace("tasks", delete)
    purge_orphan_task_logs(set(load_tasks()))
    return response({"deleted": True})


@router.get("/v1/tasks/{task_id}/logs")
async def task_logs(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    find_owned_task(task_id, owner)
    path = runtime.TASK_LOG_ROOT / f"{task_id}.log"
    items = []
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines()[-200:]:
            try:
                items.append(json.loads(line))
            except ValueError:
                continue
    return response({"items": items})
