"""认证、授权、会话与限流。

**归属（owner）与认证模式解耦**是这里最重要的约定：只要请求带阅微账号 ID，
归属就固定 `host:<id>`，与用哪种模式通过校验无关。认证模式只决定"能不能访问"，
不参与"数据属于谁"——否则管理员改一次认证模式，同一台设备就换了身份，
已上传的密钥、已建的任务、积压的消息和备份目录全部失联。
"""
import base64
import binascii
import hashlib
import hmac
import re
import secrets
import time
from datetime import datetime, timezone
from typing import Any

from fastapi import Depends, Header, HTTPException, Request, status
from fastapi.security import HTTPBasicCredentials

from app import runtime
from app.audit import audit_actor, audit_event
from app.config_store import (
    active_auth_mode,
    api_key_auth_configured,
    load_config,
    module_upload_kinds,
    save_config,
)
from app.crypto import api_key_digest, generate_long_secret, password_hash, password_matches, validate_admin_password
from app.responses import response
from app.state import backup_owner_dir_name, owner_host_account_id

# 可分配给子管理员的权限键。这里是事实来源，后台勾选框的中文说明在 admin/views
# 里按这个顺序配文案——反过来会造成 security → admin.views 的反向依赖。
ADMIN_ASSIGNABLE_PERMISSIONS = (
    "settings:write",
    "packages:write",
    "tasks:write",
    "module:sync",
    "audit:read",
    "backup:admin",
    "security:write",
)

# API Key 可授予的权限。required_api_scope 用到的每个 scope 都必须在此出现，
# 否则那条路由对任何 API Key 调用方都会无条件 403。
API_KEY_PERMISSIONS = (
    "read", "write", "packages:read", "packages:write", "tasks:read", "tasks:write",
    "credentials:read", "credentials:write", "backup:read", "backup:write",
)


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
        return set(API_KEY_PERMISSIONS)
    result = {str(item).strip() for item in permissions if str(item).strip()}
    if "read" in result:
        result.update({"packages:read", "tasks:read", "credentials:read", "backup:read"})
    if "write" in result:
        # backup:write 必须包含在内。漏掉它会让所有带 API Key 的备份上传请求
        # 无条件 403——required_api_scope 要求这个 scope，但它此前无处可授予。
        result.update({"packages:write", "tasks:write", "credentials:write", "backup:write"})
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


def normalized_admin_name(value: str) -> str:
    name = value.strip()
    if not 3 <= len(name) <= 64 or not re.fullmatch(r"[A-Za-z0-9_.-]+", name):
        raise ValueError("管理员用户名需为 3-64 位字母、数字、点、下划线或短横线")
    return name


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
        if not runtime.ADMIN_PASSWORD:
            raise HTTPException(status_code=503, detail="未配置主管理员初始密码 REAMICRO_ADMIN_PASSWORD")
        if credentials and hmac.compare_digest(credentials.username, runtime.ADMIN_USERNAME) and hmac.compare_digest(credentials.password, runtime.ADMIN_PASSWORD):
            if allow_bootstrap:
                return {"username": runtime.ADMIN_USERNAME, "role": "primary", "needsSetup": True}
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
        material = runtime.ADMIN_PASSWORD
    elif actor.get("role") == "primary":
        material = str(config.get("primaryAdmin", {}).get("passwordHash", ""))
    else:
        material = str(config.get("adminAccounts", {}).get(username, {}).get("passwordHash", ""))
    return hashlib.sha256(material.encode("utf-8")).hexdigest()[:24]


def create_admin_session(actor: dict[str, Any]) -> str:
    token = generate_long_secret(48)
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    runtime.get_state_store().save_admin_session(
        api_key_digest(token),
        actor,
        admin_auth_version(load_config(), actor),
        now,
        now + runtime.ADMIN_SESSION_SECONDS * 1000,
    )
    return token


def session_admin(request: Request) -> dict[str, Any] | None:
    token = request.cookies.get(runtime.ADMIN_SESSION_COOKIE, "")
    if not token:
        return None
    return validate_admin_session_token(token)


def validate_admin_session_token(token: str) -> dict[str, Any] | None:
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    actor = runtime.get_state_store().load_admin_session(api_key_digest(token), now)
    if not actor:
        return None
    config = load_config()
    if actor.get("role") == "subadmin":
        record = config.get("adminAccounts", {}).get(actor.get("username", ""), {})
        if not isinstance(record, dict) or not record.get("enabled", True):
            runtime.get_state_store().delete_admin_session(api_key_digest(token))
            return None
        actor["permissions"] = record.get("permissions", [])
    if not hmac.compare_digest(str(actor.get("authVersion", "")), admin_auth_version(config, actor)):
        runtime.get_state_store().delete_admin_session(api_key_digest(token))
        return None
    return actor


