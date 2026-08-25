"""服务器事务型状态存储。

单机部署默认使用 SQLite WAL；上层仅通过命名空间读写 JSON 对象，便于后续替换 PostgreSQL。
"""
import json
import sqlite3
import threading
from contextlib import contextmanager, closing
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


class StateStore:
    def __init__(self, path: Path):
        self.path = path
        self.lock = threading.RLock()

    @contextmanager
    def connect(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(self.path, timeout=30)
        try:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("PRAGMA synchronous=FULL")
            connection.execute("PRAGMA foreign_keys=ON")
            connection.execute("PRAGMA busy_timeout=30000")
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def initialize(self) -> None:
        with self.lock, self.connect() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version INTEGER PRIMARY KEY,
                    applied_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS state_documents (
                    namespace TEXT NOT NULL,
                    document_key TEXT NOT NULL,
                    body TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY(namespace, document_key)
                );
                CREATE INDEX IF NOT EXISTS idx_state_documents_namespace
                    ON state_documents(namespace);
                CREATE TABLE IF NOT EXISTS idempotency_records (
                    owner TEXT NOT NULL,
                    operation TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    response_body TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY(owner, operation, idempotency_key)
                );
                CREATE TABLE IF NOT EXISTS task_locks (
                    task_id TEXT PRIMARY KEY,
                    owner TEXT NOT NULL,
                    expires_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS admin_sessions (
                    token_digest TEXT PRIMARY KEY,
                    username TEXT NOT NULL,
                    role TEXT NOT NULL,
                    needs_setup INTEGER NOT NULL,
                    permissions TEXT NOT NULL,
                    auth_version TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    last_seen_at INTEGER NOT NULL
                );
                """
            )
            connection.execute(
                "INSERT OR IGNORE INTO schema_migrations(version, applied_at) VALUES(1, ?)",
                (datetime.now(timezone.utc).isoformat(),),
            )

    def load_namespace(self, namespace: str) -> dict[str, dict[str, Any]]:
        self.initialize()
        with self.lock, self.connect() as connection:
            rows = connection.execute(
                "SELECT document_key, body FROM state_documents WHERE namespace = ?",
                (namespace,),
            ).fetchall()
        result: dict[str, dict[str, Any]] = {}
        for key, body in rows:
            try:
                value = json.loads(body)
            except ValueError:
                continue
            if isinstance(value, dict):
                result[str(key)] = value
        return result

    def save_namespace(self, namespace: str, values: dict[str, dict[str, Any]]) -> None:
        self.initialize()
        now = datetime.now(timezone.utc).isoformat()
        with self.lock, self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute("DELETE FROM state_documents WHERE namespace = ?", (namespace,))
            connection.executemany(
                "INSERT INTO state_documents(namespace, document_key, body, updated_at) VALUES(?, ?, ?, ?)",
                [
                    (namespace, str(key), json.dumps(value, ensure_ascii=False, separators=(",", ":")), now)
                    for key, value in values.items()
                    if isinstance(value, dict)
                ],
            )
            connection.commit()

    def import_json_namespace(self, namespace: str, path: Path) -> bool:
        if self.load_namespace(namespace) or not path.is_file():
            return False
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return False
        if not isinstance(value, dict):
            return False
        documents = {str(key): item for key, item in value.items() if isinstance(item, dict)}
        if not documents:
            return False
        self.save_namespace(namespace, documents)
        path.replace(path.with_suffix(path.suffix + ".migrated"))
        return True

    def backup(self, target: Path) -> Path:
        self.initialize()
        target.parent.mkdir(parents=True, exist_ok=True)
        with self.lock, self.connect() as source, closing(sqlite3.connect(target)) as destination:
            source.backup(destination)
        return target

    def integrity_check(self) -> str:
        self.initialize()
        with self.lock, self.connect() as connection:
            row = connection.execute("PRAGMA integrity_check").fetchone()
        return str(row[0]) if row else "unknown"

    def get_idempotency(self, owner: str, operation: str, key: str) -> dict[str, Any] | None:
        self.initialize()
        with self.lock, self.connect() as connection:
            row = connection.execute(
                "SELECT response_body FROM idempotency_records WHERE owner = ? AND operation = ? AND idempotency_key = ?",
                (owner, operation, key),
            ).fetchone()
        if not row:
            return None
        try:
            value = json.loads(row[0])
            return value if isinstance(value, dict) else None
        except ValueError:
            return None

    def save_idempotency(self, owner: str, operation: str, key: str, value: dict[str, Any], created_at: int) -> None:
        self.initialize()
        with self.lock, self.connect() as connection:
            connection.execute(
                "INSERT OR REPLACE INTO idempotency_records(owner, operation, idempotency_key, response_body, created_at) VALUES(?, ?, ?, ?, ?)",
                (owner, operation, key, json.dumps(value, ensure_ascii=False, separators=(",", ":")), created_at),
            )
            connection.execute("DELETE FROM idempotency_records WHERE created_at < ?", (created_at - 86_400_000,))

    def acquire_task_lock(self, task_id: str, owner: str, expires_at: int, now: int) -> bool:
        self.initialize()
        with self.lock, self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute("DELETE FROM task_locks WHERE expires_at <= ?", (now,))
            try:
                connection.execute(
                    "INSERT INTO task_locks(task_id, owner, expires_at) VALUES(?, ?, ?)",
                    (task_id, owner, expires_at),
                )
                connection.commit()
                return True
            except sqlite3.IntegrityError:
                connection.rollback()
                return False

    def release_task_lock(self, task_id: str, owner: str) -> None:
        self.initialize()
        with self.lock, self.connect() as connection:
            connection.execute("DELETE FROM task_locks WHERE task_id = ? AND owner = ?", (task_id, owner))

    def save_admin_session(self, digest: str, actor: dict[str, Any], auth_version: str, now: int, expires_at: int) -> None:
        self.initialize()
        with self.lock, self.connect() as connection:
            connection.execute(
                "INSERT OR REPLACE INTO admin_sessions(token_digest, username, role, needs_setup, permissions, auth_version, created_at, expires_at, last_seen_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    digest,
                    str(actor.get("username", "")),
                    str(actor.get("role", "subadmin")),
                    1 if actor.get("needsSetup") else 0,
                    json.dumps(actor.get("permissions", []), ensure_ascii=False),
                    auth_version,
                    now,
                    expires_at,
                    now,
                ),
            )

    def load_admin_session(self, digest: str, now: int) -> dict[str, Any] | None:
        self.initialize()
        with self.lock, self.connect() as connection:
            connection.execute("DELETE FROM admin_sessions WHERE expires_at <= ?", (now,))
            row = connection.execute(
                "SELECT username, role, needs_setup, permissions, auth_version, expires_at FROM admin_sessions WHERE token_digest = ?",
                (digest,),
            ).fetchone()
            if row:
                connection.execute("UPDATE admin_sessions SET last_seen_at = ? WHERE token_digest = ?", (now, digest))
        if not row:
            return None
        try:
            permissions = json.loads(row[3])
        except ValueError:
            permissions = []
        return {
            "username": row[0],
            "role": row[1],
            "needsSetup": bool(row[2]),
            "permissions": permissions if isinstance(permissions, list) else [],
            "authVersion": row[4],
            "expiresAt": row[5],
        }

    def delete_admin_session(self, digest: str) -> None:
        self.initialize()
        with self.lock, self.connect() as connection:
            connection.execute("DELETE FROM admin_sessions WHERE token_digest = ?", (digest,))

    def delete_admin_sessions_for_user(self, username: str) -> None:
        self.initialize()
        with self.lock, self.connect() as connection:
            connection.execute("DELETE FROM admin_sessions WHERE username = ?", (username,))
