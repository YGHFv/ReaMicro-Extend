from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request

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
    owner_host_account_id,
    remove_credential_documents,
)

router = APIRouter()


def save_credential_documents(values, owner: str, payload: dict[str, Any], account_id: str,
                              token: str, base_url: str, now: int) -> dict[str, Any]:
    credentials = values["credentials"]
    requested_id = str(payload.get("id", "")).strip()
    requested_existing = credentials.get(requested_id) if requested_id else None
    if (isinstance(requested_existing, dict) and requested_existing.get("owner") == owner
            and account_id and str(requested_existing.get("accountId", "")) != account_id):
        requested_id = ""
    matching = [
        (key, item) for key, item in credentials.items()
        if item.get("owner") == owner and item.get("type", "reamicro") == "reamicro"
        and account_id and str(item.get("accountId", "")) == account_id
    ]
    matching.sort(key=lambda pair: (bounded_config_int(pair[1].get("updatedAt", 0), 0, 0), pair[0]), reverse=True)
    credential_id = (matching[0][0] if matching else "") or requested_id or "rea_" + secrets.token_hex(10)
    existing = credentials.get(credential_id, {})
    if existing and existing.get("owner") != owner:
        raise HTTPException(status_code=404, detail=response(code="CREDENTIAL_NOT_FOUND", message="凭据不存在"))
    credential = {
        "id": credential_id, "owner": owner, "type": "reamicro",
        "label": str(payload.get("label") or "阅微账号")[:100], "accountId": account_id,
        "secretEncrypted": encrypt_secret({"token": token, "baseUrl": base_url}),
        "createdAt": int(existing.get("createdAt", now)), "updatedAt": now,
        "lastVerifiedAt": now, "lastVerifyMessage": "验证成功",
        "lastUsedAt": int(existing.get("lastUsedAt", 0)), "lastFailedAt": int(existing.get("lastFailedAt", 0)),
        "verifyFailures": 0, "enabled": True,
    }
    credentials[credential_id] = credential
    duplicates = {
        key for key, item in credentials.items()
        if key != credential_id and item.get("owner") == owner and item.get("type", "reamicro") == "reamicro"
        and account_id and str(item.get("accountId", "")) == account_id
    }
    for duplicate in duplicates:
        credentials.pop(duplicate)
    if duplicates:
        for task in values["tasks"].values():
            if task.get("owner") != owner:
                continue
            try:
                task_request = decrypt_secret(str(task["requestEncrypted"])) if task.get("requestEncrypted") else {}
            except Exception:
                if str(task.get("credentialId", "")) in duplicates:
                    task.update(credentialId=credential_id, enabled=False, status="paused", nextRunAt=0,
                                lastMessage="任务配置无法解密，请重新配置后恢复")
                continue
            if not isinstance(task_request, dict):
                continue
            if str(task_request.get("credentialId", task.get("credentialId", ""))) not in duplicates:
                continue
            task_request["credentialId"] = credential_id
            task["credentialId"] = credential_id
            task["requestEncrypted"] = encrypt_secret(task_request)
            task["updatedAt"] = now
    return response(credential_public(credential))


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
    result = runtime.get_state_store().mutate_namespaces(
        ("credentials", "tasks"),
        lambda values: save_credential_documents(values, owner, payload, account_id, token, base_url, now),
        idempotency=(owner, "save_credential", idempotency_key) if idempotency_key else None,
    )
    credential_id = str(result["data"]["id"])
    audit_event("credential_uploaded", f"owner:{owner}", metadata={"credentialId": credential_id, "accountId": account_id, "type": "reamicro"})
    return result


@router.delete("/v1/credentials/reamicro/{credential_id}")
async def delete_reamicro_credential(credential_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    def delete(values):
        if remove_credential_documents(values, credential_id, owner) is None:
            raise HTTPException(status_code=404, detail=response(code="CREDENTIAL_NOT_FOUND", message="凭据不存在"))
    runtime.get_state_store().mutate_namespaces(("credentials", "tasks"), delete)
    return response({"deleted": True})


@router.post("/v1/credentials/reamicro/{credential_id}/verify")
async def verify_reamicro_credential(credential_id: str, owner: str = Depends(task_owner)) -> dict[str, Any]:
    credential = load_credentials().get(credential_id)
    if credential is None or credential.get("owner") != owner:
        raise HTTPException(status_code=404, detail=response(code="CREDENTIAL_NOT_FOUND", message="凭据不存在"))
    secret_encrypted = str(credential.get("secretEncrypted", ""))
    try:
        secret = decrypt_secret(secret_encrypted)
        token = str(secret.get("token", "")) if isinstance(secret, dict) else ""
        base_url = str(secret.get("baseUrl", "")) if isinstance(secret, dict) else ""
        verified, message = await executors.verify_reamicro_secret(token, base_url)
    except Exception as error:
        verified, message = False, str(error)
    accepted = executors.update_credential_health(
        credential_id, verified, message,
        expected_owner=owner, expected_secret_encrypted=secret_encrypted,
    )
    current = load_credentials().get(credential_id)
    if not isinstance(current, dict) or current.get("owner") != owner:
        raise HTTPException(status_code=404, detail=response(code="CREDENTIAL_NOT_FOUND", message="凭据不存在"))
    if not accepted or str(current.get("secretEncrypted", "")) != secret_encrypted:
        raise HTTPException(status_code=409, detail=response(code="CREDENTIAL_CHANGED", message="凭据已更新，请重新验证"))
    if not verified:
        raise HTTPException(status_code=400, detail=response(code="CREDENTIAL_VERIFY_FAILED", message=message, data=credential_public(current)))
    return response(credential_public(current))


@router.post("/v1/credentials/reamicro/{credential_id}/toggle")
async def toggle_reamicro_credential(credential_id: str, request: Request, owner: str = Depends(task_owner)) -> dict[str, Any]:
    try:
        payload = await request.json()
    except ValueError:
        raise HTTPException(status_code=400, detail=response(code="CREDENTIAL_INVALID", message="凭据 JSON 无效"))
    def toggle(credentials):
        credential = credentials.get(credential_id)
        if credential is None or credential.get("owner") != owner:
            raise HTTPException(status_code=404, detail=response(code="CREDENTIAL_NOT_FOUND", message="凭据不存在"))
        credential["enabled"] = bool(payload.get("enabled", not credential.get("enabled", True))) if isinstance(payload, dict) else not credential.get("enabled", True)
        credential["updatedAt"] = int(datetime.now(timezone.utc).timestamp() * 1000)
        return credential
    credential = runtime.get_state_store().mutate_namespace("credentials", toggle)
    audit_event("credential_toggled", f"owner:{owner}", metadata={"credentialId": credential_id, "enabled": credential["enabled"]})
    return response(credential_public(credential))
