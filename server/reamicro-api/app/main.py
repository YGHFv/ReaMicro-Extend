"""ReaMicro API server minimal v1 implementation.

内容包、备份和任务接口在此基础上扩展；本版本先提供健康检查、能力发现和 API Key 认证。
"""
import hashlib
import hmac
import os
import secrets
import json
import asyncio
import urllib.request
import urllib.error
import urllib.parse
import shutil
import threading
import html
import base64
import binascii
import io
import re
import time
import zipfile
import tempfile
import hashlib as _hashlib
import unicodedata
from email.utils import parsedate_to_datetime
from pathlib import Path
from datetime import datetime, timezone, timedelta
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Query, Form, status, UploadFile, File
from fastapi.security import HTTPBasicCredentials
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse
from fastapi.responses import JSONResponse
from fastapi.responses import Response
from app.storage import StateStore
from app import runtime
from app.config_store import (
    active_auth_mode,
    api_key_auth_configured,
    bounded_config_int,
    configured_auth_modes,
    default_config,
    infer_auth_mode,
    load_config,
    module_upload_kinds,
    module_upload_kinds_form,
    normalized_config_list,
    public_server_capabilities,
    save_config,
)
from app.responses import (
    response,
)
from app.audit import (
    audit_actor,
    audit_event,
    task_log,
)
from app.crypto import (
    api_key_digest,
    decrypt_secret,
    encrypt_secret,
    generate_long_secret,
    password_hash,
    password_matches,
    secret_cipher_key,
    secret_key_id,
)
from app.state import (
    backup_owner_dir_name,
    canonical_owner_of,
    canonicalize_owner_identities,
    credential_public,
    enqueue_task_notification,
    find_duplicate_task,
    legacy_owner_identities,
    load_credentials,
    load_notifications,
    load_presence,
    load_tasks,
    merge_duplicate_credentials,
    merge_duplicate_tasks,
    migrate_legacy_backup_dirs,
    owner_host_account_id,
    save_credentials,
    save_notifications,
    save_presence,
    save_tasks,
    task_credential_id,
    task_unique_key,
)
from app.labels import (
    _ADMIN_METADATA_LABELS,
    _admin_action_label,
    _admin_channel_label,
    _admin_health_label,
    _admin_kind_label,
    _admin_metadata_label,
    _admin_owner_label,
    _admin_result_label,
    _admin_status_label,
    _admin_task_label,
    _admin_task_status_label,
)



























































