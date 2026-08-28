"""云端任务的实际执行。

野社签到分两步：先 complete-daily-lore 完成签到，签到 8 小时后奖励解锁，再走
rest/task/get-my-task-list + rest/task/receive-reward 领取每日任务奖励。
注意不要与文社**周**奖励（claim-literary-society-weekly-reward）搞混——那是另一个奖励，
早年误用它导致每天都拿到"上一周奖励已领取"并被判成可重试失败。
"""
import asyncio
import json
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from typing import Any

from app import runtime
from app.audit import audit_event
from app.config_store import bounded_config_int, load_config
from app.crypto import decrypt_secret, encrypt_secret
from app.packages import _metadata_text
from app.state import load_credentials, save_credentials, task_credential_id


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


def nested_value(value: Any, *keys: str) -> Any:
    current = value
    for key in keys:
        if isinstance(current, dict):
            current = current.get(key)
        else:
            return None
    return current


def reamicro_business_error(body: Any) -> str:
    """提取阅微 HTTP 200 响应里的业务错误。"""
    if not isinstance(body, dict) or "code" not in body:
        return ""
    raw_code = body.get("code")
    try:
        code = int(str(raw_code).strip())
    except (TypeError, ValueError):
        return f"阅微返回无法识别的业务码：{redact_message(str(raw_code))}"
    if code in (0, 200):
        return ""
    message = body.get("message") or body.get("msg") or "未提供错误说明"
    return f"阅微业务码 {code}：{redact_message(str(message))}"


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


# 每日任务状态（取自阅微 2.3.1 DailyTask.status 的界面分支）：
# 2 = 已完成待领取，1 = 已领取，0 = 未完成。
DAILY_TASK_STATUS_CLAIMABLE = 2


DAILY_TASK_STATUS_CLAIMED = 1


# get-my-task-list 的 queryType：日常任务传 1。
DAILY_TASK_QUERY_TYPE = 1


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
            recent_endpoint = str(request.get("recentEndpoint") or "rest/reader/get-read-record-list")
            status_code, record_body, raw = json_http_request(
                base_url,
                token,
                {"pageNum": 1, "pageSize": int(request.get("recentLimit", 1) or 1)},
                recent_endpoint,
            )
            if status_code < 200 or status_code >= 300:
                return ("paused", f"读取最近阅读记录失败 HTTP {status_code}") if status_code in (401, 403, 429) else ("failed", f"读取最近阅读记录失败 HTTP {status_code}: {redact_message(raw)}")
            business_error = reamicro_business_error(record_body)
            if business_error:
                return "failed", f"读取最近阅读记录失败：{business_error}"
            books = nested_value(record_body, "data", "list") or nested_value(record_body, "data") or []
        if isinstance(books, dict):
            books = [books]
        if not isinstance(books, list):
            books = []
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
            raw_cloud_id = book.get("cloudBookId") or book.get("bookId") or book.get("id")
            try:
                cloud_id = int(str(raw_cloud_id).strip())
            except (TypeError, ValueError):
                continue
            if cloud_id <= 0:
                continue
            china_date = datetime.now(timezone(timedelta(hours=8))).date().isoformat()
            time_payload = {"list": [{"bookId": cloud_id, "date": china_date, "duration": duration_seconds, "verify": ""}]}
            time_endpoint = str(request.get("timeEndpoint") or "rest/reader/update-read-time-by-date")
            time_status, time_body, time_raw = json_http_request(base_url, token, time_payload, time_endpoint)
            if time_status in (401, 403, 429):
                return "paused", f"上报阅读时长触发认证/风控 HTTP {time_status}"
            if time_status < 200 or time_status >= 300:
                return "failed", f"上报阅读时长失败 HTTP {time_status}: {redact_message(time_raw)}"
            business_error = reamicro_business_error(time_body)
            if business_error:
                return "failed", f"上报阅读时长失败：{business_error}"
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


async def verify_reamicro_secret(token: str, base_url: str) -> tuple[bool, str]:
    status_code, body, raw = await asyncio.to_thread(
        json_http_request,
        base_url,
        token,
        {"pageNum": 1, "pageSize": 1},
        "rest/reader/get-read-record-list",
    )
    if 200 <= status_code < 300:
        business_error = reamicro_business_error(body)
        if business_error:
            return False, business_error
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
