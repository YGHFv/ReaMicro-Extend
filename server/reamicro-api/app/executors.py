"""云端任务的实际执行。

野社签到分两步：先 complete-daily-lore 完成签到，签到 8 小时后奖励解锁，再用同一个
complete-daily-lore 接口和同一个 userLoreId 领取奖励。阅微界面的“领取奖励”按钮也是这条链路。
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


def claim_daily_lore_reward(base_url: str, token: str, request: dict[str, Any], user_lore_id: int) -> tuple[str, str]:
    """用阅微实际的每日轶闻完成接口领取签到奖励。

    返回 (结果, 面向用户的说明)。结果取值：
    - granted：本次领到了奖励
    - already：奖励此前已领取
    - locked：奖励还没解锁，等下一轮定时执行，不算失败
    - paused：命中认证或风控，任务应暂停
    - failed：真实错误，交由调用方走重试逻辑
    """
    endpoint = str(request.get("completeEndpoint") or "rest/community/complete-daily-lore")
    status_code, body, raw = json_http_request(base_url, token, {"userLoreId": user_lore_id}, endpoint)
    if status_code in (401, 403, 429):
        return "paused", f"阅微认证/风控响应 HTTP {status_code}"
    if status_code < 200 or status_code >= 300:
        return "failed", f"签到奖励领取失败 HTTP {status_code}: {redact_message(raw)}"

    message = nested_value(body, "data", "message") or nested_value(body, "message") or nested_value(body, "msg") or ""
    business_error = reamicro_business_error(body)
    if not business_error:
        return "granted", "签到奖励领取成功"
    if claim_reward_already_granted(message, body):
        return "already", "签到奖励此前已领取"
    if reward_not_ready(message):
        return "locked", "签到奖励尚未解锁，5 分钟后再次检查"
    return "failed", business_error


def claim_reward_already_granted(message: Any, body: Any = None) -> bool:
    """判断失败回复是否其实表示奖励早已领取。"""
    text = str(message or "")
    if any(pattern in text for pattern in CLAIM_ALREADY_GRANTED_PATTERNS):
        return True
    if any(pattern in text.lower() for pattern in ("already claimed", "already received")):
        return True
    for key in ("claimed", "isClaimed", "received", "hasClaimed"):
        if nested_value(body, "data", key) is True:
            return True
    return False


def reward_not_ready(message: Any) -> bool:
    text = str(message or "")
    return any(pattern in text for pattern in REWARD_NOT_READY_PATTERNS)


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


REWARD_NOT_READY_PATTERNS = (
    "未达到领取条件",
    "尚未解锁",
    "暂未解锁",
    "未到领取时间",
    "暂不可领取",
    "尚不可领取",
)


LOTTERY_BALANCE_EXHAUSTED_PATTERNS = (
    "彩筹不足",
    "彩筹不够",
    "没有足够彩筹",
    "余额不足",
    "insufficient balance",
)


def lottery_balance_exhausted(*values: Any) -> bool:
    """判断抽卡回复是否表示彩筹已经耗尽。"""
    text = " ".join(
        json.dumps(value, ensure_ascii=False) if isinstance(value, (dict, list)) else str(value or "")
        for value in values
    ).lower()
    return any(pattern in text for pattern in LOTTERY_BALANCE_EXHAUSTED_PATTERNS)


def timestamp_millis(value: Any) -> int:
    """兼容阅微响应中的秒级和毫秒级时间戳。"""
    try:
        timestamp = int(value or 0)
    except (TypeError, ValueError):
        return 0
    if timestamp <= 0:
        return 0
    return timestamp * 1000 if timestamp < 100_000_000_000 else timestamp


def daily_lore_reward_summary(lore: Any) -> str:
    """从每日轶闻响应提取领取通知需要的奖励明细。"""
    rewards: list[str] = []
    exp = bounded_config_int(nested_value(lore, "data", "exp"), 0, 0)
    gem = bounded_config_int(nested_value(lore, "data", "gem"), 0, 0)
    if exp > 0:
        rewards.append(f"阅历 {exp} 点")
    if gem > 0:
        rewards.append(f"彩筹 {gem} 枚")
    prop_name = str(nested_value(lore, "data", "propName") or "").strip()
    prop_quality = str(nested_value(lore, "data", "propQuality") or "").strip()
    if prop_name:
        rewards.append(f"{prop_name}（{prop_quality}）" if prop_quality else prop_name)
    return "、".join(rewards)


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
        daily_limit = min(bounded_config_int(request.get("dailyLimit", 3), 3, 0), 20)
        today = datetime.now(timezone(timedelta(hours=8))).date().isoformat()
        used_today = bounded_config_int(task.get("dailyCounter", 0), 0, 0) if task.get("dailyCounterDate") == today else 0
        if daily_limit > 0 and used_today >= daily_limit:
            return "success", f"野社今日抽卡已达到上限 {daily_limit} 次"
        if daily_limit == 0:
            status_code, user_info, raw = json_http_request(
                base_url,
                token,
                {},
                str(request.get("userInfoEndpoint") or "rest/user/get-user-info"),
            )
            if status_code in (401, 403, 429):
                return "paused", f"读取彩筹余额时触发阅微认证/风控响应 HTTP {status_code}"
            if status_code < 200 or status_code >= 300:
                return "failed", f"读取彩筹余额失败 HTTP {status_code}: {redact_message(raw)}"
            business_error = reamicro_business_error(user_info)
            if business_error:
                return "failed", f"读取彩筹余额失败：{business_error}"
            gem_value = nested_value(user_info, "data", "gem")
            if gem_value is None:
                return "failed", "阅微用户信息响应缺少彩筹余额 gem"
            draw_target = bounded_config_int(gem_value, 0, 0)
        else:
            draw_target = daily_limit - used_today
        endpoint = str(request.get("endpoint") or "rest/lottery/lottery-v2")
        summaries: list[str] = []
        balance_exhausted = False

        def completed_draw_detail() -> str:
            return "；".join(f"第 {index} 次：{item}" for index, item in enumerate(summaries, 1))

        while len(summaries) < draw_target:
            status_code, body, raw = json_http_request(base_url, token, configured_body, endpoint)
            if status_code in (401, 403, 429):
                detail = f"；已完成：{completed_draw_detail()}" if summaries else ""
                return "paused", f"阅微认证/风控响应 HTTP {status_code}{detail}"
            business_error = reamicro_business_error(body) if 200 <= status_code < 300 else ""
            if lottery_balance_exhausted(body, raw, business_error):
                balance_exhausted = True
                break
            if status_code < 200 or status_code >= 300:
                detail = f"；已完成：{completed_draw_detail()}" if summaries else ""
                return "failed", f"抽卡请求 HTTP {status_code}: {redact_message(raw)}{detail}"
            if business_error:
                detail = f"；已完成：{completed_draw_detail()}" if summaries else ""
                return "failed", f"抽卡失败：{business_error}{detail}"
            summary = lottery_result_summary(body)
            summaries.append(summary)
            task["dailyCounterDate"] = today
            task["dailyCounter"] = used_today + len(summaries)
            task["lastDrawResult"] = completed_draw_detail()
            task["lastDrawAt"] = int(datetime.now(timezone.utc).timestamp() * 1000)
        task["waitingForCheckinReward"] = False
        if not summaries:
            return "success", "野社彩筹已全部用完，本次没有可执行的抽卡"
        detail = completed_draw_detail()
        task["lastDrawResult"] = detail
        if daily_limit == 0 or balance_exhausted:
            return "success", f"野社自动抽卡完成 {len(summaries)} 次，彩筹已全部用完：{detail}"
        return "success", f"野社自动抽卡完成 {len(summaries)} 次（今日 {task['dailyCounter']}/{daily_limit}）：{detail}"
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
        is_finished = bool(nested_value(lore, "data", "isFinish"))
        reward_claimed = bool(nested_value(lore, "data", "claimed"))
        reward_summary = daily_lore_reward_summary(lore)
        checkin_message = "野社零点签到已完成" if is_finished else ""
        if not is_finished:
            if not lore_id:
                return "failed", "阅微每日轶闻响应缺少 userLoreId"
            status_code, body, raw = json_http_request(base_url, token, {"userLoreId": int(lore_id)}, str(request.get("completeEndpoint") or "rest/community/complete-daily-lore"))
            if status_code in (401, 403, 429):
                return "paused", f"阅微认证/风控响应 HTTP {status_code}"
            if status_code < 200 or status_code >= 300:
                return "failed", f"野社签到提交失败 HTTP {status_code}: {redact_message(raw)}"
            business_error = reamicro_business_error(body)
            if business_error:
                return "failed", f"野社签到提交失败：{business_error}"
            checkin_message = "野社零点签到完成"
        if task.get("lastCheckinDate") != today:
            task["lastCheckinDate"] = today
            complete_time = timestamp_millis(nested_value(lore, "data", "completeTime"))
            task["lastCheckinAt"] = complete_time or now_ms
            end_time = timestamp_millis(nested_value(lore, "data", "endTime"))
            task["claimDueAt"] = end_time or (complete_time + 8 * 3_600_000 if complete_time else now_ms + 8 * 3_600_000)
            task["claimRetryCount"] = 0
            task["claimFinalAttemptDate"] = ""
            task["claimCompletedDate"] = ""
        if task.get("claimCompletedDate") == today:
            return "success", f"{checkin_message or '野社签到状态已检查'}，签到奖励今日已领取"
        if reward_claimed:
            task["lastClaimAt"] = now_ms
            task["claimCompletedDate"] = today
            task["claimRetryCount"] = 0
            task["claimFinalAttemptDate"] = ""
            task["claimJustCompleted"] = True
            detail = f"：{reward_summary}" if reward_summary else ""
            return "success", f"{checkin_message or '野社签到状态已检查'}，签到奖励此前已领取{detail}"
        claim_due_at = bounded_config_int(task.get("claimDueAt", 0), now_ms + 8 * 3_600_000, 0)
        if now_ms < claim_due_at:
            task["nextRunAtOverride"] = claim_due_at
            return "success", f"{checkin_message or '野社签到状态已检查'}，奖励将在签到 8 小时后自动领取"

        if not lore_id:
            return "failed", "阅微每日轶闻响应缺少 userLoreId，无法领取签到奖励"
        # 阅微界面的“领取奖励”会再次调用 complete-daily-lore，并继续传当前轶闻的 id。
        claim_result, claim_message = claim_daily_lore_reward(base_url, token, request, int(lore_id))
        if claim_result == "paused":
            return "paused", claim_message
        if claim_result in {"granted", "already", "locked"}:
            task["claimRetryCount"] = 0
            task["claimFinalAttemptDate"] = ""
            if claim_result == "locked":
                # 奖励尚未解锁不是失败，但必须在当天继续检查，不能等到次日签到。
                task["nextRunAtOverride"] = now_ms + 5 * 60_000
                return "success", f"{checkin_message or '野社签到状态已检查'}，{claim_message}"
            task["lastClaimAt"] = now_ms
            task["claimCompletedDate"] = today
            # 已领取状态同样放行联动抽卡，否则抽卡会一直等在"等待签到奖励领取完成"。
            task["claimJustCompleted"] = True
            detail = f"：{reward_summary}" if reward_summary else ""
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
        completed_books: list[str] = []
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
            completed_books.append(str(book.get("name") or book.get("bookName") or f"图书 {cloud_id}").strip())
        if completed:
            task["dailyReadDate"] = today
            task["dailyReadMinutes"] = used_today + duration_minutes
            task["bookRotation"] = rotation + completed
        if completed:
            book_summary = "、".join(completed_books)
            return "success", f"云端自动阅读完成 {completed} 本：{book_summary}；累计 {duration_minutes} 分钟"
        return "failed", "没有找到可阅读的图书"
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
