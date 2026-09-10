"""云端任务生命周期、心跳与消息回执。

心跳响应里带待发消息，模块显示成功后回执；未回执的留到下次在线重发。
"""

from typing import Any

from fastapi import APIRouter, Depends, Form, Header, HTTPException, Query, Request, UploadFile, File, status
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.security import HTTPBasicCredentials

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
from app.executors import credential_for_task
from app.responses import response
from app.scheduler import (
    find_owned_task,
    next_task_run,
    normalized_task_schedule,
    public_task,
)
from app.security import task_owner
from app.state import owner_host_account_id
from app.retention import purge_orphan_task_logs
from app.users import touch_user
from app.state import (
    enqueue_task_notification,
    find_duplicate_task,
    load_credentials,
    load_notifications,
    load_presence,
    load_tasks,
    merge_duplicate_tasks,
    save_notifications,
    save_presence,
    save_tasks,
    task_credential_id,
)

router = APIRouter()


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
    if task_type not in {"http", "yeshe_checkin", "yeshe_draw_card", "cloud_auto_read", "traveling_merchant"}:
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="不支持的任务类型"))
    execution_mode = str(payload.get("executionMode", "server")).strip().lower()
    if execution_mode not in {"server", "device"} or (execution_mode == "device" and task_type not in {"yeshe_checkin", "yeshe_draw_card", "cloud_auto_read", "traveling_merchant"}):
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="当前任务类型不支持设备执行"))
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
    schedule = normalized_task_schedule(task_type, schedule)
    task_id = "task_" + secrets.token_hex(10)
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    task = {
        **payload,
        "id": task_id,
        "taskType": task_type,
        "schedule": schedule,
        "owner": owner,
        "status": "scheduled",
        "enabled": bool(payload.get("enabled", True)),
        "createdAt": now,
        "nextRunAt": 0 if task_type == "yeshe_draw_card" else int(payload.get("nextRunAt", now)),
        "runCount": 0,
        "maxRetries": max(0, min(int(payload.get("maxRetries", 3) or 3), 10)),
        "consecutiveFailures": 0,
        "executionMode": execution_mode,
    }
    task["credentialId"] = credential_id
    task["requestEncrypted"] = encrypt_secret(request_value)
    task.pop("request", None)
    tasks = load_tasks()
    duplicate = find_duplicate_task(tasks, task)
    if duplicate:
        task_id = duplicate[0]
        previous = duplicate[1]
        task["id"] = task_id
        task["createdAt"] = previous.get("createdAt", now)
        task["updatedAt"] = now
        for field in ("executionHistory", "lastExecution", "runCount", "consecutiveFailures", "dailyCounterDate", "dailyCounter", "dailyReadDate", "dailyReadMinutes", "bookRotation", "lastCheckinDate", "lastCheckinAt", "claimDueAt", "claimRetryCount", "claimFinalAttemptDate", "claimCompletedDate", "claimFinalFailedDate", "lastClaimAt", "merchantLastNotifiedTripId"):
            if field in previous:
                task[field] = previous[field]
    tasks[task_id] = task
    save_tasks(tasks)
    task_log(task_id, "任务已创建")
    result = response(public_task(task))
    if idempotency_key:
        runtime.get_state_store().save_idempotency(owner, "create_task", idempotency_key, result, now)
    return result


