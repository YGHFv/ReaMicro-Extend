"""后台统一外壳与样式。

后台曾有四套互不相同的 CSS（主页面、内容子页、审计日志、登录页），点进子页就变样。
现在所有页面共用 _admin_layout：同一套侧栏、顶栏和 CSS，子页额外给返回入口。
错误页按 Accept 头区分：浏览器给可读 HTML，API 客户端给 JSON。
"""
import html
from typing import Any

from fastapi.responses import HTMLResponse

from app import runtime
from app.config_store import load_config
from app.labels import _admin_kind_label
from app.admin.format import admin_section_path
from app.packages import _admin_package_records


# 登录和初始化页没有侧栏，但配色、控件和圆角与后台一致。
ADMIN_AUTH_STYLE = """:root{--line:#e5e9f0;--muted:#64748b;--blue:#2563eb;--ink:#1f2937}*{box-sizing:border-box}body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px;background:#f4f7fb;color:var(--ink);font-family:system-ui,-apple-system,'Microsoft YaHei',sans-serif;line-height:1.5}main{width:100%;max-width:460px;background:#fff;border:1px solid var(--line);border-radius:10px;padding:28px}.brand{color:var(--blue);font-size:12px;font-weight:700;margin:0}h1{margin:4px 0 6px;font-size:23px}p.muted{color:var(--muted);font-size:13px;margin:0 0 18px}form{display:flex;flex-direction:column;gap:14px}label{display:flex;flex-direction:column;gap:6px;font-size:13px;font-weight:600;color:#334155}input{width:100%;padding:10px;border:1px solid #cfd6e1;border-radius:5px;font:inherit}button{min-height:42px;border:0;border-radius:5px;background:var(--blue);color:#fff;font:inherit;font-weight:650;cursor:pointer}.msg{padding:11px 13px;border-radius:6px;background:#fef2f2;color:#b91c1c;font-size:13px;margin:0 0 16px}small{display:block;color:var(--muted);font-size:12px;margin-top:16px}"""


