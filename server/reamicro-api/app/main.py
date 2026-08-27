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

API_VERSION = "1.1"
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
RELEASE_STATUS_PATH = RELEASE_ROOT / "sync-status.json"
BACKUP_ROOT = Path(os.getenv("REAMICRO_BACKUP_ROOT", "/data/backups"))
SECRET_BACKUP_ROOT = Path(os.getenv("REAMICRO_SECRET_BACKUP_ROOT", "/data/backups/secrets"))
SERVER_BACKUP_ROOT = Path(os.getenv("REAMICRO_SERVER_BACKUP_ROOT", "/data/backups/server"))
TASK_ROOT = Path(os.getenv("REAMICRO_TASK_ROOT", "/data/tasks"))
TASKS_PATH = TASK_ROOT / "tasks.json"
STATE_DB_PATH = Path(os.getenv("REAMICRO_STATE_DB", "/data/state/reamicro.sqlite3"))
TASK_LOG_ROOT = TASK_ROOT / "logs"
AUDIT_ROOT = Path(os.getenv("REAMICRO_AUDIT_ROOT", "/data/audit"))
AUDIT_PATH = AUDIT_ROOT / "events.jsonl"
ACCOUNT_ROOT = Path(os.getenv("REAMICRO_ACCOUNT_ROOT", "/data/accounts"))
ACCOUNT_PATH = ACCOUNT_ROOT / "credentials.json"
SECRET_KEY = os.getenv("REAMICRO_SECRET_KEY", "")
ADMIN_USERNAME = os.getenv("REAMICRO_ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = os.getenv("REAMICRO_ADMIN_PASSWORD", "")
SIGNING_PRIVATE_KEY_FILE = os.getenv("REAMICRO_SIGNING_PRIVATE_KEY_FILE", "")
GITHUB_WEBHOOK_SECRET = os.getenv("REAMICRO_GITHUB_WEBHOOK_SECRET", "")
config_lock = threading.RLock()
state_store_lock = threading.RLock()
state_store: StateStore | None = None
rate_lock = threading.RLock()
rate_buckets: dict[str, list[float]] = {}
RATE_LIMIT = max(int(os.getenv("REAMICRO_RATE_LIMIT", "120")), 10)
RATE_WINDOW_SECONDS = 60
ADMIN_SESSION_SECONDS = max(int(os.getenv("REAMICRO_ADMIN_SESSION_SECONDS", "43200")), 900)
ADMIN_COOKIE_SECURE = os.getenv("REAMICRO_ADMIN_COOKIE_SECURE", "true").lower() == "true"
ADMIN_SESSION_COOKIE = "reamicro_admin_session"
metrics_lock = threading.RLock()
metrics_counters: dict[str, int] = {"requests": 0, "errors": 0, "rate_limited": 0}
metrics_routes: dict[str, int] = {}
RUN_SCHEDULER = os.getenv("REAMICRO_RUN_SCHEDULER", "true").lower() == "true"
RUN_RELEASE_SYNC = os.getenv("REAMICRO_RUN_RELEASE_SYNC", "true").lower() == "true"


def env_bool(name: str, default: bool) -> bool:
    return os.getenv(name, str(default)).lower() == "true"


def default_config() -> dict[str, Any]:
    return {
        "serverId": os.getenv("REAMICRO_SERVER_ID", "reamicro-api"),
        "apiKey": os.getenv("REAMICRO_API_KEY", ""),
        "apiKeyRecords": [],
        "secretKey": os.getenv("REAMICRO_SECRET_KEY", ""),
        "allowPublic": env_bool("REAMICRO_ALLOW_PUBLIC", True),
        "authMode": os.getenv("REAMICRO_AUTH_MODE", ""),
        "features": sorted(DEFAULT_FEATURES),
        "minModuleVersion": os.getenv("REAMICRO_MIN_MODULE_VERSION", "2.0.0"),
        "signingPublicKey": os.getenv("REAMICRO_SIGNING_PUBLIC_KEY", ""),
        "githubRepository": os.getenv("REAMICRO_GITHUB_REPOSITORY", "YGHFv/ReaMicro-Extend"),
        "githubToken": os.getenv("REAMICRO_GITHUB_TOKEN", ""),
        "githubIncludePrerelease": env_bool("REAMICRO_GITHUB_INCLUDE_PRERELEASE", False),
        "releaseSyncSeconds": bounded_config_int(os.getenv("REAMICRO_RELEASE_SYNC_SECONDS", "1800"), 1800, 300),
        "releaseVersionCode": bounded_config_int(os.getenv("REAMICRO_RELEASE_VERSION_CODE", "0"), 0, 0),
        "serverSnapshotEnabled": env_bool("REAMICRO_SERVER_SNAPSHOT_ENABLED", True),
        "serverSnapshotSeconds": bounded_config_int(os.getenv("REAMICRO_SERVER_SNAPSHOT_SECONDS", "86400"), 86400, 3600),
        "serverSnapshotRetention": bounded_config_int(os.getenv("REAMICRO_SERVER_SNAPSHOT_RETENTION", "30"), 30, 3),
        "accounts": {},
        "primaryAdmin": {},
        "adminAccounts": {},
        "hostAccountAllowlist": [],
    }


def bounded_config_int(value: Any, default: int, minimum: int) -> int:
    try:
        return max(int(value), minimum)
    except (TypeError, ValueError, OverflowError):
        return default


def normalized_config_list(value: Any) -> list[str]:
    if isinstance(value, str):
        values = value.replace("\r", "\n").replace(",", "\n").splitlines()
    elif isinstance(value, (list, tuple, set)):
        values = value
    else:
        values = []
    return sorted({str(item).strip() for item in values if str(item).strip()})


def load_config() -> dict[str, Any]:
    with config_lock:
        config = default_config()
        auth_mode_explicit = False
        try:
            if CONFIG_PATH.is_file():
                stored = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
                if isinstance(stored, dict):
                    config.update(stored)
                    auth_mode_explicit = str(stored.get("authMode", "")).strip() in {"public", "api_key", "account", "host_account_allowlist"}
        except (OSError, ValueError):
            pass
        config["features"] = normalized_config_list(config.get("features", []))
        config["releaseSyncSeconds"] = bounded_config_int(config.get("releaseSyncSeconds", 1800), 1800, 300)
        config["serverSnapshotSeconds"] = bounded_config_int(config.get("serverSnapshotSeconds", 86400), 86400, 3600)
        config["serverSnapshotRetention"] = bounded_config_int(config.get("serverSnapshotRetention", 30), 30, 3)
        config["accounts"] = config.get("accounts", {}) if isinstance(config.get("accounts", {}), dict) else {}
        config["apiKeyRecords"] = config.get("apiKeyRecords", []) if isinstance(config.get("apiKeyRecords", []), list) else []
        config["primaryAdmin"] = config.get("primaryAdmin", {}) if isinstance(config.get("primaryAdmin", {}), dict) else {}
        config["adminAccounts"] = config.get("adminAccounts", {}) if isinstance(config.get("adminAccounts", {}), dict) else {}
        config["hostAccountAllowlist"] = normalized_config_list(config.get("hostAccountAllowlist", []))
        if config.get("authMode") not in {"public", "api_key", "account", "host_account_allowlist"}:
            config["authMode"] = infer_auth_mode(config)
        config["_authModeExplicit"] = auth_mode_explicit
        return config


def save_config(next_config: dict[str, Any]) -> dict[str, Any]:
    with config_lock:
        CONFIG_ROOT.mkdir(parents=True, exist_ok=True)
        safe = default_config()
        safe.update(next_config)
        safe["features"] = normalized_config_list(safe.get("features", []))
        safe["releaseSyncSeconds"] = bounded_config_int(safe.get("releaseSyncSeconds", 1800), 1800, 300)
        safe["serverSnapshotSeconds"] = bounded_config_int(safe.get("serverSnapshotSeconds", 86400), 86400, 3600)
        safe["serverSnapshotRetention"] = bounded_config_int(safe.get("serverSnapshotRetention", 30), 30, 3)
        safe["accounts"] = safe.get("accounts", {}) if isinstance(safe.get("accounts", {}), dict) else {}
        safe["apiKeyRecords"] = safe.get("apiKeyRecords", []) if isinstance(safe.get("apiKeyRecords", []), list) else []
        safe["primaryAdmin"] = safe.get("primaryAdmin", {}) if isinstance(safe.get("primaryAdmin", {}), dict) else {}
        safe["adminAccounts"] = safe.get("adminAccounts", {}) if isinstance(safe.get("adminAccounts", {}), dict) else {}
        safe["hostAccountAllowlist"] = normalized_config_list(safe.get("hostAccountAllowlist", []))
        if safe.get("authMode") not in {"public", "api_key", "account", "host_account_allowlist"}:
            safe["authMode"] = infer_auth_mode(safe)
        safe.pop("_authModeExplicit", None)
        temp = CONFIG_PATH.with_suffix(".tmp")
        temp.write_text(json.dumps(safe, ensure_ascii=False, indent=2), encoding="utf-8")
        temp.replace(CONFIG_PATH)
        return safe


def infer_auth_mode(config: dict[str, Any]) -> str:
    """从旧版多认证配置迁移到单选认证模式。"""
    if config.get("hostAccountAllowlist"):
        return "host_account_allowlist"
    if config.get("accounts"):
        return "account"
    if api_key_auth_configured(config):
        return "api_key"
    return "public"


def active_auth_mode(config: dict[str, Any]) -> str:
    mode = str(config.get("authMode") or infer_auth_mode(config))
    if mode == "public" and not config.get("_authModeExplicit") and (api_key_auth_configured(config) or config.get("accounts") or config.get("hostAccountAllowlist")):
        return infer_auth_mode(config)
    return mode if mode in {"public", "api_key", "account", "host_account_allowlist"} else "api_key"


def get_state_store() -> StateStore:
    global state_store
    with state_store_lock:
        if state_store is None or state_store.path != STATE_DB_PATH:
            state_store = StateStore(STATE_DB_PATH)
            state_store.initialize()
            state_store.import_json_namespace("tasks", TASKS_PATH)
            state_store.import_json_namespace("credentials", ACCOUNT_PATH)
        return state_store


def load_tasks() -> dict[str, dict[str, Any]]:
    return get_state_store().load_namespace("tasks")


def save_tasks(tasks: dict[str, dict[str, Any]]) -> None:
    get_state_store().save_namespace("tasks", tasks)


def load_notifications() -> dict[str, dict[str, Any]]:
    return get_state_store().load_namespace("notifications")


def save_notifications(items: dict[str, dict[str, Any]]) -> None:
    get_state_store().save_namespace("notifications", items)


def load_presence() -> dict[str, dict[str, Any]]:
    return get_state_store().load_namespace("presence")


def save_presence(items: dict[str, dict[str, Any]]) -> None:
    get_state_store().save_namespace("presence", items)


def enqueue_task_notification(task: dict[str, Any], result: str, message: str, finished_at: int) -> str:
    notification_id = "msg_" + secrets.token_hex(10)
    items = load_notifications()
    items[notification_id] = {
        "id": notification_id,
        "owner": str(task.get("owner", "")),
        "taskId": str(task.get("id", "")),
        "taskType": str(task.get("taskType", "")),
        "result": result,
        "title": _admin_task_label(task.get("taskType", "")) + ("执行完成" if result == "success" else "执行异常"),
        "message": message,
        "createdAt": finished_at,
        "deliveredAt": 0,
    }
    # 每个用户最多保留最近 200 条，防止长期离线无限增长。
    owned = sorted((item for item in items.values() if item.get("owner") == task.get("owner")), key=lambda item: int(item.get("createdAt", 0)), reverse=True)
    for expired in owned[200:]:
        items.pop(str(expired.get("id", "")), None)
    save_notifications(items)
    return notification_id


def merge_duplicate_tasks() -> int:
    """同一所有者、同一同步密钥、同一任务类型只保留最新任务。"""
    tasks = load_tasks()
    groups: dict[tuple[str, str, str], list[tuple[str, dict[str, Any]]]] = {}
    for key, task in tasks.items():
        if not isinstance(task, dict):
            continue
        task_type = str(task.get("taskType", ""))
        credential_id = task_credential_id(task)
        if task_type and credential_id and task_type != "http":
            groups.setdefault((str(task.get("owner", "")), task_type, credential_id), []).append((key, task))
    removed = 0
    for items in groups.values():
        if len(items) < 2:
            continue
        items.sort(key=lambda pair: (bounded_config_int(pair[1].get("updatedAt", 0), 0, 0), bounded_config_int(pair[1].get("createdAt", 0), 0, 0), pair[0]), reverse=True)
        for duplicate_id, _ in items[1:]:
            tasks.pop(duplicate_id, None)
            removed += 1
    if removed:
        save_tasks(tasks)
        audit_event("tasks_merged", metadata={"removedCount": removed, "source": "dedupe"})
    return removed


def task_unique_key(task: dict[str, Any]) -> tuple[str, str, str] | None:
    """返回需要幂等的任务键；通用 HTTP 任务允许多个并存。"""
    task_type = str(task.get("taskType", "")).strip()
    credential_id = task_credential_id(task)
    owner = str(task.get("owner", "")).strip()
    if not owner or not credential_id or not task_type or task_type == "http":
        return None
    return owner, credential_id, task_type


def find_duplicate_task(tasks: dict[str, dict[str, Any]], task: dict[str, Any], exclude_id: str = "") -> tuple[str, dict[str, Any]] | None:
    key = task_unique_key(task)
    if not key:
        return None
    candidates = [(task_id, item) for task_id, item in tasks.items() if task_id != exclude_id and task_unique_key(item) == key]
    if not candidates:
        return None
    return max(candidates, key=lambda pair: (bounded_config_int(pair[1].get("updatedAt", 0), 0, 0), bounded_config_int(pair[1].get("createdAt", 0), 0, 0), pair[0]))


def load_credentials() -> dict[str, dict[str, Any]]:
    return get_state_store().load_namespace("credentials")


def save_credentials(credentials: dict[str, dict[str, Any]]) -> None:
    get_state_store().save_namespace("credentials", credentials)


def merge_duplicate_credentials() -> int:
    """归并历史上同一所有者、同一阅微账号产生的重复同步密钥。"""
    credentials = load_credentials()
    groups: dict[tuple[str, str], list[tuple[str, dict[str, Any]]]] = {}
    for key, item in credentials.items():
        if not isinstance(item, dict) or item.get("type", "reamicro") != "reamicro":
            continue
        owner = str(item.get("owner", ""))
        account_id = str(item.get("accountId", ""))
        if owner and account_id:
            groups.setdefault((owner, account_id), []).append((key, item))
    removed: dict[str, str] = {}
    for (owner, _), items in groups.items():
        if len(items) < 2:
            continue
        items.sort(key=lambda pair: (bounded_config_int(pair[1].get("updatedAt", 0), 0, 0), pair[0]), reverse=True)
        keep_id = items[0][0]
        for duplicate_id, _ in items[1:]:
            credentials.pop(duplicate_id, None)
            removed[duplicate_id] = keep_id
    if not removed:
        return 0
    save_credentials(credentials)
    tasks = load_tasks()
    rebound = False
    for task in tasks.values():
        task_owner_value = str(task.get("owner", ""))
        old_id = str(task.get("credentialId", ""))
        if old_id in removed:
            task["credentialId"] = removed[old_id]
            rebound = True
        if task.get("requestEncrypted"):
            try:
                request_value = decrypt_secret(str(task["requestEncrypted"]))
            except Exception:
                request_value = None
            if isinstance(request_value, dict) and str(request_value.get("credentialId", "")) in removed:
                request_value["credentialId"] = removed[str(request_value["credentialId"])]
                task["requestEncrypted"] = encrypt_secret(request_value)
                rebound = True
        if task_owner_value and task.get("credentialId") in removed:
            task["credentialId"] = removed[str(task["credentialId"])]
            rebound = True
    if rebound:
        save_tasks(tasks)
    audit_event("credentials_merged", metadata={"removedCount": len(removed), "source": "admin_read"})
    return len(removed)


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


def create_server_snapshot() -> Path:
    SERVER_BACKUP_ROOT.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    database_copy = SERVER_BACKUP_ROOT / f"state-{timestamp}.sqlite3"
    archive = SERVER_BACKUP_ROOT / f"reamicro-server-{timestamp}.zip"
    get_state_store().backup(database_copy)
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as stream:
        stream.write(database_copy, "state/reamicro.sqlite3")
        if CONFIG_PATH.is_file():
            stream.write(CONFIG_PATH, "config/server.json")
        manifest = {"createdAt": int(datetime.now(timezone.utc).timestamp() * 1000), "files": []}
        source_map = {"state/reamicro.sqlite3": database_copy, "config/server.json": CONFIG_PATH}
        for name, source in source_map.items():
            if source.is_file():
                manifest["files"].append({"path": name, "size": source.stat().st_size, "sha256": hashlib.sha256(source.read_bytes()).hexdigest()})
        stream.writestr("snapshot-manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
    database_copy.unlink(missing_ok=True)
    prune_server_snapshots(int(load_config().get("serverSnapshotRetention", 30)))
    return archive


def prune_server_snapshots(retention: int) -> int:
    snapshots = sorted(SERVER_BACKUP_ROOT.glob("reamicro-server-*.zip"), reverse=True)
    removed = 0
    for expired in snapshots[max(int(retention), 3):]:
        expired.unlink(missing_ok=True)
        removed += 1
    return removed


def list_server_snapshots() -> list[dict[str, Any]]:
    items = []
    for path in sorted(SERVER_BACKUP_ROOT.glob("reamicro-server-*.zip"), reverse=True):
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
            "taskId": task_id,
            "level": level,
            "message": message,
        }, ensure_ascii=False) + "\n")


