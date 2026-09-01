"""后台用户管理页。

按阅微账号 ID 列出用户，显示各自的凭据、任务、上传数与在线状态，
并允许逐个开关访问、上传权限与停用。
"""
import html
from typing import Any

from app import runtime
from app.admin.format import _admin_time_detail, _admin_time_label
from app.admin.layout import _admin_layout, admin_csrf_token
from app.config_store import bounded_config_int, load_config
from app.users import (
    USER_CAPABILITIES,
    USER_CAPABILITY_LABELS,
    allowlist_membership,
    known_account_ids,
    load_users,
    user_statistics,
)


def _capability_checkboxes(prefix: str, selected: set[str]) -> str:
    esc = lambda value: html.escape(str(value), quote=True)
    options = "".join(
        f"<label><input type='checkbox' name='{prefix}' value='{esc(cap)}' "
        f"{'checked' if cap in selected else ''}> {esc(USER_CAPABILITY_LABELS.get(cap, cap))}</label>"
        for cap in USER_CAPABILITIES
    )
    return f"<div class='check-list'>{options}</div>"


def admin_users_page(
    config: dict[str, Any] | None = None,
    message: str = "",
    actor: dict[str, Any] | None = None,
    query: str = "",
) -> str:
    esc = lambda value: html.escape(str(value), quote=True)
    config = config or load_config()
    actor = actor or {"username": "", "role": "primary", "permissions": [], "needsSetup": False}
    csrf = admin_csrf_token(config, actor)
    csrf_html = f"<input type='hidden' name='csrf_token' value='{esc(csrf)}'>"
    users = load_users()
    needle = query.strip().casefold()

    account_ids = known_account_ids()
    if needle:
        account_ids = [
            item for item in account_ids
            if needle in item.casefold() or needle in str(users.get(item, {}).get("note", "")).casefold()
        ]

    records = []
    online_count = 0
    for account_id in account_ids:
        user = users.get(account_id, {})
        stats = user_statistics(account_id)
        membership = allowlist_membership(config, account_id)
        enabled = bool(user.get("enabled", True)) if user else True
        capabilities = set(user.get("capabilities", [])) if user else set()
        if stats["online"]:
            online_count += 1
        state_class = "ok" if stats["online"] else ("idle" if enabled else "bad")
        state_text = "在线" if stats["online"] else ("离线" if enabled else "已停用")
        failed_note = f" · 失败 {stats['failedTasks']}" if stats["failedTasks"] else ""
        quota = bounded_config_int(user.get("uploadQuota", 0), 0, 0) if user else 0
        upload_note = f"{stats['uploads']}/{quota} 个" if quota else f"{stats['uploads']} 个 / 不限"
        records.append(
            f"<article class='record-item'><div class='record-title'><strong>阅微账号 {esc(account_id)}</strong>"
            f"<small>{esc(user.get('note', '') or '未填备注')}</small>"
            f"<small><span class='status tone-{state_class}'>{state_text}</span> · "
            f"{esc(_admin_time_detail(stats['lastSeenAt'], '从未上报'))}</small>"
            f"{'' if user else '<small>保存后自动创建档案</small>'}</div>"
            f"<div class='record-meta'>"
            f"<div><span>模块版本</span><strong>{esc(stats['moduleVersion'] or '未上报')}</strong></div>"
            f"<div><span>同步数据</span><strong>密钥 {stats['credentials']} · 任务 {stats['tasks']}{failed_note}</strong></div>"
            f"<div><span>上传内容</span><strong>{esc(upload_note)}</strong></div>"
            f"<div><span>待发消息</span><strong>{stats['pendingNotifications']} 条</strong></div></div>"
            f"<form class='record-form' method='post' action='/admin/users/save'>{csrf_html}"
            f"<input type='hidden' name='account_id' value='{esc(account_id)}'>"
            f"<div class='check-list span-all'>"
            f"<label><input type='checkbox' name='enabled' {'checked' if enabled else ''}> 启用账号</label>"
            f"<label><input type='checkbox' name='allow_access' {'checked' if membership['access'] else ''}> 允许连接</label>"
            f"<label><input type='checkbox' name='allow_upload' {'checked' if membership['upload'] else ''}> 允许上传</label></div>"
            f"<fieldset><legend>可用功能</legend>{_capability_checkboxes('capabilities', capabilities)}</fieldset>"
            f"<label>备注<input name='note' value='{esc(user.get('note', ''))}' placeholder='例如 自用主力机'></label>"
            f"<label>上传上限<input name='upload_quota' type='number' min='0' value='{quota}'><small>0 为不限</small></label>"
            f"<div class='record-actions'><button class='button' type='submit'>保存设置</button>"
            f"<button class='button subtle danger' type='submit' formaction='/admin/users/delete'>删除档案</button>"
            f"</div></form></article>"
        )
    user_list = "".join(records) or "<div class='empty'>暂无用户。模块首次上报心跳后会自动建档。</div>"

    stats_cards = "".join(
        f"<div><strong>{esc(value)}</strong><span>{esc(label)}</span></div>"
        for value, label in (
            (len(account_ids), "已知阅微账号"),
            (online_count, "当前在线"),
            (sum(1 for item in users.values() if not item.get("enabled", True)), "已停用"),
            (len(config.get("moduleUploadAllowlist", [])), "允许上传内容库"),
        )
    )

    search_value = esc(query)
    content = (
        "<div class='page-head'><div><p class='eyebrow'>用户</p><h1>用户管理</h1>"
        "<p class='muted'>统一管理账号访问、上传权限和云端功能。</p></div></div>"
        f"<div class='stats'>{stats_cards}</div>"
        f"<form class='toolbar' method='get' action='/admin/users'>"
        f"<input name='q' value='{search_value}' placeholder='搜索阅微账号或备注'>"
        f"<button class='button' type='submit'>搜索</button>"
        + (f"<a class='button subtle' href='/admin/users'>清除</a>" if query else "")
        + "</form>"
        f"<div class='panel'><form class='inline' method='post' action='/admin/users/sync'>{csrf_html}"
        f"<button class='button subtle' type='submit'>从白名单与活动记录补建档案</button></form>"
        f"<p class='hint-tight mt'>补齐已有活动记录但尚未建档的账号。</p></div>"
        f"<div class='record-list'>{user_list}</div>"
    )
    return _admin_layout(config, actor, section="users", content=content, message=message)
