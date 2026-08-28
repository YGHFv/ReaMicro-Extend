"""书源规则级检测。

`source_check` 只验证域名可达——但站点最常见的失效方式是**改版**：域名还在、首页 200，
搜索接口的字段结构变了，`ruleSearch` 取不到任何东西。那种源在列表里显示"可用"，
实际已经搜不出书了。

这里真的发一次搜索请求，按书源自己的规则解析响应，看还能不能取出书名。判定分四类：

- `rules_ok`      规则命中，取到了书名
- `rules_stale`   请求成功但规则取不到条目 —— 站点大概率改版了
- `unsupported`   规则依赖 JS 脚本，服务端没有 JS 引擎，不做判定（不算失效）
- 其余            沿用 source_check 的 `unreachable` / `blocked`

**SSRF 防护**沿用 `source_check.check_url` 那一套：目标 URL 来自上传的书源内容，
等于让服务器访问任意地址，所以只允许 http/https、拒绝内网与回环、不跟随跨主机跳转。
"""
import json
import re
import socket
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from typing import Any

from app import jsonpath
from app.audit import audit_event
from app.packages import _package_manifests, ordered_domains, safe_package_segment
from app.source_check import (
    CHECK_MAX_BYTES,
    CHECK_TIMEOUT_SECONDS,
    STATUS_BLOCKED,
    STATUS_SKIPPED,
    STATUS_UNREACHABLE,
    is_public_host,
)
from app import runtime

STATUS_RULES_OK = "rules_ok"
STATUS_RULES_STALE = "rules_stale"
STATUS_UNSUPPORTED = "unsupported"

RULE_STATUS_LABELS = {
    STATUS_RULES_OK: "规则可用",
    STATUS_RULES_STALE: "规则失效",
    STATUS_UNSUPPORTED: "无法检测",
    STATUS_UNREACHABLE: "不可达",
    STATUS_BLOCKED: "地址被拒",
    STATUS_SKIPPED: "未检测",
}

# 用于试搜的关键词。挑常见字，绝大多数中文书站都能返回若干结果。
DEFAULT_PROBE_QUERY = "剑"
USER_AGENT = "ReaMicro-Server-RuleCheck/1.0"
# 脚本型规则的标记。命中就判 unsupported，不假装检测成功。
SCRIPT_MARKERS = ("@js:", "<js>", "@JS:", "<JS>")


def _is_script(value: str) -> bool:
    text = str(value or "")
    return any(marker in text for marker in SCRIPT_MARKERS)


def parse_search_url(raw: str) -> dict[str, Any]:
    """解析 searchUrl。

    形态是 `url` 后面可选跟一个 legado 风格的 options 块：
    `url,{"method":"POST","body":"key={{key}}","charset":"gbk","headers":{...}}`
    与模块端 `buildOnlineSearchRequest` 保持一致。
    """
    text = str(raw or "").strip()
    if not text:
        return {"error": "书源没有 searchUrl"}
    if _is_script(text):
        return {"error": "searchUrl 依赖脚本，服务端无法执行"}
    # 只取第一行非空内容，与模块一致。
    line = next((part.strip() for part in text.splitlines() if part.strip()), "")
    if not line:
        return {"error": "searchUrl 为空"}
    split_at = _options_block_index(line)
    url_part = line if split_at < 0 else line[:split_at].strip()
    options: dict[str, Any] = {}
    if split_at >= 0:
        blob = line[split_at:].strip().removeprefix(",").strip()
        try:
            parsed = json.loads(blob)
            if isinstance(parsed, dict):
                options = parsed
        except ValueError:
            # options 解析不了就按纯 GET 处理，不因为附加参数写坏而判整个源失效。
            options = {}
    headers = options.get("headers")
    return {
        "url": url_part,
        "method": str(options.get("method", "GET")).upper() or "GET",
        "body": str(options.get("body", "")),
        "charset": str(options.get("charset", "UTF-8")) or "UTF-8",
        "headers": {str(k): str(v) for k, v in headers.items()} if isinstance(headers, dict) else {},
    }


