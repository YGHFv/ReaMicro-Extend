"""任务、消息、在线状态与同步密钥的持久化，以及归属标识的历史迁移。

**归属（owner）约定**：只要请求带阅微账号 ID，归属就固定是 `host:<id>`，与认证模式无关。
旧版本按认证模式拼归属（`host-public:3`、`key:<hash>:host:3`），管理员改一次认证模式就会
让同一台设备换身份，导致密钥、任务、消息、备份全部失联。`canonicalize_owner_identities`
在启动时把历史写法折叠回规范形式。
"""
import hashlib
import json
import secrets
import shutil
from datetime import datetime, timezone
from typing import Any

from app import runtime
from app.audit import audit_event
from app.config_store import bounded_config_int
from app.crypto import decrypt_secret, encrypt_secret


def load_tasks() -> dict[str, dict[str, Any]]:
    return runtime.get_state_store().load_namespace("tasks")


def save_tasks(tasks: dict[str, dict[str, Any]]) -> None:
    runtime.get_state_store().save_namespace("tasks", tasks)


def load_notifications() -> dict[str, dict[str, Any]]:
    return runtime.get_state_store().load_namespace("notifications")


def save_notifications(items: dict[str, dict[str, Any]]) -> None:
    runtime.get_state_store().save_namespace("notifications", items)


def load_presence() -> dict[str, dict[str, Any]]:
    return runtime.get_state_store().load_namespace("presence")


def save_presence(items: dict[str, dict[str, Any]]) -> None:
    runtime.get_state_store().save_namespace("presence", items)


def load_credentials() -> dict[str, dict[str, Any]]:
    return runtime.get_state_store().load_namespace("credentials")


def save_credentials(credentials: dict[str, dict[str, Any]]) -> None:
    runtime.get_state_store().save_namespace("credentials", credentials)


def owner_host_account_id(owner: str) -> str:
    marker = ":host:"
    if marker in owner:
        return owner.rsplit(marker, 1)[1]
    if owner.startswith("host:"):
        return owner.split(":", 1)[1]
    if owner.startswith("host-public:"):
        return owner.split(":", 1)[1]
    return ""


def canonical_owner_of(owner: str) -> str:
    """把任意历史归属写法折叠成当前的规范写法。"""
    account_id = owner_host_account_id(str(owner or ""))
    return f"host:{account_id}" if account_id else str(owner or "")


def legacy_owner_identities(canonical: str) -> list[str]:
    """列出某个归属标识在历史版本里可能出现过的旧写法，供启动迁移归并。"""
    account_id = owner_host_account_id(canonical)
    if not account_id:
        return []
    return [f"host-public:{account_id}", f"host:{account_id}"]


def canonicalize_owner_identities() -> dict[str, int]:
    """把历史遗留的归属写法统一成 `host:<阅微账号>`，并归并因此产生的重复记录。

    旧版本按认证模式拼归属（`host-public:3`、`key:<hash>:host:3`、`account:name:host:3`），
    管理员一改认证模式，同一台设备就换了身份：密钥、任务、消息、在线记录和备份目录全部分裂。
    这里在启动时做一次性折叠，保证升级后旧数据还能被模块看到。
    """
    stats = {"credentials": 0, "tasks": 0, "notifications": 0, "presence": 0, "backups": 0}

    credentials = load_credentials()
    for item in credentials.values():
        if isinstance(item, dict):
            canonical = canonical_owner_of(str(item.get("owner", "")))
            if canonical and canonical != item.get("owner"):
                item["owner"] = canonical
                stats["credentials"] += 1
    if stats["credentials"]:
        save_credentials(credentials)

    tasks = load_tasks()
    for task in tasks.values():
        canonical = canonical_owner_of(str(task.get("owner", "")))
        if canonical and canonical != task.get("owner"):
            task["owner"] = canonical
            stats["tasks"] += 1
    if stats["tasks"]:
        save_tasks(tasks)

    notifications = load_notifications()
    for item in notifications.values():
        canonical = canonical_owner_of(str(item.get("owner", "")))
        if canonical and canonical != item.get("owner"):
            item["owner"] = canonical
            stats["notifications"] += 1
    if stats["notifications"]:
        save_notifications(notifications)

    # 在线记录以归属为键，折叠后同一账号的多行要合并成最近一次心跳。
    presence = load_presence()
    merged: dict[str, dict[str, Any]] = {}
    for key, item in presence.items():
        if not isinstance(item, dict):
            continue
        canonical = canonical_owner_of(str(item.get("owner", key)))
        if not canonical:
            continue
        item["owner"] = canonical
        existing = merged.get(canonical)
        if existing is None:
            merged[canonical] = item
            continue
        stats["presence"] += 1
        if bounded_config_int(item.get("lastSeenAt", 0), 0, 0) >= bounded_config_int(existing.get("lastSeenAt", 0), 0, 0):
            merged[canonical] = item
    if stats["presence"] or set(merged) != set(presence):
        save_presence(merged)

    stats["backups"] = migrate_legacy_backup_dirs()
    if any(stats.values()):
        audit_event("owner_identity_canonicalized", metadata=stats)
    return stats


def migrate_legacy_backup_dirs() -> int:
    """把旧归属写法命名的备份目录迁到规范归属目录下。"""
    moved = 0
    accounts = {
        owner_host_account_id(str(item.get("owner", "")))
        for source in (load_credentials().values(), load_tasks().values(), load_presence().values())
        for item in source
        if isinstance(item, dict)
    }
    for account_id in {value for value in accounts if value}:
        canonical_dir_name = backup_owner_dir_name(f"host:{account_id}")
        for legacy in legacy_owner_identities(f"host:{account_id}"):
            legacy_dir_name = backup_owner_dir_name(legacy)
            if legacy_dir_name == canonical_dir_name:
                continue
            for root in (runtime.BACKUP_ROOT, runtime.SECRET_BACKUP_ROOT):
                legacy_dir = root / legacy_dir_name
                target_dir = root / canonical_dir_name
                if not legacy_dir.is_dir():
                    continue
                if target_dir.exists():
                    # 规范目录已有数据时不覆盖，仅补齐缺失文件。
                    for path in legacy_dir.rglob("*"):
                        if not path.is_file():
                            continue
                        destination = target_dir / path.relative_to(legacy_dir)
                        if destination.exists():
                            continue
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(path, destination)
                        moved += 1
                else:
                    target_dir.parent.mkdir(parents=True, exist_ok=True)
                    shutil.move(str(legacy_dir), str(target_dir))
                    moved += 1
    return moved


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


def enqueue_task_notification(task: dict[str, Any], result: str, message: str, finished_at: int) -> str:
    notification_id = "msg_" + secrets.token_hex(10)
    items = load_notifications()
    items[notification_id] = {
        "id": notification_id,
        "owner": str(task.get("owner", "")),
        "taskId": str(task.get("id", "")),
        "taskType": str(task.get("taskType", "")),
        "result": result,
        "title": _task_title_label(task.get("taskType", "")) + ("执行完成" if result == "success" else "执行异常"),
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


def backup_owner_dir_name(identity: str) -> str:
    return hashlib.sha256(identity.encode("utf-8")).hexdigest()[:32]


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


def _task_title_label(task_type: Any) -> str:
    """任务类型的中文名。延迟导入 labels 以打破 state ↔ labels 的循环依赖。"""
    from app.labels import _admin_task_label

    return _admin_task_label(task_type)
