"""后台各分区的 HTML 拼装。

admin_page 是分区分发器：概览、全部内容、按类型分区、云端任务、服务器设置、
子管理员与安全。每个分区的表格与表单拆成独立函数，便于单独测试与改动。

历史坑：内容表格曾被误缩进在概览分支里，导致除概览外六个分区都是空页——
所以 test_admin_pages 专门断言每个分区都要渲染出 <table>。
"""
import html
import json
from datetime import datetime, timezone
from typing import Any

from app import runtime
from app.admin.format import (
    _admin_datetime,
    _admin_digest_label,
    _admin_duration_label,
    _admin_size_label,
    _admin_time_detail,
    _admin_time_label,
    admin_section_path,
)
from app.admin.layout import _admin_layout, admin_csrf_token
from app.backups import list_server_snapshots
from app.config_store import (
    api_key_auth_configured,
    bounded_config_int,
    infer_auth_mode,
    load_config,
    module_upload_kinds,
)
from app.crypto import api_key_digest, decrypt_secret
from app.labels import (
    _admin_action_label,
    _admin_channel_label,
    _admin_health_label,
    _admin_kind_label,
    _admin_metadata_label,
    _admin_owner_label,
    _admin_result_label,
    _admin_status_label,
    _admin_task_label,
    _admin_task_status_label,
)
from app.packages import (
    _admin_package_records,
    package_dependency_status,
    package_match_domains,
)
from app.scheduler import task_credential_id
from app.security import API_KEY_PERMISSIONS
from app.security import ADMIN_ASSIGNABLE_PERMISSIONS as SECURITY_ASSIGNABLE_PERMISSIONS
from app.state import (
    credential_public,
    merge_duplicate_credentials,
    merge_duplicate_tasks,
    canonical_owner_of,
    load_credentials,
    load_notifications,
    load_presence,
    load_tasks,
)


ADMIN_PERMISSION_LABELS = {
    "settings:write": "修改服务器设置",
    "packages:write": "管理内容包",
    "tasks:write": "管理云端任务",
    "module:sync": "同步模块 Release",
    "audit:read": "查看审计日志",
    "backup:admin": "创建和校验快照",
    "security:write": "轮换密钥等安全操作",
}

# 权限键的事实来源在 app.security；这里只负责配中文说明。
ADMIN_ASSIGNABLE_PERMISSIONS = [
    (key, ADMIN_PERMISSION_LABELS.get(key, key)) for key in SECURITY_ASSIGNABLE_PERMISSIONS
]




def api_key_public_records(config: dict[str, Any]) -> list[dict[str, Any]]:
    records = []
    for item in config.get("apiKeyRecords", []):
        if not isinstance(item, dict):
            continue
        records.append({key: item.get(key) for key in ("id", "name", "permissions", "enabled", "createdAt", "lastUsedAt", "revokedAt")})
    return sorted(records, key=lambda value: bounded_config_int(value.get("createdAt", 0), 0, 0), reverse=True)


def _admin_task_detail(task: dict[str, Any]) -> str:
    """将任务内部配置转换为后台可读的简短说明。"""
    task_type = str(task.get("taskType", ""))
    schedule = task.get("schedule") if isinstance(task.get("schedule"), dict) else {}
    time_of_day = str(schedule.get("timeOfDay", "")).strip()
    parts: list[str] = []
    if time_of_day:
        parts.append(f"执行时间：每日 {time_of_day}")
    try:
        request = decrypt_secret(str(task.get("requestEncrypted", ""))) if task.get("requestEncrypted") else {}
    except Exception:
        request = {}
    if not isinstance(request, dict):
        request = {}
    if task_type == "yeshe_checkin":
        parts.append("奖励领取：签到后 8 小时（周奖励，每周一次）")
        if task.get("lastCheckinAt"):
            parts.append(f"上次签到：{_admin_time_label(task.get('lastCheckinAt'))}")
        if task.get("lastClaimAt"):
            parts.append(f"上次领取：{_admin_time_label(task.get('lastClaimAt'))}")
        claimed_date = str(task.get("claimCompletedDate", ""))
        if claimed_date:
            parts.append(f"奖励领取日期：{claimed_date}")
        retry_count = bounded_config_int(task.get("claimRetryCount", 0), 0, 0)
        if retry_count:
            parts.append(f"领取重试：已重试 {retry_count} 次")
    elif task_type == "yeshe_draw_card":
        parts.append(f"每日抽卡：{int(request.get('dailyLimit', 1) or 1)} 次")
    elif task_type == "cloud_auto_read":
        parts.append(f"阅读时长：{int(request.get('durationMinutes', 30) or 30)} 分钟")
        books = request.get("books") if isinstance(request.get("books"), list) else []
        if books:
            names = [str(item.get("name") or item.get("bookName") or item.get("title") or item.get("bookId") or "未命名") for item in books if isinstance(item, dict)]
            parts.append("阅读图书：" + "、".join(names[:3]) + (" 等" if len(names) > 3 else ""))
        else:
            parts.append(f"阅读图书：最近阅读记录（{int(request.get('recentLimit', 1) or 1)} 本）")
    elif task_type == "http":
        parts.append(f"请求：{str(request.get('method', 'GET')).upper()} {str(request.get('url', ''))[:80]}")
    return "；".join(parts) or "未配置详细参数"


