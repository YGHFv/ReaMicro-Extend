import json
from datetime import datetime, timezone
from typing import Any

from app import runtime
from app.audit import audit_event
from app.config_store import bounded_config_int, load_config, save_config
from app.state import load_credentials, load_notifications, load_presence, load_tasks


USER_CAPABILITIES = (
    "content:upload",
    "tasks:use",
    "backup:use",
)

USER_CAPABILITY_LABELS = {
    "content:upload": "上传内容库",
    "tasks:use": "使用云端任务",
    "backup:use": "使用备份",
}


DEFAULT_CAPABILITIES = USER_CAPABILITIES


def users_path():
    return runtime.CONFIG_ROOT / "users.json"


def load_users() -> dict[str, dict[str, Any]]:
    path = users_path()
    if not path.is_file():
        return {}
    try:
        stored = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return stored if isinstance(stored, dict) else {}


def save_users(users: dict[str, dict[str, Any]]) -> None:
    path = users_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(users, ensure_ascii=False, indent=2), encoding="utf-8")
    temp.replace(path)


def normalize_account_id(value: Any) -> str:

    text = str(value or "").strip()
    if not text or len(text) > 64:
        return ""
    if not all(char.isalnum() or char in "_-" for char in text):
        return ""
    return text


def normalize_capabilities(values: Any) -> list[str]:
    if isinstance(values, str):
        values = values.replace(",", "\n").splitlines()
    if not isinstance(values, (list, tuple, set)):
        values = []
    return sorted({str(item).strip() for item in values if str(item).strip() in USER_CAPABILITIES})


def default_user(account_id: str, note: str = "") -> dict[str, Any]:
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    return {
        "accountId": account_id,
        "note": note,
        "enabled": True,
        "capabilities": list(DEFAULT_CAPABILITIES),
        "createdAt": now,
        "updatedAt": now,
        "firstSeenAt": 0,
        "lastSeenAt": 0,

        "uploadQuota": 0,
    }


def get_user(account_id: str) -> dict[str, Any] | None:
    return load_users().get(normalize_account_id(account_id)) or None


def upsert_user(account_id: str, **fields) -> dict[str, Any]:

    account_id = normalize_account_id(account_id)
    if not account_id:
        raise ValueError("阅微账号 ID 无效")
    users = load_users()
    user = users.get(account_id) or default_user(account_id)
    allowed = {"note", "enabled", "capabilities", "uploadQuota", "firstSeenAt", "lastSeenAt"}
    for key, value in fields.items():
        if key not in allowed:
            continue
        if key == "capabilities":
            user[key] = normalize_capabilities(value)
        elif key == "enabled":
            user[key] = bool(value)
        elif key == "uploadQuota":
            user[key] = bounded_config_int(value, 0, 0)
        elif key in {"firstSeenAt", "lastSeenAt"}:
            user[key] = bounded_config_int(value, 0, 0)
        else:
            user[key] = str(value)[:200]
    user["updatedAt"] = int(datetime.now(timezone.utc).timestamp() * 1000)
    users[account_id] = user
    save_users(users)
    return user


def delete_user(account_id: str) -> bool:
    account_id = normalize_account_id(account_id)
    users = load_users()
    if account_id not in users:
        return False
    users.pop(account_id)
    save_users(users)
    return True


def touch_user(account_id: str) -> dict[str, Any] | None:

    account_id = normalize_account_id(account_id)
    if not account_id:
        return None
    users = load_users()
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    user = users.get(account_id)
    if user is None:
        user = default_user(account_id)
        user["firstSeenAt"] = now
    user["lastSeenAt"] = now
    users[account_id] = user
    save_users(users)
    return user


def user_enabled(account_id: str) -> bool:

    user = get_user(account_id)
    return True if user is None else bool(user.get("enabled", True))


def user_has_capability(account_id: str, capability: str) -> bool:
    user = get_user(account_id)
    if user is None:
        return capability in DEFAULT_CAPABILITIES
    if not user.get("enabled", True):
        return False
    return capability in normalize_capabilities(user.get("capabilities", []))


def user_upload_quota(account_id: str) -> int:

    user = get_user(account_id)
    if user is None:
        return 0
    return bounded_config_int(user.get("uploadQuota", 0), 0, 0)


