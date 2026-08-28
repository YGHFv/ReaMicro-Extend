"""管理后台路由。

只做参数校验、权限与 CSRF 检查，页面渲染交给 app.admin.views，
业务操作交给对应业务模块。
"""

from typing import Any

from fastapi import APIRouter, Depends, Form, Header, HTTPException, Query, Request, UploadFile, File, status
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.security import HTTPBasicCredentials

from app import runtime
import asyncio
import hashlib
import html
import json
import secrets
import shutil
import urllib
import zipfile
from datetime import datetime, timezone
from app.admin.format import (
    _admin_digest_label,
    _admin_duration_label,
    _admin_size_label,
    _admin_time_detail,
    _admin_time_label,
    admin_section_path,
)
from app.admin.layout import (
    _admin_shell,
    admin_html,
    admin_login_page,
    admin_setup_page,
)
from app.admin.views import (
    _admin_task_detail,
    admin_page,
    api_key_public_records,
)
from app.audit import (
    audit_actor,
    audit_event,
    task_log,
)
from app.backups import (
    create_server_snapshot,
    list_server_snapshots,
    resolve_snapshot_path,
    restore_server_snapshot,
    rotate_secret_key,
    verify_server_snapshot,
)
from app.config_store import (
    bounded_config_int,
    infer_auth_mode,
    load_config,
    module_upload_kinds_form,
    save_config,
)
from app.crypto import (
    api_key_digest,
    decrypt_secret,
    encrypt_secret,
    generate_long_secret,
    password_hash,
    validate_admin_password,
)
from app.executors import (
    update_credential_health,
    verify_reamicro_secret,
)
from app.labels import (
    _admin_action_label,
    _admin_channel_label,
    _admin_kind_label,
    _admin_metadata_label,
    _admin_owner_label,
    _admin_result_label,
    _admin_status_label,
    _admin_task_label,
    _admin_task_status_label,
)
from app.security import normalized_admin_permissions
from app.packages import (
    _admin_package_payload,
    _metadata_text,
    infer_package_metadata,
    merge_package_aliases,
    next_package_version,
    normalize_content_id,
    normalize_package_dependencies,
    normalize_source_domain,
    package_dependency_status,
    package_manifest,
    package_match_domains,
    package_signature,
    persist_package_payload,
    resolve_package_identity,
    safe_package_segment,
    safe_payload_filename,
    validate_package_payload,
)
from app.releases import sync_module_release
from app.responses import response
from app.scheduler import (
    apply_task_action,
    normalized_time_of_day,
)
from app.security import (
    admin_actor,
    admin_csrf_token,
    basic_security,
    create_admin_session,
    create_api_key_record,
    normalized_admin_name,
    require_admin,
    require_admin_csrf,
    require_admin_permission,
    require_primary_admin,
    resolve_admin,
    revoke_api_key_record,
    session_admin,
    validate_admin_session_token,
)
from app.state import (
    find_duplicate_task,
    load_credentials,
    load_tasks,
    save_credentials,
    save_tasks,
    task_credential_id,
)

router = APIRouter()