# 后台全站共用一套样式，子页面（编辑、预览、历史、差异、日志、审计）与主页面外观保持一致。
ADMIN_STYLE = """:root{--line:#e5e9f0;--muted:#64748b;--blue:#2563eb;--ink:#1f2937}*{box-sizing:border-box}body{margin:0;background:#f4f7fb;color:var(--ink);font-family:system-ui,-apple-system,'Microsoft YaHei',sans-serif;line-height:1.5}aside{position:fixed;inset:0 auto 0 0;width:236px;padding:22px 14px;background:#f8fafc;border-right:1px solid var(--line);overflow-y:auto}.brand{font-size:18px;font-weight:700;padding:0 12px 22px}.brand small{display:block;color:var(--muted);font-size:11px;margin-top:5px}nav a{display:flex;justify-content:space-between;align-items:center;padding:10px 12px;margin:3px 0;border-radius:6px;color:#475569;text-decoration:none;font-size:14px}nav a.active,nav a:hover{background:#e8f0ff;color:#1d4ed8;font-weight:650}nav span{color:#94a3b8;font-size:11px}main{margin-left:236px;max-width:1480px;padding:30px 38px 56px}.topbar{display:flex;justify-content:flex-end;align-items:center;gap:15px;color:var(--muted);font-size:13px;margin-bottom:24px}.page-head{display:flex;justify-content:space-between;align-items:flex-start;gap:20px;margin-bottom:20px}h1{margin:3px 0 5px;font-size:26px;line-height:1.25}h2{margin:0 0 16px;font-size:19px;line-height:1.3}h3{margin:18px 0 10px;font-size:15px}.eyebrow{color:var(--blue);font-size:12px;font-weight:700;margin:0}.muted,small{color:var(--muted)}.stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin-bottom:20px}.stats div,.table-wrap,.panel{background:#fff;border:1px solid var(--line);border-radius:8px}.stats div{padding:18px}.stats strong{display:block;font-size:24px}.stats span{color:var(--muted);font-size:13px}.toolbar{display:flex;gap:8px;align-items:center;margin-bottom:12px}input,textarea,select{width:100%;padding:9px 10px;border:1px solid #cfd6e1;border-radius:5px;background:#fff;color:var(--ink);font:inherit}textarea{min-height:110px;resize:vertical;font-family:ui-monospace,'Cascadia Mono',Consolas,monospace}label{display:flex;flex-direction:column;gap:6px;min-width:0;font-size:13px;font-weight:600;color:#334155}label:has(input[type=checkbox]){display:flex;flex-direction:row;align-items:center;gap:8px}input[type=checkbox]{width:auto}.grid-2{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;margin-bottom:14px}.panel form{display:flex;flex-direction:column;gap:14px}.panel form.inline{flex-direction:row;gap:6px;align-items:center;display:inline-flex;margin:0}.panel form .button{align-self:flex-start}.toolbar input{width:min(420px,100%)}.button{display:inline-flex;align-items:center;justify-content:center;min-height:38px;padding:9px 14px;border:0;border-radius:5px;background:var(--blue);color:#fff;text-decoration:none;font:inherit;font-weight:650;cursor:pointer;white-space:nowrap}.button.subtle{background:#eef2f7;color:#334155;padding:6px 9px;min-height:32px;font-size:12px}.button.danger{background:#fef2f2;color:#b91c1c}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;min-width:760px}th,td{padding:13px 14px;border-bottom:1px solid var(--line);text-align:left;font-size:13px;vertical-align:top}th{background:#fafbfc;color:var(--muted);font-weight:650;white-space:nowrap}td small{display:block;margin-top:4px;font-size:11px}.status{display:inline-block;padding:3px 7px;border-radius:4px;font-size:11px;background:#eef2f7}.status-published,.status-valid,.status-success,.status-online{background:#ecfdf3;color:#047857}.status-draft,.status-testing,.status-warning,.status-unverified{background:#fff7ed;color:#b45309}.status-invalid,.status-failed{background:#fef2f2;color:#b91c1c}.status-offline,.status-paused,.status-cancelled,.status-unpublished{background:#f1f5f9;color:#475569}.actions{white-space:nowrap}.notice,.secret,.panel{padding:18px;margin-bottom:18px}.notice{background:#ecfdf5;color:#166534}.secret{background:#fff7ed;color:#9a3412;white-space:pre-wrap}.inline{display:inline-flex;gap:6px;align-items:center}.empty{padding:28px;text-align:center;color:var(--muted)}pre{white-space:pre-wrap;overflow:auto;background:#0f172a;color:#e2e8f0;border-radius:6px;padding:16px;line-height:1.55;font-size:12.5px}code{font-family:ui-monospace,'Cascadia Mono',Consolas,monospace;font-size:12px}fieldset{border:1px solid var(--line);border-radius:6px;padding:14px;margin:0;display:flex;flex-direction:column;gap:12px}legend{padding:0 6px;font-size:13px;font-weight:650;color:#334155}details summary{cursor:pointer;font-size:13px;font-weight:650;color:#334155}@media(max-width:900px){aside{width:200px}main{margin-left:200px;padding:24px 20px}.stats{grid-template-columns:repeat(2,minmax(0,1fr))}.grid-2{grid-template-columns:1fr}}@media(max-width:620px){aside{position:static;width:auto;border-right:0;border-bottom:1px solid var(--line)}main{margin-left:0;padding:20px 14px}nav{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:2px}.topbar{justify-content:flex-start;flex-wrap:wrap}.stats{grid-template-columns:1fr 1fr}.toolbar{align-items:stretch;flex-direction:column}.toolbar input{width:100%}.page-head{display:block}}"""


ADMIN_ERROR_HINTS = {
    400: "请求参数不正确，请返回上一页检查填写内容。",
    401: "后台会话已失效，请重新登录。",
    403: "当前账号没有执行该操作的权限。如果是子管理员，请联系主管理员分配对应权限。",
    404: "要操作的对象不存在，可能已被删除或改名。",
    409: "当前状态不允许该操作，请先处理提示中的前置条件。",
    413: "上传内容超过服务器允许的大小上限。",
    429: "操作过于频繁，请稍后重试。",
    500: "服务器内部错误，可凭请求 ID 在审计日志中定位。",
    502: "访问外部服务失败，请检查网络或令牌配置。",
    503: "服务尚未就绪，请稍后重试。",
}


ADMIN_NAV_LABELS = [
    ("overview", "概览"),
    ("packages", "全部内容"),
    ("online_source", "书源"),
    ("association_source", "关联源"),
    ("epub_style", "EPUB 样式"),
    ("highlight_style", "高亮样式"),
    ("theme", "主题库"),
    ("tasks", "云端任务"),
    ("settings", "服务器设置"),
    ("security", "子管理员与安全"),
]


