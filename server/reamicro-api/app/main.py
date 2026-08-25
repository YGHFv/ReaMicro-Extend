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
import shutil
import threading
import html
import base64
import re
import time
import hashlib as _hashlib
from email.utils import parsedate_to_datetime
from pathlib import Path
from datetime import datetime, timezone, timedelta
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Query, Form, status, UploadFile, File
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse
from fastapi.responses import JSONResponse

API_VERSION = "1.0"
CONFIG_ROOT = Path(os.getenv("REAMICRO_CONFIG_ROOT", "/data/config"))
CONFIG_PATH = CONFIG_ROOT / "server.json"
DEFAULT_FEATURES = {
    item.strip()
    for item in os.getenv(
        "REAMICRO_FEATURES",
        "association_source,theme_library,style_library,book_source_library,backup,module_update",
    ).split(",")
    if item.strip()
}
PACKAGE_ROOT = Path(os.getenv("REAMICRO_PACKAGE_ROOT", "/data/packages"))
RELEASE_ROOT = Path(os.getenv("REAMICRO_RELEASE_ROOT", "/data/releases/module"))
BACKUP_ROOT = Path(os.getenv("REAMICRO_BACKUP_ROOT", "/data/backups"))
SECRET_BACKUP_ROOT = Path(os.getenv("REAMICRO_SECRET_BACKUP_ROOT", "/data/backups/secrets"))
TASK_ROOT = Path(os.getenv("REAMICRO_TASK_ROOT", "/data/tasks"))
TASKS_PATH = TASK_ROOT / "tasks.json"
TASK_LOG_ROOT = TASK_ROOT / "logs"
AUDIT_ROOT = Path(os.getenv("REAMICRO_AUDIT_ROOT", "/data/audit"))
AUDIT_PATH = AUDIT_ROOT / "events.jsonl"
ACCOUNT_ROOT = Path(os.getenv("REAMICRO_ACCOUNT_ROOT", "/data/accounts"))
ACCOUNT_PATH = ACCOUNT_ROOT / "credentials.json"
SECRET_KEY = os.getenv("REAMICRO_SECRET_KEY", "")
ADMIN_USERNAME = os.getenv("REAMICRO_ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = os.getenv("REAMICRO_ADMIN_PASSWORD", "")
SIGNING_PRIVATE_KEY_FILE = os.getenv("REAMICRO_SIGNING_PRIVATE_KEY_FILE", "")
config_lock = threading.RLock()
basic_security = HTTPBasic(auto_error=False)
rate_lock = threading.RLock()
rate_buckets: dict[str, list[float]] = {}
RATE_LIMIT = max(int(os.getenv("REAMICRO_RATE_LIMIT", "120")), 10)
RATE_WINDOW_SECONDS = 60


def env_bool(name: str, default: bool) -> bool:
    return os.getenv(name, str(default)).lower() == "true"


def default_config() -> dict[str, Any]:
    return {
        "serverId": os.getenv("REAMICRO_SERVER_ID", "reamicro-api"),
        "apiKey": os.getenv("REAMICRO_API_KEY", ""),
        "secretKey": os.getenv("REAMICRO_SECRET_KEY", ""),
        "allowPublic": env_bool("REAMICRO_ALLOW_PUBLIC", True),
        "features": sorted(DEFAULT_FEATURES),
        "minModuleVersion": os.getenv("REAMICRO_MIN_MODULE_VERSION", "2.0.0"),
        "signingPublicKey": os.getenv("REAMICRO_SIGNING_PUBLIC_KEY", ""),
        "githubRepository": os.getenv("REAMICRO_GITHUB_REPOSITORY", "YGHFv/ReaMicro-Extend"),
        "githubToken": os.getenv("REAMICRO_GITHUB_TOKEN", ""),
        "githubIncludePrerelease": env_bool("REAMICRO_GITHUB_INCLUDE_PRERELEASE", False),
        "releaseSyncSeconds": max(int(os.getenv("REAMICRO_RELEASE_SYNC_SECONDS", "1800")), 300),
        "releaseVersionCode": int(os.getenv("REAMICRO_RELEASE_VERSION_CODE", "0")),
        "accounts": {},
        "primaryAdmin": {},
        "adminAccounts": {},
        "hostAccountAllowlist": [],
    }


def load_config() -> dict[str, Any]:
    with config_lock:
        config = default_config()
        try:
            if CONFIG_PATH.is_file():
                stored = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
                if isinstance(stored, dict):
                    config.update(stored)
        except (OSError, ValueError):
            pass
        config["features"] = sorted({str(item).strip() for item in config.get("features", []) if str(item).strip()})
        config["releaseSyncSeconds"] = max(int(config.get("releaseSyncSeconds", 1800)), 300)
        return config


def save_config(next_config: dict[str, Any]) -> dict[str, Any]:
    with config_lock:
        CONFIG_ROOT.mkdir(parents=True, exist_ok=True)
        safe = default_config()
        safe.update(next_config)
        safe["features"] = sorted({str(item).strip() for item in safe.get("features", []) if str(item).strip()})
        safe["releaseSyncSeconds"] = max(int(safe.get("releaseSyncSeconds", 1800)), 300)
        safe["accounts"] = safe.get("accounts", {}) if isinstance(safe.get("accounts", {}), dict) else {}
        safe["primaryAdmin"] = safe.get("primaryAdmin", {}) if isinstance(safe.get("primaryAdmin", {}), dict) else {}
        safe["adminAccounts"] = safe.get("adminAccounts", {}) if isinstance(safe.get("adminAccounts", {}), dict) else {}
        safe["hostAccountAllowlist"] = sorted({str(item).strip() for item in safe.get("hostAccountAllowlist", []) if str(item).strip()})
        temp = CONFIG_PATH.with_suffix(".tmp")
        temp.write_text(json.dumps(safe, ensure_ascii=False, indent=2), encoding="utf-8")
        temp.replace(CONFIG_PATH)
        return safe


def load_tasks() -> dict[str, dict[str, Any]]:
    TASK_ROOT.mkdir(parents=True, exist_ok=True)
    try:
        value = json.loads(TASKS_PATH.read_text(encoding="utf-8")) if TASKS_PATH.is_file() else {}
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


def save_tasks(tasks: dict[str, dict[str, Any]]) -> None:
    TASK_ROOT.mkdir(parents=True, exist_ok=True)
    temp = TASKS_PATH.with_suffix(".tmp")
    temp.write_text(json.dumps(tasks, ensure_ascii=False, indent=2), encoding="utf-8")
    temp.replace(TASKS_PATH)


def load_credentials() -> dict[str, dict[str, Any]]:
    ACCOUNT_ROOT.mkdir(parents=True, exist_ok=True)
    try:
        value = json.loads(ACCOUNT_PATH.read_text(encoding="utf-8")) if ACCOUNT_PATH.is_file() else {}
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


def save_credentials(credentials: dict[str, dict[str, Any]]) -> None:
    ACCOUNT_ROOT.mkdir(parents=True, exist_ok=True)
    temp = ACCOUNT_PATH.with_suffix(".tmp")
    temp.write_text(json.dumps(credentials, ensure_ascii=False, indent=2), encoding="utf-8")
    temp.replace(ACCOUNT_PATH)


def audit_event(action: str, actor: str = "system", request_id: str = "", success: bool = True, metadata: dict[str, Any] | None = None) -> None:
    """记录不含敏感值的管理与安全事件。"""
    try:
        AUDIT_ROOT.mkdir(parents=True, exist_ok=True)
        event = {
            "at": int(datetime.now(timezone.utc).timestamp() * 1000),
            "action": action,
            "actor": actor,
            "requestId": request_id,
            "success": success,
            "metadata": metadata or {},
        }
        with AUDIT_PATH.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")
    except OSError:
        pass


def audit_actor(actor: dict[str, Any] | None) -> str:
    if not actor:
        return "system"
    return f"admin:{actor.get('username', 'unknown')}"


def allow_rate_limit(key: str) -> bool:
    now = time.monotonic()
    with rate_lock:
        values = [item for item in rate_buckets.get(key, []) if now - item < RATE_WINDOW_SECONDS]
        if len(values) >= RATE_LIMIT:
            rate_buckets[key] = values
            return False
        values.append(now)
        rate_buckets[key] = values
        if len(rate_buckets) > 10_000:
            oldest = sorted(rate_buckets, key=lambda item: rate_buckets[item][-1] if rate_buckets[item] else now)[:1000]
            for item in oldest:
                rate_buckets.pop(item, None)
        return True


def task_log(task_id: str, message: str, level: str = "INFO") -> None:
    TASK_LOG_ROOT.mkdir(parents=True, exist_ok=True)
    path = TASK_LOG_ROOT / f"{task_id}.log"
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps({
            "at": int(datetime.now(timezone.utc).timestamp() * 1000),
            "level": level,
            "message": message,
        }, ensure_ascii=False) + "\n")