def _admin_package_table(records: list[dict[str, Any]], can_write: bool, query: str = "", csrf_token: str = "") -> str:
    esc = lambda value: html.escape(str(value), quote=True)
    needle = query.strip().lower()
    rows: list[str] = []
    for item in sorted(records, key=lambda value: (str(value.get("kind", "")), str(value.get("packageId", "")))):
        haystack = " ".join(str(item.get(key, "")) for key in ("kind", "packageId", "contentId", "name", "version", "aliases")).lower()
        if needle and needle not in haystack:
            continue
        kind = str(item.get("kind", "")); package_id = str(item.get("packageId", ""))
        dependency = "依赖满足" if item.get("dependenciesSatisfied", True) else "缺少必需依赖"
        status_value = str(item.get("status", "published"))
        source = _admin_owner_label(item.get("uploadOwner", "")) if item.get("uploadOwner") else "后台上传"
        domains = "、".join(sorted(package_match_domains(item)))
        if can_write:
            actions = (
                f"<a class='button subtle' href='/admin/packages/{esc(kind)}/{esc(package_id)}/edit'>编辑</a> "
                f"<a class='button subtle' href='/admin/packages/{esc(kind)}/{esc(package_id)}/preview'>预览</a> "
                f"<a class='button subtle' href='/admin/packages/{esc(kind)}/{esc(package_id)}/history'>历史</a> "
                f"<form class='inline' method='post' action='/admin/packages/{esc(kind)}/{esc(package_id)}/delete'>"
                f"<input type='hidden' name='csrf_token' value='{esc(csrf_token)}'>"
                f"<button class='button subtle danger' type='submit'>删除</button></form>"
            )
        else:
            actions = f"<a class='button subtle' href='/admin/packages/{esc(kind)}/{esc(package_id)}/preview'>预览</a>"
        rows.append(
            f"<tr><td>{esc(_admin_kind_label(kind))}<small>{esc(source)}</small></td>"
            f"<td><strong>{esc(item.get('name', package_id))}</strong>"
            f"<small>包 ID {esc(package_id)}{(' · ' + esc(domains)) if domains else ''}</small></td>"
            f"<td>{esc(item.get('version', ''))}<small>{esc(_admin_time_label(item.get('buildTime'), '未记录'))}</small></td>"
            f"<td><span class='status status-{esc(status_value)}'>{esc(_admin_status_label(status_value))}</span></td>"
            f"<td>{esc(_admin_channel_label(item.get('channel', 'stable')))}<small>{esc(dependency)}</small></td>"
            f"<td class='actions'>{actions}</td></tr>"
        )
    return "".join(rows) or "<tr><td colspan='6' class='empty'>没有匹配的内容包</td></tr>"


def _admin_release_panel() -> str:
    """模块 Release 同步状态。此前后台看不到同步结果和失败原因。"""
    esc = lambda value: html.escape(str(value), quote=True)
    metadata: dict[str, Any] = {}
    release_path = runtime.RELEASE_ROOT / "latest.json"
    if release_path.is_file():
        try:
            loaded = json.loads(release_path.read_text(encoding="utf-8"))
            metadata = loaded if isinstance(loaded, dict) else {}
        except (OSError, ValueError):
            metadata = {}
    sync_status: dict[str, Any] = {}
    if runtime.RELEASE_STATUS_PATH.is_file():
        try:
            loaded = json.loads(runtime.RELEASE_STATUS_PATH.read_text(encoding="utf-8"))
            sync_status = loaded if isinstance(loaded, dict) else {}
        except (OSError, ValueError):
            sync_status = {}
    apk_path = runtime.RELEASE_ROOT / "latest.apk"
    apk_note = _admin_size_label(apk_path.stat().st_size) if apk_path.is_file() else "尚未下载"
    success = sync_status.get("success")
    status_class = "success" if success else ("failed" if success is False else "unverified")
    status_text = "同步成功" if success else ("同步失败" if success is False else "尚未同步")
    rows = "".join(
        f"<tr><th style='width:170px'>{esc(label)}</th><td>{value}</td></tr>"
        for label, value in (
            ("当前版本", esc(metadata.get("versionName", "") or "未同步")),
            ("发布渠道", esc(_admin_channel_label(metadata.get("channel", "")) if metadata.get("channel") else "未同步")),
            ("发布时间", esc(_admin_time_label(metadata.get("publishedAt"), "未记录"))),
            ("APK 文件", esc(apk_note)),
            ("同步状态", f"<span class='status status-{status_class}'>{esc(status_text)}</span>"),
            ("最近同步", esc(_admin_time_detail(sync_status.get("at") or sync_status.get("checkedAt"), "未记录"))),
            ("同步说明", esc(sync_status.get("message", "") or "无附加说明")),
        )
    )
    return (
        f"<div class='panel'><h2>模块 Release 同步</h2>"
        f"<p class='muted'>服务器定时从 GitHub 拉取模块 Release 供客户端更新。"
        f"若仓库只发布预发布 Release，必须勾选下方的“包含预发布 Release”，否则 GitHub 找不到正式版会导致同步失败。</p>"
        f"<div class='table-wrap'><table style='min-width:auto'><tbody>{rows}</tbody></table></div></div>"
    )


