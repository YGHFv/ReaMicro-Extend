"""内容包的读取、下载与模块上传。

模块上传遵循"同名同域只关联、不覆盖"：服务器已有同名同域的源时只回关联信息，
不改服务器内容，避免任何白名单用户改动共享内容库。
"""

from typing import Any

from fastapi import APIRouter, Depends, Form, Header, HTTPException, Query, Request, UploadFile, File, status
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.security import HTTPBasicCredentials

from app import runtime
import base64
import binascii
import json
from app.audit import (
    audit_actor,
    audit_event,
)
from app.config_store import (
    load_config,
    module_upload_kinds,
)
from app.labels import _admin_kind_label
from app.packages import (
    MATCH_REASON_LABELS,
    absorb_match_hints,
    module_package_summary,
    parse_module_upload_item,
    _admin_package_records,
    find_matching_package,
    infer_package_metadata,
    merge_package_aliases,
    normalize_content_id,
    normalize_source_domain,
    package_dependency_status,
    package_manifest,
    persist_package_payload,
    resolve_package_identity,
    safe_package_segment,
    safe_payload_filename,
    validate_package_payload,
)
from app.responses import response
from app.users import check_upload_quota
from app.security import (
    authenticated,
    basic_security,
    check_request_auth,
    enforce_api_scope,
    module_upload_owner,
    module_upload_policy,
    require_admin,
    require_admin_csrf,
    require_admin_permission,
    task_owner,
)

router = APIRouter()


@router.get("/v1/packages")
async def packages(kind: str = Query(...), _: None = Depends(authenticated)) -> dict[str, Any]:
    if not kind.replace("_", "").replace("-", "").isalnum():
        raise HTTPException(status_code=400, detail=response(code="INVALID_KIND", message="内容包类型无效"))
    items = []
    for manifest_path in sorted((runtime.PACKAGE_ROOT / kind).glob("*/manifest.json")):
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            if str(manifest.get("status", "published")) != "published":
                continue
            manifest.setdefault("kind", kind)
            manifest.setdefault("downloadUrl", f"/v1/packages/{kind}/{manifest_path.parent.name}/download")
            manifest.update(package_dependency_status(manifest))
            items.append(manifest)
        except (OSError, ValueError, HTTPException):
            continue
    return response({"kind": kind, "items": items})


@router.get("/v1/packages/upload/policy")
async def module_upload_policy_endpoint(
    request: Request,
    x_reamicro_api_key: str | None = Header(default=None),
    x_reamicro_account: str | None = Header(default=None),
    x_reamicro_password: str | None = Header(default=None),
    x_reamicro_host_account_id: str | None = Header(default=None),
) -> dict[str, Any]:
    """模块查询是否允许上传内容库；未获许可也返回 200，由客户端提示原因。"""
    check_request_auth(x_reamicro_api_key, x_reamicro_account, x_reamicro_password, x_reamicro_host_account_id)
    enforce_api_scope(request, x_reamicro_api_key)
    policy = module_upload_policy(load_config(), x_reamicro_host_account_id)
    # 带上配额用量，模块在上传前就能显示"还能传几个"，不用先撞墙再看报错。
    _, _, usage = check_upload_quota(str(x_reamicro_host_account_id or ""))
    return response({**policy, **usage})


@router.post("/v1/packages/match")
async def match_packages(request: Request, owner: str = Depends(task_owner)) -> dict[str, Any]:
    """按“名称 + 域名”把本地书源和关联源与服务器内容库比对，返回可关联的内容包。"""
    payload = await request.json()
    items = payload.get("items") if isinstance(payload, dict) else payload
    if not isinstance(items, list):
        raise HTTPException(status_code=400, detail=response(code="INVALID_PAYLOAD", message="items 必须是数组"))
    if len(items) > 500:
        raise HTTPException(status_code=400, detail=response(code="INVALID_PAYLOAD", message="单次最多比对 500 个源"))
    results = []
    for item in items:
        parsed = parse_module_upload_item(item)
        manifest = find_matching_package(
            parsed["kind"], parsed["name"], parsed["domains"], parsed["identities"], parsed["names"],
        )
        entry: dict[str, Any] = {"kind": parsed["kind"], "name": parsed["name"], "contentId": parsed["contentId"], "matched": manifest is not None}
        if manifest is not None:
            entry["package"] = module_package_summary(manifest, parsed["kind"])
        results.append(entry)
    return response({"items": results, "matched": sum(1 for item in results if item["matched"])})