def secret_cipher_key() -> bytes:
    material = str(load_config().get("secretKey", "")) or SECRET_KEY or ADMIN_PASSWORD
    if not material:
        raise ValueError("未配置 REAMICRO_SECRET_KEY 或管理密码")
    return hashlib.sha256(material.encode("utf-8")).digest()


def encrypt_secret(value: Any) -> str:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    nonce = secrets.token_bytes(12)
    body = json.dumps(value, ensure_ascii=False).encode("utf-8")
    encrypted = AESGCM(secret_cipher_key()).encrypt(nonce, body, b"reamicro-task-v1")
    return base64.urlsafe_b64encode(nonce + encrypted).decode("ascii")


def decrypt_secret(value: str) -> Any:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    raw = base64.urlsafe_b64decode(value.encode("ascii"))
    body = AESGCM(secret_cipher_key()).decrypt(raw[:12], raw[12:], b"reamicro-task-v1")
    return json.loads(body.decode("utf-8"))


def task_interval(task: dict[str, Any]) -> int:
    schedule = task.get("schedule", {})
    if isinstance(schedule, dict):
        return max(int(schedule.get("intervalSeconds", 86_400)), 60)
    if schedule == "@hourly":
        return 3_600
    if schedule == "@daily":
        return 86_400
    return 86_400


def next_task_run(task: dict[str, Any], now_ms: int | None = None) -> int:
    now_ms = now_ms or int(datetime.now(timezone.utc).timestamp() * 1000)
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
    except (ValueError, TypeError):
        return now_ms + task_interval(task) * 1000


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


def task_credential_id(task: dict[str, Any]) -> str:
    if task.get("credentialId"):
        return str(task.get("credentialId"))
    if not task.get("requestEncrypted"):
        return ""
    try:
        request = decrypt_secret(str(task["requestEncrypted"]))
        return str(request.get("credentialId", "")) if isinstance(request, dict) else ""
    except Exception:
        return ""


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


def nested_value(value: Any, *keys: str) -> Any:
    current = value
    for key in keys:
        if isinstance(current, dict):
            current = current.get(key)
        else:
            return None
    return current


def credential_for_task(task: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    request = decrypt_secret(str(task.get("requestEncrypted", ""))) if task.get("requestEncrypted") else {}
    credential_id = str(request.get("credentialId", "")).strip()
    credentials = load_credentials()
    credential = credentials.get(credential_id)
    if not credential or credential.get("owner") != task.get("owner"):
        raise ValueError("阅微凭据不存在或不属于当前账号")
    secret = decrypt_secret(str(credential.get("secretEncrypted", "")))
    if not isinstance(secret, dict) or not secret.get("token"):
        raise ValueError("阅微凭据无有效 token")
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
        status_code, body, raw = json_http_request(base_url, token, configured_body, str(request.get("endpoint") or "rest/lottery/lottery-v2"))
        if status_code in (401, 403, 429):
            return "paused", f"阅微认证/风控响应 HTTP {status_code}"
        if status_code < 200 or status_code >= 300:
            return "failed", f"抽卡请求 HTTP {status_code}: {redact_message(raw)}"
        return "success", f"野社抽卡完成：{redact_message(json.dumps(body, ensure_ascii=False)[:300])}"
    if task_type == "yeshe_checkin":
        status_code, lore, raw = json_http_request(base_url, token, configured_body, str(request.get("endpoint") or "rest/community/get-daily-lore"))
        if status_code in (401, 403, 429):
            return "paused", f"阅微认证/风控响应 HTTP {status_code}"
        if status_code < 200 or status_code >= 300:
            return "failed", f"获取野社每日轶闻失败 HTTP {status_code}: {redact_message(raw)}"
        lore_id = nested_value(lore, "data", "id") or nested_value(lore, "data", "loreId") or nested_value(lore, "id")
        claimed = bool(nested_value(lore, "data", "isFinish") or nested_value(lore, "data", "claimed"))
        if claimed:
            return "success", "野社零点签到已完成，无需重复提交"
        if not lore_id:
            return "failed", "阅微每日轶闻响应缺少 userLoreId"
        status_code, body, raw = json_http_request(base_url, token, {"userLoreId": int(lore_id)}, str(request.get("completeEndpoint") or "rest/community/complete-daily-lore"))
        if status_code in (401, 403, 429):
            return "paused", f"阅微认证/风控响应 HTTP {status_code}"
        if status_code < 200 or status_code >= 300:
            return "failed", f"野社签到提交失败 HTTP {status_code}: {redact_message(raw)}"
        return "success", f"野社零点签到完成：{redact_message(json.dumps(body, ensure_ascii=False)[:300])}"
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
        duration_seconds = duration_minutes * 60
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
        return ("success", f"云端自动阅读完成 {completed} 本，累计 {duration_minutes} 分钟") if completed else ("failed", "没有找到可阅读的图书")
    return "failed", f"未知阅微任务类型：{task_type}"


def execute_task(task: dict[str, Any]) -> tuple[str, str]:
    if task.get("taskType") in {"http"}:
        return execute_http_task(task)
    if task.get("taskType") in {"yeshe_checkin", "yeshe_draw_card", "cloud_auto_read"}:
        return execute_reamicro_task(task)
    return "failed", f"未知任务类型：{task.get('taskType')}"


async def task_scheduler_loop() -> None:
    while True:
        try:
            tasks = load_tasks()
            now = int(datetime.now(timezone.utc).timestamp() * 1000)
            changed = False
            for task_id, task in tasks.items():
                if not task.get("enabled", True) or task.get("status") in {"paused", "cancelled", "running"}:
                    continue
                next_run = int(task.get("nextRunAt", 0))
                if next_run > now:
                    continue
                task["status"] = "running"
                task["lastRunAt"] = now
                changed = True
                save_tasks(tasks)
                result, message = await asyncio.to_thread(execute_task, task)
                task_log(task_id, message, "ERROR" if result == "failed" else "WARN" if result == "paused" else "INFO")
                task["status"] = result
                task["lastMessage"] = message
                task["runCount"] = int(task.get("runCount", 0)) + 1
                task["nextRunAt"] = next_task_run(task, now) if result != "paused" else 0
                changed = True
                save_tasks(tasks)
            if changed:
                save_tasks(tasks)
        except Exception as error:
            print(f"task scheduler failed: {error}", flush=True)
        await asyncio.sleep(15)

app = FastAPI(title="ReaMicro API", version=API_VERSION)


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


def sync_module_release() -> dict[str, Any] | None:
    config = load_config()
    repository = config["githubRepository"]
    if config.get("githubIncludePrerelease"):
        releases = github_json(f"https://api.github.com/repos/{repository}/releases?per_page=20")
        release = next((item for item in releases if not item.get("draft")), None)
    else:
        release = github_json(f"https://api.github.com/repos/{repository}/releases/latest")
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
    RELEASE_ROOT.mkdir(parents=True, exist_ok=True)
    apk_path = RELEASE_ROOT / "latest.apk"
    metadata_path = RELEASE_ROOT / "latest.json"
    build_time = release_timestamp(asset.get("updated_at") or release.get("published_at", ""))
    previous = {}
    if metadata_path.is_file():
        try:
            previous = json.loads(metadata_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            previous = {}
    if previous.get("assetId") != asset.get("id") or previous.get("buildTime") != build_time or not apk_path.is_file():
        temp = RELEASE_ROOT / ".latest.apk.tmp"
        with urllib.request.urlopen(github_request(asset["browser_download_url"]), timeout=120) as source, temp.open("wb") as target:
            shutil.copyfileobj(source, target)
        temp.replace(apk_path)
    sha256 = hashlib.sha256(apk_path.read_bytes()).hexdigest()
    tag = str(release.get("tag_name") or release.get("name") or "").strip()
    version_name = tag.lstrip("vV").split("+")[0]
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
    }
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    return metadata


async def release_sync_loop() -> None:
    while True:
        try:
            await asyncio.to_thread(sync_module_release)
        except Exception as error:
            print(f"module release sync failed: {error}", flush=True)
        await asyncio.sleep(load_config()["releaseSyncSeconds"])


@app.on_event("startup")
async def start_release_sync() -> None:
    asyncio.create_task(release_sync_loop())
    asyncio.create_task(task_scheduler_loop())


def response(data: Any = None, code: str = "OK", message: str = "", request_id: str = "") -> dict[str, Any]:
    return {
        "code": code,
        "message": message,
        "data": data,
        "requestId": request_id or "req_" + secrets.token_hex(8),
        "serverTime": datetime.now(timezone.utc).isoformat(),
    }


def password_hash(password: str, salt: bytes | None = None) -> str:
    salt = salt or secrets.token_bytes(16)
    digest = _hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 210_000)
    return "pbkdf2$210000$" + base64.urlsafe_b64encode(salt).decode() + "$" + base64.urlsafe_b64encode(digest).decode()