async def basic_security(request: Request) -> HTTPBasicCredentials | None:
    """会话优先，兼容旧的 HTTP Basic 登录。"""
    token = request.cookies.get(runtime.ADMIN_SESSION_COOKIE, "")
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
    material = (str(config.get("secretKey", "")) or runtime.SECRET_KEY or runtime.ADMIN_PASSWORD or encoded).encode("utf-8")
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
    authorized = False
    key_record = configured_api_key(config, api_key_value) if mode == "api_key" else None
    if key_record:
        if key_record.get("id") != "legacy":
            save_config(config)
        authorized = True
    elif mode == "account" and account_name and account_password:
        accounts = config.get("accounts", {})
        encoded = accounts.get(account_name) if isinstance(accounts, dict) else None
        authorized = bool(encoded) and password_matches(account_password, str(encoded))
    elif mode == "host_account_allowlist" and host_account_id:
        authorized = host_account_id in set(config.get("hostAccountAllowlist", []))
    elif mode == "public":
        authorized = True
    if not authorized:
        raise HTTPException(status_code=401, detail=response(code="AUTH_REQUIRED", message="认证信息无效"))
    # 停用的用户要在**所有**接口被拒，不只是任务和备份那几个。
    # 此前这里有四条独立的 return，各自绕过了用户闸门。
    account_id = str(host_account_id or "").strip()
    if account_id and not _user_enabled(account_id):
        raise HTTPException(
            status_code=403,
            detail=response(code="USER_DISABLED", message=f"阅微账号 {account_id} 已被管理员停用"),
        )


def resolve_identity(api_key_value: str | None, account_name: str | None, account_password: str | None, host_account_id: str | None) -> str:
    """把一次请求解析成稳定的数据归属标识。

    关键约定：只要请求带了阅微账号 ID，归属就固定是 `host:<id>`，与用哪种认证模式通过校验无关。
    认证模式只负责"能不能访问"，不参与"数据属于谁"——否则管理员在后台把认证模式从公开
    改成阅微白名单之后，同一台设备的归属会从 `host-public:3` 变成 `host:3`，
    已上传的同步密钥、已建立的任务和积压的消息全部失联，后台还会出现同一个账号两行在线记录。
    """
    config = load_config()
    mode = active_auth_mode(config)
    account_id = str(host_account_id or "").strip()
    authorized = False
    fallback = ""
    if mode == "api_key" and configured_api_key(config, api_key_value):
        authorized = True
        fallback = "key:" + hashlib.sha256(str(api_key_value).encode()).hexdigest()[:24]
    elif mode == "account" and account_name and account_password and password_matches(
        account_password,
        str(config.get("accounts", {}).get(account_name, "")),
    ):
        authorized = True
        fallback = "account:" + account_name
    elif mode == "host_account_allowlist" and account_id and account_id in set(config.get("hostAccountAllowlist", [])):
        authorized = True
    elif mode == "public":
        authorized = True
        fallback = "public"
    if not authorized:
        raise HTTPException(status_code=401, detail=response(code="AUTH_REQUIRED", message="认证信息无效"))
    if account_id and not _user_enabled(account_id):
        raise HTTPException(
            status_code=403,
            detail=response(code="USER_DISABLED", message=f"阅微账号 {account_id} 已被管理员停用"),
        )
    return ("host:" + account_id) if account_id else fallback


def _user_enabled(account_id: str) -> bool:
    """延迟导入 users：users 需要读 config 与 state，而本模块位于它们之上。"""
    from app.users import user_enabled

    return user_enabled(account_id)


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
    # 复用 resolve_identity 的规范归属，备份目录才不会在认证模式变更后失联。
    identity = resolve_identity(x_reamicro_api_key, x_reamicro_account, x_reamicro_password, x_reamicro_host_account_id)
    if identity == "public":
        raise HTTPException(status_code=401, detail=response(code="AUTH_REQUIRED", message="备份功能需要非公开认证"))
    return backup_owner_dir_name(identity)


async def module_upload_owner(
    request: Request,
    x_reamicro_api_key: str | None = Header(default=None),
    x_reamicro_account: str | None = Header(default=None),
    x_reamicro_password: str | None = Header(default=None),
    x_reamicro_host_account_id: str | None = Header(default=None),
) -> str:
    """模块上传的所有者标识。先走常规认证，再校验上传白名单。"""
    identity = resolve_identity(x_reamicro_api_key, x_reamicro_account, x_reamicro_password, x_reamicro_host_account_id)
    enforce_api_scope(request, x_reamicro_api_key)
    policy = module_upload_policy(load_config(), x_reamicro_host_account_id)
    if not policy["allowed"]:
        audit_event(
            "module_upload_denied",
            actor=identity,
            request_id=getattr(request.state, "request_id", ""),
            success=False,
            metadata={"hostAccountId": policy["hostAccountId"]},
        )
        raise HTTPException(status_code=403, detail=response(code="MODULE_UPLOAD_FORBIDDEN", message=policy["reason"], data=policy))
    return identity