def _options_block_index(raw: str) -> int:
    """找到 options 块起始位置。与模块端 indexOfOptionsBlock 同义。"""
    for pattern in (",{", ", {"):
        found = raw.find(pattern)
        if found >= 0:
            return found
    return -1


def apply_template(template: str, query: str, base_url: str, charset: str = "UTF-8") -> str:
    """替换书源模板占位符。

    只处理检测需要的几个：`{{key}}` / `{{page}}` / `searchKey` / `searchPage`。
    页码统一取 1，检测只看第一页够不够取到书名。
    """
    encoded = urllib.parse.quote(query, encoding=charset, errors="replace")
    result = str(template or "")
    replacements = {
        "{{key}}": encoded,
        "{{keyword}}": encoded,
        "{{searchKey}}": encoded,
        "searchKey": encoded,
        "{{page}}": "1",
        "{{searchPage}}": "1",
        "searchPage": "1",
        "{{baseUrl}}": base_url,
    }
    for needle, value in replacements.items():
        result = result.replace(needle, value)
    # 去掉未识别的 {{...}} 表达式：留着会让 URL 非法，去掉至少能发出请求。
    return re.sub(r"\{\{[^}]*\}\}", "", result)


def fetch(request_spec: dict[str, Any], base_url: str, query: str, timeout: int) -> dict[str, Any]:
    """按书源的请求配置发一次搜索。返回 {status, httpStatus, body, message}。"""
    charset = request_spec.get("charset", "UTF-8")
    url = apply_template(request_spec["url"], query, base_url, charset)
    if "://" not in url:
        url = urllib.parse.urljoin(base_url if base_url.endswith("/") else base_url + "/", url.lstrip("/"))
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        return {"status": STATUS_BLOCKED, "message": "只支持 http/https", "body": ""}
    if not is_public_host(parsed.hostname or ""):
        return {"status": STATUS_BLOCKED, "message": "域名无法解析或指向内网地址", "body": ""}

    data = None
    if request_spec.get("method") == "POST" and request_spec.get("body"):
        rendered = apply_template(request_spec["body"], query, base_url, charset)
        data = rendered.encode(charset, errors="replace")
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "application/json,text/html;q=0.9,*/*;q=0.8",
        **request_spec.get("headers", {}),
    }
    if data is not None and "Content-Type" not in headers:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    http_request = urllib.request.Request(url, data=data, method=request_spec.get("method", "GET"), headers=headers)

    class _NoCrossHostRedirect(urllib.request.HTTPRedirectHandler):
        """不跟随跨主机跳转，否则内网检查可以被绕过。"""

        def redirect_request(self, req, fp, code, msg, hdrs, newurl):
            if urllib.parse.urlparse(newurl).hostname != parsed.hostname:
                return None
            return super().redirect_request(req, fp, code, msg, hdrs, newurl)

    opener = urllib.request.build_opener(_NoCrossHostRedirect)
    try:
        with opener.open(http_request, timeout=timeout) as stream:
            payload = stream.read(CHECK_MAX_BYTES)
            http_status = stream.status
    except urllib.error.HTTPError as error:
        payload = error.read(CHECK_MAX_BYTES) if hasattr(error, "read") else b""
        http_status = error.code
    except (urllib.error.URLError, socket.timeout, TimeoutError, OSError, ValueError) as error:
        reason = getattr(error, "reason", error)
        return {"status": STATUS_UNREACHABLE, "message": str(reason)[:120], "body": "", "url": url}
    if http_status >= 400:
        return {
            "status": STATUS_UNREACHABLE,
            "httpStatus": http_status,
            "message": f"HTTP {http_status}",
            "body": "",
            "url": url,
        }
    return {
        "status": "fetched",
        "httpStatus": http_status,
        "body": payload.decode(charset, errors="replace"),
        "message": "",
        "url": url,
    }


