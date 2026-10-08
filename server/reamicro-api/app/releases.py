import hashlib
import hmac
import json
import re
import shutil
import urllib.error
import urllib.request
from datetime import datetime, timezone
from typing import Any

from app import runtime
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

    candidate = tag.strip().lstrip("vV").split("+")[0]
    if is_semantic_version(candidate):
        return candidate

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

    return "sha256=" + hmac.new(runtime.GITHUB_WEBHOOK_SECRET.encode("utf-8"), body, hashlib.sha256).hexdigest()