def password_matches(password: str, encoded: str) -> bool:
    try:
        scheme, rounds, salt_text, digest_text = encoded.split("$", 3)
        if scheme != "pbkdf2":
            return False
        salt = base64.urlsafe_b64decode(salt_text.encode())
        expected = base64.urlsafe_b64decode(digest_text.encode())
        actual = _hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, int(rounds))
        return hmac.compare_digest(actual, expected)
    except (ValueError, TypeError):
        return False


def generate_long_secret(byte_length: int = 48) -> str:
    """生成适合 API Key 或初始密码的 URL 安全随机值。"""
    return secrets.token_urlsafe(max(32, byte_length))


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
    if not primary_admin_configured(config):
        if not ADMIN_PASSWORD:
            raise HTTPException(status_code=503, detail="未配置主管理员初始密码 REAMICRO_ADMIN_PASSWORD")
        if credentials and hmac.compare_digest(credentials.username, ADMIN_USERNAME) and hmac.compare_digest(credentials.password, ADMIN_PASSWORD):
            if allow_bootstrap:
                return {"username": ADMIN_USERNAME, "role": "primary", "needsSetup": True}
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
    material = (str(config.get("secretKey", "")) or SECRET_KEY or ADMIN_PASSWORD or encoded).encode("utf-8")
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
    api_key = str(config.get("apiKey", ""))
    if api_key and api_key_value and hmac.compare_digest(api_key_value, api_key):
        return
    accounts = config.get("accounts", {})
    if account_name and account_password and isinstance(accounts, dict):
        encoded = accounts.get(account_name)
        if encoded and password_matches(account_password, str(encoded)):
            return
    allowlist = set(config.get("hostAccountAllowlist", []))
    if host_account_id and host_account_id in allowlist:
        return
    if config.get("allowPublic", True) and not api_key and not accounts and not allowlist:
        return
    raise HTTPException(status_code=401, detail=response(code="AUTH_REQUIRED", message="认证信息无效"))


def resolve_identity(api_key_value: str | None, account_name: str | None, account_password: str | None, host_account_id: str | None) -> str:
    config = load_config()
    api_key = str(config.get("apiKey", ""))
    if api_key and api_key_value and hmac.compare_digest(api_key_value, api_key):
        return "key:" + hashlib.sha256(api_key_value.encode()).hexdigest()[:24]
    encoded = str(config.get("accounts", {}).get(account_name or "", ""))
    if account_name and account_password and encoded and password_matches(account_password, encoded):
        return "account:" + account_name
    if host_account_id and host_account_id in set(config.get("hostAccountAllowlist", [])):
        return "host:" + host_account_id
    if config.get("allowPublic", True) and not api_key and not config.get("accounts") and not config.get("hostAccountAllowlist"):
        return "public"
    raise HTTPException(status_code=401, detail=response(code="AUTH_REQUIRED", message="认证信息无效"))


async def authenticated(
    x_reamicro_api_key: str | None = Header(default=None),
    x_reamicro_account: str | None = Header(default=None),
    x_reamicro_password: str | None = Header(default=None),
    x_reamicro_host_account_id: str | None = Header(default=None),
) -> None:
    check_request_auth(x_reamicro_api_key, x_reamicro_account, x_reamicro_password, x_reamicro_host_account_id)


async def task_owner(
    x_reamicro_api_key: str | None = Header(default=None),
    x_reamicro_account: str | None = Header(default=None),
    x_reamicro_password: str | None = Header(default=None),
    x_reamicro_host_account_id: str | None = Header(default=None),
) -> str:
    identity = resolve_identity(x_reamicro_api_key, x_reamicro_account, x_reamicro_password, x_reamicro_host_account_id)
    if identity == "public":
        raise HTTPException(status_code=401, detail=response(code="AUTH_REQUIRED", message="云任务需要非公开认证"))
    return identity