def _admin_settings_content(config: dict[str, Any], actor: dict[str, Any], csrf_html: str) -> str:
    """服务器设置分区。所有可配置项都在界面上可见可改，不再依赖隐藏字段保值。"""
    esc = lambda value: html.escape(str(value), quote=True)
    newline = "\n"
    selected_mode = str(config.get("authMode") or infer_auth_mode(config))
    mode_options = "".join(
        f"<option value='{mode}' {'selected' if mode == selected_mode else ''}>{label}</option>"
        for mode, label in (
            ("public", "公开访问"),
            ("api_key", "API Key"),
            ("account", "独立账号密码"),
            ("host_account_allowlist", "阅微账号白名单"),
        )
    )
    accounts = config.get("accounts", {})
    account_note = (
        f"当前已配置独立账号：{esc('、'.join(sorted(accounts.keys())))}"
        if isinstance(accounts, dict) and accounts
        else "当前没有独立账号"
    )
    api_key_note = "已配置 API Key" if api_key_auth_configured(config) else "尚未配置任何 API Key"
    kind_checkboxes = "".join(
        f"<label><input type='checkbox' name='module_upload_kinds' value='{esc(kind)}' "
        f"{'checked' if kind in config.get('moduleUploadKinds', []) else ''}> {esc(_admin_kind_label(kind))}</label>"
        for kind in sorted(runtime.MODULE_UPLOAD_DEFAULT_KINDS)
    )
    head = (
        "<div class='page-head'><div><p class='eyebrow'>系统</p><h1>服务器设置</h1>"
        "<p class='muted'>认证模式为单选，只有当前选中的认证方式会对 API 生效。未选中的模式配置会保留但不参与认证。</p></div></div>"
    )
    auth_blocks = (
        f"<div data-auth-section='api_key'>"
        f"<p class='muted' style='margin:0 0 10px'>{esc(api_key_note)}。明细管理请到“子管理员与安全”。</p>"
        f"<label>API Key（留空保持当前值）<input type='password' name='api_key' autocomplete='new-password'></label>"
        f"<label><input type='checkbox' name='generate_api_key'> 生成新的随机 API Key（保存后显示一次）</label>"
        f"<label><input type='checkbox' name='clear_api_key'> 清空全部 API Key</label></div>"
        f"<div data-auth-section='account'>"
        f"<p class='muted' style='margin:0 0 10px'>{account_note}。</p>"
        f"<div class='grid-2'><label>新增或更新账号名<input name='account_name' autocomplete='off'></label>"
        f"<label>账号密码<input type='password' name='account_password' autocomplete='new-password'></label></div>"
        f"<label>要删除的账号名<input name='remove_account' autocomplete='off'></label></div>"
        f"<div data-auth-section='host_account_allowlist'>"
        f"<label>阅微账号白名单（每行一个阅微账号 ID）"
        f"<textarea name='host_account_allowlist'>{esc(newline.join(config.get('hostAccountAllowlist', [])))}</textarea></label></div>"
    )
    form = (
        f"<div class='panel'><h2>基础与认证</h2><form method='post' action='/admin/settings'>{csrf_html}"
        f"<div class='grid-2'>"
        f"<label>服务器 ID<input name='server_id' value='{esc(config.get('serverId', ''))}'></label>"
        f"<label>最低模块版本<input name='min_module_version' value='{esc(config.get('minModuleVersion', ''))}'></label>"
        f"<label>认证类型<select name='auth_mode' id='auth-mode'>{mode_options}</select></label>"
        f"<label>功能列表（逗号分隔）<input name='features' value='{esc(','.join(config.get('features', [])))}'></label>"
        f"</div>{auth_blocks}"
        f"<fieldset><legend>模块 Release 同步</legend>"
        f"<div class='grid-2'>"
        f"<label>GitHub 仓库<input name='github_repository' value='{esc(config.get('githubRepository', ''))}'></label>"
        f"<label>同步间隔（秒，最小 300）<input name='release_sync_seconds' type='number' min='300' value='{esc(config.get('releaseSyncSeconds', 1800))}'></label>"
        f"<label>GitHub 令牌（留空保持当前值）<input type='password' name='github_token' autocomplete='new-password'></label>"
        f"<label>最低可接受 versionCode<input name='release_version_code' type='number' min='0' value='{esc(config.get('releaseVersionCode', 0))}'></label>"
        f"</div>"
        f"<label><input type='checkbox' name='github_include_prerelease' {'checked' if config.get('githubIncludePrerelease') else ''}> "
        f"包含预发布 Release（仓库只发预发布时必须勾选）</label>"
        f"<label><input type='checkbox' name='clear_github_token'> 清空已保存的 GitHub 令牌</label>"
        f"</fieldset>"
        f"<fieldset><legend>服务器快照</legend>"
        f"<label><input type='checkbox' name='server_snapshot_enabled' {'checked' if config.get('serverSnapshotEnabled', True) else ''}> 启用定时快照</label>"
        f"<div class='grid-2'>"
        f"<label>快照间隔（秒，最小 3600）<input name='server_snapshot_seconds' type='number' min='3600' value='{esc(config.get('serverSnapshotSeconds', 86400))}'></label>"
        f"<label>保留份数（最少 3）<input name='server_snapshot_retention' type='number' min='3' value='{esc(config.get('serverSnapshotRetention', 30))}'></label>"
        f"</div></fieldset>"
        f"<fieldset><legend>用户模块上传</legend>"
        f"<p class='muted' style='margin:0;font-size:12px'>启用后，白名单内的阅微账号可以从模块上传书源和关联源。"
        f"服务器已存在名称和域名相同的源时只建立关联，不会被模块覆盖。</p>"
        f"<label><input type='checkbox' name='module_upload_enabled' {'checked' if config.get('moduleUploadEnabled') else ''}> 启用模块上传内容库</label>"
        f"<label>上传 ID 白名单（阅微账号 ID，每行一个）"
        f"<textarea name='module_upload_allowlist' placeholder='例如&#10;10086&#10;20250'>{esc(newline.join(config.get('moduleUploadAllowlist', [])))}</textarea></label>"
        f"<div><p class='muted' style='margin:0 0 8px;font-size:12px'>允许上传的类型（全部取消则回退到默认的书源和关联源）</p>{kind_checkboxes}</div>"
        f"</fieldset>"
        f"<fieldset><legend>内容包签名</legend>"
        f"<label>Ed25519 签名公钥（Base64，留空表示不校验签名）"
        f"<textarea name='signing_public_key' style='min-height:70px'>{esc(config.get('signingPublicKey', ''))}</textarea></label>"
        f"</fieldset>"
        f"<div class='inline'><button class='button' type='submit'>保存设置</button>"
        f"<button class='button subtle' type='submit' formaction='/admin/sync'>立即同步模块 Release</button></div>"
        f"</form></div>"
    )
    script = (
        "<script>(()=>{const select=document.getElementById('auth-mode');if(!select)return;"
        "const sync=()=>document.querySelectorAll('[data-auth-section]').forEach(node=>{"
        "node.hidden=node.dataset.authSection!==select.value;"
        "node.querySelectorAll('input,textarea,select').forEach(input=>input.disabled=node.hidden)});"
        "select.addEventListener('change',sync);sync()})();</script>"
    )
    return head + form + _admin_release_panel() + script


