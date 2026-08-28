"""阅微同步密钥的增删、验证与启停。

明文密钥只在上传时接收，落盘即加密，任何响应都不回传明文。
"""

from typing import Any

from fastapi import APIRouter, Depends, Form, Header, HTTPException, Query, Request, UploadFile, File, status
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.security import HTTPBasicCredentials

from app import executors, runtime
import secrets
from datetime import datetime, timezone
from app.audit import audit_event
from app.config_store import bounded_config_int
from app.crypto import (
    decrypt_secret,
    encrypt_secret,
)
from app.responses import response
from app.security import task_owner
from app.state import (
    credential_public,
    load_credentials,
    load_tasks,
    owner_host_account_id,
    save_credentials,
    save_tasks,
)

router = APIRouter()


@router.get("/v1/credentials/reamicro")
async def list_reamicro_credentials(owner: str = Depends(task_owner)) -> dict[str, Any]:
    items = [credential_public(item) for item in load_credentials().values() if item.get("owner") == owner and item.get("type") == "reamicro"]
    items.sort(key=lambda item: int(item.get("updatedAt", 0)), reverse=True)
    return response({"items": items})


@router.post("/v1/credentials/reamicro")
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
        previous = runtime.get_state_store().get_idempotency(owner, "save_credential", idempotency_key)
        if previous:
            return previous
    if len(token) < 16 or len(token) > 8_192:
        raise HTTPException(status_code=400, detail=response(code="CREDENTIAL_INVALID", message="阅微登录密钥长度无效"))
    base_url = str(payload.get("baseUrl") or "https://api.reamicro.zhendong.ltd/").strip()
    if not base_url.startswith("https://"):
        raise HTTPException(status_code=400, detail=response(code="CREDENTIAL_INVALID", message="阅微 API 地址必须使用 HTTPS"))
    verified, verify_message = await executors.verify_reamicro_secret(token, base_url)
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
        runtime.get_state_store().save_idempotency(owner, "save_credential", idempotency_key, result, now)
    return result


@router.delete("/v1/credentials/reamicro/{credential_id}")
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


@router.post("/v1/credentials/reamicro/{credential_id}/verify")
async def verify_reamicro_credential(credential_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    credentials = load_credentials()
    credential = credentials.get(credential_id)
    if not credential or credential.get("owner") != owner:
        raise HTTPException(status_code=404, detail=response(code="CREDENTIAL_NOT_FOUND", message="凭据不存在"))
    try:
        secret = decrypt_secret(str(credential.get("secretEncrypted", "")))
        token = str(secret.get("token", "")) if isinstance(secret, dict) else ""
        base_url = str(secret.get("baseUrl", "")) if isinstance(secret, dict) else ""
        verified, message = await executors.verify_reamicro_secret(token, base_url)
    except Exception as error:
        verified, message = False, str(error)
    executors.update_credential_health(credential_id, verified, message)
    current = load_credentials().get(credential_id, credential)
    if not verified:
        raise HTTPException(status_code=400, detail=response(code="CREDENTIAL_VERIFY_FAILED", message=message, data=credential_public(current)))
    return response(credential_public(current))


@router.post("/v1/credentials/reamicro/{credential_id}/toggle")
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