def secret_cipher_key() -> bytes:
    material = str(load_config().get("secretKey", "")) or SECRET_KEY or ADMIN_PASSWORD
    if not material:
        raise ValueError("未配置 REAMICRO_SECRET_KEY 或管理密码")
    return hashlib.sha256(material.encode("utf-8")).digest()


def secret_key_id() -> str:
    material = str(load_config().get("secretKey", "")) or SECRET_KEY or ADMIN_PASSWORD
    return "key_" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:12]


def encrypt_secret(value: Any) -> str:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    nonce = secrets.token_bytes(12)
    body = json.dumps(value, ensure_ascii=False).encode("utf-8")
    encrypted = AESGCM(secret_cipher_key()).encrypt(nonce, body, b"reamicro-task-v1")
    return "RCSEC2:" + secret_key_id() + ":" + base64.urlsafe_b64encode(nonce + encrypted).decode("ascii")


def decrypt_secret(value: str) -> Any:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    encoded = value.split(":", 2)[-1] if value.startswith("RCSEC2:") else value
    raw = base64.urlsafe_b64decode(encoded.encode("ascii"))
    body = AESGCM(secret_cipher_key()).decrypt(raw[:12], raw[12:], b"reamicro-task-v1")
    return json.loads(body.decode("utf-8"))


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
        return "success", f"野社抽卡完成（今日 {task['dailyCounter']}/{daily_limit}）：{redact_message(json.dumps(body, ensure_ascii=False)[:300])}"
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

        claim_status, claim_body, claim_raw = json_http_request(base_url, token, {}, "rest/community/claim-literary-society-weekly-reward")
        if claim_status in (401, 403, 429):
            return "paused", f"阅微认证/风控响应 HTTP {claim_status}"
        success = 200 <= claim_status < 300 and nested_value(claim_body, "data", "success") is not False
        claim_message = nested_value(claim_body, "data", "message") or nested_value(claim_body, "message") or redact_message(claim_raw)
        if success:
            task["lastClaimAt"] = now_ms
            task["claimCompletedDate"] = today
            task["claimRetryCount"] = 0
            return "success", f"{checkin_message or '野社签到状态已检查'}，签到奖励领取成功" + (f"：{redact_message(str(claim_message))}" if claim_message else "")

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
                next_run = int(task.get("nextRunAt", 0))
                if next_run > now:
                    continue
                if not get_state_store().acquire_task_lock(task_id, worker_id, now + 15 * 60 * 1000, now):
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
                    changed = True
                    save_tasks(tasks)
                finally:
                    get_state_store().release_task_lock(task_id, worker_id)
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
        "channel": "beta" if release.get("prerelease") else "stable",
        "etag": hashlib.sha256(apk_path.read_bytes()).hexdigest(),
    }
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    RELEASE_STATUS_PATH.parent.mkdir(parents=True, exist_ok=True)
    RELEASE_STATUS_PATH.write_text(json.dumps({"status": "ok", "syncedAt": int(datetime.now(timezone.utc).timestamp() * 1000), "versionName": version_name, "assetId": asset.get("id")}, ensure_ascii=False, indent=2), encoding="utf-8")
    return metadata


async def release_sync_loop() -> None:
    while True:
        try:
            await asyncio.to_thread(sync_module_release)
        except Exception as error:
            RELEASE_STATUS_PATH.parent.mkdir(parents=True, exist_ok=True)
            RELEASE_STATUS_PATH.write_text(json.dumps({"status": "error", "failedAt": int(datetime.now(timezone.utc).timestamp() * 1000), "message": redact_message(str(error))}, ensure_ascii=False, indent=2), encoding="utf-8")
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
    get_state_store()
    if RUN_RELEASE_SYNC:
        asyncio.create_task(release_sync_loop())
    if RUN_SCHEDULER:
        recover_interrupted_tasks()
        asyncio.create_task(task_scheduler_loop())
        asyncio.create_task(server_snapshot_loop())


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


def api_key_digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


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