def _admin_overview_stats(config: dict[str, Any], records: list[dict[str, Any]]) -> str:
    """概览统计卡片：内容库、任务、模块在线和服务器配置要点。"""
    esc = lambda value: html.escape(str(value), quote=True)
    tasks = load_tasks()
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    # 按规范归属去重，同一个阅微账号的多条历史心跳只算一台设备。
    online = len({
        canonical_owner_of(str(item.get("owner", "")))
        for item in load_presence().values()
        if isinstance(item, dict) and bounded_config_int(item.get("onlineUntil", 0), 0, 0) > now
    })
    pending = sum(1 for item in load_notifications().values() if not item.get("deliveredAt"))
    release_version = "未同步"
    release_path = runtime.RELEASE_ROOT / "latest.json"
    if release_path.is_file():
        try:
            release_version = str(json.loads(release_path.read_text(encoding="utf-8")).get("versionName", "未同步"))
        except (OSError, ValueError):
            release_version = "读取失败"
    cards = [
        (str(len(records)), "内容包总数"),
        (str(sum(1 for item in records if str(item.get("status", "published")) == "published")), "其中已发布"),
        (str(len(tasks)), "云端任务"),
        (str(sum(1 for item in tasks.values() if str(item.get("status")) == "failed")), "执行失败任务"),
        (str(online), "模块在线设备"),
        (str(pending), "待发送消息"),
        (release_version, "模块 Release 版本"),
        ("已开放" if config.get("moduleUploadEnabled") else "未开放", "用户模块上传"),
    ]
    return (
        "<div class='stats' style='grid-template-columns:repeat(4,minmax(0,1fr))'>"
        + "".join(f"<div><strong>{esc(value)}</strong><span>{esc(label)}</span></div>" for value, label in cards)
        + "</div>"
    )


def _admin_presence_panel() -> str:
    """模块在线状态与离线消息队列。此前后台完全看不到这两类数据。"""
    esc = lambda value: html.escape(str(value), quote=True)
    now = int(datetime.now(timezone.utc).timestamp() * 1000)
    presence_rows = []
    # 按规范归属再去重一次：即使存量数据还没被启动迁移折叠，同一个阅微账号也只显示最近一行。
    deduped: dict[str, dict[str, Any]] = {}
    for item in load_presence().values():
        if not isinstance(item, dict):
            continue
        canonical = canonical_owner_of(str(item.get("owner", ""))) or "未标记"
        existing = deduped.get(canonical)
        if existing is None or bounded_config_int(item.get("lastSeenAt", 0), 0, 0) > bounded_config_int(existing.get("lastSeenAt", 0), 0, 0):
            deduped[canonical] = item
    for item in sorted(deduped.values(), key=lambda value: bounded_config_int(value.get("lastSeenAt", 0), 0, 0), reverse=True):
        online_until = bounded_config_int(item.get("onlineUntil", 0), 0, 0)
        online = online_until > now
        presence_rows.append(
            f"<tr><td>{esc(_admin_owner_label(item.get('owner', '')))}</td>"
            f"<td><span class='status status-{'online' if online else 'offline'}'>{'在线' if online else '离线'}</span>"
            f"<small>租约至 {esc(_admin_time_label(online_until, '未记录'))}</small></td>"
            f"<td>{esc(item.get('moduleVersion', '') or '未上报')}"
            f"<small>构建 {esc(_admin_time_label(item.get('buildTime'), '未记录'))}</small></td>"
            f"<td>{esc(item.get('source', '') or 'module')}</td>"
            f"<td>{esc(_admin_time_label(item.get('lastSeenAt'), '未记录'))}"
            f"<small>{esc(_admin_time_detail(item.get('lastSeenAt'), '未记录'))}</small></td></tr>"
        )
    presence_table = "".join(presence_rows) or "<tr><td colspan='5' class='empty'>暂无模块心跳记录</td></tr>"
    notification_rows = []
    notifications = sorted(
        load_notifications().values(),
        key=lambda value: bounded_config_int(value.get("createdAt", 0), 0, 0),
        reverse=True,
    )
    for item in notifications[:30]:
        delivered = bounded_config_int(item.get("deliveredAt", 0), 0, 0)
        result = str(item.get("result", ""))
        notification_rows.append(
            f"<tr><td>{esc(item.get('title', '') or '任务消息')}"
            f"<small>{esc(item.get('message', '') or '无消息内容')}</small></td>"
            f"<td>{esc(_admin_owner_label(item.get('owner', '')))}</td>"
            f"<td><span class='status status-{esc(result)}'>{esc(_admin_result_label(result))}</span></td>"
            f"<td><span class='status status-{'success' if delivered else 'unverified'}'>{'已送达' if delivered else '待发送'}</span>"
            f"<small>{esc(_admin_time_label(delivered, '模块下次在线时发送'))}</small></td>"
            f"<td>{esc(_admin_time_label(item.get('createdAt'), '未记录'))}</td></tr>"
        )
    notification_table = "".join(notification_rows) or "<tr><td colspan='5' class='empty'>暂无任务消息</td></tr>"
    pending_total = sum(1 for item in notifications if not item.get("deliveredAt"))
    return (
        f"<div class='panel'><h2>模块在线状态</h2>"
        f"<p class='muted'>阅微在前台时模块每 5 分钟上报一次心跳，另外在零点和云端任务完成后由系统闹钟唤醒上报；服务器按 10 分钟租约判断在线。"
        f"消息在心跳响应里下发，模块显示成功后回执，未回执的会保留到下次在线。</p>"
        f"<div class='table-wrap'><table><thead><tr><th>来源账号</th><th>连接状态</th><th>模块版本</th><th>上报来源</th><th>最近心跳</th></tr></thead>"
        f"<tbody>{presence_table}</tbody></table></div></div>"
        f"<div class='panel'><h2>任务消息队列</h2>"
        f"<p class='muted'>显示最近 30 条任务消息，其中 {pending_total} 条等待发送。模块确认接收后才会标记为已送达。</p>"
        f"<div class='table-wrap'><table><thead><tr><th>消息</th><th>接收账号</th><th>任务结果</th><th>发送状态</th><th>产生时间</th></tr></thead>"
        f"<tbody>{notification_table}</tbody></table></div></div>"
    )


