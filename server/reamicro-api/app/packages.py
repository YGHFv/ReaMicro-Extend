"""内容包的清单管理、身份解析、元数据推断与签名。

**身份复用**是这里最关键的约定：上传时若能从名称、域名或稳定标识认出是已有包，
就复用它的 packageId 继续发新版本，而不是新建一个包——否则模块侧会出现两条重复源，
已下载图书与登录凭据全部失联。域名比对统一走 normalize_source_domain（去 www、转小写、
取 host），纯标识（不含点）不参与域名匹配。
"""
import hashlib
import io
import json
import re
import shutil
import unicodedata
import urllib.parse
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import HTTPException

from app import runtime
from app.config_store import load_config
from app.labels import _admin_kind_label
from app.responses import response


def comparable_version(value: Any) -> tuple[tuple[int, Any], ...]:
    parts = re.findall(r"\d+|[A-Za-z]+", str(value).lower())
    return tuple((1, int(part)) if part.isdigit() else (0, part) for part in parts)


def version_satisfies(version: Any, minimum: str = "", maximum: str = "") -> bool:
    current = comparable_version(version)
    return (not minimum or current >= comparable_version(minimum)) and (not maximum or current <= comparable_version(maximum))


def normalize_package_dependencies(items: Any, current_kind: str = "", current_package_id: str = "") -> list[dict[str, Any]]:
    if not isinstance(items, list):
        raise HTTPException(status_code=400, detail="依赖必须是 JSON 数组")
    normalized: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for item in items[:50]:
        if not isinstance(item, dict):
            raise HTTPException(status_code=400, detail="每项依赖必须是 JSON 对象")
        dependency_kind = str(item.get("kind", "")).strip()
        if dependency_kind and dependency_kind not in runtime.PACKAGE_KINDS:
            raise HTTPException(status_code=400, detail=f"依赖内容类型无效：{dependency_kind}")
        target = str(item.get("packageId") or item.get("contentId") or "").strip()
        if not target:
            raise HTTPException(status_code=400, detail="依赖缺少 packageId 或 contentId")
        target = safe_package_segment(target)
        if target == current_package_id and (not dependency_kind or dependency_kind == current_kind):
            raise HTTPException(status_code=400, detail="内容包不能依赖自身")
        minimum = str(item.get("minVersion", "")).strip()
        maximum = str(item.get("maxVersion", "")).strip()
        if minimum:
            minimum = safe_package_segment(minimum)
        if maximum:
            maximum = safe_package_segment(maximum)
        key = (dependency_kind, target)
        if key in seen:
            raise HTTPException(status_code=400, detail=f"依赖重复：{target}")
        seen.add(key)
        normalized.append({
            "kind": dependency_kind,
            "packageId": target,
            "minVersion": minimum,
            "maxVersion": maximum,
            "required": bool(item.get("required", True)),
        })
    return normalized


def published_package_manifests() -> list[dict[str, Any]]:
    manifests: list[dict[str, Any]] = []
    for kind in runtime.PACKAGE_KINDS:
        for path in (runtime.PACKAGE_ROOT / kind).glob("*/manifest.json"):
            try:
                manifest = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            if isinstance(manifest, dict) and str(manifest.get("status", "published")) == "published":
                manifest.setdefault("kind", kind)
                manifests.append(manifest)
    return manifests


def package_dependency_status(manifest: dict[str, Any]) -> dict[str, Any]:
    dependencies = normalize_package_dependencies(
        manifest.get("dependencies", []),
        str(manifest.get("kind", "")),
        str(manifest.get("packageId", "")),
    )
    available = published_package_manifests()
    resolved: list[dict[str, Any]] = []
    unresolved: list[dict[str, Any]] = []
    for dependency in dependencies:
        target = dependency["packageId"]
        candidates = []
        for candidate in available:
            identities = {
                str(candidate.get("packageId", "")),
                str(candidate.get("contentId", "")),
                *[str(alias) for alias in candidate.get("aliases", []) if alias],
            }
            if target not in identities or (dependency["kind"] and candidate.get("kind") != dependency["kind"]):
                continue
            candidates.append(candidate)
        compatible = [candidate for candidate in candidates if version_satisfies(candidate.get("version", ""), dependency["minVersion"], dependency["maxVersion"])]
        if compatible:
            selected = sorted(compatible, key=lambda item: comparable_version(item.get("version", "")), reverse=True)[0]
            resolved.append({**dependency, "resolvedKind": selected.get("kind"), "resolvedPackageId": selected.get("packageId"), "resolvedVersion": selected.get("version")})
        else:
            reason = "version_mismatch" if candidates else "not_found"
            unresolved.append({**dependency, "reason": reason, "availableVersions": sorted({str(item.get("version", "")) for item in candidates})})
    blocking = [item for item in unresolved if item.get("required", True)]
    return {"dependenciesSatisfied": not blocking, "resolvedDependencies": resolved, "unresolvedDependencies": unresolved}