@router.post("/v1/packages/upload")
async def module_upload_package(request: Request, owner: str = Depends(module_upload_owner)) -> dict[str, Any]:
    """模块上传单个书源或关联源。服务器已存在同名同域的源时只关联，不覆盖内容。"""
    payload = await request.json()
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail=response(code="INVALID_PAYLOAD", message="请求体必须是对象"))
    config = load_config()
    parsed = parse_module_upload_item(payload)
    kind = parsed["kind"]
    if kind not in module_upload_kinds(config.get("moduleUploadKinds")):
        raise HTTPException(status_code=403, detail=response(code="MODULE_UPLOAD_FORBIDDEN", message=f"服务器未开放上传{_admin_kind_label(kind)}"))
    try:
        body = base64.b64decode(str(payload.get("payload", "")), validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(status_code=400, detail=response(code="INVALID_PAYLOAD", message="内容文件必须是 base64 编码"))
    if not body:
        raise HTTPException(status_code=400, detail=response(code="INVALID_PAYLOAD", message="内容文件为空"))
    if len(body) > runtime.MODULE_UPLOAD_MAX_BYTES:
        raise HTTPException(status_code=413, detail=response(code="PAYLOAD_TOO_LARGE", message=f"内容文件超过 {runtime.MODULE_UPLOAD_MAX_BYTES // (1024 * 1024)} MB"))
    filename = safe_payload_filename(str(payload.get("payloadName", "")) or f"{kind}.json")
    validate_package_payload(kind, filename, body)
    existing = find_matching_package(
        kind, parsed["name"], parsed["domains"], parsed["identities"], parsed["names"],
    )
    if existing is not None:
        # 名称与地址各命中一项即视为同一个源：不覆盖服务器内容，只回关联信息供后续更新。
        # 同时把本次带来的新名称与新地址并入清单——源改名或换域名后，
        # 集合里同时留着新旧两套，用旧信息的客户端也不会失联。
        matched = absorb_match_hints(kind, existing, parsed)
        reason = MATCH_REASON_LABELS.get(str(existing.get("_matchReason", "")), "名称与地址匹配")
        audit_event(
            "module_upload_linked",
            actor=owner,
            success=True,
            metadata={"kind": kind, "packageId": matched.get("packageId", ""), "matchReason": str(existing.get("_matchReason", ""))},
        )
        return response(
            {"uploaded": False, "linked": True, "package": module_package_summary(matched, kind)},
            message=f"服务器已存在同一个源（{reason}），已自动关联",
        )
    # 配额只拦新建：上面命中已有内容包的路径已经 return，走到这里才是真的要建新包。
    # 反过来若在关联前就拦，用户想更新自己的源会被自己的配额挡住。
    host_account_id = str(request.headers.get("X-ReaMicro-Host-Account-Id", "")).strip()
    allowed_by_quota, quota_reason, quota_usage = check_upload_quota(host_account_id)
    if not allowed_by_quota:
        audit_event(
            "module_upload_quota_exceeded",
            actor=owner,
            success=False,
            metadata={"kind": kind, "hostAccountId": host_account_id, **quota_usage},
        )
        raise HTTPException(
            status_code=409,
            detail=response(code="UPLOAD_QUOTA_EXCEEDED", message=quota_reason, data=quota_usage),
        )
    metadata = infer_package_metadata(kind, filename, body)
    detected_domains = sorted(parsed["domains"] | {
        domain for domain in (normalize_source_domain(value) for value in metadata.get("domains", [])) if domain
    })
    package_id, existing_manifest = resolve_package_identity(kind, "", parsed["contentId"], metadata)
    if existing_manifest is not None:
        # 标识命中旧包但名称或域名不同，同样只关联，避免模块改写他人内容。
        audit_event("module_upload_linked", actor=owner, success=True, metadata={"kind": kind, "packageId": package_id})
        return response(
            {"uploaded": False, "linked": True, "package": module_package_summary(existing_manifest, kind)},
            message="服务器已存在同一标识的源，已自动关联",
        )
    stable_id = normalize_content_id(parsed["contentId"], package_id)
    manifest = persist_package_payload(
        kind=kind,
        package_id=package_id,
        version="1.0.0",
        content_id=stable_id,
        filename=filename,
        body=body,
        name=parsed["name"],
        description=f"模块上传内容包 · 阅微账号 {module_upload_policy(config, request.headers.get('X-ReaMicro-Host-Account-Id'))['hostAccountId']}",
        aliases=merge_package_aliases([], list(metadata.get("identity", [])) + sorted(parsed["identities"]), ""),
        domains=detected_domains,
        status_value="published",
        channel="stable",
        dependencies=[],
        owner=owner,
        names=sorted(parsed["names"]),
        primary=parsed["primaryDomain"],
    )
    audit_event("module_upload_created", actor=owner, success=True, metadata={"kind": kind, "packageId": package_id, "version": manifest.get("version", "")})
    return response(
        {"uploaded": True, "linked": True, "package": module_package_summary(manifest, kind)},
        message="内容已上传并关联",
    )


@router.get("/v1/packages/dependency-graph")
async def package_dependency_graph(_: None = Depends(authenticated)) -> dict[str, Any]:
    nodes = []
    edges = []
    for manifest in _admin_package_records():
        node_id = f"{manifest.get('kind')}:{manifest.get('packageId')}"
        nodes.append({"id": node_id, "kind": manifest.get("kind"), "packageId": manifest.get("packageId"), "version": manifest.get("version"), "status": manifest.get("status", "published")})
        for dependency in manifest.get("dependencies", []) if isinstance(manifest.get("dependencies"), list) else []:
            edges.append({"from": node_id, "to": f"{dependency.get('kind', '*')}:{dependency.get('packageId', '')}", "required": dependency.get("required", True), "minVersion": dependency.get("minVersion", ""), "maxVersion": dependency.get("maxVersion", "")})
    return response({"nodes": nodes, "edges": edges})


@router.get("/v1/packages/{package_kind}/{package_id}/history")
async def package_history(package_kind: str, package_id: str, _: None = Depends(authenticated)) -> dict[str, Any]:
    safe_package_segment(package_kind)
    safe_package_segment(package_id)
    package_dir = (runtime.PACKAGE_ROOT / package_kind / package_id).resolve()
    if runtime.PACKAGE_ROOT.resolve() not in package_dir.parents:
        raise HTTPException(status_code=400, detail=response(code="INVALID_PACKAGE", message="内容包路径无效"))
    history = []
    for manifest_path in sorted((package_dir / "history").glob("*/manifest.json"), reverse=True):
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            history.append({key: manifest.get(key) for key in ("packageId", "contentId", "version", "buildTime", "sha256", "status", "channel", "aliases")})
        except (OSError, ValueError):
            continue
    return response({"kind": package_kind, "packageId": package_id, "items": history})


@router.post("/v1/packages/{package_kind}/{package_id}/publish")
async def publish_package(
    package_kind: str,
    package_id: str,
    request: Request,
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    x_admin_csrf: str = Header(default=""),
) -> dict[str, Any]:
    actor = require_admin(credentials)
    require_admin_permission(actor, "packages:write")
    require_admin_csrf(load_config(), actor, x_admin_csrf)
    payload = await request.json()
    status_value = str(payload.get("status", "published")).strip().lower() if isinstance(payload, dict) else "published"
    if status_value not in {"draft", "testing", "published", "unpublished"}:
        raise HTTPException(status_code=400, detail=response(code="PACKAGE_INVALID", message="内容包状态无效"))
    manifest_path, manifest = package_manifest(package_kind, package_id)
    manifest.setdefault("kind", package_kind)
    dependency_status = package_dependency_status(manifest)
    if status_value == "published" and not dependency_status["dependenciesSatisfied"]:
        raise HTTPException(
            status_code=409,
            detail=response(code="PACKAGE_DEPENDENCIES_UNRESOLVED", message="内容包存在未满足的必需依赖", data=dependency_status),
        )
    manifest["status"] = status_value
    if isinstance(payload, dict) and payload.get("channel"):
        manifest["channel"] = str(payload["channel"]).strip().lower()
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    audit_event("package_status_changed", audit_actor(actor), metadata={"kind": package_kind, "packageId": package_id, "status": status_value})
    return response({"packageId": package_id, "status": manifest["status"], "channel": manifest.get("channel", "stable")})


@router.post("/v1/packages/batch-publish")
async def batch_publish_packages(
    request: Request,
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    x_admin_csrf: str = Header(default=""),
) -> dict[str, Any]:
    actor = require_admin(credentials)
    require_admin_permission(actor, "packages:write")
    require_admin_csrf(load_config(), actor, x_admin_csrf)
    payload = await request.json()
    items = payload.get("items", []) if isinstance(payload, dict) else []
    if not isinstance(items, list) or len(items) > 100:
        raise HTTPException(status_code=400, detail=response(code="PACKAGE_INVALID", message="items 必须是最多 100 项的数组"))
    results = []
    for item in items:
        if not isinstance(item, dict):
            continue
        try:
            kind = safe_package_segment(str(item.get("kind", ""))); package_id = safe_package_segment(str(item.get("packageId", "")))
            manifest_path, manifest = package_manifest(kind, package_id)
            desired = str(item.get("status", "published")).lower()
            if desired not in {"draft", "testing", "published", "unpublished"}:
                raise ValueError("状态无效")
            if desired == "published" and not package_dependency_status(manifest).get("dependenciesSatisfied", True):
                raise ValueError("依赖未满足")
            manifest["status"] = desired
            if item.get("channel"):
                channel = str(item.get("channel")).lower()
                if channel not in {"stable", "beta", "nightly"}: raise ValueError("渠道无效")
                manifest["channel"] = channel
            manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
            results.append({"kind": kind, "packageId": package_id, "status": desired})
        except (HTTPException, ValueError) as error:
            results.append({"kind": item.get("kind", ""), "packageId": item.get("packageId", ""), "error": str(error)})
    audit_event("packages_batch_published", audit_actor(actor), metadata={"count": len(results)})
    return response({"items": results})


@router.post("/v1/packages/{package_kind}/{package_id}/rollback")
async def rollback_package(
    package_kind: str,
    package_id: str,
    request: Request,
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    x_admin_csrf: str = Header(default=""),
) -> dict[str, Any]:
    actor = require_admin(credentials)
    require_admin_permission(actor, "packages:write")
    require_admin_csrf(load_config(), actor, x_admin_csrf)
    payload = await request.json()
    target_version = str(payload.get("version", "")).strip() if isinstance(payload, dict) else ""
    manifest_path, current = package_manifest(package_kind, package_id)
    package_dir = manifest_path.parent
    candidates = []
    for history_manifest in (package_dir / "history").glob("*/manifest.json"):
        try:
            item = json.loads(history_manifest.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if target_version and str(item.get("version")) != target_version:
            continue
        old_payload = history_manifest.parent / str(item.get("payload", ""))
        if old_payload.is_file():
            candidates.append((item, old_payload))
    if not candidates:
        raise HTTPException(status_code=404, detail=response(code="PACKAGE_VERSION_NOT_FOUND", message="历史版本不存在"))
    selected, old_payload = sorted(candidates, key=lambda pair: int(pair[0].get("buildTime", 0)), reverse=True)[0]
    selected.setdefault("kind", package_kind)
    dependency_status = package_dependency_status(selected)
    if not dependency_status["dependenciesSatisfied"]:
        raise HTTPException(
            status_code=409,
            detail=response(code="PACKAGE_DEPENDENCIES_UNRESOLVED", message="历史版本存在未满足的必需依赖，不能发布回滚", data=dependency_status),
        )
    current_payload = package_dir / str(current.get("payload", ""))
    if current_payload.is_file():
        current_payload.unlink()
    new_payload = package_dir / str(selected.get("payload", old_payload.name))
    new_payload.write_bytes(old_payload.read_bytes())
    selected["status"] = "published"
    selected["rolledBackFrom"] = current.get("version", "")
    manifest_path.write_text(json.dumps(selected, ensure_ascii=False, indent=2), encoding="utf-8")
    audit_event("package_rolled_back", audit_actor(actor), metadata={"kind": package_kind, "packageId": package_id, "version": selected.get("version")})
    return response({"packageId": package_id, "version": selected.get("version"), "rolledBack": True})


@router.get("/v1/packages/{package_kind}/{package_id}/download")
async def package_download(package_kind: str, package_id: str, _: None = Depends(authenticated)) -> FileResponse:
    if not package_kind.replace("_", "").replace("-", "").isalnum() or not package_id.replace("_", "").replace("-", "").isalnum():
        raise HTTPException(status_code=400, detail=response(code="INVALID_PACKAGE", message="内容包标识无效"))
    manifest_path = runtime.PACKAGE_ROOT / package_kind / package_id / "manifest.json"
    if not manifest_path.is_file():
        raise HTTPException(status_code=404, detail=response(code="NOT_FOUND", message="内容包不存在"))
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if str(manifest.get("status", "published")) != "published":
            raise HTTPException(status_code=404, detail=response(code="PACKAGE_NOT_PUBLISHED", message="内容包尚未发布"))
        manifest.setdefault("kind", package_kind)
        dependency_status = package_dependency_status(manifest)
        if not dependency_status["dependenciesSatisfied"]:
            raise HTTPException(
                status_code=409,
                detail=response(code="PACKAGE_DEPENDENCIES_UNRESOLVED", message="内容包必需依赖尚未满足", data=dependency_status),
            )
        payload = (manifest_path.parent / manifest["payload"]).resolve()
        if runtime.PACKAGE_ROOT.resolve() not in payload.parents:
            raise ValueError("invalid payload path")
    except HTTPException:
        raise
    except (OSError, ValueError, KeyError):
        raise HTTPException(status_code=500, detail=response(code="PACKAGE_INVALID", message="内容包清单无效"))
    if not payload.is_file():
        raise HTTPException(status_code=404, detail=response(code="NOT_FOUND", message="内容包文件不存在"))
    return FileResponse(payload, media_type="application/octet-stream", filename=payload.name)