@router.post("/v1/tasks/claim")
async def claim_device_task(request: Request, owner: str = Depends(task_owner)) -> dict[str, Any]:
    """原子领取一条设备执行任务；凭据只随短期租约返回，不写入公开任务列表。"""
    try:
        payload = await request.json()
    except ValueError:
        payload = {}
    payload = payload if isinstance(payload, dict) else {}
    requested_id = str(payload.get("taskId", "")).strip()
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    tasks = load_tasks()
    candidates = []
    for task_id, task in tasks.items():
        if task.get("owner") != owner or task.get("executionMode", "server") != "device":
            continue
        if task.get("status") == "running" and int(task.get("deviceLeaseUntil", 0) or 0) <= now:
            task["status"] = "scheduled"
            task.pop("deviceLeaseToken", None)
            task.pop("deviceLeaseUntil", None)
        if not task.get("enabled", True) or task.get("status") in {"paused", "cancelled", "running"}:
            continue
        if requested_id and task_id != requested_id:
            continue
        if task.get("taskType") == "yeshe_draw_card":
            due = bool(task.get("triggeredByCheckinReward"))
        else:
            due = int(task.get("nextRunAt", 0) or 0) <= now
        if due:
            candidates.append((task_id, task))
    candidates.sort(key=lambda pair: int(pair[1].get("nextRunAt", 0) or 0))
    if not candidates:
        return response({"task": None})
    task_id, task = candidates[0]
    lease_owner = f"device:{owner}"
    lease_until = now + 15 * 60_000
    if not runtime.get_state_store().acquire_task_lock(task_id, lease_owner, lease_until, now):
        return response({"task": None})
    try:
        request_value, secret = credential_for_task(task)
        lease_token = secrets.token_urlsafe(32)
        task["status"] = "running"
        task["lastRunAt"] = now
        task["deviceLeaseToken"] = lease_token
        task["deviceLeaseUntil"] = lease_until
        save_tasks(tasks)
        return response({
            "task": public_task(task),
            "leaseToken": lease_token,
            "leaseUntil": lease_until,
            "request": request_value,
            "credential": {
                "baseUrl": str(secret.get("baseUrl") or "https://api.reamicro.zhendong.ltd/"),
                "token": str(secret.get("token")),
            },
        })
    except Exception:
        runtime.get_state_store().release_task_lock(task_id, lease_owner)
        raise HTTPException(status_code=409, detail=response(code="TASK_CLAIM_FAILED", message="设备任务凭据不可用"))