def _admin_auth_shell(title: str, subtitle: str, body: str) -> str:
    """登录与初始化页的外壳。没有侧栏，但配色和控件与后台一致。"""
    esc = lambda value: html.escape(str(value), quote=True)
    return (
        f"<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'>"
        f"<meta name='viewport' content='width=device-width,initial-scale=1'><title>{esc(title)}</title>"
        f"<style>{ADMIN_AUTH_STYLE}</style></head><body><main>"
        f"<p class='brand'>ReaMicro API 管理后台</p><h1>{esc(title)}</h1>"
        f"<p class='muted'>{esc(subtitle)}</p>{body}</main></body></html>"
    )



# 后台全站共用一套样式，子页面（编辑、预览、历史、差异、日志、审计）与主页面外观保持一致。
ADMIN_STYLE = """:root{--line:#e5e9f0;--muted:#64748b;--blue:#2563eb;--ink:#1f2937}*{box-sizing:border-box}body{margin:0;background:#f4f7fb;color:var(--ink);font-family:system-ui,-apple-system,'Microsoft YaHei',sans-serif;line-height:1.5}aside{position:fixed;inset:0 auto 0 0;width:236px;padding:22px 14px;background:#f8fafc;border-right:1px solid var(--line);overflow-y:auto}.brand{font-size:18px;font-weight:700;padding:0 12px 22px}.brand small{display:block;color:var(--muted);font-size:11px;margin-top:5px}nav a{display:flex;justify-content:space-between;align-items:center;padding:10px 12px;margin:3px 0;border-radius:6px;color:#475569;text-decoration:none;font-size:14px}nav a.active,nav a:hover{background:#e8f0ff;color:#1d4ed8;font-weight:650}nav span{color:#94a3b8;font-size:11px}main{margin-left:236px;max-width:1480px;padding:30px 38px 56px}.topbar{display:flex;justify-content:flex-end;align-items:center;gap:15px;color:var(--muted);font-size:13px;margin-bottom:24px}.page-head{display:flex;justify-content:space-between;align-items:flex-start;gap:20px;margin-bottom:20px}h1{margin:3px 0 5px;font-size:26px;line-height:1.25}h2{margin:0 0 16px;font-size:19px;line-height:1.3}h3{margin:18px 0 10px;font-size:15px}.eyebrow{color:var(--blue);font-size:12px;font-weight:700;margin:0}.muted,small{color:var(--muted)}.stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin-bottom:20px}.stats div,.table-wrap,.panel{background:#fff;border:1px solid var(--line);border-radius:8px}.stats div{padding:18px}.stats strong{display:block;font-size:24px}.stats span{color:var(--muted);font-size:13px}.toolbar{display:flex;gap:8px;align-items:center;margin-bottom:12px}input,textarea,select{width:100%;padding:9px 10px;border:1px solid #cfd6e1;border-radius:5px;background:#fff;color:var(--ink);font:inherit}textarea{min-height:110px;resize:vertical;font-family:ui-monospace,'Cascadia Mono',Consolas,monospace}label{display:flex;flex-direction:column;gap:6px;min-width:0;font-size:13px;font-weight:600;color:#334155}label:has(input[type=checkbox]){display:flex;flex-direction:row;align-items:center;gap:8px}input[type=checkbox]{width:auto}.grid-2{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;margin-bottom:14px}.panel form{display:flex;flex-direction:column;gap:14px}.panel form.inline{flex-direction:row;gap:6px;align-items:center;display:inline-flex;margin:0}.panel form .button{align-self:flex-start}.toolbar input{width:min(420px,100%)}.button{display:inline-flex;align-items:center;justify-content:center;min-height:38px;padding:9px 14px;border:0;border-radius:5px;background:var(--blue);color:#fff;text-decoration:none;font:inherit;font-weight:650;cursor:pointer;white-space:nowrap}.button.subtle{background:#eef2f7;color:#334155;padding:6px 9px;min-height:32px;font-size:12px}.button.danger{background:#fef2f2;color:#b91c1c}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;min-width:760px}th,td{padding:13px 14px;border-bottom:1px solid var(--line);text-align:left;font-size:13px;vertical-align:top}th{background:#fafbfc;color:var(--muted);font-weight:650;white-space:nowrap}td small{display:block;margin-top:4px;font-size:11px}.status{display:inline-block;padding:3px 7px;border-radius:4px;font-size:11px;background:#eef2f7}.status-published,.status-valid,.status-success,.status-online{background:#ecfdf3;color:#047857}.status-draft,.status-testing,.status-warning,.status-unverified{background:#fff7ed;color:#b45309}.status-invalid,.status-failed{background:#fef2f2;color:#b91c1c}.status-offline,.status-paused,.status-cancelled,.status-unpublished{background:#f1f5f9;color:#475569}.actions{white-space:nowrap}.notice,.secret,.panel{padding:18px;margin-bottom:18px}.notice{background:#ecfdf5;color:#166534}.secret{background:#fff7ed;color:#9a3412;white-space:pre-wrap}.inline{display:inline-flex;gap:6px;align-items:center}.empty{padding:28px;text-align:center;color:var(--muted)}pre{white-space:pre-wrap;overflow:auto;background:#0f172a;color:#e2e8f0;border-radius:6px;padding:16px;line-height:1.55;font-size:12.5px}code{font-family:ui-monospace,'Cascadia Mono',Consolas,monospace;font-size:12px}fieldset{border:1px solid var(--line);border-radius:6px;padding:14px;margin:0;display:flex;flex-direction:column;gap:12px}legend{padding:0 6px;font-size:13px;font-weight:650;color:#334155}details summary{cursor:pointer;font-size:13px;font-weight:650;color:#334155}@media(max-width:900px){aside{width:200px}main{margin-left:200px;padding:24px 20px}.stats{grid-template-columns:repeat(2,minmax(0,1fr))}.grid-2{grid-template-columns:1fr}}@media(max-width:620px){aside{position:static;width:auto;border-right:0;border-bottom:1px solid var(--line)}main{margin-left:0;padding:20px 14px}nav{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:2px}.topbar{justify-content:flex-start;flex-wrap:wrap}.stats{grid-template-columns:1fr 1fr}.toolbar{align-items:stretch;flex-direction:column}.toolbar input{width:100%}.page-head{display:block}}"""