def safe_package_segment(value: str) -> str:
    value = value.strip()
    if not value or len(value) > 120 or not value.replace("_", "").replace("-", "").replace(".", "").isalnum():
        raise HTTPException(status_code=400, detail="包标识只能包含字母、数字、点、下划线和短横线")
    return value


def safe_payload_filename(value: str) -> str:
    name = Path(value or "payload.bin").name
    name = "".join(char if (char.isalnum() or char in "._-") else "_" for char in name)
    return name[:120] or "payload.bin"


def normalize_content_id(value: str, fallback: str) -> str:
    """内容稳定 ID 不参与路径拼接，允许 URL/域名等源标识但拒绝控制字符。"""
    text = " ".join(str(value or fallback).replace("\r", " ").replace("\n", " ").split()).strip()
    if not text or len(text) > 240 or any(ord(char) < 32 for char in text):
        raise HTTPException(status_code=400, detail="稳定内容 ID 无效")
    return text


def _metadata_text(value: Any) -> str:
    """把内容元数据转成适合显示和匹配的单行文本。"""
    if value is None or isinstance(value, (dict, list)):
        return ""
    text = " ".join(str(value).replace("\r", " ").replace("\n", " ").split())
    return text[:160].strip()


def normalize_source_domain(value: Any) -> str:
    """把书源地址、域名或主机名统一成可比较的小写域名，无法识别时返回空串。"""
    text = _metadata_text(value)
    if not text:
        return ""
    if "://" in text:
        host = urllib.parse.urlsplit(text).netloc
    elif "/" in text:
        host = text.split("/", 1)[0]
    else:
        host = text
    host = host.rsplit("@", 1)[-1].strip().strip(".").lower()
    if host.startswith("["):
        host = host[1:].split("]", 1)[0]
    elif ":" in host:
        host = host.rsplit(":", 1)[0]
    if host.startswith("www."):
        host = host[4:]
    # 只有形如 example.com 或 IP 的值算域名，纯标识（youshu、qidian）不参与域名比对。
    if not host or " " in host or "." not in host:
        return ""
    if not re.fullmatch(r"[a-z0-9.\-]+", host):
        return ""
    return host[:120]


def package_match_domains(manifest: dict[str, Any]) -> set[str]:
    """内容包用于比对的域名集合，取自显式 domains 字段和历史 aliases、contentId。"""
    values: list[Any] = []
    for key in ("domains", "aliases"):
        entry = manifest.get(key)
        if isinstance(entry, (list, tuple, set)):
            values.extend(entry)
        elif entry:
            values.append(entry)
    values.append(manifest.get("contentId", ""))
    return {domain for domain in (normalize_source_domain(item) for item in values) if domain}


