"""后台样式表。

集中在这里而不是散在各视图里，保证所有页面外观一致。几条约定：

- **状态徽标只走 `.tone-*` 五种语义色调**（见 `labels.status_tone`）。不要把原始枚举值
  当类名——新增一个枚举值就会渲染成无样式的灰块，而且不报错、很难发现。
- **间距用 `--gap-*` 变量和 `.stack` / `.mt` / `.mb` 等语义类**，不要写内联 style，
  否则同一种间距会在各处漂移。
- 深色模式跟随系统 `prefers-color-scheme`，靠覆盖 `:root` 变量实现，不复制一份规则。
"""

# 设计令牌。深浅两套只换变量值，规则本身共用。
_TOKENS = """
:root{
  --bg:#f4f7fb; --surface:#fff; --surface-2:#fafbfc; --surface-3:#f8fafc;
  --ink:#1f2937; --ink-2:#334155; --muted:#64748b; --faint:#94a3b8;
  --line:#e5e9f0; --line-strong:#cfd6e1;
  --brand:#2563eb; --brand-ink:#1d4ed8; --brand-soft:#e8f0ff;
  --ok-bg:#ecfdf3; --ok-fg:#047857;
  --warn-bg:#fff7ed; --warn-fg:#b45309;
  --bad-bg:#fef2f2; --bad-fg:#b91c1c;
  --idle-bg:#f1f5f9; --idle-fg:#475569;
  --info-bg:#eff6ff; --info-fg:#1d4ed8;
  --code-bg:#0f172a; --code-fg:#e2e8f0;
  --gap-1:6px; --gap-2:10px; --gap-3:14px; --gap-4:18px; --gap-5:26px;
  --radius:8px; --radius-sm:5px;
  --shadow:0 1px 2px rgba(15,23,42,.04);
  --focus:0 0 0 3px rgba(37,99,235,.35);
  --sidebar:236px;
}
@media (prefers-color-scheme: dark){
  :root{
    --bg:#0f172a; --surface:#151d2e; --surface-2:#1a2334; --surface-3:#131b2a;
    --ink:#e6ebf4; --ink-2:#c2cbdb; --muted:#93a1b8; --faint:#6b7a92;
    --line:#26314a; --line-strong:#33405c;
    --brand:#4d84f5; --brand-ink:#8fb4ff; --brand-soft:#1c2a47;
    --ok-bg:#0d2a1f; --ok-fg:#5bd6a0;
    --warn-bg:#2e2210; --warn-fg:#f0b45e;
    --bad-bg:#2e1417; --bad-fg:#f78d92;
    --idle-bg:#1c2436; --idle-fg:#a5b2c8;
    --info-bg:#152442; --info-fg:#8fb4ff;
    --code-bg:#0a1120; --code-fg:#d5dEeb;
    --shadow:0 1px 2px rgba(0,0,0,.3);
  }
}
"""

_BASE = """
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);line-height:1.55;
  font-family:system-ui,-apple-system,'Segoe UI','Microsoft YaHei',sans-serif;
  -webkit-font-smoothing:antialiased}
h1{margin:3px 0 5px;font-size:26px;line-height:1.25;letter-spacing:-.01em}
h2{margin:0 0 var(--gap-3);font-size:19px;line-height:1.3}
h3{margin:var(--gap-4) 0 var(--gap-2);font-size:15px}
p{margin:0 0 var(--gap-2)}
p:last-child{margin-bottom:0}
a{color:var(--brand-ink)}
.eyebrow{color:var(--brand);font-size:12px;font-weight:700;margin:0;letter-spacing:.04em}
.muted,small{color:var(--muted)}
small{font-size:11.5px;line-height:1.45}
code{font-family:ui-monospace,'Cascadia Mono',Consolas,monospace;font-size:12px}
pre{white-space:pre-wrap;overflow:auto;background:var(--code-bg);color:var(--code-fg);
  border-radius:var(--radius);padding:var(--gap-4);line-height:1.55;font-size:12.5px}
:focus-visible{outline:none;box-shadow:var(--focus);border-radius:var(--radius-sm)}
"""