def _admin_layout(
    config: dict[str, Any],
    actor: dict[str, Any],
    section: str,
    content: str,
    message: str = "",
    secret_notice: str = "",
    records: list[dict[str, Any]] | None = None,
    title: str = "ReaMicro API 管理后台",
) -> str:
    """后台统一外壳：侧栏、顶栏、提示区加内容。所有后台页面都经过这里。"""
    esc = lambda value: html.escape(str(value), quote=True)
    counts = records if records is not None else _admin_package_records()
    nav_html = "".join(
        "<a class=\"{active}\" href=\"{href}\">{label}<span>{count}</span></a>".format(
            active="active" if key == section else "",
            href=admin_section_path(key),
            label=label,
            count=len([item for item in counts if item.get("kind") == key]) if key in runtime.PACKAGE_KINDS else "",
        )
        for key, label in ADMIN_NAV_LABELS
    )
    csrf_html = f"<input type='hidden' name='csrf_token' value='{esc(admin_csrf_token(config, actor))}'>"
    notice = f"<div class='notice'>{esc(message)}</div>" if message else ""
    secret = f"<div class='secret'><strong>请立即保存，离开本页后无法再次查看：</strong>\n{esc(secret_notice)}</div>" if secret_notice else ""
    role_label = "主管理员" if actor.get("role") == "primary" else "子管理员"
    version_line = f"<p class='muted' style='margin:0 0 12px;text-align:right;font-size:12px'>API v{runtime.API_VERSION} · 时间均为北京时间</p>"
    return (
        f"<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'>"
        f"<meta name='viewport' content='width=device-width,initial-scale=1'><title>{esc(title)}</title>"
        f"<style>{ADMIN_STYLE}</style></head><body>"
        f"<aside><div class='brand'>ReaMicro<small>API 管理后台 · 5222</small></div><nav>{nav_html}</nav></aside>"
        f"<main><div class='topbar'><span>管理员：{esc(actor.get('username', ''))}</span><span>{role_label}</span>"
        f"<form class='inline' method='post' action='/admin/logout'>{csrf_html}"
        f"<button class='button subtle' type='submit'>退出</button></form></div>"
        f"{notice}{secret}{version_line}{content}</main></body></html>"
    )