def infer_package_metadata(kind: str, filename: str, body: bytes) -> dict[str, Any]:
    """从书源 JSON、归档清单或样式文件中提取外显名称、稳定标识和域名。"""
    stem = Path(filename or "payload").stem
    objects: list[dict[str, Any]] = []
    if filename.lower().endswith((".json", ".json5")):
        try:
            parsed = json.loads(body.decode("utf-8"))
            if isinstance(parsed, dict):
                objects = [parsed]
            elif isinstance(parsed, list):
                objects = [item for item in parsed[:20] if isinstance(item, dict)]
        except (UnicodeDecodeError, ValueError):
            objects = []
    elif filename.lower().endswith((".rmsource", ".apk", ".jar", ".zip")):
        # 关联源是 ZIP 归档，名称和 ID 写在内部的 manifest.json 里。
        try:
            with zipfile.ZipFile(io.BytesIO(body)) as archive:
                parsed = json.loads(archive.read("manifest.json").decode("utf-8"))
            if isinstance(parsed, dict):
                objects = [parsed]
        except (KeyError, OSError, UnicodeDecodeError, ValueError, zipfile.BadZipFile):
            objects = []
    name_keys = (
        "name", "title", "displayName", "label", "bookSourceName", "sourceName",
        "styleName", "themeName", "epubStyleName", "highlightStyleName",
    )
    identity_keys = (
        "contentId", "sourceId", "bookSourceId", "id", "key", "bookSourceUrl",
        "sourceUrl", "url", "domain", "host",
    )
    name = ""
    explicit_name = False
    identity: list[str] = []
    for item in objects:
        if not name:
            for key in name_keys:
                name = _metadata_text(item.get(key))
                if name:
                    explicit_name = True
                    break
        for key in identity_keys:
            value = _metadata_text(item.get(key))
            if value and value not in identity:
                identity.append(value)
    if not name and filename.lower().endswith(".css"):
        match = re.search(r"(?:^|[\*/#@])\s*(?:name|title)\s*[:=]\s*([^\r\n*]+)", body.decode("utf-8", errors="ignore"), re.IGNORECASE)
        if match:
            name = _metadata_text(match.group(1))
            explicit_name = bool(name)
    name = name or _metadata_text(stem) or _admin_kind_label(kind)
    if not identity:
        identity = [name]
    domains: list[str] = []
    for item in identity:
        domain = normalize_source_domain(item)
        if domain and domain not in domains:
            domains.append(domain)
    return {
        "name": name[:120],
        "identity": identity[:12],
        "explicitName": explicit_name,
        "domains": domains[:12],
    }


def package_name_slug(name: str, kind: str) -> str:
    normalized = unicodedata.normalize("NFKC", name or "")
    normalized = re.sub(r"[^\w.-]+", "-", normalized, flags=re.UNICODE).strip("-._")
    normalized = normalized[:80].strip("-._")
    return normalized or kind.replace("_", "-")


def _package_manifests(kind: str) -> list[tuple[Path, dict[str, Any]]]:
    values: list[tuple[Path, dict[str, Any]]] = []
    for path in sorted((runtime.PACKAGE_ROOT / kind).glob("*/manifest.json")):
        try:
            manifest = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if isinstance(manifest, dict):
            values.append((path, manifest))
    return values


def resolve_package_identity(kind: str, requested_id: str, requested_content_id: str, metadata: dict[str, Any]) -> tuple[str, dict[str, Any] | None]:
    """复用可识别的旧包，否则按已有 ID 列表分配不重复的新 ID。"""
    manifests = _package_manifests(kind)
    requested = requested_id.strip()
    if requested:
        package_id = safe_package_segment(requested)
        return package_id, next((item for path, item in manifests if path.parent.name == package_id), None)
    targets = {item.casefold() for item in metadata.get("identity", []) if item}
    if requested_content_id.strip():
        targets.add(requested_content_id.strip().casefold())
    name = str(metadata.get("name", "")).strip().casefold()
    for path, manifest in manifests:
        values = {str(manifest.get(key, "")).strip().casefold() for key in ("packageId", "contentId", "name") if manifest.get(key)}
        values.update(str(item).strip().casefold() for item in manifest.get("aliases", []) if item)
        if targets.intersection(values) or (name and name == str(manifest.get("name", "")).strip().casefold()):
            return safe_package_segment(str(manifest.get("packageId", path.parent.name))), manifest
    identity_seed = next((str(item) for item in metadata.get("identity", []) if item), "")
    identity_seed = re.sub(r"^[a-z]+://(?:www\.)?", "", identity_seed, flags=re.IGNORECASE).split("/")[0]
    base = package_name_slug(identity_seed or str(metadata.get("name", "")), kind)
    used = {path.parent.name for path, _ in manifests}
    candidate = base
    index = 2
    while candidate in used:
        suffix = f"-{index}"
        candidate = (base[:120 - len(suffix)] + suffix).strip("-._")
        index += 1
    return safe_package_segment(candidate), None