_LAYOUT = """
aside{position:fixed;inset:0 auto 0 0;width:var(--sidebar);padding:22px 14px;
  background:var(--surface-3);border-right:1px solid var(--line);overflow-y:auto}
.brand{font-size:18px;font-weight:700;padding:0 12px 22px;letter-spacing:-.01em}
.brand small{display:block;color:var(--muted);font-size:11px;margin-top:5px;font-weight:400}
nav a{display:flex;justify-content:space-between;align-items:center;gap:8px;
  padding:9px 12px;margin:2px 0;border-radius:var(--radius-sm);color:var(--ink-2);
  text-decoration:none;font-size:14px;transition:background .12s,color .12s}
nav a:hover{background:var(--brand-soft);color:var(--brand-ink)}
nav a.active{background:var(--brand-soft);color:var(--brand-ink);font-weight:650}
nav span{color:var(--faint);font-size:11px;font-variant-numeric:tabular-nums}
main{margin-left:var(--sidebar);max-width:1480px;padding:26px 34px 56px}
.topbar{display:flex;justify-content:flex-end;align-items:center;gap:var(--gap-3);
  color:var(--muted);font-size:13px;margin-bottom:var(--gap-4);flex-wrap:wrap}
.page-head{display:flex;justify-content:space-between;align-items:flex-start;
  gap:20px;margin-bottom:var(--gap-4)}
.api-tag{margin:0 0 var(--gap-3);text-align:right;font-size:12px;color:var(--muted)}
"""

_SURFACES = """
.panel,.table-wrap,.stats>div,.notice,.secret{background:var(--surface);
  border:1px solid var(--line);border-radius:var(--radius);box-shadow:var(--shadow)}
.panel{padding:var(--gap-4);margin-bottom:var(--gap-4)}
.notice,.secret{padding:var(--gap-4);margin-bottom:var(--gap-4);border-radius:var(--radius)}
.notice{background:var(--ok-bg);color:var(--ok-fg);border-color:transparent}
.secret{background:var(--warn-bg);color:var(--warn-fg);border-color:transparent;white-space:pre-wrap}
/* auto-fit：卡片数量变化时不用改 CSS，也不用内联覆盖列数 */
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));
  gap:var(--gap-3);margin-bottom:var(--gap-4)}
.stats>div{padding:16px 18px}
.stats strong{display:block;font-size:23px;line-height:1.25;font-variant-numeric:tabular-nums}
.stats span{color:var(--muted);font-size:13px}
.empty{padding:30px;text-align:center;color:var(--muted)}
fieldset{border:1px solid var(--line);border-radius:var(--radius-sm);
  padding:var(--gap-3);margin:0;display:flex;flex-direction:column;gap:var(--gap-2)}
legend{padding:0 6px;font-size:13px;font-weight:650;color:var(--ink-2)}
details summary{cursor:pointer;font-size:13px;font-weight:650;color:var(--ink-2)}
"""

_TABLE = """
.table-wrap{overflow:auto;margin-bottom:var(--gap-4)}
table{width:100%;border-collapse:separate;border-spacing:0;min-width:760px}
th,td{padding:12px 14px;text-align:left;font-size:13px;vertical-align:top;
  border-bottom:1px solid var(--line)}
th{background:var(--surface-2);color:var(--muted);font-weight:650;white-space:nowrap;
  position:sticky;top:0;z-index:1}
tbody tr:last-child td{border-bottom:0}
tbody tr:hover td{background:var(--surface-2)}
td small{display:block;margin-top:3px}
td code{word-break:break-all}
.actions{white-space:nowrap}
.actions .button{margin:2px 2px 2px 0}
.col-narrow{width:150px}
.col-mid{width:170px}
.col-wide{min-width:320px}
.table-plain{min-width:auto}
"""

_FORMS = """
input,textarea,select{width:100%;padding:9px 11px;border:1px solid var(--line-strong);
  border-radius:var(--radius-sm);background:var(--surface);color:var(--ink);font:inherit;
  transition:border-color .12s,box-shadow .12s}
input:hover,textarea:hover,select:hover{border-color:var(--brand)}
input:focus,textarea:focus,select:focus{outline:none;border-color:var(--brand);box-shadow:var(--focus)}
textarea{min-height:110px;resize:vertical;
  font-family:ui-monospace,'Cascadia Mono',Consolas,monospace;font-size:12.5px;line-height:1.5}
.textarea-sm{min-height:70px}
.textarea-md{min-height:90px}
.textarea-lg{min-height:320px}
label{display:flex;flex-direction:column;gap:5px;min-width:0;font-size:13px;
  font-weight:600;color:var(--ink-2)}
label:has(input[type=checkbox]){flex-direction:row;align-items:center;gap:8px;font-weight:500}
input[type=checkbox]{width:auto;accent-color:var(--brand)}
.grid-2{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));
  gap:var(--gap-3);margin-bottom:var(--gap-3)}
.panel form{display:flex;flex-direction:column;gap:var(--gap-3)}
.panel form.inline{display:inline-flex;flex-direction:row;align-items:center;
  gap:var(--gap-1);margin:0}
.panel form .button{align-self:flex-start}
.toolbar{display:flex;gap:8px;align-items:center;margin-bottom:var(--gap-2);flex-wrap:wrap}
.toolbar input{width:min(420px,100%)}
"""