async def backup_owner(
    x_reamicro_api_key: str | None = Header(default=None),
    x_reamicro_account: str | None = Header(default=None),
    x_reamicro_password: str | None = Header(default=None),
    x_reamicro_host_account_id: str | None = Header(default=None),
) -> str:
    config = load_config()
    api_key = str(config.get("apiKey", ""))
    if api_key and x_reamicro_api_key and hmac.compare_digest(api_key, x_reamicro_api_key):
        identity = "key:" + x_reamicro_api_key
    elif x_reamicro_account and x_reamicro_password and password_matches(
        x_reamicro_password,
        str(config.get("accounts", {}).get(x_reamicro_account, "")),
    ):
        identity = "account:" + x_reamicro_account
    elif x_reamicro_host_account_id and x_reamicro_host_account_id in set(config.get("hostAccountAllowlist", [])):
        identity = "host:" + x_reamicro_host_account_id
    else:
        raise HTTPException(status_code=401, detail=response(code="AUTH_REQUIRED", message="备份功能需要非公开认证"))
    return hashlib.sha256(identity.encode("utf-8")).hexdigest()[:32]


@app.middleware("http")
async def security_middleware(request: Request, call_next):
    request_id = request.headers.get("X-Request-Id", "").strip()[:80] or "req_" + secrets.token_hex(8)
    request.state.request_id = request_id
    client_host = request.client.host if request.client else "unknown"
    rate_key = f"{client_host}:{request.url.path}"
    if not allow_rate_limit(rate_key):
        audit_event("rate_limit", actor=client_host, request_id=request_id, success=False, metadata={"path": request.url.path})
        return JSONResponse(
            status_code=429,
            content=response(code="RATE_LIMITED", message="请求过于频繁，请稍后重试", request_id=request_id),
            headers={"Retry-After": str(RATE_WINDOW_SECONDS), "X-Request-Id": request_id},
        )
    try:
        result = await call_next(request)
    except Exception:
        audit_event("request_error", actor=client_host, request_id=request_id, success=False, metadata={"path": request.url.path})
        raise
    result.headers["X-Request-Id"] = request_id
    return result


@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException) -> JSONResponse:
    request_id = getattr(request.state, "request_id", "")
    detail = exc.detail if isinstance(exc.detail, dict) else response(code="HTTP_ERROR", message=str(exc.detail), request_id=request_id)
    if isinstance(detail, dict):
        detail["requestId"] = request_id or detail.get("requestId", "")
    return JSONResponse(status_code=exc.status_code, content=detail, headers={**(exc.headers or {}), "X-Request-Id": request_id})


@app.get("/v1/health")
async def health(_: None = Depends(authenticated)) -> dict[str, Any]:
    return response({"status": "ok", "serverId": load_config()["serverId"]})


def admin_setup_page(message: str = "", actor: dict[str, Any] | None = None) -> str:
    message_html = f"<p class='msg'>{html.escape(message)}</p>" if message else ""
    actor = actor or {"username": ADMIN_USERNAME, "role": "primary", "needsSetup": True}
    csrf_value = admin_csrf_token(load_config(), actor)
    return f"""<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>初始化主管理员</title><style>body{{font-family:system-ui,-apple-system,sans-serif;max-width:560px;margin:48px auto;padding:0 18px;background:#f5f7fb;color:#182230}}main{{background:#fff;border-radius:14px;padding:26px;box-shadow:0 5px 24px #0001}}label{{display:block;margin:14px 0 5px;font-weight:600}}input{{box-sizing:border-box;width:100%;padding:10px;border:1px solid #ccd4df;border-radius:8px;font:inherit}}button{{margin-top:20px;padding:11px 18px;border:0;border-radius:8px;background:#2563eb;color:#fff;font-weight:600}}.msg{{padding:10px;background:#fef2f2;color:#991b1b;border-radius:8px}}small{{color:#64748b}}</style></head>
<body><main><h1>首次设置主管理员</h1><p>当前环境变量账号仅用于首次引导。完成后将停用初始密码，并改用数据卷内保存的加盐密码哈希。</p>{message_html}
<form method='post' action='/admin/setup'><input type='hidden' name='csrf_token' value='{csrf_value}'><label>主管理员用户名</label><input name='username' value='{html.escape(ADMIN_USERNAME, quote=True)}' autocomplete='username' required>
<label>新密码（至少 12 位）</label><input type='password' name='password' minlength='12' autocomplete='new-password' required>
<label>确认新密码</label><input type='password' name='password_confirm' minlength='12' autocomplete='new-password' required><button type='submit'>完成初始化</button></form>
<p><small>初始化信息保存于 /data/config/server.json；密码只保存 PBKDF2 哈希。</small></p></main></body></html>"""


