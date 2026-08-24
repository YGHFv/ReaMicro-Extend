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
from email.utils import parsedate_to_datetime
from pathlib import Path
from datetime import datetime, timezone
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
ADMIN_USERNAME = os.getenv("REAMICRO_ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = os.getenv("REAMICRO_ADMIN_PASSWORD", "")
SIGNING_PRIVATE_KEY_FILE = os.getenv("REAMICRO_SIGNING_PRIVATE_KEY_FILE", "")
config_lock = threading.RLock()
basic_security = HTTPBasic(auto_error=False)


def env_bool(name: str, default: bool) -> bool:
    return os.getenv(name, str(default)).lower() == "true"


def default_config() -> dict[str, Any]:
    return {
        "serverId": os.getenv("REAMICRO_SERVER_ID", "reamicro-api"),
        "apiKey": os.getenv("REAMICRO_API_KEY", ""),
        "allowPublic": env_bool("REAMICRO_ALLOW_PUBLIC", True),
        "features": sorted(DEFAULT_FEATURES),
        "minModuleVersion": os.getenv("REAMICRO_MIN_MODULE_VERSION", "2.0.0"),
        "signingPublicKey": os.getenv("REAMICRO_SIGNING_PUBLIC_KEY", ""),
        "githubRepository": os.getenv("REAMICRO_GITHUB_REPOSITORY", "YGHFv/ReaMicro-Extend"),
        "githubToken": os.getenv("REAMICRO_GITHUB_TOKEN", ""),
        "githubIncludePrerelease": env_bool("REAMICRO_GITHUB_INCLUDE_PRERELEASE", False),
        "releaseSyncSeconds": max(int(os.getenv("REAMICRO_RELEASE_SYNC_SECONDS", "1800")), 300),
        "releaseVersionCode": int(os.getenv("REAMICRO_RELEASE_VERSION_CODE", "0")),
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
        temp = CONFIG_PATH.with_suffix(".tmp")
        temp.write_text(json.dumps(safe, ensure_ascii=False, indent=2), encoding="utf-8")
        temp.replace(CONFIG_PATH)
        return safe

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


def response(data: Any = None, code: str = "OK", message: str = "") -> dict[str, Any]:
    return {
        "code": code,
        "message": message,
        "data": data,
        "requestId": "req_" + secrets.token_hex(8),
        "serverTime": datetime.now(timezone.utc).isoformat(),
    }


def check_api_key(value: str | None) -> None:
    config = load_config()
    api_key = str(config.get("apiKey", ""))
    if api_key:
        if not value or not hmac.compare_digest(value, api_key):
            raise HTTPException(status_code=401, detail=response(code="AUTH_REQUIRED", message="API Key 无效"))
    elif not config.get("allowPublic", True):
        raise HTTPException(status_code=401, detail=response(code="AUTH_REQUIRED", message="服务器未开启公开访问"))


async def authenticated(x_reamicro_api_key: str | None = Header(default=None)) -> None:
    check_api_key(x_reamicro_api_key)


@app.exception_handler(HTTPException)
async def http_exception_handler(_: Request, exc: HTTPException) -> JSONResponse:
    detail = exc.detail if isinstance(exc.detail, dict) else response(code="HTTP_ERROR", message=str(exc.detail))
    return JSONResponse(status_code=exc.status_code, content=detail, headers=exc.headers)


@app.get("/v1/health")
async def health(_: None = Depends(authenticated)) -> dict[str, Any]:
    return response({"status": "ok", "serverId": load_config()["serverId"]})


def require_admin(credentials: HTTPBasicCredentials | None) -> None:
    if not ADMIN_PASSWORD:
        raise HTTPException(status_code=503, detail="未配置 REAMICRO_ADMIN_PASSWORD")
    if not credentials or not (
        hmac.compare_digest(credentials.username, ADMIN_USERNAME)
        and hmac.compare_digest(credentials.password, ADMIN_PASSWORD)
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="需要后台管理员认证",
            headers={"WWW-Authenticate": "Basic"},
        )


def admin_page(config: dict[str, Any], message: str = "") -> str:
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
    return f"""<!doctype html>
<html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>ReaMicro API 管理后台</title>
<style>body{{font-family:system-ui,-apple-system,sans-serif;max-width:860px;margin:32px auto;padding:0 18px;background:#f5f7fb;color:#182230}}main{{background:white;border-radius:14px;padding:24px;box-shadow:0 5px 24px #0001}}label{{display:block;margin:14px 0 5px;font-weight:600}}input,textarea{{box-sizing:border-box;width:100%;padding:10px;border:1px solid #ccd4df;border-radius:8px;font:inherit}}textarea{{min-height:72px}}.row{{display:grid;grid-template-columns:1fr 1fr;gap:16px}}button{{margin-top:20px;padding:10px 16px;border:0;border-radius:8px;background:#2563eb;color:#fff;font-weight:600;cursor:pointer}}.secondary{{background:#475569;margin-left:8px}}.msg{{padding:10px;background:#ecfdf5;color:#166534;border-radius:8px}}small{{color:#64748b}}table{{width:100%;border-collapse:collapse;margin-top:12px}}th,td{{padding:8px;border-bottom:1px solid #e2e8f0;text-align:left}}</style></head>
<body><main><h1>ReaMicro API 管理后台</h1><p><small>默认端口：5222 · 配置保存到 Docker 数据卷 /data/config/server.json</small></p>
{f"<p class='msg'>{esc(message)}</p>" if message else ""}
<form method='post' action='/admin/settings'>
<div class='row'><div><label>服务器 ID</label><input name='server_id' value='{esc(config.get("serverId", ""))}'></div><div><label>最低模块版本</label><input name='min_module_version' value='{esc(config.get("minModuleVersion", ""))}'></div></div>
<label>API Key（当前：{'已设置' if config.get("apiKey") else '未设置'}）</label><input type='password' name='api_key' placeholder='留空保持当前值'><label><input type='checkbox' name='clear_api_key'> 清除 API Key</label>
<label><input type='checkbox' name='allow_public' {checked_public}> 允许公开访问（无 API Key 时）</label>
<label>功能列表（逗号分隔）</label><textarea name='features'>{esc(feature_text)}</textarea>
<label>内容包签名公钥（Base64 X.509 Ed25519）</label><textarea name='signing_public_key'>{esc(config.get("signingPublicKey", ""))}</textarea>
<div class='row'><div><label>GitHub 仓库</label><input name='github_repository' value='{esc(config.get("githubRepository", ""))}'></div><div><label>Release 同步秒数（至少 300）</label><input name='release_sync_seconds' type='number' value='{esc(config.get("releaseSyncSeconds", 1800))}'></div></div>
<label>GitHub Token（当前：{'已设置' if config.get("githubToken") else '未设置'}）</label><input type='password' name='github_token' placeholder='留空保持当前值'><label><input type='checkbox' name='clear_github_token'> 清除 GitHub Token</label>
<label><input type='checkbox' name='github_include_prerelease' {checked_pre}> 包含预发布 Release</label>
<label>Release versionCode 覆盖值</label><input name='release_version_code' type='number' value='{esc(config.get("releaseVersionCode", 0))}'>
<button type='submit'>保存设置</button><button class='secondary' type='submit' formaction='/admin/sync'>立即同步模块 Release</button>
</form><hr><h2>内容包上传/更新</h2>
<p><small>上传后立即覆盖当前版本；旧文件保留在对应目录的 history 中。kind 支持 online_source、epub_style、highlight_style、association_source、theme。</small></p>
<form method='post' action='/admin/packages/upload' enctype='multipart/form-data'>
<div class='row'><div><label>内容类型</label><input name='kind' placeholder='online_source' required></div><div><label>包 ID</label><input name='package_id' placeholder='source.example' required></div></div>
<div class='row'><div><label>版本号</label><input name='version' placeholder='1.0.0' required></div><div><label>稳定内容 ID（书源建议固定）</label><input name='content_id' placeholder='source.example'></div></div>
<label>旧 ID/域名别名（逗号分隔）</label><input name='aliases' placeholder='online_old_hash,old.example.com'>
<label>Ed25519 签名（可选，需与服务器公钥匹配）</label><input name='signature' placeholder='Base64 签名'>
<label>内容文件</label><input type='file' name='payload' required>
<button type='submit'>上传并发布</button></form><h3>已发布内容包</h3><table><thead><tr><th>类型</th><th>包 ID</th><th>版本</th><th>构建时间</th></tr></thead><tbody>{package_table}</tbody></table>
<hr><p><a href='/v1/health'>健康检查</a>　<a href='/v1/meta'>能力信息</a></p></main></body></html>"""


@app.get("/admin", response_class=HTMLResponse)
async def admin(credentials: HTTPBasicCredentials | None = Depends(basic_security)) -> HTMLResponse:
    require_admin(credentials)
    return HTMLResponse(admin_page(load_config()))


@app.post("/admin/settings", response_class=HTMLResponse)
async def admin_settings(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    server_id: str = Form(""),
    min_module_version: str = Form("2.0.0"),
    api_key: str = Form(""),
    allow_public: str | None = Form(None),
    features: str = Form(""),
    signing_public_key: str = Form(""),
    github_repository: str = Form("YGHFv/ReaMicro-Extend"),
    github_token: str = Form(""),
    clear_api_key: str | None = Form(None),
    clear_github_token: str | None = Form(None),
    github_include_prerelease: str | None = Form(None),
    release_sync_seconds: int = Form(1800),
    release_version_code: int = Form(0),
) -> HTMLResponse:
    require_admin(credentials)
    current = load_config()
    next_config = {
        "serverId": server_id.strip() or current["serverId"],
        "minModuleVersion": min_module_version.strip() or current["minModuleVersion"],
        "apiKey": "" if clear_api_key is not None else (api_key or current.get("apiKey", "")),
        "allowPublic": allow_public is not None,
        "features": [item.strip() for item in features.split(",") if item.strip()],
        "signingPublicKey": signing_public_key.strip(),
        "githubRepository": github_repository.strip() or current["githubRepository"],
        "githubToken": "" if clear_github_token is not None else (github_token or current.get("githubToken", "")),
        "githubIncludePrerelease": github_include_prerelease is not None,
        "releaseSyncSeconds": release_sync_seconds,
        "releaseVersionCode": release_version_code,
    }
    return HTMLResponse(admin_page(save_config(next_config), "设置已保存"))


@app.post("/admin/sync", response_class=HTMLResponse)
async def admin_sync(credentials: HTTPBasicCredentials | None = Depends(basic_security)) -> HTMLResponse:
    require_admin(credentials)
    try:
        await asyncio.to_thread(sync_module_release)
        return HTMLResponse(admin_page(load_config(), "模块 Release 已同步"))
    except Exception as error:
        return HTMLResponse(admin_page(load_config(), f"同步失败：{error}"), status_code=502)


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
) -> HTMLResponse:
    require_admin(credentials)
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
    return HTMLResponse(admin_page(load_config(), f"已发布 {kind}/{package_id} {version}"))


@app.get("/v1/meta")
async def meta(_: None = Depends(authenticated)) -> dict[str, Any]:
    config = load_config()
    return response({
        "apiVersion": API_VERSION,
        "serverId": config["serverId"],
        "features": config["features"],
        "authModes": ["public", "api_key"],
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
