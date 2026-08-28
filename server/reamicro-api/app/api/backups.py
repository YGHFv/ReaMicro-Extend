"""模块设置备份与账号密钥备份。

账号密钥备份在模块本机用口令派生密钥加密，服务器只存密文、无法解密。
"""

from typing import Any

from fastapi import APIRouter, Depends, Form, Header, HTTPException, Query, Request, UploadFile, File, status
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.security import HTTPBasicCredentials

from app import runtime
import hashlib
import json
from datetime import datetime, timezone
from app.responses import response
from app.security import backup_owner

router = APIRouter()


@router.post("/v1/backups/module")
async def upload_module_backup(request: Request, owner: str = Depends(backup_owner)) -> dict[str, Any]:
    body = await request.body()
    if not body or len(body) > 20 * 1024 * 1024:
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="备份为空或超过 20 MB"))
    if not body.startswith(b"PK"):
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="备份不是 ZIP 文件"))
    owner_dir = runtime.BACKUP_ROOT / owner / "module"
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


@router.get("/v1/backups/module/latest")
async def download_module_backup(owner: str = Depends(backup_owner)) -> FileResponse:
    owner_dir = runtime.BACKUP_ROOT / owner / "module"
    metadata_path = owner_dir / "latest.json"
    if not metadata_path.is_file():
        raise HTTPException(status_code=404, detail=response(code="BACKUP_NOT_FOUND", message="没有模块设置备份"))
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    target = (owner_dir / str(metadata.get("file", ""))).resolve()
    if target.parent != owner_dir.resolve() or not target.is_file():
        raise HTTPException(status_code=404, detail=response(code="BACKUP_NOT_FOUND", message="备份文件不存在"))
    return FileResponse(target, media_type="application/zip", filename="reamicro-module-backup.zip")


@router.post("/v1/backups/credentials")
async def upload_credentials_backup(request: Request, owner: str = Depends(backup_owner)) -> dict[str, Any]:
    body = await request.body()
    if not body or len(body) > 10 * 1024 * 1024:
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="密钥备份为空或超过 10 MB"))
    if not body.startswith(b"RCRED1\n"):
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="密钥备份格式无效"))
    owner_dir = runtime.SECRET_BACKUP_ROOT / owner
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


@router.get("/v1/backups/credentials/latest")
async def download_credentials_backup(owner: str = Depends(backup_owner)) -> FileResponse:
    owner_dir = runtime.SECRET_BACKUP_ROOT / owner
    metadata_path = owner_dir / "latest.json"
    if not metadata_path.is_file():
        raise HTTPException(status_code=404, detail=response(code="BACKUP_NOT_FOUND", message="没有账号密钥备份"))
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    target = (owner_dir / str(metadata.get("file", ""))).resolve()
    if target.parent != owner_dir.resolve() or not target.is_file():
        raise HTTPException(status_code=404, detail=response(code="BACKUP_NOT_FOUND", message="密钥备份文件不存在"))
    return FileResponse(target, media_type="application/octet-stream", filename="reamicro-credentials.rcbak")
