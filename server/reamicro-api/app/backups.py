import hashlib
import json
import re
import tempfile
import zipfile
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import HTTPException

from app import runtime
from app.config_store import config_file_lock, load_config, save_config
from app.responses import response
from app.crypto import decrypt_secret, encrypt_secret, generate_long_secret, validate_admin_password


def create_server_snapshot() -> Path:
    from app.users import load_users
    load_users()
    runtime.SERVER_BACKUP_ROOT.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S-%fZ")
    archive = runtime.SERVER_BACKUP_ROOT / f"reamicro-server-{timestamp}.zip"
    with tempfile.TemporaryDirectory(prefix=".snapshot-", dir=runtime.SERVER_BACKUP_ROOT) as temporary:
        database_copy = Path(temporary) / "state.sqlite3"
        staged_archive = Path(temporary) / "snapshot.zip"
        config = runtime.get_state_store().backup_with_metadata(database_copy, load_config)
        # Read configuration after the DB copy. Key history keeps its ciphertext
        # readable even when a rotation committed while the backup was in progress.
        config.pop("_authModeExplicit", None)
        config_bytes = json.dumps(config, ensure_ascii=False, indent=2).encode("utf-8")
        manifest = {"createdAt": int(datetime.now(timezone.utc).timestamp() * 1000), "files": []}
        with zipfile.ZipFile(staged_archive, "w", compression=zipfile.ZIP_DEFLATED) as stream:
            stream.write(database_copy, "state/reamicro.sqlite3")
            stream.writestr("config/server.json", config_bytes)
            for name, data in (("state/reamicro.sqlite3", database_copy.read_bytes()),
                               ("config/server.json", config_bytes)):
                manifest["files"].append({"path": name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()})
            stream.writestr("snapshot-manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        staged_archive.replace(archive)
    prune_server_snapshots(int(config.get("serverSnapshotRetention", 30)))
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
    new_key = new_key.strip() or generate_long_secret()
    validate_admin_password(new_key)
    config = load_config()
    config["secretKey"] = new_key
    save_config(config)
    def reencrypt(values):
        count = 0
        for namespace, field in (("credentials", "secretEncrypted"), ("tasks", "requestEncrypted")):
            for record in values[namespace].values():
                if record.get(field):
                    record[field] = encrypt_secret(decrypt_secret(str(record[field])))
                    count += 1
        return count
    # A failure rolls back both namespaces; retained keys also cover a process exit
    # between publishing the key and this transaction, plus already-encrypted requests.
    return runtime.get_state_store().mutate_namespaces(("credentials", "tasks"), reencrypt)


def verify_server_snapshot(path: Path) -> dict[str, Any]:
    with zipfile.ZipFile(path) as archive:
        allowed = {"snapshot-manifest.json", "state/reamicro.sqlite3", "config/server.json"}
        names = archive.namelist()
        if len(names) != len(set(names)) or set(names) != allowed:
            raise ValueError("备份文件列表不完整、重复或包含不允许的路径")
        manifest = json.loads(archive.read("snapshot-manifest.json").decode("utf-8"))
        if not isinstance(manifest, dict) or not isinstance(manifest.get("files"), list):
            raise ValueError("备份清单无效")
        entries = manifest["files"]
        expected = allowed - {"snapshot-manifest.json"}
        if (len(entries) != len(expected) or any(not isinstance(item, dict) or not isinstance(item.get("path"), str) for item in entries)
                or {item.get("path") for item in entries} != expected):
            raise ValueError("备份清单必须完整列出数据库和配置")
        results = []
        for item in entries:
            data = archive.read(str(item.get("path", "")))
            digest = hashlib.sha256(data).hexdigest()
            results.append({"path": item.get("path"), "size": len(data), "sha256": digest,
                            "valid": digest == item.get("sha256") and len(data) == item.get("size")})
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
    with tempfile.TemporaryDirectory(prefix="reamicro-restore-") as directory:
        extract_root = Path(directory)
        with zipfile.ZipFile(path) as archive:
            archive.extractall(extract_root)
        restored_db = extract_root / "state" / "reamicro.sqlite3"
        restored_config = extract_root / "config" / "server.json"
        if not restored_db.is_file() or not restored_config.is_file():
            raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="备份缺少数据库或配置"))
        decoded_config = json.loads(restored_config.read_text(encoding="utf-8"))
        if not isinstance(decoded_config, dict):
            raise HTTPException(status_code=400, detail=response(code="BACKUP_INVALID", message="备份配置不是对象"))
        # The safety snapshot may prune the oldest archive. Extract the chosen archive
        # first so restoring an old retained snapshot cannot delete its own input.
        safety_snapshot = create_server_snapshot()
        def restore_configuration() -> None:
            with runtime.config_lock, config_file_lock(exclusive=True):
                runtime.CONFIG_ROOT.mkdir(parents=True, exist_ok=True)
                temporary = None
                try:
                    with tempfile.NamedTemporaryFile(dir=runtime.CONFIG_ROOT, prefix=".restore-", delete=False) as output:
                        temporary = Path(output.name)
                        output.write(restored_config.read_bytes())
                        output.flush()
                        os.fsync(output.fileno())
                    temporary.replace(runtime.CONFIG_PATH)
                finally:
                    if temporary is not None:
                        temporary.unlink(missing_ok=True)
        try:
            runtime.get_state_store().restore(restored_db, restore_configuration,
                                              int(datetime.now(timezone.utc).timestamp() * 1000))
        except ValueError as error:
            raise HTTPException(status_code=409, detail=response(code="BACKUP_BUSY", message=str(error))) from error
    from app.scheduler import recover_interrupted_tasks
    recover_interrupted_tasks()
    return {"restored": True, "filename": filename, "safetySnapshot": safety_snapshot.name}