def _admin_shell(
    title: str,
    body: str,
    config: dict[str, Any],
    actor: dict[str, Any],
    section: str = "packages",
    eyebrow: str = "内容中心",
    description: str = "",
    back_href: str = "",
    back_label: str = "返回内容列表",
) -> str:
    """后台子页面（编辑、预览、历史、差异、日志）的统一外壳，与主页面同一套侧栏和样式。"""
    esc = lambda value: html.escape(str(value), quote=True)
    back = f"<a class='button subtle' href='{esc(back_href)}'>← {esc(back_label)}</a>" if back_href else ""
    description_html = f"<p class='muted'>{esc(description)}</p>" if description else ""
    head = (
        f"<div class='page-head'><div><p class='eyebrow'>{esc(eyebrow)}</p><h1>{esc(title)}</h1>"
        f"{description_html}</div>{back}</div>"
    )
    return _admin_layout(config, actor, section, head + body, title=f"{title} · ReaMicro 管理后台")


def admin_html(page: str, status_code: int = 200) -> HTMLResponse:
    return HTMLResponse(page, status_code=status_code, headers={"Cache-Control": "no-store, no-cache, must-revalidate, max-age=0", "Pragma": "no-cache", "Expires": "0"})


def admin_error_page(status_code: int, message: str, request_id: str = "") -> str:
    """后台错误提示页。此前后台任何异常都直接返回裸 JSON，浏览器里无法阅读。"""
    esc = lambda value: html.escape(str(value), quote=True)
    hint = ADMIN_ERROR_HINTS.get(status_code, "操作没有完成。")
    request_note = f"<small>请求 ID：{esc(request_id)}</small>" if request_id else ""
    login_button = "<a class='button' href='/admin/login'>重新登录</a>" if status_code == 401 else ""
    body = (
        f"<p class='msg'>{esc(message or hint)}</p>"
        f"<p class='muted' style='margin:0 0 18px'>{esc(hint)}</p>"
        f"<div class='inline' style='gap:8px'>{login_button}"
        f"<a class='button' href='/admin' style='background:#eef2f7;color:#334155'>返回后台首页</a></div>"
        f"{request_note}"
    )
    return _admin_auth_shell(f"操作未完成（HTTP {status_code}）", "后台操作被服务器拒绝。", body)


def admin_login_page(message: str = "") -> str:
    message_html = f"<p class='msg'>{html.escape(message)}</p>" if message else ""
    body = (
        f"{message_html}"
        f"<form method='post' action='/admin/login'>"
        f"<label>管理员用户名<input name='username' autocomplete='username' required></label>"
        f"<label>密码<input type='password' name='password' autocomplete='current-password' required></label>"
        f"<button type='submit'>登录</button></form>"
        f"<small>主管理员与子管理员使用同一个登录入口。会话通过 HttpOnly Cookie 保存。</small>"
    )
    return _admin_auth_shell("登录", "请输入后台管理员账号。", body)


def admin_setup_page(message: str = "", actor: dict[str, Any] | None = None) -> str:
    message_html = f"<p class='msg'>{html.escape(message)}</p>" if message else ""
    actor = actor or {"username": runtime.ADMIN_USERNAME, "role": "primary", "needsSetup": True}
    csrf_value = html.escape(admin_csrf_token(load_config(), actor), quote=True)
    body = (
        f"{message_html}"
        f"<form method='post' action='/admin/setup'>"
        f"<input type='hidden' name='csrf_token' value='{csrf_value}'>"
        f"<label>主管理员用户名<input name='username' value='{html.escape(runtime.ADMIN_USERNAME, quote=True)}' autocomplete='username' required></label>"
        f"<label>新密码（至少 12 位）<input type='password' name='password' minlength='12' autocomplete='new-password' required></label>"
        f"<label>确认新密码<input type='password' name='password_confirm' minlength='12' autocomplete='new-password' required></label>"
        f"<button type='submit'>完成初始化</button></form>"
        f"<small>初始化信息保存于 /data/config/server.json，密码只保存 PBKDF2 哈希。完成后环境变量中的引导密码立即失效。</small>"
    )
    return _admin_auth_shell(
        "首次设置主管理员",
        "环境变量中的账号仅用于首次引导，完成后改用数据卷内保存的加盐密码哈希。",
        body,
    )


def admin_csrf_token(config: dict[str, Any], actor: dict[str, Any]) -> str:
    """延迟导入 security：外壳要渲染 CSRF 隐藏域，而 security 又要用外壳渲染错误页。"""
    from app.security import admin_csrf_token as issue

    return issue(config, actor)