def admin_page(config: dict[str, Any], message: str = "", actor: dict[str, Any] | None = None, secret_notice: str = "") -> str:
    def esc(value: Any) -> str:
        return html.escape(str(value), quote=True)

    checked_public = "checked" if config.get("allowPublic") else ""
    checked_pre = "checked" if config.get("githubIncludePrerelease") else ""
    feature_text = ",".join(config.get("features", []))
    package_rows = []
    for kind in sorted(PACKAGE_KINDS):
        for manifest_path in sorted((PACKAGE_ROOT / kind).glob("*/manifest.json")):
            try:
                item = json.loads(manifest_path.read_text(encoding="utf-8"))
                package_rows.append(
                    f"<tr><td>{esc(kind)}</td><td>{esc(item.get('packageId', manifest_path.parent.name))}</td>"
                    f"<td>{esc(item.get('version', ''))}</td><td>{esc(item.get('buildTime', ''))}</td></tr>"
                )
            except (OSError, ValueError):
                continue
    package_table = "".join(package_rows) or "<tr><td colspan='4'>暂无内容包</td></tr>"
    task_rows = []
    for task in load_tasks().values():
        task_rows.append(
            f"<tr><td>{esc(task.get('taskType', ''))}</td><td>{esc(task.get('status', ''))}</td>"
            f"<td>{esc(task.get('lastMessage', ''))}</td><td>{esc(task.get('nextRunAt', ''))}</td></tr>"
        )
    task_table = "".join(task_rows) or "<tr><td colspan='4'>暂无云任务</td></tr>"
    credential_rows = []
    for credential in load_credentials().values():
        credential_rows.append(
            f"<tr><td>{esc(credential.get('id', ''))}</td><td>{esc(credential.get('owner', ''))}</td>"
            f"<td>{esc(credential.get('label', ''))}</td><td>{esc(credential.get('lastVerifyMessage', ''))}</td></tr>"
        )
    credential_table = "".join(credential_rows) or "<tr><td colspan='4'>暂无阅微凭据，请由模块客户端上传</td></tr>"
    actor = actor or {"username": "", "role": "subadmin"}
    csrf_html = f"<input type='hidden' name='csrf_token' value='{esc(admin_csrf_token(config, actor))}'>"
    admin_rows = []
    for username, record in sorted(config.get("adminAccounts", {}).items()):
        if not isinstance(record, dict):
            continue
        enabled = bool(record.get("enabled", True))
        admin_rows.append(
            f"<tr><td>{esc(username)}</td><td>{'启用' if enabled else '停用'}</td><td>{esc(record.get('createdAt', ''))}</td><td>"
            f"<form class='inline' method='post' action='/admin/admins/reset'>{csrf_html}<input type='hidden' name='username' value='{esc(username)}'><button class='compact secondary' type='submit'>重置密码</button></form>"
            f"<form class='inline' method='post' action='/admin/admins/toggle'>{csrf_html}<input type='hidden' name='username' value='{esc(username)}'><button class='compact secondary' type='submit'>{'停用' if enabled else '启用'}</button></form>"
            f"<form class='inline' method='post' action='/admin/admins/delete'>{csrf_html}<input type='hidden' name='username' value='{esc(username)}'><button class='compact danger' type='submit'>删除</button></form></td></tr>"
        )
    admin_table = "".join(admin_rows) or "<tr><td colspan='4'>暂无子管理员</td></tr>"
    admin_management = ""
    if actor.get("role") == "primary":
        admin_management = f"""<hr><h2>子管理员</h2><p><small>子管理员可以协助维护服务器设置、内容包和云任务，但不能新增、重置、停用或删除其他管理员。</small></p>
<form method='post' action='/admin/admins/create'>{csrf_html}<div class='row'><div><label>子管理员用户名</label><input name='username' placeholder='operator' required></div><div><label>初始密码（可留空自动生成）</label><input type='password' name='password' minlength='12' placeholder='至少 12 位'></div></div><label>权限（逗号分隔）</label><input name='permissions' value='settings:write,packages:write,tasks:write'><button type='submit'>创建子管理员</button></form>
<table><thead><tr><th>用户名</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead><tbody>{admin_table}</tbody></table>"""
    return f"""<!doctype html>
<html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>ReaMicro API 管理后台</title>
<style>body{{font-family:system-ui,-apple-system,sans-serif;max-width:980px;margin:32px auto;padding:0 18px;background:#f5f7fb;color:#182230}}main{{background:white;border-radius:14px;padding:24px;box-shadow:0 5px 24px #0001}}label{{display:block;margin:14px 0 5px;font-weight:600}}input,textarea,select{{box-sizing:border-box;width:100%;padding:10px;border:1px solid #ccd4df;border-radius:8px;font:inherit;background:white}}textarea{{min-height:72px}}.row{{display:grid;grid-template-columns:1fr 1fr;gap:16px}}button{{margin-top:20px;padding:10px 16px;border:0;border-radius:8px;background:#2563eb;color:#fff;font-weight:600;cursor:pointer}}.secondary{{background:#475569;margin-left:8px}}.danger{{background:#b91c1c}}.compact{{margin:2px;padding:6px 9px}}.inline{{display:inline}}.msg{{padding:10px;background:#ecfdf5;color:#166534;border-radius:8px}}.secret{{padding:12px;background:#fff7ed;color:#9a3412;border-radius:8px;overflow-wrap:anywhere}}small{{color:#64748b}}table{{width:100%;border-collapse:collapse;margin-top:12px}}th,td{{padding:8px;border-bottom:1px solid #e2e8f0;text-align:left}}details{{margin-top:18px;padding:12px;border:1px solid #e2e8f0;border-radius:8px}}</style></head>
<body><main><h1>ReaMicro API 管理后台</h1><p><small>当前管理员：{esc(actor.get('username', ''))}（{'主管理员' if actor.get('role') == 'primary' else '子管理员'}） · 默认端口：5222 · 配置保存到 Docker 数据卷 /data/config/server.json</small></p>
{f"<p class='msg'>{esc(message)}</p>" if message else ""}
{f"<p class='secret'><strong>请立即保存，关闭页面后不再显示：</strong><br>{esc(secret_notice)}</p>" if secret_notice else ""}
<form method='post' action='/admin/settings'>{csrf_html}
<div class='row'><div><label>服务器 ID</label><input name='server_id' value='{esc(config.get("serverId", ""))}'></div><div><label>最低模块版本</label><input name='min_module_version' value='{esc(config.get("minModuleVersion", ""))}'></div></div>
<label>API Key（当前：{'已设置' if config.get("apiKey") else '未设置'}）</label><input type='password' name='api_key' placeholder='留空保持当前值'><label><input type='checkbox' name='generate_api_key'> 自动生成 64 位以上长密钥</label><label><input type='checkbox' name='clear_api_key'> 清除 API Key</label>
<label>服务器加密密钥（当前：{'已设置' if config.get("secretKey") else '未设置'}）</label><label><input type='checkbox' name='generate_secret_key'> 当前未设置时生成新的服务器加密长密钥</label><small>已有密钥不能直接替换，否则历史阅微凭据将无法解密。</small>
<label><input type='checkbox' name='allow_public' {checked_public}> 允许公开访问（无 API Key 时）</label>
<label>阅微账号白名单（每行一个账号 ID）</label><textarea name='host_account_allowlist'>{esc(chr(10).join(config.get('hostAccountAllowlist', [])))}</textarea>
<div class='row'><div><label>新增独立账号</label><input name='account_name' placeholder='用户名'></div><div><label>新增账号密码</label><input type='password' name='account_password' placeholder='留空不修改'></div></div>
<label>删除独立账号（可选，填写用户名）</label><input name='remove_account' placeholder='username'>
<label>功能列表（逗号分隔）</label><textarea name='features'>{esc(feature_text)}</textarea>
<label>内容包签名公钥（Base64 X.509 Ed25519）</label><textarea name='signing_public_key'>{esc(config.get("signingPublicKey", ""))}</textarea>
<div class='row'><div><label>GitHub 仓库</label><input name='github_repository' value='{esc(config.get("githubRepository", ""))}'></div><div><label>Release 同步秒数（至少 300）</label><input name='release_sync_seconds' type='number' value='{esc(config.get("releaseSyncSeconds", 1800))}'></div></div>
<label>GitHub Token（当前：{'已设置' if config.get("githubToken") else '未设置'}）</label><input type='password' name='github_token' placeholder='留空保持当前值'><label><input type='checkbox' name='clear_github_token'> 清除 GitHub Token</label>
<label><input type='checkbox' name='github_include_prerelease' {checked_pre}> 包含预发布 Release</label>
<label>Release versionCode 覆盖值</label><input name='release_version_code' type='number' value='{esc(config.get("releaseVersionCode", 0))}'>
<button type='submit'>保存设置</button><button class='secondary' type='submit' formaction='/admin/sync'>立即同步模块 Release</button>
</form><hr><h2>内容包上传/更新</h2>
<p><small>上传后立即覆盖当前版本；旧文件保留在对应目录的 history 中。kind 支持 online_source、epub_style、highlight_style、association_source、theme。</small></p>
<form method='post' action='/admin/packages/upload' enctype='multipart/form-data'>{csrf_html}
<div class='row'><div><label>内容类型</label><input name='kind' placeholder='online_source' required></div><div><label>包 ID</label><input name='package_id' placeholder='source.example' required></div></div>
<div class='row'><div><label>版本号</label><input name='version' placeholder='1.0.0' required></div><div><label>稳定内容 ID（书源建议固定）</label><input name='content_id' placeholder='source.example'></div></div>
<label>旧 ID/域名别名（逗号分隔）</label><input name='aliases' placeholder='online_old_hash,old.example.com'>
<label>Ed25519 签名（可选，需与服务器公钥匹配）</label><input name='signature' placeholder='Base64 签名'>
<label>内容文件</label><input type='file' name='payload' required>
<button type='submit'>上传并发布</button></form><h3>已发布内容包</h3><table><thead><tr><th>类型</th><th>包 ID</th><th>版本</th><th>构建时间</th></tr></thead><tbody>{package_table}</tbody></table>
<hr><h2>云任务</h2><form method='post' action='/admin/tasks/create'>{csrf_html}
<h3>已上传阅微凭据</h3><table><thead><tr><th>凭据 ID</th><th>所有者</th><th>名称</th><th>验证状态</th></tr></thead><tbody>{credential_table}</tbody></table>
<div class='row'><div><label>任务类型</label><select name='task_type'><option value='yeshe_checkin'>野社零点签到</option><option value='yeshe_draw_card'>野社自动抽卡</option><option value='cloud_auto_read'>云端自动阅读</option><option value='http'>通用 HTTPS 请求</option></select></div><div><label>每日执行时间</label><input name='time_of_day' value='00:05' placeholder='HH:MM'></div></div>
<div class='row'><div><label>阅微凭据 ID</label><input name='credential_id' placeholder='上传凭据接口返回的 id'></div><div><label>任务所有者标识</label><input name='owner' value='admin'></div></div>
<div class='row'><div><label>阅读时长（分钟）</label><input name='duration_minutes' type='number' min='1' max='720' value='30'></div><div><label>最近阅读数量</label><input name='recent_limit' type='number' min='1' max='20' value='1'></div></div>
<label>自定义图书 JSON（可选，留空读取最近阅读记录）</label><textarea name='request_body' placeholder='[{{"cloudBookId":123,"bookId":123,"name":"书名"}}]'></textarea>
<details><summary>通用 HTTP 任务高级字段</summary><label>请求 URL</label><input name='request_url' placeholder='https://api.example.com/checkin'><div class='row'><div><label>HTTP 方法</label><input name='request_method' value='POST'></div><div><label>执行间隔（秒）</label><input name='interval_seconds' type='number' value='86400'></div></div><label>请求头 JSON</label><textarea name='request_headers' placeholder='{{"X-Example":"value"}}'></textarea></details><button type='submit'>创建云任务</button></form>
<h3>任务状态</h3><table><thead><tr><th>类型</th><th>状态</th><th>最近结果</th><th>下次执行</th></tr></thead><tbody>{task_table}</tbody></table>
{admin_management}<hr><p><a href='/admin/audit'>审计日志</a>　<a href='/v1/health'>健康检查</a>　<a href='/v1/meta'>能力信息</a></p></main></body></html>"""


