import secrets
from datetime import datetime, timezone
from typing import Any


def response(data: Any = None, code: str = "OK", message: str = "", request_id: str = "") -> dict[str, Any]:
    return {
        "code": code,
        "message": message,
        "data": data,
        "requestId": request_id or "req_" + secrets.token_hex(8),
        "serverTime": datetime.now(timezone.utc).isoformat(),
    }
