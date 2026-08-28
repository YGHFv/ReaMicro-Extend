"""后台用户管理页。

按阅微账号 ID 列出用户，显示各自的凭据、任务、上传数与在线状态，
并允许逐个开关访问、上传权限与停用。
"""
import html
from typing import Any

from app import runtime
from app.admin.format import _admin_time_detail, _admin_time_label
from app.admin.layout import _admin_layout, admin_csrf_token
from app.config_store import load_config
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
    return "".join(
        f"<label><input type='checkbox' name='{prefix}' value='{esc(cap)}' "
        f"{'checked' if cap in selected else ''}> {esc(USER_CAPABILITY_LABELS.get(cap, cap))}</label>"
        for cap in USER_CAPABILITIES
    )


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

    rows = []
    online_count = 0
    for account_id in account_ids:
        user = users.get(account_id, {})
        stats = user_statistics(account_id)
        membership = allowlist_membership(config, account_id)
        enabled = bool(user.get("enabled", True)) if user else True
        capabilities = set(user.get("capabilities", [])) if user else set()
        if stats["online"]:
            online_count += 1
        state_class = "online" if stats["online"] else ("offline" if enabled else "invalid")
        state_text = "在线" if stats["online"] else ("离线" if enabled else "已停用")
        failed_note = f"（失败 {stats['failedTasks']}）" if stats["failedTasks"] else ""
        rows.append(
            f"<tr><td><strong>阅微账号 {esc(account_id)}</strong>"
            f"<small>{esc(user.get('note', '') or '未填备注')}</small>"
            f"{'' if user else '<small>尚未建档，保存后自动创建</small>'}</td>"
            f"<td><span class='status status-{state_class}'>{state_text}</span>"
            f"<small>{esc(_admin_time_detail(stats['lastSeenAt'], '从未上报'))}</small></td>"
            f"<td>{esc(stats['moduleVersion'] or '未上报')}</td>"
            f"<td>密钥 {stats['credentials']} · 任务 {stats['tasks']}{failed_note}"
            f"<small>上传内容 {stats['uploads']} 个 · 待发消息 {stats['pendingNotifications']} 条</small></td>"
            f"<td><form method='post' action='/admin/users/save'>{csrf_html}"
            f"<input type='hidden' name='account_id' value='{esc(account_id)}'>"
            f"<label><input type='checkbox' name='enabled' {'checked' if enabled else ''}> 启用账号</label>"
            f"<label><input type='checkbox' name='allow_access' {'checked' if membership['access'] else ''}> 允许连接服务器</label>"
            f"<label><input type='checkbox' name='allow_upload' {'checked' if membership['upload'] else ''}> 允许上传内容库</label>"
            f"<fieldset><legend>可用功能</legend>{_capability_checkboxes('capabilities', capabilities)}</fieldset>"
            f"<label>备注<input name='note' value='{esc(user.get('note', ''))}' placeholder='例如 自用主力机'></label>"
            f"<button class='button subtle' type='submit'>保存</button></form>"
            f"<form class='inline' method='post' action='/admin/users/delete' style='margin-top:8px'>{csrf_html}"
            f"<input type='hidden' name='account_id' value='{esc(account_id)}'>"
            f"<button class='button subtle danger' type='submit'>删除档案</button></form></td></tr>"
        )
    table = "".join(rows) or "<tr><td colspan='5' class='empty'>暂无用户。模块首次上报心跳后会自动建档。</td></tr>"

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
        "<p class='muted'>按阅微账号 ID 区分用户。模块首次上报心跳时自动建档，"
        "停用后该账号的全部接口调用都会被拒绝。</p></div></div>"
        f"<div class='stats'>{stats_cards}</div>"
        f"<form class='toolbar' method='get' action='/admin/users'>"
        f"<input name='q' value='{search_value}' placeholder='搜索阅微账号或备注'>"
        f"<button class='button' type='submit'>搜索</button>"
        + (f"<a class='button subtle' href='/admin/users'>清除</a>" if query else "")
        + "</form>"
        f"<div class='panel'><form class='inline' method='post' action='/admin/users/sync'>{csrf_html}"
        f"<button class='button subtle' type='submit'>从白名单与活动记录补建档案</button></form>"
        f"<p class='muted' style='margin:10px 0 0'>已在白名单或已产生数据、但还没有档案的阅微账号会被补齐。</p></div>"
        f"<div class='table-wrap'><table><thead><tr>"
        f"<th>账号</th><th>状态</th><th>模块版本</th><th>使用情况</th><th style='min-width:320px'>设置</th>"
        f"</tr></thead><tbody>{table}</tbody></table></div>"
    )
    return _admin_layout(config, actor, section="users", content=content, message=message)