@app.get("/admin", response_class=HTMLResponse)
async def admin(credentials: HTTPBasicCredentials | None = Depends(basic_security)) -> HTMLResponse:
    actor = require_admin(credentials, allow_bootstrap=True)
    if actor.get("needsSetup"):
        return HTMLResponse(admin_setup_page(actor=actor))
    return HTMLResponse(admin_page(load_config(), actor=actor))


@app.get("/admin/audit", response_class=HTMLResponse)
async def admin_audit(credentials: HTTPBasicCredentials | None = Depends(basic_security)) -> HTMLResponse:
    actor = require_admin(credentials)
    require_admin_permission(actor, "audit:read")
    rows = []
    if AUDIT_PATH.is_file():
        for line in AUDIT_PATH.read_text(encoding="utf-8").splitlines()[-500:][::-1]:
            try:
                event = json.loads(line)
            except ValueError:
                continue
            rows.append(
                "<tr>"
                f"<td>{html.escape(str(event.get('at', '')))}</td>"
                f"<td>{html.escape(str(event.get('actor', '')))}</td>"
                f"<td>{html.escape(str(event.get('action', '')))}</td>"
                f"<td>{'成功' if event.get('success') else '失败'}</td>"
                f"<td><code>{html.escape(json.dumps(event.get('metadata', {}), ensure_ascii=False))}</code></td>"
                "</tr>"
            )
    table = "".join(rows) or "<tr><td colspan='5'>暂无审计记录</td></tr>"
    return HTMLResponse(
        "<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
        "<title>审计日志</title><style>body{font-family:system-ui;margin:30px;background:#f5f7fb}main{background:#fff;padding:24px;border-radius:12px}table{width:100%;border-collapse:collapse}td,th{padding:8px;border-bottom:1px solid #ddd;text-align:left}code{white-space:pre-wrap}</style></head>"
        f"<body><main><h1>后台审计日志</h1><p><a href='/admin'>返回管理后台</a></p><table><thead><tr><th>时间</th><th>操作者</th><th>操作</th><th>结果</th><th>详情</th></tr></thead><tbody>{table}</tbody></table></main></body></html>"
    )


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
    clear_api_key: str | None = Form(None),
    generate_api_key: str | None = Form(None),
    generate_secret_key: str | None = Form(None),
    clear_github_token: str | None = Form(None),
    github_include_prerelease: str | None = Form(None),
    release_sync_seconds: int = Form(1800),
    release_version_code: int = Form(0),
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
    next_config = {
        "serverId": server_id.strip() or current["serverId"],
        "minModuleVersion": min_module_version.strip() or current["minModuleVersion"],
        "apiKey": "" if clear_api_key is not None else (generated_api_key or api_key or current.get("apiKey", "")),
        "secretKey": generated_secret_key or current.get("secretKey", "") or SECRET_KEY,
        "allowPublic": allow_public is not None,
        "hostAccountAllowlist": [item.strip() for item in host_account_allowlist.splitlines() if item.strip()],
        "features": [item.strip() for item in features.split(",") if item.strip()],
        "signingPublicKey": signing_public_key.strip(),
        "githubRepository": github_repository.strip() or current["githubRepository"],
        "githubToken": "" if clear_github_token is not None else (github_token or current.get("githubToken", "")),
        "githubIncludePrerelease": github_include_prerelease is not None,
        "releaseSyncSeconds": release_sync_seconds,
        "releaseVersionCode": release_version_code,
        "primaryAdmin": current.get("primaryAdmin", {}),
        "adminAccounts": current.get("adminAccounts", {}),
    }
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
    return HTMLResponse(admin_page(saved, "设置已保存", actor=actor, secret_notice="\n".join(notices)))