def module_upload_policy(config: dict[str, Any], host_account_id: str | None) -> dict[str, Any]:
    """模块上传许可：需要后台启用上传功能，且当前阅微账号 ID 在上传白名单内。"""
    enabled = bool(config.get("moduleUploadEnabled", False))
    account_id = str(host_account_id or "").strip()
    allowlist = set(config.get("moduleUploadAllowlist", []))
    allowed = enabled and bool(account_id) and account_id in allowlist
    if allowed and not _user_can_upload(account_id):
        allowed = False
    if not enabled:
        reason = "服务器未启用用户模块上传功能"
    elif not account_id:
        reason = "请求未携带阅微账号 ID，请先在阅微登录账号"
    elif account_id in allowlist and not _user_can_upload(account_id):
        reason = f"阅微账号 {account_id} 的上传权限已被管理员关闭"
    elif not allowed:
        reason = f"阅微账号 {account_id} 不在上传白名单"
    else:
        reason = ""
    return {
        "enabled": enabled,
        "allowed": allowed,
        "reason": reason,
        "hostAccountId": account_id,
        "kinds": module_upload_kinds(config.get("moduleUploadKinds")),
        "maxSize": runtime.MODULE_UPLOAD_MAX_BYTES,
    }


def allow_rate_limit(key: str) -> bool:
    now = time.monotonic()
    with runtime.rate_lock:
        values = [item for item in runtime.rate_buckets.get(key, []) if now - item < runtime.RATE_WINDOW_SECONDS]
        if len(values) >= runtime.RATE_LIMIT:
            runtime.rate_buckets[key] = values
            return False
        values.append(now)
        runtime.rate_buckets[key] = values
        if len(runtime.rate_buckets) > 10_000:
            oldest = sorted(runtime.rate_buckets, key=lambda item: runtime.rate_buckets[item][-1] if runtime.rate_buckets[item] else now)[:1000]
            for item in oldest:
                runtime.rate_buckets.pop(item, None)
        return True


def create_api_key_record(config: dict[str, Any], name: str, raw_permissions: Any) -> tuple[dict[str, Any], str]:
    """生成一条 API Key 记录并返回明文密钥；明文只在创建时返回一次。"""
    if isinstance(raw_permissions, str):
        raw_permissions = [item for item in raw_permissions.replace(",", "\n").splitlines()]
    if not isinstance(raw_permissions, (list, tuple)):
        raise HTTPException(status_code=400, detail=response(code="API_SCOPE_INVALID", message="permissions 必须是数组"))
    permissions = sorted({str(item).strip() for item in raw_permissions if str(item).strip()})
    if not set(permissions).issubset(set(API_KEY_PERMISSIONS)):
        raise HTTPException(status_code=400, detail=response(code="API_SCOPE_INVALID", message="存在不支持的 API Key 权限范围"))
    plain = generate_long_secret(48)
    record = {
        "id": "key_" + secrets.token_hex(8),
        "name": str(name or "API Key")[:80],
        "digest": api_key_digest(plain),
        "permissions": permissions or ["read"],
        "enabled": True,
        "createdAt": int(datetime.now(timezone.utc).timestamp() * 1000),
        "lastUsedAt": 0,
    }
    config["apiKeyRecords"] = [item for item in config.get("apiKeyRecords", []) if isinstance(item, dict)] + [record]
    return record, plain


def revoke_api_key_record(config: dict[str, Any], key_id: str) -> None:
    """把指定 API Key 标记为已吊销；记录保留以便审计。"""
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


def normalized_admin_permissions(values: Any) -> list[str]:
    """把复选框的多值或逗号分隔字符串统一成受支持的权限列表。"""
    if isinstance(values, str):
        values = values.replace(",", "\n").splitlines()
    if not isinstance(values, (list, tuple, set)):
        values = []
    allowed = set(ADMIN_ASSIGNABLE_PERMISSIONS)
    return sorted({str(item).strip() for item in values if str(item).strip() in allowed})



def _user_can_upload(account_id: str) -> bool:
    """延迟导入同上。用户档案里关掉上传能力时，即便在白名单里也不放行。"""
    from app.users import user_has_capability

    return user_has_capability(account_id, "content:upload")