def extract_results(body: str, rule_search: str) -> dict[str, Any]:
    """按 ruleSearch 从响应里取书名。

    JSON 走移植过来的 JSONPath；不是 JSON 就退化为 HTML 锚点扫描，
    与模块端 `parseOnlineHtmlResults` 的兜底思路一致。
    """
    try:
        rules = json.loads(rule_search) if rule_search.strip() else {}
    except ValueError:
        rules = {}
    if not isinstance(rules, dict):
        rules = {}
    book_list_rule = str(rules.get("bookList", ""))
    name_rule = str(rules.get("name", ""))
    if _is_script(book_list_rule) or _is_script(name_rule):
        return {"kind": "script", "names": [], "total": 0}

    stripped = body.lstrip()
    if stripped[:1] in {"{", "["}:
        try:
            document = json.loads(body)
        except ValueError:
            document = None
        if document is not None:
            nodes = jsonpath.rule_values(document, book_list_rule) if book_list_rule else []
            if not nodes and not book_list_rule:
                # 没写 bookList 时试常见包裹层，尽量给出有意义的判定。
                for candidate in jsonpath.candidate_roots(document):
                    if isinstance(candidate, list) and candidate:
                        nodes = candidate
                        break
            names = []
            for node in nodes:
                text = jsonpath.rule_string(node, name_rule) if name_rule else ""
                if not text and isinstance(node, dict):
                    for key in ("bookName", "name", "title", "bookTitle", "novelName"):
                        if str(node.get(key, "")).strip():
                            text = str(node[key]).strip()
                            break
                if text and len(text) <= 120:
                    names.append(text)
            return {"kind": "json", "names": names[:20], "total": len(nodes)}

    # HTML 兜底：数一数带 href 的锚点文字，只用于判断"页面里有没有条目"。
    anchors = re.findall(r"(?is)<a\b[^>]*\bhref\s*=\s*[\"'][^\"']+[\"'][^>]*>(.*?)</a>", body)
    names = []
    for raw in anchors:
        text = re.sub(r"(?s)<[^>]*>", "", raw)
        text = re.sub(r"\s+", " ", text).strip()
        if text and len(text) <= 120:
            names.append(text)
    return {"kind": "html", "names": names[:20], "total": len(names)}


def check_source_rules(
    manifest: dict[str, Any],
    payload: dict[str, Any],
    query: str = DEFAULT_PROBE_QUERY,
    timeout: int = CHECK_TIMEOUT_SECONDS,
) -> dict[str, Any]:
    """对单个书源做规则级检测。`payload` 是书源 JSON 解析后的字典。"""
    started = datetime.now(timezone.utc)
    search_url = str(payload.get("searchUrl", "") or payload.get("searchURL", "") or payload.get("search", ""))
    rule_search = payload.get("ruleSearch")
    if isinstance(rule_search, (dict, list)):
        rule_search = json.dumps(rule_search, ensure_ascii=False)
    rule_search = str(rule_search or "")

    result: dict[str, Any] = {
        "status": STATUS_SKIPPED,
        "message": "",
        "names": [],
        "matched": 0,
        "elapsedMs": 0,
        "query": query,
    }
    spec = parse_search_url(search_url)
    if "error" in spec:
        result.update(status=STATUS_UNSUPPORTED, message=spec["error"])
        return result
    if _is_script(rule_search):
        result.update(status=STATUS_UNSUPPORTED, message="ruleSearch 依赖脚本，服务端无法执行")
        return result

    domains = ordered_domains(manifest)
    base_url = str(payload.get("bookSourceUrl", "") or (f"https://{domains[0]}" if domains else ""))
    if not base_url:
        result.update(status=STATUS_UNSUPPORTED, message="书源没有可用的基础地址")
        return result

    fetched = fetch(spec, base_url, query, timeout)
    result["elapsedMs"] = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
    result["url"] = fetched.get("url", "")
    result["httpStatus"] = fetched.get("httpStatus", 0)
    if fetched["status"] != "fetched":
        result.update(status=fetched["status"], message=fetched["message"])
        return result

    extracted = extract_results(fetched["body"], rule_search)
    if extracted["kind"] == "script":
        result.update(status=STATUS_UNSUPPORTED, message="ruleSearch 依赖脚本，服务端无法执行")
        return result
    result["names"] = extracted["names"][:5]
    result["matched"] = len(extracted["names"])
    if extracted["names"]:
        result.update(status=STATUS_RULES_OK, message=f"取到 {len(extracted['names'])} 条结果")
    elif extracted["kind"] == "html" and not rule_search.strip():
        # 没有规则可判的 HTML 源：能取到锚点就算通，取不到也不能断言规则失效。
        result.update(status=STATUS_UNSUPPORTED, message="HTML 书源缺少 ruleSearch，无法判定规则")
    else:
        result.update(
            status=STATUS_RULES_STALE,
            message=f"请求成功（HTTP {result['httpStatus']}）但规则取不到条目，站点可能已改版",
        )
    return result