def count_user_uploads(account_id: str) -> int:

    from app.state import canonical_owner_of

    account_id = normalize_account_id(account_id)
    if not account_id:
        return 0
    owner = f"host:{account_id}"
    total = 0
    for kind in sorted(runtime.PACKAGE_KINDS):
        for path in (runtime.PACKAGE_ROOT / kind).glob("*/manifest.json"):
            try:
                manifest = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            if canonical_owner_of(str(manifest.get("uploadOwner", ""))) == owner:
                total += 1
    return total


def check_upload_quota(account_id: str) -> tuple[bool, str, dict[str, int]]:

    quota = user_upload_quota(account_id)
    used = count_user_uploads(account_id)
    usage = {"quota": quota, "used": used, "remaining": max(0, quota - used) if quota else 0}
    if quota <= 0:
        return True, "", usage
    if used >= quota:
        return False, f"已达到上传上限（{used}/{quota} 个内容包），请先删除不再需要的内容或联系管理员调整", usage
    return True, "", usage


def user_statistics(account_id: str) -> dict[str, Any]:

    account_id = normalize_account_id(account_id)
    owner = f"host:{account_id}"
    from app.state import canonical_owner_of

    def owned(items):
        return [item for item in items if isinstance(item, dict) and canonical_owner_of(str(item.get("owner", ""))) == owner]

    tasks = owned(load_tasks().values())
    presence = owned(load_presence().values())
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    uploads = 0
    for kind in sorted(runtime.PACKAGE_KINDS):
        for path in (runtime.PACKAGE_ROOT / kind).glob("*/manifest.json"):
            try:
                manifest = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            if canonical_owner_of(str(manifest.get("uploadOwner", ""))) == owner:
                uploads += 1
    return {
        "accountId": account_id,
        "credentials": len(owned(load_credentials().values())),
        "tasks": len(tasks),
        "failedTasks": sum(1 for task in tasks if str(task.get("status")) == "failed"),
        "pendingNotifications": sum(
            1 for item in owned(load_notifications().values()) if not item.get("deliveredAt")
        ),
        "uploads": uploads,
        "online": any(bounded_config_int(item.get("onlineUntil", 0), 0, 0) > now for item in presence),
        "lastSeenAt": max((bounded_config_int(item.get("lastSeenAt", 0), 0, 0) for item in presence), default=0),
        "moduleVersion": next((str(item.get("moduleVersion", "")) for item in presence if item.get("moduleVersion")), ""),
    }


def known_account_ids() -> list[str]:

    config = load_config()
    found = set(load_users())
    for key in ("hostAccountAllowlist", "moduleUploadAllowlist"):
        found.update(normalize_account_id(item) for item in config.get(key, []))
    from app.state import owner_host_account_id

    for source in (load_credentials().values(), load_tasks().values(), load_presence().values()):
        for item in source:
            if isinstance(item, dict):
                found.add(normalize_account_id(owner_host_account_id(str(item.get("owner", "")))))
    return sorted(item for item in found if item)


def sync_users_from_activity() -> int:

    users = load_users()
    added = 0
    for account_id in known_account_ids():
        if account_id in users:
            continue
        users[account_id] = default_user(account_id)
        added += 1
    if added:
        save_users(users)
        audit_event("users_synced", metadata={"added": added})
    return added


def set_allowlist_membership(account_id: str, allow_access: bool, allow_upload: bool) -> dict[str, Any]:

    account_id = normalize_account_id(account_id)
    if not account_id:
        raise ValueError("阅微账号 ID 无效")
    config = load_config()
    for key, wanted in (("hostAccountAllowlist", allow_access), ("moduleUploadAllowlist", allow_upload)):
        current = {normalize_account_id(item) for item in config.get(key, [])}
        current.discard("")
        if wanted:
            current.add(account_id)
        else:
            current.discard(account_id)
        config[key] = sorted(current)
    return save_config(config)


def allowlist_membership(config: dict[str, Any], account_id: str) -> dict[str, bool]:
    account_id = normalize_account_id(account_id)
    return {
        "access": account_id in {normalize_account_id(i) for i in config.get("hostAccountAllowlist", [])},
        "upload": account_id in {normalize_account_id(i) for i in config.get("moduleUploadAllowlist", [])},
    }