def api_key_auth_configured(config: dict[str, Any]) -> bool:
    if str(config.get("apiKey", "")):
        return True
    return any(
        isinstance(record, dict) and record.get("enabled", True) and str(record.get("digest", ""))
        for record in config.get("apiKeyRecords", [])
    )


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


def admin_auth_version(config: dict[str, Any], actor: dict[str, Any]) -> str:
    username = str(actor.get("username", ""))
    if actor.get("needsSetup"):
        material = ADMIN_PASSWORD
    elif actor.get("role") == "primary":
        material = str(config.get("primaryAdmin", {}).get("passwordHash", ""))
    else:
        material = str(config.get("adminAccounts", {}).get(username, {}).get("passwordHash", ""))
    return hashlib.sha256(material.encode("utf-8")).hexdigest()[:24]


def create_admin_session(actor: dict[str, Any]) -> str:
    token = generate_long_secret(48)
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    get_state_store().save_admin_session(
        api_key_digest(token),
        actor,
        admin_auth_version(load_config(), actor),
        now,
        now + ADMIN_SESSION_SECONDS * 1000,
    )
    return token


def session_admin(request: Request) -> dict[str, Any] | None:
    token = request.cookies.get(ADMIN_SESSION_COOKIE, "")
    if not token:
        return None
    return validate_admin_session_token(token)


def validate_admin_session_token(token: str) -> dict[str, Any] | None:
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    actor = get_state_store().load_admin_session(api_key_digest(token), now)
    if not actor:
        return None
    config = load_config()
    if actor.get("role") == "subadmin":
        record = config.get("adminAccounts", {}).get(actor.get("username", ""), {})
        if not isinstance(record, dict) or not record.get("enabled", True):
            get_state_store().delete_admin_session(api_key_digest(token))
            return None
        actor["permissions"] = record.get("permissions", [])
    if not hmac.compare_digest(str(actor.get("authVersion", "")), admin_auth_version(config, actor)):
        get_state_store().delete_admin_session(api_key_digest(token))
        return None
    return actor


async def basic_security(request: Request) -> HTTPBasicCredentials | None:
    """会话优先，兼容旧的 HTTP Basic 登录。"""
    token = request.cookies.get(ADMIN_SESSION_COOKIE, "")
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
    config = load_config()
    mode = active_auth_mode(config)
    api_key = str(config.get("apiKey", ""))
    key_record = configured_api_key(config, api_key_value) if mode == "api_key" else None
    if key_record:
        identity = "key:" + hashlib.sha256(api_key_value.encode()).hexdigest()[:24]
        return identity + (":host:" + host_account_id if host_account_id else "")
    encoded = str(config.get("accounts", {}).get(account_name or "", ""))
    if mode == "account" and account_name and account_password and encoded and password_matches(account_password, encoded):
        identity = "account:" + account_name
        return identity + (":host:" + host_account_id if host_account_id else "")
    if mode == "host_account_allowlist" and host_account_id and host_account_id in set(config.get("hostAccountAllowlist", [])):
        return "host:" + host_account_id
    if mode == "public":
        return "host-public:" + host_account_id if host_account_id else "public"
    raise HTTPException(status_code=401, detail=response(code="AUTH_REQUIRED", message="认证信息无效"))


def configured_auth_modes(config: dict[str, Any]) -> list[str]:
    return [active_auth_mode(config)]


def public_server_capabilities(config: dict[str, Any]) -> dict[str, Any]:
    modes = configured_auth_modes(config)
    return {
        "apiVersion": API_VERSION,
        "serverId": config["serverId"],
        "features": config["features"],
        "authModes": modes,
        "authMode": modes[0] if len(modes) == 1 else "multiple",
        "authRequired": modes != ["public"],
        "allowPublic": "public" in modes,
        "authMethods": ["apiKey", "account", "hostAccount", "public"],
        "packageSchemaVersions": [1],
        "taskTypes": ["http", "yeshe_checkin", "yeshe_draw_card", "cloud_auto_read"],
        "maxUploadSize": 50 * 1024 * 1024,
        "minModuleVersion": config["minModuleVersion"],
        "signingPublicKey": config["signingPublicKey"],
    }


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


async def backup_owner(
    request: Request,
    x_reamicro_api_key: str | None = Header(default=None),
    x_reamicro_account: str | None = Header(default=None),
    x_reamicro_password: str | None = Header(default=None),
    x_reamicro_host_account_id: str | None = Header(default=None),
) -> str:
    enforce_api_scope(request, x_reamicro_api_key)
    config = load_config()
    mode = active_auth_mode(config)
    api_key = str(config.get("apiKey", ""))
    if mode == "api_key" and configured_api_key(config, x_reamicro_api_key):
        identity = "key:" + x_reamicro_api_key + (":host:" + x_reamicro_host_account_id if x_reamicro_host_account_id else "")
    elif mode == "account" and x_reamicro_account and x_reamicro_password and password_matches(
        x_reamicro_password,
        str(config.get("accounts", {}).get(x_reamicro_account, "")),
    ):
        identity = "account:" + x_reamicro_account + (":host:" + x_reamicro_host_account_id if x_reamicro_host_account_id else "")
    elif mode == "host_account_allowlist" and x_reamicro_host_account_id and x_reamicro_host_account_id in set(config.get("hostAccountAllowlist", [])):
        identity = "host:" + x_reamicro_host_account_id
    elif mode == "public":
        identity = "host-public:" + x_reamicro_host_account_id
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
        with metrics_lock:
            metrics_counters["rate_limited"] += 1
        audit_event("rate_limit", actor=client_host, request_id=request_id, success=False, metadata={"path": request.url.path})
        return JSONResponse(
            status_code=429,
            content=response(code="RATE_LIMITED", message="请求过于频繁，请稍后重试", request_id=request_id),
            headers={"Retry-After": str(RATE_WINDOW_SECONDS), "X-Request-Id": request_id},
        )
    try:
        result = await call_next(request)
    except Exception:
        with metrics_lock:
            metrics_counters["errors"] += 1
        audit_event("request_error", actor=client_host, request_id=request_id, success=False, metadata={"path": request.url.path})
        raise
    result.headers["X-Request-Id"] = request_id
    result.headers["X-ReaMicro-API-Version"] = API_VERSION
    # 后台页面包含账号、凭据状态和 CSRF，禁止浏览器及反向代理复用旧 HTML。
    if request.url.path == "/admin" or request.url.path.startswith("/admin/"):
        result.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
        result.headers["Pragma"] = "no-cache"
        result.headers["Expires"] = "0"
    with metrics_lock:
        metrics_counters["requests"] += 1
        metrics_routes[request.url.path] = metrics_routes.get(request.url.path, 0) + 1
    return result


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
    return JSONResponse(status_code=exc.status_code, content=detail, headers={**(exc.headers or {}), "X-Request-Id": request_id})


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    request_id = getattr(request.state, "request_id", "") or "req_" + secrets.token_hex(8)
    client_host = request.client.host if request.client else "unknown"
    with metrics_lock:
        metrics_counters["errors"] += 1
    audit_event(
        "unhandled_exception",
        actor=client_host,
        request_id=request_id,
        success=False,
        metadata={"path": request.url.path, "type": type(exc).__name__},
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
    database = get_state_store().integrity_check()
    ready = database == "ok" and CONFIG_ROOT.parent.exists()
    payload = response({"status": "ready" if ready else "not_ready", "database": database})
    return JSONResponse(status_code=200 if ready else 503, content=payload)


@app.get("/health/dependencies")
async def health_dependencies(_: None = Depends(authenticated)) -> dict[str, Any]:
    sync_status = {}
    if RELEASE_STATUS_PATH.is_file():
        try: sync_status = json.loads(RELEASE_STATUS_PATH.read_text(encoding="utf-8"))
        except (OSError, ValueError): sync_status = {"status": "invalid"}
    return response({
        "database": get_state_store().integrity_check(),
        "releaseCache": (RELEASE_ROOT / "latest.json").is_file(),
        "releaseSync": sync_status,
        "packageStorage": PACKAGE_ROOT.exists(),
        "backupStorage": BACKUP_ROOT.exists(),
    })


@app.get("/v1/diagnostics")
async def diagnostics(_: None = Depends(authenticated)) -> dict[str, Any]:
    config = load_config()
    database = get_state_store().integrity_check()
    package_count = sum(1 for kind in PACKAGE_KINDS for _ in (PACKAGE_ROOT / kind).glob("*/manifest.json"))
    snapshot_count = len(list(SERVER_BACKUP_ROOT.glob("reamicro-server-*.zip"))) if SERVER_BACKUP_ROOT.exists() else 0
    release_status = {}
    if RELEASE_STATUS_PATH.is_file():
        try: release_status = json.loads(RELEASE_STATUS_PATH.read_text(encoding="utf-8"))
        except (OSError, ValueError): release_status = {"status": "invalid"}
    return response({
        "serverId": config.get("serverId", ""),
        "database": database,
        "packages": package_count,
        "credentials": len(load_credentials()),
        "tasks": len(load_tasks()),
        "snapshots": snapshot_count,
        "releaseSync": release_status,
        "storage": {"config": CONFIG_ROOT.exists(), "packages": PACKAGE_ROOT.exists(), "backups": BACKUP_ROOT.exists()},
    })


@app.get("/metrics")
async def metrics(_: None = Depends(authenticated)) -> Response:
    with metrics_lock:
        counters = dict(metrics_counters)
        routes = dict(metrics_routes)
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
        f"reamicro_packages {sum(1 for kind in PACKAGE_KINDS for _ in (PACKAGE_ROOT / kind).glob('*/manifest.json'))}",
    ]
    for route, count in sorted(routes.items()):
        safe_route = route.replace('"', '\\"')
        lines.append(f'reamicro_http_route_requests_total{{route="{safe_route}"}} {count}')
    return Response("\n".join(lines) + "\n", media_type="text/plain; version=0.0.4")


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


def admin_login_page(message: str = "") -> str:
    message_html = f"<p style='padding:10px;background:#fef2f2;color:#991b1b;border-radius:8px'>{html.escape(message)}</p>" if message else ""
    return f"""<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>后台登录</title>
<style>body{{font-family:system-ui;max-width:460px;margin:64px auto;padding:18px;background:#f5f7fb}}main{{background:white;padding:26px;border-radius:14px;box-shadow:0 5px 24px #0001}}label{{display:block;margin:14px 0 5px;font-weight:600}}input{{box-sizing:border-box;width:100%;padding:11px;border:1px solid #ccd4df;border-radius:8px}}button{{margin-top:20px;width:100%;padding:11px;border:0;border-radius:8px;background:#2563eb;color:white;font-weight:600}}</style></head>
<body><main><h1>ReaMicro 管理后台</h1>{message_html}<form method='post' action='/admin/login'><label>管理员用户名</label><input name='username' autocomplete='username' required><label>密码</label><input type='password' name='password' autocomplete='current-password' required><button type='submit'>登录</button></form></main></body></html>"""


