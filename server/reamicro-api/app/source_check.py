import ipaddress
import json
import socket
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from typing import Any

from app import runtime
from app.audit import audit_event
from app.packages import _package_manifests, normalize_source_domain, safe_package_segment

CHECK_TIMEOUT_SECONDS = 10
CHECK_MAX_BYTES = 64 * 1024
CHECK_USER_AGENT = "ReaMicro-Server-SourceCheck/1.0"


STATUS_OK = "ok"
STATUS_SLOW = "slow"
STATUS_UNREACHABLE = "unreachable"
STATUS_BLOCKED = "blocked"
STATUS_SKIPPED = "skipped"

STATUS_LABELS = {
    STATUS_OK: "可用",
    STATUS_SLOW: "缓慢",
    STATUS_UNREACHABLE: "不可达",
    STATUS_BLOCKED: "地址被拒",
    STATUS_SKIPPED: "未检测",
}

SLOW_THRESHOLD_MS = 3_000


def is_public_host(host: str) -> bool:

    if not host:
        return False
    try:
        infos = socket.getaddrinfo(host, None)
    except (socket.gaierror, UnicodeError):
        return False
    for info in infos:
        address = info[4][0]
        try:
            parsed = ipaddress.ip_address(address)
        except ValueError:
            return False
        if parsed.is_loopback or parsed.is_private or parsed.is_link_local or parsed.is_reserved or parsed.is_multicast:
            return False
    return True


def check_url(url: str, timeout: int = CHECK_TIMEOUT_SECONDS) -> dict[str, Any]:

    started = datetime.now(timezone.utc)
    result = {"url": url, "status": STATUS_SKIPPED, "httpStatus": 0, "elapsedMs": 0, "message": ""}
    parsed = urllib.parse.urlparse(url if "://" in url else f"https://{url}")
    if parsed.scheme not in {"http", "https"}:
        result.update(status=STATUS_BLOCKED, message="只支持 http/https")
        return result
    if not is_public_host(parsed.hostname or ""):
        result.update(status=STATUS_BLOCKED, message="域名无法解析或指向内网地址")
        return result
    target = parsed.geturl()
    request = urllib.request.Request(target, method="GET", headers={
        "User-Agent": CHECK_USER_AGENT,
        "Accept": "text/html,application/json;q=0.9,*/*;q=0.8",
    })

    class _NoRedirect(urllib.request.HTTPRedirectHandler):


        def redirect_request(self, req, fp, code, msg, headers, newurl):
            if urllib.parse.urlparse(newurl).hostname != parsed.hostname:
                return None
            return super().redirect_request(req, fp, code, msg, headers, newurl)

    opener = urllib.request.build_opener(_NoRedirect)
    try:
        with opener.open(request, timeout=timeout) as stream:
            stream.read(CHECK_MAX_BYTES)
            result["httpStatus"] = stream.status
    except urllib.error.HTTPError as error:
        result["httpStatus"] = error.code
    except (urllib.error.URLError, socket.timeout, TimeoutError, OSError, ValueError) as error:
        elapsed = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
        reason = getattr(error, "reason", error)
        result.update(status=STATUS_UNREACHABLE, elapsedMs=elapsed, message=str(reason)[:120])
        return result
    elapsed = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
    result["elapsedMs"] = elapsed
    code = result["httpStatus"]
    if 200 <= code < 400:
        result["status"] = STATUS_SLOW if elapsed >= SLOW_THRESHOLD_MS else STATUS_OK
        result["message"] = f"HTTP {code}"
    elif code in (401, 403):

        result.update(status=STATUS_SLOW, message=f"HTTP {code}（站点可达但拒绝匿名访问）")
    else:
        result.update(status=STATUS_UNREACHABLE, message=f"HTTP {code}")
    return result


def manifest_check_targets(manifest: dict[str, Any]) -> list[str]:

    targets: list[str] = []
    for value in manifest.get("domains", []) or []:
        domain = normalize_source_domain(str(value))
        if domain and domain not in targets:
            targets.append(domain)

    if not targets:
        fallback = normalize_source_domain(str(manifest.get("contentId", "")))
        if fallback:
            targets.append(fallback)
    return targets


def check_package(kind: str, package_id: str, timeout: int = CHECK_TIMEOUT_SECONDS) -> dict[str, Any]:

    kind = safe_package_segment(kind)
    package_id = safe_package_segment(package_id)
    manifest_path = runtime.PACKAGE_ROOT / kind / package_id / "manifest.json"
    if not manifest_path.is_file():
        return {"packageId": package_id, "kind": kind, "status": STATUS_SKIPPED, "results": [], "message": "内容包不存在"}
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"packageId": package_id, "kind": kind, "status": STATUS_SKIPPED, "results": [], "message": "清单无法解析"}
    targets = manifest_check_targets(manifest)
    results = [check_url(target, timeout) for target in targets]
    overall = summarize(results)
    manifest["healthCheck"] = {
        "checkedAt": int(datetime.now(timezone.utc).timestamp() * 1000),
        "status": overall,
        "results": results,
    }
    temp = manifest_path.with_suffix(".tmp")
    temp.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    temp.replace(manifest_path)
    return {
        "packageId": package_id,
        "kind": kind,
        "name": str(manifest.get("name", package_id)),
        "status": overall,
        "results": results,
        "message": "" if targets else "该内容包没有可检测的地址",
    }


def summarize(results: list[dict[str, Any]]) -> str:

    if not results:
        return STATUS_SKIPPED
    for status in (STATUS_OK, STATUS_SLOW):
        if any(item.get("status") == status for item in results):
            return status
    if all(item.get("status") == STATUS_BLOCKED for item in results):
        return STATUS_BLOCKED
    return STATUS_UNREACHABLE


def check_kind(kind: str, timeout: int = CHECK_TIMEOUT_SECONDS, limit: int = 200) -> list[dict[str, Any]]:

    kind = safe_package_segment(kind)
    reports = []
    for path, manifest in _package_manifests(kind)[:limit]:
        reports.append(check_package(kind, str(manifest.get("packageId", path.parent.name)), timeout))
    audit_event("source_checked", metadata={
        "kind": kind,
        "total": len(reports),
        "unreachable": sum(1 for item in reports if item["status"] == STATUS_UNREACHABLE),
    })
    return reports


def stored_health(manifest: dict[str, Any]) -> dict[str, Any]:

    health = manifest.get("healthCheck")
    if not isinstance(health, dict):
        return {"status": STATUS_SKIPPED, "checkedAt": 0, "results": []}
    return {
        "status": str(health.get("status", STATUS_SKIPPED)),
        "checkedAt": health.get("checkedAt", 0),
        "results": health.get("results", []) if isinstance(health.get("results"), list) else [],
    }