def _admin_subadmin_table(config: dict[str, Any], csrf_html: str) -> str:
    """子管理员列表，带重置密码、启用停用和删除操作。"""
    esc = lambda value: html.escape(str(value), quote=True)
    accounts = config.get("adminAccounts", {})
    rows = []
    for username in sorted(accounts.keys()):
        record = accounts.get(username)
        if not isinstance(record, dict):
            continue
        enabled = bool(record.get("enabled", True))
        permissions = record.get("permissions", [])
        permission_labels = "、".join(
            dict(ADMIN_ASSIGNABLE_PERMISSIONS).get(str(item), str(item)) for item in permissions
        ) if isinstance(permissions, list) and permissions else "未分配权限"
        actions = (
            f"<form class='inline' method='post' action='/admin/admins/reset'>{csrf_html}"
            f"<input type='hidden' name='username' value='{esc(username)}'>"
            f"<button class='button subtle' type='submit'>重置密码</button></form> "
            f"<form class='inline' method='post' action='/admin/admins/toggle'>{csrf_html}"
            f"<input type='hidden' name='username' value='{esc(username)}'>"
            f"<button class='button subtle' type='submit'>{'停用' if enabled else '启用'}</button></form> "
            f"<form class='inline' method='post' action='/admin/admins/delete'>{csrf_html}"
            f"<input type='hidden' name='username' value='{esc(username)}'>"
            f"<button class='button subtle danger' type='submit'>删除</button></form>"
        )
        rows.append(
            f"<tr><td><strong>{esc(username)}</strong><small>由 {esc(record.get('createdBy', '未知'))} 创建</small></td>"
            f"<td><span class='status status-{'valid' if enabled else 'paused'}'>{'已启用' if enabled else '已停用'}</span></td>"
            f"<td>{esc(permission_labels)}</td>"
            f"<td>{esc(_admin_time_label(record.get('updatedAt'), '未记录'))}"
            f"<small>创建于 {esc(_admin_time_label(record.get('createdAt'), '未记录'))}</small></td>"
            f"<td class='actions'>{actions}</td></tr>"
        )
    table = "".join(rows) or "<tr><td colspan='5' class='empty'>暂无子管理员</td></tr>"
    permission_checkboxes = "".join(
        f"<label><input type='checkbox' name='permissions' value='{esc(key)}' "
        f"{'checked' if key in {'settings:write', 'packages:write', 'tasks:write'} else ''}> {esc(label)}</label>"
        for key, label in ADMIN_ASSIGNABLE_PERMISSIONS
    )
    return (
        f"<div class='panel'><h2>子管理员</h2>"
        f"<p class='muted'>子管理员使用同一个登录地址，只能执行被授予的操作。重置密码、停用和删除会立即清空该账号的全部后台会话。</p>"
        f"<div class='table-wrap' style='margin-bottom:16px'><table><thead><tr><th>用户名</th><th>状态</th><th>权限</th><th>最近变更</th><th>操作</th></tr></thead>"
        f"<tbody>{table}</tbody></table></div>"
        f"<h3>创建子管理员</h3>"
        f"<form method='post' action='/admin/admins/create'>{csrf_html}"
        f"<div class='grid-2'><label>用户名<input name='username' required></label>"
        f"<label>初始密码（留空自动生成 24 位随机密码）<input type='password' name='password' minlength='12'></label></div>"
        f"<fieldset><legend>授予权限</legend>{permission_checkboxes}</fieldset>"
        f"<button class='button' type='submit'>创建子管理员</button></form></div>"
    )


def _admin_api_key_table(config: dict[str, Any], csrf_html: str) -> str:
    """API Key 列表与创建、吊销操作。密钥明文只在创建时显示一次。"""
    esc = lambda value: html.escape(str(value), quote=True)
    rows = []
    for record in api_key_public_records(config):
        enabled = bool(record.get("enabled", True))
        permissions = record.get("permissions", [])
        actions = ""
        if enabled:
            actions = (
                f"<form class='inline' method='post' action='/admin/security/api-keys/revoke'>{csrf_html}"
                f"<input type='hidden' name='key_id' value='{esc(record.get('id', ''))}'>"
                f"<button class='button subtle danger' type='submit'>吊销</button></form>"
            )
        revoked_note = "" if enabled else f"<small>吊销于 {esc(_admin_time_label(record.get('revokedAt'), '未记录'))}</small>"
        permission_text = "、".join(str(item) for item in permissions) if permissions else "无"
        action_cell = actions or "<span class='muted'>无可用操作</span>"
        rows.append(
            f"<tr><td><strong>{esc(record.get('name', ''))}</strong><small><code>{esc(record.get('id', ''))}</code></small></td>"
            f"<td><span class='status status-{'valid' if enabled else 'invalid'}'>{'生效中' if enabled else '已吊销'}</span>{revoked_note}</td>"
            f"<td>{esc(permission_text)}</td>"
            f"<td>{esc(_admin_time_label(record.get('createdAt'), '未记录'))}</td>"
            f"<td>{esc(_admin_time_label(record.get('lastUsedAt'), '从未使用'))}</td>"
            f"<td class='actions'>{action_cell}</td></tr>"
        )
    table = "".join(rows) or "<tr><td colspan='6' class='empty'>暂无 API Key</td></tr>"
    permission_checkboxes = "".join(
        f"<label><input type='checkbox' name='permissions' value='{esc(key)}' {'checked' if key == 'read' else ''}> {esc(key)}</label>"
        for key in API_KEY_PERMISSIONS
    )
    return (
        f"<div class='panel'><h2>API Key</h2>"
        f"<p class='muted'>供脚本或第三方客户端调用 API 使用。密钥只保存哈希，明文仅在创建时显示一次；泄露后请立即吊销。</p>"
        f"<div class='table-wrap' style='margin-bottom:16px'><table><thead><tr><th>名称</th><th>状态</th><th>权限范围</th><th>创建时间</th><th>最近使用</th><th>操作</th></tr></thead>"
        f"<tbody>{table}</tbody></table></div>"
        f"<h3>创建 API Key</h3>"
        f"<form method='post' action='/admin/security/api-keys/create'>{csrf_html}"
        f"<label>名称<input name='name' placeholder='例如 内容同步脚本'></label>"
        f"<fieldset><legend>权限范围</legend>{permission_checkboxes}</fieldset>"
        f"<button class='button' type='submit'>创建 API Key</button></form></div>"
    )


