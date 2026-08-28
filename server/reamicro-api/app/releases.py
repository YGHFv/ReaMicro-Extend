"""从 GitHub Release 同步模块 APK。

本模块的 CI 发布的全是预发布 Release，所以"包含预发布"开关默认关闭时会同步不到东西；
渠道标记规则：预发布标 beta，正式标 stable，只有请求 stable 时才拒绝 beta。
"""
import hashlib
import hmac
import json
import os
import re
import shutil
import urllib.error
import urllib.request
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from typing import Any

from app import runtime
from app.audit import audit_event
from app.config_store import load_config


def github_request(url: str) -> urllib.request.Request:
    config = load_config()
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "ReaMicro-API-Server",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if config.get("githubToken"):
        headers["Authorization"] = f"Bearer {config['githubToken']}"
    return urllib.request.Request(url, headers=headers)


def github_json(url: str) -> Any:
    with urllib.request.urlopen(github_request(url), timeout=30) as stream:
        return json.loads(stream.read().decode("utf-8"))


def release_timestamp(value: str) -> int:
    if not value:
        return 0
    return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)


def is_semantic_version(value: str) -> bool:
    core = value.strip().split("-", 1)[0]
    if not core:
        return False
    return all(part.isdigit() for part in core.split("."))


def release_version_name(tag: str, title: str) -> str:
    """从 tag 或 Release 标题里取出语义版本号。

    客户端按语义版本号比较新旧，无法解析的字符串会被当成 0.0.0 并永远判定"已是最新版本"。
    CI 的 tag 形如 ``ci-123-1``，但标题里带着真实版本号（``CI 2.0.0 #123``），
    因此 tag 不是语义版本号时回退到标题。
    """
    candidate = tag.strip().lstrip("vV").split("+")[0]
    if is_semantic_version(candidate):
        return candidate
    # 至少要带一个小数点，避免把标题里的构建号（#123）误当成版本号。
    match = re.search(r"\d+(?:\.\d+)+", title or "")
    if match:
        return match.group(0)
    return candidate


def sync_module_release() -> dict[str, Any] | None:
    config = load_config()
    repository = config["githubRepository"]
    if config.get("githubIncludePrerelease"):
        releases = github_json(f"https://api.github.com/repos/{repository}/releases?per_page=20")
        release = next((item for item in releases if not item.get("draft")), None)
    else:
        try:
            release = github_json(f"https://api.github.com/repos/{repository}/releases/latest")
        except urllib.error.HTTPError as error:
            # GitHub 的 /releases/latest 会跳过预发布。仓库里只有预发布 Release 时返回 404，
            # 这不是网络故障，直接提示需要在后台勾选"包含预发布 Release"。
            if error.code == 404:
                raise RuntimeError(
                    f"仓库 {repository} 没有正式 Release；如果只发布预发布版本，请在后台勾选“包含预发布 Release”"
                ) from error
            raise
    if not release:
        return None
    assets = [asset for asset in release.get("assets", []) if asset.get("name", "").lower().endswith(".apk")]
    if not assets:
        return None
    asset = sorted(
        assets,
        key=lambda item: (
            "unsigned" in item.get("name", "").lower(),
            "release" not in item.get("name", "").lower(),
        ),
    )[0]
    runtime.RELEASE_ROOT.mkdir(parents=True, exist_ok=True)
    apk_path = runtime.RELEASE_ROOT / "latest.apk"
    metadata_path = runtime.RELEASE_ROOT / "latest.json"
    build_time = release_timestamp(asset.get("updated_at") or release.get("published_at", ""))
    previous = {}
    if metadata_path.is_file():
        try:
            previous = json.loads(metadata_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            previous = {}
    if previous.get("assetId") != asset.get("id") or previous.get("buildTime") != build_time or not apk_path.is_file():
        temp = runtime.RELEASE_ROOT / ".latest.apk.tmp"
        with urllib.request.urlopen(github_request(asset["browser_download_url"]), timeout=120) as source, temp.open("wb") as target:
            shutil.copyfileobj(source, target)
        temp.replace(apk_path)
    sha256 = hashlib.sha256(apk_path.read_bytes()).hexdigest()
    tag = str(release.get("tag_name") or release.get("name") or "").strip()
    version_name = release_version_name(tag, str(release.get("name") or ""))
    metadata = {
        "versionName": version_name,
        "versionCode": int(config.get("releaseVersionCode", 0)),
        "buildTime": build_time,
        "apkUrl": "/v1/releases/module/download",
        "sha256": sha256,
        "signature": "",
        "changelog": release.get("body", ""),
        "releaseUrl": release.get("html_url", ""),
        "assetId": asset.get("id"),
        "assetName": asset.get("name", "latest.apk"),
        "channel": "beta" if release.get("prerelease") else "stable",
        "etag": hashlib.sha256(apk_path.read_bytes()).hexdigest(),
    }
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    runtime.RELEASE_STATUS_PATH.parent.mkdir(parents=True, exist_ok=True)
    runtime.RELEASE_STATUS_PATH.write_text(json.dumps({"status": "ok", "syncedAt": int(datetime.now(timezone.utc).timestamp() * 1000), "versionName": version_name, "assetId": asset.get("id")}, ensure_ascii=False, indent=2), encoding="utf-8")
    return metadata


def github_webhook_signature(body: bytes) -> str:
    """按当前配置的 Webhook Secret 计算期望签名。抽出来便于单测覆盖比对逻辑。"""
    return "sha256=" + hmac.new(runtime.GITHUB_WEBHOOK_SECRET.encode("utf-8"), body, hashlib.sha256).hexdigest()