def _legacy_admin_page(config: dict[str, Any], message: str = "", actor: dict[str, Any] | None = None, secret_notice: str = "") -> str:
    def esc(value: Any) -> str:
        return html.escape(str(value), quote=True)

    checked_public = "checked" if config.get("allowPublic") else ""
    checked_pre = "checked" if config.get("githubIncludePrerelease") else ""
    feature_text = ",".join(config.get("features", []))
    actor = actor or {"username": "", "role": "subadmin"}
    csrf_html = f"<input type='hidden' name='csrf_token' value='{esc(admin_csrf_token(config, actor))}'>"
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
        task_id = esc(task.get("id", ""))
        task_actions = ""
        if actor and (actor.get("role") == "primary" or "tasks:write" in set(actor.get("permissions", []))):
            secondary_action = "resume" if task.get("status") in {"paused", "cancelled"} else "pause"
            secondary_label = "恢复" if secondary_action == "resume" else "暂停"
            task_actions = (
                f"<form class='inline' method='post' action='/admin/tasks/action'>{csrf_html}"
                f"<input type='hidden' name='task_id' value='{task_id}'><input type='hidden' name='action' value='run'>"
                "<button class='compact secondary' type='submit'>立即执行</button></form>"
                f"<form class='inline' method='post' action='/admin/tasks/action'>{csrf_html}"
                f"<input type='hidden' name='task_id' value='{task_id}'><input type='hidden' name='action' value='{secondary_action}'>"
                f"<button class='compact secondary' type='submit'>{secondary_label}</button></form>"
            )
        task_rows.append(
            f"<tr><td>{esc(task.get('taskType', ''))}</td><td>{esc(task.get('status', ''))}</td>"
            f"<td>{esc(task.get('lastMessage', ''))}</td><td>{esc(task.get('nextRunAt', ''))}</td><td>{task_actions}</td></tr>"
        )
    task_table = "".join(task_rows) or "<tr><td colspan='5'>暂无云任务</td></tr>"
    credential_rows = []
    for credential in load_credentials().values():
        credential_rows.append(
            f"<tr><td>{esc(credential.get('id', ''))}</td><td>{esc(credential.get('owner', ''))}</td>"
            f"<td>{esc(credential.get('label', ''))}</td><td>{esc(credential.get('lastVerifyMessage', ''))}</td></tr>"
        )
    credential_table = "".join(credential_rows) or "<tr><td colspan='4'>暂无阅微凭据，请由模块客户端上传</td></tr>"
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
    system_tools = ""
    if actor.get("role") == "primary" or "backup:admin" in set(actor.get("permissions", [])):
        system_tools = f"""<hr><h2>服务器数据保护</h2><p><small>快照包含事务数据库和服务器配置，最多保留 30 份。</small></p>
<form method='post' action='/admin/backups/server'>{csrf_html}<button type='submit'>立即创建服务器快照</button></form>"""
    security_tools = ""
    if actor.get("role") == "primary":
        security_tools = f"""<hr><h2>加密密钥轮换</h2><p><small>轮换前会先解密全部阅微凭据和任务请求，再以新密钥重新加密。请先创建服务器快照并永久保存新密钥。</small></p>
<form method='post' action='/admin/security/rotate-secret'>{csrf_html}<label>新服务器加密密钥（至少 12 位，建议 64 位以上随机值）</label><input type='password' name='new_secret_key' minlength='12' required><button class='danger' type='submit'>轮换并重新加密</button></form>"""
    return f"""<!doctype html>
<html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>ReaMicro API 管理后台</title>
<style>body{{font-family:system-ui,-apple-system,sans-serif;max-width:980px;margin:32px auto;padding:0 18px;background:#f5f7fb;color:#182230}}main{{background:white;border-radius:14px;padding:24px;box-shadow:0 5px 24px #0001}}label{{display:block;margin:14px 0 5px;font-weight:600}}input,textarea,select{{box-sizing:border-box;width:100%;padding:10px;border:1px solid #ccd4df;border-radius:8px;font:inherit;background:white}}textarea{{min-height:72px}}.row{{display:grid;grid-template-columns:1fr 1fr;gap:16px}}button{{margin-top:20px;padding:10px 16px;border:0;border-radius:8px;background:#2563eb;color:#fff;font-weight:600;cursor:pointer}}.secondary{{background:#475569;margin-left:8px}}.danger{{background:#b91c1c}}.compact{{margin:2px;padding:6px 9px}}.inline{{display:inline}}.msg{{padding:10px;background:#ecfdf5;color:#166534;border-radius:8px}}.secret{{padding:12px;background:#fff7ed;color:#9a3412;border-radius:8px;overflow-wrap:anywhere}}small{{color:#64748b}}table{{width:100%;border-collapse:collapse;margin-top:12px}}th,td{{padding:8px;border-bottom:1px solid #e2e8f0;text-align:left}}details{{margin-top:18px;padding:12px;border:1px solid #e2e8f0;border-radius:8px}}</style></head>
<body><main><h1>ReaMicro API 管理后台</h1><p><small>当前管理员：{esc(actor.get('username', ''))}（{'主管理员' if actor.get('role') == 'primary' else '子管理员'}） · 默认端口：5222 · 配置保存到 Docker 数据卷 /data/config/server.json</small></p>
<form method='post' action='/admin/logout'>{csrf_html}<button class='secondary' type='submit'>退出登录</button></form>
{f"<p class='msg'>{esc(message)}</p>" if message else ""}
{f"<p class='secret'><strong>请立即保存，关闭页面后不再显示：</strong><br>{esc(secret_notice)}</p>" if secret_notice else ""}
<form method='post' action='/admin/settings'>{csrf_html}
<div class='row'><div><label>服务器 ID</label><input name='server_id' value='{esc(config.get("serverId", ""))}'></div><div><label>最低模块版本</label><input name='min_module_version' value='{esc(config.get("minModuleVersion", ""))}'></div></div>
<label>API Key（当前：{'已设置' if config.get("apiKey") else '未设置'}）</label><input type='password' name='api_key' placeholder='留空保持当前值'><label><input type='checkbox' name='generate_api_key'> 自动生成 64 位以上长密钥</label><label><input type='checkbox' name='clear_api_key'> 清除 API Key</label>
<label>服务器加密密钥（当前：{'已设置' if config.get("secretKey") else '未设置'}）</label><label><input type='checkbox' name='generate_secret_key'> 当前未设置时生成新的服务器加密长密钥</label><small>已有密钥不能直接替换，否则历史阅微凭据将无法解密。</small>
<small>如需轮换已有密钥，请使用维护接口完成重新加密，不要直接修改环境变量。</small>
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
<div class='row'><div><label>自动服务器快照</label><label><input type='checkbox' name='server_snapshot_enabled' {'checked' if config.get('serverSnapshotEnabled', True) else ''}> 启用</label></div><div><label>快照间隔（秒，至少 3600）</label><input name='server_snapshot_seconds' type='number' value='{esc(config.get("serverSnapshotSeconds", 86400))}'></div></div>
<label>快照保留份数（至少 3）</label><input name='server_snapshot_retention' type='number' value='{esc(config.get("serverSnapshotRetention", 30))}'>
<button type='submit'>保存设置</button><button class='secondary' type='submit' formaction='/admin/sync'>立即同步模块 Release</button>
</form><hr><h2>内容包上传/更新</h2>
<p><small>上传后立即覆盖当前版本；旧文件保留在对应目录的 history 中。kind 支持 online_source、epub_style、highlight_style、association_source、theme。</small></p>
<form method='post' action='/admin/packages/upload' enctype='multipart/form-data'>{csrf_html}
<div class='row'><div><label>内容类型</label><input name='kind' placeholder='online_source' required></div><div><label>包 ID</label><input name='package_id' placeholder='source.example' required></div></div>
<div class='row'><div><label>版本号</label><input name='version' placeholder='1.0.0' required></div><div><label>稳定内容 ID（书源建议固定）</label><input name='content_id' placeholder='source.example'></div></div>
<div class='row'><div><label>发布状态</label><select name='status_value'><option value='published'>立即发布</option><option value='draft'>草稿</option><option value='testing'>测试中</option><option value='unpublished'>下架</option></select></div><div><label>发布渠道</label><select name='channel'><option value='stable'>稳定版</option><option value='beta'>测试版</option><option value='nightly'>开发版</option></select></div></div>
<label>旧 ID/域名别名（逗号分隔）</label><input name='aliases' placeholder='online_old_hash,old.example.com'>
<label>依赖 JSON 数组（可选）</label><textarea name='dependencies' placeholder='[{{"packageId":"parser.common","minVersion":"1.0.0"}}]'></textarea>
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
<h3>任务状态</h3><table><thead><tr><th>类型</th><th>状态</th><th>最近结果</th><th>下次执行</th><th>操作</th></tr></thead><tbody>{task_table}</tbody></table>
{admin_management}{system_tools}{security_tools}<hr><p><a href='/admin/audit'>审计日志</a>　<a href='/health/live'>存活检查</a>　<a href='/health/ready'>就绪检查</a>　<a href='/v1/meta'>能力信息</a></p></main></body></html>"""


def _admin_package_records() -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for kind in sorted(PACKAGE_KINDS):
        for manifest_path in sorted((PACKAGE_ROOT / kind).glob("*/manifest.json")):
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


def _admin_kind_label(kind: str) -> str:
    return {"online_source": "书源", "association_source": "关联源", "epub_style": "EPUB 样式", "highlight_style": "高亮样式", "theme": "主题库"}.get(kind, kind)


def _admin_task_label(task_type: Any) -> str:
    return {
        "yeshe_checkin": "野社零点签到",
        "yeshe_draw_card": "野社自动抽卡",
        "cloud_auto_read": "云端自动阅读",
        "http": "通用 HTTPS 请求",
    }.get(str(task_type), str(task_type) or "未命名任务")


def _admin_task_status_label(status: Any) -> str:
    return {
        "scheduled": "等待执行",
        "running": "执行中",
        "success": "执行成功",
        "failed": "执行失败",
        "paused": "已暂停",
        "cancelled": "已取消",
    }.get(str(status), str(status) or "未知")


def _admin_health_label(health: Any) -> str:
    return {
        "valid": "有效",
        "warning": "需要验证",
        "invalid": "已失效",
        "paused": "已暂停",
        "unverified": "未验证",
    }.get(str(health), str(health) or "未验证")


def _admin_owner_label(owner: Any) -> str:
    text = str(owner or "")
    if text.startswith("host:") or ":host:" in text or text.startswith("host-public:"):
        account_id = owner_host_account_id(text)
        return f"阅微账号 {account_id}" if account_id else "阅微账号认证"
    if text.startswith("account:"):
        return f"独立账号：{text.split(':', 1)[1]}"
    if text.startswith("key:"):
        return "API Key 用户"
    return text or "未标记"