def _admin_snapshot_table(config: dict[str, Any], actor: dict[str, Any], csrf_html: str) -> str:
    """服务器快照列表，带下载、校验和（仅主管理员）恢复操作。"""
    esc = lambda value: html.escape(str(value), quote=True)
    is_primary = actor.get("role") == "primary"
    rows = []
    for item in list_server_snapshots():
        filename = str(item.get("filename", item.get("name", "")))
        actions = [
            f"<a class='button subtle' href='/admin/backups/server/{esc(filename)}'>下载</a>",
            f"<form class='inline' method='post' action='/admin/security/backups/verify'>{csrf_html}"
            f"<input type='hidden' name='filename' value='{esc(filename)}'>"
            f"<button class='button subtle' type='submit'>校验完整性</button></form>",
        ]
        created = item.get("createdAt") or item.get("modifiedAt")
        rows.append(
            f"<tr><td><strong>{esc(filename)}</strong>"
            f"<small>摘要 <code title='{esc(item.get('sha256', ''))}'>{esc(_admin_digest_label(item.get('sha256')))}</code></small></td>"
            f"<td>{esc(_admin_size_label(item.get('size')))}</td>"
            f"<td>{esc(_admin_time_label(created, '未记录'))}"
            f"<small>{esc(_admin_time_detail(created, '未记录'))}</small></td>"
            f"<td class='actions'>{' '.join(actions)}</td></tr>"
        )
    table = "".join(rows) or "<tr><td colspan='4' class='empty'>暂无服务器快照</td></tr>"
    restore_block = ""
    if is_primary:
        restore_block = (
            f"<h3>恢复快照</h3>"
            f"<p class='muted'>恢复会用快照内的数据库和 <code>server.json</code> 覆盖当前数据，"
            f"覆盖前会自动创建一份安全快照。恢复后请重启容器让所有进程重新加载配置。</p>"
            f"<form method='post' action='/admin/security/backups/restore'>{csrf_html}"
            f"<div class='grid-2'><label>要恢复的快照文件名<input name='filename' placeholder='reamicro-server-....zip' required></label>"
            f"<label>再次输入同一文件名以确认<input name='confirm' placeholder='与上方完全一致' required></label></div>"
            f"<button class='button danger' type='submit'>确认恢复</button></form>"
        )
    else:
        restore_block = "<p class='muted'>恢复快照仅限主管理员操作。</p>"
    retention = bounded_config_int(config.get("serverSnapshotRetention", 30), 30, 3)
    return (
        f"<div class='panel'><h2>服务器快照</h2>"
        f"<p class='muted'>快照包含 SQLite 数据库与服务器配置，最多保留 {retention} 份。"
        f"快照内含加密后的阅微凭据，请与服务器加密密钥分开保存。</p>"
        f"<form class='inline' method='post' action='/admin/backups/server' style='margin-bottom:14px'>{csrf_html}"
        f"<button class='button' type='submit'>立即创建快照</button></form>"
        f"<div class='table-wrap' style='margin-bottom:16px'><table><thead><tr><th>文件名</th><th>大小</th><th>创建时间</th><th>操作</th></tr></thead>"
        f"<tbody>{table}</tbody></table></div>{restore_block}</div>"
    )


def _admin_security_content(config: dict[str, Any], actor: dict[str, Any], csrf_html: str) -> str:
    """安全分区：子管理员、API Key、快照、密钥轮换和审计入口。"""
    esc = lambda value: html.escape(str(value), quote=True)
    is_primary = actor.get("role") == "primary"
    permissions = set(actor.get("permissions", []))
    head = (
        "<div class='page-head'><div><p class='eyebrow'>保护</p><h1>子管理员与安全</h1>"
        "<p class='muted'>管理员账号、API Key、服务器快照与加密密钥。</p></div></div>"
    )
    stats = (
        f"<div class='stats'>"
        f"<div><strong>{len(config.get('adminAccounts', {}))}</strong><span>子管理员</span></div>"
        f"<div><strong>{sum(1 for item in api_key_public_records(config) if item.get('enabled', True))}</strong><span>生效中的 API Key</span></div>"
        f"<div><strong>{len(list_server_snapshots())}</strong><span>服务器快照</span></div>"
        f"<div><strong>{'已设置' if config.get('secretKey') else '未设置'}</strong><span>服务器加密密钥</span></div>"
        f"</div>"
    )
    blocks = [head, stats]
    if is_primary:
        blocks.append(_admin_subadmin_table(config, csrf_html))
        blocks.append(_admin_api_key_table(config, csrf_html))
    if is_primary or "backup:admin" in permissions:
        blocks.append(_admin_snapshot_table(config, actor, csrf_html))
    if is_primary or "security:write" in permissions:
        blocks.append(
            f"<div class='panel'><h2>服务器加密密钥</h2>"
            f"<p class='muted'>该密钥用于加密阅微登录凭据和任务参数。轮换会用新密钥重新加密所有既有数据；"
            f"轮换期间请避免并发写入，并务必保存新密钥，丢失后既有凭据无法解密。</p>"
            f"<form method='post' action='/admin/security/rotate-secret'>{csrf_html}"
            f"<label>新的加密密钥（留空则自动生成随机长密钥）<input type='password' name='new_secret_key' autocomplete='new-password'></label>"
            f"<button class='button danger' type='submit'>轮换加密密钥</button></form></div>"
        )
    if is_primary or "audit:read" in permissions:
        blocks.append(
            f"<div class='panel'><h2>审计日志</h2>"
            f"<p class='muted'>记录登录、设置变更、内容包操作、模块上传和快照操作，保存在数据卷的 "
            f"<code>/data/audit/events.jsonl</code>。</p>"
            f"<p><a class='button subtle' href='/admin/audit'>查看审计日志</a></p></div>"
        )
    if not is_primary:
        blocks.append(
            f"<div class='panel'><h2>当前账号权限</h2><p class='muted'>你是子管理员 "
            f"{esc(actor.get('username', ''))}，已获授权："
            f"{esc('、'.join(dict(ADMIN_ASSIGNABLE_PERMISSIONS).get(item, item) for item in sorted(permissions)) or '无')}。"
            f"子管理员账号与 API Key 管理仅主管理员可见。</p></div>"
        )
    return "".join(blocks)