@router.post("/v1/tasks/{task_id}/complete")
async def complete_device_task(task_id: str, request: Request, owner: str = Depends(task_owner)) -> dict[str, Any]:
    try:
        payload = await request.json()
    except ValueError:
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务结果 JSON 无效"))
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务结果无效"))
    tasks, task = find_owned_task(task_id, owner)
    if task.get("executionMode", "server") != "device":
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务不是设备执行模式"))
    token = str(payload.get("leaseToken", ""))
    if not token or token != str(task.get("deviceLeaseToken", "")):
        raise HTTPException(status_code=409, detail=response(code="TASK_LEASE_INVALID", message="设备任务租约已失效"))
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    if int(task.get("deviceLeaseUntil", 0) or 0) < now:
        raise HTTPException(status_code=409, detail=response(code="TASK_LEASE_EXPIRED", message="设备任务租约已过期"))
    result = str(payload.get("result", "failed")).strip().lower()
    if result not in {"success", "failed", "paused"}:
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务结果类型无效"))
    message = " ".join(str(payload.get("message", "任务已完成")).split())[:500]
    state = payload.get("state", {})
    if isinstance(state, dict):
        allowed_state = {"lastCheckinDate", "lastCheckinAt", "claimDueAt", "nextRunAtOverride", "claimRetryCount", "claimFinalAttemptDate", "claimCompletedDate", "claimFinalFailedDate", "lastClaimAt", "claimJustCompleted", "notificationItems", "dailyCounterDate", "dailyCounter", "lastDrawItems", "lastDrawResult", "lastDrawAt", "waitingForCheckinReward", "merchantLastNotifiedTripId", "merchantPausedUntil"}
        for key in allowed_state:
            if key in state:
                task[key] = state[key]
    task.pop("deviceLeaseToken", None)
    task.pop("deviceLeaseUntil", None)
    task["lastMessage"] = message
    task["runCount"] = int(task.get("runCount", 0)) + 1
    task["status"] = result
    if task.get("taskType") == "yeshe_draw_card" and result == "success":
        # 事件型抽卡只允许被本次签到奖励触发一次，完成回报后清掉事件标记。
        task.pop("triggeredByCheckinReward", None)
    task["consecutiveFailures"] = 0 if result == "success" else int(task.get("consecutiveFailures", 0)) + 1
    if result == "success":
        task["nextRunAt"] = next_task_run(task, now)
    elif result == "failed" and task["consecutiveFailures"] <= max(0, min(int(task.get("maxRetries", 3)), 10)):
        task["status"] = "scheduled"
        task["nextRunAt"] = now + 5 * 60_000
    else:
        task["enabled"] = False
        task["status"] = "paused"
        task["nextRunAt"] = 0
    save_tasks(tasks)
    runtime.get_state_store().release_task_lock(task_id, f"device:{owner}")
    finished_at = now
    # notify=false 用于行商在途/已结算等无需打扰用户的中间态，只更新状态不投消息。
    should_notify = payload.get("notify", True) is not False
    notification = None
    if should_notify:
        notification_id = enqueue_task_notification(task, result, message, finished_at)
        notification = load_notifications().get(notification_id)
    if task.get("taskType") == "yeshe_checkin" and result == "success" and task.get("claimJustCompleted"):
        credential_id = task_credential_id(task)
        for draw_task in tasks.values():
            if draw_task.get("owner") == owner and draw_task.get("taskType") == "yeshe_draw_card" and task_credential_id(draw_task) == credential_id and draw_task.get("enabled", True):
                draw_task["triggeredByCheckinReward"] = True
                draw_task["status"] = "scheduled"
                draw_task["nextRunAt"] = 0
        save_tasks(tasks)
    result_body = public_task(task)
    if notification:
        result_body["notification"] = notification
    return response(result_body)


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
    # 顺带记录用户活跃：首次出现的阅微账号自动建档，省去管理员手工录入。
    touch_user(owner_host_account_id(owner))
    presence = load_presence()
    presence[owner] = {
        "owner": owner,
        "lastSeenAt": now,
        "onlineUntil": lease_until,
        "moduleVersion": str(payload.get("moduleVersion", ""))[:64],
        "buildTime": str(payload.get("buildTime", ""))[:64],
        "source": str(payload.get("source", "module"))[:32],
    }
    save_presence(presence)
    all_tasks = load_tasks()
    migrated = False
    for task in all_tasks.values():
        # 兼容 2.3.1 以前创建的签到/抽卡任务：首次模块心跳时自动切到设备执行。
        if task.get("owner") == owner and "executionMode" not in task and task.get("taskType") in {"yeshe_checkin", "yeshe_draw_card", "cloud_auto_read"}:
            task["executionMode"] = "device"
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
    payload = await request.json()
    ids = payload.get("ids", []) if isinstance(payload, dict) else []
    if not isinstance(ids, list) or len(ids) > 100:
        raise HTTPException(status_code=400, detail=response(code="NOTIFICATION_INVALID", message="消息 ID 列表无效"))
    items = load_notifications()
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    updated = 0
    for notification_id in {str(item) for item in ids}:
        item = items.get(notification_id)
        if item and item.get("owner") == owner and not item.get("deliveredAt"):
            item["deliveredAt"] = now
            updated += 1
    if updated:
        save_notifications(items)
    return response({"acknowledged": updated})


