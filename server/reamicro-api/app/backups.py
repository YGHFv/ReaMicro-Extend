import hashlib
import json
import re
import shutil
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import HTTPException

from app import runtime
from app.config_store import load_config, save_config
from app.responses import response
from app.crypto import decrypt_secret, encrypt_secret, validate_admin_password
from app.state import load_credentials, load_tasks, save_credentials, save_tasks


def create_server_snapshot() -> Path:
    runtime.SERVER_BACKUP_ROOT.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    database_copy = runtime.SERVER_BACKUP_ROOT / f"state-{timestamp}.sqlite3"
    archive = runtime.SERVER_BACKUP_ROOT / f"reamicro-server-{timestamp}.zip"
    runtime.get_state_store().backup(database_copy)
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as stream:
        stream.write(database_copy, "state/reamicro.sqlite3")
        if runtime.CONFIG_PATH.is_file():
            stream.write(runtime.CONFIG_PATH, "config/server.json")
        manifest = {"createdAt": int(datetime.now(timezone.utc).timestamp() * 1000), "files": []}
        source_map = {"state/reamicro.sqlite3": database_copy, "config/server.json": runtime.CONFIG_PATH}
        for name, source in source_map.items():
            if source.is_file():
                manifest["files"].append({"path": name, "size": source.stat().st_size, "sha256": hashlib.sha256(source.read_bytes()).hexdigest()})
        stream.writestr("snapshot-manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
    database_copy.unlink(missing_ok=True)
    prune_server_snapshots(int(load_config().get("serverSnapshotRetention", 30)))
    return archive


def prune_server_snapshots(retention: int) -> int:
    snapshots = sorted(runtime.SERVER_BACKUP_ROOT.glob("reamicro-server-*.zip"), reverse=True)
    removed = 0
    for expired in snapshots[max(int(retention), 3):]:
        expired.unlink(missing_ok=True)
        removed += 1
    return removed


def list_server_snapshots() -> list[dict[str, Any]]:
    items = []
    for path in sorted(runtime.SERVER_BACKUP_ROOT.glob("reamicro-server-*.zip"), reverse=True):
        try:
            items.append({"name": path.name, "size": path.stat().st_size, "modifiedAt": int(path.stat().st_mtime * 1000), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        except OSError:
            continue
    return items


def rotate_secret_key(new_key: str) -> int:
    validate_admin_password(new_key)
    credentials = load_credentials()
    tasks = load_tasks()
    decrypted_credentials: dict[str, Any] = {}
    for key, value in credentials.items():
        if value.get("secretEncrypted"):
            decrypted_credentials[key] = decrypt_secret(str(value["secretEncrypted"]))
    decrypted_tasks: dict[str, Any] = {}
    for key, value in tasks.items():
        if value.get("requestEncrypted"):
            decrypted_tasks[key] = decrypt_secret(str(value["requestEncrypted"]))
    config = load_config()
    config["secretKey"] = new_key
    save_config(config)
    for key, secret in decrypted_credentials.items():
        credentials[key]["secretEncrypted"] = encrypt_secret(secret)
    for key, request in decrypted_tasks.items():
        tasks[key]["requestEncrypted"] = encrypt_secret(request)
    save_credentials(credentials)
    save_tasks(tasks)
    return len(decrypted_credentials) + len(decrypted_tasks)


def verify_server_snapshot(path: Path) -> dict[str, Any]:
    with zipfile.ZipFile(path) as archive:
        allowed = {"snapshot-manifest.json", "state/reamicro.sqlite3", "config/server.json"}
        if any(name not in allowed for name in archive.namelist()):
            raise ValueError("备份包含不允许的文件路径")
        manifest = json.loads(archive.read("snapshot-manifest.json").decode("utf-8"))
        results = []
        for item in manifest.get("files", []):
            data = archive.read(str(item.get("path", "")))
            digest = hashlib.sha256(data).hexdigest()
            results.append({"path": item.get("path"), "size": len(data), "sha256": digest, "valid": digest == item.get("sha256")})
        return {"valid": all(item["valid"] for item in results), "createdAt": manifest.get("createdAt"), "files": results}


def resolve_snapshot_path(filename: str) -> Path:

    if not re.fullmatch(r"reamicro-server-[0-9TZ-]+\.zip", filename):
        raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="备份文件名无效"))
    path = (runtime.SERVER_BACKUP_ROOT / filename).resolve()
    if path.parent != runtime.SERVER_BACKUP_ROOT.resolve() or not path.is_file():
        raise HTTPException(status_code=404, detail=response(code="BACKUP_NOT_FOUND", message="服务器快照不存在"))
    return path


def restore_server_snapshot(filename: str) -> dict[str, Any]:

    path = resolve_snapshot_path(filename)
    verification = verify_server_snapshot(path)
    if not verification.get("valid"):
        raise HTTPException(status_code=409, detail=response(code="BACKUP_INVALID", message="备份完整性校验失败"))
    safety_snapshot = create_server_snapshot()
    with tempfile.TemporaryDirectory(prefix="reamicro-restore-") as directory:
        extract_root = Path(directory)
        with zipfile.ZipFile(path) as archive:
            archive.extractall(extract_root)
        restored_db = extract_root / "state" / "reamicro.sqlite3"
        restored_config = extract_root / "config" / "server.json"
        if not restored_db.is_file() or not restored_config.is_file():
            raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="备份缺少数据库或配置"))
        runtime.CONFIG_ROOT.mkdir(parents=True, exist_ok=True)
        runtime.STATE_DB_PATH.parent.mkdir(parents=True, exist_ok=True)
        Path(str(runtime.STATE_DB_PATH) + "-wal").unlink(missing_ok=True)
        Path(str(runtime.STATE_DB_PATH) + "-shm").unlink(missing_ok=True)
        shutil.copy2(restored_config, runtime.CONFIG_PATH)
        shutil.copy2(restored_db, runtime.STATE_DB_PATH)
    with runtime.state_store_lock:
        state_store = None
    return {"restored": True, "filename": filename, "safetySnapshot": safety_snapshot.name}
