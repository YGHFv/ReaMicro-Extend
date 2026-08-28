"""按阅微账号 ID 区分的用户档案。

服务器此前只有一个扁平的 `hostAccountAllowlist`（一串 ID）和 `moduleUploadAllowlist`，
看不出某个用户传了什么、有几个任务、什么时候来过，也没法单独停用一个人或限额。
这里把用户提升为一等实体：阅微 ID 是主键，档案与配置分开存放，便于后续加字段。

**归属对齐**：用户 ID 与 `state.canonical_owner_of` 的 `host:<id>` 一一对应，
统计与配额都按这个 ID 归集，认证模式变更不影响。
"""
import json
from datetime import datetime, timezone
from typing import Any

from app import runtime
from app.audit import audit_event
from app.config_store import bounded_config_int, load_config, save_config
from app.state import load_credentials, load_notifications, load_presence, load_tasks

# 用户级权限。与 API Key 的 scope 分开：这是"这个人能做什么"，
# 而 scope 是"这把钥匙能做什么"。
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

# 新建档案默认拥有全部功能。白名单才是"能不能用"的授权来源，
# 用户档案的作用是**收回**权限——所以默认不能比白名单更严，否则一加档案就把人锁在外面。
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
    """阅微账号 ID 只允许数字与有限符号，且要参与路径无关的键名。"""
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
        # 0 表示不限；单位为个数。
        "uploadQuota": 0,
    }


def get_user(account_id: str) -> dict[str, Any] | None:
    return load_users().get(normalize_account_id(account_id)) or None


def upsert_user(account_id: str, **fields) -> dict[str, Any]:
    """新建或更新用户档案。未知字段会被忽略，避免后台表单写入任意键。"""
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
    """记录一次活跃。首次出现时自动建档，省去管理员手工录入。"""
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
    """未建档的用户视为允许：访问控制仍由认证模式的白名单负责，档案只做管理。"""
    user = get_user(account_id)
    return True if user is None else bool(user.get("enabled", True))


def user_has_capability(account_id: str, capability: str) -> bool:
    user = get_user(account_id)
    if user is None:
        return capability in DEFAULT_CAPABILITIES
    if not user.get("enabled", True):
        return False
    return capability in normalize_capabilities(user.get("capabilities", []))


def user_statistics(account_id: str) -> dict[str, Any]:
    """归集某个用户的使用情况，供后台用户页展示。"""
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
    """已建档 + 从白名单和实际数据里发现的全部阅微 ID。"""
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
    """把白名单与实际数据里出现过、但尚未建档的阅微 ID 补建档案。"""
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
    """同步用户在两份白名单里的成员资格，避免后台两处分别维护后不一致。"""
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