def _admin_time_label(value: Any) -> str:
    try:
        timestamp = float(value)
        if timestamp <= 0:
            return "未安排"
        if timestamp > 10_000_000_000:
            timestamp /= 1000
        return datetime.fromtimestamp(timestamp, timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M")
    except (TypeError, ValueError, OverflowError, OSError):
        return str(value) if value else "未记录"


def admin_section_path(section: str) -> str:
    if section in PACKAGE_KINDS:
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
        parts.append("奖励领取：签到后 8 小时")
        if task.get("lastCheckinAt"):
            parts.append(f"上次签到：{_admin_time_label(task.get('lastCheckinAt'))}")
        if task.get("lastClaimAt"):
            parts.append(f"上次领取：{_admin_time_label(task.get('lastClaimAt'))}")
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
        dependency = "满足" if item.get("dependenciesSatisfied", True) else "缺少依赖"
        actions = f"<a class='button subtle' href='/admin/packages/{esc(kind)}/{esc(package_id)}/edit'>编辑</a> <a class='button subtle' href='/admin/packages/{esc(kind)}/{esc(package_id)}/preview'>预览</a> <a class='button subtle' href='/admin/packages/{esc(kind)}/{esc(package_id)}/history'>历史</a> <form class='inline' method='post' action='/admin/packages/{esc(kind)}/{esc(package_id)}/delete'><input type='hidden' name='csrf_token' value='{esc(csrf_token)}'><button class='button subtle' type='submit'>删除</button></form>" if can_write else f"<a class='button subtle' href='/admin/packages/{esc(kind)}/{esc(package_id)}/preview'>预览</a>"
        rows.append(f"<tr><td>{esc(_admin_kind_label(kind))}</td><td><strong>{esc(item.get('name', package_id))}</strong><small>{esc(package_id)} · {esc(item.get('contentId', ''))}</small></td><td>{esc(item.get('version', ''))}<small>{esc(item.get('buildTime', ''))}</small></td><td><span class='status status-{esc(item.get('status', 'published'))}'>{esc(item.get('status', 'published'))}</span></td><td>{esc(item.get('channel', 'stable'))}<small>{dependency}</small></td><td class='actions'>{actions}</td></tr>")
    return "".join(rows) or "<tr><td colspan='6' class='empty'>没有匹配的内容包</td></tr>"


def admin_page(config: dict[str, Any], message: str = "", actor: dict[str, Any] | None = None, secret_notice: str = "", section: str = "overview", query: str = "") -> str:
    """分类管理后台，保留原有写入路由并提供统一内容列表入口。"""
    merge_duplicate_credentials()
    merge_duplicate_tasks()
    esc = lambda value: html.escape(str(value), quote=True)
    actor = actor or {"username": "", "role": "subadmin", "permissions": []}
    valid_sections = {"overview", "packages", "tasks", "settings", "security", *PACKAGE_KINDS}
    section = section if section in valid_sections else "overview"
    csrf_html = f"<input type='hidden' name='csrf_token' value='{esc(admin_csrf_token(config, actor))}'>"
    records = _admin_package_records()
    can_packages = actor.get("role") == "primary" or "packages:write" in set(actor.get("permissions", []))
    labels = [("overview", "概览"), ("packages", "全部内容"), ("online_source", "书源"), ("association_source", "关联源"), ("epub_style", "EPUB 样式"), ("highlight_style", "高亮样式"), ("theme", "主题库"), ("tasks", "云端任务"), ("settings", "服务器设置"), ("security", "子管理员与安全")]
    credential_rows = []
    for credential in sorted(load_credentials().values(), key=lambda value: bounded_config_int(value.get("updatedAt", 0), 0, 0), reverse=True):
        owner = str(credential.get("owner", ""))
        owner_display = owner[:12] + "…" if len(owner) > 12 else owner
        public_credential = credential_public(credential)
        health = str(public_credential.get("health", "unverified"))
        credential_rows.append(f"<tr><td><strong>{esc(credential.get('id', ''))}</strong></td><td>{esc(credential.get('accountId', '') or '未提供')}</td><td>{esc(_admin_owner_label(owner))}<small>{esc(owner_display)}</small></td><td><span class='status status-{esc(health)}'>{esc(_admin_health_label(health))}</span><small>{esc(credential.get('lastVerifyMessage', '') or '暂无验证说明')}</small></td><td>{esc(_admin_time_label(credential.get('updatedAt', 0)))}</td></tr>")
    credential_table = "".join(credential_rows) or "<tr><td colspan='5' class='empty'>暂无模块上传的阅微同步密钥</td></tr>"
    credential_options = "<option value=''>不使用凭据</option>" + "".join(
        f"<option value='{esc(item.get('id', ''))}'>{esc(item.get('label', '阅微账号'))} · {esc(item.get('accountId', '') or item.get('owner', ''))}</option>"
        for item in sorted(load_credentials().values(), key=lambda value: str(value.get('label', '')))
    )
    task_rows = []
    for task in sorted(load_tasks().values(), key=lambda value: bounded_config_int(value.get("createdAt", 0), 0, 0), reverse=True):
        task_id = esc(task.get("id", "")); task_status = str(task.get("status", ""))
        task_actions = ""
        if actor.get("role") == "primary" or "tasks:write" in set(actor.get("permissions", [])):
            buttons = ["<button class='button subtle' name='action' value='run'>立即运行</button>"]
            if task_status == "running":
                buttons = []
            elif task.get("enabled", True) and task_status not in {"cancelled", "paused"}:
                buttons.append("<button class='button subtle' name='action' value='pause'>暂停</button>")
            else:
                buttons.append("<button class='button subtle' name='action' value='resume'>恢复</button>")
            if task_status not in {"cancelled", "running"}:
                buttons.append("<button class='button subtle' name='action' value='cancel'>取消</button>")
            if buttons:
                task_actions = f"<form class='inline' style='display:inline-flex;flex-direction:row;gap:6px' method='post' action='/admin/tasks/action'>{csrf_html}<input type='hidden' name='task_id' value='{task_id}'>{''.join(buttons)}</form>"
        task_rows.append(f"<tr><td><strong>{esc(_admin_task_label(task.get('taskType', '')))}</strong></td><td>{esc(_admin_owner_label(task.get('owner', '')))}</td><td>{esc(task_credential_id(task) or '未关联')}</td><td>{esc(_admin_task_detail(task))}</td><td><span class='status status-{esc(task_status)}'>{esc(_admin_task_status_label(task_status))}</span><small>{'已启用' if task.get('enabled', True) else '已停用'}</small></td><td>{esc(task.get('lastMessage', '') or '暂无执行记录')}</td><td>{esc(_admin_time_label(task.get('nextRunAt', 0)))}</td><td><a href='/admin/tasks/{task_id}/logs'>查看日志</a> {task_actions}</td></tr>")
    task_table = "".join(task_rows) or "<tr><td colspan='8' class='empty'>暂无云端任务</td></tr>"
    nav_html = "".join(f"<a class=\"{'active' if key == section else ''}\" href=\"{admin_section_path(key)}\">{label}<span>{len([x for x in records if x.get('kind') == key]) if key in PACKAGE_KINDS else ''}</span></a>" for key, label in labels)
    notice = f"<div class='notice'>{esc(message)}</div>" if message else ""
    secret = f"<div class='secret'><strong>请立即保存：</strong><br>{esc(secret_notice)}</div>" if secret_notice else ""
    if section in {"overview", "packages", *PACKAGE_KINDS}:
        selected = section if section in PACKAGE_KINDS else ""
        visible = [item for item in records if not selected or item.get("kind") == selected]
        title = "概览" if section == "overview" else ("内容管理" if section == "packages" else _admin_kind_label(section))
        head_action = f"<a class='button' href='/admin/packages/new?kind={esc(selected or 'online_source')}'>新增内容</a>" if can_packages else ""
        content = f"<div class='page-head'><div><p class='eyebrow'>内容中心</p><h1>{title}</h1><p class='muted'>独立管理版本、依赖、发布状态和历史版本。</p></div>{head_action}</div>"
        if section == "overview":
            content += f"<div class='stats'><div><strong>{len(records)}</strong><span>内容包</span></div><div><strong>{sum(1 for x in records if x.get('status', 'published') == 'published')}</strong><span>已发布</span></div><div><strong>{len(load_tasks())}</strong><span>云端任务</span></div><div><strong>{esc(config.get('serverId', ''))}</strong><span>服务器 ID</span></div></div>"
            content += '<template id="cloud-book-example">[{"cloudBookId":123,"bookId":123,"name":"书名"}]</template>'
            content += f"<form class='toolbar' method='get' action='{admin_section_path(section)}'><input name='q' value='{esc(query)}' placeholder='搜索名称、包 ID、版本或别名'><button class='button' type='submit'>搜索</button></form><div class='table-wrap'><table><thead><tr><th>类型</th><th>内容</th><th>版本</th><th>状态</th><th>渠道 / 依赖</th><th>操作</th></tr></thead><tbody>{_admin_package_table(visible, can_packages, query, admin_csrf_token(config, actor))}</tbody></table></div>"
    else:
        task_credentials = f"<div class='stats'><div><strong>{len(load_credentials())}</strong><span>同步密钥数量</span></div><div><strong>{len(load_tasks())}</strong><span>云端任务数量</span></div><div><strong>{sum(1 for value in load_tasks().values() if value.get('enabled', True))}</strong><span>已启用任务</span></div><div><strong>{sum(1 for value in load_tasks().values() if value.get('status') == 'failed')}</strong><span>执行失败任务</span></div></div><div class='panel'><h2>阅微同步密钥</h2><p class='muted'>密钥只保存加密密文，不显示原文。一个阅微账号 ID 对应一个同步密钥；重新同步会更新该账号的原记录。</p><div class='table-wrap'><table><thead><tr><th>同步密钥 ID</th><th>阅微账号 ID</th><th>来源账号</th><th>验证状态</th><th>最近更新时间</th></tr></thead><tbody>{credential_table}</tbody></table></div></div><div class='panel'><h2>任务运行状态</h2><div class='table-wrap'><table><thead><tr><th>任务</th><th>来源账号</th><th>同步密钥 ID</th><th>任务详情</th><th>运行状态</th><th>最近结果</th><th>下次执行</th><th>操作 / 日志</th></tr></thead><tbody>{task_table}</tbody></table></div></div>" if section == "tasks" else ""
        if section == "tasks":
            task_form = f"<div class='panel'><h2>创建云端任务</h2><form method='post' action='/admin/tasks/create'>{csrf_html}<div class='grid-2'><label>任务类型<select name='task_type'><option value='yeshe_checkin'>野社零点签到</option><option value='yeshe_draw_card'>野社自动抽卡</option><option value='cloud_auto_read'>云端自动阅读</option><option value='http'>通用 HTTPS 请求</option></select></label><label>每日执行时间<input name='time_of_day' value='00:05'></label><label>凭据<select name='credential_id'>{credential_options}</select></label><label>所有者<input name='owner' value='admin'></label><label>阅读时长（分钟）<input name='duration_minutes' type='number' min='1' max='720' value='30'></label><label>最近阅读数量<input name='recent_limit' type='number' min='1' max='20' value='1'></label></div><label>自定义图书 JSON<textarea name='request_body' placeholder='留空则使用最近阅读记录'></textarea></label><button class='button' type='submit'>创建任务</button></form></div>"
            content = f"<div class='page-head'><div><p class='eyebrow'>自动化</p><h1>云端任务</h1><p class='muted'>配置、查看和控制自动阅读、签到、抽卡任务。每个阅微账号只保留一个同步密钥，重新同步会更新原密钥。</p></div></div>{task_credentials}{task_form}"
        elif section == "settings":
            selected_mode = str(config.get("authMode") or infer_auth_mode(config))
            mode_options = "".join(f"<option value='{mode}' {'selected' if mode == selected_mode else ''}>{label}</option>" for mode, label in (("public", "公开访问"), ("api_key", "API Key"), ("account", "独立账号密码"), ("host_account_allowlist", "阅微账号白名单")))
            content = f"<div class='page-head'><div><p class='eyebrow'>系统</p><h1>服务器设置</h1><p class='muted'>认证模式为单选；只有当前选中的认证方式会对 API 生效。</p></div></div><div class='panel'><form method='post' action='/admin/settings'>{csrf_html}<input type='hidden' name='signing_public_key' value='{esc(config.get('signingPublicKey', ''))}'><input type='hidden' name='release_version_code' value='{esc(config.get('releaseVersionCode', 0))}'><input type='hidden' name='github_include_prerelease' value='{'on' if config.get('githubIncludePrerelease') else ''}'><input type='hidden' name='server_snapshot_enabled' value='{'on' if config.get('serverSnapshotEnabled', True) else ''}'><div class='grid-2'><label>服务器 ID<input name='server_id' value='{esc(config.get('serverId', ''))}'></label><label>最低模块版本<input name='min_module_version' value='{esc(config.get('minModuleVersion', ''))}'></label><label>认证类型<select name='auth_mode' id='auth-mode'>{mode_options}</select></label><label>GitHub 仓库<input name='github_repository' value='{esc(config.get('githubRepository', ''))}'></label><label>同步间隔（秒）<input name='release_sync_seconds' type='number' value='{esc(config.get('releaseSyncSeconds', 1800))}'></label><label>快照间隔（秒）<input name='server_snapshot_seconds' type='number' value='{esc(config.get('serverSnapshotSeconds', 86400))}'></label><label>快照保留份数<input name='server_snapshot_retention' type='number' value='{esc(config.get('serverSnapshotRetention', 30))}'></label></div><div data-auth-section='api_key'><label>API Key（留空保持）<input type='password' name='api_key'></label><label><input type='checkbox' name='generate_api_key'> 生成新 API Key</label></div><div data-auth-section='account'><label>独立账号名<input name='account_name'></label><label>独立账号密码<input type='password' name='account_password'></label></div><div data-auth-section='host_account_allowlist'><label>阅微账号白名单<textarea name='host_account_allowlist'>{esc(chr(10).join(config.get('hostAccountAllowlist', [])))}</textarea></label></div><label>功能列表<textarea name='features'>{esc(','.join(config.get('features', [])))}</textarea></label><button class='button' type='submit'>保存设置</button><button class='button subtle' type='submit' formaction='/admin/sync'>立即同步模块</button></form></div><script>(()=>{{const select=document.getElementById('auth-mode');if(!select)return;const sync=()=>document.querySelectorAll('[data-auth-section]').forEach(node=>{{node.hidden=node.dataset.authSection!==select.value;node.querySelectorAll('input,textarea,select').forEach(input=>input.disabled=node.hidden)}});select.addEventListener('change',sync);sync()}})();</script>"
        else:
            admin_block = ""
            if section == "security" and actor.get("role") == "primary":
                admin_block = f"<div class='panel'><h2>子管理员</h2><form method='post' action='/admin/admins/create'>{csrf_html}<div class='grid-2'><label>用户名<input name='username' required></label><label>初始密码<input type='password' name='password'></label></div><label>权限<input name='permissions' value='settings:write,packages:write,tasks:write'></label><button class='button' type='submit'>创建子管理员</button></form></div>"
            content = f"<div class='page-head'><div><p class='eyebrow'>保护</p><h1>子管理员与安全</h1><p class='muted'>管理员、快照和审计操作。</p></div></div>{admin_block}<div class='panel'><h2>服务器快照</h2><p>快照包含数据库与服务器配置，并支持完整性校验。</p><form method='post' action='/admin/backups/server'>{csrf_html}<button class='button' type='submit'>立即创建快照</button></form><p><a href='/admin/audit'>查看审计日志</a> · <a href='/admin/backups/server'>快照列表</a></p></div>"
    content = f"<p class='muted' style='margin:0 0 12px;text-align:right;font-size:12px'>API v{API_VERSION}</p>" + content
    return f"""<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>ReaMicro API 管理后台</title><style>:root{{--line:#e5e9f0;--muted:#64748b;--blue:#2563eb;--ink:#1f2937}}*{{box-sizing:border-box}}body{{margin:0;background:#f4f7fb;color:var(--ink);font-family:system-ui,-apple-system,'Microsoft YaHei',sans-serif;line-height:1.5}}aside{{position:fixed;inset:0 auto 0 0;width:236px;padding:22px 14px;background:#f8fafc;border-right:1px solid var(--line);overflow-y:auto}}.brand{{font-size:18px;font-weight:700;padding:0 12px 22px}}.brand small{{display:block;color:var(--muted);font-size:11px;margin-top:5px}}nav a{{display:flex;justify-content:space-between;align-items:center;padding:10px 12px;margin:3px 0;border-radius:6px;color:#475569;text-decoration:none;font-size:14px}}nav a.active,nav a:hover{{background:#e8f0ff;color:#1d4ed8;font-weight:650}}nav span{{color:#94a3b8;font-size:11px}}main{{margin-left:236px;max-width:1480px;padding:30px 38px 56px}}.topbar{{display:flex;justify-content:flex-end;align-items:center;gap:15px;color:var(--muted);font-size:13px;margin-bottom:24px}}.page-head{{display:flex;justify-content:space-between;align-items:flex-start;gap:20px;margin-bottom:20px}}h1{{margin:3px 0 5px;font-size:26px;line-height:1.25}}h2{{margin:0 0 16px;font-size:19px;line-height:1.3}}.eyebrow{{color:var(--blue);font-size:12px;font-weight:700;margin:0}}.muted,small{{color:var(--muted)}}.stats{{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin-bottom:20px}}.stats div,.table-wrap,.panel{{background:#fff;border:1px solid var(--line);border-radius:8px}}.stats div{{padding:18px}}.stats strong{{display:block;font-size:24px}}.stats span{{color:var(--muted);font-size:13px}}.toolbar{{display:flex;gap:8px;align-items:center;margin-bottom:12px}}input,textarea,select{{width:100%;padding:9px 10px;border:1px solid #cfd6e1;border-radius:5px;background:#fff;color:var(--ink);font:inherit}}textarea{{min-height:110px;resize:vertical}}label{{display:flex;flex-direction:column;gap:6px;min-width:0;font-size:13px;font-weight:600;color:#334155}}label:has(input[type=checkbox]){{display:flex;flex-direction:row;align-items:center;gap:8px}}input[type=checkbox]{{width:auto}}.grid-2{{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;margin-bottom:14px}}.panel form{{display:flex;flex-direction:column;gap:14px}}.panel form .button{{align-self:flex-start}}.toolbar input{{width:min(420px,100%)}}.button{{display:inline-flex;align-items:center;justify-content:center;min-height:38px;padding:9px 14px;border:0;border-radius:5px;background:var(--blue);color:#fff;text-decoration:none;font:inherit;font-weight:650;cursor:pointer;white-space:nowrap}}.button.subtle{{background:#eef2f7;color:#334155;padding:6px 9px;min-height:32px;font-size:12px}}.table-wrap{{overflow:auto}}table{{width:100%;border-collapse:collapse;min-width:760px}}th,td{{padding:13px 14px;border-bottom:1px solid var(--line);text-align:left;font-size:13px;vertical-align:top}}th{{background:#fafbfc;color:var(--muted);font-weight:650;white-space:nowrap}}td small{{display:block;margin-top:4px;font-size:11px}}.status{{display:inline-block;padding:3px 7px;border-radius:4px;font-size:11px;background:#eef2f7}}.status-published{{background:#ecfdf3;color:#047857}}.status-draft{{background:#fff7ed;color:#b45309}}.actions{{white-space:nowrap}}.notice,.secret,.panel{{padding:18px;margin-bottom:18px}}.notice{{background:#ecfdf5;color:#166534}}.secret{{background:#fff7ed;color:#9a3412}}.inline{{display:inline-flex;gap:6px;align-items:center}}.empty{{padding:28px;text-align:center;color:var(--muted)}}@media(max-width:900px){{aside{{width:200px}}main{{margin-left:200px;padding:24px 20px}}.stats{{grid-template-columns:repeat(2,minmax(0,1fr))}}.grid-2{{grid-template-columns:1fr}}}}@media(max-width:620px){{aside{{position:static;width:auto;border-right:0;border-bottom:1px solid var(--line)}}main{{margin-left:0;padding:20px 14px}}nav{{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:2px}}.topbar{{justify-content:flex-start;flex-wrap:wrap}}.stats{{grid-template-columns:1fr 1fr}}.toolbar{{align-items:stretch;flex-direction:column}}.toolbar input{{width:100%}}.page-head{{display:block}}}}</style></head><body><aside><div class='brand'>ReaMicro<small>API 管理后台 · 5222</small></div><nav>{nav_html}</nav></aside><main><div class='topbar'><span>管理员：{esc(actor.get('username', ''))}</span><span>{'主管理员' if actor.get('role') == 'primary' else '子管理员'}</span><form class='inline' method='post' action='/admin/logout'>{csrf_html}<button class='button subtle' type='submit'>退出</button></form></div>{notice}{secret}{content}</main></body></html>"""


@app.get("/admin/legacy", response_class=HTMLResponse)
async def admin_legacy(actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    return RedirectResponse("/admin/settings", status_code=303)


def _admin_shell(title: str, body: str, config: dict[str, Any], actor: dict[str, Any]) -> str:
    esc = lambda value: html.escape(str(value), quote=True)
    csrf_html = f"<input type='hidden' name='csrf_token' value='{esc(admin_csrf_token(config, actor))}'>"
    return f"<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>{esc(title)}</title><style>body{{margin:0;background:#f4f7fb;color:#1f2937;font-family:system-ui,-apple-system,'Microsoft YaHei',sans-serif}}main{{max-width:1100px;margin:0 auto;padding:28px 20px}}.panel{{background:#fff;border:1px solid #e5e9f0;border-radius:8px;padding:22px;margin-top:16px}}h1{{margin:0 0 5px}}.muted,small{{color:#64748b}}label{{display:block;font-weight:600;font-size:13px;margin:13px 0}}input,textarea,select{{width:100%;box-sizing:border-box;margin-top:6px;padding:9px;border:1px solid #cfd6e1;border-radius:5px;font:inherit}}textarea{{min-height:260px;font-family:ui-monospace,monospace}}.grid{{display:grid;grid-template-columns:repeat(2,1fr);gap:14px}}button,.button{{display:inline-block;padding:9px 14px;border:0;border-radius:5px;background:#2563eb;color:#fff;text-decoration:none;font:inherit;font-weight:650;cursor:pointer}}.subtle{{background:#eef2f7;color:#334155}}pre{{white-space:pre-wrap;overflow:auto;background:#0f172a;color:#e2e8f0;border-radius:6px;padding:16px;line-height:1.55}}table{{width:100%;border-collapse:collapse}}th,td{{padding:11px;border-bottom:1px solid #e5e9f0;text-align:left;font-size:13px}}.notice{{padding:11px;background:#ecfdf5;color:#166534;border-radius:6px}}@media(max-width:700px){{.grid{{grid-template-columns:1fr}}}}</style></head><body><main><p><a href='/admin'>← 返回后台</a></p><h1>{esc(title)}</h1>{body}<p><form method='post' action='/admin/logout'>{csrf_html}<button class='subtle' type='submit'>退出登录</button></form></p></main></body></html>"


def _admin_package_payload(package_dir: Path, manifest: dict[str, Any]) -> tuple[str, bytes]:
    filename = safe_payload_filename(str(manifest.get("payload", "payload.bin")))
    payload_path = package_dir / filename
    if not payload_path.is_file():
        raise HTTPException(status_code=404, detail="内容文件不存在")
    return filename, payload_path.read_bytes()


@app.get("/admin/packages/new", response_class=HTMLResponse)
async def admin_new_package(kind: str = Query("online_source"), actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    require_admin_permission(actor, "packages:write")
    kind = kind if kind in PACKAGE_KINDS else "online_source"
    config = load_config(); esc = lambda value: html.escape(str(value), quote=True)
    csrf = f"<input type='hidden' name='csrf_token' value='{esc(admin_csrf_token(config, actor))}'>"
    options = "".join(f"<option value='{k}' {'selected' if k == kind else ''}>{_admin_kind_label(k)}</option>" for k in sorted(PACKAGE_KINDS))
    body = f"<div class='panel'><p class='muted'>包 ID、显示名称和版本号可留空：服务器会根据文件内容及已有列表自动生成；同一包再次上传会自动归档旧版本。</p><form method='post' action='/admin/packages/upload' enctype='multipart/form-data'>{csrf}<div class='grid'><label>内容类型<select name='kind'>{options}</select></label><label>包 ID（可留空自动生成）<input name='package_id' placeholder='根据名称自动生成'></label><label>版本号（可留空自动递增）<input name='version' placeholder='新包默认为 1.0.0'></label><label>显示名称（可留空自动识别）<input name='name' placeholder='从书源/样式文件识别'></label><label>稳定内容 ID（可留空使用包 ID）<input name='content_id'></label><label>状态<select name='status_value'><option value='published'>立即发布</option><option value='draft'>草稿</option><option value='testing'>测试中</option></select></label><label>渠道<select name='channel'><option>stable</option><option>beta</option><option>nightly</option></select></label></div><label>别名（逗号分隔）<input name='aliases'></label><label>依赖 JSON<textarea name='dependencies' placeholder='[]'></textarea></label><label>内容文件<input type='file' name='payload' required></label><button type='submit'>上传并保存</button></form></div>"
    return HTMLResponse(_admin_shell("新增内容", body, config, actor))


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
    body = f"<div class='panel'><p class='muted'>{html.escape(_admin_kind_label(package_kind))} · {html.escape(package_id)} · 版本 {html.escape(str(manifest.get('version', '')))} · 构建时间 {html.escape(str(manifest.get('buildTime', '')))}</p><pre>{html.escape(text)}</pre></div>"
    return HTMLResponse(_admin_shell("预览 · " + _admin_kind_label(package_kind), body, load_config(), actor))


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
    status_options = "".join(f"<option value='{status}' {'selected' if manifest.get('status', 'published') == status else ''}>{status}</option>" for status in ('published', 'draft', 'testing', 'unpublished'))
    channel_options = "".join(f"<option value='{channel}' {'selected' if manifest.get('channel', 'stable') == channel else ''}>{channel}</option>" for channel in ('stable', 'beta', 'nightly'))
    body = f"<div class='panel'><form method='post' action='/admin/packages/{esc(package_kind)}/{esc(package_id)}/edit'>{csrf}<div class='grid'><label>版本号<input name='version' value='{esc(manifest.get('version', ''))}' required></label><label>内容文件名<input name='filename' value='{esc(filename)}' required></label><label>稳定内容 ID<input name='content_id' value='{esc(manifest.get('contentId', ''))}'></label><label>名称<input name='name' value='{esc(manifest.get('name', package_id))}'></label><label>发布状态<select name='status_value'>{status_options}</select></label><label>发布渠道<select name='channel'>{channel_options}</select></label></div><label>别名（逗号分隔）<input name='aliases' value='{esc(','.join(manifest.get('aliases', [])))}'></label><label>依赖 JSON<textarea name='dependencies'>{esc(json.dumps(manifest.get('dependencies', []), ensure_ascii=False, indent=2))}</textarea></label><label>内容<textarea name='payload_text'>{esc(text)}</textarea></label><button type='submit'>保存新版本</button> <a class='button subtle' href='/admin/packages/{esc(package_kind)}/{esc(package_id)}/preview'>取消</a></form></div>"
    return HTMLResponse(_admin_shell("编辑 · " + _admin_kind_label(package_kind), body, config, actor))


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
    manifest_path, _ = package_manifest(package_kind, package_id); rows = []
    for path in sorted((manifest_path.parent / "history").glob("*/manifest.json"), reverse=True):
        try: item = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError): continue
        version_value = html.escape(str(item.get("version", "")), quote=True)
        csrf = html.escape(admin_csrf_token(load_config(), actor), quote=True)
        rows.append(f"<tr><td>{version_value}</td><td>{html.escape(str(item.get('buildTime', '')))}</td><td>{html.escape(str(item.get('sha256', ''))[:16])}</td><td>{html.escape(str(item.get('status', '')))}</td><td><form method='post' action='/admin/packages/{html.escape(package_kind)}/{html.escape(package_id)}/rollback'><input type='hidden' name='csrf_token' value='{csrf}'><input type='hidden' name='version' value='{version_value}'><button type='submit'>回滚</button></form></td></tr>")
    history_rows = ''.join(rows) or '<tr><td colspan="5">暂无历史版本</td></tr>'
    body = f"<div class='panel'><table><thead><tr><th>版本</th><th>构建时间</th><th>SHA256</th><th>状态</th><th>操作</th></tr></thead><tbody>{history_rows}</tbody></table></div>"
    return HTMLResponse(_admin_shell("历史版本 · " + _admin_kind_label(package_kind), body, load_config(), actor))


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
    trash_dir = PACKAGE_ROOT / ".trash" / package_kind
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
    body = f"<div class='panel'><p>历史版本 {html.escape(str(target_manifest.get('version')))} 与当前版本 {html.escape(str(current.get('version')))} 的差异。</p><pre>{html.escape(diff or '内容没有文本差异')}</pre></div>"
    return HTMLResponse(_admin_shell("内容差异", body, load_config(), actor))


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
        ADMIN_SESSION_COOKIE,
        token,
        max_age=ADMIN_SESSION_SECONDS,
        httponly=True,
        secure=ADMIN_COOKIE_SECURE,
        samesite="lax",
        path="/admin",
    )
    return result


@app.post("/admin/logout")
async def admin_logout(request: Request, csrf_token: str = Form("")) -> RedirectResponse:
    token = request.cookies.get(ADMIN_SESSION_COOKIE, "")
    if token:
        actor = validate_admin_session_token(token)
        if actor:
            require_admin_csrf(load_config(), actor, csrf_token)
        get_state_store().delete_admin_session(api_key_digest(token))
    result = RedirectResponse("/admin/login", status_code=303)
    result.delete_cookie(ADMIN_SESSION_COOKIE, path="/admin")
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
    if package_kind not in PACKAGE_KINDS:
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


@app.get("/admin/api-keys")
async def admin_api_keys(credentials: HTTPBasicCredentials | None = Depends(basic_security)) -> dict[str, Any]:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    records = []
    for item in load_config().get("apiKeyRecords", []):
        if not isinstance(item, dict):
            continue
        records.append({key: item.get(key) for key in ("id", "name", "permissions", "enabled", "createdAt", "lastUsedAt", "revokedAt")})
    return response({"items": records})


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
    raw_permissions = payload.get("permissions", ["read"])
    if not isinstance(raw_permissions, list):
        raise HTTPException(status_code=400, detail=response(code="API_SCOPE_INVALID", message="permissions 必须是数组"))
    allowed = {"read", "write", "packages:read", "packages:write", "tasks:read", "tasks:write", "credentials:read", "credentials:write", "backup:read"}
    permissions = sorted({str(item).strip() for item in raw_permissions if str(item).strip()})
    if not set(permissions).issubset(allowed):
        raise HTTPException(status_code=400, detail=response(code="API_SCOPE_INVALID", message="存在不支持的 API Key 权限范围"))
    plain = generate_long_secret(48)
    record = {"id": "key_" + secrets.token_hex(8), "name": str(payload.get("name") or "API Key")[:80], "digest": api_key_digest(plain), "permissions": permissions or ["read"], "enabled": True, "createdAt": int(datetime.now(timezone.utc).timestamp() * 1000), "lastUsedAt": 0}
    config["apiKeyRecords"] = [item for item in config.get("apiKeyRecords", []) if isinstance(item, dict)] + [record]
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
    if not re.fullmatch(r"reamicro-server-[0-9TZ-]+\.zip", filename):
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="备份文件名无效"))
    path = (SERVER_BACKUP_ROOT / filename).resolve()
    if path.parent != SERVER_BACKUP_ROOT.resolve() or not path.is_file():
        raise HTTPException(status_code=404, detail=response(code="BACKUP_NOT_FOUND", message="服务器快照不存在"))
    return FileResponse(path, media_type="application/zip", filename=filename)


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
    if not re.fullmatch(r"reamicro-server-[0-9TZ-]+\.zip", filename):
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="备份文件名无效"))
    path = (SERVER_BACKUP_ROOT / filename).resolve()
    if path.parent != SERVER_BACKUP_ROOT.resolve() or not path.is_file():
        raise HTTPException(status_code=404, detail=response(code="BACKUP_NOT_FOUND", message="服务器快照不存在"))
    try: result = verify_server_snapshot(path)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message=f"备份校验失败：{error}"))
    return JSONResponse(content=response(result), status_code=200 if result.get("valid") else 409)