_BUTTONS = """
.button{display:inline-flex;align-items:center;justify-content:center;gap:6px;
  min-height:38px;padding:9px 15px;border:1px solid transparent;
  border-radius:var(--radius-sm);background:var(--brand);color:#fff;text-decoration:none;
  font:inherit;font-weight:650;cursor:pointer;white-space:nowrap;
  transition:filter .12s,background .12s,border-color .12s}
.button:hover{filter:brightness(1.08)}
.button:active{filter:brightness(.94)}
.button:focus-visible{box-shadow:var(--focus)}
.button.subtle{background:var(--idle-bg);color:var(--ink-2);border-color:var(--line);
  padding:6px 10px;min-height:32px;font-size:12px}
.button.subtle:hover{background:var(--brand-soft);color:var(--brand-ink);border-color:var(--brand)}
.button.danger{background:var(--bad-bg);color:var(--bad-fg);border-color:transparent}
.button.danger:hover{filter:brightness(.97);border-color:var(--bad-fg)}
.inline{display:inline-flex;gap:6px;align-items:center}
"""

_TONES = """
.status{display:inline-block;padding:3px 8px;border-radius:999px;font-size:11.5px;
  font-weight:600;white-space:nowrap;background:var(--idle-bg);color:var(--idle-fg)}
.tone-ok{background:var(--ok-bg);color:var(--ok-fg)}
.tone-warn{background:var(--warn-bg);color:var(--warn-fg)}
.tone-bad{background:var(--bad-bg);color:var(--bad-fg)}
.tone-idle{background:var(--idle-bg);color:var(--idle-fg)}
.tone-info{background:var(--info-bg);color:var(--info-fg)}
"""

# 语义间距类，替代散落的内联 style。
_UTILITIES = """
.stack{display:flex;flex-direction:column;gap:var(--gap-3)}
.mt{margin-top:var(--gap-2)}
.mb{margin-bottom:var(--gap-3)}
.mb-lg{margin-bottom:var(--gap-4)}
.flush{margin:0}
.hint{margin:0 0 var(--gap-2);font-size:12px;color:var(--muted)}
.hint-tight{margin:0;font-size:12px;color:var(--muted)}
.section-head{margin:var(--gap-5) 0 var(--gap-3)}
.pager{display:flex;align-items:center;gap:var(--gap-1);flex-wrap:wrap;margin-bottom:var(--gap-4)}
.pager .hint-tight{margin:0 var(--gap-2)}
.pager [aria-disabled=true]{opacity:.45;cursor:default;pointer-events:none}
.batch-bar{display:flex;align-items:center;gap:var(--gap-2);flex-wrap:wrap;
  padding:10px var(--gap-3);margin-bottom:var(--gap-2);background:var(--surface);
  border:1px solid var(--line);border-radius:var(--radius);box-shadow:var(--shadow)}
.batch-bar select{width:auto;min-width:120px}
.col-check{width:38px}
.col-check input{margin:0}
"""

_RESPONSIVE = """
@media(max-width:900px){
  :root{--sidebar:200px}
  main{padding:22px 18px 48px}
}
@media(max-width:620px){
  aside{position:static;width:auto;border-right:0;border-bottom:1px solid var(--line);padding:16px 12px}
  .brand{padding-bottom:14px}
  main{margin-left:0;padding:18px 14px 40px}
  nav{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:2px}
  .topbar{justify-content:flex-start}
  .page-head{display:block}
  .toolbar{align-items:stretch;flex-direction:column}
  .toolbar input{width:100%}
  th{position:static}
}
@media(prefers-reduced-motion:reduce){
  *{transition:none!important}
}
"""

ADMIN_STYLE = "".join(
    part.strip() for part in (
        _TOKENS, _BASE, _LAYOUT, _SURFACES, _TABLE, _FORMS, _BUTTONS, _TONES, _UTILITIES, _RESPONSIVE,
    )
)

# 登录与初始化页：无侧栏，但配色和控件与后台同源。
ADMIN_AUTH_STYLE = "".join(
    part.strip() for part in (_TOKENS, _BASE, _SURFACES, _FORMS, _BUTTONS, _TONES, _UTILITIES)
) + """
body{display:flex;align-items:center;justify-content:center;padding:24px;min-height:100vh}
.auth-card{width:100%;max-width:420px;padding:30px}
.auth-card h1{font-size:22px;margin:0 0 6px}
.auth-card form{display:flex;flex-direction:column;gap:var(--gap-3);margin-top:var(--gap-4)}
.auth-card .button{width:100%}
"""