@router.get("/v1/tasks/{task_id}")
async def get_task(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    _, task = find_owned_task(task_id, owner)
    return response(public_task(task))


@router.post("/v1/tasks/{task_id}/pause")
async def pause_task(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    tasks, task = find_owned_task(task_id, owner)
    task["status"] = "paused"
    task["enabled"] = False
    task["nextRunAt"] = 0
    save_tasks(tasks)
    task_log(task_id, "任务已暂停")
    return response(public_task(task))


@router.post("/v1/tasks/{task_id}/resume")
async def resume_task(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    tasks, task = find_owned_task(task_id, owner)
    task["status"] = "scheduled"
    task["enabled"] = True
    task["nextRunAt"] = 0 if task.get("taskType") == "yeshe_draw_card" else int(datetime.now(timezone.utc).timestamp() * 1000)
    save_tasks(tasks)
    task_log(task_id, "任务已恢复")
    return response(public_task(task))


@router.post("/v1/tasks/{task_id}/cancel")
async def cancel_task(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    tasks, task = find_owned_task(task_id, owner)
    task["status"] = "cancelled"
    task["enabled"] = False
    save_tasks(tasks)
    task_log(task_id, "任务已取消")
    return response(public_task(task))


@router.post("/v1/tasks/{task_id}/run")
async def run_task_now(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    tasks, task = find_owned_task(task_id, owner)
    if task.get("taskType") == "yeshe_draw_card":
        task["triggeredByCheckinReward"] = True
    task["status"] = "scheduled"
    task["enabled"] = True
    task["nextRunAt"] = int(datetime.now(timezone.utc).timestamp() * 1000)
    save_tasks(tasks)
    task_log(task_id, "任务已安排立即执行")
    return response(public_task(task))


@router.post("/v1/tasks/{task_id}/configure")
async def configure_task(task_id: str, request: Request, owner: str = Depends(task_owner)) -> dict[str, Any]:
    payload = await request.json()
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务配置无效"))
    tasks, task = find_owned_task(task_id, owner)
    if "schedule" in payload:
        schedule = payload.get("schedule")
        if not isinstance(schedule, dict):
            raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务 schedule 必须是对象"))
        task["schedule"] = normalized_task_schedule(str(task.get("taskType", "")), schedule)
    if "request" in payload:
        request_value = payload.get("request") or {}
        if not isinstance(request_value, dict):
            raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务 request 必须是对象"))
        credential_id = str(request_value.get("credentialId", "")).strip()
        if task.get("taskType") != "http":
            credential = load_credentials().get(credential_id)
            if not credential or credential.get("owner") != owner:
                raise HTTPException(status_code=400, detail=response(code="CREDENTIAL_NOT_FOUND", message="阅微凭据不存在或不属于当前账号"))
        task["credentialId"] = credential_id
        task["requestEncrypted"] = encrypt_secret(request_value)
    if "enabled" in payload:
        task["enabled"] = bool(payload.get("enabled"))
        task["status"] = "scheduled" if task["enabled"] else "paused"
        task["nextRunAt"] = next_task_run(task) if task["enabled"] else 0
    if "executionMode" in payload:
        execution_mode = str(payload.get("executionMode", "server")).strip().lower()
        if execution_mode not in {"server", "device"} or (execution_mode == "device" and task.get("taskType") not in {"yeshe_checkin", "yeshe_draw_card", "cloud_auto_read", "traveling_merchant"}):
            raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="当前任务类型不支持设备执行"))
        task["executionMode"] = execution_mode
    task["updatedAt"] = int(datetime.now(timezone.utc).timestamp() * 1000)
    duplicate = find_duplicate_task(tasks, task, exclude_id=task_id)
    if duplicate:
        tasks.pop(duplicate[0], None)
        audit_event("task_merged", f"owner:{owner}", metadata={"keptTaskId": task_id, "removedTaskId": duplicate[0], "reason": "unique_task_key"})
    save_tasks(tasks)
    task_log(task_id, "任务配置已更新")
    return response(public_task(task))


@router.delete("/v1/tasks/{task_id}")
async def delete_task(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    tasks, _ = find_owned_task(task_id, owner)
    tasks.pop(task_id, None)
    save_tasks(tasks)
    # 原先在这里写一条"任务已删除"日志——那会给已不存在的任务**新建**日志文件，
    # 正是孤儿日志的来源。任务没了，它的日志也没有保留价值，直接删掉。
    purge_orphan_task_logs(set(tasks))
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