@app.post("/admin/backups/server/{filename}/restore")
async def admin_restore_server_backup(filename: str, request: Request, credentials: HTTPBasicCredentials | None = Depends(basic_security), x_admin_csrf: str = Header(default="")) -> JSONResponse:
    global state_store
    actor = require_admin(credentials); require_primary_admin(actor); require_admin_permission(actor, "backup:admin"); require_admin_csrf(load_config(), actor, x_admin_csrf)
    try: payload = await request.json()
    except ValueError: payload = {}
    if not isinstance(payload, dict) or payload.get("confirm") is not True:
        raise HTTPException(status_code=400, detail=response(code="BACKUP_CONFIRM_REQUIRED", message="恢复操作必须明确 confirm=true"))
    if not re.fullmatch(r"reamicro-server-[0-9TZ-]+\.zip", filename):
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="备份文件名无效"))
    path = (SERVER_BACKUP_ROOT / filename).resolve()
    if path.parent != SERVER_BACKUP_ROOT.resolve() or not path.is_file():
        raise HTTPException(status_code=404, detail=response(code="BACKUP_NOT_FOUND", message="服务器快照不存在"))
    verification = verify_server_snapshot(path)
    if not verification.get("valid"):
        raise HTTPException(status_code=409, detail=response(code="BACKUP_INVALID", message="备份完整性校验失败"))
    safety_snapshot = await asyncio.to_thread(create_server_snapshot)
    with tempfile.TemporaryDirectory(prefix="reamicro-restore-") as directory:
        extract_root = Path(directory)
        with zipfile.ZipFile(path) as archive: archive.extractall(extract_root)
        restored_db = extract_root / "state" / "reamicro.sqlite3"; restored_config = extract_root / "config" / "server.json"
        if not restored_db.is_file() or not restored_config.is_file():
            raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="备份缺少数据库或配置"))
        CONFIG_ROOT.mkdir(parents=True, exist_ok=True); STATE_DB_PATH.parent.mkdir(parents=True, exist_ok=True)
        Path(str(STATE_DB_PATH) + "-wal").unlink(missing_ok=True); Path(str(STATE_DB_PATH) + "-shm").unlink(missing_ok=True)
        shutil.copy2(restored_config, CONFIG_PATH); shutil.copy2(restored_db, STATE_DB_PATH)
    with state_store_lock: state_store = None
    audit_event("server_snapshot_restored", audit_actor(actor), metadata={"filename": filename, "safetySnapshot": safety_snapshot.name})
    return JSONResponse(content=response({"restored": True, "filename": filename, "safetySnapshot": safety_snapshot.name}))


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
        return HTMLResponse(admin_page(config, "任务不存在", actor=actor, section="tasks"), status_code=404)
    try:
        message = apply_task_action(task, action)
    except ValueError:
        return HTMLResponse(admin_page(config, "不支持的任务操作", actor=actor, section="tasks"), status_code=400)
    save_tasks(tasks)
    task_log(task_id, message)
    audit_event("admin_task_action", audit_actor(actor), metadata={"taskId": task_id, "action": action})
    return HTMLResponse(admin_page(config, message, actor=actor, section="tasks"))


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
        get_state_store().delete_admin_sessions_for_user(str(actor.get("username", "")))
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
        "secretKey": generated_secret_key or current.get("secretKey", "") or SECRET_KEY,
        "allowPublic": selected_auth_mode == "public",
        "authMode": selected_auth_mode,
        "hostAccountAllowlist": ([item.strip() for item in host_account_allowlist.splitlines() if item.strip()] if selected_auth_mode == "host_account_allowlist" else current.get("hostAccountAllowlist", [])),
        "features": [item.strip() for item in features.split(",") if item.strip()],
        "signingPublicKey": signing_public_key.strip(),
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
    get_state_store().delete_admin_sessions_for_user(username)
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
        get_state_store().delete_admin_sessions_for_user(username)
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
    get_state_store().delete_admin_sessions_for_user(username)
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