@app.post("/admin/admins/create", response_class=HTMLResponse)
async def admin_create_subadmin(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    username: str = Form(""),
    password: str = Form(""),
    permissions: str = Form("settings:write,packages:write,tasks:write"),
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
            "permissions": sorted({item.strip() for item in permissions.split(",") if item.strip()}),
        }
        current["adminAccounts"] = admins
        saved = save_config(current)
        audit_event("subadmin_created", audit_actor(actor), success=True, metadata={"username": username})
        notice = f"子管理员 {username} 的初始密码：{password}" if generated else ""
        return HTMLResponse(admin_page(saved, f"子管理员 {username} 已创建", actor=actor, secret_notice=notice))
    except ValueError as error:
        return HTMLResponse(admin_page(current, str(error), actor=actor), status_code=400)


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
        return HTMLResponse(admin_page(current, "子管理员不存在", actor=actor), status_code=404)
    password = generate_long_secret(24)
    record = dict(record)
    record["passwordHash"] = password_hash(password)
    record["updatedAt"] = datetime.now(timezone.utc).isoformat()
    admins[username] = record
    current["adminAccounts"] = admins
    saved = save_config(current)
    audit_event("subadmin_password_reset", audit_actor(actor), success=True, metadata={"username": username})
    return HTMLResponse(admin_page(saved, f"子管理员 {username} 的密码已重置", actor=actor, secret_notice=f"新密码：{password}"))


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
        return HTMLResponse(admin_page(current, "子管理员不存在", actor=actor), status_code=404)
    record = dict(record)
    record["enabled"] = not bool(record.get("enabled", True))
    record["updatedAt"] = datetime.now(timezone.utc).isoformat()
    admins[username] = record
    current["adminAccounts"] = admins
    saved = save_config(current)
    audit_event("subadmin_toggled", audit_actor(actor), success=True, metadata={"username": username, "enabled": record["enabled"]})
    state_text = "启用" if record["enabled"] else "停用"
    return HTMLResponse(admin_page(saved, f"子管理员 {username} 已{state_text}", actor=actor))


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
        return HTMLResponse(admin_page(current, "子管理员不存在", actor=actor), status_code=404)
    admins.pop(username, None)
    current["adminAccounts"] = admins
    saved = save_config(current)
    audit_event("subadmin_deleted", audit_actor(actor), success=True, metadata={"username": username})
    return HTMLResponse(admin_page(saved, f"子管理员 {username} 已删除", actor=actor))


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
        return HTMLResponse(admin_page(load_config(), "模块 Release 已同步", actor=actor))
    except Exception as error:
        return HTMLResponse(admin_page(load_config(), f"同步失败：{error}", actor=actor), status_code=502)


PACKAGE_KINDS = {
    "online_source",
    "epub_style",
    "highlight_style",
    "association_source",
    "theme",
}


def safe_package_segment(value: str) -> str:
    value = value.strip()
    if not value or len(value) > 120 or not value.replace("_", "").replace("-", "").replace(".", "").isalnum():
        raise HTTPException(status_code=400, detail="包标识只能包含字母、数字、点、下划线和短横线")
    return value


def safe_payload_filename(value: str) -> str:
    name = Path(value or "payload.bin").name
    name = "".join(char if (char.isalnum() or char in "._-") else "_" for char in name)
    return name[:120] or "payload.bin"


def package_signature(package_id: str, kind: str, content_id: str, version: str, build_time: int, sha256: str, body: bytes) -> str:
    if not SIGNING_PRIVATE_KEY_FILE:
        return ""
    from cryptography.hazmat.primitives import serialization
    key = serialization.load_pem_private_key(Path(SIGNING_PRIVATE_KEY_FILE).read_bytes(), password=None)
    message = f"{package_id}\n{kind}\n{content_id}\n{version}\n{build_time}\n{sha256}".encode("utf-8") + body
    import base64
    return base64.b64encode(key.sign(message)).decode("ascii")


@app.post("/admin/packages/upload", response_class=HTMLResponse)
async def admin_upload_package(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    kind: str = Form(...),
    package_id: str = Form(...),
    version: str = Form(...),
    content_id: str = Form(""),
    aliases: str = Form(""),
    signature: str = Form(""),
    payload: UploadFile = File(...),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    current_config = load_config()
    require_admin_csrf(current_config, actor, csrf_token)
    require_admin_permission(actor, "packages:write")
    if kind not in PACKAGE_KINDS:
        raise HTTPException(status_code=400, detail="不支持的内容包类型")
    package_id = safe_package_segment(package_id)
    version = safe_package_segment(version)
    stable_id = safe_package_segment(content_id) if content_id.strip() else package_id
    filename = safe_payload_filename(payload.filename or "payload.bin")
    body = await payload.read()
    if not body or len(body) > 50 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="内容文件为空或超过 50 MB")
    package_dir = (PACKAGE_ROOT / kind / package_id).resolve()
    if PACKAGE_ROOT.resolve() not in package_dir.parents:
        raise HTTPException(status_code=400, detail="内容包路径无效")
    package_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = package_dir / "manifest.json"
    if manifest_path.is_file():
        history = package_dir / "history"
        history.mkdir(exist_ok=True)
        old_version = "old"
        old_manifest = {}
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
    payload_path = package_dir / filename
    payload_path.write_bytes(body)
    build_time = int(datetime.now(timezone.utc).timestamp() * 1000)
    digest = hashlib.sha256(body).hexdigest()
    effective_signature = signature.strip() or package_signature(package_id, kind, stable_id, version, build_time, digest, body)
    manifest = {
        "packageId": package_id,
        "kind": kind,
        "version": version,
        "buildTime": build_time,
        "schemaVersion": 1,
        "minModuleVersion": load_config()["minModuleVersion"],
        "sha256": digest,
        "signature": effective_signature,
        "payload": filename,
        "contentId": stable_id,
        "aliases": [item.strip() for item in aliases.split(",") if item.strip()],
        "name": package_id,
        "description": "后台上传内容包",
    }
    temp_manifest = package_dir / ".manifest.json.tmp"
    temp_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    temp_manifest.replace(manifest_path)
    return HTMLResponse(admin_page(load_config(), f"已发布 {kind}/{package_id} {version}", actor=actor))


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
            if not credential or credential.get("owner") != owner:
                raise ValueError("阅微凭据不存在或不属于任务所有者")
        task_id = "task_" + secrets.token_hex(10)
        now = int(datetime.now(timezone.utc).timestamp() * 1000)
        tasks = load_tasks()
        tasks[task_id] = {
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
        }
        save_tasks(tasks)
        task_log(task_id, "管理员创建任务")
        return HTMLResponse(admin_page(load_config(), f"任务 {task_id} 已创建", actor=actor))
    except Exception as error:
        return HTMLResponse(admin_page(load_config(), f"创建任务失败：{error}", actor=actor), status_code=400)


@app.get("/v1/meta")
async def meta(_: None = Depends(authenticated)) -> dict[str, Any]:
    config = load_config()
    return response({
        "apiVersion": API_VERSION,
        "serverId": config["serverId"],
        "features": config["features"],
        "authModes": ["public", "api_key", "account", "host_account_allowlist"],
        "minModuleVersion": config["minModuleVersion"],
        "signingPublicKey": config["signingPublicKey"],
    })


@app.get("/v1/releases/module/latest")
async def latest_release(_: None = Depends(authenticated)) -> dict[str, Any]:
    metadata_path = RELEASE_ROOT / "latest.json"
    if not metadata_path.is_file():
        try:
            await asyncio.to_thread(sync_module_release)
        except Exception as error:
            return response(None, code="SYNC_FAILED", message=f"同步模块版本失败：{error}")
    if not metadata_path.is_file():
        return response(None, code="NOT_CONFIGURED", message="GitHub Release 中没有 APK")
    return response(json.loads(metadata_path.read_text(encoding="utf-8")))


