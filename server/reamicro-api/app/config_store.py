"""服务器配置的读写、认证模式推断与对外能力声明。

配置落在 `server.json`，缺失字段用环境变量默认值补齐。认证模式历史上是多选，
现已收敛为单选，`infer_auth_mode` 负责从旧配置迁移。
本模块只做配置的读取与推断，不涉及密码校验和会话——那些在 app.security。
"""
import json
import os
from typing import Any

from app import runtime


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


def default_config() -> dict[str, Any]:
    return {
        "serverId": os.getenv("REAMICRO_SERVER_ID", "reamicro-api"),
        "apiKey": os.getenv("REAMICRO_API_KEY", ""),
        "apiKeyRecords": [],
        "secretKey": os.getenv("REAMICRO_SECRET_KEY", ""),
        "allowPublic": runtime.env_bool("REAMICRO_ALLOW_PUBLIC", True),
        "authMode": os.getenv("REAMICRO_AUTH_MODE", ""),
        "features": sorted(runtime.DEFAULT_FEATURES),
        "minModuleVersion": os.getenv("REAMICRO_MIN_MODULE_VERSION", "2.0.0"),
        "signingPublicKey": os.getenv("REAMICRO_SIGNING_PUBLIC_KEY", ""),
        "githubRepository": os.getenv("REAMICRO_GITHUB_REPOSITORY", "YGHFv/ReaMicro-Extend"),
        "githubToken": os.getenv("REAMICRO_GITHUB_TOKEN", ""),
        "githubIncludePrerelease": runtime.env_bool("REAMICRO_GITHUB_INCLUDE_PRERELEASE", False),
        "releaseSyncSeconds": bounded_config_int(os.getenv("REAMICRO_RELEASE_SYNC_SECONDS", "1800"), 1800, 300),
        "releaseVersionCode": bounded_config_int(os.getenv("REAMICRO_RELEASE_VERSION_CODE", "0"), 0, 0),
        "serverSnapshotEnabled": runtime.env_bool("REAMICRO_SERVER_SNAPSHOT_ENABLED", True),
        "serverSnapshotSeconds": bounded_config_int(os.getenv("REAMICRO_SERVER_SNAPSHOT_SECONDS", "86400"), 86400, 3600),
        "serverSnapshotRetention": bounded_config_int(os.getenv("REAMICRO_SERVER_SNAPSHOT_RETENTION", "30"), 30, 3),
        "accounts": {},
        "primaryAdmin": {},
        "adminAccounts": {},
        "hostAccountAllowlist": [],
        "moduleUploadEnabled": runtime.env_bool("REAMICRO_MODULE_UPLOAD_ENABLED", False),
        "moduleUploadAllowlist": [],
        "moduleUploadKinds": sorted(runtime.MODULE_UPLOAD_DEFAULT_KINDS),
    }


def load_config() -> dict[str, Any]:
    with runtime.config_lock:
        config = default_config()
        auth_mode_explicit = False
        try:
            if runtime.CONFIG_PATH.is_file():
                stored = json.loads(runtime.CONFIG_PATH.read_text(encoding="utf-8"))
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
        config["moduleUploadEnabled"] = bool(config.get("moduleUploadEnabled", False))
        config["moduleUploadAllowlist"] = normalized_config_list(config.get("moduleUploadAllowlist", []))
        config["moduleUploadKinds"] = module_upload_kinds(config.get("moduleUploadKinds"))
        if config.get("authMode") not in {"public", "api_key", "account", "host_account_allowlist"}:
            config["authMode"] = infer_auth_mode(config)
        config["_authModeExplicit"] = auth_mode_explicit
        return config


def save_config(next_config: dict[str, Any]) -> dict[str, Any]:
    with runtime.config_lock:
        runtime.CONFIG_ROOT.mkdir(parents=True, exist_ok=True)
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
        safe["moduleUploadEnabled"] = bool(safe.get("moduleUploadEnabled", False))
        safe["moduleUploadAllowlist"] = normalized_config_list(safe.get("moduleUploadAllowlist", []))
        safe["moduleUploadKinds"] = module_upload_kinds(safe.get("moduleUploadKinds"))
        if safe.get("authMode") not in {"public", "api_key", "account", "host_account_allowlist"}:
            safe["authMode"] = infer_auth_mode(safe)
        safe.pop("_authModeExplicit", None)
        temp = runtime.CONFIG_PATH.with_suffix(".tmp")
        temp.write_text(json.dumps(safe, ensure_ascii=False, indent=2), encoding="utf-8")
        temp.replace(runtime.CONFIG_PATH)
        return safe


def api_key_auth_configured(config: dict[str, Any]) -> bool:
    if str(config.get("apiKey", "")):
        return True
    return any(
        isinstance(record, dict) and record.get("enabled", True) and str(record.get("digest", ""))
        for record in config.get("apiKeyRecords", [])
    )


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


def configured_auth_modes(config: dict[str, Any]) -> list[str]:
    return [active_auth_mode(config)]


def module_upload_kinds(value: Any) -> list[str]:
    """模块可上传的内容类型。只保留白名单内的类型，配置为空时回退到默认集合。"""
    values = {item for item in normalized_config_list(value) if item in runtime.MODULE_UPLOAD_DEFAULT_KINDS}
    return sorted(values or runtime.MODULE_UPLOAD_DEFAULT_KINDS)


# 后台表单字段与 module_upload_kinds 同名，另取别名避免函数被参数遮蔽。
module_upload_kinds_form = module_upload_kinds


def public_server_capabilities(config: dict[str, Any]) -> dict[str, Any]:
    modes = configured_auth_modes(config)
    return {
        "apiVersion": runtime.API_VERSION,
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
        "moduleUploadEnabled": bool(config.get("moduleUploadEnabled", False)),
        "moduleUploadKinds": module_upload_kinds(config.get("moduleUploadKinds")),
        "moduleUploadMaxSize": runtime.MODULE_UPLOAD_MAX_BYTES,
    }