def admin_page(config: dict[str, Any], message: str = "", actor: dict[str, Any] | None = None, secret_notice: str = "", section: str = "overview", query: str = "") -> str:
    """分类管理后台，保留原有写入路由并提供统一内容列表入口。"""
    merge_duplicate_credentials()
    merge_duplicate_tasks()
    esc = lambda value: html.escape(str(value), quote=True)
    actor = actor or {"username": "", "role": "subadmin", "permissions": []}
    valid_sections = {"overview", "packages", "tasks", "settings", "security", *runtime.PACKAGE_KINDS}
    section = section if section in valid_sections else "overview"
    csrf_html = f"<input type='hidden' name='csrf_token' value='{esc(admin_csrf_token(config, actor))}'>"
    records = _admin_package_records()
    actor_permissions = set(actor.get("permissions", []))
    is_primary = actor.get("role") == "primary"
    can_packages = is_primary or "packages:write" in actor_permissions
    can_tasks = is_primary or "tasks:write" in actor_permissions
    no_permission_note = "<span class='muted'>需要任务管理权限</span>"
    credential_rows = []
    for credential in sorted(load_credentials().values(), key=lambda value: bounded_config_int(value.get("updatedAt", 0), 0, 0), reverse=True):
        owner = str(credential.get("owner", ""))
        owner_display = owner[:12] + "…" if len(owner) > 12 else owner
        public_credential = credential_public(credential)
        health = str(public_credential.get("health", "unverified"))
        credential_id = str(credential.get("id", ""))
        enabled = bool(credential.get("enabled", True))
        credential_actions = ""
        if can_tasks:
            credential_actions = (
                f"<form class='inline' method='post' action='/admin/credentials/action'>{csrf_html}"
                f"<input type='hidden' name='credential_id' value='{esc(credential_id)}'>"
                f"<button class='button subtle' name='action' value='verify' type='submit'>验证</button>"
                f"<button class='button subtle' name='action' value='toggle' type='submit'>{'停用' if enabled else '启用'}</button>"
                f"<button class='button subtle danger' name='action' value='delete' type='submit'>删除</button></form>"
            )
        credential_rows.append(
            f"<tr><td><strong>{esc(credential.get('label', '阅微账号'))}</strong><small><code>{esc(credential_id)}</code></small></td>"
            f"<td>{esc(credential.get('accountId', '') or '未提供')}</td>"
            f"<td>{esc(_admin_owner_label(owner))}<small>{esc(owner_display)}</small></td>"
            f"<td><span class='status status-{esc(health)}'>{esc(_admin_health_label(health))}</span>"
            f"<small>{esc(credential.get('lastVerifyMessage', '') or '暂无验证说明')}</small></td>"
            f"<td>{esc(_admin_time_label(credential.get('updatedAt', 0), '未记录'))}"
            f"<small>最近使用 {esc(_admin_time_label(credential.get('lastUsedAt', 0), '从未使用'))}</small></td>"
            f"<td class='actions'>{credential_actions or no_permission_note}</td></tr>"
        )
    credential_table = "".join(credential_rows) or "<tr><td colspan='6' class='empty'>暂无模块上传的阅微同步密钥</td></tr>"
    credential_options = "<option value=''>不使用凭据</option>" + "".join(
        f"<option value='{esc(item.get('id', ''))}'>{esc(item.get('label', '阅微账号'))} · {esc(item.get('accountId', '') or item.get('owner', ''))}</option>"
        for item in sorted(load_credentials().values(), key=lambda value: str(value.get('label', '')))
    )
    task_rows = []
    for task in sorted(load_tasks().values(), key=lambda value: bounded_config_int(value.get("createdAt", 0), 0, 0), reverse=True):
        task_id = esc(task.get("id", "")); task_status = str(task.get("status", ""))
        task_actions = ""
        if can_tasks:
            buttons = ["<button class='button subtle' name='action' value='run'>立即运行</button>"]
            if task_status == "running":
                buttons = []
            elif task.get("enabled", True) and task_status not in {"cancelled", "paused"}:
                buttons.append("<button class='button subtle' name='action' value='pause'>暂停</button>")
            else:
                buttons.append("<button class='button subtle' name='action' value='resume'>恢复</button>")
            if task_status not in {"cancelled", "running"}:
                buttons.append("<button class='button subtle' name='action' value='cancel'>取消</button>")
            if task_status != "running":
                buttons.append("<button class='button subtle danger' name='action' value='delete'>删除</button>")
            if buttons:
                task_actions = f"<form class='inline' method='post' action='/admin/tasks/action'>{csrf_html}<input type='hidden' name='task_id' value='{task_id}'>{''.join(buttons)}</form>"
        last_result = str(task.get("lastResult", ""))
        result_note = f"<small>{esc(_admin_result_label(last_result))}</small>" if last_result else ""
        task_rows.append(
            f"<tr><td><strong>{esc(_admin_task_label(task.get('taskType', '')))}</strong>"
            f"<small>{esc(_admin_time_label(task.get('createdAt'), '未记录'))} 创建</small></td>"
            f"<td>{esc(_admin_owner_label(task.get('owner', '')))}</td>"
            f"<td>{esc(task_credential_id(task) or '未关联')}</td>"
            f"<td>{esc(_admin_task_detail(task))}</td>"
            f"<td><span class='status status-{esc(task_status)}'>{esc(_admin_task_status_label(task_status))}</span>"
            f"<small>{'已启用' if task.get('enabled', True) else '已停用'}</small></td>"
            f"<td>{esc(task.get('lastMessage', '') or '暂无执行记录')}{result_note}</td>"
            f"<td>{esc(_admin_time_label(task.get('nextRunAt', 0)))}"
            f"<small>{esc(_admin_time_detail(task.get('nextRunAt', 0)))}</small></td>"
            f"<td class='actions'><a class='button subtle' href='/admin/tasks/{task_id}/logs'>日志</a> {task_actions}</td></tr>"
        )
    task_table = "".join(task_rows) or "<tr><td colspan='8' class='empty'>暂无云端任务</td></tr>"
    if section in {"overview", "packages", *runtime.PACKAGE_KINDS}:
        selected = section if section in runtime.PACKAGE_KINDS else ""
        visible = [item for item in records if not selected or item.get("kind") == selected]
        head_action = f"<a class='button' href='/admin/packages/new?kind={esc(selected or 'online_source')}'>新增内容</a>" if can_packages else ""
        if section == "overview":
            content = (
                f"<div class='page-head'><div><p class='eyebrow'>总览</p><h1>概览</h1>"
                f"<p class='muted'>服务器状态、内容库统计与模块连接情况。</p></div>{head_action}</div>"
            )
            content += _admin_overview_stats(config, records)
            content += _admin_presence_panel()
            content += "<h2 style='margin:26px 0 14px'>全部内容</h2>"
        else:
            title = "内容管理" if section == "packages" else _admin_kind_label(section)
            description = (
                "按类型管理版本、依赖、发布状态和历史版本。"
                if section == "packages"
                else f"管理{_admin_kind_label(section)}的版本、发布状态和历史版本。"
            )
            content = (
                f"<div class='page-head'><div><p class='eyebrow'>内容中心</p><h1>{esc(title)}</h1>"
                f"<p class='muted'>{esc(description)}</p></div>{head_action}</div>"
            )
        clear_button = (
            f"<a class='button subtle' href='{admin_section_path(section)}'>清除搜索</a>" if query.strip() else ""
        )
        # 内容表格对概览、全部内容和各类型分区都要渲染，此前被误缩进导致分区页只有标题。
        content += (
            f"<form class='toolbar' method='get' action='{admin_section_path(section)}'>"
            f"<input name='q' value='{esc(query)}' placeholder='搜索名称、包 ID、版本或别名'>"
            f"<button class='button' type='submit'>搜索</button>{clear_button}"
            f"</form>"
            f"<div class='table-wrap'><table><thead><tr><th>类型 / 来源</th><th>内容</th>"
            f"<th>版本 / 构建时间</th><th>状态</th><th>渠道 / 依赖</th><th>操作</th></tr></thead>"
            f"<tbody>{_admin_package_table(visible, can_packages, query, admin_csrf_token(config, actor))}</tbody></table></div>"
        )
    else:
        task_credentials = ""
        if section == "tasks":
            all_tasks = load_tasks()
            task_credentials = (
                f"<div class='stats'>"
                f"<div><strong>{len(load_credentials())}</strong><span>同步密钥数量</span></div>"
                f"<div><strong>{len(all_tasks)}</strong><span>云端任务数量</span></div>"
                f"<div><strong>{sum(1 for value in all_tasks.values() if value.get('enabled', True))}</strong><span>已启用任务</span></div>"
                f"<div><strong>{sum(1 for value in all_tasks.values() if value.get('status') == 'failed')}</strong><span>执行失败任务</span></div>"
                f"</div>"
                f"<div class='panel'><h2>阅微同步密钥</h2>"
                f"<p class='muted'>密钥只保存加密密文，不显示原文。一个阅微账号 ID 对应一个同步密钥；重新同步会更新该账号的原记录。"
                f"删除密钥会自动暂停依赖它的任务。</p>"
                f"<div class='table-wrap'><table><thead><tr><th>密钥名称</th><th>阅微账号 ID</th><th>来源账号</th>"
                f"<th>验证状态</th><th>更新 / 使用时间</th><th>操作</th></tr></thead><tbody>{credential_table}</tbody></table></div></div>"
                f"<div class='panel'><h2>任务运行状态</h2>"
                f"<div class='table-wrap'><table><thead><tr><th>任务</th><th>来源账号</th><th>同步密钥 ID</th><th>任务详情</th>"
                f"<th>运行状态</th><th>最近结果</th><th>下次执行</th><th>操作</th></tr></thead><tbody>{task_table}</tbody></table></div></div>"
            )
        if section == "tasks":
            book_example = '[{"cloudBookId":123,"bookId":123,"name":"书名"}]'
            task_form = (
                f"<div class='panel'><h2>创建云端任务</h2>"
                f"<p class='muted'>同一所有者、同步密钥和任务类型只保留一个任务；重复创建会更新已有任务而不是新增。</p>"
                f"<form method='post' action='/admin/tasks/create'>{csrf_html}"
                f"<div class='grid-2'>"
                f"<label>任务类型<select name='task_type'><option value='yeshe_checkin'>野社零点签到</option>"
                f"<option value='yeshe_draw_card'>野社自动抽卡</option><option value='cloud_auto_read'>云端自动阅读</option>"
                f"<option value='http'>通用 HTTPS 请求</option></select></label>"
                f"<label>每日执行时间（北京时间 HH:MM）<input name='time_of_day' value='00:05'></label>"
                f"<label>同步密钥<select name='credential_id'>{credential_options}</select></label>"
                # 非 http 任务的归属跟随所选同步密钥，这里只作为 http 任务的兜底归属。
                f"<label>任务所有者（仅自定义 HTTP 任务使用；阅微任务自动跟随所选同步密钥）"
                f"<input name='owner' value='admin'></label>"
                f"<label>阅读时长（分钟，1-720）<input name='duration_minutes' type='number' min='1' max='720' value='30'></label>"
                f"<label>最近阅读取用数量（1-20）<input name='recent_limit' type='number' min='1' max='20' value='1'></label>"
                f"</div>"
                f"<label>自定义图书 JSON（留空则读取阅微最近阅读记录）"
                f"<textarea name='request_body' placeholder='{html.escape(book_example, quote=True)}'></textarea></label>"
                f"<p class='muted' style='margin:0'>自定义图书格式示例：<code>{html.escape(book_example)}</code></p>"
                f"<button class='button' type='submit'>创建任务</button></form></div>"
            )
            content = f"<div class='page-head'><div><p class='eyebrow'>自动化</p><h1>云端任务</h1><p class='muted'>配置、查看和控制自动阅读、签到、抽卡任务。每个阅微账号只保留一个同步密钥，重新同步会更新原密钥。</p></div></div>{task_credentials}{task_form}"
        elif section == "settings":
            content = _admin_settings_content(config, actor, csrf_html)
        else:
            content = _admin_security_content(config, actor, csrf_html)
    return _admin_layout(config, actor, section, content, message=message, secret_notice=secret_notice, records=records)