@router.get("/admin/legacy", response_class=HTMLResponse)
async def admin_legacy(actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    return RedirectResponse("/admin/settings", status_code=303)


@router.get("/admin/packages/new", response_class=HTMLResponse)
async def admin_new_package(kind: str = Query("online_source"), actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    require_admin_permission(actor, "packages:write")
    kind = kind if kind in runtime.PACKAGE_KINDS else "online_source"
    config = load_config(); esc = lambda value: html.escape(str(value), quote=True)
    csrf = f"<input type='hidden' name='csrf_token' value='{esc(admin_csrf_token(config, actor))}'>"
    options = "".join(f"<option value='{k}' {'selected' if k == kind else ''}>{_admin_kind_label(k)}</option>" for k in sorted(runtime.PACKAGE_KINDS))
    channel_options = "".join(f"<option value='{value}'>{_admin_channel_label(value)}</option>" for value in ("stable", "beta", "nightly"))
    body = f"<div class='panel'><p class='muted'>包 ID、显示名称和版本号可留空：服务器会根据文件内容及已有列表自动生成；同一包再次上传会自动归档旧版本。</p><form method='post' action='/admin/packages/upload' enctype='multipart/form-data'>{csrf}<div class='grid-2'><label>内容类型<select name='kind'>{options}</select></label><label>包 ID（可留空自动生成）<input name='package_id' placeholder='根据名称自动生成'></label><label>版本号（可留空自动递增）<input name='version' placeholder='新包默认为 1.0.0'></label><label>显示名称（可留空自动识别）<input name='name' placeholder='从书源/样式文件识别'></label><label>稳定内容 ID（可留空使用包 ID）<input name='content_id'></label><label>发布状态<select name='status_value'><option value='published'>立即发布</option><option value='draft'>草稿</option><option value='testing'>测试中</option></select></label><label>发布渠道<select name='channel'>{channel_options}</select></label></div><label>别名（逗号分隔）<input name='aliases'></label><label>依赖 JSON<textarea name='dependencies' placeholder='[]'></textarea></label><label>内容文件<input type='file' name='payload' required></label><button class='button' type='submit'>上传并保存</button></form></div>"
    return HTMLResponse(_admin_shell(
        "新增内容",
        body,
        config,
        actor,
        section=kind,
        description="上传书源、关联源、样式或主题。留空字段会由服务器根据文件内容推断。",
        back_href=admin_section_path(kind),
        back_label=f"返回{_admin_kind_label(kind)}列表",
    ))


@router.get("/admin/packages/{package_kind}/{package_id}/preview", response_class=HTMLResponse)
async def admin_preview_package(package_kind: str, package_id: str, actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    package_kind = safe_package_segment(package_kind); package_id = safe_package_segment(package_id)
    manifest_path, manifest = package_manifest(package_kind, package_id)
    filename, payload = _admin_package_payload(manifest_path.parent, manifest)
    try:
        text = payload.decode("utf-8")
    except UnicodeDecodeError:
        text = "[二进制内容，无法在浏览器中预览]"
    if filename.lower().endswith((".json", ".json5")):
        try:
            text = json.dumps(json.loads(text), ensure_ascii=False, indent=2)
        except ValueError:
            pass
    esc = lambda value: html.escape(str(value), quote=True)
    meta_rows = "".join(
        f"<tr><th style='width:150px'>{esc(label)}</th><td>{value}</td></tr>"
        for label, value in (
            ("显示名称", esc(manifest.get("name", package_id))),
            ("包 ID", f"<code>{esc(package_id)}</code>"),
            ("稳定内容 ID", f"<code>{esc(manifest.get('contentId', '') or package_id)}</code>"),
            ("版本", esc(manifest.get("version", ""))),
            ("构建时间", esc(_admin_time_detail(manifest.get("buildTime"), "未记录"))),
            ("发布状态", f"<span class='status status-{esc(manifest.get('status', 'published'))}'>{esc(_admin_status_label(manifest.get('status', 'published')))}</span>"),
            ("发布渠道", esc(_admin_channel_label(manifest.get("channel", "stable")))),
            ("内容文件", f"<code>{esc(filename)}</code> · {esc(_admin_size_label(len(payload)))}"),
            ("内容摘要", f"<code title='{esc(manifest.get('sha256', ''))}'>{esc(_admin_digest_label(manifest.get('sha256')))}</code>"),
            ("域名", esc("、".join(sorted(package_match_domains(manifest))) or "未记录")),
            ("别名", esc("、".join(str(item) for item in manifest.get("aliases", [])) or "无")),
            ("上传来源", esc(_admin_owner_label(manifest.get("uploadOwner", "")) if manifest.get("uploadOwner") else "后台管理员")),
        )
    )
    body = f"<div class='table-wrap' style='margin-bottom:18px'><table style='min-width:auto'><tbody>{meta_rows}</tbody></table></div><div class='panel'><h2>内容文件</h2><pre>{html.escape(text)}</pre></div>"
    return HTMLResponse(_admin_shell(
        "预览 · " + str(manifest.get("name", package_id)),
        body,
        load_config(),
        actor,
        section=package_kind,
        description=f"{_admin_kind_label(package_kind)} · 只读预览，修改请使用编辑。",
        back_href=admin_section_path(package_kind),
        back_label=f"返回{_admin_kind_label(package_kind)}列表",
    ))


@router.get("/admin/packages/{package_kind}/{package_id}/edit", response_class=HTMLResponse)
async def admin_edit_package_get(package_kind: str, package_id: str, actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    require_admin_permission(actor, "packages:write")
    package_kind = safe_package_segment(package_kind); package_id = safe_package_segment(package_id)
    manifest_path, manifest = package_manifest(package_kind, package_id)
    filename, payload = _admin_package_payload(manifest_path.parent, manifest)
    try: text = payload.decode("utf-8")
    except UnicodeDecodeError: text = ""
    config = load_config(); esc = lambda value: html.escape(str(value), quote=True)
    csrf = f"<input type='hidden' name='csrf_token' value='{esc(admin_csrf_token(config, actor))}'>"
    status_options = "".join(f"<option value='{status}' {'selected' if manifest.get('status', 'published') == status else ''}>{_admin_status_label(status)}</option>" for status in ('published', 'draft', 'testing', 'unpublished'))
    channel_options = "".join(f"<option value='{channel}' {'selected' if manifest.get('channel', 'stable') == channel else ''}>{_admin_channel_label(channel)}</option>" for channel in ('stable', 'beta', 'nightly'))
    body = f"<div class='panel'><p class='muted'>当前版本 {esc(manifest.get('version', ''))}，构建于 {esc(_admin_time_label(manifest.get('buildTime'), '未记录'))}。保存后旧版本自动归档到历史，可随时回滚。</p><form method='post' action='/admin/packages/{esc(package_kind)}/{esc(package_id)}/edit'>{csrf}<div class='grid-2'><label>版本号<input name='version' value='{esc(manifest.get('version', ''))}' required></label><label>内容文件名<input name='filename' value='{esc(filename)}' required></label><label>稳定内容 ID<input name='content_id' value='{esc(manifest.get('contentId', ''))}'></label><label>名称<input name='name' value='{esc(manifest.get('name', package_id))}'></label><label>发布状态<select name='status_value'>{status_options}</select></label><label>发布渠道<select name='channel'>{channel_options}</select></label></div><label>别名（逗号分隔）<input name='aliases' value='{esc(','.join(str(item) for item in manifest.get('aliases', [])))}'></label><label>依赖 JSON<textarea name='dependencies'>{esc(json.dumps(manifest.get('dependencies', []), ensure_ascii=False, indent=2))}</textarea></label><label>内容<textarea name='payload_text' style='min-height:320px'>{esc(text)}</textarea></label><div class='inline'><button class='button' type='submit'>保存新版本</button> <a class='button subtle' href='/admin/packages/{esc(package_kind)}/{esc(package_id)}/preview'>取消</a></div></form></div>"
    return HTMLResponse(_admin_shell(
        "编辑 · " + str(manifest.get("name", package_id)),
        body,
        config,
        actor,
        section=package_kind,
        description=f"{_admin_kind_label(package_kind)} · 保存会生成新版本并归档当前版本。",
        back_href=f"/admin/packages/{package_kind}/{package_id}/preview",
        back_label="返回预览",
    ))


@router.post("/admin/packages/{package_kind}/{package_id}/edit", response_class=HTMLResponse)
async def admin_edit_package_post(
    package_kind: str,
    package_id: str,
    version: str = Form(...),
    filename: str = Form(...),
    content_id: str = Form(""),
    name: str = Form(""),
    aliases: str = Form(""),
    status_value: str = Form("published"),
    channel: str = Form("stable"),
    dependencies: str = Form("[]"),
    payload_text: str = Form(""),
    csrf_token: str = Form(""),
    actor: dict[str, Any] = Depends(admin_actor),
) -> HTMLResponse:
    require_admin_permission(actor, "packages:write")
    package_kind = safe_package_segment(package_kind); package_id = safe_package_segment(package_id)
    config = load_config(); require_admin_csrf(config, actor, csrf_token)
    manifest_path, current = package_manifest(package_kind, package_id)
    filename = safe_payload_filename(filename)
    version = safe_package_segment(version)
    status_value = status_value.strip().lower(); channel = channel.strip().lower()
    if status_value not in {"published", "draft", "testing", "unpublished"} or channel not in {"stable", "beta", "nightly"}:
        raise HTTPException(status_code=400, detail="状态或渠道无效")
    body = payload_text.encode("utf-8")
    validate_package_payload(package_kind, filename, body)
    metadata = infer_package_metadata(package_kind, filename, body)
    stable_id = normalize_content_id(content_id, str(current.get("contentId", "")) if current.get("contentId") else package_id)
    try: dependency_items = normalize_package_dependencies(json.loads(dependencies or "[]"), package_kind, package_id)
    except (ValueError, TypeError):
        raise HTTPException(status_code=400, detail="依赖必须是 JSON 数组")
    package_dir = manifest_path.parent
    history = package_dir / "history"; history.mkdir(exist_ok=True)
    old_version = safe_package_segment(current.get("version", "old"))
    archive_dir = history / f"{old_version}-{int(datetime.now(timezone.utc).timestamp() * 1000)}"; archive_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(manifest_path, archive_dir / "manifest.json")
    old_payload = package_dir / str(current.get("payload", ""))
    if old_payload.is_file() and old_payload.parent == package_dir:
        shutil.copy2(old_payload, archive_dir / old_payload.name)
    build_time = int(datetime.now(timezone.utc).timestamp() * 1000); digest = hashlib.sha256(body).hexdigest()
    manifest = {**current, "packageId": package_id, "kind": package_kind, "version": version, "buildTime": build_time, "sha256": digest, "payload": filename, "contentId": stable_id, "aliases": merge_package_aliases(current.get("aliases", []), metadata.get("identity", []), aliases), "name": name.strip() or (str(current.get("name", "")) if not metadata.get("explicitName") else "") or str(metadata.get("name") or package_id), "status": status_value, "channel": channel, "dependencies": dependency_items}
    if status_value == "published" and not package_dependency_status(manifest).get("dependenciesSatisfied", True):
        raise HTTPException(status_code=409, detail="内容包存在未满足的必需依赖")
    manifest["signature"] = package_signature(package_id, package_kind, stable_id, version, build_time, digest, body)
    payload_path = package_dir / filename
    old_payload_path = package_dir / str(current.get("payload", ""))
    temp_payload = package_dir / ".payload.tmp"; temp_payload.write_bytes(body); temp_payload.replace(payload_path)
    if old_payload_path != payload_path and old_payload_path.is_file() and old_payload_path.parent == package_dir:
        old_payload_path.unlink()
    temp_manifest = package_dir / ".manifest.json.tmp"; temp_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"); temp_manifest.replace(manifest_path)
    audit_event("package_edited", audit_actor(actor), metadata={"kind": package_kind, "packageId": package_id, "version": version})
    return HTMLResponse(admin_page(load_config(), f"已保存 {_admin_kind_label(package_kind)}/{package_id} {version}", actor=actor, section=package_kind))


@router.get("/admin/packages/{package_kind}/{package_id}/history", response_class=HTMLResponse)
async def admin_history_package(package_kind: str, package_id: str, actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    package_kind = safe_package_segment(package_kind); package_id = safe_package_segment(package_id)
    manifest_path, current = package_manifest(package_kind, package_id)
    config = load_config()
    esc = lambda value: html.escape(str(value), quote=True)
    csrf = esc(admin_csrf_token(config, actor))
    can_write = actor.get("role") == "primary" or "packages:write" in set(actor.get("permissions", []))
    rows = []
    for path in sorted((manifest_path.parent / "history").glob("*/manifest.json"), reverse=True):
        try:
            item = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        version_value = esc(item.get("version", ""))
        actions = [f"<a class='button subtle' href='/admin/packages/{esc(package_kind)}/{esc(package_id)}/diff?version={urllib.parse.quote(str(item.get('version', '')))}'>对比当前</a>"]
        if can_write:
            actions.append(
                f"<form class='inline' method='post' action='/admin/packages/{esc(package_kind)}/{esc(package_id)}/rollback'>"
                f"<input type='hidden' name='csrf_token' value='{csrf}'>"
                f"<input type='hidden' name='version' value='{version_value}'>"
                f"<button class='button subtle' type='submit'>回滚到此版本</button></form>"
            )
        rows.append(
            f"<tr><td><strong>{version_value}</strong></td>"
            f"<td>{esc(_admin_time_label(item.get('buildTime'), '未记录'))}<small>{esc(_admin_time_detail(item.get('buildTime'), '未记录'))}</small></td>"
            f"<td><code title='{esc(item.get('sha256', ''))}'>{esc(_admin_digest_label(item.get('sha256')))}</code></td>"
            f"<td><span class='status status-{esc(item.get('status', 'published'))}'>{esc(_admin_status_label(item.get('status', 'published')))}</span>"
            f"<small>{esc(_admin_channel_label(item.get('channel', 'stable')))}</small></td>"
            f"<td class='actions'>{' '.join(actions)}</td></tr>"
        )
    history_rows = "".join(rows) or "<tr><td colspan='5' class='empty'>暂无历史版本，保存新版本后旧版本会自动归档到这里</td></tr>"
    current_row = (
        f"<div class='panel'><h2>当前版本</h2><p class='muted'>版本 {esc(current.get('version', ''))} · "
        f"构建于 {esc(_admin_time_detail(current.get('buildTime'), '未记录'))} · "
        f"{esc(_admin_status_label(current.get('status', 'published')))} · {esc(_admin_channel_label(current.get('channel', 'stable')))}</p></div>"
    )
    body = f"{current_row}<div class='table-wrap'><table><thead><tr><th>版本</th><th>构建时间</th><th>内容摘要</th><th>状态 / 渠道</th><th>操作</th></tr></thead><tbody>{history_rows}</tbody></table></div>"
    return HTMLResponse(_admin_shell(
        "历史版本 · " + str(current.get("name", package_id)),
        body,
        config,
        actor,
        section=package_kind,
        description=f"{_admin_kind_label(package_kind)} · 回滚会把所选版本重新置为已发布状态。",
        back_href=admin_section_path(package_kind),
        back_label=f"返回{_admin_kind_label(package_kind)}列表",
    ))


@router.post("/admin/packages/{package_kind}/{package_id}/rollback", response_class=HTMLResponse)
async def admin_rollback_package(
    package_kind: str,
    package_id: str,
    version: str = Form(""),
    csrf_token: str = Form(""),
    actor: dict[str, Any] = Depends(admin_actor),
) -> HTMLResponse:
    require_admin_permission(actor, "packages:write")
    package_kind = safe_package_segment(package_kind); package_id = safe_package_segment(package_id)
    config = load_config(); require_admin_csrf(config, actor, csrf_token)
    manifest_path, current = package_manifest(package_kind, package_id)
    candidates = []
    for history_manifest in (manifest_path.parent / "history").glob("*/manifest.json"):
        try: item = json.loads(history_manifest.read_text(encoding="utf-8"))
        except (OSError, ValueError): continue
        if version and str(item.get("version")) != version: continue
        old_payload = history_manifest.parent / str(item.get("payload", ""))
        if old_payload.is_file(): candidates.append((item, old_payload))
    if not candidates:
        return HTMLResponse(admin_page(config, "历史版本不存在", actor=actor, section=package_kind), status_code=404)
    selected, old_payload = sorted(candidates, key=lambda pair: int(pair[0].get("buildTime", 0)), reverse=True)[0]
    if not package_dependency_status(selected).get("dependenciesSatisfied", True):
        return HTMLResponse(admin_page(config, "历史版本依赖未满足，无法回滚", actor=actor, section=package_kind), status_code=409)
    current_payload = manifest_path.parent / str(current.get("payload", ""))
    if current_payload.is_file(): current_payload.unlink()
    new_payload = manifest_path.parent / safe_payload_filename(str(selected.get("payload", old_payload.name)))
    new_payload.write_bytes(old_payload.read_bytes())
    selected["status"] = "published"; selected["rolledBackFrom"] = current.get("version", "")
    manifest_path.write_text(json.dumps(selected, ensure_ascii=False, indent=2), encoding="utf-8")
    audit_event("package_rolled_back", audit_actor(actor), metadata={"kind": package_kind, "packageId": package_id, "version": selected.get("version")})
    return HTMLResponse(admin_page(load_config(), f"已回滚到版本 {selected.get('version', '')}", actor=actor, section=package_kind))


@router.post("/admin/packages/{package_kind}/{package_id}/delete", response_class=HTMLResponse)
async def admin_delete_package(
    package_kind: str,
    package_id: str,
    csrf_token: str = Form(""),
    actor: dict[str, Any] = Depends(admin_actor),
) -> HTMLResponse:
    require_admin_permission(actor, "packages:write")
    package_kind = safe_package_segment(package_kind); package_id = safe_package_segment(package_id)
    config = load_config(); require_admin_csrf(config, actor, csrf_token)
    manifest_path, manifest = package_manifest(package_kind, package_id)
    if str(manifest.get("status", "published")) == "published":
        raise HTTPException(status_code=409, detail="已发布内容不能直接删除，请先下架")
    package_dir = manifest_path.parent
    trash_dir = runtime.PACKAGE_ROOT / ".trash" / package_kind
    trash_dir.mkdir(parents=True, exist_ok=True)
    target = trash_dir / f"{package_id}-{int(datetime.now(timezone.utc).timestamp() * 1000)}"
    shutil.move(str(package_dir), str(target))
    audit_event("package_deleted", audit_actor(actor), metadata={"kind": package_kind, "packageId": package_id, "status": manifest.get("status")})
    return HTMLResponse(admin_page(load_config(), f"已移除未发布内容 {package_kind}/{package_id}", actor=actor, section=package_kind))


@router.get("/admin/packages/{package_kind}/{package_id}/diff", response_class=HTMLResponse)
async def admin_diff_package(package_kind: str, package_id: str, version: str = Query(""), actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    package_kind = safe_package_segment(package_kind); package_id = safe_package_segment(package_id)
    manifest_path, current = package_manifest(package_kind, package_id)
    current_name, current_payload = _admin_package_payload(manifest_path.parent, current)
    target_payload = b""; target_manifest: dict[str, Any] | None = None
    for path in (manifest_path.parent / "history").glob("*/manifest.json"):
        try: item = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError): continue
        if version and str(item.get("version")) != version: continue
        candidate = path.parent / str(item.get("payload", ""))
        if candidate.is_file(): target_manifest, target_payload = item, candidate.read_bytes(); break
    if target_manifest is None:
        raise HTTPException(status_code=404, detail="历史版本不存在")
    import difflib
    current_text = current_payload.decode("utf-8", errors="replace").splitlines()
    target_text = target_payload.decode("utf-8", errors="replace").splitlines()
    diff = "\n".join(difflib.unified_diff(target_text, current_text, fromfile=f"{target_manifest.get('version')}/{current_name}", tofile=f"{current.get('version')}/{current_name}", lineterm=""))
    esc = lambda value: html.escape(str(value), quote=True)
    body = (
        f"<div class='panel'><p class='muted'>左侧为历史版本 {esc(target_manifest.get('version'))}"
        f"（构建于 {esc(_admin_time_label(target_manifest.get('buildTime'), '未记录'))}），"
        f"右侧为当前版本 {esc(current.get('version'))}"
        f"（构建于 {esc(_admin_time_label(current.get('buildTime'), '未记录'))}）。"
        f"以 <code>-</code> 开头的是历史版本独有的行，<code>+</code> 开头的是当前版本新增的行。</p>"
        f"<pre>{html.escape(diff or '两个版本的内容完全一致，没有文本差异。')}</pre></div>"
    )
    return HTMLResponse(_admin_shell(
        "版本差异 · " + str(current.get("name", package_id)),
        body,
        load_config(),
        actor,
        section=package_kind,
        description=f"{_admin_kind_label(package_kind)} · 历史版本与当前版本的逐行对比。",
        back_href=f"/admin/packages/{package_kind}/{package_id}/history",
        back_label="返回历史版本",
    ))


@router.get("/admin/login", response_class=HTMLResponse)
async def admin_login_get(request: Request) -> HTMLResponse:
    if session_admin(request):
        return RedirectResponse("/admin", status_code=303)
    return HTMLResponse(admin_login_page())


@router.post("/admin/login", response_class=HTMLResponse)
async def admin_login_post(username: str = Form(""), password: str = Form("")) -> Response:
    try:
        actor = resolve_admin(HTTPBasicCredentials(username=username, password=password), allow_bootstrap=True)
    except HTTPException:
        audit_event("admin_login_failed", actor=f"admin:{username}", success=False)
        return HTMLResponse(admin_login_page("用户名或密码错误"), status_code=401)
    token = create_admin_session(actor)
    audit_event("admin_login_success", actor=audit_actor(actor))
    result = RedirectResponse("/admin", status_code=303)
    result.set_cookie(
        runtime.ADMIN_SESSION_COOKIE,
        token,
        max_age=runtime.ADMIN_SESSION_SECONDS,
        httponly=True,
        secure=runtime.ADMIN_COOKIE_SECURE,
        samesite="lax",
        path="/admin",
    )
    return result


@router.post("/admin/logout")
async def admin_logout(request: Request, csrf_token: str = Form("")) -> RedirectResponse:
    token = request.cookies.get(runtime.ADMIN_SESSION_COOKIE, "")
    if token:
        actor = validate_admin_session_token(token)
        if actor:
            require_admin_csrf(load_config(), actor, csrf_token)
        runtime.get_state_store().delete_admin_session(api_key_digest(token))
    result = RedirectResponse("/admin/login", status_code=303)
    result.delete_cookie(runtime.ADMIN_SESSION_COOKIE, path="/admin")
    return result


@router.get("/admin", response_class=HTMLResponse)
async def admin(section: str = Query("overview"), q: str = Query(""), actor: dict[str, Any] = Depends(admin_actor)) -> Response:
    if actor.get("needsSetup"):
        return admin_html(admin_setup_page(actor=actor))
    if section != "overview":
        target = admin_section_path(section)
        if q:
            target += "?q=" + urllib.parse.quote(q, safe="")
        return RedirectResponse(target, status_code=303, headers={"Cache-Control": "no-store"})
    return admin_html(admin_page(load_config(), actor=actor, section="overview", query=q))


@router.get("/admin/content", response_class=HTMLResponse)
async def admin_content(q: str = Query(""), actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    return admin_html(admin_page(load_config(), actor=actor, section="packages", query=q))


@router.get("/admin/content/{package_kind}", response_class=HTMLResponse)
async def admin_content_kind(package_kind: str, q: str = Query(""), actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    if package_kind not in runtime.PACKAGE_KINDS:
        raise HTTPException(status_code=404, detail="内容分类不存在")
    return admin_html(admin_page(load_config(), actor=actor, section=package_kind, query=q))


@router.get("/admin/tasks", response_class=HTMLResponse)
async def admin_tasks_page(actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    return admin_html(admin_page(load_config(), actor=actor, section="tasks"))


@router.get("/admin/settings", response_class=HTMLResponse)
async def admin_settings_page(actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    return admin_html(admin_page(load_config(), actor=actor, section="settings"))


@router.get("/admin/security", response_class=HTMLResponse)
async def admin_security_page(actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    return admin_html(admin_page(load_config(), actor=actor, section="security"))


@router.get("/admin/audit", response_class=HTMLResponse)
async def admin_audit(
    q: str = Query(""),
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_admin_permission(actor, "audit:read")
    esc = lambda value: html.escape(str(value), quote=True)
    needle = q.strip().lower()
    rows = []
    total = 0
    failures = 0
    if runtime.AUDIT_PATH.is_file():
        for line in runtime.AUDIT_PATH.read_text(encoding="utf-8").splitlines()[-800:][::-1]:
            try:
                event = json.loads(line)
            except ValueError:
                continue
            total += 1
            success = bool(event.get("success"))
            if not success:
                failures += 1
            action = str(event.get("action", ""))
            action_label = _admin_action_label(action)
            actor_label = _admin_owner_label(event.get("actor", "")) if str(event.get("actor", "")).startswith(("host:", "account:", "key:", "host-public:")) else str(event.get("actor", "")) or "系统"
            metadata_label = _admin_metadata_label(event.get("metadata"))
            if needle and needle not in " ".join((action, action_label, actor_label, metadata_label)).lower():
                continue
            if len(rows) >= 400:
                continue
            request_id = str(event.get("requestId", ""))
            request_note = f"<small>请求 ID {esc(request_id)}</small>" if request_id else ""
            rows.append(
                f"<tr><td>{esc(_admin_time_label(event.get('at'), '未记录'))}"
                f"<small>{esc(_admin_time_detail(event.get('at'), '未记录'))}</small></td>"
                f"<td>{esc(actor_label)}</td>"
                f"<td><strong>{esc(action_label)}</strong><small><code>{esc(action)}</code></small></td>"
                f"<td><span class='status status-{'success' if success else 'failed'}'>{'成功' if success else '失败'}</span></td>"
                f"<td>{esc(metadata_label)}{request_note}</td></tr>"
            )
    table = "".join(rows) or "<tr><td colspan='5' class='empty'>没有匹配的审计记录</td></tr>"
    stats = (
        f"<div class='stats'>"
        f"<div><strong>{total}</strong><span>最近记录条数</span></div>"
        f"<div><strong>{failures}</strong><span>其中失败事件</span></div>"
        f"<div><strong>{len(rows)}</strong><span>当前显示</span></div>"
        f"<div><strong>{_admin_size_label(runtime.AUDIT_PATH.stat().st_size) if runtime.AUDIT_PATH.is_file() else '0 B'}</strong><span>日志文件大小</span></div>"
        f"</div>"
    )
    body = (
        f"{stats}"
        f"<form class='toolbar' method='get' action='/admin/audit'>"
        f"<input name='q' value='{esc(q)}' placeholder='搜索操作、操作者或详情'>"
        f"<button class='button' type='submit'>搜索</button></form>"
        f"<div class='table-wrap'><table><thead><tr><th>时间</th><th>操作者</th><th>操作</th><th>结果</th><th>详情</th></tr></thead>"
        f"<tbody>{table}</tbody></table></div>"
    )
    return admin_html(_admin_shell(
        "审计日志",
        body,
        load_config(),
        actor,
        section="security",
        eyebrow="保护",
        description="按时间倒序显示最近 800 条事件，最多渲染 400 条。",
        back_href="/admin/security",
        back_label="返回安全设置",
    ))


@router.get("/admin/api-keys")
async def admin_api_keys(credentials: HTTPBasicCredentials | None = Depends(basic_security)) -> dict[str, Any]:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    return response({"items": api_key_public_records(load_config())})


@router.post("/admin/api-keys")
async def admin_create_api_key(
    request: Request,
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    x_admin_csrf: str = Header(default=""),
) -> dict[str, Any]:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    config = load_config()
    require_admin_csrf(config, actor, x_admin_csrf)
    try:
        payload = await request.json()
    except ValueError:
        payload = {}
    if not isinstance(payload, dict):
        payload = {}
    record, plain = create_api_key_record(config, payload.get("name", ""), payload.get("permissions", ["read"]))
    save_config(config)
    audit_event("api_key_created", audit_actor(actor), metadata={"keyId": record["id"], "permissions": record["permissions"]})
    return response({"id": record["id"], "name": record["name"], "permissions": record["permissions"], "key": plain})


@router.post("/admin/api-keys/{key_id}/revoke")
async def admin_revoke_api_key(
    key_id: str,
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    x_admin_csrf: str = Header(default=""),
) -> dict[str, Any]:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    config = load_config()
    require_admin_csrf(config, actor, x_admin_csrf)
    revoke_api_key_record(config, key_id)
    save_config(config)
    audit_event("api_key_revoked", audit_actor(actor), metadata={"keyId": key_id})
    return response({"id": key_id, "revoked": True})


@router.post("/admin/backups/server", response_class=HTMLResponse)
async def admin_server_backup(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    current = load_config()
    require_admin_csrf(current, actor, csrf_token)
    require_admin_permission(actor, "backup:admin")
    try:
        snapshot = await asyncio.to_thread(create_server_snapshot)
        audit_event("server_snapshot_created", audit_actor(actor), success=True, metadata={"filename": snapshot.name})
        return HTMLResponse(admin_page(current, f"服务器快照已创建：{snapshot.name}", actor=actor, section="security"))
    except Exception as error:
        audit_event("server_snapshot_created", audit_actor(actor), success=False)
        return HTMLResponse(admin_page(current, f"服务器快照失败：{error}", actor=actor, section="security"), status_code=500)


@router.post("/admin/security/rotate-secret", response_class=HTMLResponse)
async def admin_rotate_secret(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    new_secret_key: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    current = load_config()
    require_admin_csrf(current, actor, csrf_token)
    try:
        count = await asyncio.to_thread(rotate_secret_key, new_secret_key)
        audit_event("secret_key_rotated", audit_actor(actor), metadata={"reencrypted": count})
        return HTMLResponse(admin_page(load_config(), f"服务器加密密钥已轮换并重新加密 {count} 项数据", actor=actor, section="security"))
    except Exception as error:
        audit_event("secret_key_rotation_failed", audit_actor(actor), success=False)
        return HTMLResponse(admin_page(current, f"密钥轮换失败：{error}", actor=actor, section="security"), status_code=400)


@router.get("/admin/backups/server")
async def admin_server_backups(credentials: HTTPBasicCredentials | None = Depends(basic_security)) -> JSONResponse:
    actor = require_admin(credentials)
    require_admin_permission(actor, "backup:admin")
    return JSONResponse(content=response({"items": list_server_snapshots()}))


@router.get("/admin/backups/server/{filename}")
async def admin_server_backup_download(filename: str, credentials: HTTPBasicCredentials | None = Depends(basic_security)) -> FileResponse:
    actor = require_admin(credentials)
    require_admin_permission(actor, "backup:admin")
    return FileResponse(resolve_snapshot_path(filename), media_type="application/zip", filename=filename)


@router.get("/admin/backups/server/{filename}/verify")
async def admin_verify_server_backup(filename: str, credentials: HTTPBasicCredentials | None = Depends(basic_security)) -> JSONResponse:
    actor = require_admin(credentials); require_admin_permission(actor, "backup:admin")
    path = resolve_snapshot_path(filename)
    try:
        result = verify_server_snapshot(path)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message=f"备份校验失败：{error}"))
    return JSONResponse(content=response(result), status_code=200 if result.get("valid") else 409)


@router.post("/admin/backups/server/{filename}/restore")
async def admin_restore_server_backup(filename: str, request: Request, credentials: HTTPBasicCredentials | None = Depends(basic_security), x_admin_csrf: str = Header(default="")) -> JSONResponse:
    actor = require_admin(credentials); require_primary_admin(actor); require_admin_permission(actor, "backup:admin"); require_admin_csrf(load_config(), actor, x_admin_csrf)
    try: payload = await request.json()
    except ValueError: payload = {}
    if not isinstance(payload, dict) or payload.get("confirm") is not True:
        raise HTTPException(status_code=400, detail=response(code="BACKUP_CONFIRM_REQUIRED", message="恢复操作必须明确 confirm=true"))
    result = await asyncio.to_thread(restore_server_snapshot, filename)
    audit_event("server_snapshot_restored", audit_actor(actor), metadata={"filename": filename, "safetySnapshot": result["safetySnapshot"]})
    return JSONResponse(content=response(result))


@router.post("/admin/security/api-keys/create", response_class=HTMLResponse)
async def admin_security_create_api_key(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    name: str = Form(""),
    # 权限用复选框提交多个同名字段。
    permissions: list[str] = Form(default=["read"]),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    """后台表单创建 API Key，与 JSON 接口共用同一套记录生成逻辑。"""
    actor = require_admin(credentials)
    require_primary_admin(actor)
    config = load_config()
    require_admin_csrf(config, actor, csrf_token)
    try:
        record, plain = create_api_key_record(config, name, permissions)
    except HTTPException as error:
        detail = error.detail
        message = detail.get("message", "创建失败") if isinstance(detail, dict) else str(detail)
        return admin_html(admin_page(config, f"创建 API Key 失败：{message}", actor=actor, section="security"), status_code=400)
    saved = save_config(config)
    audit_event("api_key_created", audit_actor(actor), metadata={"keyId": record["id"], "permissions": record["permissions"]})
    return admin_html(admin_page(
        saved,
        f"API Key「{record['name']}」已创建",
        actor=actor,
        secret_notice=f"{record['name']}（{record['id']}）的密钥：\n{plain}",
        section="security",
    ))


@router.post("/admin/security/api-keys/revoke", response_class=HTMLResponse)
async def admin_security_revoke_api_key(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    key_id: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    config = load_config()
    require_admin_csrf(config, actor, csrf_token)
    try:
        revoke_api_key_record(config, key_id.strip())
    except HTTPException:
        return admin_html(admin_page(config, "API Key 不存在", actor=actor, section="security"), status_code=404)
    saved = save_config(config)
    audit_event("api_key_revoked", audit_actor(actor), metadata={"keyId": key_id.strip()})
    return admin_html(admin_page(saved, f"API Key {key_id.strip()} 已吊销", actor=actor, section="security"))


@router.post("/admin/security/backups/verify", response_class=HTMLResponse)
async def admin_security_verify_backup(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    filename: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    config = load_config()
    require_admin_csrf(config, actor, csrf_token)
    require_admin_permission(actor, "backup:admin")
    try:
        path = resolve_snapshot_path(filename.strip())
        result = await asyncio.to_thread(verify_server_snapshot, path)
    except HTTPException as error:
        detail = error.detail
        message = detail.get("message", "校验失败") if isinstance(detail, dict) else str(detail)
        return admin_html(admin_page(config, f"快照校验失败：{message}", actor=actor, section="security"), status_code=error.status_code)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        return admin_html(admin_page(config, f"快照校验失败：{error}", actor=actor, section="security"), status_code=400)
    if result.get("valid"):
        message = f"快照 {filename.strip()} 完整性校验通过，共 {len(result.get('files', []))} 个文件"
    else:
        broken = "、".join(str(item.get("path")) for item in result.get("files", []) if not item.get("valid"))
        message = f"快照 {filename.strip()} 校验未通过，损坏文件：{broken or '未知'}"
    return admin_html(admin_page(config, message, actor=actor, section="security"), status_code=200 if result.get("valid") else 409)


@router.post("/admin/security/backups/restore", response_class=HTMLResponse)
async def admin_security_restore_backup(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    filename: str = Form(""),
    confirm: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    """后台表单恢复快照。恢复会覆盖数据库和配置，因此仅限主管理员并要求二次确认。"""
    actor = require_admin(credentials)
    require_primary_admin(actor)
    config = load_config()
    require_admin_csrf(config, actor, csrf_token)
    require_admin_permission(actor, "backup:admin")
    if confirm.strip() != filename.strip() or not filename.strip():
        return admin_html(
            admin_page(config, "恢复未执行：请在确认框中完整输入要恢复的快照文件名", actor=actor, section="security"),
            status_code=400,
        )
    try:
        result = await asyncio.to_thread(restore_server_snapshot, filename.strip())
    except HTTPException as error:
        detail = error.detail
        message = detail.get("message", "恢复失败") if isinstance(detail, dict) else str(detail)
        audit_event("server_snapshot_restored", audit_actor(actor), success=False, metadata={"filename": filename.strip()})
        return admin_html(admin_page(config, f"快照恢复失败：{message}", actor=actor, section="security"), status_code=error.status_code)
    audit_event("server_snapshot_restored", audit_actor(actor), metadata={"filename": filename.strip(), "safetySnapshot": result["safetySnapshot"]})
    return admin_html(admin_page(
        load_config(),
        f"已恢复快照 {result['filename']}；恢复前的数据已另存为 {result['safetySnapshot']}",
        actor=actor,
        section="security",
    ))


@router.post("/admin/tasks/action", response_class=HTMLResponse)
async def admin_task_action(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    task_id: str = Form(""),
    action: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_admin_permission(actor, "tasks:write")
    config = load_config()
    require_admin_csrf(config, actor, csrf_token)
    tasks = load_tasks()
    task = tasks.get(task_id)
    if not task:
        return admin_html(admin_page(config, "任务不存在", actor=actor, section="tasks"), status_code=404)
    if action == "delete":
        # 删除是不可逆操作，单独处理并保留审计记录。
        tasks.pop(task_id, None)
        save_tasks(tasks)
        audit_event("admin_task_action", audit_actor(actor), metadata={"taskId": task_id, "action": action})
        return admin_html(admin_page(config, f"任务 {task_id} 已删除", actor=actor, section="tasks"))
    try:
        message = apply_task_action(task, action)
    except ValueError:
        return admin_html(admin_page(config, "不支持的任务操作", actor=actor, section="tasks"), status_code=400)
    save_tasks(tasks)
    task_log(task_id, message)
    audit_event("admin_task_action", audit_actor(actor), metadata={"taskId": task_id, "action": action})
    return admin_html(admin_page(config, message, actor=actor, section="tasks"))


@router.post("/admin/credentials/action", response_class=HTMLResponse)
async def admin_credential_action(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    credential_id: str = Form(""),
    action: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    """后台对阅微同步密钥执行验证、启用停用和删除，与模块端接口共用底层逻辑。"""
    actor = require_admin(credentials)
    require_admin_permission(actor, "tasks:write")
    config = load_config()
    require_admin_csrf(config, actor, csrf_token)
    stored = load_credentials()
    record = stored.get(credential_id.strip())
    if not record:
        return admin_html(admin_page(config, "同步密钥不存在", actor=actor, section="tasks"), status_code=404)
    if action == "verify":
        try:
            secret = decrypt_secret(str(record.get("secretEncrypted", "")))
        except Exception:
            secret = {}
        token = str(secret.get("token", "")) if isinstance(secret, dict) else ""
        if not token:
            update_credential_health(credential_id.strip(), False, "密钥密文无法解密")
            return admin_html(admin_page(config, "验证失败：密钥密文无法解密，请让模块重新上传", actor=actor, section="tasks"), status_code=409)
        success, detail = await verify_reamicro_secret(token, "")
        update_credential_health(credential_id.strip(), success, detail)
        audit_event("admin_credential_verified", audit_actor(actor), success=success, metadata={"credentialId": credential_id.strip()})
        return admin_html(admin_page(load_config(), f"{'验证通过' if success else '验证失败'}：{detail}", actor=actor, section="tasks"), status_code=200 if success else 409)
    if action == "toggle":
        record = dict(record)
        record["enabled"] = not bool(record.get("enabled", True))
        record["updatedAt"] = int(datetime.now(timezone.utc).timestamp() * 1000)
        stored[credential_id.strip()] = record
        save_credentials(stored)
        audit_event("admin_credential_toggled", audit_actor(actor), metadata={"credentialId": credential_id.strip(), "enabled": record["enabled"]})
        return admin_html(admin_page(load_config(), f"同步密钥已{'启用' if record['enabled'] else '停用'}", actor=actor, section="tasks"))
    if action == "delete":
        stored.pop(credential_id.strip(), None)
        save_credentials(stored)
        # 密钥删除后，依赖它的任务无法继续执行，一并暂停避免反复失败。
        tasks = load_tasks()
        affected = 0
        for task in tasks.values():
            if task_credential_id(task) == credential_id.strip() and task.get("enabled", True):
                task["enabled"] = False
                task["status"] = "paused"
                affected += 1
        if affected:
            save_tasks(tasks)
        audit_event("admin_credential_deleted", audit_actor(actor), metadata={"credentialId": credential_id.strip()})
        suffix = f"，并暂停了 {affected} 个关联任务" if affected else ""
        return admin_html(admin_page(load_config(), f"同步密钥 {credential_id.strip()} 已删除{suffix}", actor=actor, section="tasks"))
    return admin_html(admin_page(config, "不支持的密钥操作", actor=actor, section="tasks"), status_code=400)


@router.post("/admin/setup", response_class=HTMLResponse)
async def admin_setup(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    username: str = Form(""),
    password: str = Form(""),
    password_confirm: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials, allow_bootstrap=True)
    if not actor.get("needsSetup"):
        raise HTTPException(status_code=409, detail="主管理员已经完成初始化")
    require_admin_csrf(load_config(), actor, csrf_token)
    try:
        username = normalized_admin_name(username)
        validate_admin_password(password)
        if password != password_confirm:
            raise ValueError("两次输入的密码不一致")
        current = load_config()
        now = datetime.now(timezone.utc).isoformat()
        current["primaryAdmin"] = {
            "username": username,
            "passwordHash": password_hash(password),
            "createdAt": now,
            "updatedAt": now,
        }
        save_config(current)
        runtime.get_state_store().delete_admin_sessions_for_user(str(actor.get("username", "")))
        audit_event("primary_admin_initialized", f"admin:{username}", success=True)
        return HTMLResponse(
            "<!doctype html><html lang='zh-CN'><meta charset='utf-8'><title>初始化完成</title>"
            "<body style='font-family:system-ui;max-width:560px;margin:48px auto;padding:18px'>"
            "<h1>主管理员初始化完成</h1><p>初始环境变量密码已经停用。请关闭当前浏览器登录窗口，使用刚设置的新账号密码重新进入后台。</p>"
            "<p><a href='/admin'>重新进入后台</a></p></body></html>"
        )
    except ValueError as error:
        return HTMLResponse(admin_setup_page(str(error), actor=actor), status_code=400)


@router.post("/admin/settings", response_class=HTMLResponse)
async def admin_settings(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    server_id: str = Form(""),
    min_module_version: str = Form("2.0.0"),
    api_key: str = Form(""),
    allow_public: str | None = Form(None),
    host_account_allowlist: str = Form(""),
    account_name: str = Form(""),
    account_password: str = Form(""),
    remove_account: str = Form(""),
    features: str = Form(""),
    signing_public_key: str = Form(""),
    github_repository: str = Form("YGHFv/ReaMicro-Extend"),
    github_token: str = Form(""),
    auth_mode: str = Form(""),
    clear_api_key: str | None = Form(None),
    generate_api_key: str | None = Form(None),
    generate_secret_key: str | None = Form(None),
    clear_github_token: str | None = Form(None),
    github_include_prerelease: str | None = Form(None),
    release_sync_seconds: int = Form(1800),
    release_version_code: int = Form(0),
    server_snapshot_enabled: str | None = Form(None),
    server_snapshot_seconds: int = Form(86400),
    server_snapshot_retention: int = Form(30),
    module_upload_enabled: str | None = Form(None),
    module_upload_allowlist: str = Form(""),
    # 后台用复选框提交，未勾选任何类型时由 module_upload_kinds() 回退到默认集合。
    module_upload_kinds: list[str] = Form(default=[]),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    current = load_config()
    require_admin_csrf(current, actor, csrf_token)
    require_admin_permission(actor, "settings:write")
    if (generate_api_key is not None or generate_secret_key is not None or clear_api_key is not None or clear_github_token is not None) and actor.get("role") != "primary":
        require_admin_permission(actor, "security:write")
    generated_api_key = generate_long_secret() if generate_api_key is not None else ""
    generated_secret_key = ""
    secret_key_message = ""
    if generate_secret_key is not None:
        if current.get("secretKey"):
            secret_key_message = "服务器加密密钥已存在，本次未覆盖，避免历史阅微凭据失效"
        else:
            generated_secret_key = generate_long_secret()
    selected_auth_mode = auth_mode.strip() if auth_mode.strip() in {"public", "api_key", "account", "host_account_allowlist"} else infer_auth_mode(current)
    next_config = {
        "serverId": server_id.strip() or current["serverId"],
        "minModuleVersion": min_module_version.strip() or current["minModuleVersion"],
        "apiKey": "" if clear_api_key is not None else (generated_api_key or api_key or current.get("apiKey", "")),
        "secretKey": generated_secret_key or current.get("secretKey", "") or runtime.SECRET_KEY,
        "allowPublic": selected_auth_mode == "public",
        "authMode": selected_auth_mode,
        "hostAccountAllowlist": ([item.strip() for item in host_account_allowlist.splitlines() if item.strip()] if selected_auth_mode == "host_account_allowlist" else current.get("hostAccountAllowlist", [])),
        "features": [item.strip() for item in features.replace("\r", "\n").replace("\n", ",").split(",") if item.strip()],
        # 公钥从文本域提交，粘贴时常带换行和空格，去掉后再保存。
        "signingPublicKey": "".join(signing_public_key.split()),
        "githubRepository": github_repository.strip() or current["githubRepository"],
        "githubToken": "" if clear_github_token is not None else (github_token or current.get("githubToken", "")),
        "githubIncludePrerelease": github_include_prerelease is not None and str(github_include_prerelease).lower() not in {"", "0", "false"},
        "releaseSyncSeconds": release_sync_seconds,
        "releaseVersionCode": release_version_code,
        "serverSnapshotEnabled": server_snapshot_enabled is not None and str(server_snapshot_enabled).lower() not in {"", "0", "false"},
        "serverSnapshotSeconds": server_snapshot_seconds,
        "serverSnapshotRetention": server_snapshot_retention,
        "primaryAdmin": current.get("primaryAdmin", {}),
        "adminAccounts": current.get("adminAccounts", {}),
        "moduleUploadEnabled": module_upload_enabled is not None and str(module_upload_enabled).lower() not in {"", "0", "false"},
        "moduleUploadAllowlist": [item.strip() for item in module_upload_allowlist.replace(",", "\n").splitlines() if item.strip()],
        "moduleUploadKinds": module_upload_kinds_form(module_upload_kinds),
    }
    if generated_api_key:
        records = [item for item in current.get("apiKeyRecords", []) if isinstance(item, dict) and item.get("enabled", True)]
        records.append({
            "id": "key_" + secrets.token_hex(8),
            "name": "后台生成密钥",
            "digest": api_key_digest(generated_api_key),
            "permissions": ["read", "write"],
            "enabled": True,
            "createdAt": int(datetime.now(timezone.utc).timestamp() * 1000),
        })
        next_config["apiKeyRecords"] = records
    elif clear_api_key is not None:
        next_config["apiKeyRecords"] = []
    else:
        next_config["apiKeyRecords"] = current.get("apiKeyRecords", [])
    accounts = dict(current.get("accounts", {}))
    if account_name.strip() and account_password:
        accounts[account_name.strip()] = password_hash(account_password)
    if remove_account.strip():
        accounts.pop(remove_account.strip(), None)
    next_config["accounts"] = accounts
    saved = save_config(next_config)
    audit_event("admin_settings_updated", audit_actor(actor), success=True, metadata={"serverId": saved.get("serverId")})
    notices = []
    if generated_api_key:
        notices.append(f"新 API Key：{generated_api_key}")
    if generated_secret_key:
        notices.append(f"新服务器加密密钥：{generated_secret_key}")
    if secret_key_message:
        notices.append(secret_key_message)
    return HTMLResponse(admin_page(saved, "设置已保存", actor=actor, secret_notice="\n".join(notices), section="settings"))


@router.post("/admin/admins/create", response_class=HTMLResponse)
async def admin_create_subadmin(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    username: str = Form(""),
    password: str = Form(""),
    # 后台用复选框提交多个同名字段，同时兼容旧的逗号分隔单值写法。
    permissions: list[str] = Form(default=["settings:write", "packages:write", "tasks:write"]),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    current = load_config()
    require_admin_csrf(current, actor, csrf_token)
    try:
        username = normalized_admin_name(username)
        if username == str(current.get("primaryAdmin", {}).get("username", "")):
            raise ValueError("子管理员用户名不能与主管理员相同")
        admins = dict(current.get("adminAccounts", {}))
        if username in admins:
            raise ValueError("该子管理员已经存在")
        generated = not password
        password = password or generate_long_secret(24)
        validate_admin_password(password)
        now = datetime.now(timezone.utc).isoformat()
        admins[username] = {
            "passwordHash": password_hash(password),
            "enabled": True,
            "createdAt": now,
            "updatedAt": now,
            "createdBy": actor["username"],
            "permissions": normalized_admin_permissions(permissions),
        }
        current["adminAccounts"] = admins
        saved = save_config(current)
        audit_event("subadmin_created", audit_actor(actor), success=True, metadata={"username": username})
        notice = f"子管理员 {username} 的初始密码：{password}" if generated else ""
        return HTMLResponse(admin_page(saved, f"子管理员 {username} 已创建", actor=actor, secret_notice=notice, section="security"))
    except ValueError as error:
        return HTMLResponse(admin_page(current, str(error), actor=actor, section="security"), status_code=400)


@router.post("/admin/admins/reset", response_class=HTMLResponse)
async def admin_reset_subadmin(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    username: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    current = load_config()
    require_admin_csrf(current, actor, csrf_token)
    admins = dict(current.get("adminAccounts", {}))
    record = admins.get(username)
    if not isinstance(record, dict):
        return HTMLResponse(admin_page(current, "子管理员不存在", actor=actor, section="security"), status_code=404)
    password = generate_long_secret(24)
    record = dict(record)
    record["passwordHash"] = password_hash(password)
    record["updatedAt"] = datetime.now(timezone.utc).isoformat()
    admins[username] = record
    current["adminAccounts"] = admins
    saved = save_config(current)
    runtime.get_state_store().delete_admin_sessions_for_user(username)
    audit_event("subadmin_password_reset", audit_actor(actor), success=True, metadata={"username": username})
    return HTMLResponse(admin_page(saved, f"子管理员 {username} 的密码已重置", actor=actor, secret_notice=f"新密码：{password}", section="security"))


@router.post("/admin/admins/toggle", response_class=HTMLResponse)
async def admin_toggle_subadmin(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    username: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    current = load_config()
    require_admin_csrf(current, actor, csrf_token)
    admins = dict(current.get("adminAccounts", {}))
    record = admins.get(username)
    if not isinstance(record, dict):
        return HTMLResponse(admin_page(current, "子管理员不存在", actor=actor, section="security"), status_code=404)
    record = dict(record)
    record["enabled"] = not bool(record.get("enabled", True))
    record["updatedAt"] = datetime.now(timezone.utc).isoformat()
    admins[username] = record
    current["adminAccounts"] = admins
    saved = save_config(current)
    if not record["enabled"]:
        runtime.get_state_store().delete_admin_sessions_for_user(username)
    audit_event("subadmin_toggled", audit_actor(actor), success=True, metadata={"username": username, "enabled": record["enabled"]})
    state_text = "启用" if record["enabled"] else "停用"
    return HTMLResponse(admin_page(saved, f"子管理员 {username} 已{state_text}", actor=actor, section="security"))


@router.post("/admin/admins/delete", response_class=HTMLResponse)
async def admin_delete_subadmin(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    username: str = Form(""),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_primary_admin(actor)
    current = load_config()
    require_admin_csrf(current, actor, csrf_token)
    admins = dict(current.get("adminAccounts", {}))
    if username not in admins:
        return HTMLResponse(admin_page(current, "子管理员不存在", actor=actor, section="security"), status_code=404)
    admins.pop(username, None)
    current["adminAccounts"] = admins
    saved = save_config(current)
    runtime.get_state_store().delete_admin_sessions_for_user(username)
    audit_event("subadmin_deleted", audit_actor(actor), success=True, metadata={"username": username})
    return HTMLResponse(admin_page(saved, f"子管理员 {username} 已删除", actor=actor, section="security"))


@router.post("/admin/sync", response_class=HTMLResponse)
async def admin_sync(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    require_admin_csrf(load_config(), actor, csrf_token)
    require_admin_permission(actor, "module:sync")
    try:
        await asyncio.to_thread(sync_module_release)
        return HTMLResponse(admin_page(load_config(), "模块 Release 已同步", actor=actor, section="settings"))
    except Exception as error:
        return HTMLResponse(admin_page(load_config(), f"同步失败：{error}", actor=actor, section="settings"), status_code=502)


@router.post("/admin/packages/upload", response_class=HTMLResponse)
async def admin_upload_package(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    kind: str = Form(...),
    package_id: str = Form(""),
    version: str = Form(""),
    content_id: str = Form(""),
    name: str = Form(""),
    aliases: str = Form(""),
    signature: str = Form(""),
    status_value: str = Form("published"),
    channel: str = Form("stable"),
    dependencies: str = Form(""),
    payload: UploadFile = File(...),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    current_config = load_config()
    require_admin_csrf(current_config, actor, csrf_token)
    require_admin_permission(actor, "packages:write")
    if kind not in runtime.PACKAGE_KINDS:
        raise HTTPException(status_code=400, detail="不支持的内容包类型")
    filename = safe_payload_filename(payload.filename or "payload.bin")
    body = await payload.read()
    if not body or len(body) > 50 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="内容文件为空或超过 50 MB")
    validate_package_payload(kind, filename, body)
    metadata = infer_package_metadata(kind, filename, body)
    package_id, existing_manifest = resolve_package_identity(kind, package_id, content_id, metadata)
    if existing_manifest is not None and not version.strip():
        version = next_package_version(existing_manifest.get("version"))
    version = safe_package_segment(version or "1.0.0")
    stable_id = normalize_content_id(content_id, str(existing_manifest.get("contentId", "")) if existing_manifest and existing_manifest.get("contentId") else package_id)
    normalized_status = status_value.strip().lower() or "published"
    if normalized_status not in {"draft", "testing", "published", "unpublished"}:
        raise HTTPException(status_code=400, detail="内容包状态无效")
    normalized_channel = channel.strip().lower() or "stable"
    if normalized_channel not in {"stable", "beta", "nightly"}:
        raise HTTPException(status_code=400, detail="内容包发布渠道无效")
    try:
        dependency_items = json.loads(dependencies) if dependencies.strip() else []
    except ValueError:
        raise HTTPException(status_code=400, detail="依赖必须是 JSON 数组")
    if not isinstance(dependency_items, list):
        raise HTTPException(status_code=400, detail="依赖必须是 JSON 数组")
    dependency_items = normalize_package_dependencies(dependency_items, kind, package_id)
    merged_domains = sorted({
        domain
        for domain in (
            normalize_source_domain(item)
            for item in list(metadata.get("domains", [])) + list(existing_manifest.get("domains", []) if existing_manifest else [])
        )
        if domain
    })
    manifest = persist_package_payload(
        kind=kind,
        package_id=package_id,
        version=version,
        content_id=stable_id,
        filename=filename,
        body=body,
        name=_metadata_text(name) or (str(existing_manifest.get("name", "")) if existing_manifest and not metadata.get("explicitName") else "") or str(metadata.get("name") or package_id),
        description=str(existing_manifest.get("description", "")) if existing_manifest and existing_manifest.get("description") else "后台上传内容包",
        aliases=merge_package_aliases(existing_manifest.get("aliases", []) if existing_manifest else [], metadata.get("identity", []), aliases),
        domains=merged_domains,
        status_value=normalized_status,
        channel=normalized_channel,
        dependencies=dependency_items,
        signature=signature,
        owner=str(existing_manifest.get("uploadOwner", "")) if existing_manifest else "",
    )
    audit_event("package_uploaded", audit_actor(actor), success=True, metadata={"kind": kind, "packageId": package_id, "version": version, "status": normalized_status})
    return admin_html(admin_page(load_config(), f"已保存 {_admin_kind_label(kind)}“{manifest['name']}” · {package_id} · {version}", actor=actor, section=kind))


@router.post("/admin/tasks/create", response_class=HTMLResponse)
async def admin_create_task(
    credentials: HTTPBasicCredentials | None = Depends(basic_security),
    task_type: str = Form(...),
    interval_seconds: int = Form(86_400),
    request_url: str = Form(""),
    request_method: str = Form("POST"),
    request_headers: str = Form("{}"),
    request_body: str = Form(""),
    credential_id: str = Form(""),
    duration_minutes: int = Form(30),
    recent_limit: int = Form(1),
    book_limit: int = Form(1),
    time_of_day: str = Form("00:00"),
    owner: str = Form("admin"),
    csrf_token: str = Form(""),
) -> HTMLResponse:
    actor = require_admin(credentials)
    current_config = load_config()
    require_admin_csrf(current_config, actor, csrf_token)
    require_admin_permission(actor, "tasks:write")
    try:
        task_type = task_type.strip()
        if task_type not in {"http", "yeshe_checkin", "yeshe_draw_card", "cloud_auto_read"}:
            raise ValueError("不支持的任务类型")
        owner = owner.strip() or "admin"
        headers = json.loads(request_headers or "{}")
        if not isinstance(headers, dict):
            raise ValueError("headers must be object")
        request_value = {
            "url": request_url.strip(),
            "method": request_method.strip().upper(),
            "headers": headers,
            "body": request_body,
            "credentialId": credential_id.strip(),
            "durationMinutes": max(1, min(duration_minutes, 720)),
            "recentLimit": max(1, min(recent_limit, 20)),
            "bookLimit": max(1, min(book_limit, 10)),
        }
        if task_type.strip() == "cloud_auto_read" and request_body.strip():
            decoded_body = json.loads(request_body)
            if not isinstance(decoded_body, list):
                raise ValueError("自定义图书必须是 JSON 数组")
            request_value["books"] = decoded_body
        if task_type != "http":
            credential = load_credentials().get(credential_id.strip())
            if not credential:
                raise ValueError("阅微凭据不存在")
            # 归属以密钥为准。后台表单的 owner 默认是 admin，而模块上传的密钥归属是
            # host:<阅微账号>，拿表单值去比对会让任何选了密钥的提交都失败。
            credential_owner = str(credential.get("owner", "")).strip()
            if credential_owner:
                owner = credential_owner
        task_id = "task_" + secrets.token_hex(10)
        now = int(datetime.now(timezone.utc).timestamp() * 1000)
        tasks = load_tasks()
        new_task = {
            "id": task_id,
            "owner": owner,
            "taskType": task_type,
            "credentialId": credential_id.strip(),
            "schedule": {
                "intervalSeconds": max(interval_seconds, 60),
                "timeOfDay": normalized_time_of_day(time_of_day) if task_type in {"yeshe_checkin", "yeshe_draw_card", "cloud_auto_read"} else "",
                "timezoneOffsetMinutes": 480,
            },
            "requestEncrypted": encrypt_secret(request_value),
            "status": "scheduled",
            "enabled": True,
            "createdAt": now,
            "nextRunAt": now,
            "runCount": 0,
            "maxRetries": 3,
            "consecutiveFailures": 0,
        }
        duplicate = find_duplicate_task(tasks, new_task)
        if duplicate:
            task_id = duplicate[0]
            previous = duplicate[1]
            new_task["id"] = task_id
            new_task["createdAt"] = previous.get("createdAt", now)
            new_task["updatedAt"] = now
            for field in ("executionHistory", "lastExecution", "runCount", "consecutiveFailures", "dailyCounterDate", "dailyCounter", "dailyReadDate", "dailyReadMinutes", "bookRotation", "lastCheckinDate", "lastCheckinAt", "claimDueAt", "claimRetryCount", "claimFinalAttemptDate", "claimCompletedDate", "claimFinalFailedDate", "lastClaimAt"):
                if field in previous:
                    new_task[field] = previous[field]
        tasks[task_id] = new_task
        save_tasks(tasks)
        task_log(task_id, "管理员更新任务" if duplicate else "管理员创建任务")
        return HTMLResponse(admin_page(load_config(), f"任务 {task_id} {'已更新' if duplicate else '已创建'}", actor=actor, section="tasks"))
    except Exception as error:
        return HTMLResponse(admin_page(load_config(), f"创建任务失败：{error}", actor=actor, section="tasks"), status_code=400)


@router.get("/admin/tasks/{task_id}/logs", response_class=HTMLResponse)
async def admin_task_logs(task_id: str, actor: dict[str, Any] = Depends(admin_actor)) -> HTMLResponse:
    task = load_tasks().get(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="任务不存在")
    esc = lambda value: html.escape(str(value), quote=True)
    history = task.get("executionHistory") if isinstance(task.get("executionHistory"), list) else []
    rows = []
    for item in history[-100:][::-1]:
        result = str(item.get("result", ""))
        rows.append(
            f"<tr><td>{esc(_admin_time_label(item.get('finishedAt'), '未记录'))}"
            f"<small>{esc(_admin_time_detail(item.get('finishedAt'), '未记录'))}</small></td>"
            f"<td><span class='status status-{esc(result)}'>{esc(_admin_result_label(result))}</span></td>"
            f"<td>{esc(_admin_duration_label(item.get('durationMs')))}</td>"
            f"<td>{esc(item.get('message', '') or '无执行说明')}</td></tr>"
        )
    table = "".join(rows) or "<tr><td colspan='4' class='empty'>暂无执行记录</td></tr>"
    summary_rows = "".join(
        f"<tr><th style='width:150px'>{esc(label)}</th><td>{esc(value)}</td></tr>"
        for label, value in (
            ("任务类型", _admin_task_label(task.get("taskType", ""))),
            ("任务 ID", task_id),
            ("来源账号", _admin_owner_label(task.get("owner", ""))),
            ("同步密钥", task_credential_id(task) or "未关联"),
            ("运行状态", _admin_task_status_label(task.get("status", ""))),
            ("任务详情", _admin_task_detail(task)),
            ("下次执行", _admin_time_detail(task.get("nextRunAt"))),
            ("累计执行", f"{bounded_config_int(task.get('runCount', 0), 0, 0)} 次"),
        )
    )
    # task_log() 写的逐行日志此前只有 /v1/tasks/{id}/logs 能取到，后台完全看不到，
    # 排查失败原因时只能看到执行历史里的一句汇总。这里一并展示。
    log_rows = []
    log_path = runtime.TASK_LOG_ROOT / f"{task_id}.log"
    if log_path.is_file():
        for line in log_path.read_text(encoding="utf-8").splitlines()[-200:][::-1]:
            try:
                entry = json.loads(line)
            except ValueError:
                continue
            level = str(entry.get("level", "INFO"))
            level_class = {"ERROR": "failed", "WARN": "warning"}.get(level, "success")
            log_rows.append(
                f"<tr><td>{esc(_admin_time_label(entry.get('at'), '未记录'))}"
                f"<small>{esc(_admin_time_detail(entry.get('at'), '未记录'))}</small></td>"
                f"<td><span class='status status-{level_class}'>{esc(level)}</span></td>"
                f"<td>{esc(entry.get('message', '') or '无内容')}</td></tr>"
            )
    log_table = "".join(log_rows) or "<tr><td colspan='3' class='empty'>暂无运行日志</td></tr>"
    body = (
        f"<div class='table-wrap' style='margin-bottom:18px'><table style='min-width:auto'><tbody>{summary_rows}</tbody></table></div>"
        f"<div class='panel'><h2>执行记录</h2><div class='table-wrap'><table><thead><tr><th>完成时间</th><th>结果</th><th>耗时</th><th>执行说明</th></tr></thead><tbody>{table}</tbody></table></div></div>"
        f"<div class='panel'><h2>运行日志</h2><p class='muted'>最近 200 条逐行日志，最新的在前。</p>"
        f"<div class='table-wrap'><table><thead><tr><th>时间</th><th>级别</th><th>内容</th></tr></thead><tbody>{log_table}</tbody></table></div></div>"
    )
    return HTMLResponse(_admin_shell(
        "执行日志 · " + _admin_task_label(task.get("taskType", "")),
        body,
        load_config(),
        actor,
        section="tasks",
        eyebrow="自动化",
        description=f"最近 {min(len(history), 100)} 条执行记录，最新的排在最前。",
        back_href="/admin/tasks",
        back_label="返回云端任务",
    ))