@app.post("/v1/backups/module")
async def upload_module_backup(request: Request, owner: str = Depends(backup_owner)) -> dict[str, Any]:
    body = await request.body()
    if not body or len(body) > 20 * 1024 * 1024:
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="备份为空或超过 20 MB"))
    if not body.startswith(b"PK"):
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="备份不是 ZIP 文件"))
    owner_dir = BACKUP_ROOT / owner / "module"
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
    owner_dir = BACKUP_ROOT / owner / "module"
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
    owner_dir = SECRET_BACKUP_ROOT / owner
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
    owner_dir = SECRET_BACKUP_ROOT / owner
    metadata_path = owner_dir / "latest.json"
    if not metadata_path.is_file():
        raise HTTPException(status_code=404, detail=response(code="BACKUP_NOT_FOUND", message="没有账号密钥备份"))
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    target = (owner_dir / str(metadata.get("file", ""))).resolve()
    if target.parent != owner_dir.resolve() or not target.is_file():
        raise HTTPException(status_code=404, detail=response(code="BACKUP_NOT_FOUND", message="密钥备份文件不存在"))
    return FileResponse(target, media_type="application/octet-stream", filename="reamicro-credentials.rcbak")


def credential_public(value: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": value.get("id", ""),
        "type": value.get("type", "reamicro"),
        "label": value.get("label", "阅微账号"),
        "accountId": value.get("accountId", ""),
        "createdAt": value.get("createdAt", 0),
        "updatedAt": value.get("updatedAt", 0),
        "lastVerifiedAt": value.get("lastVerifiedAt", 0),
        "lastVerifyMessage": value.get("lastVerifyMessage", ""),
    }


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
    if len(token) < 16 or len(token) > 8_192:
        raise HTTPException(status_code=400, detail=response(code="CREDENTIAL_INVALID", message="阅微登录密钥长度无效"))
    base_url = str(payload.get("baseUrl") or "https://api.reamicro.zhendong.ltd/").strip()
    if not base_url.startswith("https://"):
        raise HTTPException(status_code=400, detail=response(code="CREDENTIAL_INVALID", message="阅微 API 地址必须使用 HTTPS"))
    status_code, _, raw = await asyncio.to_thread(
        json_http_request,
        base_url,
        token,
        {"pageNum": 1, "pageSize": 1},
        "rest/read/get-reader-record-list",
    )
    if status_code < 200 or status_code >= 300:
        raise HTTPException(status_code=400, detail=response(code="CREDENTIAL_VERIFY_FAILED", message=f"阅微登录密钥验证失败：HTTP {status_code} {redact_message(raw)}"))
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    credential_id = str(payload.get("id", "")).strip() or "rea_" + secrets.token_hex(10)
    credentials = load_credentials()
    existing = credentials.get(credential_id, {})
    if existing and existing.get("owner") != owner:
        raise HTTPException(status_code=404, detail=response(code="CREDENTIAL_NOT_FOUND", message="凭据不存在"))
    credentials[credential_id] = {
        "id": credential_id,
        "owner": owner,
        "type": "reamicro",
        "label": str(payload.get("label") or "阅微账号")[:100],
        "accountId": str(payload.get("accountId") or "")[:100],
        "secretEncrypted": encrypt_secret({"token": token, "baseUrl": base_url}),
        "createdAt": int(existing.get("createdAt", now)),
        "updatedAt": now,
        "lastVerifiedAt": now,
        "lastVerifyMessage": "验证成功",
    }
    save_credentials(credentials)
    return response(credential_public(credentials[credential_id]))


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


@app.post("/v1/tasks")
async def create_task(request: Request, owner: str = Depends(task_owner)) -> dict[str, Any]:
    try:
        payload = await request.json()
    except ValueError:
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="任务 JSON 无效"))
    if not isinstance(payload, dict) or not payload.get("taskType"):
        raise HTTPException(status_code=400, detail=response(code="TASK_INVALID", message="缺少 taskType"))
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
    }
    task["credentialId"] = credential_id
    task["requestEncrypted"] = encrypt_secret(request_value)
    task.pop("request", None)
    tasks = load_tasks()
    tasks[task_id] = task
    save_tasks(tasks)
    task_log(task_id, "任务已创建")
    return response(public_task(task))


@app.get("/v1/tasks")
async def list_tasks(owner: str = Depends(task_owner)) -> dict[str, Any]:
    tasks = [public_task(task, include_request=False) for task in load_tasks().values() if task.get("owner") == owner]
    return response({"items": tasks})


def public_task(task: dict[str, Any], include_request: bool = False) -> dict[str, Any]:
    value = {key: item for key, item in task.items() if key != "requestEncrypted"}
    value["credentialId"] = task_credential_id(task)
    if include_request and task.get("requestEncrypted"):
        try:
            value["request"] = decrypt_secret(str(task["requestEncrypted"]))
        except Exception:
            value["request"] = {}
    return value


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
    path = TASK_LOG_ROOT / f"{task_id}.log"
    items = []
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines()[-200:]:
            try:
                items.append(json.loads(line))
            except ValueError:
                continue
    return response({"items": items})


@app.get("/v1/releases/module/download")
async def download_release(_: None = Depends(authenticated)) -> FileResponse:
    apk_path = RELEASE_ROOT / "latest.apk"
    if not apk_path.is_file():
        raise HTTPException(status_code=404, detail=response(code="NOT_FOUND", message="模块 APK 尚未同步"))
    return FileResponse(apk_path, media_type="application/vnd.android.package-archive", filename="ReaMicro-Extend-latest.apk")


@app.get("/v1/packages")
async def packages(kind: str = Query(...), _: None = Depends(authenticated)) -> dict[str, Any]:
    if not kind.replace("_", "").replace("-", "").isalnum():
        raise HTTPException(status_code=400, detail=response(code="INVALID_KIND", message="内容包类型无效"))
    items = []
    for manifest_path in sorted((PACKAGE_ROOT / kind).glob("*/manifest.json")):
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest.setdefault("kind", kind)
            manifest.setdefault("downloadUrl", f"/v1/packages/{kind}/{manifest_path.parent.name}/download")
            items.append(manifest)
        except (OSError, ValueError):
            continue
    return response({"kind": kind, "items": items})


@app.get("/v1/packages/{package_kind}/{package_id}/download")
async def package_download(package_kind: str, package_id: str, _: None = Depends(authenticated)) -> FileResponse:
    if not package_kind.replace("_", "").replace("-", "").isalnum() or not package_id.replace("_", "").replace("-", "").isalnum():
        raise HTTPException(status_code=400, detail=response(code="INVALID_PACKAGE", message="内容包标识无效"))
    manifest_path = PACKAGE_ROOT / package_kind / package_id / "manifest.json"
    if not manifest_path.is_file():
        raise HTTPException(status_code=404, detail=response(code="NOT_FOUND", message="内容包不存在"))
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        payload = (manifest_path.parent / manifest["payload"]).resolve()
        if PACKAGE_ROOT.resolve() not in payload.parents:
            raise ValueError("invalid payload path")
    except (OSError, ValueError, KeyError):
        raise HTTPException(status_code=500, detail=response(code="PACKAGE_INVALID", message="内容包清单无效"))
    if not payload.is_file():
        raise HTTPException(status_code=404, detail=response(code="NOT_FOUND", message="内容包文件不存在"))
    return FileResponse(payload, media_type="application/octet-stream", filename=payload.name)


@app.get("/v1/diagnostics")
async def diagnostics(_: None = Depends(authenticated)) -> dict[str, Any]:
    config = load_config()
    return response({"apiVersion": API_VERSION, "sha256ApiKeyConfigured": bool(config.get("apiKey"))})
