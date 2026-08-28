"""后台显示层的纯格式化。

统一按北京时间显示并附相对时间；毫秒时间戳、耗时、字节数、摘要都在这里转成人话。
不读配置、不碰存储，方便单测。
"""
import re
from datetime import datetime, timedelta, timezone
from typing import Any

from app import runtime


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