def create_server_snapshot() -> Path:
    runtime.SERVER_BACKUP_ROOT.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    database_copy = runtime.SERVER_BACKUP_ROOT / f"state-{timestamp}.sqlite3"
    archive = runtime.SERVER_BACKUP_ROOT / f"reamicro-server-{timestamp}.zip"
    runtime.get_state_store().backup(database_copy)
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as stream:
        stream.write(database_copy, "state/reamicro.sqlite3")
        if runtime.CONFIG_PATH.is_file():
            stream.write(runtime.CONFIG_PATH, "config/server.json")
        manifest = {"createdAt": int(datetime.now(timezone.utc).timestamp() * 1000), "files": []}
        source_map = {"state/reamicro.sqlite3": database_copy, "config/server.json": runtime.CONFIG_PATH}
        for name, source in source_map.items():
            if source.is_file():
                manifest["files"].append({"path": name, "size": source.stat().st_size, "sha256": hashlib.sha256(source.read_bytes()).hexdigest()})
        stream.writestr("snapshot-manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
    database_copy.unlink(missing_ok=True)
    prune_server_snapshots(int(load_config().get("serverSnapshotRetention", 30)))
    return archive


def prune_server_snapshots(retention: int) -> int:
    snapshots = sorted(runtime.SERVER_BACKUP_ROOT.glob("reamicro-server-*.zip"), reverse=True)
    removed = 0
    for expired in snapshots[max(int(retention), 3):]:
        expired.unlink(missing_ok=True)
        removed += 1
    return removed


def list_server_snapshots() -> list[dict[str, Any]]:
    items = []
    for path in sorted(runtime.SERVER_BACKUP_ROOT.glob("reamicro-server-*.zip"), reverse=True):
        try:
            items.append({"name": path.name, "size": path.stat().st_size, "modifiedAt": int(path.stat().st_mtime * 1000), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        except OSError:
            continue
    return items


def rotate_secret_key(new_key: str) -> int:
    validate_admin_password(new_key)
    credentials = load_credentials()
    tasks = load_tasks()
    decrypted_credentials: dict[str, Any] = {}
    for key, value in credentials.items():
        if value.get("secretEncrypted"):
            decrypted_credentials[key] = decrypt_secret(str(value["secretEncrypted"]))
    decrypted_tasks: dict[str, Any] = {}
    for key, value in tasks.items():
        if value.get("requestEncrypted"):
            decrypted_tasks[key] = decrypt_secret(str(value["requestEncrypted"]))
    config = load_config()
    config["secretKey"] = new_key
    save_config(config)
    for key, secret in decrypted_credentials.items():
        credentials[key]["secretEncrypted"] = encrypt_secret(secret)
    for key, request in decrypted_tasks.items():
        tasks[key]["requestEncrypted"] = encrypt_secret(request)
    save_credentials(credentials)
    save_tasks(tasks)
    return len(decrypted_credentials) + len(decrypted_tasks)


def allow_rate_limit(key: str) -> bool:
    now = time.monotonic()
    with runtime.rate_lock:
        values = [item for item in runtime.rate_buckets.get(key, []) if now - item < runtime.RATE_WINDOW_SECONDS]
        if len(values) >= runtime.RATE_LIMIT:
            runtime.rate_buckets[key] = values
            return False
        values.append(now)
        runtime.rate_buckets[key] = values
        if len(runtime.rate_buckets) > 10_000:
            oldest = sorted(runtime.rate_buckets, key=lambda item: runtime.rate_buckets[item][-1] if runtime.rate_buckets[item] else now)[:1000]
            for item in oldest:
                runtime.rate_buckets.pop(item, None)
        return True












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




def execute_http_task(task: dict[str, Any]) -> tuple[str, str]:
    request = decrypt_secret(str(task.get("requestEncrypted", ""))) if task.get("requestEncrypted") else {}
    url = str(request.get("url", "")).strip()
    if not url.startswith(("https://", "http://")):
        return "failed", "任务 URL 无效"
    method = str(request.get("method", "GET")).upper()
    body = request.get("body", "")
    if isinstance(body, str):
        body_bytes = body.encode("utf-8")
    else:
        body_bytes = json.dumps(body, ensure_ascii=False).encode("utf-8")
    request_obj = urllib.request.Request(url, data=body_bytes if method != "GET" else None, method=method)
    for key, value in dict(request.get("headers", {})).items():
        request_obj.add_header(str(key), str(value))
    try:
        with urllib.request.urlopen(request_obj, timeout=30) as stream:
            response_body = stream.read(4_096).decode("utf-8", errors="replace")
            if any(word in response_body.lower() for word in ("captcha", "验证码", "risk-control", "风控")):
                return "paused", "检测到验证码或风控响应，任务已暂停"
            return "success", f"HTTP {stream.status}: {response_body[:300]}"
    except urllib.error.HTTPError as error:
        body_text = error.read(2_000).decode("utf-8", errors="replace")
        if error.code in (401, 403, 429) or any(word in body_text.lower() for word in ("captcha", "验证码", "风控")):
            return "paused", f"HTTP {error.code} 触发认证/风控，任务已暂停"
        return "failed", f"HTTP {error.code}: {body_text[:300]}"
    except Exception as error:
        return "failed", str(error)


def redact_message(value: str) -> str:
    return value.replace("Bearer ", "Bearer ***").replace("token", "token=***")[:500]


def json_http_request(url: str, token: str, payload: Any, endpoint: str = "", timeout: int = 45) -> tuple[int, Any, str]:
    target = url.rstrip("/") + "/" + endpoint.lstrip("/")
    body = json.dumps(payload if payload is not None else {}, ensure_ascii=False).encode("utf-8")
    request_obj = urllib.request.Request(target, data=body, method="POST", headers={
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "Accept": "application/json",
        "platform": "android",
        "User-Agent": "ReaMicro-Cloud-Worker/1.0",
    })
    try:
        with urllib.request.urlopen(request_obj, timeout=timeout) as stream:
            raw = stream.read(128_000).decode("utf-8", errors="replace")
            try:
                parsed = json.loads(raw)
            except ValueError:
                parsed = {"raw": raw}
            return stream.status, parsed, raw
    except urllib.error.HTTPError as error:
        raw = error.read(16_000).decode("utf-8", errors="replace")
        return error.code, {}, raw


# 每日任务状态（取自阅微 2.3.1 DailyTask.status 的界面分支）：
# 2 = 已完成待领取，1 = 已领取，0 = 未完成。
DAILY_TASK_STATUS_CLAIMABLE = 2
DAILY_TASK_STATUS_CLAIMED = 1
# get-my-task-list 的 queryType：日常任务传 1。
DAILY_TASK_QUERY_TYPE = 1


def claim_daily_task_rewards(base_url: str, token: str, request: dict[str, Any]) -> tuple[str, str, str]:
    """领取野社每日签到任务奖励（签到 8 小时后解锁）。

    返回 (结果, 面向用户的说明, 明细)。结果取值：
    - granted：本次领到了奖励
    - already：奖励此前已领取
    - locked：奖励还没解锁，等下一轮定时执行，不算失败
    - paused：命中认证或风控，任务应暂停
    - failed：真实错误，交由调用方走重试逻辑
    """
    list_endpoint = str(request.get("taskListEndpoint") or "rest/task/get-my-task-list")
    claim_endpoint = str(request.get("claimEndpoint") or "rest/task/receive-reward")
    query_type = bounded_config_int(request.get("taskQueryType", DAILY_TASK_QUERY_TYPE), DAILY_TASK_QUERY_TYPE, 0)
    status_code, body, raw = json_http_request(
        base_url, token, {"pageNum": 1, "pageSize": 50, "queryType": query_type}, list_endpoint,
    )
    if status_code in (401, 403, 429):
        return "paused", f"阅微认证/风控响应 HTTP {status_code}", ""
    if status_code < 200 or status_code >= 300:
        return "failed", f"获取野社任务列表失败 HTTP {status_code}: {redact_message(raw)}", ""
    tasks = nested_value(body, "data", "tasks")
    if not isinstance(tasks, list):
        tasks = nested_value(body, "data", "list") or nested_value(body, "data") or []
    if isinstance(tasks, dict):
        tasks = [tasks]
    if not isinstance(tasks, list):
        tasks = []
    claimable, claimed = [], []
    for item in tasks:
        if not isinstance(item, dict):
            continue
        item_id = item.get("id") or item.get("taskId")
        if not item_id:
            continue
        item_status = bounded_config_int(item.get("status", 0), 0, 0)
        if item_status == DAILY_TASK_STATUS_CLAIMABLE:
            claimable.append((str(item_id), _metadata_text(item.get("content")) or str(item_id)))
        elif item_status == DAILY_TASK_STATUS_CLAIMED:
            claimed.append(_metadata_text(item.get("content")) or str(item_id))
    if not claimable:
        if claimed:
            return "already", "签到奖励此前已领取", "、".join(claimed[:5])
        # 既没有待领取也没有已领取：奖励还没解锁，下一轮再看。
        return "locked", "暂无可领取的签到奖励，等待下一次检查", ""
    granted, errors = [], []
    for item_id, label in claimable[:20]:
        claim_status, claim_body, claim_raw = json_http_request(base_url, token, {"id": item_id}, claim_endpoint)
        if claim_status in (401, 403, 429):
            return "paused", f"阅微认证/风控响应 HTTP {claim_status}", ""
        message = nested_value(claim_body, "data", "message") or nested_value(claim_body, "message") or ""
        ok = 200 <= claim_status < 300 and nested_value(claim_body, "data", "success") is not False
        # 单项回"已领取"不中断整批，继续领其余项。
        if ok or (200 <= claim_status < 300 and claim_reward_already_granted(message, claim_body)):
            granted.append(label)
            continue
        errors.append(f"{label}：{redact_message(str(message) or claim_raw)}")
    if granted and not errors:
        return "granted", f"签到奖励领取成功（{len(granted)} 项）", "、".join(granted[:5])
    if granted:
        return "granted", f"签到奖励部分领取成功（{len(granted)} 项）", "；".join(errors[:3])
    return "failed", "、".join(errors[:3]) or "签到奖励领取失败", ""


# 阅微对重复领取的回复文案。命中任意一条即视为奖励已到账的终态，不再重试。
CLAIM_ALREADY_GRANTED_PATTERNS = (
    "已领取",
    "已经领取",
    "已领过",
    "重复领取",
    "已发放",
    "已到账",
    "already claimed",
    "already received",
)


def claim_reward_already_granted(message: Any, body: Any = None) -> bool:
    """判断领取周奖励的失败回复是否其实表示"早就领过了"。

    阅微在重复领取时返回 HTTP 200 + `data.success=false` + "上一周奖励已领取"。
    这是终态而非可重试错误，必须和真正的领取失败区分开。
    """
    text = str(message or "")
    if any(pattern in text for pattern in CLAIM_ALREADY_GRANTED_PATTERNS):
        return True
    if any(pattern in text.lower() for pattern in ("already claimed", "already received")):
        return True
    for key in ("claimed", "isClaimed", "received", "hasClaimed"):
        if nested_value(body, "data", key) is True:
            return True
    return False


def nested_value(value: Any, *keys: str) -> Any:
    current = value
    for key in keys:
        if isinstance(current, dict):
            current = current.get(key)
        else:
            return None
    return current


def lottery_result_summary(body: Any) -> str:
    """从阅微抽卡响应中提取适合通知展示的结果。"""
    result = nested_value(body, "data", "result") or nested_value(body, "result") or nested_value(body, "data")
    if isinstance(result, str):
        text = result.strip()
        if text.startswith(("{", "[")):
            try:
                result = json.loads(text)
            except ValueError:
                return redact_message(text)
        else:
            return redact_message(text)
    items = result if isinstance(result, list) else [result] if isinstance(result, dict) else []
    summaries: list[str] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        name = item.get("name") or item.get("cardName") or item.get("propName") or item.get("title") or item.get("result")
        quality = item.get("quality") or item.get("cardQuality") or item.get("propQuality") or item.get("rarity")
        count = item.get("count") or item.get("quantity") or item.get("num")
        if name:
            summary = str(name)
            if quality:
                summary += f"（{quality}）"
            if count not in (None, "", 1, "1"):
                summary += f" ×{count}"
            summaries.append(summary)
    return "、".join(summaries[:10]) or redact_message(json.dumps(result if result is not None else body, ensure_ascii=False)[:300])


def credential_for_task(task: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    request = decrypt_secret(str(task.get("requestEncrypted", ""))) if task.get("requestEncrypted") else {}
    credential_id = str(request.get("credentialId", "")).strip()
    credentials = load_credentials()
    credential = credentials.get(credential_id)
    if not credential or credential.get("owner") != task.get("owner"):
        raise ValueError("阅微凭据不存在或不属于当前账号")
    if not credential.get("enabled", True):
        raise ValueError("阅微凭据已暂停，请重新验证后再运行任务")
    try:
        secret = decrypt_secret(str(credential.get("secretEncrypted", "")))
    except Exception as error:
        update_credential_health(str(credential.get("id", credential_id)), False, f"凭据解密失败：{error}")
        raise ValueError("阅微凭据无法解密，请重新上传")
    if not isinstance(secret, dict) or not secret.get("token"):
        raise ValueError("阅微凭据无有效 token")
    update_credential_health(str(credential.get("id", credential_id)), True, "任务使用成功", used=True)
    return request, secret


def execute_reamicro_task(task: dict[str, Any]) -> tuple[str, str]:
    request, secret = credential_for_task(task)
    base_url = str(secret.get("baseUrl") or "https://api.reamicro.zhendong.ltd/").strip()
    token = str(secret.get("token"))
    task_type = str(task.get("taskType"))
    configured_body = request.get("body", {})
    if isinstance(configured_body, str):
        try:
            configured_body = json.loads(configured_body or "{}")
        except ValueError:
            configured_body = {}
    if task_type == "yeshe_draw_card":
        if not task.pop("triggeredByCheckinReward", False):
            task["waitingForCheckinReward"] = True
            return "success", "野社自动抽卡等待签到奖励领取完成后触发"
        daily_limit = max(1, min(int(request.get("dailyLimit", 1) or 1), 20))
        today = datetime.now(timezone(timedelta(hours=8))).date().isoformat()
        if task.get("dailyCounterDate") == today and int(task.get("dailyCounter", 0)) >= daily_limit:
            return "success", f"野社今日抽卡已达到上限 {daily_limit} 次"
        status_code, body, raw = json_http_request(base_url, token, configured_body, str(request.get("endpoint") or "rest/lottery/lottery-v2"))
        if status_code in (401, 403, 429):
            return "paused", f"阅微认证/风控响应 HTTP {status_code}"
        if status_code < 200 or status_code >= 300:
            return "failed", f"抽卡请求 HTTP {status_code}: {redact_message(raw)}"
        previous_date = task.get("dailyCounterDate")
        task["dailyCounterDate"] = today
        task["dailyCounter"] = int(task.get("dailyCounter", 0)) + 1 if previous_date == today else 1
        summary = lottery_result_summary(body)
        task["lastDrawResult"] = summary
        task["lastDrawAt"] = int(datetime.now(timezone.utc).timestamp() * 1000)
        task["waitingForCheckinReward"] = False
        return "success", f"野社自动抽卡完成（今日 {task['dailyCounter']}/{daily_limit}）：{summary}"
    if task_type == "yeshe_checkin":
        china_zone = timezone(timedelta(hours=8))
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        today = datetime.fromtimestamp(now_ms / 1000, china_zone).date().isoformat()
        status_code, lore, raw = json_http_request(base_url, token, configured_body, str(request.get("endpoint") or "rest/community/get-daily-lore"))
        if status_code in (401, 403, 429):
            return "paused", f"阅微认证/风控响应 HTTP {status_code}"
        if status_code < 200 or status_code >= 300:
            return "failed", f"获取野社每日轶闻失败 HTTP {status_code}: {redact_message(raw)}"
        lore_id = nested_value(lore, "data", "id") or nested_value(lore, "data", "loreId") or nested_value(lore, "id")
        claimed = bool(nested_value(lore, "data", "isFinish") or nested_value(lore, "data", "claimed"))
        checkin_message = "野社零点签到已完成" if claimed else ""
        if not claimed:
            if not lore_id:
                return "failed", "阅微每日轶闻响应缺少 userLoreId"
            status_code, body, raw = json_http_request(base_url, token, {"userLoreId": int(lore_id)}, str(request.get("completeEndpoint") or "rest/community/complete-daily-lore"))
            if status_code in (401, 403, 429):
                return "paused", f"阅微认证/风控响应 HTTP {status_code}"
            if status_code < 200 or status_code >= 300:
                return "failed", f"野社签到提交失败 HTTP {status_code}: {redact_message(raw)}"
            checkin_message = "野社零点签到完成"
        if task.get("lastCheckinDate") != today:
            task["lastCheckinDate"] = today
            task["lastCheckinAt"] = now_ms
            task["claimDueAt"] = now_ms + 8 * 3_600_000
            task["claimRetryCount"] = 0
            task["claimFinalAttemptDate"] = ""
            task["claimCompletedDate"] = ""
        if task.get("claimCompletedDate") == today:
            return "success", f"{checkin_message or '野社签到状态已检查'}，签到奖励今日已领取"
        claim_due_at = bounded_config_int(task.get("claimDueAt", 0), now_ms + 8 * 3_600_000, 0)
        if now_ms < claim_due_at:
            task["nextRunAtOverride"] = claim_due_at
            return "success", f"{checkin_message or '野社签到状态已检查'}，奖励将在签到 8 小时后自动领取"

        # 要领的是每日签到任务奖励（签到 8 小时后解锁），走 rest/task 那套接口：
        # 先取任务列表，再对 status=2（已完成待领取）的项逐个 receive-reward。
        claim_result, claim_message, claim_detail = claim_daily_task_rewards(base_url, token, request)
        if claim_result == "paused":
            return "paused", claim_message
        if claim_result in {"granted", "already", "locked"}:
            task["claimRetryCount"] = 0
            task["claimFinalAttemptDate"] = ""
            if claim_result == "locked":
                # 奖励尚未解锁不是失败，等下一轮定时执行即可，不进重试循环。
                return "success", f"{checkin_message or '野社签到状态已检查'}，{claim_message}"
            task["lastClaimAt"] = now_ms
            task["claimCompletedDate"] = today
            # 已领取状态同样放行联动抽卡，否则抽卡会一直等在"等待签到奖励领取完成"。
            task["claimJustCompleted"] = True
            detail = f"：{claim_detail}" if claim_detail else ""
            return "success", f"{checkin_message or '野社签到状态已检查'}，{claim_message}{detail}"

        retry_count = bounded_config_int(task.get("claimRetryCount", 0), 0, 0) + 1
        task["claimRetryCount"] = retry_count
        local_now = datetime.fromtimestamp(now_ms / 1000, china_zone)
        if retry_count <= 3:
            task["nextRunAtOverride"] = now_ms + 5 * 60_000
            return "success", f"签到奖励领取失败（第 {retry_count}/3 次），5 分钟后自动重试：{redact_message(str(claim_message))}"
        if task.get("claimFinalAttemptDate") != today:
            final_at = local_now.replace(hour=23, minute=59, second=0, microsecond=0)
            if final_at <= local_now:
                final_at = local_now + timedelta(minutes=1)
            task["claimFinalAttemptDate"] = today
            task["nextRunAtOverride"] = int(final_at.timestamp() * 1000)
            return "success", f"签到奖励连续领取失败，已安排今日 23:59 最后重试：{redact_message(str(claim_message))}"
        task["claimFinalFailedDate"] = today
        return "success", f"签到奖励今日最终领取仍失败：{redact_message(str(claim_message))}"
    if task_type == "cloud_auto_read":
        books = request.get("books") if isinstance(request.get("books"), list) else []
        if not books:
            status_code, record_body, raw = json_http_request(
                base_url,
                token,
                {"pageNum": 1, "pageSize": int(request.get("recentLimit", 1) or 1)},
                "rest/read/get-reader-record-list",
            )
            if status_code < 200 or status_code >= 300:
                return ("paused", f"读取最近阅读记录失败 HTTP {status_code}") if status_code in (401, 403, 429) else ("failed", f"读取最近阅读记录失败 HTTP {status_code}: {redact_message(raw)}")
            books = nested_value(record_body, "data", "list") or nested_value(record_body, "data") or []
        if isinstance(books, dict):
            books = [books]
        books = books[: max(1, min(int(request.get("bookLimit", 1) or 1), 10))]
        duration_minutes = max(1, min(int(request.get("durationMinutes", 30) or 30), 720))
        daily_limit_minutes = max(duration_minutes, min(int(request.get("dailyLimitMinutes", 720) or 720), 1440))
        today = datetime.now(timezone(timedelta(hours=8))).date().isoformat()
        used_today = int(task.get("dailyReadMinutes", 0)) if task.get("dailyReadDate") == today else 0
        duration_minutes = min(duration_minutes, max(daily_limit_minutes - used_today, 0))
        if duration_minutes <= 0:
            return "success", f"云端阅读今日已达到 {daily_limit_minutes} 分钟上限"
        duration_seconds = duration_minutes * 60
        rotation = int(task.get("bookRotation", 0)) % max(len(books), 1)
        books = books[rotation:] + books[:rotation]
        completed = 0
        for book in books:
            if not isinstance(book, dict):
                continue
            cloud_id = book.get("cloudBookId") or book.get("bookId") or book.get("id")
            if not cloud_id:
                continue
            progress_payload = {
                "objectId": str(book.get("objectId") or cloud_id),
                "bookId": str(book.get("bookId") or cloud_id),
                "bookName": str(book.get("name") or book.get("bookName") or ""),
                "chapterNum": int(book.get("chapterNum") or 0),
                "cursorIndex": str(book.get("cursorIndex") or ""),
                "title": str(book.get("name") or book.get("bookName") or ""),
                "progress": min(0.999, float(book.get("progress") or 0.0) + 0.01),
            }
            progress_status, _, progress_raw = json_http_request(base_url, token, progress_payload, "rest/read/update-reader-progress")
            if progress_status in (401, 403, 429):
                return "paused", f"上报阅读进度触发认证/风控 HTTP {progress_status}"
            if progress_status < 200 or progress_status >= 300:
                return "failed", f"上报阅读进度失败 HTTP {progress_status}: {redact_message(progress_raw)}"
            china_date = datetime.now(timezone(timedelta(hours=8))).date().isoformat()
            time_payload = {"list": [{"bookId": int(cloud_id), "date": china_date, "duration": duration_seconds, "verify": ""}]}
            time_status, _, time_raw = json_http_request(base_url, token, time_payload, "rest/read/update-reader-time-by-date")
            if time_status in (401, 403, 429):
                return "paused", f"上报阅读时长触发认证/风控 HTTP {time_status}"
            if time_status < 200 or time_status >= 300:
                return "failed", f"上报阅读时长失败 HTTP {time_status}: {redact_message(time_raw)}"
            completed += 1
        if completed:
            task["dailyReadDate"] = today
            task["dailyReadMinutes"] = used_today + duration_minutes
            task["bookRotation"] = rotation + completed
        return ("success", f"云端自动阅读完成 {completed} 本，累计 {duration_minutes} 分钟") if completed else ("failed", "没有找到可阅读的图书")
    return "failed", f"未知阅微任务类型：{task_type}"


def execute_task(task: dict[str, Any]) -> tuple[str, str]:
    if task.get("taskType") in {"http"}:
        return execute_http_task(task)
    if task.get("taskType") in {"yeshe_checkin", "yeshe_draw_card", "cloud_auto_read"}:
        return execute_reamicro_task(task)
    return "failed", f"未知任务类型：{task.get('taskType')}"


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

app = FastAPI(title="ReaMicro API", version=runtime.API_VERSION)


def github_request(url: str) -> urllib.request.Request:
    config = load_config()
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "ReaMicro-API-Server",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if config.get("githubToken"):
        headers["Authorization"] = f"Bearer {config['githubToken']}"
    return urllib.request.Request(url, headers=headers)


def github_json(url: str) -> Any:
    with urllib.request.urlopen(github_request(url), timeout=30) as stream:
        return json.loads(stream.read().decode("utf-8"))


def release_timestamp(value: str) -> int:
    if not value:
        return 0
    return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)


def is_semantic_version(value: str) -> bool:
    core = value.strip().split("-", 1)[0]
    if not core:
        return False
    return all(part.isdigit() for part in core.split("."))


def release_version_name(tag: str, title: str) -> str:
    """从 tag 或 Release 标题里取出语义版本号。

    客户端按语义版本号比较新旧，无法解析的字符串会被当成 0.0.0 并永远判定"已是最新版本"。
    CI 的 tag 形如 ``ci-123-1``，但标题里带着真实版本号（``CI 2.0.0 #123``），
    因此 tag 不是语义版本号时回退到标题。
    """
    candidate = tag.strip().lstrip("vV").split("+")[0]
    if is_semantic_version(candidate):
        return candidate
    # 至少要带一个小数点，避免把标题里的构建号（#123）误当成版本号。
    match = re.search(r"\d+(?:\.\d+)+", title or "")
    if match:
        return match.group(0)
    return candidate


def sync_module_release() -> dict[str, Any] | None:
    config = load_config()
    repository = config["githubRepository"]
    if config.get("githubIncludePrerelease"):
        releases = github_json(f"https://api.github.com/repos/{repository}/releases?per_page=20")
        release = next((item for item in releases if not item.get("draft")), None)
    else:
        try:
            release = github_json(f"https://api.github.com/repos/{repository}/releases/latest")
        except urllib.error.HTTPError as error:
            # GitHub 的 /releases/latest 会跳过预发布。仓库里只有预发布 Release 时返回 404，
            # 这不是网络故障，直接提示需要在后台勾选"包含预发布 Release"。
            if error.code == 404:
                raise RuntimeError(
                    f"仓库 {repository} 没有正式 Release；如果只发布预发布版本，请在后台勾选“包含预发布 Release”"
                ) from error
            raise
    if not release:
        return None
    assets = [asset for asset in release.get("assets", []) if asset.get("name", "").lower().endswith(".apk")]
    if not assets:
        return None
    asset = sorted(
        assets,
        key=lambda item: (
            "unsigned" in item.get("name", "").lower(),
            "release" not in item.get("name", "").lower(),
        ),
    )[0]
    runtime.RELEASE_ROOT.mkdir(parents=True, exist_ok=True)
    apk_path = runtime.RELEASE_ROOT / "latest.apk"
    metadata_path = runtime.RELEASE_ROOT / "latest.json"
    build_time = release_timestamp(asset.get("updated_at") or release.get("published_at", ""))
    previous = {}
    if metadata_path.is_file():
        try:
            previous = json.loads(metadata_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            previous = {}
    if previous.get("assetId") != asset.get("id") or previous.get("buildTime") != build_time or not apk_path.is_file():
        temp = runtime.RELEASE_ROOT / ".latest.apk.tmp"
        with urllib.request.urlopen(github_request(asset["browser_download_url"]), timeout=120) as source, temp.open("wb") as target:
            shutil.copyfileobj(source, target)
        temp.replace(apk_path)
    sha256 = hashlib.sha256(apk_path.read_bytes()).hexdigest()
    tag = str(release.get("tag_name") or release.get("name") or "").strip()
    version_name = release_version_name(tag, str(release.get("name") or ""))
    metadata = {
        "versionName": version_name,
        "versionCode": int(config.get("releaseVersionCode", 0)),
        "buildTime": build_time,
        "apkUrl": "/v1/releases/module/download",
        "sha256": sha256,
        "signature": "",
        "changelog": release.get("body", ""),
        "releaseUrl": release.get("html_url", ""),
        "assetId": asset.get("id"),
        "assetName": asset.get("name", "latest.apk"),
        "channel": "beta" if release.get("prerelease") else "stable",
        "etag": hashlib.sha256(apk_path.read_bytes()).hexdigest(),
    }
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    runtime.RELEASE_STATUS_PATH.parent.mkdir(parents=True, exist_ok=True)
    runtime.RELEASE_STATUS_PATH.write_text(json.dumps({"status": "ok", "syncedAt": int(datetime.now(timezone.utc).timestamp() * 1000), "versionName": version_name, "assetId": asset.get("id")}, ensure_ascii=False, indent=2), encoding="utf-8")
    return metadata


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


@app.on_event("startup")
async def start_release_sync() -> None:
    runtime.get_state_store()
    # 认证模式变更过的服务器会留下多套归属写法，先折叠再启动调度，避免任务按旧归属重复执行。
    canonicalize_owner_identities()
    if runtime.RUN_RELEASE_SYNC:
        asyncio.create_task(release_sync_loop())
    if runtime.RUN_SCHEDULER:
        recover_interrupted_tasks()
        asyncio.create_task(task_scheduler_loop())
        asyncio.create_task(server_snapshot_loop())












def configured_api_key(config: dict[str, Any], value: str | None) -> dict[str, Any] | None:
    if not value:
        return None
    for record in config.get("apiKeyRecords", []):
        if isinstance(record, dict) and record.get("enabled", True) and hmac.compare_digest(str(record.get("digest", "")), api_key_digest(value)):
            record["lastUsedAt"] = int(datetime.now(timezone.utc).timestamp() * 1000)
            return record
    records = config.get("apiKeyRecords", [])
    if isinstance(records, list) and records:
        return None
    legacy = str(config.get("apiKey", ""))
    if legacy and hmac.compare_digest(value, legacy):
        return {"id": "legacy", "name": "legacy", "permissions": ["read", "write"], "enabled": True}
    return None


def api_key_permissions(config: dict[str, Any], value: str | None) -> set[str]:
    record = configured_api_key(config, value)
    if not record:
        return set()
    permissions = record.get("permissions", [])
    if not isinstance(permissions, list) or not permissions:
        return {"read", "write", "packages:read", "packages:write", "tasks:read", "tasks:write", "credentials:read", "credentials:write", "backup:read"}
    result = {str(item).strip() for item in permissions if str(item).strip()}
    if "read" in result:
        result.update({"packages:read", "tasks:read", "credentials:read", "backup:read"})
    if "write" in result:
        result.update({"packages:write", "tasks:write", "credentials:write"})
    return result


def required_api_scope(request: Request) -> str | None:
    path = request.url.path
    if path.startswith("/v1/packages"):
        return "packages:write" if request.method not in {"GET", "HEAD"} else "packages:read"
    if path.startswith("/v1/tasks"):
        return "tasks:write" if request.method not in {"GET", "HEAD"} else "tasks:read"
    if path.startswith("/v1/notifications"):
        return "tasks:write" if request.method not in {"GET", "HEAD"} else "tasks:read"
    if path.startswith("/v1/presence"):
        return "tasks:write"
    if path.startswith("/v1/credentials"):
        return "credentials:write" if request.method not in {"GET", "HEAD"} else "credentials:read"
    if path.startswith("/v1/backups"):
        return "backup:write" if request.method not in {"GET", "HEAD"} else "backup:read"
    return None


def enforce_api_scope(request: Request, api_key_value: str | None) -> None:
    scope = required_api_scope(request)
    if not scope or not api_key_value:
        return
    permissions = api_key_permissions(load_config(), api_key_value)
    if scope not in permissions:
        raise HTTPException(status_code=403, detail=response(code="API_SCOPE_REQUIRED", message=f"当前 API Key 缺少权限：{scope}"))




def normalized_admin_name(value: str) -> str:
    name = value.strip()
    if not 3 <= len(name) <= 64 or not re.fullmatch(r"[A-Za-z0-9_.-]+", name):
        raise ValueError("管理员用户名需为 3-64 位字母、数字、点、下划线或短横线")
    return name


def validate_admin_password(value: str) -> str:
    if len(value) < 12:
        raise ValueError("管理员密码至少需要 12 位")
    return value


def primary_admin_configured(config: dict[str, Any]) -> bool:
    record = config.get("primaryAdmin", {})
    return isinstance(record, dict) and bool(record.get("username")) and bool(record.get("passwordHash"))


def resolve_admin(credentials: HTTPBasicCredentials | None, allow_bootstrap: bool = False) -> dict[str, Any]:
    config = load_config()
    if credentials and credentials.username.startswith("__session__:"):
        actor = validate_admin_session_token(credentials.username.split(":", 1)[1])
        if actor:
            return actor
        raise HTTPException(status_code=401, detail="后台会话已失效")
    if not primary_admin_configured(config):
        if not runtime.ADMIN_PASSWORD:
            raise HTTPException(status_code=503, detail="未配置主管理员初始密码 REAMICRO_ADMIN_PASSWORD")
        if credentials and hmac.compare_digest(credentials.username, runtime.ADMIN_USERNAME) and hmac.compare_digest(credentials.password, runtime.ADMIN_PASSWORD):
            if allow_bootstrap:
                return {"username": runtime.ADMIN_USERNAME, "role": "primary", "needsSetup": True}
            raise HTTPException(status_code=403, detail="主管理员首次登录必须先设置新账号密码")
    else:
        primary = config["primaryAdmin"]
        if credentials and hmac.compare_digest(credentials.username, str(primary.get("username", ""))) and password_matches(
            credentials.password,
            str(primary.get("passwordHash", "")),
        ):
            return {"username": credentials.username, "role": "primary", "needsSetup": False}

    if credentials:
        record = config.get("adminAccounts", {}).get(credentials.username)
        if isinstance(record, dict) and record.get("enabled", True) and password_matches(
            credentials.password,
            str(record.get("passwordHash", "")),
        ):
            permissions = record.get("permissions", ["settings:write", "packages:write", "tasks:write"])
            return {
                "username": credentials.username,
                "role": "subadmin",
                "needsSetup": False,
                "permissions": [str(item) for item in permissions] if isinstance(permissions, list) else [],
            }
    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="需要后台管理员认证",
        headers={"WWW-Authenticate": 'Basic realm="ReaMicro Admin"'},
    )


def require_admin(credentials: HTTPBasicCredentials | None, allow_bootstrap: bool = False) -> dict[str, Any]:
    return resolve_admin(credentials, allow_bootstrap=allow_bootstrap)


def admin_auth_version(config: dict[str, Any], actor: dict[str, Any]) -> str:
    username = str(actor.get("username", ""))
    if actor.get("needsSetup"):
        material = runtime.ADMIN_PASSWORD
    elif actor.get("role") == "primary":
        material = str(config.get("primaryAdmin", {}).get("passwordHash", ""))
    else:
        material = str(config.get("adminAccounts", {}).get(username, {}).get("passwordHash", ""))
    return hashlib.sha256(material.encode("utf-8")).hexdigest()[:24]


def create_admin_session(actor: dict[str, Any]) -> str:
    token = generate_long_secret(48)
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    runtime.get_state_store().save_admin_session(
        api_key_digest(token),
        actor,
        admin_auth_version(load_config(), actor),
        now,
        now + runtime.ADMIN_SESSION_SECONDS * 1000,
    )
    return token


def session_admin(request: Request) -> dict[str, Any] | None:
    token = request.cookies.get(runtime.ADMIN_SESSION_COOKIE, "")
    if not token:
        return None
    return validate_admin_session_token(token)


def validate_admin_session_token(token: str) -> dict[str, Any] | None:
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    actor = runtime.get_state_store().load_admin_session(api_key_digest(token), now)
    if not actor:
        return None
    config = load_config()
    if actor.get("role") == "subadmin":
        record = config.get("adminAccounts", {}).get(actor.get("username", ""), {})
        if not isinstance(record, dict) or not record.get("enabled", True):
            runtime.get_state_store().delete_admin_session(api_key_digest(token))
            return None
        actor["permissions"] = record.get("permissions", [])
    if not hmac.compare_digest(str(actor.get("authVersion", "")), admin_auth_version(config, actor)):
        runtime.get_state_store().delete_admin_session(api_key_digest(token))
        return None
    return actor


async def basic_security(request: Request) -> HTTPBasicCredentials | None:
    """会话优先，兼容旧的 HTTP Basic 登录。"""
    token = request.cookies.get(runtime.ADMIN_SESSION_COOKIE, "")
    if token and validate_admin_session_token(token):
        return HTTPBasicCredentials(username="__session__:" + token, password="")
    authorization = request.headers.get("Authorization", "")
    if not authorization.lower().startswith("basic "):
        return None
    try:
        raw = base64.b64decode(authorization[6:].strip()).decode("utf-8")
        username, password = raw.split(":", 1)
        return HTTPBasicCredentials(username=username, password=password)
    except (ValueError, UnicodeDecodeError, binascii.Error):
        return None


async def admin_actor(
    request: Request,
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
) -> dict[str, Any]:
    actor = session_admin(request)
    if actor:
        return actor
    if credentials:
        return require_admin(credentials, allow_bootstrap=True)
    raise HTTPException(status_code=303, detail="请先登录后台", headers={"Location": "/admin/login"})


def require_primary_admin(actor: dict[str, Any]) -> None:
    if actor.get("role") != "primary" or actor.get("needsSetup"):
        raise HTTPException(status_code=403, detail="只有主管理员可以管理子管理员")


def require_admin_permission(actor: dict[str, Any], permission: str) -> None:
    if actor.get("role") == "primary":
        return
    if permission not in set(actor.get("permissions", [])):
        raise HTTPException(status_code=403, detail=f"当前子管理员缺少权限：{permission}")


def admin_csrf_token(config: dict[str, Any], actor: dict[str, Any]) -> str:
    username = str(actor.get("username", ""))
    if actor.get("role") == "primary":
        encoded = str(config.get("primaryAdmin", {}).get("passwordHash", ""))
    else:
        encoded = str(config.get("adminAccounts", {}).get(username, {}).get("passwordHash", ""))
    material = (str(config.get("secretKey", "")) or runtime.SECRET_KEY or runtime.ADMIN_PASSWORD or encoded).encode("utf-8")
    return hmac.new(hashlib.sha256(material).digest(), f"admin-csrf:{username}:{encoded}".encode("utf-8"), hashlib.sha256).hexdigest()


def require_admin_csrf(config: dict[str, Any], actor: dict[str, Any], value: str) -> None:
    expected = admin_csrf_token(config, actor)
    if not value or not hmac.compare_digest(value, expected):
        audit_event("admin_csrf_rejected", audit_actor(actor), success=False)
        raise HTTPException(status_code=403, detail="后台请求校验失败，请刷新页面后重试")


def check_request_auth(
    api_key_value: str | None,
    account_name: str | None,
    account_password: str | None,
    host_account_id: str | None,
) -> None:
    config = load_config()
    mode = active_auth_mode(config)
    api_key = str(config.get("apiKey", ""))
    key_record = configured_api_key(config, api_key_value) if mode == "api_key" else None
    if key_record:
        if key_record.get("id") != "legacy":
            save_config(config)
        return
    accounts = config.get("accounts", {})
    if mode == "account" and account_name and account_password and isinstance(accounts, dict):
        encoded = accounts.get(account_name)
        if encoded and password_matches(account_password, str(encoded)):
            return
    allowlist = set(config.get("hostAccountAllowlist", []))
    if mode == "host_account_allowlist" and host_account_id and host_account_id in allowlist:
        return
    if mode == "public":
        return
    raise HTTPException(status_code=401, detail=response(code="AUTH_REQUIRED", message="认证信息无效"))


def resolve_identity(api_key_value: str | None, account_name: str | None, account_password: str | None, host_account_id: str | None) -> str:
    """把一次请求解析成稳定的数据归属标识。

    关键约定：只要请求带了阅微账号 ID，归属就固定是 `host:<id>`，与用哪种认证模式通过校验无关。
    认证模式只负责"能不能访问"，不参与"数据属于谁"——否则管理员在后台把认证模式从公开
    改成阅微白名单之后，同一台设备的归属会从 `host-public:3` 变成 `host:3`，
    已上传的同步密钥、已建立的任务和积压的消息全部失联，后台还会出现同一个账号两行在线记录。
    """
    config = load_config()
    mode = active_auth_mode(config)
    account_id = str(host_account_id or "").strip()
    authorized = False
    fallback = ""
    if mode == "api_key" and configured_api_key(config, api_key_value):
        authorized = True
        fallback = "key:" + hashlib.sha256(str(api_key_value).encode()).hexdigest()[:24]
    elif mode == "account" and account_name and account_password and password_matches(
        account_password,
        str(config.get("accounts", {}).get(account_name, "")),
    ):
        authorized = True
        fallback = "account:" + account_name
    elif mode == "host_account_allowlist" and account_id and account_id in set(config.get("hostAccountAllowlist", [])):
        authorized = True
    elif mode == "public":
        authorized = True
        fallback = "public"
    if not authorized:
        raise HTTPException(status_code=401, detail=response(code="AUTH_REQUIRED", message="认证信息无效"))
    return ("host:" + account_id) if account_id else fallback










async def authenticated(
    request: Request,
    x_reamicro_api_key: str | None = Header(default=None),
    x_reamicro_account: str | None = Header(default=None),
    x_reamicro_password: str | None = Header(default=None),
    x_reamicro_host_account_id: str | None = Header(default=None),
) -> None:
    check_request_auth(x_reamicro_api_key, x_reamicro_account, x_reamicro_password, x_reamicro_host_account_id)
    enforce_api_scope(request, x_reamicro_api_key)


async def task_owner(
    request: Request,
    x_reamicro_api_key: str | None = Header(default=None),
    x_reamicro_account: str | None = Header(default=None),
    x_reamicro_password: str | None = Header(default=None),
    x_reamicro_host_account_id: str | None = Header(default=None),
) -> str:
    identity = resolve_identity(x_reamicro_api_key, x_reamicro_account, x_reamicro_password, x_reamicro_host_account_id)
    enforce_api_scope(request, x_reamicro_api_key)
    if identity == "public":
        raise HTTPException(status_code=401, detail=response(code="AUTH_REQUIRED", message="云任务需要非公开认证"))
    return identity


def module_upload_policy(config: dict[str, Any], host_account_id: str | None) -> dict[str, Any]:
    """模块上传许可：需要后台启用上传功能，且当前阅微账号 ID 在上传白名单内。"""
    enabled = bool(config.get("moduleUploadEnabled", False))
    account_id = str(host_account_id or "").strip()
    allowlist = set(config.get("moduleUploadAllowlist", []))
    allowed = enabled and bool(account_id) and account_id in allowlist
    if not enabled:
        reason = "服务器未启用用户模块上传功能"
    elif not account_id:
        reason = "请求未携带阅微账号 ID，请先在阅微登录账号"
    elif not allowed:
        reason = f"阅微账号 {account_id} 不在上传白名单"
    else:
        reason = ""
    return {
        "enabled": enabled,
        "allowed": allowed,
        "reason": reason,
        "hostAccountId": account_id,
        "kinds": module_upload_kinds(config.get("moduleUploadKinds")),
        "maxSize": runtime.MODULE_UPLOAD_MAX_BYTES,
    }


async def module_upload_owner(
    request: Request,
    x_reamicro_api_key: str | None = Header(default=None),
    x_reamicro_account: str | None = Header(default=None),
    x_reamicro_password: str | None = Header(default=None),
    x_reamicro_host_account_id: str | None = Header(default=None),
) -> str:
    """模块上传的所有者标识。先走常规认证，再校验上传白名单。"""
    identity = resolve_identity(x_reamicro_api_key, x_reamicro_account, x_reamicro_password, x_reamicro_host_account_id)
    enforce_api_scope(request, x_reamicro_api_key)
    policy = module_upload_policy(load_config(), x_reamicro_host_account_id)
    if not policy["allowed"]:
        audit_event(
            "module_upload_denied",
            actor=identity,
            request_id=getattr(request.state, "request_id", ""),
            success=False,
            metadata={"hostAccountId": policy["hostAccountId"]},
        )
        raise HTTPException(status_code=403, detail=response(code="MODULE_UPLOAD_FORBIDDEN", message=policy["reason"], data=policy))
    return identity


async def backup_owner(
    request: Request,
    x_reamicro_api_key: str | None = Header(default=None),
    x_reamicro_account: str | None = Header(default=None),
    x_reamicro_password: str | None = Header(default=None),
    x_reamicro_host_account_id: str | None = Header(default=None),
) -> str:
    enforce_api_scope(request, x_reamicro_api_key)
    # 复用 resolve_identity 的规范归属，备份目录才不会在认证模式变更后失联。
    identity = resolve_identity(x_reamicro_api_key, x_reamicro_account, x_reamicro_password, x_reamicro_host_account_id)
    if identity == "public":
        raise HTTPException(status_code=401, detail=response(code="AUTH_REQUIRED", message="备份功能需要非公开认证"))
    return backup_owner_dir_name(identity)




@app.middleware("http")
async def security_middleware(request: Request, call_next):
    request_id = request.headers.get("X-Request-Id", "").strip()[:80] or "req_" + secrets.token_hex(8)
    request.state.request_id = request_id
    client_host = request.client.host if request.client else "unknown"
    rate_key = f"{client_host}:{request.url.path}"
    if not allow_rate_limit(rate_key):
        with runtime.metrics_lock:
            runtime.metrics_counters["rate_limited"] += 1
        audit_event("rate_limit", actor=client_host, request_id=request_id, success=False, metadata={"path": request.url.path})
        return JSONResponse(
            status_code=429,
            content=response(code="RATE_LIMITED", message="请求过于频繁，请稍后重试", request_id=request_id),
            headers={"Retry-After": str(runtime.RATE_WINDOW_SECONDS), "X-Request-Id": request_id},
        )
    try:
        result = await call_next(request)
    except Exception:
        with runtime.metrics_lock:
            runtime.metrics_counters["errors"] += 1
        audit_event("request_error", actor=client_host, request_id=request_id, success=False, metadata={"path": request.url.path})
        raise
    result.headers["X-Request-Id"] = request_id
    result.headers["X-ReaMicro-API-Version"] = runtime.API_VERSION
    # 后台页面包含账号、凭据状态和 CSRF，禁止浏览器及反向代理复用旧 HTML。
    if request.url.path == "/admin" or request.url.path.startswith("/admin/"):
        result.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
        result.headers["Pragma"] = "no-cache"
        result.headers["Expires"] = "0"
    with runtime.metrics_lock:
        runtime.metrics_counters["requests"] += 1
        runtime.metrics_routes[request.url.path] = runtime.metrics_routes.get(request.url.path, 0) + 1
    return result


def wants_admin_html(request: Request) -> bool:
    """判断是否应该给后台浏览器返回 HTML 错误页而不是 JSON。"""
    path = request.url.path
    if path != "/admin" and not path.startswith("/admin/"):
        return False
    return "text/html" in request.headers.get("Accept", "").lower()


ADMIN_ERROR_HINTS = {
    400: "请求参数不正确，请返回上一页检查填写内容。",
    401: "后台会话已失效，请重新登录。",
    403: "当前账号没有执行该操作的权限。如果是子管理员，请联系主管理员分配对应权限。",
    404: "要操作的对象不存在，可能已被删除或改名。",
    409: "当前状态不允许该操作，请先处理提示中的前置条件。",
    413: "上传内容超过服务器允许的大小上限。",
    429: "操作过于频繁，请稍后重试。",
    500: "服务器内部错误，可凭请求 ID 在审计日志中定位。",
    502: "访问外部服务失败，请检查网络或令牌配置。",
    503: "服务尚未就绪，请稍后重试。",
}


def admin_error_page(status_code: int, message: str, request_id: str = "") -> str:
    """后台错误提示页。此前后台任何异常都直接返回裸 JSON，浏览器里无法阅读。"""
    esc = lambda value: html.escape(str(value), quote=True)
    hint = ADMIN_ERROR_HINTS.get(status_code, "操作没有完成。")
    request_note = f"<small>请求 ID：{esc(request_id)}</small>" if request_id else ""
    login_button = "<a class='button' href='/admin/login'>重新登录</a>" if status_code == 401 else ""
    body = (
        f"<p class='msg'>{esc(message or hint)}</p>"
        f"<p class='muted' style='margin:0 0 18px'>{esc(hint)}</p>"
        f"<div class='inline' style='gap:8px'>{login_button}"
        f"<a class='button' href='/admin' style='background:#eef2f7;color:#334155'>返回后台首页</a></div>"
        f"{request_note}"
    )
    return _admin_auth_shell(f"操作未完成（HTTP {status_code}）", "后台操作被服务器拒绝。", body)


@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException) -> Response:
    request_id = getattr(request.state, "request_id", "")
    if exc.status_code in (302, 303, 307, 308) and exc.headers and exc.headers.get("Location"):
        result = RedirectResponse(exc.headers["Location"], status_code=exc.status_code)
        if request_id:
            result.headers["X-Request-Id"] = request_id
        return result
    detail = exc.detail if isinstance(exc.detail, dict) else response(code="HTTP_ERROR", message=str(exc.detail), request_id=request_id)
    if isinstance(detail, dict):
        detail["requestId"] = request_id or detail.get("requestId", "")
    if wants_admin_html(request):
        # 后台页面用浏览器访问，错误要以可读页面呈现；WWW-Authenticate 之类的头继续保留。
        message = detail.get("message", "") if isinstance(detail, dict) else str(exc.detail)
        return HTMLResponse(
            admin_error_page(exc.status_code, message, request_id),
            status_code=exc.status_code,
            headers={**(exc.headers or {}), "X-Request-Id": request_id, "Cache-Control": "no-store"},
        )
    return JSONResponse(status_code=exc.status_code, content=detail, headers={**(exc.headers or {}), "X-Request-Id": request_id})


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> Response:
    request_id = getattr(request.state, "request_id", "") or "req_" + secrets.token_hex(8)
    client_host = request.client.host if request.client else "unknown"
    with runtime.metrics_lock:
        runtime.metrics_counters["errors"] += 1
    audit_event(
        "unhandled_exception",
        actor=client_host,
        request_id=request_id,
        success=False,
        metadata={"path": request.url.path, "type": type(exc).__name__},
    )
    if wants_admin_html(request):
        return HTMLResponse(
            admin_error_page(500, "服务器内部错误，请凭请求 ID 在审计日志中查询。", request_id),
            status_code=500,
            headers={"X-Request-Id": request_id, "Cache-Control": "no-store"},
        )
    return JSONResponse(
        status_code=500,
        content=response(code="INTERNAL_ERROR", message="服务器内部错误，请使用 requestId 查询日志", request_id=request_id),
        headers={"X-Request-Id": request_id},
    )


@app.get("/v1/health")
async def health() -> dict[str, Any]:
    """公开健康检查；认证状态和详细依赖信息分别由 /v1/meta 与 /health/dependencies 提供。"""
    return response({"status": "ok", "serverId": load_config()["serverId"]})


@app.get("/health/live")
async def health_live() -> dict[str, Any]:
    return response({"status": "alive"})


@app.get("/health/ready")
async def health_ready() -> JSONResponse:
    database = runtime.get_state_store().integrity_check()
    ready = database == "ok" and runtime.CONFIG_ROOT.parent.exists()
    payload = response({"status": "ready" if ready else "not_ready", "database": database})
    return JSONResponse(status_code=200 if ready else 503, content=payload)


@app.get("/health/dependencies")
async def health_dependencies(_: None = Depends(authenticated)) -> dict[str, Any]:
    sync_status = {}
    if runtime.RELEASE_STATUS_PATH.is_file():
        try: sync_status = json.loads(runtime.RELEASE_STATUS_PATH.read_text(encoding="utf-8"))
        except (OSError, ValueError): sync_status = {"status": "invalid"}
    return response({
        "database": runtime.get_state_store().integrity_check(),
        "releaseCache": (runtime.RELEASE_ROOT / "latest.json").is_file(),
        "releaseSync": sync_status,
        "packageStorage": runtime.PACKAGE_ROOT.exists(),
        "backupStorage": runtime.BACKUP_ROOT.exists(),
    })


@app.get("/v1/diagnostics")
async def diagnostics(_: None = Depends(authenticated)) -> dict[str, Any]:
    config = load_config()
    database = runtime.get_state_store().integrity_check()
    package_count = sum(1 for kind in runtime.PACKAGE_KINDS for _ in (runtime.PACKAGE_ROOT / kind).glob("*/manifest.json"))
    snapshot_count = len(list(runtime.SERVER_BACKUP_ROOT.glob("reamicro-server-*.zip"))) if runtime.SERVER_BACKUP_ROOT.exists() else 0
    release_status = {}
    if runtime.RELEASE_STATUS_PATH.is_file():
        try: release_status = json.loads(runtime.RELEASE_STATUS_PATH.read_text(encoding="utf-8"))
        except (OSError, ValueError): release_status = {"status": "invalid"}
    return response({
        "serverId": config.get("serverId", ""),
        "database": database,
        "packages": package_count,
        "credentials": len(load_credentials()),
        "tasks": len(load_tasks()),
        "snapshots": snapshot_count,
        "releaseSync": release_status,
        "storage": {"config": runtime.CONFIG_ROOT.exists(), "packages": runtime.PACKAGE_ROOT.exists(), "backups": runtime.BACKUP_ROOT.exists()},
    })


@app.get("/metrics")
async def metrics(_: None = Depends(authenticated)) -> Response:
    with runtime.metrics_lock:
        counters = dict(runtime.metrics_counters)
        routes = dict(runtime.metrics_routes)
    lines = [
        "# HELP reamicro_http_requests_total Total HTTP responses",
        "# TYPE reamicro_http_requests_total counter",
        f"reamicro_http_requests_total {counters['requests']}",
        "# HELP reamicro_http_errors_total Total unhandled HTTP errors",
        "# TYPE reamicro_http_errors_total counter",
        f"reamicro_http_errors_total {counters['errors']}",
        "# HELP reamicro_http_rate_limited_total Total rate-limited requests",
        "# TYPE reamicro_http_rate_limited_total counter",
        f"reamicro_http_rate_limited_total {counters['rate_limited']}",
        "# HELP reamicro_credentials Current credential count",
        "# TYPE reamicro_credentials gauge",
        f"reamicro_credentials {len(load_credentials())}",
        "# HELP reamicro_tasks Current task count",
        "# TYPE reamicro_tasks gauge",
        f"reamicro_tasks {len(load_tasks())}",
        "# HELP reamicro_tasks_failed Current failed task count",
        "# TYPE reamicro_tasks_failed gauge",
        f"reamicro_tasks_failed {sum(1 for task in load_tasks().values() if task.get('status') == 'failed')}",
        "# HELP reamicro_packages Current package count",
        "# TYPE reamicro_packages gauge",
        f"reamicro_packages {sum(1 for kind in runtime.PACKAGE_KINDS for _ in (runtime.PACKAGE_ROOT / kind).glob('*/manifest.json'))}",
    ]
    for route, count in sorted(routes.items()):
        safe_route = route.replace('"', '\\"')
        lines.append(f'reamicro_http_route_requests_total{{route="{safe_route}"}} {count}')
    return Response("\n".join(lines) + "\n", media_type="text/plain; version=0.0.4")


def _admin_auth_shell(title: str, subtitle: str, body: str) -> str:
    """登录与初始化页的外壳。没有侧栏，但配色和控件与后台一致。"""
    esc = lambda value: html.escape(str(value), quote=True)
    return (
        f"<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'>"
        f"<meta name='viewport' content='width=device-width,initial-scale=1'><title>{esc(title)}</title>"
        f"<style>{ADMIN_AUTH_STYLE}</style></head><body><main>"
        f"<p class='brand'>ReaMicro API 管理后台</p><h1>{esc(title)}</h1>"
        f"<p class='muted'>{esc(subtitle)}</p>{body}</main></body></html>"
    )


def admin_setup_page(message: str = "", actor: dict[str, Any] | None = None) -> str:
    message_html = f"<p class='msg'>{html.escape(message)}</p>" if message else ""
    actor = actor or {"username": runtime.ADMIN_USERNAME, "role": "primary", "needsSetup": True}
    csrf_value = html.escape(admin_csrf_token(load_config(), actor), quote=True)
    body = (
        f"{message_html}"
        f"<form method='post' action='/admin/setup'>"
        f"<input type='hidden' name='csrf_token' value='{csrf_value}'>"
        f"<label>主管理员用户名<input name='username' value='{html.escape(runtime.ADMIN_USERNAME, quote=True)}' autocomplete='username' required></label>"
        f"<label>新密码（至少 12 位）<input type='password' name='password' minlength='12' autocomplete='new-password' required></label>"
        f"<label>确认新密码<input type='password' name='password_confirm' minlength='12' autocomplete='new-password' required></label>"
        f"<button type='submit'>完成初始化</button></form>"
        f"<small>初始化信息保存于 /data/config/server.json，密码只保存 PBKDF2 哈希。完成后环境变量中的引导密码立即失效。</small>"
    )
    return _admin_auth_shell(
        "首次设置主管理员",
        "环境变量中的账号仅用于首次引导，完成后改用数据卷内保存的加盐密码哈希。",
        body,
    )


def admin_login_page(message: str = "") -> str:
    message_html = f"<p class='msg'>{html.escape(message)}</p>" if message else ""
    body = (
        f"{message_html}"
        f"<form method='post' action='/admin/login'>"
        f"<label>管理员用户名<input name='username' autocomplete='username' required></label>"
        f"<label>密码<input type='password' name='password' autocomplete='current-password' required></label>"
        f"<button type='submit'>登录</button></form>"
        f"<small>主管理员与子管理员使用同一个登录入口。会话通过 HttpOnly Cookie 保存。</small>"
    )
    return _admin_auth_shell("登录", "请输入后台管理员账号。", body)


def _admin_package_records() -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for kind in sorted(runtime.PACKAGE_KINDS):
        for manifest_path in sorted((runtime.PACKAGE_ROOT / kind).glob("*/manifest.json")):
            try:
                item = json.loads(manifest_path.read_text(encoding="utf-8"))
                if not isinstance(item, dict):
                    continue
                item.setdefault("kind", kind)
                item.setdefault("packageId", manifest_path.parent.name)
                item.update(package_dependency_status(item))
                records.append(item)
            except (OSError, ValueError, HTTPException):
                continue
    return records












BEIJING_TZ = timezone(timedelta(hours=8))


def _admin_datetime(value: Any) -> datetime | None:
    """把毫秒/秒时间戳或 ISO 字符串统一解析为北京时间，无法解析时返回 None。"""
    if value is None or value == "":
        return None
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return None
        if not re.fullmatch(r"-?\d+(\.\d+)?", text):
            try:
                parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
            except ValueError:
                return None
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            return parsed.astimezone(BEIJING_TZ)
        value = text
    try:
        timestamp = float(value)
    except (TypeError, ValueError):
        return None
    if timestamp <= 0:
        return None
    if timestamp > 10_000_000_000:
        timestamp /= 1000
    try:
        return datetime.fromtimestamp(timestamp, BEIJING_TZ)
    except (OverflowError, OSError, ValueError):
        return None


def _admin_time_label(value: Any, empty: str = "未安排") -> str:
    parsed = _admin_datetime(value)
    if parsed is None:
        return str(value) if value else empty
    return parsed.strftime("%Y-%m-%d %H:%M")


def _admin_time_detail(value: Any, empty: str = "未安排") -> str:
    """完整时间 + 相对时间，用于表格里的次要说明行。"""
    parsed = _admin_datetime(value)
    if parsed is None:
        return empty
    return f"{parsed.strftime('%Y-%m-%d %H:%M:%S')}（{_admin_relative_label(parsed)}）"


def _admin_relative_label(moment: datetime) -> str:
    delta = datetime.now(BEIJING_TZ) - moment
    seconds = int(delta.total_seconds())
    future = seconds < 0
    seconds = abs(seconds)
    if seconds < 60:
        text = "刚刚" if not future else "即将"
        return text
    for limit, divisor, unit in ((3600, 60, "分钟"), (86400, 3600, "小时"), (2592000, 86400, "天")):
        if seconds < limit:
            return f"{seconds // divisor} {unit}{'后' if future else '前'}"
    return f"{seconds // 2592000} 个月{'后' if future else '前'}"


def _admin_duration_label(value: Any) -> str:
    """毫秒耗时转成可读时长。"""
    try:
        millis = int(float(value))
    except (TypeError, ValueError):
        return "未记录"
    if millis <= 0:
        return "不足 1 毫秒"
    if millis < 1000:
        return f"{millis} 毫秒"
    if millis < 60_000:
        return f"{millis / 1000:.1f} 秒"
    minutes, seconds = divmod(millis // 1000, 60)
    if minutes < 60:
        return f"{minutes} 分 {seconds} 秒"
    hours, minutes = divmod(minutes, 60)
    return f"{hours} 小时 {minutes} 分"


def _admin_size_label(value: Any) -> str:
    """字节数转成人类可读大小。"""
    try:
        size = float(value)
    except (TypeError, ValueError):
        return "未知大小"
    if size < 0:
        return "未知大小"
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            return f"{int(size)} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} GB"








def _admin_digest_label(value: Any) -> str:
    """摘要只显示前 12 位，完整值放进 title 供悬停查看。"""
    text = str(value or "")
    if not text:
        return "无摘要"
    return text[:12]








def admin_section_path(section: str) -> str:
    if section in runtime.PACKAGE_KINDS:
        return f"/admin/content/{section}"
    return {
        "overview": "/admin",
        "packages": "/admin/content",
        "tasks": "/admin/tasks",
        "settings": "/admin/settings",
        "security": "/admin/security",
    }.get(section, "/admin")


def _admin_task_detail(task: dict[str, Any]) -> str:
    """将任务内部配置转换为后台可读的简短说明。"""
    task_type = str(task.get("taskType", ""))
    schedule = task.get("schedule") if isinstance(task.get("schedule"), dict) else {}
    time_of_day = str(schedule.get("timeOfDay", "")).strip()
    parts: list[str] = []
    if time_of_day:
        parts.append(f"执行时间：每日 {time_of_day}")
    try:
        request = decrypt_secret(str(task.get("requestEncrypted", ""))) if task.get("requestEncrypted") else {}
    except Exception:
        request = {}
    if not isinstance(request, dict):
        request = {}
    if task_type == "yeshe_checkin":
        parts.append("奖励领取：签到后 8 小时（周奖励，每周一次）")
        if task.get("lastCheckinAt"):
            parts.append(f"上次签到：{_admin_time_label(task.get('lastCheckinAt'))}")
        if task.get("lastClaimAt"):
            parts.append(f"上次领取：{_admin_time_label(task.get('lastClaimAt'))}")
        claimed_date = str(task.get("claimCompletedDate", ""))
        if claimed_date:
            parts.append(f"奖励领取日期：{claimed_date}")
        retry_count = bounded_config_int(task.get("claimRetryCount", 0), 0, 0)
        if retry_count:
            parts.append(f"领取重试：已重试 {retry_count} 次")
    elif task_type == "yeshe_draw_card":
        parts.append(f"每日抽卡：{int(request.get('dailyLimit', 1) or 1)} 次")
    elif task_type == "cloud_auto_read":
        parts.append(f"阅读时长：{int(request.get('durationMinutes', 30) or 30)} 分钟")
        books = request.get("books") if isinstance(request.get("books"), list) else []
        if books:
            names = [str(item.get("name") or item.get("bookName") or item.get("title") or item.get("bookId") or "未命名") for item in books if isinstance(item, dict)]
            parts.append("阅读图书：" + "、".join(names[:3]) + (" 等" if len(names) > 3 else ""))
        else:
            parts.append(f"阅读图书：最近阅读记录（{int(request.get('recentLimit', 1) or 1)} 本）")
    elif task_type == "http":
        parts.append(f"请求：{str(request.get('method', 'GET')).upper()} {str(request.get('url', ''))[:80]}")
    return "；".join(parts) or "未配置详细参数"


def _admin_package_table(records: list[dict[str, Any]], can_write: bool, query: str = "", csrf_token: str = "") -> str:
    esc = lambda value: html.escape(str(value), quote=True)
    needle = query.strip().lower()
    rows: list[str] = []
    for item in sorted(records, key=lambda value: (str(value.get("kind", "")), str(value.get("packageId", "")))):
        haystack = " ".join(str(item.get(key, "")) for key in ("kind", "packageId", "contentId", "name", "version", "aliases")).lower()
        if needle and needle not in haystack:
            continue
        kind = str(item.get("kind", "")); package_id = str(item.get("packageId", ""))
        dependency = "依赖满足" if item.get("dependenciesSatisfied", True) else "缺少必需依赖"
        status_value = str(item.get("status", "published"))
        source = _admin_owner_label(item.get("uploadOwner", "")) if item.get("uploadOwner") else "后台上传"
        domains = "、".join(sorted(package_match_domains(item)))
        if can_write:
            actions = (
                f"<a class='button subtle' href='/admin/packages/{esc(kind)}/{esc(package_id)}/edit'>编辑</a> "
                f"<a class='button subtle' href='/admin/packages/{esc(kind)}/{esc(package_id)}/preview'>预览</a> "
                f"<a class='button subtle' href='/admin/packages/{esc(kind)}/{esc(package_id)}/history'>历史</a> "
                f"<form class='inline' method='post' action='/admin/packages/{esc(kind)}/{esc(package_id)}/delete'>"
                f"<input type='hidden' name='csrf_token' value='{esc(csrf_token)}'>"
                f"<button class='button subtle danger' type='submit'>删除</button></form>"
            )
        else:
            actions = f"<a class='button subtle' href='/admin/packages/{esc(kind)}/{esc(package_id)}/preview'>预览</a>"
        rows.append(
            f"<tr><td>{esc(_admin_kind_label(kind))}<small>{esc(source)}</small></td>"
            f"<td><strong>{esc(item.get('name', package_id))}</strong>"
            f"<small>包 ID {esc(package_id)}{(' · ' + esc(domains)) if domains else ''}</small></td>"
            f"<td>{esc(item.get('version', ''))}<small>{esc(_admin_time_label(item.get('buildTime'), '未记录'))}</small></td>"
            f"<td><span class='status status-{esc(status_value)}'>{esc(_admin_status_label(status_value))}</span></td>"
            f"<td>{esc(_admin_channel_label(item.get('channel', 'stable')))}<small>{esc(dependency)}</small></td>"
            f"<td class='actions'>{actions}</td></tr>"
        )
    return "".join(rows) or "<tr><td colspan='6' class='empty'>没有匹配的内容包</td></tr>"


ADMIN_NAV_LABELS = [
    ("overview", "概览"),
    ("packages", "全部内容"),
    ("online_source", "书源"),
    ("association_source", "关联源"),
    ("epub_style", "EPUB 样式"),
    ("highlight_style", "高亮样式"),
    ("theme", "主题库"),
    ("tasks", "云端任务"),
    ("settings", "服务器设置"),
    ("security", "子管理员与安全"),
]

# 后台全站共用一套样式，子页面（编辑、预览、历史、差异、日志、审计）与主页面外观保持一致。
ADMIN_STYLE = """:root{--line:#e5e9f0;--muted:#64748b;--blue:#2563eb;--ink:#1f2937}*{box-sizing:border-box}body{margin:0;background:#f4f7fb;color:var(--ink);font-family:system-ui,-apple-system,'Microsoft YaHei',sans-serif;line-height:1.5}aside{position:fixed;inset:0 auto 0 0;width:236px;padding:22px 14px;background:#f8fafc;border-right:1px solid var(--line);overflow-y:auto}.brand{font-size:18px;font-weight:700;padding:0 12px 22px}.brand small{display:block;color:var(--muted);font-size:11px;margin-top:5px}nav a{display:flex;justify-content:space-between;align-items:center;padding:10px 12px;margin:3px 0;border-radius:6px;color:#475569;text-decoration:none;font-size:14px}nav a.active,nav a:hover{background:#e8f0ff;color:#1d4ed8;font-weight:650}nav span{color:#94a3b8;font-size:11px}main{margin-left:236px;max-width:1480px;padding:30px 38px 56px}.topbar{display:flex;justify-content:flex-end;align-items:center;gap:15px;color:var(--muted);font-size:13px;margin-bottom:24px}.page-head{display:flex;justify-content:space-between;align-items:flex-start;gap:20px;margin-bottom:20px}h1{margin:3px 0 5px;font-size:26px;line-height:1.25}h2{margin:0 0 16px;font-size:19px;line-height:1.3}h3{margin:18px 0 10px;font-size:15px}.eyebrow{color:var(--blue);font-size:12px;font-weight:700;margin:0}.muted,small{color:var(--muted)}.stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin-bottom:20px}.stats div,.table-wrap,.panel{background:#fff;border:1px solid var(--line);border-radius:8px}.stats div{padding:18px}.stats strong{display:block;font-size:24px}.stats span{color:var(--muted);font-size:13px}.toolbar{display:flex;gap:8px;align-items:center;margin-bottom:12px}input,textarea,select{width:100%;padding:9px 10px;border:1px solid #cfd6e1;border-radius:5px;background:#fff;color:var(--ink);font:inherit}textarea{min-height:110px;resize:vertical;font-family:ui-monospace,'Cascadia Mono',Consolas,monospace}label{display:flex;flex-direction:column;gap:6px;min-width:0;font-size:13px;font-weight:600;color:#334155}label:has(input[type=checkbox]){display:flex;flex-direction:row;align-items:center;gap:8px}input[type=checkbox]{width:auto}.grid-2{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;margin-bottom:14px}.panel form{display:flex;flex-direction:column;gap:14px}.panel form.inline{flex-direction:row;gap:6px;align-items:center;display:inline-flex;margin:0}.panel form .button{align-self:flex-start}.toolbar input{width:min(420px,100%)}.button{display:inline-flex;align-items:center;justify-content:center;min-height:38px;padding:9px 14px;border:0;border-radius:5px;background:var(--blue);color:#fff;text-decoration:none;font:inherit;font-weight:650;cursor:pointer;white-space:nowrap}.button.subtle{background:#eef2f7;color:#334155;padding:6px 9px;min-height:32px;font-size:12px}.button.danger{background:#fef2f2;color:#b91c1c}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;min-width:760px}th,td{padding:13px 14px;border-bottom:1px solid var(--line);text-align:left;font-size:13px;vertical-align:top}th{background:#fafbfc;color:var(--muted);font-weight:650;white-space:nowrap}td small{display:block;margin-top:4px;font-size:11px}.status{display:inline-block;padding:3px 7px;border-radius:4px;font-size:11px;background:#eef2f7}.status-published,.status-valid,.status-success,.status-online{background:#ecfdf3;color:#047857}.status-draft,.status-testing,.status-warning,.status-unverified{background:#fff7ed;color:#b45309}.status-invalid,.status-failed{background:#fef2f2;color:#b91c1c}.status-offline,.status-paused,.status-cancelled,.status-unpublished{background:#f1f5f9;color:#475569}.actions{white-space:nowrap}.notice,.secret,.panel{padding:18px;margin-bottom:18px}.notice{background:#ecfdf5;color:#166534}.secret{background:#fff7ed;color:#9a3412;white-space:pre-wrap}.inline{display:inline-flex;gap:6px;align-items:center}.empty{padding:28px;text-align:center;color:var(--muted)}pre{white-space:pre-wrap;overflow:auto;background:#0f172a;color:#e2e8f0;border-radius:6px;padding:16px;line-height:1.55;font-size:12.5px}code{font-family:ui-monospace,'Cascadia Mono',Consolas,monospace;font-size:12px}fieldset{border:1px solid var(--line);border-radius:6px;padding:14px;margin:0;display:flex;flex-direction:column;gap:12px}legend{padding:0 6px;font-size:13px;font-weight:650;color:#334155}details summary{cursor:pointer;font-size:13px;font-weight:650;color:#334155}@media(max-width:900px){aside{width:200px}main{margin-left:200px;padding:24px 20px}.stats{grid-template-columns:repeat(2,minmax(0,1fr))}.grid-2{grid-template-columns:1fr}}@media(max-width:620px){aside{position:static;width:auto;border-right:0;border-bottom:1px solid var(--line)}main{margin-left:0;padding:20px 14px}nav{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:2px}.topbar{justify-content:flex-start;flex-wrap:wrap}.stats{grid-template-columns:1fr 1fr}.toolbar{align-items:stretch;flex-direction:column}.toolbar input{width:100%}.page-head{display:block}}"""

# 登录和初始化页没有侧栏，但配色、控件和圆角与后台一致。
ADMIN_AUTH_STYLE = """:root{--line:#e5e9f0;--muted:#64748b;--blue:#2563eb;--ink:#1f2937}*{box-sizing:border-box}body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px;background:#f4f7fb;color:var(--ink);font-family:system-ui,-apple-system,'Microsoft YaHei',sans-serif;line-height:1.5}main{width:100%;max-width:460px;background:#fff;border:1px solid var(--line);border-radius:10px;padding:28px}.brand{color:var(--blue);font-size:12px;font-weight:700;margin:0}h1{margin:4px 0 6px;font-size:23px}p.muted{color:var(--muted);font-size:13px;margin:0 0 18px}form{display:flex;flex-direction:column;gap:14px}label{display:flex;flex-direction:column;gap:6px;font-size:13px;font-weight:600;color:#334155}input{width:100%;padding:10px;border:1px solid #cfd6e1;border-radius:5px;font:inherit}button{min-height:42px;border:0;border-radius:5px;background:var(--blue);color:#fff;font:inherit;font-weight:650;cursor:pointer}.msg{padding:11px 13px;border-radius:6px;background:#fef2f2;color:#b91c1c;font-size:13px;margin:0 0 16px}small{display:block;color:var(--muted);font-size:12px;margin-top:16px}"""


def _admin_layout(
    config: dict[str, Any],
    actor: dict[str, Any],
    section: str,
    content: str,
    message: str = "",
    secret_notice: str = "",
    records: list[dict[str, Any]] | None = None,
    title: str = "ReaMicro API 管理后台",
) -> str:
    """后台统一外壳：侧栏、顶栏、提示区加内容。所有后台页面都经过这里。"""
    esc = lambda value: html.escape(str(value), quote=True)
    counts = records if records is not None else _admin_package_records()
    nav_html = "".join(
        "<a class=\"{active}\" href=\"{href}\">{label}<span>{count}</span></a>".format(
            active="active" if key == section else "",
            href=admin_section_path(key),
            label=label,
            count=len([item for item in counts if item.get("kind") == key]) if key in runtime.PACKAGE_KINDS else "",
        )
        for key, label in ADMIN_NAV_LABELS
    )
    csrf_html = f"<input type='hidden' name='csrf_token' value='{esc(admin_csrf_token(config, actor))}'>"
    notice = f"<div class='notice'>{esc(message)}</div>" if message else ""
    secret = f"<div class='secret'><strong>请立即保存，离开本页后无法再次查看：</strong>\n{esc(secret_notice)}</div>" if secret_notice else ""
    role_label = "主管理员" if actor.get("role") == "primary" else "子管理员"
    version_line = f"<p class='muted' style='margin:0 0 12px;text-align:right;font-size:12px'>API v{runtime.API_VERSION} · 时间均为北京时间</p>"
    return (
        f"<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'>"
        f"<meta name='viewport' content='width=device-width,initial-scale=1'><title>{esc(title)}</title>"
        f"<style>{ADMIN_STYLE}</style></head><body>"
        f"<aside><div class='brand'>ReaMicro<small>API 管理后台 · 5222</small></div><nav>{nav_html}</nav></aside>"
        f"<main><div class='topbar'><span>管理员：{esc(actor.get('username', ''))}</span><span>{role_label}</span>"
        f"<form class='inline' method='post' action='/admin/logout'>{csrf_html}"
        f"<button class='button subtle' type='submit'>退出</button></form></div>"
        f"{notice}{secret}{version_line}{content}</main></body></html>"
    )


ADMIN_ASSIGNABLE_PERMISSIONS = [
    ("settings:write", "修改服务器设置"),
    ("packages:write", "管理内容包"),
    ("tasks:write", "管理云端任务"),
    ("module:sync", "同步模块 Release"),
    ("audit:read", "查看审计日志"),
    ("backup:admin", "创建和校验快照"),
    ("security:write", "轮换密钥等安全操作"),
]


def _admin_release_panel() -> str:
    """模块 Release 同步状态。此前后台看不到同步结果和失败原因。"""
    esc = lambda value: html.escape(str(value), quote=True)
    metadata: dict[str, Any] = {}
    release_path = runtime.RELEASE_ROOT / "latest.json"
    if release_path.is_file():
        try:
            loaded = json.loads(release_path.read_text(encoding="utf-8"))
            metadata = loaded if isinstance(loaded, dict) else {}
        except (OSError, ValueError):
            metadata = {}
    sync_status: dict[str, Any] = {}
    if runtime.RELEASE_STATUS_PATH.is_file():
        try:
            loaded = json.loads(runtime.RELEASE_STATUS_PATH.read_text(encoding="utf-8"))
            sync_status = loaded if isinstance(loaded, dict) else {}
        except (OSError, ValueError):
            sync_status = {}
    apk_path = runtime.RELEASE_ROOT / "latest.apk"
    apk_note = _admin_size_label(apk_path.stat().st_size) if apk_path.is_file() else "尚未下载"
    success = sync_status.get("success")
    status_class = "success" if success else ("failed" if success is False else "unverified")
    status_text = "同步成功" if success else ("同步失败" if success is False else "尚未同步")
    rows = "".join(
        f"<tr><th style='width:170px'>{esc(label)}</th><td>{value}</td></tr>"
        for label, value in (
            ("当前版本", esc(metadata.get("versionName", "") or "未同步")),
            ("发布渠道", esc(_admin_channel_label(metadata.get("channel", "")) if metadata.get("channel") else "未同步")),
            ("发布时间", esc(_admin_time_label(metadata.get("publishedAt"), "未记录"))),
            ("APK 文件", esc(apk_note)),
            ("同步状态", f"<span class='status status-{status_class}'>{esc(status_text)}</span>"),
            ("最近同步", esc(_admin_time_detail(sync_status.get("at") or sync_status.get("checkedAt"), "未记录"))),
            ("同步说明", esc(sync_status.get("message", "") or "无附加说明")),
        )
    )
    return (
        f"<div class='panel'><h2>模块 Release 同步</h2>"
        f"<p class='muted'>服务器定时从 GitHub 拉取模块 Release 供客户端更新。"
        f"若仓库只发布预发布 Release，必须勾选下方的“包含预发布 Release”，否则 GitHub 找不到正式版会导致同步失败。</p>"
        f"<div class='table-wrap'><table style='min-width:auto'><tbody>{rows}</tbody></table></div></div>"
    )


def _admin_settings_content(config: dict[str, Any], actor: dict[str, Any], csrf_html: str) -> str:
    """服务器设置分区。所有可配置项都在界面上可见可改，不再依赖隐藏字段保值。"""
    esc = lambda value: html.escape(str(value), quote=True)
    newline = "\n"
    selected_mode = str(config.get("authMode") or infer_auth_mode(config))
    mode_options = "".join(
        f"<option value='{mode}' {'selected' if mode == selected_mode else ''}>{label}</option>"
        for mode, label in (
            ("public", "公开访问"),
            ("api_key", "API Key"),
            ("account", "独立账号密码"),
            ("host_account_allowlist", "阅微账号白名单"),
        )
    )
    accounts = config.get("accounts", {})
    account_note = (
        f"当前已配置独立账号：{esc('、'.join(sorted(accounts.keys())))}"
        if isinstance(accounts, dict) and accounts
        else "当前没有独立账号"
    )
    api_key_note = "已配置 API Key" if api_key_auth_configured(config) else "尚未配置任何 API Key"
    kind_checkboxes = "".join(
        f"<label><input type='checkbox' name='module_upload_kinds' value='{esc(kind)}' "
        f"{'checked' if kind in config.get('moduleUploadKinds', []) else ''}> {esc(_admin_kind_label(kind))}</label>"
        for kind in sorted(runtime.MODULE_UPLOAD_DEFAULT_KINDS)
    )
    head = (
        "<div class='page-head'><div><p class='eyebrow'>系统</p><h1>服务器设置</h1>"
        "<p class='muted'>认证模式为单选，只有当前选中的认证方式会对 API 生效。未选中的模式配置会保留但不参与认证。</p></div></div>"
    )
    auth_blocks = (
        f"<div data-auth-section='api_key'>"
        f"<p class='muted' style='margin:0 0 10px'>{esc(api_key_note)}。明细管理请到“子管理员与安全”。</p>"
        f"<label>API Key（留空保持当前值）<input type='password' name='api_key' autocomplete='new-password'></label>"
        f"<label><input type='checkbox' name='generate_api_key'> 生成新的随机 API Key（保存后显示一次）</label>"
        f"<label><input type='checkbox' name='clear_api_key'> 清空全部 API Key</label></div>"
        f"<div data-auth-section='account'>"
        f"<p class='muted' style='margin:0 0 10px'>{account_note}。</p>"
        f"<div class='grid-2'><label>新增或更新账号名<input name='account_name' autocomplete='off'></label>"
        f"<label>账号密码<input type='password' name='account_password' autocomplete='new-password'></label></div>"
        f"<label>要删除的账号名<input name='remove_account' autocomplete='off'></label></div>"
        f"<div data-auth-section='host_account_allowlist'>"
        f"<label>阅微账号白名单（每行一个阅微账号 ID）"
        f"<textarea name='host_account_allowlist'>{esc(newline.join(config.get('hostAccountAllowlist', [])))}</textarea></label></div>"
    )
    form = (
        f"<div class='panel'><h2>基础与认证</h2><form method='post' action='/admin/settings'>{csrf_html}"
        f"<div class='grid-2'>"
        f"<label>服务器 ID<input name='server_id' value='{esc(config.get('serverId', ''))}'></label>"
        f"<label>最低模块版本<input name='min_module_version' value='{esc(config.get('minModuleVersion', ''))}'></label>"
        f"<label>认证类型<select name='auth_mode' id='auth-mode'>{mode_options}</select></label>"
        f"<label>功能列表（逗号分隔）<input name='features' value='{esc(','.join(config.get('features', [])))}'></label>"
        f"</div>{auth_blocks}"
        f"<fieldset><legend>模块 Release 同步</legend>"
        f"<div class='grid-2'>"
        f"<label>GitHub 仓库<input name='github_repository' value='{esc(config.get('githubRepository', ''))}'></label>"
        f"<label>同步间隔（秒，最小 300）<input name='release_sync_seconds' type='number' min='300' value='{esc(config.get('releaseSyncSeconds', 1800))}'></label>"
        f"<label>GitHub 令牌（留空保持当前值）<input type='password' name='github_token' autocomplete='new-password'></label>"
        f"<label>最低可接受 versionCode<input name='release_version_code' type='number' min='0' value='{esc(config.get('releaseVersionCode', 0))}'></label>"
        f"</div>"
        f"<label><input type='checkbox' name='github_include_prerelease' {'checked' if config.get('githubIncludePrerelease') else ''}> "
        f"包含预发布 Release（仓库只发预发布时必须勾选）</label>"
        f"<label><input type='checkbox' name='clear_github_token'> 清空已保存的 GitHub 令牌</label>"
        f"</fieldset>"
        f"<fieldset><legend>服务器快照</legend>"
        f"<label><input type='checkbox' name='server_snapshot_enabled' {'checked' if config.get('serverSnapshotEnabled', True) else ''}> 启用定时快照</label>"
        f"<div class='grid-2'>"
        f"<label>快照间隔（秒，最小 3600）<input name='server_snapshot_seconds' type='number' min='3600' value='{esc(config.get('serverSnapshotSeconds', 86400))}'></label>"
        f"<label>保留份数（最少 3）<input name='server_snapshot_retention' type='number' min='3' value='{esc(config.get('serverSnapshotRetention', 30))}'></label>"
        f"</div></fieldset>"
        f"<fieldset><legend>用户模块上传</legend>"
        f"<p class='muted' style='margin:0;font-size:12px'>启用后，白名单内的阅微账号可以从模块上传书源和关联源。"
        f"服务器已存在名称和域名相同的源时只建立关联，不会被模块覆盖。</p>"
        f"<label><input type='checkbox' name='module_upload_enabled' {'checked' if config.get('moduleUploadEnabled') else ''}> 启用模块上传内容库</label>"
        f"<label>上传 ID 白名单（阅微账号 ID，每行一个）"
        f"<textarea name='module_upload_allowlist' placeholder='例如&#10;10086&#10;20250'>{esc(newline.join(config.get('moduleUploadAllowlist', [])))}</textarea></label>"
        f"<div><p class='muted' style='margin:0 0 8px;font-size:12px'>允许上传的类型（全部取消则回退到默认的书源和关联源）</p>{kind_checkboxes}</div>"
        f"</fieldset>"
        f"<fieldset><legend>内容包签名</legend>"
        f"<label>Ed25519 签名公钥（Base64，留空表示不校验签名）"
        f"<textarea name='signing_public_key' style='min-height:70px'>{esc(config.get('signingPublicKey', ''))}</textarea></label>"
        f"</fieldset>"
        f"<div class='inline'><button class='button' type='submit'>保存设置</button>"
        f"<button class='button subtle' type='submit' formaction='/admin/sync'>立即同步模块 Release</button></div>"
        f"</form></div>"
    )
    script = (
        "<script>(()=>{const select=document.getElementById('auth-mode');if(!select)return;"
        "const sync=()=>document.querySelectorAll('[data-auth-section]').forEach(node=>{"
        "node.hidden=node.dataset.authSection!==select.value;"
        "node.querySelectorAll('input,textarea,select').forEach(input=>input.disabled=node.hidden)});"
        "select.addEventListener('change',sync);sync()})();</script>"
    )
    return head + form + _admin_release_panel() + script


def _admin_overview_stats(config: dict[str, Any], records: list[dict[str, Any]]) -> str:
    """概览统计卡片：内容库、任务、模块在线和服务器配置要点。"""
    esc = lambda value: html.escape(str(value), quote=True)
    tasks = load_tasks()
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    # 按规范归属去重，同一个阅微账号的多条历史心跳只算一台设备。
    online = len({
        canonical_owner_of(str(item.get("owner", "")))
        for item in load_presence().values()
        if isinstance(item, dict) and bounded_config_int(item.get("onlineUntil", 0), 0, 0) > now
    })
    pending = sum(1 for item in load_notifications().values() if not item.get("deliveredAt"))
    release_version = "未同步"
    release_path = runtime.RELEASE_ROOT / "latest.json"
    if release_path.is_file():
        try:
            release_version = str(json.loads(release_path.read_text(encoding="utf-8")).get("versionName", "未同步"))
        except (OSError, ValueError):
            release_version = "读取失败"
    cards = [
        (str(len(records)), "内容包总数"),
        (str(sum(1 for item in records if str(item.get("status", "published")) == "published")), "其中已发布"),
        (str(len(tasks)), "云端任务"),
        (str(sum(1 for item in tasks.values() if str(item.get("status")) == "failed")), "执行失败任务"),
        (str(online), "模块在线设备"),
        (str(pending), "待发送消息"),
        (release_version, "模块 Release 版本"),
        ("已开放" if config.get("moduleUploadEnabled") else "未开放", "用户模块上传"),
    ]
    return (
        "<div class='stats' style='grid-template-columns:repeat(4,minmax(0,1fr))'>"
        + "".join(f"<div><strong>{esc(value)}</strong><span>{esc(label)}</span></div>" for value, label in cards)
        + "</div>"
    )


def _admin_presence_panel() -> str:
    """模块在线状态与离线消息队列。此前后台完全看不到这两类数据。"""
    esc = lambda value: html.escape(str(value), quote=True)
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    presence_rows = []
    # 按规范归属再去重一次：即使存量数据还没被启动迁移折叠，同一个阅微账号也只显示最近一行。
    deduped: dict[str, dict[str, Any]] = {}
    for item in load_presence().values():
        if not isinstance(item, dict):
            continue
        canonical = canonical_owner_of(str(item.get("owner", ""))) or "未标记"
        existing = deduped.get(canonical)
        if existing is None or bounded_config_int(item.get("lastSeenAt", 0), 0, 0) > bounded_config_int(existing.get("lastSeenAt", 0), 0, 0):
            deduped[canonical] = item
    for item in sorted(deduped.values(), key=lambda value: bounded_config_int(value.get("lastSeenAt", 0), 0, 0), reverse=True):
        online_until = bounded_config_int(item.get("onlineUntil", 0), 0, 0)
        online = online_until > now
        presence_rows.append(
            f"<tr><td>{esc(_admin_owner_label(item.get('owner', '')))}</td>"
            f"<td><span class='status status-{'online' if online else 'offline'}'>{'在线' if online else '离线'}</span>"
            f"<small>租约至 {esc(_admin_time_label(online_until, '未记录'))}</small></td>"
            f"<td>{esc(item.get('moduleVersion', '') or '未上报')}"
            f"<small>构建 {esc(_admin_time_label(item.get('buildTime'), '未记录'))}</small></td>"
            f"<td>{esc(item.get('source', '') or 'module')}</td>"
            f"<td>{esc(_admin_time_label(item.get('lastSeenAt'), '未记录'))}"
            f"<small>{esc(_admin_time_detail(item.get('lastSeenAt'), '未记录'))}</small></td></tr>"
        )
    presence_table = "".join(presence_rows) or "<tr><td colspan='5' class='empty'>暂无模块心跳记录</td></tr>"
    notification_rows = []
    notifications = sorted(
        load_notifications().values(),
        key=lambda value: bounded_config_int(value.get("createdAt", 0), 0, 0),
        reverse=True,
    )
    for item in notifications[:30]:
        delivered = bounded_config_int(item.get("deliveredAt", 0), 0, 0)
        result = str(item.get("result", ""))
        notification_rows.append(
            f"<tr><td>{esc(item.get('title', '') or '任务消息')}"
            f"<small>{esc(item.get('message', '') or '无消息内容')}</small></td>"
            f"<td>{esc(_admin_owner_label(item.get('owner', '')))}</td>"
            f"<td><span class='status status-{esc(result)}'>{esc(_admin_result_label(result))}</span></td>"
            f"<td><span class='status status-{'success' if delivered else 'unverified'}'>{'已送达' if delivered else '待发送'}</span>"
            f"<small>{esc(_admin_time_label(delivered, '模块下次在线时发送'))}</small></td>"
            f"<td>{esc(_admin_time_label(item.get('createdAt'), '未记录'))}</td></tr>"
        )
    notification_table = "".join(notification_rows) or "<tr><td colspan='5' class='empty'>暂无任务消息</td></tr>"
    pending_total = sum(1 for item in notifications if not item.get("deliveredAt"))
    return (
        f"<div class='panel'><h2>模块在线状态</h2>"
        f"<p class='muted'>阅微在前台时模块每 5 分钟上报一次心跳，另外在零点和云端任务完成后由系统闹钟唤醒上报；服务器按 10 分钟租约判断在线。"
        f"消息在心跳响应里下发，模块显示成功后回执，未回执的会保留到下次在线。</p>"
        f"<div class='table-wrap'><table><thead><tr><th>来源账号</th><th>连接状态</th><th>模块版本</th><th>上报来源</th><th>最近心跳</th></tr></thead>"
        f"<tbody>{presence_table}</tbody></table></div></div>"
        f"<div class='panel'><h2>任务消息队列</h2>"
        f"<p class='muted'>显示最近 30 条任务消息，其中 {pending_total} 条等待发送。模块确认接收后才会标记为已送达。</p>"
        f"<div class='table-wrap'><table><thead><tr><th>消息</th><th>接收账号</th><th>任务结果</th><th>发送状态</th><th>产生时间</th></tr></thead>"
        f"<tbody>{notification_table}</tbody></table></div></div>"
    )


def normalized_admin_permissions(values: Any) -> list[str]:
    """把复选框的多值或逗号分隔字符串统一成受支持的权限列表。"""
    if isinstance(values, str):
        values = values.replace(",", "\n").splitlines()
    if not isinstance(values, (list, tuple, set)):
        values = []
    allowed = {key for key, _ in ADMIN_ASSIGNABLE_PERMISSIONS}
    return sorted({str(item).strip() for item in values if str(item).strip() in allowed})


def _admin_subadmin_table(config: dict[str, Any], csrf_html: str) -> str:
    """子管理员列表，带重置密码、启用停用和删除操作。"""
    esc = lambda value: html.escape(str(value), quote=True)
    accounts = config.get("adminAccounts", {})
    rows = []
    for username in sorted(accounts.keys()):
        record = accounts.get(username)
        if not isinstance(record, dict):
            continue
        enabled = bool(record.get("enabled", True))
        permissions = record.get("permissions", [])
        permission_labels = "、".join(
            dict(ADMIN_ASSIGNABLE_PERMISSIONS).get(str(item), str(item)) for item in permissions
        ) if isinstance(permissions, list) and permissions else "未分配权限"
        actions = (
            f"<form class='inline' method='post' action='/admin/admins/reset'>{csrf_html}"
            f"<input type='hidden' name='username' value='{esc(username)}'>"
            f"<button class='button subtle' type='submit'>重置密码</button></form> "
            f"<form class='inline' method='post' action='/admin/admins/toggle'>{csrf_html}"
            f"<input type='hidden' name='username' value='{esc(username)}'>"
            f"<button class='button subtle' type='submit'>{'停用' if enabled else '启用'}</button></form> "
            f"<form class='inline' method='post' action='/admin/admins/delete'>{csrf_html}"
            f"<input type='hidden' name='username' value='{esc(username)}'>"
            f"<button class='button subtle danger' type='submit'>删除</button></form>"
        )
        rows.append(
            f"<tr><td><strong>{esc(username)}</strong><small>由 {esc(record.get('createdBy', '未知'))} 创建</small></td>"
            f"<td><span class='status status-{'valid' if enabled else 'paused'}'>{'已启用' if enabled else '已停用'}</span></td>"
            f"<td>{esc(permission_labels)}</td>"
            f"<td>{esc(_admin_time_label(record.get('updatedAt'), '未记录'))}"
            f"<small>创建于 {esc(_admin_time_label(record.get('createdAt'), '未记录'))}</small></td>"
            f"<td class='actions'>{actions}</td></tr>"
        )
    table = "".join(rows) or "<tr><td colspan='5' class='empty'>暂无子管理员</td></tr>"
    permission_checkboxes = "".join(
        f"<label><input type='checkbox' name='permissions' value='{esc(key)}' "
        f"{'checked' if key in {'settings:write', 'packages:write', 'tasks:write'} else ''}> {esc(label)}</label>"
        for key, label in ADMIN_ASSIGNABLE_PERMISSIONS
    )
    return (
        f"<div class='panel'><h2>子管理员</h2>"
        f"<p class='muted'>子管理员使用同一个登录地址，只能执行被授予的操作。重置密码、停用和删除会立即清空该账号的全部后台会话。</p>"
        f"<div class='table-wrap' style='margin-bottom:16px'><table><thead><tr><th>用户名</th><th>状态</th><th>权限</th><th>最近变更</th><th>操作</th></tr></thead>"
        f"<tbody>{table}</tbody></table></div>"
        f"<h3>创建子管理员</h3>"
        f"<form method='post' action='/admin/admins/create'>{csrf_html}"
        f"<div class='grid-2'><label>用户名<input name='username' required></label>"
        f"<label>初始密码（留空自动生成 24 位随机密码）<input type='password' name='password' minlength='12'></label></div>"
        f"<fieldset><legend>授予权限</legend>{permission_checkboxes}</fieldset>"
        f"<button class='button' type='submit'>创建子管理员</button></form></div>"
    )


def _admin_api_key_table(config: dict[str, Any], csrf_html: str) -> str:
    """API Key 列表与创建、吊销操作。密钥明文只在创建时显示一次。"""
    esc = lambda value: html.escape(str(value), quote=True)
    rows = []
    for record in api_key_public_records(config):
        enabled = bool(record.get("enabled", True))
        permissions = record.get("permissions", [])
        actions = ""
        if enabled:
            actions = (
                f"<form class='inline' method='post' action='/admin/security/api-keys/revoke'>{csrf_html}"
                f"<input type='hidden' name='key_id' value='{esc(record.get('id', ''))}'>"
                f"<button class='button subtle danger' type='submit'>吊销</button></form>"
            )
        revoked_note = "" if enabled else f"<small>吊销于 {esc(_admin_time_label(record.get('revokedAt'), '未记录'))}</small>"
        permission_text = "、".join(str(item) for item in permissions) if permissions else "无"
        action_cell = actions or "<span class='muted'>无可用操作</span>"
        rows.append(
            f"<tr><td><strong>{esc(record.get('name', ''))}</strong><small><code>{esc(record.get('id', ''))}</code></small></td>"
            f"<td><span class='status status-{'valid' if enabled else 'invalid'}'>{'生效中' if enabled else '已吊销'}</span>{revoked_note}</td>"
            f"<td>{esc(permission_text)}</td>"
            f"<td>{esc(_admin_time_label(record.get('createdAt'), '未记录'))}</td>"
            f"<td>{esc(_admin_time_label(record.get('lastUsedAt'), '从未使用'))}</td>"
            f"<td class='actions'>{action_cell}</td></tr>"
        )
    table = "".join(rows) or "<tr><td colspan='6' class='empty'>暂无 API Key</td></tr>"
    permission_checkboxes = "".join(
        f"<label><input type='checkbox' name='permissions' value='{esc(key)}' {'checked' if key == 'read' else ''}> {esc(key)}</label>"
        for key in API_KEY_PERMISSIONS
    )
    return (
        f"<div class='panel'><h2>API Key</h2>"
        f"<p class='muted'>供脚本或第三方客户端调用 API 使用。密钥只保存哈希，明文仅在创建时显示一次；泄露后请立即吊销。</p>"
        f"<div class='table-wrap' style='margin-bottom:16px'><table><thead><tr><th>名称</th><th>状态</th><th>权限范围</th><th>创建时间</th><th>最近使用</th><th>操作</th></tr></thead>"
        f"<tbody>{table}</tbody></table></div>"
        f"<h3>创建 API Key</h3>"
        f"<form method='post' action='/admin/security/api-keys/create'>{csrf_html}"
        f"<label>名称<input name='name' placeholder='例如 内容同步脚本'></label>"
        f"<fieldset><legend>权限范围</legend>{permission_checkboxes}</fieldset>"
        f"<button class='button' type='submit'>创建 API Key</button></form></div>"
    )


def _admin_snapshot_table(config: dict[str, Any], actor: dict[str, Any], csrf_html: str) -> str:
    """服务器快照列表，带下载、校验和（仅主管理员）恢复操作。"""
    esc = lambda value: html.escape(str(value), quote=True)
    is_primary = actor.get("role") == "primary"
    rows = []
    for item in list_server_snapshots():
        filename = str(item.get("filename", item.get("name", "")))
        actions = [
            f"<a class='button subtle' href='/admin/backups/server/{esc(filename)}'>下载</a>",
            f"<form class='inline' method='post' action='/admin/security/backups/verify'>{csrf_html}"
            f"<input type='hidden' name='filename' value='{esc(filename)}'>"
            f"<button class='button subtle' type='submit'>校验完整性</button></form>",
        ]
        created = item.get("createdAt") or item.get("modifiedAt")
        rows.append(
            f"<tr><td><strong>{esc(filename)}</strong>"
            f"<small>摘要 <code title='{esc(item.get('sha256', ''))}'>{esc(_admin_digest_label(item.get('sha256')))}</code></small></td>"
            f"<td>{esc(_admin_size_label(item.get('size')))}</td>"
            f"<td>{esc(_admin_time_label(created, '未记录'))}"
            f"<small>{esc(_admin_time_detail(created, '未记录'))}</small></td>"
            f"<td class='actions'>{' '.join(actions)}</td></tr>"
        )
    table = "".join(rows) or "<tr><td colspan='4' class='empty'>暂无服务器快照</td></tr>"
    restore_block = ""
    if is_primary:
        restore_block = (
            f"<h3>恢复快照</h3>"
            f"<p class='muted'>恢复会用快照内的数据库和 <code>server.json</code> 覆盖当前数据，"
            f"覆盖前会自动创建一份安全快照。恢复后请重启容器让所有进程重新加载配置。</p>"
            f"<form method='post' action='/admin/security/backups/restore'>{csrf_html}"
            f"<div class='grid-2'><label>要恢复的快照文件名<input name='filename' placeholder='reamicro-server-....zip' required></label>"
            f"<label>再次输入同一文件名以确认<input name='confirm' placeholder='与上方完全一致' required></label></div>"
            f"<button class='button danger' type='submit'>确认恢复</button></form>"
        )
    else:
        restore_block = "<p class='muted'>恢复快照仅限主管理员操作。</p>"
    retention = bounded_config_int(config.get("serverSnapshotRetention", 30), 30, 3)
    return (
        f"<div class='panel'><h2>服务器快照</h2>"
        f"<p class='muted'>快照包含 SQLite 数据库与服务器配置，最多保留 {retention} 份。"
        f"快照内含加密后的阅微凭据，请与服务器加密密钥分开保存。</p>"
        f"<form class='inline' method='post' action='/admin/backups/server' style='margin-bottom:14px'>{csrf_html}"
        f"<button class='button' type='submit'>立即创建快照</button></form>"
        f"<div class='table-wrap' style='margin-bottom:16px'><table><thead><tr><th>文件名</th><th>大小</th><th>创建时间</th><th>操作</th></tr></thead>"
        f"<tbody>{table}</tbody></table></div>{restore_block}</div>"
    )


def _admin_security_content(config: dict[str, Any], actor: dict[str, Any], csrf_html: str) -> str:
    """安全分区：子管理员、API Key、快照、密钥轮换和审计入口。"""
    esc = lambda value: html.escape(str(value), quote=True)
    is_primary = actor.get("role") == "primary"
    permissions = set(actor.get("permissions", []))
    head = (
        "<div class='page-head'><div><p class='eyebrow'>保护</p><h1>子管理员与安全</h1>"
        "<p class='muted'>管理员账号、API Key、服务器快照与加密密钥。</p></div></div>"
    )
    stats = (
        f"<div class='stats'>"
        f"<div><strong>{len(config.get('adminAccounts', {}))}</strong><span>子管理员</span></div>"
        f"<div><strong>{sum(1 for item in api_key_public_records(config) if item.get('enabled', True))}</strong><span>生效中的 API Key</span></div>"
        f"<div><strong>{len(list_server_snapshots())}</strong><span>服务器快照</span></div>"
        f"<div><strong>{'已设置' if config.get('secretKey') else '未设置'}</strong><span>服务器加密密钥</span></div>"
        f"</div>"
    )
    blocks = [head, stats]
    if is_primary:
        blocks.append(_admin_subadmin_table(config, csrf_html))
        blocks.append(_admin_api_key_table(config, csrf_html))
    if is_primary or "backup:admin" in permissions:
        blocks.append(_admin_snapshot_table(config, actor, csrf_html))
    if is_primary or "security:write" in permissions:
        blocks.append(
            f"<div class='panel'><h2>服务器加密密钥</h2>"
            f"<p class='muted'>该密钥用于加密阅微登录凭据和任务参数。轮换会用新密钥重新加密所有既有数据；"
            f"轮换期间请避免并发写入，并务必保存新密钥，丢失后既有凭据无法解密。</p>"
            f"<form method='post' action='/admin/security/rotate-secret'>{csrf_html}"
            f"<label>新的加密密钥（留空则自动生成随机长密钥）<input type='password' name='new_secret_key' autocomplete='new-password'></label>"
            f"<button class='button danger' type='submit'>轮换加密密钥</button></form></div>"
        )
    if is_primary or "audit:read" in permissions:
        blocks.append(
            f"<div class='panel'><h2>审计日志</h2>"
            f"<p class='muted'>记录登录、设置变更、内容包操作、模块上传和快照操作，保存在数据卷的 "
            f"<code>/data/audit/events.jsonl</code>。</p>"
            f"<p><a class='button subtle' href='/admin/audit'>查看审计日志</a></p></div>"
        )
    if not is_primary:
        blocks.append(
            f"<div class='panel'><h2>当前账号权限</h2><p class='muted'>你是子管理员 "
            f"{esc(actor.get('username', ''))}，已获授权："
            f"{esc('、'.join(dict(ADMIN_ASSIGNABLE_PERMISSIONS).get(item, item) for item in sorted(permissions)) or '无')}。"
            f"子管理员账号与 API Key 管理仅主管理员可见。</p></div>"
        )
    return "".join(blocks)


def admin_page(config: dict[str, Any], message: str = "", actor: dict[str, Any] | None = None, secret_notice: str = "", section: str = "overview", query: str = "") -> str:
    """分类管理后台，保留原有写入路由并提供统一内容列表入口。"""
    merge_duplicate_credentials()
    merge_duplicate_tasks()
    esc = lambda value: html.escape(str(value), quote=True)
    actor = actor or {"username": "", "role": "subadmin", "permissions": []}
    valid_sections = {"overview", "packages", "tasks", "settings", "security", *runtime.PACKAGE_KINDS}
    section = section if section in valid_sections else "overview"
    csrf_html = f"<input type='hidden' name='csrf_token' value='{esc(admin_csrf_token(config, actor))}'>"
    records = _admin_package_records()
    actor_permissions = set(actor.get("permissions", []))
    is_primary = actor.get("role") == "primary"
    can_packages = is_primary or "packages:write" in actor_permissions
    can_tasks = is_primary or "tasks:write" in actor_permissions
    no_permission_note = "<span class='muted'>需要任务管理权限</span>"
    credential_rows = []
    for credential in sorted(load_credentials().values(), key=lambda value: bounded_config_int(value.get("updatedAt", 0), 0, 0), reverse=True):
        owner = str(credential.get("owner", ""))
        owner_display = owner[:12] + "…" if len(owner) > 12 else owner
        public_credential = credential_public(credential)
        health = str(public_credential.get("health", "unverified"))
        credential_id = str(credential.get("id", ""))
        enabled = bool(credential.get("enabled", True))
        credential_actions = ""
        if can_tasks:
            credential_actions = (
                f"<form class='inline' method='post' action='/admin/credentials/action'>{csrf_html}"
                f"<input type='hidden' name='credential_id' value='{esc(credential_id)}'>"
                f"<button class='button subtle' name='action' value='verify' type='submit'>验证</button>"
                f"<button class='button subtle' name='action' value='toggle' type='submit'>{'停用' if enabled else '启用'}</button>"
                f"<button class='button subtle danger' name='action' value='delete' type='submit'>删除</button></form>"
            )
        credential_rows.append(
            f"<tr><td><strong>{esc(credential.get('label', '阅微账号'))}</strong><small><code>{esc(credential_id)}</code></small></td>"
            f"<td>{esc(credential.get('accountId', '') or '未提供')}</td>"
            f"<td>{esc(_admin_owner_label(owner))}<small>{esc(owner_display)}</small></td>"
            f"<td><span class='status status-{esc(health)}'>{esc(_admin_health_label(health))}</span>"
            f"<small>{esc(credential.get('lastVerifyMessage', '') or '暂无验证说明')}</small></td>"
            f"<td>{esc(_admin_time_label(credential.get('updatedAt', 0), '未记录'))}"
            f"<small>最近使用 {esc(_admin_time_label(credential.get('lastUsedAt', 0), '从未使用'))}</small></td>"
            f"<td class='actions'>{credential_actions or no_permission_note}</td></tr>"
        )
    credential_table = "".join(credential_rows) or "<tr><td colspan='6' class='empty'>暂无模块上传的阅微同步密钥</td></tr>"
    credential_options = "<option value=''>不使用凭据</option>" + "".join(
        f"<option value='{esc(item.get('id', ''))}'>{esc(item.get('label', '阅微账号'))} · {esc(item.get('accountId', '') or item.get('owner', ''))}</option>"
        for item in sorted(load_credentials().values(), key=lambda value: str(value.get('label', '')))
    )
    task_rows = []
    for task in sorted(load_tasks().values(), key=lambda value: bounded_config_int(value.get("createdAt", 0), 0, 0), reverse=True):
        task_id = esc(task.get("id", "")); task_status = str(task.get("status", ""))
        task_actions = ""
        if can_tasks:
            buttons = ["<button class='button subtle' name='action' value='run'>立即运行</button>"]
            if task_status == "running":
                buttons = []
            elif task.get("enabled", True) and task_status not in {"cancelled", "paused"}:
                buttons.append("<button class='button subtle' name='action' value='pause'>暂停</button>")
            else:
                buttons.append("<button class='button subtle' name='action' value='resume'>恢复</button>")
            if task_status not in {"cancelled", "running"}:
                buttons.append("<button class='button subtle' name='action' value='cancel'>取消</button>")
            if task_status != "running":
                buttons.append("<button class='button subtle danger' name='action' value='delete'>删除</button>")
            if buttons:
                task_actions = f"<form class='inline' method='post' action='/admin/tasks/action'>{csrf_html}<input type='hidden' name='task_id' value='{task_id}'>{''.join(buttons)}</form>"
        last_result = str(task.get("lastResult", ""))
        result_note = f"<small>{esc(_admin_result_label(last_result))}</small>" if last_result else ""
        task_rows.append(
            f"<tr><td><strong>{esc(_admin_task_label(task.get('taskType', '')))}</strong>"
            f"<small>{esc(_admin_time_label(task.get('createdAt'), '未记录'))} 创建</small></td>"
            f"<td>{esc(_admin_owner_label(task.get('owner', '')))}</td>"
            f"<td>{esc(task_credential_id(task) or '未关联')}</td>"
            f"<td>{esc(_admin_task_detail(task))}</td>"
            f"<td><span class='status status-{esc(task_status)}'>{esc(_admin_task_status_label(task_status))}</span>"
            f"<small>{'已启用' if task.get('enabled', True) else '已停用'}</small></td>"
            f"<td>{esc(task.get('lastMessage', '') or '暂无执行记录')}{result_note}</td>"
            f"<td>{esc(_admin_time_label(task.get('nextRunAt', 0)))}"
            f"<small>{esc(_admin_time_detail(task.get('nextRunAt', 0)))}</small></td>"
            f"<td class='actions'><a class='button subtle' href='/admin/tasks/{task_id}/logs'>日志</a> {task_actions}</td></tr>"
        )
    task_table = "".join(task_rows) or "<tr><td colspan='8' class='empty'>暂无云端任务</td></tr>"
    if section in {"overview", "packages", *runtime.PACKAGE_KINDS}:
        selected = section if section in runtime.PACKAGE_KINDS else ""
        visible = [item for item in records if not selected or item.get("kind") == selected]
        head_action = f"<a class='button' href='/admin/packages/new?kind={esc(selected or 'online_source')}'>新增内容</a>" if can_packages else ""
        if section == "overview":
            content = (
                f"<div class='page-head'><div><p class='eyebrow'>总览</p><h1>概览</h1>"
                f"<p class='muted'>服务器状态、内容库统计与模块连接情况。</p></div>{head_action}</div>"
            )
            content += _admin_overview_stats(config, records)
            content += _admin_presence_panel()
            content += "<h2 style='margin:26px 0 14px'>全部内容</h2>"
        else:
            title = "内容管理" if section == "packages" else _admin_kind_label(section)
            description = (
                "按类型管理版本、依赖、发布状态和历史版本。"
                if section == "packages"
                else f"管理{_admin_kind_label(section)}的版本、发布状态和历史版本。"
            )
            content = (
                f"<div class='page-head'><div><p class='eyebrow'>内容中心</p><h1>{esc(title)}</h1>"
                f"<p class='muted'>{esc(description)}</p></div>{head_action}</div>"
            )
        clear_button = (
            f"<a class='button subtle' href='{admin_section_path(section)}'>清除搜索</a>" if query.strip() else ""
        )
        # 内容表格对概览、全部内容和各类型分区都要渲染，此前被误缩进导致分区页只有标题。
        content += (
            f"<form class='toolbar' method='get' action='{admin_section_path(section)}'>"
            f"<input name='q' value='{esc(query)}' placeholder='搜索名称、包 ID、版本或别名'>"
            f"<button class='button' type='submit'>搜索</button>{clear_button}"
            f"</form>"
            f"<div class='table-wrap'><table><thead><tr><th>类型 / 来源</th><th>内容</th>"
            f"<th>版本 / 构建时间</th><th>状态</th><th>渠道 / 依赖</th><th>操作</th></tr></thead>"
            f"<tbody>{_admin_package_table(visible, can_packages, query, admin_csrf_token(config, actor))}</tbody></table></div>"
        )
    else:
        task_credentials = ""
        if section == "tasks":
            all_tasks = load_tasks()
            task_credentials = (
                f"<div class='stats'>"
                f"<div><strong>{len(load_credentials())}</strong><span>同步密钥数量</span></div>"
                f"<div><strong>{len(all_tasks)}</strong><span>云端任务数量</span></div>"
                f"<div><strong>{sum(1 for value in all_tasks.values() if value.get('enabled', True))}</strong><span>已启用任务</span></div>"
                f"<div><strong>{sum(1 for value in all_tasks.values() if value.get('status') == 'failed')}</strong><span>执行失败任务</span></div>"
                f"</div>"
                f"<div class='panel'><h2>阅微同步密钥</h2>"
                f"<p class='muted'>密钥只保存加密密文，不显示原文。一个阅微账号 ID 对应一个同步密钥；重新同步会更新该账号的原记录。"
                f"删除密钥会自动暂停依赖它的任务。</p>"
                f"<div class='table-wrap'><table><thead><tr><th>密钥名称</th><th>阅微账号 ID</th><th>来源账号</th>"
                f"<th>验证状态</th><th>更新 / 使用时间</th><th>操作</th></tr></thead><tbody>{credential_table}</tbody></table></div></div>"
                f"<div class='panel'><h2>任务运行状态</h2>"
                f"<div class='table-wrap'><table><thead><tr><th>任务</th><th>来源账号</th><th>同步密钥 ID</th><th>任务详情</th>"
                f"<th>运行状态</th><th>最近结果</th><th>下次执行</th><th>操作</th></tr></thead><tbody>{task_table}</tbody></table></div></div>"
            )
        if section == "tasks":
            book_example = '[{"cloudBookId":123,"bookId":123,"name":"书名"}]'
            task_form = (
                f"<div class='panel'><h2>创建云端任务</h2>"
                f"<p class='muted'>同一所有者、同步密钥和任务类型只保留一个任务；重复创建会更新已有任务而不是新增。</p>"
                f"<form method='post' action='/admin/tasks/create'>{csrf_html}"
                f"<div class='grid-2'>"
                f"<label>任务类型<select name='task_type'><option value='yeshe_checkin'>野社零点签到</option>"
                f"<option value='yeshe_draw_card'>野社自动抽卡</option><option value='cloud_auto_read'>云端自动阅读</option>"
                f"<option value='http'>通用 HTTPS 请求</option></select></label>"
                f"<label>每日执行时间（北京时间 HH:MM）<input name='time_of_day' value='00:05'></label>"
                f"<label>同步密钥<select name='credential_id'>{credential_options}</select></label>"
                # 非 http 任务的归属跟随所选同步密钥，这里只作为 http 任务的兜底归属。
                f"<label>任务所有者（仅自定义 HTTP 任务使用；阅微任务自动跟随所选同步密钥）"
                f"<input name='owner' value='admin'></label>"
                f"<label>阅读时长（分钟，1-720）<input name='duration_minutes' type='number' min='1' max='720' value='30'></label>"
                f"<label>最近阅读取用数量（1-20）<input name='recent_limit' type='number' min='1' max='20' value='1'></label>"
                f"</div>"
                f"<label>自定义图书 JSON（留空则读取阅微最近阅读记录）"
                f"<textarea name='request_body' placeholder='{html.escape(book_example, quote=True)}'></textarea></label>"
                f"<p class='muted' style='margin:0'>自定义图书格式示例：<code>{html.escape(book_example)}</code></p>"
                f"<button class='button' type='submit'>创建任务</button></form></div>"
            )
            content = f"<div class='page-head'><div><p class='eyebrow'>自动化</p><h1>云端任务</h1><p class='muted'>配置、查看和控制自动阅读、签到、抽卡任务。每个阅微账号只保留一个同步密钥，重新同步会更新原密钥。</p></div></div>{task_credentials}{task_form}"
        elif section == "settings":
            content = _admin_settings_content(config, actor, csrf_html)
        else:
            content = _admin_security_content(config, actor, csrf_html)
    return _admin_layout(config, actor, section, content, message=message, secret_notice=secret_notice, records=records)


@app.get("/admin/legacy", response_class=HTMLResponse)
async def admin_legacy(actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    return RedirectResponse("/admin/settings", status_code=303)


def _admin_shell(
    title: str,
    body: str,
    config: dict[str, Any],
    actor: dict[str, Any],
    section: str = "packages",
    eyebrow: str = "内容中心",
    description: str = "",
    back_href: str = "",
    back_label: str = "返回内容列表",
) -> str:
    """后台子页面（编辑、预览、历史、差异、日志）的统一外壳，与主页面同一套侧栏和样式。"""
    esc = lambda value: html.escape(str(value), quote=True)
    back = f"<a class='button subtle' href='{esc(back_href)}'>← {esc(back_label)}</a>" if back_href else ""
    description_html = f"<p class='muted'>{esc(description)}</p>" if description else ""
    head = (
        f"<div class='page-head'><div><p class='eyebrow'>{esc(eyebrow)}</p><h1>{esc(title)}</h1>"
        f"{description_html}</div>{back}</div>"
    )
    return _admin_layout(config, actor, section, head + body, title=f"{title} · ReaMicro 管理后台")


def _admin_package_payload(package_dir: Path, manifest: dict[str, Any]) -> tuple[str, bytes]:
    filename = safe_payload_filename(str(manifest.get("payload", "payload.bin")))
    payload_path = package_dir / filename
    if not payload_path.is_file():
        raise HTTPException(status_code=404, detail="内容文件不存在")
    return filename, payload_path.read_bytes()


@app.get("/admin/packages/new", response_class=HTMLResponse)
async def admin_new_package(kind: str = Query("online_source"), actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    require_admin_permission(actor, "packages:write")
    kind = kind if kind in runtime.PACKAGE_KINDS else "online_source"
    config = load_config(); esc = lambda value: html.escape(str(value), quote=True)
    csrf = f"<input type='hidden' name='csrf_token' value='{esc(admin_csrf_token(config, actor))}'>"
    options = "".join(f"<option value='{k}' {'selected' if k == kind else ''}>{_admin_kind_label(k)}</option>" for k in sorted(runtime.PACKAGE_KINDS))
    channel_options = "".join(f"<option value='{value}'>{_admin_channel_label(value)}</option>" for value in ("stable", "beta", "nightly"))
    body = f"<div class='panel'><p class='muted'>包 ID、显示名称和版本号可留空：服务器会根据文件内容及已有列表自动生成；同一包再次上传会自动归档旧版本。</p><form method='post' action='/admin/packages/upload' enctype='multipart/form-data'>{csrf}<div class='grid-2'><label>内容类型<select name='kind'>{options}</select></label><label>包 ID（可留空自动生成）<input name='package_id' placeholder='根据名称自动生成'></label><label>版本号（可留空自动递增）<input name='version' placeholder='新包默认为 1.0.0'></label><label>显示名称（可留空自动识别）<input name='name' placeholder='从书源/样式文件识别'></label><label>稳定内容 ID（可留空使用包 ID）<input name='content_id'></label><label>发布状态<select name='status_value'><option value='published'>立即发布</option><option value='draft'>草稿</option><option value='testing'>测试中</option></select></label><label>发布渠道<select name='channel'>{channel_options}</select></label></div><label>别名（逗号分隔）<input name='aliases'></label><label>依赖 JSON<textarea name='dependencies' placeholder='[]'></textarea></label><label>内容文件<input type='file' name='payload' required></label><button class='button' type='submit'>上传并保存</button></form></div>"
    return HTMLResponse(_admin_shell(
        "新增内容",
        body,
        config,
        actor,
        section=kind,
        description="上传书源、关联源、样式或主题。留空字段会由服务器根据文件内容推断。",
        back_href=admin_section_path(kind),
        back_label=f"返回{_admin_kind_label(kind)}列表",
    ))


@app.get("/admin/packages/{package_kind}/{package_id}/preview", response_class=HTMLResponse)
async def admin_preview_package(package_kind: str, package_id: str, actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    package_kind = safe_package_segment(package_kind); package_id = safe_package_segment(package_id)
    manifest_path, manifest = package_manifest(package_kind, package_id)
    filename, payload = _admin_package_payload(manifest_path.parent, manifest)
    try:
        text = payload.decode("utf-8")
    except UnicodeDecodeError:
        text = "[二进制内容，无法在浏览器中预览]"
    if filename.lower().endswith((".json", ".json5")):
        try:
            text = json.dumps(json.loads(text), ensure_ascii=False, indent=2)
        except ValueError:
            pass
    esc = lambda value: html.escape(str(value), quote=True)
    meta_rows = "".join(
        f"<tr><th style='width:150px'>{esc(label)}</th><td>{value}</td></tr>"
        for label, value in (
            ("显示名称", esc(manifest.get("name", package_id))),
            ("包 ID", f"<code>{esc(package_id)}</code>"),
            ("稳定内容 ID", f"<code>{esc(manifest.get('contentId', '') or package_id)}</code>"),
            ("版本", esc(manifest.get("version", ""))),
            ("构建时间", esc(_admin_time_detail(manifest.get("buildTime"), "未记录"))),
            ("发布状态", f"<span class='status status-{esc(manifest.get('status', 'published'))}'>{esc(_admin_status_label(manifest.get('status', 'published')))}</span>"),
            ("发布渠道", esc(_admin_channel_label(manifest.get("channel", "stable")))),
            ("内容文件", f"<code>{esc(filename)}</code> · {esc(_admin_size_label(len(payload)))}"),
            ("内容摘要", f"<code title='{esc(manifest.get('sha256', ''))}'>{esc(_admin_digest_label(manifest.get('sha256')))}</code>"),
            ("域名", esc("、".join(sorted(package_match_domains(manifest))) or "未记录")),
            ("别名", esc("、".join(str(item) for item in manifest.get("aliases", [])) or "无")),
            ("上传来源", esc(_admin_owner_label(manifest.get("uploadOwner", "")) if manifest.get("uploadOwner") else "后台管理员")),
        )
    )
    body = f"<div class='table-wrap' style='margin-bottom:18px'><table style='min-width:auto'><tbody>{meta_rows}</tbody></table></div><div class='panel'><h2>内容文件</h2><pre>{html.escape(text)}</pre></div>"
    return HTMLResponse(_admin_shell(
        "预览 · " + str(manifest.get("name", package_id)),
        body,
        load_config(),
        actor,
        section=package_kind,
        description=f"{_admin_kind_label(package_kind)} · 只读预览，修改请使用编辑。",
        back_href=admin_section_path(package_kind),
        back_label=f"返回{_admin_kind_label(package_kind)}列表",
    ))


@app.get("/admin/packages/{package_kind}/{package_id}/edit", response_class=HTMLResponse)
async def admin_edit_package_get(package_kind: str, package_id: str, actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    require_admin_permission(actor, "packages:write")
    package_kind = safe_package_segment(package_kind); package_id = safe_package_segment(package_id)
    manifest_path, manifest = package_manifest(package_kind, package_id)
    filename, payload = _admin_package_payload(manifest_path.parent, manifest)
    try: text = payload.decode("utf-8")
    except UnicodeDecodeError: text = ""
    config = load_config(); esc = lambda value: html.escape(str(value), quote=True)
    csrf = f"<input type='hidden' name='csrf_token' value='{esc(admin_csrf_token(config, actor))}'>"
    status_options = "".join(f"<option value='{status}' {'selected' if manifest.get('status', 'published') == status else ''}>{_admin_status_label(status)}</option>" for status in ('published', 'draft', 'testing', 'unpublished'))
    channel_options = "".join(f"<option value='{channel}' {'selected' if manifest.get('channel', 'stable') == channel else ''}>{_admin_channel_label(channel)}</option>" for channel in ('stable', 'beta', 'nightly'))
    body = f"<div class='panel'><p class='muted'>当前版本 {esc(manifest.get('version', ''))}，构建于 {esc(_admin_time_label(manifest.get('buildTime'), '未记录'))}。保存后旧版本自动归档到历史，可随时回滚。</p><form method='post' action='/admin/packages/{esc(package_kind)}/{esc(package_id)}/edit'>{csrf}<div class='grid-2'><label>版本号<input name='version' value='{esc(manifest.get('version', ''))}' required></label><label>内容文件名<input name='filename' value='{esc(filename)}' required></label><label>稳定内容 ID<input name='content_id' value='{esc(manifest.get('contentId', ''))}'></label><label>名称<input name='name' value='{esc(manifest.get('name', package_id))}'></label><label>发布状态<select name='status_value'>{status_options}</select></label><label>发布渠道<select name='channel'>{channel_options}</select></label></div><label>别名（逗号分隔）<input name='aliases' value='{esc(','.join(str(item) for item in manifest.get('aliases', [])))}'></label><label>依赖 JSON<textarea name='dependencies'>{esc(json.dumps(manifest.get('dependencies', []), ensure_ascii=False, indent=2))}</textarea></label><label>内容<textarea name='payload_text' style='min-height:320px'>{esc(text)}</textarea></label><div class='inline'><button class='button' type='submit'>保存新版本</button> <a class='button subtle' href='/admin/packages/{esc(package_kind)}/{esc(package_id)}/preview'>取消</a></div></form></div>"
    return HTMLResponse(_admin_shell(
        "编辑 · " + str(manifest.get("name", package_id)),
        body,
        config,
        actor,
        section=package_kind,
        description=f"{_admin_kind_label(package_kind)} · 保存会生成新版本并归档当前版本。",
        back_href=f"/admin/packages/{package_kind}/{package_id}/preview",
        back_label="返回预览",
    ))


@app.post("/admin/packages/{package_kind}/{package_id}/edit", response_class=HTMLResponse)
async def admin_edit_package_post(
    package_kind: str,
    package_id: str,
    version: str = Form(...),
    filename: str = Form(...),
    content_id: str = Form(""),
    name: str = Form(""),
    aliases: str = Form(""),
    status_value: str = Form("published"),
    channel: str = Form("stable"),
    dependencies: str = Form("[]"),
    payload_text: str = Form(""),
    csrf_token: str = Form(""),
    actor: dict[str, Any] = Depends(admin_actor),
) -> HTMLResponse:
    require_admin_permission(actor, "packages:write")
    package_kind = safe_package_segment(package_kind); package_id = safe_package_segment(package_id)
    config = load_config(); require_admin_csrf(config, actor, csrf_token)
    manifest_path, current = package_manifest(package_kind, package_id)
    filename = safe_payload_filename(filename)
    version = safe_package_segment(version)
    status_value = status_value.strip().lower(); channel = channel.strip().lower()
    if status_value not in {"published", "draft", "testing", "unpublished"} or channel not in {"stable", "beta", "nightly"}:
        raise HTTPException(status_code=400, detail="状态或渠道无效")
    body = payload_text.encode("utf-8")
    validate_package_payload(package_kind, filename, body)
    metadata = infer_package_metadata(package_kind, filename, body)
    stable_id = normalize_content_id(content_id, str(current.get("contentId", "")) if current.get("contentId") else package_id)
    try: dependency_items = normalize_package_dependencies(json.loads(dependencies or "[]"), package_kind, package_id)
    except (ValueError, TypeError):
        raise HTTPException(status_code=400, detail="依赖必须是 JSON 数组")
    package_dir = manifest_path.parent
    history = package_dir / "history"; history.mkdir(exist_ok=True)
    old_version = safe_package_segment(current.get("version", "old"))
    archive_dir = history / f"{old_version}-{int(datetime.now(timezone.utc).timestamp() * 1000)}"; archive_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(manifest_path, archive_dir / "manifest.json")
    old_payload = package_dir / str(current.get("payload", ""))
    if old_payload.is_file() and old_payload.parent == package_dir:
        shutil.copy2(old_payload, archive_dir / old_payload.name)
    build_time = int(datetime.now(timezone.utc).timestamp() * 1000); digest = hashlib.sha256(body).hexdigest()
    manifest = {**current, "packageId": package_id, "kind": package_kind, "version": version, "buildTime": build_time, "sha256": digest, "payload": filename, "contentId": stable_id, "aliases": merge_package_aliases(current.get("aliases", []), metadata.get("identity", []), aliases), "name": name.strip() or (str(current.get("name", "")) if not metadata.get("explicitName") else "") or str(metadata.get("name") or package_id), "status": status_value, "channel": channel, "dependencies": dependency_items}
    if status_value == "published" and not package_dependency_status(manifest).get("dependenciesSatisfied", True):
        raise HTTPException(status_code=409, detail="内容包存在未满足的必需依赖")
    manifest["signature"] = package_signature(package_id, package_kind, stable_id, version, build_time, digest, body)
    payload_path = package_dir / filename
    old_payload_path = package_dir / str(current.get("payload", ""))
    temp_payload = package_dir / ".payload.tmp"; temp_payload.write_bytes(body); temp_payload.replace(payload_path)
    if old_payload_path != payload_path and old_payload_path.is_file() and old_payload_path.parent == package_dir:
        old_payload_path.unlink()
    temp_manifest = package_dir / ".manifest.json.tmp"; temp_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"); temp_manifest.replace(manifest_path)
    audit_event("package_edited", audit_actor(actor), metadata={"kind": package_kind, "packageId": package_id, "version": version})
    return HTMLResponse(admin_page(load_config(), f"已保存 {_admin_kind_label(package_kind)}/{package_id} {version}", actor=actor, section=package_kind))


@app.get("/admin/packages/{package_kind}/{package_id}/history", response_class=HTMLResponse)
async def admin_history_package(package_kind: str, package_id: str, actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    package_kind = safe_package_segment(package_kind); package_id = safe_package_segment(package_id)
    manifest_path, current = package_manifest(package_kind, package_id)
    config = load_config()
    esc = lambda value: html.escape(str(value), quote=True)
    csrf = esc(admin_csrf_token(config, actor))
    can_write = actor.get("role") == "primary" or "packages:write" in set(actor.get("permissions", []))
    rows = []
    for path in sorted((manifest_path.parent / "history").glob("*/manifest.json"), reverse=True):
        try:
            item = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        version_value = esc(item.get("version", ""))
        actions = [f"<a class='button subtle' href='/admin/packages/{esc(package_kind)}/{esc(package_id)}/diff?version={urllib.parse.quote(str(item.get('version', '')))}'>对比当前</a>"]
        if can_write:
            actions.append(
                f"<form class='inline' method='post' action='/admin/packages/{esc(package_kind)}/{esc(package_id)}/rollback'>"
                f"<input type='hidden' name='csrf_token' value='{csrf}'>"
                f"<input type='hidden' name='version' value='{version_value}'>"
                f"<button class='button subtle' type='submit'>回滚到此版本</button></form>"
            )
        rows.append(
            f"<tr><td><strong>{version_value}</strong></td>"
            f"<td>{esc(_admin_time_label(item.get('buildTime'), '未记录'))}<small>{esc(_admin_time_detail(item.get('buildTime'), '未记录'))}</small></td>"
            f"<td><code title='{esc(item.get('sha256', ''))}'>{esc(_admin_digest_label(item.get('sha256')))}</code></td>"
            f"<td><span class='status status-{esc(item.get('status', 'published'))}'>{esc(_admin_status_label(item.get('status', 'published')))}</span>"
            f"<small>{esc(_admin_channel_label(item.get('channel', 'stable')))}</small></td>"
            f"<td class='actions'>{' '.join(actions)}</td></tr>"
        )
    history_rows = "".join(rows) or "<tr><td colspan='5' class='empty'>暂无历史版本，保存新版本后旧版本会自动归档到这里</td></tr>"
    current_row = (
        f"<div class='panel'><h2>当前版本</h2><p class='muted'>版本 {esc(current.get('version', ''))} · "
        f"构建于 {esc(_admin_time_detail(current.get('buildTime'), '未记录'))} · "
        f"{esc(_admin_status_label(current.get('status', 'published')))} · {esc(_admin_channel_label(current.get('channel', 'stable')))}</p></div>"
    )
    body = f"{current_row}<div class='table-wrap'><table><thead><tr><th>版本</th><th>构建时间</th><th>内容摘要</th><th>状态 / 渠道</th><th>操作</th></tr></thead><tbody>{history_rows}</tbody></table></div>"
    return HTMLResponse(_admin_shell(
        "历史版本 · " + str(current.get("name", package_id)),
        body,
        config,
        actor,
        section=package_kind,
        description=f"{_admin_kind_label(package_kind)} · 回滚会把所选版本重新置为已发布状态。",
        back_href=admin_section_path(package_kind),
        back_label=f"返回{_admin_kind_label(package_kind)}列表",
    ))


@app.post("/admin/packages/{package_kind}/{package_id}/rollback", response_class=HTMLResponse)
async def admin_rollback_package(
    package_kind: str,
    package_id: str,
    version: str = Form(""),
    csrf_token: str = Form(""),
    actor: dict[str, Any] = Depends(admin_actor),
) -> HTMLResponse:
    require_admin_permission(actor, "packages:write")
    package_kind = safe_package_segment(package_kind); package_id = safe_package_segment(package_id)
    config = load_config(); require_admin_csrf(config, actor, csrf_token)
    manifest_path, current = package_manifest(package_kind, package_id)
    candidates = []
    for history_manifest in (manifest_path.parent / "history").glob("*/manifest.json"):
        try: item = json.loads(history_manifest.read_text(encoding="utf-8"))
        except (OSError, ValueError): continue
        if version and str(item.get("version")) != version: continue
        old_payload = history_manifest.parent / str(item.get("payload", ""))
        if old_payload.is_file(): candidates.append((item, old_payload))
    if not candidates:
        return HTMLResponse(admin_page(config, "历史版本不存在", actor=actor, section=package_kind), status_code=404)
    selected, old_payload = sorted(candidates, key=lambda pair: int(pair[0].get("buildTime", 0)), reverse=True)[0]
    if not package_dependency_status(selected).get("dependenciesSatisfied", True):
        return HTMLResponse(admin_page(config, "历史版本依赖未满足，无法回滚", actor=actor, section=package_kind), status_code=409)
    current_payload = manifest_path.parent / str(current.get("payload", ""))
    if current_payload.is_file(): current_payload.unlink()
    new_payload = manifest_path.parent / safe_payload_filename(str(selected.get("payload", old_payload.name)))
    new_payload.write_bytes(old_payload.read_bytes())
    selected["status"] = "published"; selected["rolledBackFrom"] = current.get("version", "")
    manifest_path.write_text(json.dumps(selected, ensure_ascii=False, indent=2), encoding="utf-8")
    audit_event("package_rolled_back", audit_actor(actor), metadata={"kind": package_kind, "packageId": package_id, "version": selected.get("version")})
    return HTMLResponse(admin_page(load_config(), f"已回滚到版本 {selected.get('version', '')}", actor=actor, section=package_kind))


@app.post("/admin/packages/{package_kind}/{package_id}/delete", response_class=HTMLResponse)
async def admin_delete_package(
    package_kind: str,
    package_id: str,
    csrf_token: str = Form(""),
    actor: dict[str, Any] = Depends(admin_actor),
) -> HTMLResponse:
    require_admin_permission(actor, "packages:write")
    package_kind = safe_package_segment(package_kind); package_id = safe_package_segment(package_id)
    config = load_config(); require_admin_csrf(config, actor, csrf_token)
    manifest_path, manifest = package_manifest(package_kind, package_id)
    if str(manifest.get("status", "published")) == "published":
        raise HTTPException(status_code=409, detail="已发布内容不能直接删除，请先下架")
    package_dir = manifest_path.parent
    trash_dir = runtime.PACKAGE_ROOT / ".trash" / package_kind
    trash_dir.mkdir(parents=True, exist_ok=True)
    target = trash_dir / f"{package_id}-{int(datetime.now(timezone.utc).timestamp() * 1000)}"
    shutil.move(str(package_dir), str(target))
    audit_event("package_deleted", audit_actor(actor), metadata={"kind": package_kind, "packageId": package_id, "status": manifest.get("status")})
    return HTMLResponse(admin_page(load_config(), f"已移除未发布内容 {package_kind}/{package_id}", actor=actor, section=package_kind))


@app.get("/admin/packages/{package_kind}/{package_id}/diff", response_class=HTMLResponse)
async def admin_diff_package(package_kind: str, package_id: str, version: str = Query(""), actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    package_kind = safe_package_segment(package_kind); package_id = safe_package_segment(package_id)
    manifest_path, current = package_manifest(package_kind, package_id)
    current_name, current_payload = _admin_package_payload(manifest_path.parent, current)
    target_payload = b""; target_manifest: dict[str, Any] | None = None
    for path in (manifest_path.parent / "history").glob("*/manifest.json"):
        try: item = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError): continue
        if version and str(item.get("version")) != version: continue
        candidate = path.parent / str(item.get("payload", ""))
        if candidate.is_file(): target_manifest, target_payload = item, candidate.read_bytes(); break
    if target_manifest is None:
        raise HTTPException(status_code=404, detail="历史版本不存在")
    import difflib
    current_text = current_payload.decode("utf-8", errors="replace").splitlines()
    target_text = target_payload.decode("utf-8", errors="replace").splitlines()
    diff = "\n".join(difflib.unified_diff(target_text, current_text, fromfile=f"{target_manifest.get('version')}/{current_name}", tofile=f"{current.get('version')}/{current_name}", lineterm=""))
    esc = lambda value: html.escape(str(value), quote=True)
    body = (
        f"<div class='panel'><p class='muted'>左侧为历史版本 {esc(target_manifest.get('version'))}"
        f"（构建于 {esc(_admin_time_label(target_manifest.get('buildTime'), '未记录'))}），"
        f"右侧为当前版本 {esc(current.get('version'))}"
        f"（构建于 {esc(_admin_time_label(current.get('buildTime'), '未记录'))}）。"
        f"以 <code>-</code> 开头的是历史版本独有的行，<code>+</code> 开头的是当前版本新增的行。</p>"
        f"<pre>{html.escape(diff or '两个版本的内容完全一致，没有文本差异。')}</pre></div>"
    )
    return HTMLResponse(_admin_shell(
        "版本差异 · " + str(current.get("name", package_id)),
        body,
        load_config(),
        actor,
        section=package_kind,
        description=f"{_admin_kind_label(package_kind)} · 历史版本与当前版本的逐行对比。",
        back_href=f"/admin/packages/{package_kind}/{package_id}/history",
        back_label="返回历史版本",
    ))


@app.get("/admin/login", response_class=HTMLResponse)
async def admin_login_get(request: Request) -> HTMLResponse:
    if session_admin(request):
        return RedirectResponse("/admin", status_code=303)
    return HTMLResponse(admin_login_page())


@app.post("/admin/login", response_class=HTMLResponse)
async def admin_login_post(username: str = Form(""), password: str = Form("")) -> Response:
    try:
        actor = resolve_admin(HTTPBasicCredentials(username=username, password=password), allow_bootstrap=True)
    except HTTPException:
        audit_event("admin_login_failed", actor=f"admin:{username}", success=False)
        return HTMLResponse(admin_login_page("用户名或密码错误"), status_code=401)
    token = create_admin_session(actor)
    audit_event("admin_login_success", actor=audit_actor(actor))
    result = RedirectResponse("/admin", status_code=303)
    result.set_cookie(
        runtime.ADMIN_SESSION_COOKIE,
        token,
        max_age=runtime.ADMIN_SESSION_SECONDS,
        httponly=True,
        secure=runtime.ADMIN_COOKIE_SECURE,
        samesite="lax",
        path="/admin",
    )
    return result


@app.post("/admin/logout")
async def admin_logout(request: Request, csrf_token: str = Form("")) -> RedirectResponse:
    token = request.cookies.get(runtime.ADMIN_SESSION_COOKIE, "")
    if token:
        actor = validate_admin_session_token(token)
        if actor:
            require_admin_csrf(load_config(), actor, csrf_token)
        runtime.get_state_store().delete_admin_session(api_key_digest(token))
    result = RedirectResponse("/admin/login", status_code=303)
    result.delete_cookie(runtime.ADMIN_SESSION_COOKIE, path="/admin")
    return result


def admin_html(page: str, status_code: int = 200) -> HTMLResponse:
    return HTMLResponse(page, status_code=status_code, headers={"Cache-Control": "no-store, no-cache, must-revalidate, max-age=0", "Pragma": "no-cache", "Expires": "0"})


@app.get("/admin", response_class=HTMLResponse)
async def admin(section: str = Query("overview"), q: str = Query(""), actor: dict[str, Any] = Depends(admin_actor)) -> Response:
    if actor.get("needsSetup"):
        return admin_html(admin_setup_page(actor=actor))
    if section != "overview":
        target = admin_section_path(section)
        if q:
            target += "?q=" + urllib.parse.quote(q, safe="")
        return RedirectResponse(target, status_code=303, headers={"Cache-Control": "no-store"})
    return admin_html(admin_page(load_config(), actor=actor, section="overview", query=q))


@app.get("/admin/content", response_class=HTMLResponse)
async def admin_content(q: str = Query(""), actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    return admin_html(admin_page(load_config(), actor=actor, section="packages", query=q))


@app.get("/admin/content/{package_kind}", response_class=HTMLResponse)
async def admin_content_kind(package_kind: str, q: str = Query(""), actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    if package_kind not in runtime.PACKAGE_KINDS:
        raise HTTPException(status_code=404, detail="内容分类不存在")
    return admin_html(admin_page(load_config(), actor=actor, section=package_kind, query=q))


@app.get("/admin/tasks", response_class=HTMLResponse)
async def admin_tasks_page(actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    return admin_html(admin_page(load_config(), actor=actor, section="tasks"))


@app.get("/admin/settings", response_class=HTMLResponse)
async def admin_settings_page(actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    return admin_html(admin_page(load_config(), actor=actor, section="settings"))


@app.get("/admin/security", response_class=HTMLResponse)
async def admin_security_page(actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    return admin_html(admin_page(load_config(), actor=actor, section="security"))


@app.get("/admin/audit", response_class=HTMLResponse)
async def admin_audit(
    q: str = Query(""),
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_admin_permission(actor, "audit:read")
    esc = lambda value: html.escape(str(value), quote=True)
    needle = q.strip().lower()
    rows = []
    total = 0
    failures = 0
    if runtime.AUDIT_PATH.is_file():
        for line in runtime.AUDIT_PATH.read_text(encoding="utf-8").splitlines()[-800:][::-1]:
            try:
                event = json.loads(line)
            except ValueError:
                continue
            total += 1
            success = bool(event.get("success"))
            if not success:
                failures += 1
            action = str(event.get("action", ""))
            action_label = _admin_action_label(action)
            actor_label = _admin_owner_label(event.get("actor", "")) if str(event.get("actor", "")).startswith(("host:", "account:", "key:", "host-public:")) else str(event.get("actor", "")) or "系统"
            metadata_label = _admin_metadata_label(event.get("metadata"))
            if needle and needle not in " ".join((action, action_label, actor_label, metadata_label)).lower():
                continue
            if len(rows) >= 400:
                continue
            request_id = str(event.get("requestId", ""))
            request_note = f"<small>请求 ID {esc(request_id)}</small>" if request_id else ""
            rows.append(
                f"<tr><td>{esc(_admin_time_label(event.get('at'), '未记录'))}"
                f"<small>{esc(_admin_time_detail(event.get('at'), '未记录'))}</small></td>"
                f"<td>{esc(actor_label)}</td>"
                f"<td><strong>{esc(action_label)}</strong><small><code>{esc(action)}</code></small></td>"
                f"<td><span class='status status-{'success' if success else 'failed'}'>{'成功' if success else '失败'}</span></td>"
                f"<td>{esc(metadata_label)}{request_note}</td></tr>"
            )
    table = "".join(rows) or "<tr><td colspan='5' class='empty'>没有匹配的审计记录</td></tr>"
    stats = (
        f"<div class='stats'>"
        f"<div><strong>{total}</strong><span>最近记录条数</span></div>"
        f"<div><strong>{failures}</strong><span>其中失败事件</span></div>"
        f"<div><strong>{len(rows)}</strong><span>当前显示</span></div>"
        f"<div><strong>{_admin_size_label(runtime.AUDIT_PATH.stat().st_size) if runtime.AUDIT_PATH.is_file() else '0 B'}</strong><span>日志文件大小</span></div>"
        f"</div>"
    )
    body = (
        f"{stats}"
        f"<form class='toolbar' method='get' action='/admin/audit'>"
        f"<input name='q' value='{esc(q)}' placeholder='搜索操作、操作者或详情'>"
        f"<button class='button' type='submit'>搜索</button></form>"
        f"<div class='table-wrap'><table><thead><tr><th>时间</th><th>操作者</th><th>操作</th><th>结果</th><th>详情</th></tr></thead>"
        f"<tbody>{table}</tbody></table></div>"
    )
    return admin_html(_admin_shell(
        "审计日志",
        body,
        load_config(),
        actor,
        section="security",
        eyebrow="保护",
        description="按时间倒序显示最近 800 条事件，最多渲染 400 条。",
        back_href="/admin/security",
        back_label="返回安全设置",
    ))


API_KEY_PERMISSIONS = (
    "read",
    "write",
    "packages:read",
    "packages:write",
    "tasks:read",
    "tasks:write",
    "credentials:read",
    "credentials:write",
    "backup:read",
)


def create_api_key_record(config: dict[str, Any], name: str, raw_permissions: Any) -> tuple[dict[str, Any], str]:
    """生成一条 API Key 记录并返回明文密钥；明文只在创建时返回一次。"""
    if isinstance(raw_permissions, str):
        raw_permissions = [item for item in raw_permissions.replace(",", "\n").splitlines()]
    if not isinstance(raw_permissions, (list, tuple)):
        raise HTTPException(status_code=400, detail=response(code="API_SCOPE_INVALID", message="permissions 必须是数组"))
    permissions = sorted({str(item).strip() for item in raw_permissions if str(item).strip()})
    if not set(permissions).issubset(set(API_KEY_PERMISSIONS)):
        raise HTTPException(status_code=400, detail=response(code="API_SCOPE_INVALID", message="存在不支持的 API Key 权限范围"))
    plain = generate_long_secret(48)
    record = {
        "id": "key_" + secrets.token_hex(8),
        "name": str(name or "API Key")[:80],
        "digest": api_key_digest(plain),
        "permissions": permissions or ["read"],
        "enabled": True,
        "createdAt": int(datetime.now(timezone.utc).timestamp() * 1000),
        "lastUsedAt": 0,
    }
    config["apiKeyRecords"] = [item for item in config.get("apiKeyRecords", []) if isinstance(item, dict)] + [record]
    return record, plain


def revoke_api_key_record(config: dict[str, Any], key_id: str) -> None:
    """把指定 API Key 标记为已吊销；记录保留以便审计。"""
    found = False
    records = []
    for item in config.get("apiKeyRecords", []):
        if not isinstance(item, dict):
            continue
        value = dict(item)
        if str(value.get("id")) == key_id:
            value["enabled"] = False
            value["revokedAt"] = int(datetime.now(timezone.utc).timestamp() * 1000)
            found = True
        records.append(value)
    if not found:
        raise HTTPException(status_code=404, detail=response(code="API_KEY_NOT_FOUND", message="API Key 不存在"))
    config["apiKeyRecords"] = records


def api_key_public_records(config: dict[str, Any]) -> list[dict[str, Any]]:
    records = []
    for item in config.get("apiKeyRecords", []):
        if not isinstance(item, dict):
            continue
        records.append({key: item.get(key) for key in ("id", "name", "permissions", "enabled", "createdAt", "lastUsedAt", "revokedAt")})
    return sorted(records, key=lambda value: bounded_config_int(value.get("createdAt", 0), 0, 0), reverse=True)


def resolve_snapshot_path(filename: str) -> Path:
    """校验快照文件名并返回数据卷内的实际路径。"""
    if not re.fullmatch(r"reamicro-server-[0-9TZ-]+\.zip", filename):
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="备份文件名无效"))
    path = (runtime.SERVER_BACKUP_ROOT / filename).resolve()
    if path.parent != runtime.SERVER_BACKUP_ROOT.resolve() or not path.is_file():
        raise HTTPException(status_code=404, detail=response(code="BACKUP_NOT_FOUND", message="服务器快照不存在"))
    return path


def restore_server_snapshot(filename: str) -> dict[str, Any]:
    """校验并恢复服务器快照；恢复前先自动创建一份安全快照。"""
    path = resolve_snapshot_path(filename)
    verification = verify_server_snapshot(path)
    if not verification.get("valid"):
        raise HTTPException(status_code=409, detail=response(code="BACKUP_INVALID", message="备份完整性校验失败"))
    safety_snapshot = create_server_snapshot()
    with tempfile.TemporaryDirectory(prefix="reamicro-restore-") as directory:
        extract_root = Path(directory)
        with zipfile.ZipFile(path) as archive:
            archive.extractall(extract_root)
        restored_db = extract_root / "state" / "reamicro.sqlite3"
        restored_config = extract_root / "config" / "server.json"
        if not restored_db.is_file() or not restored_config.is_file():
            raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="备份缺少数据库或配置"))
        runtime.CONFIG_ROOT.mkdir(parents=True, exist_ok=True)
        runtime.STATE_DB_PATH.parent.mkdir(parents=True, exist_ok=True)
        Path(str(runtime.STATE_DB_PATH) + "-wal").unlink(missing_ok=True)
        Path(str(runtime.STATE_DB_PATH) + "-shm").unlink(missing_ok=True)
        shutil.copy2(restored_config, runtime.CONFIG_PATH)
        shutil.copy2(restored_db, runtime.STATE_DB_PATH)
    with runtime.state_store_lock:
        state_store = None
    return {"restored": True, "filename": filename, "safetySnapshot": safety_snapshot.name}


@app.get("/admin/api-keys")
async def admin_api_keys(credentials: HTTPBasicCredentials | None = Depends(basic_security)) -> dict[str, Any]:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    return response({"items": api_key_public_records(load_config())})


@app.post("/admin/api-keys")
async def admin_create_api_key(
    request: Request,
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    x_admin_csrf: str = Header(default=""),
) -> dict[str, Any]:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    config = load_config()
    require_admin_csrf(config, actor, x_admin_csrf)
    try:
        payload = await request.json()
    except ValueError:
        payload = {}
    if not isinstance(payload, dict):
        payload = {}
    record, plain = create_api_key_record(config, payload.get("name", ""), payload.get("permissions", ["read"]))
    save_config(config)
    audit_event("api_key_created", audit_actor(actor), metadata={"keyId": record["id"], "permissions": record["permissions"]})
    return response({"id": record["id"], "name": record["name"], "permissions": record["permissions"], "key": plain})


@app.post("/admin/api-keys/{key_id}/revoke")
async def admin_revoke_api_key(
    key_id: str,
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    x_admin_csrf: str = Header(default=""),
) -> dict[str, Any]:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    config = load_config()
    require_admin_csrf(config, actor, x_admin_csrf)
    revoke_api_key_record(config, key_id)
    save_config(config)
    audit_event("api_key_revoked", audit_actor(actor), metadata={"keyId": key_id})
    return response({"id": key_id, "revoked": True})


@app.post("/admin/backups/server", response_class=HTMLResponse)
async def admin_server_backup(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    current = load_config()
    require_admin_csrf(current, actor, csrf_token)
    require_admin_permission(actor, "backup:admin")
    try:
        snapshot = await asyncio.to_thread(create_server_snapshot)
        audit_event("server_snapshot_created", audit_actor(actor), success=True, metadata={"filename": snapshot.name})
        return HTMLResponse(admin_page(current, f"服务器快照已创建：{snapshot.name}", actor=actor, section="security"))
    except Exception as error:
        audit_event("server_snapshot_created", audit_actor(actor), success=False)
        return HTMLResponse(admin_page(current, f"服务器快照失败：{error}", actor=actor, section="security"), status_code=500)


@app.post("/admin/security/rotate-secret", response_class=HTMLResponse)
async def admin_rotate_secret(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    new_secret_key: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    current = load_config()
    require_admin_csrf(current, actor, csrf_token)
    try:
        count = await asyncio.to_thread(rotate_secret_key, new_secret_key)
        audit_event("secret_key_rotated", audit_actor(actor), metadata={"reencrypted": count})
        return HTMLResponse(admin_page(load_config(), f"服务器加密密钥已轮换并重新加密 {count} 项数据", actor=actor, section="security"))
    except Exception as error:
        audit_event("secret_key_rotation_failed", audit_actor(actor), success=False)
        return HTMLResponse(admin_page(current, f"密钥轮换失败：{error}", actor=actor, section="security"), status_code=400)


@app.get("/admin/backups/server")
async def admin_server_backups(credentials: HTTPBasicCredentials | None = Depends(basic_security)) -> JSONResponse:
    actor = require_admin(credentials)
    require_admin_permission(actor, "backup:admin")
    return JSONResponse(content=response({"items": list_server_snapshots()}))


@app.get("/admin/backups/server/{filename}")
async def admin_server_backup_download(filename: str, credentials: HTTPBasicCredentials | None = Depends(basic_security)) -> FileResponse:
    actor = require_admin(credentials)
    require_admin_permission(actor, "backup:admin")
    return FileResponse(resolve_snapshot_path(filename), media_type="application/zip", filename=filename)


def verify_server_snapshot(path: Path) -> dict[str, Any]:
    with zipfile.ZipFile(path) as archive:
        allowed = {"snapshot-manifest.json", "state/reamicro.sqlite3", "config/server.json"}
        if any(name not in allowed for name in archive.namelist()):
            raise ValueError("备份包含不允许的文件路径")
        manifest = json.loads(archive.read("snapshot-manifest.json").decode("utf-8"))
        results = []
        for item in manifest.get("files", []):
            data = archive.read(str(item.get("path", "")))
            digest = hashlib.sha256(data).hexdigest()
            results.append({"path": item.get("path"), "size": len(data), "sha256": digest, "valid": digest == item.get("sha256")})
        return {"valid": all(item["valid"] for item in results), "createdAt": manifest.get("createdAt"), "files": results}


@app.get("/admin/backups/server/{filename}/verify")
async def admin_verify_server_backup(filename: str, credentials: HTTPBasicCredentials | None = Depends(basic_security)) -> JSONResponse:
    actor = require_admin(credentials); require_admin_permission(actor, "backup:admin")
    path = resolve_snapshot_path(filename)
    try:
        result = verify_server_snapshot(path)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message=f"备份校验失败：{error}"))
    return JSONResponse(content=response(result), status_code=200 if result.get("valid") else 409)


@app.post("/admin/backups/server/{filename}/restore")
async def admin_restore_server_backup(filename: str, request: Request, credentials: HTTPBasicCredentials | None = Depends(basic_security), x_admin_csrf: str = Header(default="")) -> JSONResponse:
    actor = require_admin(credentials); require_primary_admin(actor); require_admin_permission(actor, "backup:admin"); require_admin_csrf(load_config(), actor, x_admin_csrf)
    try: payload = await request.json()
    except ValueError: payload = {}
    if not isinstance(payload, dict) or payload.get("confirm") is not True:
        raise HTTPException(status_code=400, detail=response(code="BACKUP_CONFIRM_REQUIRED", message="恢复操作必须明确 confirm=true"))
    result = await asyncio.to_thread(restore_server_snapshot, filename)
    audit_event("server_snapshot_restored", audit_actor(actor), metadata={"filename": filename, "safetySnapshot": result["safetySnapshot"]})
    return JSONResponse(content=response(result))


@app.post("/admin/security/api-keys/create", response_class=HTMLResponse)
async def admin_security_create_api_key(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    name: str = Form(""),
    # 权限用复选框提交多个同名字段。
    permissions: list[str] = Form(default=["read"]),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    """后台表单创建 API Key，与 JSON 接口共用同一套记录生成逻辑。"""
    actor = require_admin(credentials)
    require_primary_admin(actor)
    config = load_config()
    require_admin_csrf(config, actor, csrf_token)
    try:
        record, plain = create_api_key_record(config, name, permissions)
    except HTTPException as error:
        detail = error.detail
        message = detail.get("message", "创建失败") if isinstance(detail, dict) else str(detail)
        return admin_html(admin_page(config, f"创建 API Key 失败：{message}", actor=actor, section="security"), status_code=400)
    saved = save_config(config)
    audit_event("api_key_created", audit_actor(actor), metadata={"keyId": record["id"], "permissions": record["permissions"]})
    return admin_html(admin_page(
        saved,
        f"API Key「{record['name']}」已创建",
        actor=actor,
        secret_notice=f"{record['name']}（{record['id']}）的密钥：\n{plain}",
        section="security",
    ))


@app.post("/admin/security/api-keys/revoke", response_class=HTMLResponse)
async def admin_security_revoke_api_key(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    key_id: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    config = load_config()
    require_admin_csrf(config, actor, csrf_token)
    try:
        revoke_api_key_record(config, key_id.strip())
    except HTTPException:
        return admin_html(admin_page(config, "API Key 不存在", actor=actor, section="security"), status_code=404)
    saved = save_config(config)
    audit_event("api_key_revoked", audit_actor(actor), metadata={"keyId": key_id.strip()})
    return admin_html(admin_page(saved, f"API Key {key_id.strip()} 已吊销", actor=actor, section="security"))


@app.post("/admin/security/backups/verify", response_class=HTMLResponse)
async def admin_security_verify_backup(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    filename: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    config = load_config()
    require_admin_csrf(config, actor, csrf_token)
    require_admin_permission(actor, "backup:admin")
    try:
        path = resolve_snapshot_path(filename.strip())
        result = await asyncio.to_thread(verify_server_snapshot, path)
    except HTTPException as error:
        detail = error.detail
        message = detail.get("message", "校验失败") if isinstance(detail, dict) else str(detail)
        return admin_html(admin_page(config, f"快照校验失败：{message}", actor=actor, section="security"), status_code=error.status_code)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        return admin_html(admin_page(config, f"快照校验失败：{error}", actor=actor, section="security"), status_code=400)
    if result.get("valid"):
        message = f"快照 {filename.strip()} 完整性校验通过，共 {len(result.get('files', []))} 个文件"
    else:
        broken = "、".join(str(item.get("path")) for item in result.get("files", []) if not item.get("valid"))
        message = f"快照 {filename.strip()} 校验未通过，损坏文件：{broken or '未知'}"
    return admin_html(admin_page(config, message, actor=actor, section="security"), status_code=200 if result.get("valid") else 409)


@app.post("/admin/security/backups/restore", response_class=HTMLResponse)
async def admin_security_restore_backup(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    filename: str = Form(""),
    confirm: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    """后台表单恢复快照。恢复会覆盖数据库和配置，因此仅限主管理员并要求二次确认。"""
    actor = require_admin(credentials)
    require_primary_admin(actor)
    config = load_config()
    require_admin_csrf(config, actor, csrf_token)
    require_admin_permission(actor, "backup:admin")
    if confirm.strip() != filename.strip() or not filename.strip():
        return admin_html(
            admin_page(config, "恢复未执行：请在确认框中完整输入要恢复的快照文件名", actor=actor, section="security"),
            status_code=400,
        )
    try:
        result = await asyncio.to_thread(restore_server_snapshot, filename.strip())
    except HTTPException as error:
        detail = error.detail
        message = detail.get("message", "恢复失败") if isinstance(detail, dict) else str(detail)
        audit_event("server_snapshot_restored", audit_actor(actor), success=False, metadata={"filename": filename.strip()})
        return admin_html(admin_page(config, f"快照恢复失败：{message}", actor=actor, section="security"), status_code=error.status_code)
    audit_event("server_snapshot_restored", audit_actor(actor), metadata={"filename": filename.strip(), "safetySnapshot": result["safetySnapshot"]})
    return admin_html(admin_page(
        load_config(),
        f"已恢复快照 {result['filename']}；恢复前的数据已另存为 {result['safetySnapshot']}",
        actor=actor,
        section="security",
    ))


@app.post("/admin/tasks/action", response_class=HTMLResponse)
async def admin_task_action(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    task_id: str = Form(""),
    action: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_admin_permission(actor, "tasks:write")
    config = load_config()
    require_admin_csrf(config, actor, csrf_token)
    tasks = load_tasks()
    task = tasks.get(task_id)
    if not task:
        return admin_html(admin_page(config, "任务不存在", actor=actor, section="tasks"), status_code=404)
    if action == "delete":
        # 删除是不可逆操作，单独处理并保留审计记录。
        tasks.pop(task_id, None)
        save_tasks(tasks)
        audit_event("admin_task_action", audit_actor(actor), metadata={"taskId": task_id, "action": action})
        return admin_html(admin_page(config, f"任务 {task_id} 已删除", actor=actor, section="tasks"))
    try:
        message = apply_task_action(task, action)
    except ValueError:
        return admin_html(admin_page(config, "不支持的任务操作", actor=actor, section="tasks"), status_code=400)
    save_tasks(tasks)
    task_log(task_id, message)
    audit_event("admin_task_action", audit_actor(actor), metadata={"taskId": task_id, "action": action})
    return admin_html(admin_page(config, message, actor=actor, section="tasks"))


@app.post("/admin/credentials/action", response_class=HTMLResponse)
async def admin_credential_action(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    credential_id: str = Form(""),
    action: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    """后台对阅微同步密钥执行验证、启用停用和删除，与模块端接口共用底层逻辑。"""
    actor = require_admin(credentials)
    require_admin_permission(actor, "tasks:write")
    config = load_config()
    require_admin_csrf(config, actor, csrf_token)
    stored = load_credentials()
    record = stored.get(credential_id.strip())
    if not record:
        return admin_html(admin_page(config, "同步密钥不存在", actor=actor, section="tasks"), status_code=404)
    if action == "verify":
        try:
            secret = decrypt_secret(str(record.get("secretEncrypted", "")))
        except Exception:
            secret = {}
        token = str(secret.get("token", "")) if isinstance(secret, dict) else ""
        if not token:
            update_credential_health(credential_id.strip(), False, "密钥密文无法解密")
            return admin_html(admin_page(config, "验证失败：密钥密文无法解密，请让模块重新上传", actor=actor, section="tasks"), status_code=409)
        success, detail = await verify_reamicro_secret(token, "")
        update_credential_health(credential_id.strip(), success, detail)
        audit_event("admin_credential_verified", audit_actor(actor), success=success, metadata={"credentialId": credential_id.strip()})
        return admin_html(admin_page(load_config(), f"{'验证通过' if success else '验证失败'}：{detail}", actor=actor, section="tasks"), status_code=200 if success else 409)
    if action == "toggle":
        record = dict(record)
        record["enabled"] = not bool(record.get("enabled", True))
        record["updatedAt"] = int(datetime.now(timezone.utc).timestamp() * 1000)
        stored[credential_id.strip()] = record
        save_credentials(stored)
        audit_event("admin_credential_toggled", audit_actor(actor), metadata={"credentialId": credential_id.strip(), "enabled": record["enabled"]})
        return admin_html(admin_page(load_config(), f"同步密钥已{'启用' if record['enabled'] else '停用'}", actor=actor, section="tasks"))
    if action == "delete":
        stored.pop(credential_id.strip(), None)
        save_credentials(stored)
        # 密钥删除后，依赖它的任务无法继续执行，一并暂停避免反复失败。
        tasks = load_tasks()
        affected = 0
        for task in tasks.values():
            if task_credential_id(task) == credential_id.strip() and task.get("enabled", True):
                task["enabled"] = False
                task["status"] = "paused"
                affected += 1
        if affected:
            save_tasks(tasks)
        audit_event("admin_credential_deleted", audit_actor(actor), metadata={"credentialId": credential_id.strip()})
        suffix = f"，并暂停了 {affected} 个关联任务" if affected else ""
        return admin_html(admin_page(load_config(), f"同步密钥 {credential_id.strip()} 已删除{suffix}", actor=actor, section="tasks"))
    return admin_html(admin_page(config, "不支持的密钥操作", actor=actor, section="tasks"), status_code=400)


@app.post("/admin/setup", response_class=HTMLResponse)
async def admin_setup(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    username: str = Form(""),
    password: str = Form(""),
    password_confirm: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials, allow_bootstrap=True)
    if not actor.get("needsSetup"):
        raise HTTPException(status_code=409, detail="主管理员已经完成初始化")
    require_admin_csrf(load_config(), actor, csrf_token)
    try:
        username = normalized_admin_name(username)
        validate_admin_password(password)
        if password != password_confirm:
            raise ValueError("两次输入的密码不一致")
        current = load_config()
        now = datetime.now(timezone.utc).isoformat()
        current["primaryAdmin"] = {
            "username": username,
            "passwordHash": password_hash(password),
            "createdAt": now,
            "updatedAt": now,
        }
        save_config(current)
        runtime.get_state_store().delete_admin_sessions_for_user(str(actor.get("username", "")))
        audit_event("primary_admin_initialized", f"admin:{username}", success=True)
        return HTMLResponse(
            "<!doctype html><html lang='zh-CN'><meta charset='utf-8'><title>初始化完成</title>"
            "<body style='font-family:system-ui;max-width:560px;margin:48px auto;padding:18px'>"
            "<h1>主管理员初始化完成</h1><p>初始环境变量密码已经停用。请关闭当前浏览器登录窗口，使用刚设置的新账号密码重新进入后台。</p>"
            "<p><a href='/admin'>重新进入后台</a></p></body></html>"
        )
    except ValueError as error:
        return HTMLResponse(admin_setup_page(str(error), actor=actor), status_code=400)


@app.post("/admin/settings", response_class=HTMLResponse)
async def admin_settings(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    server_id: str = Form(""),
    min_module_version: str = Form("2.0.0"),
    api_key: str = Form(""),
    allow_public: str | None = Form(None),
    host_account_allowlist: str = Form(""),
    account_name: str = Form(""),
    account_password: str = Form(""),
    remove_account: str = Form(""),
    features: str = Form(""),
    signing_public_key: str = Form(""),
    github_repository: str = Form("YGHFv/ReaMicro-Extend"),
    github_token: str = Form(""),
    auth_mode: str = Form(""),
    clear_api_key: str | None = Form(None),
    generate_api_key: str | None = Form(None),
    generate_secret_key: str | None = Form(None),
    clear_github_token: str | None = Form(None),
    github_include_prerelease: str | None = Form(None),
    release_sync_seconds: int = Form(1800),
    release_version_code: int = Form(0),
    server_snapshot_enabled: str | None = Form(None),
    server_snapshot_seconds: int = Form(86400),
    server_snapshot_retention: int = Form(30),
    module_upload_enabled: str | None = Form(None),
    module_upload_allowlist: str = Form(""),
    # 后台用复选框提交，未勾选任何类型时由 module_upload_kinds() 回退到默认集合。
    module_upload_kinds: list[str] = Form(default=[]),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    current = load_config()
    require_admin_csrf(current, actor, csrf_token)
    require_admin_permission(actor, "settings:write")
    if (generate_api_key is not None or generate_secret_key is not None or clear_api_key is not None or clear_github_token is not None) and actor.get("role") != "primary":
        require_admin_permission(actor, "security:write")
    generated_api_key = generate_long_secret() if generate_api_key is not None else ""
    generated_secret_key = ""
    secret_key_message = ""
    if generate_secret_key is not None:
        if current.get("secretKey"):
            secret_key_message = "服务器加密密钥已存在，本次未覆盖，避免历史阅微凭据失效"
        else:
            generated_secret_key = generate_long_secret()
    selected_auth_mode = auth_mode.strip() if auth_mode.strip() in {"public", "api_key", "account", "host_account_allowlist"} else infer_auth_mode(current)
    next_config = {
        "serverId": server_id.strip() or current["serverId"],
        "minModuleVersion": min_module_version.strip() or current["minModuleVersion"],
        "apiKey": "" if clear_api_key is not None else (generated_api_key or api_key or current.get("apiKey", "")),
        "secretKey": generated_secret_key or current.get("secretKey", "") or runtime.SECRET_KEY,
        "allowPublic": selected_auth_mode == "public",
        "authMode": selected_auth_mode,
        "hostAccountAllowlist": ([item.strip() for item in host_account_allowlist.splitlines() if item.strip()] if selected_auth_mode == "host_account_allowlist" else current.get("hostAccountAllowlist", [])),
        "features": [item.strip() for item in features.replace("\r", "\n").replace("\n", ",").split(",") if item.strip()],
        # 公钥从文本域提交，粘贴时常带换行和空格，去掉后再保存。
        "signingPublicKey": "".join(signing_public_key.split()),
        "githubRepository": github_repository.strip() or current["githubRepository"],
        "githubToken": "" if clear_github_token is not None else (github_token or current.get("githubToken", "")),
        "githubIncludePrerelease": github_include_prerelease is not None and str(github_include_prerelease).lower() not in {"", "0", "false"},
        "releaseSyncSeconds": release_sync_seconds,
        "releaseVersionCode": release_version_code,
        "serverSnapshotEnabled": server_snapshot_enabled is not None and str(server_snapshot_enabled).lower() not in {"", "0", "false"},
        "serverSnapshotSeconds": server_snapshot_seconds,
        "serverSnapshotRetention": server_snapshot_retention,
        "primaryAdmin": current.get("primaryAdmin", {}),
        "adminAccounts": current.get("adminAccounts", {}),
        "moduleUploadEnabled": module_upload_enabled is not None and str(module_upload_enabled).lower() not in {"", "0", "false"},
        "moduleUploadAllowlist": [item.strip() for item in module_upload_allowlist.replace(",", "\n").splitlines() if item.strip()],
        "moduleUploadKinds": module_upload_kinds_form(module_upload_kinds),
    }
    if generated_api_key:
        records = [item for item in current.get("apiKeyRecords", []) if isinstance(item, dict) and item.get("enabled", True)]
        records.append({
            "id": "key_" + secrets.token_hex(8),
            "name": "后台生成密钥",
            "digest": api_key_digest(generated_api_key),
            "permissions": ["read", "write"],
            "enabled": True,
            "createdAt": int(datetime.now(timezone.utc).timestamp() * 1000),
        })
        next_config["apiKeyRecords"] = records
    elif clear_api_key is not None:
        next_config["apiKeyRecords"] = []
    else:
        next_config["apiKeyRecords"] = current.get("apiKeyRecords", [])
    accounts = dict(current.get("accounts", {}))
    if account_name.strip() and account_password:
        accounts[account_name.strip()] = password_hash(account_password)
    if remove_account.strip():
        accounts.pop(remove_account.strip(), None)
    next_config["accounts"] = accounts
    saved = save_config(next_config)
    audit_event("admin_settings_updated", audit_actor(actor), success=True, metadata={"serverId": saved.get("serverId")})
    notices = []
    if generated_api_key:
        notices.append(f"新 API Key：{generated_api_key}")
    if generated_secret_key:
        notices.append(f"新服务器加密密钥：{generated_secret_key}")
    if secret_key_message:
        notices.append(secret_key_message)
    return HTMLResponse(admin_page(saved, "设置已保存", actor=actor, secret_notice="\n".join(notices), section="settings"))


@app.post("/admin/admins/create", response_class=HTMLResponse)
async def admin_create_subadmin(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    username: str = Form(""),
    password: str = Form(""),
    # 后台用复选框提交多个同名字段，同时兼容旧的逗号分隔单值写法。
    permissions: list[str] = Form(default=["settings:write", "packages:write", "tasks:write"]),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    current = load_config()
    require_admin_csrf(current, actor, csrf_token)
    try:
        username = normalized_admin_name(username)
        if username == str(current.get("primaryAdmin", {}).get("username", "")):
            raise ValueError("子管理员用户名不能与主管理员相同")
        admins = dict(current.get("adminAccounts", {}))
        if username in admins:
            raise ValueError("该子管理员已经存在")
        generated = not password
        password = password or generate_long_secret(24)
        validate_admin_password(password)
        now = datetime.now(timezone.utc).isoformat()
        admins[username] = {
            "passwordHash": password_hash(password),
            "enabled": True,
            "createdAt": now,
            "updatedAt": now,
            "createdBy": actor["username"],
            "permissions": normalized_admin_permissions(permissions),
        }
        current["adminAccounts"] = admins
        saved = save_config(current)
        audit_event("subadmin_created", audit_actor(actor), success=True, metadata={"username": username})
        notice = f"子管理员 {username} 的初始密码：{password}" if generated else ""
        return HTMLResponse(admin_page(saved, f"子管理员 {username} 已创建", actor=actor, secret_notice=notice, section="security"))
    except ValueError as error:
        return HTMLResponse(admin_page(current, str(error), actor=actor, section="security"), status_code=400)


@app.post("/admin/admins/reset", response_class=HTMLResponse)
async def admin_reset_subadmin(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    username: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    current = load_config()
    require_admin_csrf(current, actor, csrf_token)
    admins = dict(current.get("adminAccounts", {}))
    record = admins.get(username)
    if not isinstance(record, dict):
        return HTMLResponse(admin_page(current, "子管理员不存在", actor=actor, section="security"), status_code=404)
    password = generate_long_secret(24)
    record = dict(record)
    record["passwordHash"] = password_hash(password)
    record["updatedAt"] = datetime.now(timezone.utc).isoformat()
    admins[username] = record
    current["adminAccounts"] = admins
    saved = save_config(current)
    runtime.get_state_store().delete_admin_sessions_for_user(username)
    audit_event("subadmin_password_reset", audit_actor(actor), success=True, metadata={"username": username})
    return HTMLResponse(admin_page(saved, f"子管理员 {username} 的密码已重置", actor=actor, secret_notice=f"新密码：{password}", section="security"))


@app.post("/admin/admins/toggle", response_class=HTMLResponse)
async def admin_toggle_subadmin(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    username: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    current = load_config()
    require_admin_csrf(current, actor, csrf_token)
    admins = dict(current.get("adminAccounts", {}))
    record = admins.get(username)
    if not isinstance(record, dict):
        return HTMLResponse(admin_page(current, "子管理员不存在", actor=actor, section="security"), status_code=404)
    record = dict(record)
    record["enabled"] = not bool(record.get("enabled", True))
    record["updatedAt"] = datetime.now(timezone.utc).isoformat()
    admins[username] = record
    current["adminAccounts"] = admins
    saved = save_config(current)
    if not record["enabled"]:
        runtime.get_state_store().delete_admin_sessions_for_user(username)
    audit_event("subadmin_toggled", audit_actor(actor), success=True, metadata={"username": username, "enabled": record["enabled"]})
    state_text = "启用" if record["enabled"] else "停用"
    return HTMLResponse(admin_page(saved, f"子管理员 {username} 已{state_text}", actor=actor, section="security"))


@app.post("/admin/admins/delete", response_class=HTMLResponse)
async def admin_delete_subadmin(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    username: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    current = load_config()
    require_admin_csrf(current, actor, csrf_token)
    admins = dict(current.get("adminAccounts", {}))
    if username not in admins:
        return HTMLResponse(admin_page(current, "子管理员不存在", actor=actor, section="security"), status_code=404)
    admins.pop(username, None)
    current["adminAccounts"] = admins
    saved = save_config(current)
    runtime.get_state_store().delete_admin_sessions_for_user(username)
    audit_event("subadmin_deleted", audit_actor(actor), success=True, metadata={"username": username})
    return HTMLResponse(admin_page(saved, f"子管理员 {username} 已删除", actor=actor, section="security"))


@app.post("/admin/sync", response_class=HTMLResponse)
async def admin_sync(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_admin_csrf(load_config(), actor, csrf_token)
    require_admin_permission(actor, "module:sync")
    try:
        await asyncio.to_thread(sync_module_release)
        return HTMLResponse(admin_page(load_config(), "模块 Release 已同步", actor=actor, section="settings"))
    except Exception as error:
        return HTMLResponse(admin_page(load_config(), f"同步失败：{error}", actor=actor, section="settings"), status_code=502)




def comparable_version(value: Any) -> tuple[tuple[int, Any], ...]:
    parts = re.findall(r"\d+|[A-Za-z]+", str(value).lower())
    return tuple((1, int(part)) if part.isdigit() else (0, part) for part in parts)


def version_satisfies(version: Any, minimum: str = "", maximum: str = "") -> bool:
    current = comparable_version(version)
    return (not minimum or current >= comparable_version(minimum)) and (not maximum or current <= comparable_version(maximum))


def normalize_package_dependencies(items: Any, current_kind: str = "", current_package_id: str = "") -> list[dict[str, Any]]:
    if not isinstance(items, list):
        raise HTTPException(status_code=400, detail="依赖必须是 JSON 数组")
    normalized: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for item in items[:50]:
        if not isinstance(item, dict):
            raise HTTPException(status_code=400, detail="每项依赖必须是 JSON 对象")
        dependency_kind = str(item.get("kind", "")).strip()
        if dependency_kind and dependency_kind not in runtime.PACKAGE_KINDS:
            raise HTTPException(status_code=400, detail=f"依赖内容类型无效：{dependency_kind}")
        target = str(item.get("packageId") or item.get("contentId") or "").strip()
        if not target:
            raise HTTPException(status_code=400, detail="依赖缺少 packageId 或 contentId")
        target = safe_package_segment(target)
        if target == current_package_id and (not dependency_kind or dependency_kind == current_kind):
            raise HTTPException(status_code=400, detail="内容包不能依赖自身")
        minimum = str(item.get("minVersion", "")).strip()
        maximum = str(item.get("maxVersion", "")).strip()
        if minimum:
            minimum = safe_package_segment(minimum)
        if maximum:
            maximum = safe_package_segment(maximum)
        key = (dependency_kind, target)
        if key in seen:
            raise HTTPException(status_code=400, detail=f"依赖重复：{target}")
        seen.add(key)
        normalized.append({
            "kind": dependency_kind,
            "packageId": target,
            "minVersion": minimum,
            "maxVersion": maximum,
            "required": bool(item.get("required", True)),
        })
    return normalized


def published_package_manifests() -> list[dict[str, Any]]:
    manifests: list[dict[str, Any]] = []
    for kind in runtime.PACKAGE_KINDS:
        for path in (runtime.PACKAGE_ROOT / kind).glob("*/manifest.json"):
            try:
                manifest = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            if isinstance(manifest, dict) and str(manifest.get("status", "published")) == "published":
                manifest.setdefault("kind", kind)
                manifests.append(manifest)
    return manifests


def package_dependency_status(manifest: dict[str, Any]) -> dict[str, Any]:
    dependencies = normalize_package_dependencies(
        manifest.get("dependencies", []),
        str(manifest.get("kind", "")),
        str(manifest.get("packageId", "")),
    )
    available = published_package_manifests()
    resolved: list[dict[str, Any]] = []
    unresolved: list[dict[str, Any]] = []
    for dependency in dependencies:
        target = dependency["packageId"]
        candidates = []
        for candidate in available:
            identities = {
                str(candidate.get("packageId", "")),
                str(candidate.get("contentId", "")),
                *[str(alias) for alias in candidate.get("aliases", []) if alias],
            }
            if target not in identities or (dependency["kind"] and candidate.get("kind") != dependency["kind"]):
                continue
            candidates.append(candidate)
        compatible = [candidate for candidate in candidates if version_satisfies(candidate.get("version", ""), dependency["minVersion"], dependency["maxVersion"])]
        if compatible:
            selected = sorted(compatible, key=lambda item: comparable_version(item.get("version", "")), reverse=True)[0]
            resolved.append({**dependency, "resolvedKind": selected.get("kind"), "resolvedPackageId": selected.get("packageId"), "resolvedVersion": selected.get("version")})
        else:
            reason = "version_mismatch" if candidates else "not_found"
            unresolved.append({**dependency, "reason": reason, "availableVersions": sorted({str(item.get("version", "")) for item in candidates})})
    blocking = [item for item in unresolved if item.get("required", True)]
    return {"dependenciesSatisfied": not blocking, "resolvedDependencies": resolved, "unresolvedDependencies": unresolved}


def safe_package_segment(value: str) -> str:
    value = value.strip()
    if not value or len(value) > 120 or not value.replace("_", "").replace("-", "").replace(".", "").isalnum():
        raise HTTPException(status_code=400, detail="包标识只能包含字母、数字、点、下划线和短横线")
    return value


def safe_payload_filename(value: str) -> str:
    name = Path(value or "payload.bin").name
    name = "".join(char if (char.isalnum() or char in "._-") else "_" for char in name)
    return name[:120] or "payload.bin"


def normalize_content_id(value: str, fallback: str) -> str:
    """内容稳定 ID 不参与路径拼接，允许 URL/域名等源标识但拒绝控制字符。"""
    text = " ".join(str(value or fallback).replace("\r", " ").replace("\n", " ").split()).strip()
    if not text or len(text) > 240 or any(ord(char) < 32 for char in text):
        raise HTTPException(status_code=400, detail="稳定内容 ID 无效")
    return text


def _metadata_text(value: Any) -> str:
    """把内容元数据转成适合显示和匹配的单行文本。"""
    if value is None or isinstance(value, (dict, list)):
        return ""
    text = " ".join(str(value).replace("\r", " ").replace("\n", " ").split())
    return text[:160].strip()


def normalize_source_domain(value: Any) -> str:
    """把书源地址、域名或主机名统一成可比较的小写域名，无法识别时返回空串。"""
    text = _metadata_text(value)
    if not text:
        return ""
    if "://" in text:
        host = urllib.parse.urlsplit(text).netloc
    elif "/" in text:
        host = text.split("/", 1)[0]
    else:
        host = text
    host = host.rsplit("@", 1)[-1].strip().strip(".").lower()
    if host.startswith("["):
        host = host[1:].split("]", 1)[0]
    elif ":" in host:
        host = host.rsplit(":", 1)[0]
    if host.startswith("www."):
        host = host[4:]
    # 只有形如 example.com 或 IP 的值算域名，纯标识（youshu、qidian）不参与域名比对。
    if not host or " " in host or "." not in host:
        return ""
    if not re.fullmatch(r"[a-z0-9.\-]+", host):
        return ""
    return host[:120]


def normalize_match_name(value: Any) -> str:
    """内容名称的比较形式：折叠空白并忽略大小写。"""
    return " ".join(str(value or "").split()).casefold()


def package_match_domains(manifest: dict[str, Any]) -> set[str]:
    """内容包用于比对的域名集合，取自显式 domains 字段和历史 aliases、contentId。"""
    values: list[Any] = []
    for key in ("domains", "aliases"):
        entry = manifest.get(key)
        if isinstance(entry, (list, tuple, set)):
            values.extend(entry)
        elif entry:
            values.append(entry)
    values.append(manifest.get("contentId", ""))
    return {domain for domain in (normalize_source_domain(item) for item in values) if domain}


def package_match_identities(manifest: dict[str, Any]) -> set[str]:
    """内容包的稳定标识集合，供没有域名的关联源退化匹配使用。"""
    values = {str(manifest.get(key, "")).strip().casefold() for key in ("packageId", "contentId") if manifest.get(key)}
    aliases = manifest.get("aliases")
    if isinstance(aliases, (list, tuple, set)):
        values.update(str(item).strip().casefold() for item in aliases if str(item).strip())
    return {item for item in values if item}


def find_matching_package(kind: str, name: str, domains: set[str], identities: set[str]) -> dict[str, Any] | None:
    """按“名称 + 域名”定位服务器上的同一个源；没有域名时退化为“名称 + 稳定标识”。"""
    target_name = normalize_match_name(name)
    if not target_name:
        return None
    target_domains = {domain for domain in (normalize_source_domain(item) for item in domains) if domain}
    target_identities = {str(item).strip().casefold() for item in identities if str(item).strip()}
    for path, manifest in _package_manifests(kind):
        manifest.setdefault("kind", kind)
        manifest.setdefault("packageId", path.parent.name)
        if normalize_match_name(manifest.get("name", "")) != target_name:
            continue
        manifest_domains = package_match_domains(manifest)
        if target_domains and manifest_domains:
            if target_domains & manifest_domains:
                return manifest
            continue
        # 关联源等没有域名的内容只靠名称容易误判，要求稳定标识同时命中。
        if target_identities & package_match_identities(manifest):
            return manifest
    return None


def infer_package_metadata(kind: str, filename: str, body: bytes) -> dict[str, Any]:
    """从书源 JSON、归档清单或样式文件中提取外显名称、稳定标识和域名。"""
    stem = Path(filename or "payload").stem
    objects: list[dict[str, Any]] = []
    if filename.lower().endswith((".json", ".json5")):
        try:
            parsed = json.loads(body.decode("utf-8"))
            if isinstance(parsed, dict):
                objects = [parsed]
            elif isinstance(parsed, list):
                objects = [item for item in parsed[:20] if isinstance(item, dict)]
        except (UnicodeDecodeError, ValueError):
            objects = []
    elif filename.lower().endswith((".rmsource", ".apk", ".jar", ".zip")):
        # 关联源是 ZIP 归档，名称和 ID 写在内部的 manifest.json 里。
        try:
            with zipfile.ZipFile(io.BytesIO(body)) as archive:
                parsed = json.loads(archive.read("manifest.json").decode("utf-8"))
            if isinstance(parsed, dict):
                objects = [parsed]
        except (KeyError, OSError, UnicodeDecodeError, ValueError, zipfile.BadZipFile):
            objects = []
    name_keys = (
        "name", "title", "displayName", "label", "bookSourceName", "sourceName",
        "styleName", "themeName", "epubStyleName", "highlightStyleName",
    )
    identity_keys = (
        "contentId", "sourceId", "bookSourceId", "id", "key", "bookSourceUrl",
        "sourceUrl", "url", "domain", "host",
    )
    name = ""
    explicit_name = False
    identity: list[str] = []
    for item in objects:
        if not name:
            for key in name_keys:
                name = _metadata_text(item.get(key))
                if name:
                    explicit_name = True
                    break
        for key in identity_keys:
            value = _metadata_text(item.get(key))
            if value and value not in identity:
                identity.append(value)
    if not name and filename.lower().endswith(".css"):
        match = re.search(r"(?:^|[\*/#@])\s*(?:name|title)\s*[:=]\s*([^\r\n*]+)", body.decode("utf-8", errors="ignore"), re.IGNORECASE)
        if match:
            name = _metadata_text(match.group(1))
            explicit_name = bool(name)
    name = name or _metadata_text(stem) or _admin_kind_label(kind)
    if not identity:
        identity = [name]
    domains: list[str] = []
    for item in identity:
        domain = normalize_source_domain(item)
        if domain and domain not in domains:
            domains.append(domain)
    return {
        "name": name[:120],
        "identity": identity[:12],
        "explicitName": explicit_name,
        "domains": domains[:12],
    }


def package_name_slug(name: str, kind: str) -> str:
    normalized = unicodedata.normalize("NFKC", name or "")
    normalized = re.sub(r"[^\w.-]+", "-", normalized, flags=re.UNICODE).strip("-._")
    normalized = normalized[:80].strip("-._")
    return normalized or kind.replace("_", "-")


def _package_manifests(kind: str) -> list[tuple[Path, dict[str, Any]]]:
    values: list[tuple[Path, dict[str, Any]]] = []
    for path in sorted((runtime.PACKAGE_ROOT / kind).glob("*/manifest.json")):
        try:
            manifest = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if isinstance(manifest, dict):
            values.append((path, manifest))
    return values


def resolve_package_identity(kind: str, requested_id: str, requested_content_id: str, metadata: dict[str, Any]) -> tuple[str, dict[str, Any] | None]:
    """复用可识别的旧包，否则按已有 ID 列表分配不重复的新 ID。"""
    manifests = _package_manifests(kind)
    requested = requested_id.strip()
    if requested:
        package_id = safe_package_segment(requested)
        return package_id, next((item for path, item in manifests if path.parent.name == package_id), None)
    targets = {item.casefold() for item in metadata.get("identity", []) if item}
    if requested_content_id.strip():
        targets.add(requested_content_id.strip().casefold())
    name = str(metadata.get("name", "")).strip().casefold()
    for path, manifest in manifests:
        values = {str(manifest.get(key, "")).strip().casefold() for key in ("packageId", "contentId", "name") if manifest.get(key)}
        values.update(str(item).strip().casefold() for item in manifest.get("aliases", []) if item)
        if targets.intersection(values) or (name and name == str(manifest.get("name", "")).strip().casefold()):
            return safe_package_segment(str(manifest.get("packageId", path.parent.name))), manifest
    identity_seed = next((str(item) for item in metadata.get("identity", []) if item), "")
    identity_seed = re.sub(r"^[a-z]+://(?:www\.)?", "", identity_seed, flags=re.IGNORECASE).split("/")[0]
    base = package_name_slug(identity_seed or str(metadata.get("name", "")), kind)
    used = {path.parent.name for path, _ in manifests}
    candidate = base
    index = 2
    while candidate in used:
        suffix = f"-{index}"
        candidate = (base[:120 - len(suffix)] + suffix).strip("-._")
        index += 1
    return safe_package_segment(candidate), None


def next_package_version(current: Any) -> str:
    value = str(current or "").strip()
    numbers = re.findall(r"\d+", value)
    if not numbers:
        return "1.0.0"
    parts = [int(item) for item in numbers[:3]]
    while len(parts) < 3:
        parts.append(0)
    parts[2] += 1
    return ".".join(str(item) for item in parts)


def merge_package_aliases(existing: Any, detected: Any, requested: str) -> list[str]:
    values = []
    if isinstance(existing, list):
        values.extend(existing)
    if isinstance(detected, list):
        values.extend(detected)
    values.extend(requested.split(","))
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        text = _metadata_text(value)
        key = text.casefold()
        if not text or len(text) > 240 or key in seen:
            continue
        seen.add(key)
        result.append(text)
        if len(result) >= 50:
            break
    return result


def package_signature(package_id: str, kind: str, content_id: str, version: str, build_time: int, sha256: str, body: bytes) -> str:
    if not runtime.SIGNING_PRIVATE_KEY_FILE:
        return ""
    from cryptography.hazmat.primitives import serialization
    key = serialization.load_pem_private_key(Path(runtime.SIGNING_PRIVATE_KEY_FILE).read_bytes(), password=None)
    message = f"{package_id}\n{kind}\n{content_id}\n{version}\n{build_time}\n{sha256}".encode("utf-8") + body
    import base64
    return base64.b64encode(key.sign(message)).decode("ascii")


def persist_package_payload(
    kind: str,
    package_id: str,
    version: str,
    content_id: str,
    filename: str,
    body: bytes,
    name: str,
    description: str,
    aliases: list[str],
    domains: list[str],
    status_value: str,
    channel: str,
    dependencies: list[dict[str, Any]],
    signature: str = "",
    owner: str = "",
) -> dict[str, Any]:
    """把内容包写入数据卷：旧版本进 history，清单与 payload 原子替换。"""
    package_dir = (runtime.PACKAGE_ROOT / kind / package_id).resolve()
    if runtime.PACKAGE_ROOT.resolve() not in package_dir.parents:
        raise HTTPException(status_code=400, detail="内容包路径无效")
    package_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = package_dir / "manifest.json"
    old_payload_path: Path | None = None
    if manifest_path.is_file():
        history = package_dir / "history"
        history.mkdir(exist_ok=True)
        old_version = "old"
        old_manifest: dict[str, Any] = {}
        try:
            old_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            old_version = safe_package_segment(old_manifest.get("version", "old"))
        except (OSError, ValueError):
            pass
        archive_dir = history / f"{old_version}-{int(datetime.now(timezone.utc).timestamp() * 1000)}"
        archive_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(manifest_path, archive_dir / "manifest.json")
        old_payload = package_dir / str(old_manifest.get("payload", ""))
        if old_payload.is_file() and old_payload.parent == package_dir:
            shutil.copy2(old_payload, archive_dir / old_payload.name)
            old_payload_path = old_payload
    build_time = int(datetime.now(timezone.utc).timestamp() * 1000)
    digest = hashlib.sha256(body).hexdigest()
    manifest = {
        "packageId": package_id,
        "kind": kind,
        "version": version,
        "buildTime": build_time,
        "schemaVersion": 1,
        "minModuleVersion": load_config()["minModuleVersion"],
        "sha256": digest,
        "signature": signature.strip() or package_signature(package_id, kind, content_id, version, build_time, digest, body),
        "payload": filename,
        "contentId": content_id,
        "aliases": aliases,
        "domains": domains,
        "name": name,
        "description": description,
        "status": status_value,
        "channel": channel,
        "dependencies": dependencies,
    }
    if owner:
        manifest["uploadOwner"] = owner
    if status_value == "published":
        dependency_status = package_dependency_status(manifest)
        if not dependency_status["dependenciesSatisfied"]:
            raise HTTPException(
                status_code=409,
                detail=response(code="PACKAGE_DEPENDENCIES_UNRESOLVED", message="内容包存在未满足的必需依赖，请先上传依赖或保存为草稿", data=dependency_status),
            )
    payload_path = package_dir / filename
    temp_payload = package_dir / ".payload.tmp"
    temp_payload.write_bytes(body)
    temp_payload.replace(payload_path)
    temp_manifest = package_dir / ".manifest.json.tmp"
    temp_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    temp_manifest.replace(manifest_path)
    if old_payload_path and old_payload_path != payload_path and old_payload_path.is_file():
        old_payload_path.unlink()
    return manifest


@app.post("/admin/packages/upload", response_class=HTMLResponse)
async def admin_upload_package(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    kind: str = Form(...),
    package_id: str = Form(""),
    version: str = Form(""),
    content_id: str = Form(""),
    name: str = Form(""),
    aliases: str = Form(""),
    signature: str = Form(""),
    status_value: str = Form("published"),
    channel: str = Form("stable"),
    dependencies: str = Form(""),
    payload: UploadFile = File(...),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    current_config = load_config()
    require_admin_csrf(current_config, actor, csrf_token)
    require_admin_permission(actor, "packages:write")
    if kind not in runtime.PACKAGE_KINDS:
        raise HTTPException(status_code=400, detail="不支持的内容包类型")
    filename = safe_payload_filename(payload.filename or "payload.bin")
    body = await payload.read()
    if not body or len(body) > 50 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="内容文件为空或超过 50 MB")
    validate_package_payload(kind, filename, body)
    metadata = infer_package_metadata(kind, filename, body)
    package_id, existing_manifest = resolve_package_identity(kind, package_id, content_id, metadata)
    if existing_manifest is not None and not version.strip():
        version = next_package_version(existing_manifest.get("version"))
    version = safe_package_segment(version or "1.0.0")
    stable_id = normalize_content_id(content_id, str(existing_manifest.get("contentId", "")) if existing_manifest and existing_manifest.get("contentId") else package_id)
    normalized_status = status_value.strip().lower() or "published"
    if normalized_status not in {"draft", "testing", "published", "unpublished"}:
        raise HTTPException(status_code=400, detail="内容包状态无效")
    normalized_channel = channel.strip().lower() or "stable"
    if normalized_channel not in {"stable", "beta", "nightly"}:
        raise HTTPException(status_code=400, detail="内容包发布渠道无效")
    try:
        dependency_items = json.loads(dependencies) if dependencies.strip() else []
    except ValueError:
        raise HTTPException(status_code=400, detail="依赖必须是 JSON 数组")
    if not isinstance(dependency_items, list):
        raise HTTPException(status_code=400, detail="依赖必须是 JSON 数组")
    dependency_items = normalize_package_dependencies(dependency_items, kind, package_id)
    merged_domains = sorted({
        domain
        for domain in (
            normalize_source_domain(item)
            for item in list(metadata.get("domains", [])) + list(existing_manifest.get("domains", []) if existing_manifest else [])
        )
        if domain
    })
    manifest = persist_package_payload(
        kind=kind,
        package_id=package_id,
        version=version,
        content_id=stable_id,
        filename=filename,
        body=body,
        name=_metadata_text(name) or (str(existing_manifest.get("name", "")) if existing_manifest and not metadata.get("explicitName") else "") or str(metadata.get("name") or package_id),
        description=str(existing_manifest.get("description", "")) if existing_manifest and existing_manifest.get("description") else "后台上传内容包",
        aliases=merge_package_aliases(existing_manifest.get("aliases", []) if existing_manifest else [], metadata.get("identity", []), aliases),
        domains=merged_domains,
        status_value=normalized_status,
        channel=normalized_channel,
        dependencies=dependency_items,
        signature=signature,
        owner=str(existing_manifest.get("uploadOwner", "")) if existing_manifest else "",
    )
    audit_event("package_uploaded", audit_actor(actor), success=True, metadata={"kind": kind, "packageId": package_id, "version": version, "status": normalized_status})
    return admin_html(admin_page(load_config(), f"已保存 {_admin_kind_label(kind)}“{manifest['name']}” · {package_id} · {version}", actor=actor, section=kind))


@app.post("/admin/tasks/create", response_class=HTMLResponse)
async def admin_create_task(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    task_type: str = Form(...),
    interval_seconds: int = Form(86_400),
    request_url: str = Form(""),
    request_method: str = Form("POST"),
    request_headers: str = Form("{}"),
    request_body: str = Form(""),
    credential_id: str = Form(""),
    duration_minutes: int = Form(30),
    recent_limit: int = Form(1),
    book_limit: int = Form(1),
    time_of_day: str = Form("00:00"),
    owner: str = Form("admin"),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    current_config = load_config()
    require_admin_csrf(current_config, actor, csrf_token)
    require_admin_permission(actor, "tasks:write")
    try:
        task_type = task_type.strip()
        if task_type not in {"http", "yeshe_checkin", "yeshe_draw_card", "cloud_auto_read"}:
            raise ValueError("不支持的任务类型")
        owner = owner.strip() or "admin"
        headers = json.loads(request_headers or "{}")
        if not isinstance(headers, dict):
            raise ValueError("headers must be object")
        request_value = {
            "url": request_url.strip(),
            "method": request_method.strip().upper(),
            "headers": headers,
            "body": request_body,
            "credentialId": credential_id.strip(),
            "durationMinutes": max(1, min(duration_minutes, 720)),
            "recentLimit": max(1, min(recent_limit, 20)),
            "bookLimit": max(1, min(book_limit, 10)),
        }
        if task_type.strip() == "cloud_auto_read" and request_body.strip():
            decoded_body = json.loads(request_body)
            if not isinstance(decoded_body, list):
                raise ValueError("自定义图书必须是 JSON 数组")
            request_value["books"] = decoded_body
        if task_type != "http":
            credential = load_credentials().get(credential_id.strip())
            if not credential:
                raise ValueError("阅微凭据不存在")
            # 归属以密钥为准。后台表单的 owner 默认是 admin，而模块上传的密钥归属是
            # host:<阅微账号>，拿表单值去比对会让任何选了密钥的提交都失败。
            credential_owner = str(credential.get("owner", "")).strip()
            if credential_owner:
                owner = credential_owner
        task_id = "task_" + secrets.token_hex(10)
        now = int(datetime.now(timezone.utc).timestamp() * 1000)
        tasks = load_tasks()
        new_task = {
            "id": task_id,
            "owner": owner,
            "taskType": task_type,
            "credentialId": credential_id.strip(),
            "schedule": {
                "intervalSeconds": max(interval_seconds, 60),
                "timeOfDay": normalized_time_of_day(time_of_day) if task_type in {"yeshe_checkin", "yeshe_draw_card", "cloud_auto_read"} else "",
                "timezoneOffsetMinutes": 480,
            },
            "requestEncrypted": encrypt_secret(request_value),
            "status": "scheduled",
            "enabled": True,
            "createdAt": now,
            "nextRunAt": now,
            "runCount": 0,
            "maxRetries": 3,
            "consecutiveFailures": 0,
        }
        duplicate = find_duplicate_task(tasks, new_task)
        if duplicate:
            task_id = duplicate[0]
            previous = duplicate[1]
            new_task["id"] = task_id
            new_task["createdAt"] = previous.get("createdAt", now)
            new_task["updatedAt"] = now
            for field in ("executionHistory", "lastExecution", "runCount", "consecutiveFailures", "dailyCounterDate", "dailyCounter", "dailyReadDate", "dailyReadMinutes", "bookRotation", "lastCheckinDate", "lastCheckinAt", "claimDueAt", "claimRetryCount", "claimFinalAttemptDate", "claimCompletedDate", "claimFinalFailedDate", "lastClaimAt"):
                if field in previous:
                    new_task[field] = previous[field]
        tasks[task_id] = new_task
        save_tasks(tasks)
        task_log(task_id, "管理员更新任务" if duplicate else "管理员创建任务")
        return HTMLResponse(admin_page(load_config(), f"任务 {task_id} {'已更新' if duplicate else '已创建'}", actor=actor, section="tasks"))
    except Exception as error:
        return HTMLResponse(admin_page(load_config(), f"创建任务失败：{error}", actor=actor, section="tasks"), status_code=400)


@app.get("/v1/discovery")
async def discovery() -> dict[str, Any]:
    """公开能力发现，不返回 API Key、账号或白名单内容。"""
    return response(public_server_capabilities(load_config()))


@app.get("/v1/meta")
async def meta(_: None = Depends(authenticated)) -> dict[str, Any]:
    config = load_config()
    return response({**public_server_capabilities(config), "storage": {"state": "sqlite-wal", "multiHost": False}, "healthEndpoints": {"live": "/health/live", "ready": "/health/ready", "dependencies": "/health/dependencies"}})


@app.get("/v1/releases/module/latest")
async def latest_release(channel: str = Query("stable"), _: None = Depends(authenticated)) -> dict[str, Any]:
    metadata_path = runtime.RELEASE_ROOT / "latest.json"
    if not metadata_path.is_file():
        try:
            await asyncio.to_thread(sync_module_release)
        except Exception as error:
            return response(None, code="SYNC_FAILED", message=f"同步模块版本失败：{error}")
    if not metadata_path.is_file():
        return response(None, code="NOT_CONFIGURED", message="GitHub Release 中没有 APK")
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    release_channel = str(metadata.get("channel", "stable"))
    if channel not in {"stable", "beta", "nightly"}:
        raise HTTPException(status_code=400, detail=response(code="INVALID_CHANNEL", message="更新渠道无效"))
    if channel == "stable" and release_channel != "stable":
        raise HTTPException(status_code=404, detail=response(code="RELEASE_NOT_FOUND", message="没有可用的稳定版更新"))
    return response(metadata)


def github_webhook_signature(body: bytes) -> str:
    """按当前配置的 Webhook Secret 计算期望签名。抽出来便于单测覆盖比对逻辑。"""
    return "sha256=" + hmac.new(runtime.GITHUB_WEBHOOK_SECRET.encode("utf-8"), body, hashlib.sha256).hexdigest()


@app.post("/v1/webhooks/github")
async def github_release_webhook(request: Request) -> dict[str, Any]:
    """GitHub Release webhook。签名计算见 github_webhook_signature。"""
    if not runtime.GITHUB_WEBHOOK_SECRET:
        raise HTTPException(status_code=503, detail=response(code="WEBHOOK_DISABLED", message="未配置 GitHub Webhook Secret"))
    body = await request.body()
    provided = request.headers.get("X-Hub-Signature-256", "")
    expected = github_webhook_signature(body)
    if not provided or not hmac.compare_digest(provided, expected):
        audit_event("github_webhook_rejected", success=False)
        raise HTTPException(status_code=401, detail=response(code="WEBHOOK_SIGNATURE_INVALID", message="Webhook 签名无效"))
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise HTTPException(status_code=400, detail=response(code="WEBHOOK_INVALID", message="Webhook 内容无效"))
    event = request.headers.get("X-GitHub-Event", "")
    action = str(payload.get("action", "")) if isinstance(payload, dict) else ""
    if event == "release" and action in {"published", "released", "prereleased", "edited"}:
        await asyncio.to_thread(sync_module_release)
        audit_event("github_release_synced", metadata={"action": action})
        return response({"accepted": True, "synced": True})
    return response({"accepted": True, "synced": False})


@app.post("/v1/backups/module")
async def upload_module_backup(request: Request, owner: str = Depends(backup_owner)) -> dict[str, Any]:
    body = await request.body()
    if not body or len(body) > 20 * 1024 * 1024:
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="备份为空或超过 20 MB"))
    if not body.startswith(b"PK"):
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="备份不是 ZIP 文件"))
    owner_dir = runtime.BACKUP_ROOT / owner / "module"
    owner_dir.mkdir(parents=True, exist_ok=True)
    created_at = int(datetime.now(timezone.utc).timestamp() * 1000)
    target = owner_dir / f"{created_at}.zip"
    temp = owner_dir / f".{created_at}.tmp"
    temp.write_bytes(body)
    temp.replace(target)
    (owner_dir / "latest.json").write_text(json.dumps({
        "createdAt": created_at,
        "file": target.name,
        "size": len(body),
        "sha256": hashlib.sha256(body).hexdigest(),
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    backups = sorted(owner_dir.glob("*.zip"), reverse=True)
    for expired in backups[20:]:
        expired.unlink(missing_ok=True)
    return response({"createdAt": created_at, "size": len(body)})


@app.get("/v1/backups/module/latest")
async def download_module_backup(owner: str = Depends(backup_owner)) -> FileResponse:
    owner_dir = runtime.BACKUP_ROOT / owner / "module"
    metadata_path = owner_dir / "latest.json"
    if not metadata_path.is_file():
        raise HTTPException(status_code=404, detail=response(code="BACKUP_NOT_FOUND", message="没有模块设置备份"))
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    target = (owner_dir / str(metadata.get("file", ""))).resolve()
    if target.parent != owner_dir.resolve() or not target.is_file():
        raise HTTPException(status_code=404, detail=response(code="BACKUP_NOT_FOUND", message="备份文件不存在"))
    return FileResponse(target, media_type="application/zip", filename="reamicro-module-backup.zip")


@app.post("/v1/backups/credentials")
async def upload_credentials_backup(request: Request, owner: str = Depends(backup_owner)) -> dict[str, Any]:
    body = await request.body()
    if not body or len(body) > 10 * 1024 * 1024:
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="密钥备份为空或超过 10 MB"))
    if not body.startswith(b"RCRED1\n"):
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="密钥备份格式无效"))
    owner_dir = runtime.SECRET_BACKUP_ROOT / owner
    owner_dir.mkdir(parents=True, exist_ok=True)
    created_at = int(datetime.now(timezone.utc).timestamp() * 1000)
    target = owner_dir / f"{created_at}.bin"
    temp = owner_dir / f".{created_at}.tmp"
    temp.write_bytes(body)
    temp.replace(target)
    (owner_dir / "latest.json").write_text(json.dumps({
        "createdAt": created_at,
        "file": target.name,
        "size": len(body),
        "sha256": hashlib.sha256(body).hexdigest(),
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    for expired in sorted(owner_dir.glob("*.bin"), reverse=True)[10:]:
        expired.unlink(missing_ok=True)
    return response({"createdAt": created_at, "size": len(body), "encrypted": True})


@app.get("/v1/backups/credentials/latest")
async def download_credentials_backup(owner: str = Depends(backup_owner)) -> FileResponse:
    owner_dir = runtime.SECRET_BACKUP_ROOT / owner
    metadata_path = owner_dir / "latest.json"
    if not metadata_path.is_file():
        raise HTTPException(status_code=404, detail=response(code="BACKUP_NOT_FOUND", message="没有账号密钥备份"))
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    target = (owner_dir / str(metadata.get("file", ""))).resolve()
    if target.parent != owner_dir.resolve() or not target.is_file():
        raise HTTPException(status_code=404, detail=response(code="BACKUP_NOT_FOUND", message="密钥备份文件不存在"))
    return FileResponse(target, media_type="application/octet-stream", filename="reamicro-credentials.rcbak")




async def verify_reamicro_secret(token: str, base_url: str) -> tuple[bool, str]:
    status_code, _, raw = await asyncio.to_thread(
        json_http_request,
        base_url,
        token,
        {"pageNum": 1, "pageSize": 1},
        "rest/read/get-reader-record-list",
    )
    if 200 <= status_code < 300:
        return True, "验证成功"
    return False, f"HTTP {status_code} {redact_message(raw)}"


def update_credential_health(credential_id: str, success: bool, message: str, used: bool = False) -> None:
    credentials = load_credentials()
    credential = credentials.get(credential_id)
    if not credential:
        return
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    if used:
        credential["lastUsedAt"] = now
    if success:
        credential["lastVerifiedAt"] = now
        credential["lastVerifyMessage"] = message
        credential["verifyFailures"] = 0
    else:
        credential["lastFailedAt"] = now
        credential["lastVerifyMessage"] = message
        credential["verifyFailures"] = int(credential.get("verifyFailures", 0)) + 1
        if credential["verifyFailures"] >= 3:
            credential["enabled"] = False
    credential["updatedAt"] = now
    save_credentials(credentials)




@app.get("/v1/credentials/reamicro")
async def list_reamicro_credentials(owner: str = Depends(task_owner)) -> dict[str, Any]:
    items = [credential_public(item) for item in load_credentials().values() if item.get("owner") == owner and item.get("type") == "reamicro"]
    items.sort(key=lambda item: int(item.get("updatedAt", 0)), reverse=True)
    return response({"items": items})


@app.post("/v1/credentials/reamicro")
async def save_reamicro_credential(request: Request, owner: str = Depends(task_owner)) -> dict[str, Any]:
    try:
        payload = await request.json()
    except ValueError:
        raise HTTPException(status_code=400, detail=response(code="CREDENTIAL_INVALID", message="凭据 JSON 无效"))
    token = str(payload.get("token", "")).strip() if isinstance(payload, dict) else ""
    idempotency_key = request.headers.get("Idempotency-Key", "").strip()
    if idempotency_key:
        if len(idempotency_key) > 128:
            raise HTTPException(status_code=400, detail=response(code="IDEMPOTENCY_INVALID", message="Idempotency-Key 过长"))
        previous = runtime.get_state_store().get_idempotency(owner, "save_credential", idempotency_key)
        if previous:
            return previous
    if len(token) < 16 or len(token) > 8_192:
        raise HTTPException(status_code=400, detail=response(code="CREDENTIAL_INVALID", message="阅微登录密钥长度无效"))
    base_url = str(payload.get("baseUrl") or "https://api.reamicro.zhendong.ltd/").strip()
    if not base_url.startswith("https://"):
        raise HTTPException(status_code=400, detail=response(code="CREDENTIAL_INVALID", message="阅微 API 地址必须使用 HTTPS"))
    verified, verify_message = await verify_reamicro_secret(token, base_url)
    if not verified:
        raise HTTPException(status_code=400, detail=response(code="CREDENTIAL_VERIFY_FAILED", message=f"阅微登录密钥验证失败：{verify_message}"))
    account_id = str(payload.get("accountId") or "")[:100]
    owner_account_id = owner_host_account_id(owner)
    if owner_account_id and account_id and account_id != owner_account_id:
        raise HTTPException(status_code=403, detail=response(code="ACCOUNT_ID_MISMATCH", message="上传凭据的阅微账号与当前连接账号不一致"))
    if owner_account_id:
        account_id = owner_account_id
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    credentials = load_credentials()
    requested_id = str(payload.get("id", "")).strip()
    requested_existing = credentials.get(requested_id) if requested_id else None
    # 旧客户端可能携带上一个账号的 ID，禁止因此覆盖其他账号的同步密钥。
    if (
        isinstance(requested_existing, dict)
        and requested_existing.get("owner") == owner
        and account_id
        and str(requested_existing.get("accountId", "")) != account_id
    ):
        requested_id = ""
    matching_items = [
        (key, item) for key, item in credentials.items()
        if item.get("owner") == owner
        and item.get("type", "reamicro") == "reamicro"
        and account_id
        and str(item.get("accountId", "")) == account_id
    ]
    matching_items.sort(key=lambda pair: (bounded_config_int(pair[1].get("updatedAt", 0), 0, 0), pair[0]), reverse=True)
    matching_id = matching_items[0][0] if matching_items else ""
    # 账号 ID 是同步密钥的唯一业务键；优先复用按账号找到的记录，兼容旧客户端携带的过期 ID。
    credential_id = matching_id or requested_id or "rea_" + secrets.token_hex(10)
    existing = credentials.get(credential_id, {})
    if existing and existing.get("owner") != owner:
        raise HTTPException(status_code=404, detail=response(code="CREDENTIAL_NOT_FOUND", message="凭据不存在"))
    credentials[credential_id] = {
        "id": credential_id,
        "owner": owner,
        "type": "reamicro",
        "label": str(payload.get("label") or "阅微账号")[:100],
        "accountId": account_id,
        "secretEncrypted": encrypt_secret({"token": token, "baseUrl": base_url}),
        "createdAt": int(existing.get("createdAt", now)),
        "updatedAt": now,
        "lastVerifiedAt": now,
        "lastVerifyMessage": "验证成功",
        "lastUsedAt": int(existing.get("lastUsedAt", 0)),
        "lastFailedAt": int(existing.get("lastFailedAt", 0)),
        "verifyFailures": 0,
        "enabled": True,
    }
    duplicate_ids = [
        key for key, item in credentials.items()
        if key != credential_id
        and item.get("owner") == owner
        and item.get("type") == "reamicro"
        and account_id
        and str(item.get("accountId", "")) == account_id
    ]
    for duplicate_id in duplicate_ids:
        credentials.pop(duplicate_id, None)
    save_credentials(credentials)
    if duplicate_ids:
        tasks = load_tasks()
        rebound = False
        for task in tasks.values():
            if task.get("owner") != owner or not task.get("requestEncrypted"):
                continue
            try:
                task_request = decrypt_secret(str(task["requestEncrypted"]))
            except Exception:
                continue
            if not isinstance(task_request, dict) or str(task_request.get("credentialId", "")) not in duplicate_ids:
                continue
            task_request["credentialId"] = credential_id
            task["credentialId"] = credential_id
            task["requestEncrypted"] = encrypt_secret(task_request)
            task["updatedAt"] = now
            rebound = True
        if rebound:
            save_tasks(tasks)
        audit_event("credentials_merged", f"owner:{owner}", metadata={"accountId": account_id, "keptCredentialId": credential_id, "removedCount": len(duplicate_ids)})
    audit_event("credential_uploaded", f"owner:{owner}", metadata={"credentialId": credential_id, "accountId": account_id, "type": "reamicro"})
    result = response(credential_public(credentials[credential_id]))
    if idempotency_key:
        runtime.get_state_store().save_idempotency(owner, "save_credential", idempotency_key, result, now)
    return result


@app.delete("/v1/credentials/reamicro/{credential_id}")
async def delete_reamicro_credential(credential_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    credentials = load_credentials()
    credential = credentials.get(credential_id)
    if not credential or credential.get("owner") != owner:
        raise HTTPException(status_code=404, detail=response(code="CREDENTIAL_NOT_FOUND", message="凭据不存在"))
    credentials.pop(credential_id, None)
    save_credentials(credentials)
    tasks = load_tasks()
    changed = False
    for task in tasks.values():
        if task.get("owner") != owner or not task.get("requestEncrypted"):
            continue
        try:
            task_request = decrypt_secret(str(task["requestEncrypted"]))
        except Exception:
            continue
        if task_request.get("credentialId") == credential_id:
            task["enabled"] = False
            task["status"] = "paused"
            task["lastMessage"] = "关联的阅微凭据已删除"
            changed = True
    if changed:
        save_tasks(tasks)
    return response({"deleted": True})


@app.post("/v1/credentials/reamicro/{credential_id}/verify")
async def verify_reamicro_credential(credential_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    credentials = load_credentials()
    credential = credentials.get(credential_id)
    if not credential or credential.get("owner") != owner:
        raise HTTPException(status_code=404, detail=response(code="CREDENTIAL_NOT_FOUND", message="凭据不存在"))
    try:
        secret = decrypt_secret(str(credential.get("secretEncrypted", "")))
        token = str(secret.get("token", "")) if isinstance(secret, dict) else ""
        base_url = str(secret.get("baseUrl", "")) if isinstance(secret, dict) else ""
        verified, message = await verify_reamicro_secret(token, base_url)
    except Exception as error:
        verified, message = False, str(error)
    update_credential_health(credential_id, verified, message)
    current = load_credentials().get(credential_id, credential)
    if not verified:
        raise HTTPException(status_code=400, detail=response(code="CREDENTIAL_VERIFY_FAILED", message=message, data=credential_public(current)))
    return response(credential_public(current))


@app.post("/v1/credentials/reamicro/{credential_id}/toggle")
async def toggle_reamicro_credential(credential_id: str, request: Request, owner: str = Depends(task_owner)) -> dict[str, Any]:
    credentials = load_credentials()
    credential = credentials.get(credential_id)
    if not credential or credential.get("owner") != owner:
        raise HTTPException(status_code=404, detail=response(code="CREDENTIAL_NOT_FOUND", message="凭据不存在"))
    try:
        payload = await request.json()
    except ValueError:
        payload = {}
    credential["enabled"] = bool(payload.get("enabled", not credential.get("enabled", True))) if isinstance(payload, dict) else not credential.get("enabled", True)
    credential["updatedAt"] = int(datetime.now(timezone.utc).timestamp() * 1000)
    save_credentials(credentials)
    audit_event("credential_toggled", f"owner:{owner}", metadata={"credentialId": credential_id, "enabled": credential["enabled"]})
    return response(credential_public(credential))


@app.post("/v1/tasks")
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
    if task_type not in {"http", "yeshe_checkin", "yeshe_draw_card", "cloud_auto_read"}:
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="不支持的任务类型"))
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
    if schedule.get("timeOfDay"):
        schedule["timeOfDay"] = normalized_time_of_day(schedule.get("timeOfDay"))
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
        "nextRunAt": int(payload.get("nextRunAt", now)),
        "runCount": 0,
        "maxRetries": max(0, min(int(payload.get("maxRetries", 3) or 3), 10)),
        "consecutiveFailures": 0,
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
        for field in ("executionHistory", "lastExecution", "runCount", "consecutiveFailures", "dailyCounterDate", "dailyCounter", "dailyReadDate", "dailyReadMinutes", "bookRotation", "lastCheckinDate", "lastCheckinAt", "claimDueAt", "claimRetryCount", "claimFinalAttemptDate", "claimCompletedDate", "claimFinalFailedDate", "lastClaimAt"):
            if field in previous:
                task[field] = previous[field]
    tasks[task_id] = task
    save_tasks(tasks)
    task_log(task_id, "任务已创建")
    result = response(public_task(task))
    if idempotency_key:
        runtime.get_state_store().save_idempotency(owner, "create_task", idempotency_key, result, now)
    return result


@app.get("/v1/tasks")
async def list_tasks(owner: str = Depends(task_owner)) -> dict[str, Any]:
    merge_duplicate_tasks()
    tasks = [public_task(task, include_request=False) for task in load_tasks().values() if task.get("owner") == owner]
    return response({"items": tasks})


@app.get("/v1/notifications")
async def list_notifications(owner: str = Depends(task_owner)) -> dict[str, Any]:
    items = [item for item in load_notifications().values() if item.get("owner") == owner and not item.get("deliveredAt")]
    items.sort(key=lambda item: int(item.get("createdAt", 0)))
    return response({"items": items[-50:]})


@app.post("/v1/presence/heartbeat")
async def presence_heartbeat(request: Request, owner: str = Depends(task_owner)) -> dict[str, Any]:
    """记录模块在线租约，并返回下次应唤醒的时间。"""
    try:
        payload = await request.json()
    except ValueError:
        payload = {}
    payload = payload if isinstance(payload, dict) else {}
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    lease_until = now + 10 * 60_000
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
    tasks = [task for task in load_tasks().values() if task.get("owner") == owner and task.get("enabled", True) and task.get("status") not in {"paused", "cancelled"}]
    next_task_at = min((bounded_config_int(task.get("nextRunAt", 0), 0, 0) for task in tasks if bounded_config_int(task.get("nextRunAt", 0), 0, 0) > now), default=0)
    pending = [item for item in load_notifications().values() if item.get("owner") == owner and not item.get("deliveredAt")]
    pending.sort(key=lambda item: int(item.get("createdAt", 0)))
    return response({"onlineUntil": lease_until, "nextTaskAt": next_task_at, "pendingCount": len(pending), "notifications": pending[-50:]})


@app.post("/v1/notifications/ack")
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


def public_task(task: dict[str, Any], include_request: bool = False) -> dict[str, Any]:
    value = {key: item for key, item in task.items() if key != "requestEncrypted"}
    value["credentialId"] = task_credential_id(task)
    if include_request and task.get("requestEncrypted"):
        try:
            value["request"] = decrypt_secret(str(task["requestEncrypted"]))
        except Exception:
            value["request"] = {}
    return value


def validate_package_payload(kind: str, filename: str, body: bytes) -> None:
    if len(body) > 50 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="内容文件超过 50 MB")
    if kind in {"online_source", "association_source", "theme", "epub_style", "highlight_style"} and filename.lower().endswith((".json", ".json5")):
        try:
            value = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise HTTPException(status_code=400, detail="JSON 内容包格式无效")
        if not isinstance(value, (dict, list)):
            raise HTTPException(status_code=400, detail="JSON 内容包必须是对象或数组")
    if kind in {"epub_style", "highlight_style"} and filename.lower().endswith(".css"):
        try:
            body.decode("utf-8")
        except UnicodeDecodeError:
            raise HTTPException(status_code=400, detail="样式文件必须使用 UTF-8 编码")


def package_manifest(package_kind: str, package_id: str) -> tuple[Path, dict[str, Any]]:
    manifest_path = runtime.PACKAGE_ROOT / package_kind / package_id / "manifest.json"
    if not manifest_path.is_file():
        raise HTTPException(status_code=404, detail=response(code="PACKAGE_NOT_FOUND", message="内容包不存在"))
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise HTTPException(status_code=500, detail=response(code="PACKAGE_INVALID", message="内容包清单无效"))
    return manifest_path, manifest


def find_owned_task(task_id: str, owner: str) -> tuple[dict[str, dict[str, Any]], dict[str, Any]]:
    tasks = load_tasks()
    task = tasks.get(task_id)
    if not task or task.get("owner") != owner:
        raise HTTPException(status_code=404, detail=response(code="TASK_NOT_FOUND", message="任务不存在"))
    return tasks, task


@app.get("/v1/tasks/{task_id}")
async def get_task(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    _, task = find_owned_task(task_id, owner)
    return response(public_task(task))


@app.post("/v1/tasks/{task_id}/pause")
async def pause_task(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    tasks, task = find_owned_task(task_id, owner)
    task["status"] = "paused"
    task["enabled"] = False
    save_tasks(tasks)
    task_log(task_id, "任务已暂停")
    return response(public_task(task))


@app.post("/v1/tasks/{task_id}/resume")
async def resume_task(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    tasks, task = find_owned_task(task_id, owner)
    task["status"] = "scheduled"
    task["enabled"] = True
    task["nextRunAt"] = int(datetime.now(timezone.utc).timestamp() * 1000)
    save_tasks(tasks)
    task_log(task_id, "任务已恢复")
    return response(public_task(task))


@app.post("/v1/tasks/{task_id}/cancel")
async def cancel_task(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    tasks, task = find_owned_task(task_id, owner)
    task["status"] = "cancelled"
    task["enabled"] = False
    save_tasks(tasks)
    task_log(task_id, "任务已取消")
    return response(public_task(task))


@app.post("/v1/tasks/{task_id}/run")
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


@app.post("/v1/tasks/{task_id}/configure")
async def configure_task(task_id: str, request: Request, owner: str = Depends(task_owner)) -> dict[str, Any]:
    payload = await request.json()
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务配置无效"))
    tasks, task = find_owned_task(task_id, owner)
    if "schedule" in payload:
        schedule = payload.get("schedule")
        if not isinstance(schedule, dict):
            raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务 schedule 必须是对象"))
        if schedule.get("timeOfDay"):
            schedule["timeOfDay"] = normalized_time_of_day(schedule.get("timeOfDay"))
        task["schedule"] = schedule
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
    task["updatedAt"] = int(datetime.now(timezone.utc).timestamp() * 1000)
    duplicate = find_duplicate_task(tasks, task, exclude_id=task_id)
    if duplicate:
        tasks.pop(duplicate[0], None)
        audit_event("task_merged", f"owner:{owner}", metadata={"keptTaskId": task_id, "removedTaskId": duplicate[0], "reason": "unique_task_key"})
    save_tasks(tasks)
    task_log(task_id, "任务配置已更新")
    return response(public_task(task))


@app.delete("/v1/tasks/{task_id}")
async def delete_task(task_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    tasks, _ = find_owned_task(task_id, owner)
    tasks.pop(task_id, None)
    save_tasks(tasks)
    task_log(task_id, "任务已删除")
    return response({"deleted": True})


@app.get("/v1/tasks/{task_id}/logs")
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


@app.get("/admin/tasks/{task_id}/logs", response_class=HTMLResponse)
async def admin_task_logs(task_id: str, actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    task = load_tasks().get(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="任务不存在")
    esc = lambda value: html.escape(str(value), quote=True)
    history = task.get("executionHistory") if isinstance(task.get("executionHistory"), list) else []
    rows = []
    for item in history[-100:][::-1]:
        result = str(item.get("result", ""))
        rows.append(
            f"<tr><td>{esc(_admin_time_label(item.get('finishedAt'), '未记录'))}"
            f"<small>{esc(_admin_time_detail(item.get('finishedAt'), '未记录'))}</small></td>"
            f"<td><span class='status status-{esc(result)}'>{esc(_admin_result_label(result))}</span></td>"
            f"<td>{esc(_admin_duration_label(item.get('durationMs')))}</td>"
            f"<td>{esc(item.get('message', '') or '无执行说明')}</td></tr>"
        )
    table = "".join(rows) or "<tr><td colspan='4' class='empty'>暂无执行记录</td></tr>"
    summary_rows = "".join(
        f"<tr><th style='width:150px'>{esc(label)}</th><td>{esc(value)}</td></tr>"
        for label, value in (
            ("任务类型", _admin_task_label(task.get("taskType", ""))),
            ("任务 ID", task_id),
            ("来源账号", _admin_owner_label(task.get("owner", ""))),
            ("同步密钥", task_credential_id(task) or "未关联"),
            ("运行状态", _admin_task_status_label(task.get("status", ""))),
            ("任务详情", _admin_task_detail(task)),
            ("下次执行", _admin_time_detail(task.get("nextRunAt"))),
            ("累计执行", f"{bounded_config_int(task.get('runCount', 0), 0, 0)} 次"),
        )
    )
    # task_log() 写的逐行日志此前只有 /v1/tasks/{id}/logs 能取到，后台完全看不到，
    # 排查失败原因时只能看到执行历史里的一句汇总。这里一并展示。
    log_rows = []
    log_path = runtime.TASK_LOG_ROOT / f"{task_id}.log"
    if log_path.is_file():
        for line in log_path.read_text(encoding="utf-8").splitlines()[-200:][::-1]:
            try:
                entry = json.loads(line)
            except ValueError:
                continue
            level = str(entry.get("level", "INFO"))
            level_class = {"ERROR": "failed", "WARN": "warning"}.get(level, "success")
            log_rows.append(
                f"<tr><td>{esc(_admin_time_label(entry.get('at'), '未记录'))}"
                f"<small>{esc(_admin_time_detail(entry.get('at'), '未记录'))}</small></td>"
                f"<td><span class='status status-{level_class}'>{esc(level)}</span></td>"
                f"<td>{esc(entry.get('message', '') or '无内容')}</td></tr>"
            )
    log_table = "".join(log_rows) or "<tr><td colspan='3' class='empty'>暂无运行日志</td></tr>"
    body = (
        f"<div class='table-wrap' style='margin-bottom:18px'><table style='min-width:auto'><tbody>{summary_rows}</tbody></table></div>"
        f"<div class='panel'><h2>执行记录</h2><div class='table-wrap'><table><thead><tr><th>完成时间</th><th>结果</th><th>耗时</th><th>执行说明</th></tr></thead><tbody>{table}</tbody></table></div></div>"
        f"<div class='panel'><h2>运行日志</h2><p class='muted'>最近 200 条逐行日志，最新的在前。</p>"
        f"<div class='table-wrap'><table><thead><tr><th>时间</th><th>级别</th><th>内容</th></tr></thead><tbody>{log_table}</tbody></table></div></div>"
    )
    return HTMLResponse(_admin_shell(
        "执行日志 · " + _admin_task_label(task.get("taskType", "")),
        body,
        load_config(),
        actor,
        section="tasks",
        eyebrow="自动化",
        description=f"最近 {min(len(history), 100)} 条执行记录，最新的排在最前。",
        back_href="/admin/tasks",
        back_label="返回云端任务",
    ))


@app.get("/v1/releases/module/download")
async def download_release(request: Request, _: None = Depends(authenticated)) -> Response:
    apk_path = runtime.RELEASE_ROOT / "latest.apk"
    if not apk_path.is_file():
        raise HTTPException(status_code=404, detail=response(code="NOT_FOUND", message="模块 APK 尚未同步"))
    metadata_path = runtime.RELEASE_ROOT / "latest.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8")) if metadata_path.is_file() else {}
    etag = str(metadata.get("etag") or metadata.get("sha256") or hashlib.sha256(apk_path.read_bytes()).hexdigest())
    if request.headers.get("If-None-Match", "").strip('"') == etag:
        return Response(status_code=304, headers={"ETag": f'"{etag}"', "Accept-Ranges": "bytes"})
    size = apk_path.stat().st_size
    range_header = request.headers.get("Range", "")
    if range_header.startswith("bytes="):
        try:
            start_text, end_text = range_header[6:].split("-", 1)
            start = int(start_text or 0)
            end = min(int(end_text) if end_text else size - 1, size - 1)
            if start < 0 or start > end or start >= size:
                raise ValueError
        except ValueError:
            return Response(status_code=416, headers={"Content-Range": f"bytes */{size}"})
        with apk_path.open("rb") as stream:
            stream.seek(start)
            body = stream.read(end - start + 1)
        return Response(
            content=body,
            status_code=206,
            media_type="application/vnd.android.package-archive",
            headers={
                "Content-Range": f"bytes {start}-{end}/{size}",
                "Content-Length": str(len(body)),
                "Accept-Ranges": "bytes",
                "ETag": f'"{etag}"',
                "Content-Disposition": 'attachment; filename="ReaMicro-Extend-latest.apk"',
            },
        )
    return FileResponse(
        apk_path,
        media_type="application/vnd.android.package-archive",
        filename="ReaMicro-Extend-latest.apk",
        headers={"ETag": f'"{etag}"', "Accept-Ranges": "bytes"},
    )


@app.get("/v1/packages")
async def packages(kind: str = Query(...), _: None = Depends(authenticated)) -> dict[str, Any]:
    if not kind.replace("_", "").replace("-", "").isalnum():
        raise HTTPException(status_code=400, detail=response(code="INVALID_KIND", message="内容包类型无效"))
    items = []
    for manifest_path in sorted((runtime.PACKAGE_ROOT / kind).glob("*/manifest.json")):
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            if str(manifest.get("status", "published")) != "published":
                continue
            manifest.setdefault("kind", kind)
            manifest.setdefault("downloadUrl", f"/v1/packages/{kind}/{manifest_path.parent.name}/download")
            manifest.update(package_dependency_status(manifest))
            items.append(manifest)
        except (OSError, ValueError, HTTPException):
            continue
    return response({"kind": kind, "items": items})


@app.get("/v1/packages/upload/policy")
async def module_upload_policy_endpoint(
    request: Request,
    x_reamicro_api_key: str | None = Header(default=None),
    x_reamicro_account: str | None = Header(default=None),
    x_reamicro_password: str | None = Header(default=None),
    x_reamicro_host_account_id: str | None = Header(default=None),
) -> dict[str, Any]:
    """模块查询是否允许上传内容库；未获许可也返回 200，由客户端提示原因。"""
    check_request_auth(x_reamicro_api_key, x_reamicro_account, x_reamicro_password, x_reamicro_host_account_id)
    enforce_api_scope(request, x_reamicro_api_key)
    return response(module_upload_policy(load_config(), x_reamicro_host_account_id))


def parse_module_upload_item(item: Any) -> dict[str, Any]:
    """解析模块提交的单个源描述：类型、名称、域名和本地稳定标识。"""
    if not isinstance(item, dict):
        raise HTTPException(status_code=400, detail=response(code="INVALID_PAYLOAD", message="内容描述必须是对象"))
    kind = str(item.get("kind", "")).strip()
    if kind not in runtime.MODULE_UPLOAD_DEFAULT_KINDS:
        raise HTTPException(status_code=400, detail=response(code="INVALID_KIND", message="模块只能上传书源和关联源"))
    name = _metadata_text(item.get("name"))
    if not name:
        raise HTTPException(status_code=400, detail=response(code="INVALID_PAYLOAD", message="内容名称不能为空"))
    raw_domains = item.get("domains")
    if not isinstance(raw_domains, (list, tuple, set)):
        raw_domains = [raw_domains] if raw_domains else []
    domains = {domain for domain in (normalize_source_domain(value) for value in list(raw_domains)[:12]) if domain}
    raw_identities = item.get("identities")
    if not isinstance(raw_identities, (list, tuple, set)):
        raw_identities = [raw_identities] if raw_identities else []
    identities = {
        text
        for text in (_metadata_text(value)[:240] for value in list(raw_identities)[:12] + [item.get("contentId", "")])
        if text
    }
    return {"kind": kind, "name": name, "domains": domains, "identities": identities, "contentId": _metadata_text(item.get("contentId"))[:240]}


def module_package_summary(manifest: dict[str, Any], kind: str) -> dict[str, Any]:
    """回给模块的内容包摘要，只包含关联和后续更新需要的字段。"""
    package_id = str(manifest.get("packageId", ""))
    return {
        "kind": str(manifest.get("kind", kind)),
        "packageId": package_id,
        "contentId": str(manifest.get("contentId", "") or package_id),
        "version": str(manifest.get("version", "")),
        "buildTime": bounded_config_int(manifest.get("buildTime", 0), 0, 0),
        "name": str(manifest.get("name", "")),
        "status": str(manifest.get("status", "published")),
        "aliases": [str(value) for value in manifest.get("aliases", []) if str(value).strip()][:50],
        "domains": sorted(package_match_domains(manifest)),
        "downloadUrl": f"/v1/packages/{manifest.get('kind', kind)}/{package_id}/download",
    }


@app.post("/v1/packages/match")
async def match_packages(request: Request, owner: str = Depends(task_owner)) -> dict[str, Any]:
    """按“名称 + 域名”把本地书源和关联源与服务器内容库比对，返回可关联的内容包。"""
    payload = await request.json()
    items = payload.get("items") if isinstance(payload, dict) else payload
    if not isinstance(items, list):
        raise HTTPException(status_code=400, detail=response(code="INVALID_PAYLOAD", message="items 必须是数组"))
    if len(items) > 500:
        raise HTTPException(status_code=400, detail=response(code="INVALID_PAYLOAD", message="单次最多比对 500 个源"))
    results = []
    for item in items:
        parsed = parse_module_upload_item(item)
        manifest = find_matching_package(parsed["kind"], parsed["name"], parsed["domains"], parsed["identities"])
        entry: dict[str, Any] = {"kind": parsed["kind"], "name": parsed["name"], "contentId": parsed["contentId"], "matched": manifest is not None}
        if manifest is not None:
            entry["package"] = module_package_summary(manifest, parsed["kind"])
        results.append(entry)
    return response({"items": results, "matched": sum(1 for item in results if item["matched"])})


@app.post("/v1/packages/upload")
async def module_upload_package(request: Request, owner: str = Depends(module_upload_owner)) -> dict[str, Any]:
    """模块上传单个书源或关联源。服务器已存在同名同域的源时只关联，不覆盖内容。"""
    payload = await request.json()
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail=response(code="INVALID_PAYLOAD", message="请求体必须是对象"))
    config = load_config()
    parsed = parse_module_upload_item(payload)
    kind = parsed["kind"]
    if kind not in module_upload_kinds(config.get("moduleUploadKinds")):
        raise HTTPException(status_code=403, detail=response(code="MODULE_UPLOAD_FORBIDDEN", message=f"服务器未开放上传{_admin_kind_label(kind)}"))
    try:
        body = base64.b64decode(str(payload.get("payload", "")), validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(status_code=400, detail=response(code="INVALID_PAYLOAD", message="内容文件必须是 base64 编码"))
    if not body:
        raise HTTPException(status_code=400, detail=response(code="INVALID_PAYLOAD", message="内容文件为空"))
    if len(body) > runtime.MODULE_UPLOAD_MAX_BYTES:
        raise HTTPException(status_code=413, detail=response(code="PAYLOAD_TOO_LARGE", message=f"内容文件超过 {runtime.MODULE_UPLOAD_MAX_BYTES // (1024 * 1024)} MB"))
    filename = safe_payload_filename(str(payload.get("payloadName", "")) or f"{kind}.json")
    validate_package_payload(kind, filename, body)
    existing = find_matching_package(kind, parsed["name"], parsed["domains"], parsed["identities"])
    if existing is not None:
        # 同名同域视为同一个源：不覆盖服务器内容，只把关联信息回给模块用于后续更新。
        audit_event("module_upload_linked", actor=owner, success=True, metadata={"kind": kind, "packageId": existing.get("packageId", "")})
        return response(
            {"uploaded": False, "linked": True, "package": module_package_summary(existing, kind)},
            message="服务器已存在同名同域的源，已自动关联",
        )
    metadata = infer_package_metadata(kind, filename, body)
    detected_domains = sorted(parsed["domains"] | {
        domain for domain in (normalize_source_domain(value) for value in metadata.get("domains", [])) if domain
    })
    package_id, existing_manifest = resolve_package_identity(kind, "", parsed["contentId"], metadata)
    if existing_manifest is not None:
        # 标识命中旧包但名称或域名不同，同样只关联，避免模块改写他人内容。
        audit_event("module_upload_linked", actor=owner, success=True, metadata={"kind": kind, "packageId": package_id})
        return response(
            {"uploaded": False, "linked": True, "package": module_package_summary(existing_manifest, kind)},
            message="服务器已存在同一标识的源，已自动关联",
        )
    stable_id = normalize_content_id(parsed["contentId"], package_id)
    manifest = persist_package_payload(
        kind=kind,
        package_id=package_id,
        version="1.0.0",
        content_id=stable_id,
        filename=filename,
        body=body,
        name=parsed["name"],
        description=f"模块上传内容包 · 阅微账号 {module_upload_policy(config, request.headers.get('X-ReaMicro-Host-Account-Id'))['hostAccountId']}",
        aliases=merge_package_aliases([], list(metadata.get("identity", [])) + sorted(parsed["identities"]), ""),
        domains=detected_domains,
        status_value="published",
        channel="stable",
        dependencies=[],
        owner=owner,
    )
    audit_event("module_upload_created", actor=owner, success=True, metadata={"kind": kind, "packageId": package_id, "version": manifest.get("version", "")})
    return response(
        {"uploaded": True, "linked": True, "package": module_package_summary(manifest, kind)},
        message="内容已上传并关联",
    )


@app.get("/v1/packages/dependency-graph")
async def package_dependency_graph(_: None = Depends(authenticated)) -> dict[str, Any]:
    nodes = []
    edges = []
    for manifest in _admin_package_records():
        node_id = f"{manifest.get('kind')}:{manifest.get('packageId')}"
        nodes.append({"id": node_id, "kind": manifest.get("kind"), "packageId": manifest.get("packageId"), "version": manifest.get("version"), "status": manifest.get("status", "published")})
        for dependency in manifest.get("dependencies", []) if isinstance(manifest.get("dependencies"), list) else []:
            edges.append({"from": node_id, "to": f"{dependency.get('kind', '*')}:{dependency.get('packageId', '')}", "required": dependency.get("required", True), "minVersion": dependency.get("minVersion", ""), "maxVersion": dependency.get("maxVersion", "")})
    return response({"nodes": nodes, "edges": edges})


@app.get("/v1/packages/{package_kind}/{package_id}/history")
async def package_history(package_kind: str, package_id: str, _: None = Depends(authenticated)) -> dict[str, Any]:
    safe_package_segment(package_kind)
    safe_package_segment(package_id)
    package_dir = (runtime.PACKAGE_ROOT / package_kind / package_id).resolve()
    if runtime.PACKAGE_ROOT.resolve() not in package_dir.parents:
        raise HTTPException(status_code=400, detail=response(code="INVALID_PACKAGE", message="内容包路径无效"))
    history = []
    for manifest_path in sorted((package_dir / "history").glob("*/manifest.json"), reverse=True):
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            history.append({key: manifest.get(key) for key in ("packageId", "contentId", "version", "buildTime", "sha256", "status", "channel", "aliases")})
        except (OSError, ValueError):
            continue
    return response({"kind": package_kind, "packageId": package_id, "items": history})


@app.post("/v1/packages/{package_kind}/{package_id}/publish")
async def publish_package(
    package_kind: str,
    package_id: str,
    request: Request,
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    x_admin_csrf: str = Header(default=""),
) -> dict[str, Any]:
    actor = require_admin(credentials)
    require_admin_permission(actor, "packages:write")
    require_admin_csrf(load_config(), actor, x_admin_csrf)
    payload = await request.json()
    status_value = str(payload.get("status", "published")).strip().lower() if isinstance(payload, dict) else "published"
    if status_value not in {"draft", "testing", "published", "unpublished"}:
        raise HTTPException(status_code=400, detail=response(code="PACKAGE_INVALID", message="内容包状态无效"))
    manifest_path, manifest = package_manifest(package_kind, package_id)
    manifest.setdefault("kind", package_kind)
    dependency_status = package_dependency_status(manifest)
    if status_value == "published" and not dependency_status["dependenciesSatisfied"]:
        raise HTTPException(
            status_code=409,
            detail=response(code="PACKAGE_DEPENDENCIES_UNRESOLVED", message="内容包存在未满足的必需依赖", data=dependency_status),
        )
    manifest["status"] = status_value
    if isinstance(payload, dict) and payload.get("channel"):
        manifest["channel"] = str(payload["channel"]).strip().lower()
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    audit_event("package_status_changed", audit_actor(actor), metadata={"kind": package_kind, "packageId": package_id, "status": status_value})
    return response({"packageId": package_id, "status": manifest["status"], "channel": manifest.get("channel", "stable")})


@app.post("/v1/packages/batch-publish")
async def batch_publish_packages(
    request: Request,
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    x_admin_csrf: str = Header(default=""),
) -> dict[str, Any]:
    actor = require_admin(credentials)
    require_admin_permission(actor, "packages:write")
    require_admin_csrf(load_config(), actor, x_admin_csrf)
    payload = await request.json()
    items = payload.get("items", []) if isinstance(payload, dict) else []
    if not isinstance(items, list) or len(items) > 100:
        raise HTTPException(status_code=400, detail=response(code="PACKAGE_INVALID", message="items 必须是最多 100 项的数组"))
    results = []
    for item in items:
        if not isinstance(item, dict):
            continue
        try:
            kind = safe_package_segment(str(item.get("kind", ""))); package_id = safe_package_segment(str(item.get("packageId", "")))
            manifest_path, manifest = package_manifest(kind, package_id)
            desired = str(item.get("status", "published")).lower()
            if desired not in {"draft", "testing", "published", "unpublished"}:
                raise ValueError("状态无效")
            if desired == "published" and not package_dependency_status(manifest).get("dependenciesSatisfied", True):
                raise ValueError("依赖未满足")
            manifest["status"] = desired
            if item.get("channel"):
                channel = str(item.get("channel")).lower()
                if channel not in {"stable", "beta", "nightly"}: raise ValueError("渠道无效")
                manifest["channel"] = channel
            manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
            results.append({"kind": kind, "packageId": package_id, "status": desired})
        except (HTTPException, ValueError) as error:
            results.append({"kind": item.get("kind", ""), "packageId": item.get("packageId", ""), "error": str(error)})
    audit_event("packages_batch_published", audit_actor(actor), metadata={"count": len(results)})
    return response({"items": results})


@app.post("/v1/packages/{package_kind}/{package_id}/rollback")
async def rollback_package(
    package_kind: str,
    package_id: str,
    request: Request,
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    x_admin_csrf: str = Header(default=""),
) -> dict[str, Any]:
    actor = require_admin(credentials)
    require_admin_permission(actor, "packages:write")
    require_admin_csrf(load_config(), actor, x_admin_csrf)
    payload = await request.json()
    target_version = str(payload.get("version", "")).strip() if isinstance(payload, dict) else ""
    manifest_path, current = package_manifest(package_kind, package_id)
    package_dir = manifest_path.parent
    candidates = []
    for history_manifest in (package_dir / "history").glob("*/manifest.json"):
        try:
            item = json.loads(history_manifest.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if target_version and str(item.get("version")) != target_version:
            continue
        old_payload = history_manifest.parent / str(item.get("payload", ""))
        if old_payload.is_file():
            candidates.append((item, old_payload))
    if not candidates:
        raise HTTPException(status_code=404, detail=response(code="PACKAGE_VERSION_NOT_FOUND", message="历史版本不存在"))
    selected, old_payload = sorted(candidates, key=lambda pair: int(pair[0].get("buildTime", 0)), reverse=True)[0]
    selected.setdefault("kind", package_kind)
    dependency_status = package_dependency_status(selected)
    if not dependency_status["dependenciesSatisfied"]:
        raise HTTPException(
            status_code=409,
            detail=response(code="PACKAGE_DEPENDENCIES_UNRESOLVED", message="历史版本存在未满足的必需依赖，不能发布回滚", data=dependency_status),
        )
    current_payload = package_dir / str(current.get("payload", ""))
    if current_payload.is_file():
        current_payload.unlink()
    new_payload = package_dir / str(selected.get("payload", old_payload.name))
    new_payload.write_bytes(old_payload.read_bytes())
    selected["status"] = "published"
    selected["rolledBackFrom"] = current.get("version", "")
    manifest_path.write_text(json.dumps(selected, ensure_ascii=False, indent=2), encoding="utf-8")
    audit_event("package_rolled_back", audit_actor(actor), metadata={"kind": package_kind, "packageId": package_id, "version": selected.get("version")})
    return response({"packageId": package_id, "version": selected.get("version"), "rolledBack": True})


@app.get("/v1/packages/{package_kind}/{package_id}/download")
async def package_download(package_kind: str, package_id: str, _: None = Depends(authenticated)) -> FileResponse:
    if not package_kind.replace("_", "").replace("-", "").isalnum() or not package_id.replace("_", "").replace("-", "").isalnum():
        raise HTTPException(status_code=400, detail=response(code="INVALID_PACKAGE", message="内容包标识无效"))
    manifest_path = runtime.PACKAGE_ROOT / package_kind / package_id / "manifest.json"
    if not manifest_path.is_file():
        raise HTTPException(status_code=404, detail=response(code="NOT_FOUND", message="内容包不存在"))
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if str(manifest.get("status", "published")) != "published":
            raise HTTPException(status_code=404, detail=response(code="PACKAGE_NOT_PUBLISHED", message="内容包尚未发布"))
        manifest.setdefault("kind", package_kind)
        dependency_status = package_dependency_status(manifest)
        if not dependency_status["dependenciesSatisfied"]:
            raise HTTPException(
                status_code=409,
                detail=response(code="PACKAGE_DEPENDENCIES_UNRESOLVED", message="内容包必需依赖尚未满足", data=dependency_status),
            )
        payload = (manifest_path.parent / manifest["payload"]).resolve()
        if runtime.PACKAGE_ROOT.resolve() not in payload.parents:
            raise ValueError("invalid payload path")
    except HTTPException:
        raise
    except (OSError, ValueError, KeyError):
        raise HTTPException(status_code=500, detail=response(code="PACKAGE_INVALID", message="内容包清单无效"))
    if not payload.is_file():
        raise HTTPException(status_code=404, detail=response(code="NOT_FOUND", message="内容包文件不存在"))
    return FileResponse(payload, media_type="application/octet-stream", filename=payload.name)
