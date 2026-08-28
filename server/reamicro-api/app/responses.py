"""统一响应包裹。

所有 /v1 接口都回 `{code, message, data, requestId, serverTime}`，
模块端按 `data` 是否为 null 判断成败，所以这个形状不能随意改。
"""
import secrets
from datetime import datetime, timezone
from typing import Any

from app import runtime


def response(data: Any = None, code: str = "OK", message: str = "", request_id: str = "") -> dict[str, Any]:
    return {
        "code": code,
        "message": message,
        "data": data,
        "requestId": request_id or "req_" + secrets.token_hex(8),
        "serverTime": datetime.now(timezone.utc).isoformat(),
    }