PACKAGE_KINDS = {
    "online_source",
    "epub_style",
    "highlight_style",
    "association_source",
    "theme",
}


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
        if dependency_kind and dependency_kind not in PACKAGE_KINDS:
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
    for kind in PACKAGE_KINDS:
        for path in (PACKAGE_ROOT / kind).glob("*/manifest.json"):
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


def infer_package_metadata(kind: str, filename: str, body: bytes) -> dict[str, Any]:
    """从书源 JSON 或样式文件中提取外显名称和稳定标识。"""
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
    return {"name": name[:120], "identity": identity[:12], "explicitName": explicit_name}


def package_name_slug(name: str, kind: str) -> str:
    normalized = unicodedata.normalize("NFKC", name or "")
    normalized = re.sub(r"[^\w.-]+", "-", normalized, flags=re.UNICODE).strip("-._")
    normalized = normalized[:80].strip("-._")
    return normalized or kind.replace("_", "-")


def _package_manifests(kind: str) -> list[tuple[Path, dict[str, Any]]]:
    values: list[tuple[Path, dict[str, Any]]] = []
    for path in sorted((PACKAGE_ROOT / kind).glob("*/manifest.json")):
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
    if kind not in PACKAGE_KINDS:
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
    package_dir = (PACKAGE_ROOT / kind / package_id).resolve()
    if PACKAGE_ROOT.resolve() not in package_dir.parents:
        raise HTTPException(status_code=400, detail="内容包路径无效")
    package_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = package_dir / "manifest.json"
    old_payload_path: Path | None = None
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
            old_payload_path = old_payload
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
        "aliases": merge_package_aliases(existing_manifest.get("aliases", []) if existing_manifest else [], metadata.get("identity", []), aliases),
        "name": _metadata_text(name) or (str(existing_manifest.get("name", "")) if existing_manifest and not metadata.get("explicitName") else "") or str(metadata.get("name") or package_id),
        "description": str(existing_manifest.get("description", "")) if existing_manifest and existing_manifest.get("description") else "后台上传内容包",
        "status": normalized_status,
        "channel": normalized_channel,
        "dependencies": dependency_items,
    }
    if normalized_status == "published":
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
            if not credential or credential.get("owner") != owner:
                raise ValueError("阅微凭据不存在或不属于任务所有者")
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
    metadata_path = RELEASE_ROOT / "latest.json"
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