def find_matching_package(kind: str, name: str, domains: set[str], identities: set[str]) -> dict[str, Any] | None:
    """按“名称 + 域名”定位服务器上的同一个源；没有域名时退化为“名称 + 稳定标识”。"""
    target_name = normalize_match_name(name)
    if not target_name:
        return None
    target_domains = {domain for domain in (normalize_source_domain(item) for item in domains) if domain}
    target_identities = {str(item).strip().casefold() for item in identities if str(item).strip()}
    for path, manifest in _package_manifests(kind):
        manifest.setdefault("kind", kind)
        manifest.setdefault("packageId", path.parent.name)
        if normalize_match_name(manifest.get("name", "")) != target_name:
            continue
        manifest_domains = package_match_domains(manifest)
        if target_domains and manifest_domains:
            if target_domains & manifest_domains:
                return manifest
            continue
        # 关联源等没有域名的内容只靠名称容易误判，要求稳定标识同时命中。
        if target_identities & package_match_identities(manifest):
            return manifest
    return None


def next_package_version(current: Any) -> str:
    value = str(current or "").strip()
    numbers = re.findall(r"\d+", value)
    if not numbers:
        return "1.0.0"
    parts = [int(item) for item in numbers[:3]]
    while len(parts) < 3:
        parts.append(0)
    parts[2] += 1
    return ".".join(str(item) for item in parts)


def merge_package_aliases(existing: Any, detected: Any, requested: str) -> list[str]:
    values = []
    if isinstance(existing, list):
        values.extend(existing)
    if isinstance(detected, list):
        values.extend(detected)
    values.extend(requested.split(","))
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        text = _metadata_text(value)
        key = text.casefold()
        if not text or len(text) > 240 or key in seen:
            continue
        seen.add(key)
        result.append(text)
        if len(result) >= 50:
            break
    return result


def package_signature(package_id: str, kind: str, content_id: str, version: str, build_time: int, sha256: str, body: bytes) -> str:
    if not runtime.SIGNING_PRIVATE_KEY_FILE:
        return ""
    from cryptography.hazmat.primitives import serialization
    key = serialization.load_pem_private_key(Path(runtime.SIGNING_PRIVATE_KEY_FILE).read_bytes(), password=None)
    message = f"{package_id}\n{kind}\n{content_id}\n{version}\n{build_time}\n{sha256}".encode("utf-8") + body
    import base64
    return base64.b64encode(key.sign(message)).decode("ascii")


def validate_package_payload(kind: str, filename: str, body: bytes) -> None:
    if len(body) > 50 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="内容文件超过 50 MB")
    if kind in {"online_source", "association_source", "theme", "epub_style", "highlight_style"} and filename.lower().endswith((".json", ".json5")):
        try:
            value = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise HTTPException(status_code=400, detail="JSON 内容包格式无效")
        if not isinstance(value, (dict, list)):
            raise HTTPException(status_code=400, detail="JSON 内容包必须是对象或数组")
    if kind in {"epub_style", "highlight_style"} and filename.lower().endswith(".css"):
        try:
            body.decode("utf-8")
        except UnicodeDecodeError:
            raise HTTPException(status_code=400, detail="样式文件必须使用 UTF-8 编码")


def package_manifest(package_kind: str, package_id: str) -> tuple[Path, dict[str, Any]]:
    manifest_path = runtime.PACKAGE_ROOT / package_kind / package_id / "manifest.json"
    if not manifest_path.is_file():
        raise HTTPException(status_code=404, detail=response(code="PACKAGE_NOT_FOUND", message="内容包不存在"))
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise HTTPException(status_code=500, detail=response(code="PACKAGE_INVALID", message="内容包清单无效"))
    return manifest_path, manifest


