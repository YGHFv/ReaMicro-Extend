"""云端任务的实际执行。

get-daily-lore 获取每日轶闻及 endTime；isFinish=true 后才能调用 complete-daily-lore 领取奖励。
等待中的轶闻只安排后续检查，不提前调用领取接口，也不猜测固定的 8 小时等待期。
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


def reamicro_operation_error(body: Any) -> str:
    error = reamicro_business_error(body)
    if error:
        return error
    data = nested_value(body, "data") or body
    if isinstance(data, dict) and data.get("success") is False:
        return str(data.get("message") or data.get("msg") or "服务端未接受操作")
    return ""


LOTTERY_QUALITY_PRIORITY = {
    "RED": 70,
    "ORANGE": 65,
    "GOLD": 65,
    "YELLOW": 60,
    "PURPLE": 50,
    "BLUE": 40,
    "GREEN": 30,
    "GREY": 20,
    "GRAY": 20,
}

LOTTERY_QUALITY_ALIASES = {
    "红": "RED",
    "红色": "RED",
    "绝品": "RED",
    "传说": "RED",
    "橙": "ORANGE",
    "橙色": "ORANGE",
    "金": "GOLD",
    "金色": "GOLD",
    "紫": "PURPLE",
    "紫色": "PURPLE",
    "珍品": "PURPLE",
    "蓝": "BLUE",
    "蓝色": "BLUE",
    "精品": "BLUE",
    "绿": "GREEN",
    "绿色": "GREEN",
    "良品": "GREEN",
    "灰": "GREY",
    "灰色": "GREY",
    "普通": "GREY",
}


def normalize_lottery_quality(value: Any) -> str:
    quality = str(value or "").strip().upper()
    return LOTTERY_QUALITY_ALIASES.get(quality, quality)


def lottery_result_items(body: Any) -> list[dict[str, Any]]:
    """从阅微祈愿响应中提取结构化物品，供后台与通知统一渲染。"""
    result = (
        nested_value(body, "data", "props")
        or nested_value(body, "props")
        or nested_value(body, "data", "result")
        or nested_value(body, "result")
        or nested_value(body, "data")
    )
    if isinstance(result, str):
        text = result.strip()
        if text.startswith(("{", "[")):
            try:
                result = json.loads(text)
            except ValueError:
                return [{"name": redact_message(text), "quality": "", "count": 1}]
        else:
            return [{"name": redact_message(text), "quality": "", "count": 1}]
    items = result if isinstance(result, list) else [result] if isinstance(result, dict) else []
    parsed: list[dict[str, Any]] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        name = item.get("name") or item.get("cardName") or item.get("propName") or item.get("title") or item.get("result")
        quality = item.get("quality") or item.get("cardQuality") or item.get("propQuality") or item.get("rarity")
        if name:
            count = bounded_config_int(item.get("count") or item.get("quantity") or item.get("num") or 1, 1, 1)
            parsed.append({
                "name": str(name).strip(),
                "quality": normalize_lottery_quality(quality),
                "count": count,
            })
    if parsed:
        return parsed
    fallback = redact_message(json.dumps(result if result is not None else body, ensure_ascii=False)[:300])
    return [{"name": fallback, "quality": "", "count": 1}]


def aggregate_lottery_items(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """合并同名同品质物品，并按品质从高到低稳定排序。"""
    merged: dict[tuple[str, str], int] = {}
    for item in items:
        name = str(item.get("name", "")).strip()
        if not name:
            continue
        quality = normalize_lottery_quality(item.get("quality", ""))
        count = bounded_config_int(item.get("count", 1), 1, 1)
        merged[(name, quality)] = merged.get((name, quality), 0) + count
    result = [
        {"name": name, "quality": quality, "count": count}
        for (name, quality), count in merged.items()
    ]
    result.sort(key=lambda item: (
        -LOTTERY_QUALITY_PRIORITY.get(str(item.get("quality", "")), 0),
        str(item.get("name", "")).casefold(),
    ))
    return result


def lottery_items_summary(items: list[dict[str, Any]]) -> str:
    """生成不重复品质文字的紧凑结果，例如“端砚 x1、花笺 x2”。"""
    return "、".join(f"{item['name']} x{item['count']}" for item in aggregate_lottery_items(items))


def lottery_result_summaries(body: Any) -> list[str]:
    """兼容旧调用方，返回已聚合的紧凑物品文本。"""
    return [f"{item['name']} x{item['count']}" for item in aggregate_lottery_items(lottery_result_items(body))]


def lottery_result_summary(body: Any) -> str:
    """兼容需要单行展示祈愿结果的调用方。"""
    return lottery_items_summary(lottery_result_items(body))


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
    business_error = reamicro_operation_error(body)
    if not business_error and nested_value(body, "data", "claimed") is not False:
        return "granted", "签到奖励领取成功"
    if claim_reward_already_granted(message, body):
        return "already", "签到奖励此前已领取"
    if reward_not_ready(message):
        return "locked", "签到奖励尚未解锁，5 分钟后再次检查"
    return "failed", business_error or "服务端尚未确认奖励已领取"


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


def daily_lore_reward_items(lore: Any) -> list[dict[str, Any]]:
    """从每日轶闻响应提取结构化奖励明细。"""
    rewards: list[dict[str, Any]] = []
    exp = bounded_config_int(nested_value(lore, "data", "exp"), 0, 0)
    gem = bounded_config_int(nested_value(lore, "data", "gem"), 0, 0)
    if exp > 0:
        rewards.append({"name": "阅历", "quality": "", "count": exp})
    if gem > 0:
        rewards.append({"name": "彩筹", "quality": "", "count": gem})
    prop_name = str(nested_value(lore, "data", "propName") or "").strip()
    prop_quality = str(nested_value(lore, "data", "propQuality") or "").strip()
    if prop_name:
        rewards.append({"name": prop_name, "quality": normalize_lottery_quality(prop_quality), "count": 1})
    return rewards


def daily_lore_reward_summary(lore: Any) -> str:
    """生成签到奖励的紧凑通知文本。"""
    return lottery_items_summary(daily_lore_reward_items(lore))


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
    if task.get("taskType") == "pawn":
        return execute_pawn_task(task)
    if task.get("taskType") == "traveling_merchant":
        return execute_traveling_merchant_task(task)
    result, message = _execute_reamicro_task_body(task)
    if task.get("taskType") == "yeshe_draw_card" and result == "success":
        task["triggeredByCheckinReward"] = False
    return result, message


def blessing_label(blessing_type: Any) -> str:
    return {"LUCK": "求运签", "SAFETY": "求安签", "WEALTH": "求财签"}.get(str(blessing_type or "").strip().upper(), "")


def blessing_note(details: dict[str, Any]) -> str:
    label = blessing_label(details.get("activeType"))
    if not label:
        return ""
    if not details.get("prayed"):
        return f" · 沿用已有{label}"
    replaced = blessing_label(details.get("replacedType"))
    return f" · 已祈禳{label}" + (f"（顶掉原有{replaced}）" if replaced else "")


def ensure_taoist_blessing(
    base_url: str, token: str, request: dict[str, Any], blessing_type: Any,
    details: dict[str, Any] | None = None,
) -> str | None:
    requested = str(blessing_type or "").strip().upper()
    if not requested:
        return None
    if not blessing_label(requested):
        return f"不支持的运签配置：{requested}"
    status, body, raw = json_http_request(
        base_url, token, {}, str(request.get("blessingEndpoint") or "rest/community/get-taoist-blessing"),
    )
    if status in (401, 403, 429):
        return f"查询运签触发认证/风控 HTTP {status}"
    if status < 200 or status >= 300:
        return f"查询运签失败 HTTP {status}"
    error = reamicro_operation_error(body)
    if error:
        return f"查询运签失败：{error}"
    data = nested_value(body, "data") or body
    blessing = data.get("blessing") if isinstance(data, dict) else None
    active_type = str(blessing.get("blessingType") or "").strip().upper() if isinstance(blessing, dict) else ""
    if active_type == requested:
        if details is not None:
            details.update(activeType=active_type, prayed=False)
        return None
    status, body, raw = json_http_request(
        base_url, token, {"blessingType": requested},
        str(request.get("prayEndpoint") or "rest/community/pray-taoist-blessing"),
    )
    if status in (401, 403, 429):
        return f"祈禳触发认证/风控 HTTP {status}"
    if status < 200 or status >= 300:
        return f"祈禳失败 HTTP {status}: {redact_message(raw)}"
    error = reamicro_operation_error(body)
    if error:
        return f"祈禳失败：{error}"
    data = nested_value(body, "data") or body
    prayed = data.get("blessing") if isinstance(data, dict) else None
    if not isinstance(prayed, dict):
        status, body, raw = json_http_request(
            base_url, token, {}, str(request.get("blessingEndpoint") or "rest/community/get-taoist-blessing"),
        )
        error = reamicro_operation_error(body)
        if status < 200 or status >= 300 or error:
            return f"确认运签失败：{error or f'HTTP {status}'}"
        data = nested_value(body, "data") or body
        prayed = data.get("blessing") if isinstance(data, dict) else None
    actual_type = str(prayed.get("blessingType") or "").strip().upper() if isinstance(prayed, dict) else ""
    if actual_type != requested:
        return "服务端未返回生效运签" if not actual_type else f"服务端返回{blessing_label(actual_type)}，与配置不符"
    if details is not None:
        details.update(activeType=actual_type, prayed=True, replacedType=active_type)
    return None


def _execute_reamicro_task_body(task: dict[str, Any]) -> tuple[str, str]:
    request, secret = credential_for_task(task)
    base_url = str(secret.get("baseUrl") or "https://api.reamicro.zhendong.ltd/").strip()
    token = str(secret.get("token"))
    task_type = str(task.get("taskType"))
    task.pop("notificationItems", None)
    configured_body = request.get("body", {})
    if isinstance(configured_body, str):
        try:
            configured_body = json.loads(configured_body or "{}")
        except ValueError:
            configured_body = {}
    if task_type == "yeshe_draw_card":
        if not task.get("triggeredByCheckinReward", False):
            task["waitingForCheckinReward"] = True
            return "success", "等待签到奖励领取后触发"
        daily_limit = min(bounded_config_int(request.get("dailyLimit", 3), 3, 0), 20)
        today = datetime.now(timezone(timedelta(hours=8))).date().isoformat()
        used_today = bounded_config_int(task.get("dailyCounter", 0), 0, 0) if task.get("dailyCounterDate") == today else 0
        if daily_limit > 0 and used_today >= daily_limit:
            return "success", f"今日已达到 {daily_limit} 抽上限"
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
        configured_endpoint = str(request.get("endpoint") or "").strip()
        endpoint = configured_endpoint if configured_endpoint and "lottery" not in configured_endpoint else "rest/community/wish"
        draw_items: list[dict[str, Any]] = []
        consumed = 0

        def completed_draw_detail() -> str:
            return lottery_items_summary(draw_items)

        def remember_draw_result() -> None:
            aggregated = aggregate_lottery_items(draw_items)
            task["lastDrawItems"] = aggregated
            task["notificationItems"] = aggregated
            task["lastDrawResult"] = lottery_items_summary(aggregated)
            task["lastDrawAt"] = int(datetime.now(timezone.utc).timestamp() * 1000)

        while consumed < draw_target:
            request_count = 9 if draw_target - consumed >= 9 else 1
            wish_body = dict(configured_body) if isinstance(configured_body, dict) else {}
            wish_body["count"] = request_count
            status_code, body, raw = json_http_request(base_url, token, wish_body, endpoint)
            if status_code in (401, 403, 429):
                detail = f"；已获得：{completed_draw_detail()}" if draw_items else ""
                return "paused", f"阅微认证/风控响应 HTTP {status_code}{detail}"
            business_error = reamicro_business_error(body) if 200 <= status_code < 300 else ""
            if lottery_balance_exhausted(body, raw, business_error):
                break
            if status_code < 200 or status_code >= 300:
                detail = f"；已获得：{completed_draw_detail()}" if draw_items else ""
                return "failed", f"祈愿请求 HTTP {status_code}: {redact_message(raw)}{detail}"
            if business_error:
                detail = f"；已获得：{completed_draw_detail()}" if draw_items else ""
                return "failed", f"祈愿失败：{business_error}{detail}"
            wish_success = nested_value(body, "data", "success")
            if wish_success is False:
                message = nested_value(body, "data", "message") or "阅微未返回失败原因"
                detail = f"；已获得：{completed_draw_detail()}" if draw_items else ""
                return "failed", f"祈愿失败：{redact_message(str(message))}{detail}"
            draw_items.extend(lottery_result_items(body))
            consumed += request_count
            task["dailyCounterDate"] = today
            task["dailyCounter"] = used_today + consumed
            remember_draw_result()
        task["waitingForCheckinReward"] = False
        if not draw_items:
            return "success", "彩筹已用完"
        detail = completed_draw_detail()
        remember_draw_result()
        return "success", detail
    if task_type == "yeshe_checkin":
        china_zone = timezone(timedelta(hours=8))
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        today = datetime.fromtimestamp(now_ms / 1000, china_zone).date().isoformat()
        same_day = task.get("lastCheckinDate") == today
        task.pop("claimJustCompleted", None)
        task.pop("nextRunAtOverride", None)
        blessing_details: dict[str, Any] = {}
        if not same_day:
            failure = ensure_taoist_blessing(base_url, token, request, request.get("blessingType"), blessing_details)
            if failure:
                task["nextRunAtOverride"] = now_ms + 5 * 60_000
                return "failed", failure
        suffix = blessing_note(blessing_details)
        status_code, lore, raw = json_http_request(base_url, token, configured_body, str(request.get("endpoint") or "rest/community/get-daily-lore"))
        if status_code in (401, 403, 429):
            return "paused", f"阅微认证/风控响应 HTTP {status_code}"
        if status_code < 200 or status_code >= 300:
            return "failed", f"获取野社每日轶闻失败 HTTP {status_code}: {redact_message(raw)}"
        business_error = reamicro_operation_error(lore)
        if business_error:
            return "failed", f"获取野社每日轶闻失败：{business_error}"
        data = nested_value(lore, "data") or lore
        data = data if isinstance(data, dict) else {}
        lore_id = _safe_long(data.get("id") or data.get("loreId"))
        if lore_id <= 0:
            return "failed", "阅微每日轶闻响应缺少 userLoreId"
        previous_id = _safe_long(task.get("claimLoreId"))
        same_lore = same_day and previous_id in (0, lore_id)
        if same_lore and previous_id == lore_id and task.get("claimCompletedDate") == today:
            return "success", "今日已领取"
        if not same_lore:
            task["claimRetryCount"] = 0
            task["claimFinalAttemptDate"] = ""
            task["claimCompletedDate"] = ""
        task["lastCheckinDate"] = today
        task["lastCheckinAt"] = task.get("lastCheckinAt", now_ms) if same_lore else now_ms
        task["claimLoreId"] = lore_id
        end_time = timestamp_millis(data.get("endTime"))
        claim_due_at = end_time or (_safe_long(task.get("claimDueAt")) if same_lore else 0)
        task["claimDueAt"] = claim_due_at
        reward_claimed = bool(data.get("claimed"))
        reward_items = daily_lore_reward_items(lore)
        reward_summary = lottery_items_summary(reward_items)
        ready = bool(data.get("isFinish")) if "isFinish" in data else 0 < claim_due_at <= now_ms
        if not reward_claimed and not ready:
            if claim_due_at > now_ms:
                task["nextRunAtOverride"] = claim_due_at
                end_label = datetime.fromtimestamp(claim_due_at / 1000, china_zone).strftime("%m-%d %H:%M")
                return "success", f"签到完成，奖励待领取（{end_label} 解锁）{suffix}"
            task["nextRunAtOverride"] = now_ms + (5 * 60_000 if claim_due_at else 3_600_000)
            return "success", f"签到完成，奖励待领取，已安排后续查询{suffix}"
        if reward_claimed:
            claim_result, claim_message = "already", "签到奖励此前已领取"
        else:
            claim_result, claim_message = claim_daily_lore_reward(base_url, token, request, lore_id)
        if claim_result == "paused":
            return "paused", claim_message
        if claim_result in {"granted", "already", "locked"}:
            task["claimRetryCount"] = 0
            task["claimFinalAttemptDate"] = ""
            if claim_result == "locked":
                task["nextRunAtOverride"] = now_ms + 5 * 60_000
                return "success", "奖励尚未解锁，5 分钟后再次检查"
            task["lastClaimAt"] = now_ms
            task["claimCompletedDate"] = today
            task["claimJustCompleted"] = True
            task["notificationItems"] = reward_items
            detail = f"：{reward_summary}" if reward_summary else ""
            return "success", f"奖励已领取{detail}{suffix}"
        retry_count = bounded_config_int(task.get("claimRetryCount", 0), 0, 0) + 1
        task["claimRetryCount"] = retry_count
        local_now = datetime.fromtimestamp(now_ms / 1000, china_zone)
        if retry_count <= 3:
            task["nextRunAtOverride"] = now_ms + 5 * 60_000
            return "success", f"奖励领取失败，5 分钟后重试：{redact_message(str(claim_message))}"
        if task.get("claimFinalAttemptDate") != today:
            final_at = local_now.replace(hour=23, minute=59, second=0, microsecond=0)
            if final_at <= local_now:
                final_at = local_now + timedelta(minutes=1)
            task["claimFinalAttemptDate"] = today
            task["nextRunAtOverride"] = int(final_at.timestamp() * 1000)
            return "success", f"奖励领取失败，23:59 最后重试：{redact_message(str(claim_message))}"
        task["claimFinalFailedDate"] = today
        return "success", f"今日奖励领取失败：{redact_message(str(claim_message))}"
    if task_type == "cloud_auto_read":
        books = request.get("books") if isinstance(request.get("books"), list) else []
        uses_recent_books = not books
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
        daily_limit_minutes = max(1, min(int(request.get("dailyLimitMinutes", 720) or 720), 1440))
        today = datetime.now(timezone(timedelta(hours=8))).date().isoformat()
        used_today = int(task.get("dailyReadMinutes", 0)) if task.get("dailyReadDate") == today else 0
        duration_minutes = min(duration_minutes, max(daily_limit_minutes - used_today, 0))
        if duration_minutes <= 0:
            return "success", f"今日已达到 {daily_limit_minutes} 分钟上限"
        rotation = int(task.get("bookRotation", 0)) % max(len(books), 1)
        books = books[rotation:] + books[:rotation]
        completed = 0
        completed_minutes = 0
        completed_books: list[str] = []
        for offset, book in enumerate(books):
            current_duration = min(duration_minutes, max(daily_limit_minutes - used_today - completed_minutes, 0))
            if current_duration <= 0:
                break
            if not isinstance(book, dict):
                continue
            raw_book_id = book.get("bookId")
            if not uses_recent_books and raw_book_id in (None, ""):
                # 兼容旧版自定义图书配置；最近阅读响应不得回退到公共云书 ID。
                raw_book_id = book.get("cloudBookId")
            try:
                book_id = int(str(raw_book_id).strip())
            except (TypeError, ValueError):
                continue
            if book_id <= 0:
                continue
            time_payload = {"list": [{"bookId": book_id, "date": today, "duration": current_duration * 60, "verify": ""}]}
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
            completed_minutes += current_duration
            task["dailyReadDate"] = today
            task["dailyReadMinutes"] = used_today + completed_minutes
            task["bookRotation"] = rotation + offset + 1
            completed_books.append(str(book.get("name") or book.get("bookName") or f"图书 {book_id}").strip())
        if completed:
            book_summary = "、".join(completed_books)
            return "success", f"{book_summary} · {completed_minutes} 分钟"
        return "failed", "没有找到可阅读的图书"
    return "failed", f"未知阅微任务类型：{task_type}"



PROHIBITED_PAWN_PROP_HINTS = {
    "11": "传承消耗物品（清酒）",
    "12": "祈禳消耗物品（剡藤）",
    "13": "传承消耗物品（檀香）",
    "14": "祈禳消耗物品（青瓷）",
    "15": "祈禳消耗物品（徽墨）",
    "16": "备选消耗物品（端砚）",
    "17": "夺宝消耗物品（琬琰）",
    "18": "传承消耗物品（欹器）",
    # 青圭的 propId 静态拿不到（背包 materials 里没有，宿主只存 qinggui 计数），
    # 用 name: 前缀键占位，执行时除 propId 外再按当日期物名字兜底匹配。
    "name:青圭": "招募消耗物品（青圭）",
}


def execute_pawn_task(task: dict[str, Any]) -> tuple[str, str]:
    """期物典当：把当日期物换成铜钱。

    判定与参考脚本一致：`get-pawn-count` 取当日可典当期物与剩余次数；期物命中禁当清单就跳过
    （那些消耗品另有用途，典当掉会让祈禳/传承/夺宝缺料）；再从背包里找到该期物的 userPropId，
    循环 `pawn` 直到次数或持有量用尽。
    """
    request, secret = credential_for_task(task)
    base_url = str(secret.get("baseUrl") or "https://api.reamicro.zhendong.ltd/").strip()
    token = str(secret.get("token"))
    status, body, raw = json_http_request(
        base_url, token, {}, str(request.get("pawnCountEndpoint") or "rest/community/get-pawn-count"),
    )
    if status in (401, 403, 429):
        return "paused", f"获取期物典当信息触发认证/风控 HTTP {status}"
    if status < 200 or status >= 300:
        return "failed", f"获取期物典当信息失败 HTTP {status}: {redact_message(raw)}"
    error = reamicro_business_error(body)
    if error:
        return "failed", f"获取期物典当信息失败：{error}"
    data = nested_value(body, "data") or {}
    remaining = max(bounded_config_int(data.get("remaining", 0), 0, 0), 0)
    max_per_day = max(bounded_config_int(data.get("maxPerDay", 0), 0, 0), 0)
    used_today = max(bounded_config_int(data.get("usedToday", 0), 0, 0), 0)
    prop_id = str(data.get("specialPropId") or "").strip()
    prop_name = str(data.get("specialPropName") or "期物")
    if remaining <= 0:
        return "success", f"今日可典当次数已用完（{used_today}/{max_per_day}）"
    if prop_id in PROHIBITED_PAWN_PROP_HINTS:
        return "success", f"今日期物「{prop_name}」是{PROHIBITED_PAWN_PROP_HINTS[prop_id]}，跳过典当"
    if f"name:{prop_name}" in PROHIBITED_PAWN_PROP_HINTS:
        return "success", f"今日期物「{prop_name}」是{PROHIBITED_PAWN_PROP_HINTS[f'name:{prop_name}']}，跳过典当"
    # 红色品质一律不自动典当：RED 档全是青圭这类另有用途的稀有消耗品。
    if str(data.get("specialPropQuality") or "").strip().upper() == "RED":
        return "success", f"今日期物「{prop_name}」是红色品质，不自动典当"
    status, body, raw = json_http_request(
        base_url, token, {}, str(request.get("materialsEndpoint") or "rest/community/get-user-materials"),
    )
    if status in (401, 403, 429):
        return "paused", f"读取背包触发认证/风控 HTTP {status}"
    if status < 200 or status >= 300:
        return "failed", f"读取背包失败 HTTP {status}: {redact_message(raw)}"
    materials = nested_value(body, "data", "materials")
    if not isinstance(materials, list):
        materials = []
    target = next(
        (item for item in materials if isinstance(item, dict) and str(item.get("propId") or "").strip() == prop_id),
        None,
    )
    if target is None:
        return "success", f"背包里没有期物「{prop_name}」，跳过典当"
    quantity = max(bounded_config_int(target.get("quantity", 0), 0, 0), 0)
    if quantity <= 0:
        return "success", f"期物「{prop_name}」持有数量为 0，跳过典当"
    user_prop_id = target.get("userPropId")
    if user_prop_id in (None, ""):
        return "failed", f"期物「{prop_name}」缺少 userPropId，无法典当"
    success_count = 0
    coin = 0
    failure = ""
    for _ in range(min(remaining, quantity)):
        status, body, raw = json_http_request(
            base_url, token, {"userPropId": int(user_prop_id)},
            str(request.get("pawnEndpoint") or "rest/community/pawn"),
        )
        if status in (401, 403, 429):
            failure = f"触发认证/风控 HTTP {status}"
            break
        if status < 200 or status >= 300:
            failure = f"HTTP {status}: {redact_message(raw)}"
            break
        error = reamicro_operation_error(body)
        if error:
            failure = str(error)
            break
        coin += _safe_long(nested_value(body, "data", "coin") if nested_value(body, "data", "coin") is not None else body.get("coin"))
        success_count += 1
    task["lastPawnDate"] = datetime.now(timezone(timedelta(hours=8))).date().isoformat()
    task["pawnUsedToday"] = used_today + success_count
    task["pawnLastCoin"] = coin
    if success_count == 0:
        return "failed", f"典当失败：{failure or '未知原因'}"
    tail = f"（第 {success_count + 1} 次中断：{failure}）" if failure else ""
    return ("failed" if failure else "success"), f"典当「{prop_name}」{success_count} 件，获得铜钱 {coin} 文{tail}"

def _safe_long(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError, OverflowError):
        return 0


def merchant_profit_text(event_title: Any, settlement_amount: Any, principal: Any) -> str:
    """行商完成通知正文：事件标题 + 收益/亏损（结算额 − 本金）。"""
    delta = _safe_long(settlement_amount) - _safe_long(principal)
    profit = f"收益 +{delta}" if delta >= 0 else f"亏损 {delta}"
    title = str(event_title or "").strip() or "行商"
    return f"{title} · {profit}"


def execute_traveling_merchant_task(task: dict[str, Any]) -> tuple[str, str]:
    """SETTLED 是待领取状态；领取成功后才按配置祈禳并续开。"""
    request, secret = credential_for_task(task)
    base_url = str(secret.get("baseUrl") or "https://api.reamicro.zhendong.ltd/").strip()
    token = str(secret.get("token"))
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    task["nextRunAtOverride"] = now_ms + 5 * 60_000
    task.pop("notificationItems", None)
    status_code, body, raw = json_http_request(
        base_url, token, request.get("body", {}),
        str(request.get("endpoint") or "rest/community/get-traveling-merchant"),
    )
    if status_code in (401, 403, 429):
        return "paused", f"阅微认证/风控响应 HTTP {status_code}"
    if status_code < 200 or status_code >= 300:
        return "failed", f"获取行商状态失败 HTTP {status_code}: {redact_message(raw)}"
    business_error = reamicro_operation_error(body)
    if business_error:
        return "failed", f"获取行商状态失败：{business_error}"
    trip = _merchant_trip(body)
    remembered = _merchant_remembered_config(task, trip)
    _remember_merchant_config(task, remembered)
    config = _merchant_start_config(request, remembered)
    auto_complete = bool(request.get("merchantAutoComplete"))
    trip_id = _safe_long(trip.get("id")) if trip else 0
    end_time_ms = timestamp_millis(trip.get("endTime")) if trip else 0
    task["merchantEndTime"] = end_time_ms
    if trip and trip_id <= 0:
        return "failed", "行商响应缺少 tripId"
    if trip and str(trip.get("status") or "").upper() != "SETTLED":
        if not end_time_ms or now_ms < end_time_ms:
            task["nextRunAtOverride"] = _merchant_next_run(trip, now_ms)
            return "success", "行商进行中，等待完成"
        task["nextRunAtOverride"] = now_ms + 60_000
        return "success", "行商已到预计结束时间，等待服务端生成结算结果"
    last_notified = _safe_long(task.get("merchantLastNotifiedTripId"))
    notify = bool(trip and trip_id != last_notified)
    if notify:
        task["merchantLastNotifiedTripId"] = trip_id
    message = merchant_profit_text(trip.get("eventTitle"), trip.get("settlementAmount"), trip.get("principal")) if notify else "行商奖励待领取"
    if not trip:
        message = "当前没有进行中的行商"
    settled_trip_id = _safe_long(task.get("merchantSettledTripId"))
    if trip and auto_complete and trip_id != settled_trip_id:
        try:
            settle_status, settled, settle_raw = json_http_request(
                base_url, token, {"tripId": trip_id},
                str(request.get("settleEndpoint") or "rest/community/settle-traveling-merchant"),
            )
        except Exception as error:
            return "failed", f"{message}（领取行商奖励失败：{redact_message(str(error))}）"
        settle_error = reamicro_operation_error(settled) if 200 <= settle_status < 300 else f"HTTP {settle_status}"
        if settle_error:
            return "failed", f"{message}（领取行商奖励失败：{settle_error}）"
        task["merchantSettledTripId"] = trip_id
        task["merchantEndTime"] = 0
        settled_trip_id = trip_id
        settled_trip = _merchant_trip(settled)
        if settled_trip:
            trip = settled_trip
        message = merchant_profit_text(trip.get("eventTitle"), trip.get("settlementAmount"), trip.get("principal")) + "，已领取行商奖励"
    elif trip and trip_id == settled_trip_id:
        message = "行商奖励已领取"
        task["merchantEndTime"] = 0
    can_start = auto_complete and (not trip or (
        trip_id == settled_trip_id and trip_id != _safe_long(task.get("merchantRestartedAfterTripId"))
    ))
    if can_start:
        if not config["cityCode"] or config["principal"] <= 0 or config["transportId"] < 0:
            return "failed", f"{message}（未能开启新行商：{_merchant_start_hint(config)}）"
        blessing_details: dict[str, Any] = {}
        try:
            failure = ensure_taoist_blessing(base_url, token, request, request.get("blessingType"), blessing_details)
        except Exception as error:
            failure = redact_message(str(error))
        if failure:
            return "failed", f"{message}（运签：{failure}）"
        started, start_error = _start_traveling_merchant(base_url, token, request, config)
        if start_error:
            return "failed", f"{message}（未能开启新行商：{start_error}）"
        next_trip = _merchant_trip(started)
        if not next_trip:
            try:
                refresh_status, refreshed, _ = json_http_request(
                    base_url, token, {}, str(request.get("endpoint") or "rest/community/get-traveling-merchant"),
                )
                if 200 <= refresh_status < 300 and not reamicro_operation_error(refreshed):
                    next_trip = _merchant_trip(refreshed)
            except Exception:
                pass
        start_data = nested_value(started, "data") or started
        confirmed = isinstance(start_data, dict) and start_data.get("success") is True
        if (not next_trip or _safe_long(next_trip.get("id")) == trip_id) and not confirmed:
            return "failed", f"{message}（未能确认新行商已开启）"
        if trip_id:
            task["merchantRestartedAfterTripId"] = trip_id
        _remember_merchant_config(task, config)
        task["merchantEndTime"] = timestamp_millis(next_trip.get("endTime")) if next_trip else 0
        task["nextRunAtOverride"] = _merchant_next_run(next_trip, now_ms)
        started_message = "，已开启新行商" if trip_id else "，已按配置开启新行商"
        return "success", f"{message}{started_message}{blessing_note(blessing_details)}"
    task["nextRunAtOverride"] = now_ms + 4 * 3_600_000
    return "success", message


def _merchant_trip(body: Any) -> dict[str, Any] | None:
    data = nested_value(body, "data") or body
    if not isinstance(data, dict):
        return None
    trip = data.get("activeTrip") or data.get("trip")
    return trip if isinstance(trip, dict) else None


def _merchant_next_run(trip: dict[str, Any] | None, now_ms: int) -> int:
    end_time = timestamp_millis(trip.get("endTime")) if trip else 0
    return end_time + 60_000 if end_time > now_ms else now_ms + 5 * 60_000


def _merchant_remembered_config(task: dict[str, Any], trip: Any) -> dict[str, Any]:
    """上次行商实际使用的城池/本金/车马。用户留空时沿用——阅微只在内存里保存这些选择。"""
    trip = trip if isinstance(trip, dict) else {}
    return {
        "cityCode": str(trip.get("cityCode") or task.get("merchantLastCityCode") or "").strip(),
        "transportId": bounded_config_int(trip.get("transportId", task.get("merchantLastTransportId", 0)), 0, 0),
        "principal": bounded_config_int(trip.get("principal") or task.get("merchantLastPrincipal", 0), 0, 0),
    }


def _merchant_start_config(request: dict[str, Any], remembered: dict[str, Any]) -> dict[str, Any]:
    """用户填了的优先，留空的沿用上次行商配置。"""
    return {
        "cityCode": str(request.get("merchantCityCode") or "").strip() or remembered.get("cityCode", ""),
        "transportId": bounded_config_int(request.get("merchantTransportId", 0), 0, 0) or remembered.get("transportId", 0),
        "principal": bounded_config_int(request.get("merchantPrincipal", 0), 0, 0) or remembered.get("principal", 0),
    }


def _merchant_start_hint(config: dict[str, Any]) -> str:
    if not config.get("cityCode"):
        return "缺少城池"
    if int(config.get("transportId") or 0) < 0:
        return "车马配置无效"
    if int(config.get("principal") or 0) <= 0:
        return "缺少本金"
    return "参数不完整"


def _remember_merchant_config(task: dict[str, Any], config: dict[str, Any]) -> None:
    task["merchantLastCityCode"] = config.get("cityCode", "")
    task["merchantLastTransportId"] = config.get("transportId", 0)
    task["merchantLastPrincipal"] = config.get("principal", 0)


def _start_traveling_merchant(
    base_url: str, token: str, request: dict[str, Any], config: dict[str, Any],
) -> tuple[Any, str]:
    try:
        status_code, body, _ = json_http_request(
            base_url, token,
            {"cityCode": config["cityCode"], "principal": config["principal"], "transportId": config["transportId"]},
            str(request.get("startEndpoint") or "rest/community/start-traveling-merchant"),
        )
    except Exception as error:
        return None, redact_message(str(error))
    error = reamicro_operation_error(body) if 200 <= status_code < 300 else f"HTTP {status_code}"
    return body, error


def execute_task(task: dict[str, Any]) -> tuple[str, str]:
    try:
        if task.get("taskType") == "http":
            return execute_http_task(task)
        if task.get("taskType") == "traveling_merchant":
            return execute_traveling_merchant_task(task)
        if task.get("taskType") in {"yeshe_checkin", "yeshe_draw_card", "cloud_auto_read", "pawn"}:
            return execute_reamicro_task(task)
        return "failed", f"未知任务类型：{task.get('taskType')}"
    except Exception as error:
        return "failed", redact_message(str(error))


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