@app.post("/v1/webhooks/github")
async def github_release_webhook(request: Request) -> dict[str, Any]:
    if not GITHUB_WEBHOOK_SECRET:
        raise HTTPException(status_code=503, detail=response(code="WEBHOOK_DISABLED", message="未配置 GitHub Webhook Secret"))
    body = await request.body()
    provided = request.headers.get("X-Hub-Signature-256", "")
    expected = "sha256=" + hmac.new(GITHUB_WEBHOOK_SECRET.encode("utf-8"), body, hashlib.sha256).hexdigest()
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
    enabled = bool(value.get("enabled", True))
    verify_failures = max(int(value.get("verifyFailures", 0)), 0)
    if not enabled:
        health = "paused"
    elif verify_failures >= 3:
        health = "invalid"
    elif verify_failures:
        health = "warning"
    elif value.get("lastVerifiedAt"):
        health = "valid"
    else:
        health = "unverified"
    return {
        "id": value.get("id", ""),
        "type": value.get("type", "reamicro"),
        "label": value.get("label", "阅微账号"),
        "accountId": value.get("accountId", ""),
        "createdAt": value.get("createdAt", 0),
        "updatedAt": value.get("updatedAt", 0),
        "lastVerifiedAt": value.get("lastVerifiedAt", 0),
        "lastVerifyMessage": value.get("lastVerifyMessage", ""),
        "lastUsedAt": value.get("lastUsedAt", 0),
        "lastFailedAt": value.get("lastFailedAt", 0),
        "verifyFailures": verify_failures,
        "enabled": enabled,
        "health": health,
    }


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


def owner_host_account_id(owner: str) -> str:
    marker = ":host:"
    if marker in owner:
        return owner.rsplit(marker, 1)[1]
    if owner.startswith("host:"):
        return owner.split(":", 1)[1]
    if owner.startswith("host-public:"):
        return owner.split(":", 1)[1]
    return ""


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
        previous = get_state_store().get_idempotency(owner, "save_credential", idempotency_key)
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
        get_state_store().save_idempotency(owner, "save_credential", idempotency_key, result, now)
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
        previous = get_state_store().get_idempotency(owner, "create_task", idempotency_key)
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
        get_state_store().save_idempotency(owner, "create_task", idempotency_key, result, now)
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
    manifest_path = PACKAGE_ROOT / package_kind / package_id / "manifest.json"
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
    path = TASK_LOG_ROOT / f"{task_id}.log"
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
    rows = []
    for item in task.get("executionHistory", [])[-100:][::-1] if isinstance(task.get("executionHistory"), list) else []:
        rows.append(f"<tr><td>{html.escape(str(item.get('finishedAt', '')))}</td><td>{html.escape(str(item.get('result', '')))}</td><td>{html.escape(str(item.get('durationMs', '')))} ms</td><td>{html.escape(str(item.get('message', '')))}</td></tr>")
    body = f"<div class='panel'><p>任务：{html.escape(task_id)} · 类型：{html.escape(str(task.get('taskType', '')))}</p><table><thead><tr><th>完成时间</th><th>结果</th><th>耗时</th><th>消息</th></tr></thead><tbody>{''.join(rows) or '<tr><td colspan=\"4\">暂无执行记录</td></tr>'}</tbody></table></div>"
    return HTMLResponse(_admin_shell("任务执行日志", body, load_config(), actor))


@app.get("/v1/releases/module/download")
async def download_release(request: Request, _: None = Depends(authenticated)) -> Response:
    apk_path = RELEASE_ROOT / "latest.apk"
    if not apk_path.is_file():
        raise HTTPException(status_code=404, detail=response(code="NOT_FOUND", message="模块 APK 尚未同步"))
    metadata_path = RELEASE_ROOT / "latest.json"
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
    for manifest_path in sorted((PACKAGE_ROOT / kind).glob("*/manifest.json")):
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
    package_dir = (PACKAGE_ROOT / package_kind / package_id).resolve()
    if PACKAGE_ROOT.resolve() not in package_dir.parents:
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
    manifest_path = PACKAGE_ROOT / package_kind / package_id / "manifest.json"
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
        if PACKAGE_ROOT.resolve() not in payload.parents:
            raise ValueError("invalid payload path")
    except HTTPException:
        raise
    except (OSError, ValueError, KeyError):
        raise HTTPException(status_code=500, detail=response(code="PACKAGE_INVALID", message="内容包清单无效"))
    if not payload.is_file():
        raise HTTPException(status_code=404, detail=response(code="NOT_FOUND", message="内容包文件不存在"))
    return FileResponse(payload, media_type="application/octet-stream", filename=payload.name)
