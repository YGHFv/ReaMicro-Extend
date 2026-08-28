"""模块 APK 版本查询、下载与 GitHub webhook。

本仓库 CI 发布的都是预发布，所以不带 channel 参数时会命中 stable 过滤并返回 404。
"""

from typing import Any

from fastapi import APIRouter, Depends, Form, Header, HTTPException, Query, Request, UploadFile, File, status
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.security import HTTPBasicCredentials

from app import runtime
import asyncio
import hashlib
import hmac
import json
from app.audit import audit_event
from app.releases import github_webhook_signature
from app import releases as releases_module
from app.responses import response
from app.security import authenticated

router = APIRouter()


@router.get("/v1/releases/module/latest")
async def latest_release(channel: str = Query("stable"), _: None = Depends(authenticated)) -> dict[str, Any]:
    metadata_path = runtime.RELEASE_ROOT / "latest.json"
    if not metadata_path.is_file():
        try:
            await asyncio.to_thread(releases_module.sync_module_release)
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


@router.post("/v1/webhooks/github")
async def github_release_webhook(request: Request) -> dict[str, Any]:
    """GitHub Release webhook。签名计算见 github_webhook_signature。"""
    if not runtime.GITHUB_WEBHOOK_SECRET:
        raise HTTPException(status_code=503, detail=response(code="WEBHOOK_DISABLED", message="未配置 GitHub Webhook Secret"))
    body = await request.body()
    provided = request.headers.get("X-Hub-Signature-256", "")
    expected = github_webhook_signature(body)
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
        await asyncio.to_thread(releases_module.sync_module_release)
        audit_event("github_release_synced", metadata={"action": action})
        return response({"accepted": True, "synced": True})
    return response({"accepted": True, "synced": False})


@router.get("/v1/releases/module/download")
async def download_release(request: Request, _: None = Depends(authenticated)) -> Response:
    apk_path = runtime.RELEASE_ROOT / "latest.apk"
    if not apk_path.is_file():
        raise HTTPException(status_code=404, detail=response(code="NOT_FOUND", message="模块 APK 尚未同步"))
    metadata_path = runtime.RELEASE_ROOT / "latest.json"
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