def persist_package_payload(
    kind: str,
    package_id: str,
    version: str,
    content_id: str,
    filename: str,
    body: bytes,
    name: str,
    description: str,
    aliases: list[str],
    domains: list[str],
    status_value: str,
    channel: str,
    dependencies: list[dict[str, Any]],
    signature: str = "",
    owner: str = "",
) -> dict[str, Any]:
    """把内容包写入数据卷：旧版本进 history，清单与 payload 原子替换。"""
    package_dir = (runtime.PACKAGE_ROOT / kind / package_id).resolve()
    if runtime.PACKAGE_ROOT.resolve() not in package_dir.parents:
        raise HTTPException(status_code=400, detail="内容包路径无效")
    package_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = package_dir / "manifest.json"
    old_payload_path: Path | None = None
    if manifest_path.is_file():
        history = package_dir / "history"
        history.mkdir(exist_ok=True)
        old_version = "old"
        old_manifest: dict[str, Any] = {}
        try:
            old_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            old_version = safe_package_segment(old_manifest.get("version", "old"))
        except (OSError, ValueError):
            pass
        archive_dir = history / f"{old_version}-{int(datetime.now(timezone.utc).timestamp() * 1000)}"
        archive_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(manifest_path, archive_dir / "manifest.json")
        old_payload = package_dir / str(old_manifest.get("payload", ""))
        if old_payload.is_file() and old_payload.parent == package_dir:
            shutil.copy2(old_payload, archive_dir / old_payload.name)
            old_payload_path = old_payload
    build_time = int(datetime.now(timezone.utc).timestamp() * 1000)
    digest = hashlib.sha256(body).hexdigest()
    manifest = {
        "packageId": package_id,
        "kind": kind,
        "version": version,
        "buildTime": build_time,
        "schemaVersion": 1,
        "minModuleVersion": load_config()["minModuleVersion"],
        "sha256": digest,
        "signature": signature.strip() or package_signature(package_id, kind, content_id, version, build_time, digest, body),
        "payload": filename,
        "contentId": content_id,
        "aliases": aliases,
        "domains": domains,
        "name": name,
        "description": description,
        "status": status_value,
        "channel": channel,
        "dependencies": dependencies,
    }
    if owner:
        manifest["uploadOwner"] = owner
    if status_value == "published":
        dependency_status = package_dependency_status(manifest)
        if not dependency_status["dependenciesSatisfied"]:
            raise HTTPException(
                status_code=409,
                detail=response(code="PACKAGE_DEPENDENCIES_UNRESOLVED", message="内容包存在未满足的必需依赖，请先上传依赖或保存为草稿", data=dependency_status),
            )
    payload_path = package_dir / filename
    temp_payload = package_dir / ".payload.tmp"
    temp_payload.write_bytes(body)
    temp_payload.replace(payload_path)
    temp_manifest = package_dir / ".manifest.json.tmp"
    temp_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    temp_manifest.replace(manifest_path)
    if old_payload_path and old_payload_path != payload_path and old_payload_path.is_file():
        old_payload_path.unlink()
    return manifest


def _admin_package_records() -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for kind in sorted(runtime.PACKAGE_KINDS):
        for manifest_path in sorted((runtime.PACKAGE_ROOT / kind).glob("*/manifest.json")):
            try:
                item = json.loads(manifest_path.read_text(encoding="utf-8"))
                if not isinstance(item, dict):
                    continue
                item.setdefault("kind", kind)
                item.setdefault("packageId", manifest_path.parent.name)
                item.update(package_dependency_status(item))
                records.append(item)
            except (OSError, ValueError, HTTPException):
                continue
    return records


def _admin_package_payload(package_dir: Path, manifest: dict[str, Any]) -> tuple[str, bytes]:
    filename = safe_payload_filename(str(manifest.get("payload", "payload.bin")))
    payload_path = package_dir / filename
    if not payload_path.is_file():
        raise HTTPException(status_code=404, detail="内容文件不存在")
    return filename, payload_path.read_bytes()


def normalize_match_name(value: Any) -> str:
    """内容名称的比较形式：折叠空白并忽略大小写。"""
    return " ".join(str(value or "").split()).casefold()


def package_match_identities(manifest: dict[str, Any]) -> set[str]:
    """内容包的稳定标识集合，供没有域名的关联源退化匹配使用。"""
    values = {str(manifest.get(key, "")).strip().casefold() for key in ("packageId", "contentId") if manifest.get(key)}
    aliases = manifest.get("aliases")
    if isinstance(aliases, (list, tuple, set)):
        values.update(str(item).strip().casefold() for item in aliases if str(item).strip())
    return {item for item in values if item}