def load_package_payload(kind: str, package_id: str) -> dict[str, Any] | None:
    """读出内容包的书源 JSON。不是 JSON 或读不到时返回 None。"""
    directory = runtime.PACKAGE_ROOT / safe_package_segment(kind) / safe_package_segment(package_id)
    manifest_path = directory / "manifest.json"
    if not manifest_path.is_file():
        return None
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        payload_path = directory / str(manifest.get("payload", ""))
        if not payload_path.is_file() or payload_path.parent != directory:
            return None
        parsed = json.loads(payload_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if isinstance(parsed, list):
        parsed = next((item for item in parsed if isinstance(item, dict)), None)
    return parsed if isinstance(parsed, dict) else None


def check_package_rules(kind: str, package_id: str, query: str = DEFAULT_PROBE_QUERY,
                        timeout: int = CHECK_TIMEOUT_SECONDS) -> dict[str, Any]:
    """检测一个内容包的规则，并把结果写回清单的 `ruleCheck`。"""
    kind = safe_package_segment(kind)
    package_id = safe_package_segment(package_id)
    manifest_path = runtime.PACKAGE_ROOT / kind / package_id / "manifest.json"
    base = {"kind": kind, "packageId": package_id, "status": STATUS_SKIPPED, "message": "", "names": []}
    if not manifest_path.is_file():
        return {**base, "message": "内容包不存在"}
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {**base, "message": "清单无法解析"}
    base["name"] = str(manifest.get("name", package_id))
    if kind != "online_source":
        return {**base, "status": STATUS_UNSUPPORTED, "message": "只有书源支持规则检测"}
    payload = load_package_payload(kind, package_id)
    if payload is None:
        return {**base, "status": STATUS_UNSUPPORTED, "message": "书源内容不是 JSON，无法解析规则"}

    report = check_source_rules(manifest, payload, query=query, timeout=timeout)
    manifest["ruleCheck"] = {
        "checkedAt": int(datetime.now(timezone.utc).timestamp() * 1000),
        "status": report["status"],
        "matched": report.get("matched", 0),
        "message": report.get("message", ""),
        "query": report.get("query", query),
        "samples": report.get("names", [])[:5],
    }
    temp = manifest_path.with_suffix(".tmp")
    temp.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    temp.replace(manifest_path)
    return {**base, **report}


def check_kind_rules(kind: str, query: str = DEFAULT_PROBE_QUERY,
                     timeout: int = CHECK_TIMEOUT_SECONDS, limit: int = 100) -> list[dict[str, Any]]:
    """批量检测一类内容包的规则。串行执行，避免同时对多个站点发搜索请求。"""
    kind = safe_package_segment(kind)
    reports = []
    for path, manifest in _package_manifests(kind)[:limit]:
        reports.append(check_package_rules(kind, str(manifest.get("packageId", path.parent.name)), query, timeout))
    audit_event("source_rules_checked", metadata={
        "kind": kind,
        "total": len(reports),
        "stale": sum(1 for item in reports if item["status"] == STATUS_RULES_STALE),
    })
    return reports


def stored_rule_check(manifest: dict[str, Any]) -> dict[str, Any]:
    """读回上次规则检测结果，供列表显示，不触发网络请求。"""
    stored = manifest.get("ruleCheck")
    if not isinstance(stored, dict):
        return {"status": STATUS_SKIPPED, "checkedAt": 0, "matched": 0, "message": "", "samples": []}
    return {
        "status": str(stored.get("status", STATUS_SKIPPED)),
        "checkedAt": stored.get("checkedAt", 0),
        "matched": stored.get("matched", 0),
        "message": str(stored.get("message", "")),
        "samples": stored.get("samples", []) if isinstance(stored.get("samples"), list) else [],
    }
