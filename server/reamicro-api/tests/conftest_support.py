"""端到端测试共用的服务器隔离与客户端构造。

所有路径常量都是模块级全局，直接改 main 上的属性即可把一整个服务器实例
重定向到临时目录，互不干扰。
"""
import hashlib
import base64
import importlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi.testclient import TestClient

from app import main, runtime
from app import config_store
from app import crypto
from app import security
from app import state

ADMIN_USER = "owner"
ADMIN_PASSWORD = "owner-password-12345"
HOST_ACCOUNT_ID = "3"


def isolate(root: Path, secret_key: str = "test-secret-key-for-end-to-end-suite") -> None:
    """把服务器的全部持久化路径指到 root 下。

    路径和可变状态都住在 `app.runtime`，各模块在调用时读 `runtime.X`，
    所以这里改 runtime 的属性就能整体重定向。**不要改 main 上的同名属性**——
    拆分后那些只是导入时的引用，改了不生效。
    """
    runtime.CONFIG_ROOT = root / "config"
    runtime.CONFIG_PATH = runtime.CONFIG_ROOT / "server.json"
    runtime.PACKAGE_ROOT = root / "packages"
    runtime.RELEASE_ROOT = root / "releases" / "module"
    runtime.RELEASE_STATUS_PATH = runtime.RELEASE_ROOT / "sync-status.json"
    runtime.BACKUP_ROOT = root / "backups"
    runtime.SECRET_BACKUP_ROOT = root / "backups" / "secrets"
    runtime.SERVER_BACKUP_ROOT = root / "backups" / "server"
    runtime.TASK_ROOT = root / "tasks"
    runtime.TASKS_PATH = runtime.TASK_ROOT / "tasks.json"
    runtime.TASK_LOG_ROOT = runtime.TASK_ROOT / "logs"
    runtime.AUDIT_ROOT = root / "audit"
    runtime.AUDIT_PATH = runtime.AUDIT_ROOT / "events.jsonl"
    runtime.ACCOUNT_ROOT = root / "accounts"
    runtime.ACCOUNT_PATH = runtime.ACCOUNT_ROOT / "credentials.json"
    runtime.STATE_DB_PATH = root / "state" / "reamicro.sqlite3"
    runtime.SECRET_KEY = secret_key
    runtime.SIGNING_PRIVATE_KEY_FILE = ""
    runtime.GITHUB_WEBHOOK_SECRET = ""
    runtime.ADMIN_COOKIE_SECURE = False
    runtime.state_store = None
    runtime.rate_buckets.clear()
    runtime.metrics_counters.update({"requests": 0, "errors": 0, "rate_limited": 0})
    runtime.metrics_routes.clear()


def base_config(**overrides) -> dict:
    config = {
        **config_store.default_config(),
        "authMode": "host_account_allowlist",
        "hostAccountAllowlist": [HOST_ACCOUNT_ID],
        "primaryAdmin": {
            "username": ADMIN_USER,
            "passwordHash": crypto.password_hash(ADMIN_PASSWORD),
        },
        "adminAccounts": {},
        "minModuleVersion": "2.0.0",
    }
    config.update(overrides)
    return config_store.save_config(config)


def client() -> TestClient:
    """不触发 startup 事件，避免后台调度循环干扰测试。"""
    return TestClient(main.app, raise_server_exceptions=False)


def module_headers(account_id: str = HOST_ACCOUNT_ID, **extra) -> dict:
    headers = {"X-ReaMicro-Host-Account-Id": account_id, "Accept": "application/json"}
    headers.update(extra)
    return headers


def admin_auth() -> tuple[str, str]:
    return (ADMIN_USER, ADMIN_PASSWORD)


def admin_csrf(actor_role: str = "primary", username: str = ADMIN_USER) -> str:
    actor = {"username": username, "role": actor_role, "permissions": [], "needsSetup": False}
    return security.admin_csrf_token(config_store.load_config(), actor)


def seed_package(kind: str = "online_source", package_id: str = "example-com", **manifest_overrides) -> dict:
    """写入一个已发布内容包（含 payload 与一份历史版本）。"""
    package_dir = runtime.PACKAGE_ROOT / kind / package_id
    package_dir.mkdir(parents=True, exist_ok=True)
    payload = json.dumps({"bookSourceName": "示例书源", "bookSourceUrl": "https://example.com"}, ensure_ascii=False).encode("utf-8")
    (package_dir / "source.json").write_bytes(payload)
    manifest = {
        "packageId": package_id,
        "kind": kind,
        "version": "1.2.0",
        "buildTime": 1750000000000,
        "schemaVersion": 1,
        "minModuleVersion": "2.0.0",
        "sha256": hashlib.sha256(payload).hexdigest(),
        "signature": "",
        "payload": "source.json",
        "contentId": "online_example",
        "name": "示例书源",
        "description": "端到端测试内容包",
        "domains": ["example.com"],
        "aliases": ["online_example"],
        "status": "published",
        "channel": "stable",
        "dependencies": [],
    }
    manifest.update(manifest_overrides)
    (package_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
    history = package_dir / "history" / "1.1.0-1740000000000"
    history.mkdir(parents=True, exist_ok=True)
    old_payload = json.dumps({"bookSourceName": "旧版示例书源"}, ensure_ascii=False).encode("utf-8")
    (history / "source.json").write_bytes(old_payload)
    (history / "manifest.json").write_text(
        json.dumps({**manifest, "version": "1.1.0", "sha256": hashlib.sha256(old_payload).hexdigest()}, ensure_ascii=False),
        encoding="utf-8",
    )
    return manifest


def seed_credential(credential_id: str = "rea_1", owner: str = f"host:{HOST_ACCOUNT_ID}") -> dict:
    credential = {
        "id": credential_id,
        "owner": owner,
        "type": "reamicro",
        "label": "阅微账号",
        "accountId": HOST_ACCOUNT_ID,
        "secretEncrypted": crypto.encrypt_secret({"token": "token-value", "baseUrl": "https://example.invalid/"}),
        "createdAt": 1750000000000,
        "updatedAt": 1750000000000,
        "enabled": True,
        "health": "unverified",
    }
    state.save_credentials({**state.load_credentials(), credential_id: credential})
    return credential


def seed_task(task_id: str = "task_1", owner: str = f"host:{HOST_ACCOUNT_ID}", task_type: str = "yeshe_checkin", **overrides) -> dict:
    task = {
        "id": task_id,
        "owner": owner,
        "taskType": task_type,
        "credentialId": "rea_1",
        "requestEncrypted": crypto.encrypt_secret({"credentialId": "rea_1"}),
        "schedule": {"intervalSeconds": 86400, "timeOfDay": "00:05", "timezoneOffsetMinutes": 480},
        "status": "scheduled",
        "enabled": True,
        "createdAt": 1750000000000,
        "nextRunAt": 1750000000000,
        "runCount": 0,
    }
    task.update(overrides)
    state.save_tasks({**state.load_tasks(), task_id: task})
    return task


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def unwrap(response) -> dict:
    """取出统一响应包裹里的 data。"""
    body = response.json()
    return body.get("data") if isinstance(body, dict) and "data" in body else body
