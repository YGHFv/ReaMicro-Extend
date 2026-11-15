import json
import sqlite3
import threading
import fcntl
import tempfile
from contextlib import contextmanager, closing
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, TypeVar


Result = TypeVar("Result")
_MISSING = object()


class NamespaceSnapshot(dict[str, dict[str, Any]]):
    """A loaded view carries its baseline so stale writers only commit their own changes."""

    def __init__(self, namespace: str, values: dict[str, dict[str, Any]]):
        super().__init__(values)
        self.namespace = namespace
        self.baseline = deepcopy(values)


def merge_document_changes(before: dict[str, Any], after: dict[str, Any],
                           current: dict[str, Any]) -> dict[str, Any]:
    result = deepcopy(current)
    for key in before.keys() - after.keys():
        result.pop(key, None)
    for key, value in after.items():
        old = before.get(key, _MISSING)
        if old == value:
            continue
        if isinstance(old, dict) and isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = merge_document_changes(old, value, result[key])
        else:
            result[key] = deepcopy(value)
    return result


class StateStore:
    def __init__(self, path: Path):
        self.path = path
        self.lock = threading.RLock()

    @contextmanager
    def file_lock(self, exclusive: bool = False):
        """Coordinate live connections with maintenance across API and worker processes."""
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.with_suffix(self.path.suffix + ".lock").open("a+b") as lock_file:
            fcntl.flock(lock_file, fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH)
            try:
                yield
            finally:
                fcntl.flock(lock_file, fcntl.LOCK_UN)

    @contextmanager
    def connect(self):
        with self.file_lock():
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
                CREATE TABLE IF NOT EXISTS namespace_imports (
                    namespace TEXT PRIMARY KEY,
                    imported_at TEXT NOT NULL
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

    @staticmethod
    def _read_namespace(connection, namespace: str) -> dict[str, dict[str, Any]]:
        rows = connection.execute(
            "SELECT document_key, body FROM state_documents WHERE namespace = ?",
            (namespace,),
        ).fetchall()
        result: dict[str, dict[str, Any]] = {}
        for key, body in rows:
            value = json.loads(body)
            if not isinstance(value, dict):
                raise ValueError(f"Invalid stored document in {namespace}")
            result[str(key)] = value
        return result

    @staticmethod
    def _write_document(connection, namespace: str, key: str, value: dict[str, Any], now: str) -> None:
        connection.execute(
            "INSERT INTO state_documents(namespace, document_key, body, updated_at) VALUES(?, ?, ?, ?) "
            "ON CONFLICT(namespace, document_key) DO UPDATE SET body=excluded.body, updated_at=excluded.updated_at",
            (namespace, key, json.dumps(value, ensure_ascii=False, separators=(",", ":")), now),
        )

    def load_namespace(self, namespace: str) -> NamespaceSnapshot:
        self.initialize()
        with self.lock, self.connect() as connection:
            return NamespaceSnapshot(namespace, self._read_namespace(connection, namespace))

    def save_namespace(self, namespace: str, values: dict[str, dict[str, Any]]) -> None:
        self.initialize()
        now = datetime.now(timezone.utc).isoformat()
        with self.lock, self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            if isinstance(values, NamespaceSnapshot):
                if values.namespace != namespace:
                    raise ValueError("Snapshot namespace does not match the destination")
                current = self._read_namespace(connection, namespace)
                for key in values.baseline.keys() - values.keys():
                    connection.execute(
                        "DELETE FROM state_documents WHERE namespace = ? AND document_key = ?", (namespace, key),
                    )
                for key, value in values.items():
                    if not isinstance(value, dict):
                        raise ValueError("State documents must be objects")
                    before = values.baseline.get(key)
                    if before is None:
                        if key in current and current[key] != value:
                            raise ValueError(f"Concurrent document creation in {namespace}; reload before saving")
                        self._write_document(connection, namespace, str(key), value, now)
                    elif before != value and key in current:
                        self._write_document(connection, namespace, str(key),
                                             merge_document_changes(before, value, current[key]), now)
                    # A concurrently deleted task or credential must not be resurrected by an
                    # old worker result. Unchanged documents are deliberately not rewritten.
            else:
                # Explicit full replacement is reserved for importing an authoritative snapshot.
                connection.execute("DELETE FROM state_documents WHERE namespace = ?", (namespace,))
                for key, value in values.items():
                    if not isinstance(value, dict):
                        raise ValueError("State documents must be objects")
                    self._write_document(connection, namespace, str(key), value, now)
        if isinstance(values, NamespaceSnapshot):
            values.baseline = deepcopy(dict(values))

    def mutate_namespace(self, namespace: str,
                         mutate: Callable[[dict[str, dict[str, Any]]], Result], *,
                         idempotency: tuple[str, str, str] | None = None) -> Result:
        """Run a short state decision against the latest data under SQLite's process-wide lock.

        The callback must not await, perform network I/O, or open another store transaction.
        """
        return self.mutate_namespaces(
            (namespace,), lambda values: mutate(values[namespace]), idempotency=idempotency,
        )

    def mutate_namespaces(self, namespaces: tuple[str, ...],
                          mutate: Callable[[dict[str, dict[str, dict[str, Any]]]], Result], *,
                          idempotency: tuple[str, str, str] | None = None) -> Result:
        """Atomically update related namespaces without exposing a nested transaction."""
        self.initialize()
        with self.lock, self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            created_at = int(datetime.now(timezone.utc).timestamp() * 1000)
            if idempotency is not None:
                row = connection.execute(
                    "SELECT response_body FROM idempotency_records WHERE owner = ? AND operation = ? "
                    "AND idempotency_key = ? AND created_at > ?",
                    (*idempotency, created_at - 86_400_000),
                ).fetchone()
                if row is not None:
                    return json.loads(row[0])
            values = {namespace: self._read_namespace(connection, namespace) for namespace in namespaces}
            before = deepcopy(values)
            result = mutate(values)
            now = datetime.now(timezone.utc).isoformat()
            for namespace in namespaces:
                for key in before[namespace].keys() - values[namespace].keys():
                    connection.execute(
                        "DELETE FROM state_documents WHERE namespace = ? AND document_key = ?", (namespace, key),
                    )
                for key, value in values[namespace].items():
                    if not isinstance(value, dict):
                        raise ValueError("State documents must be objects")
                    if before[namespace].get(key) != value:
                        self._write_document(connection, namespace, str(key), value, now)
            if idempotency is not None:
                connection.execute(
                    "INSERT OR REPLACE INTO idempotency_records(owner, operation, idempotency_key, response_body, created_at) "
                    "VALUES(?, ?, ?, ?, ?)",
                    (*idempotency, json.dumps(result, ensure_ascii=False, separators=(",", ":")), created_at),
                )
                connection.execute("DELETE FROM idempotency_records WHERE created_at <= ?", (created_at - 86_400_000,))
            return result

    def import_json_namespace(self, namespace: str, path: Path) -> bool:
        self.initialize()
        imported = False
        with self.lock, self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            if connection.execute("SELECT 1 FROM namespace_imports WHERE namespace = ?", (namespace,)).fetchone():
                return False
            now = datetime.now(timezone.utc).isoformat()
            if not self._read_namespace(connection, namespace) and path.is_file():
                value = json.loads(path.read_text(encoding="utf-8"))
                if not isinstance(value, dict) or any(not isinstance(item, dict) for item in value.values()):
                    raise ValueError(f"Invalid legacy {namespace} documents")
                for key, item in value.items():
                    self._write_document(connection, namespace, str(key), item, now)
                imported = True
            connection.execute(
                "INSERT INTO namespace_imports(namespace, imported_at) VALUES(?, ?)", (namespace, now),
            )
        # The transaction marker is authoritative if a process exits before this rename.
        if imported and path.is_file():
            path.replace(path.with_suffix(path.suffix + ".migrated"))
        return imported

    def backup(self, target: Path) -> Path:
        self.initialize()
        target.parent.mkdir(parents=True, exist_ok=True)
        with self.lock, self.connect() as source, closing(sqlite3.connect(target)) as destination:
            source.backup(destination)
        return target

    def backup_with_metadata(self, target: Path, read_metadata: Callable[[], Result]) -> Result:
        """Keep a restore from separating a DB snapshot from its matching configuration."""
        self.initialize()
        target.parent.mkdir(parents=True, exist_ok=True)
        with self.lock, self.file_lock(exclusive=True):
            with closing(sqlite3.connect(self.path)) as source, closing(sqlite3.connect(target)) as destination:
                source.backup(destination)
                return read_metadata()

    def restore(self, source: Path, finalize: Callable[[], None], now: int) -> None:
        """Restore through SQLite's backup API; never replace an open database or delete its WAL."""
        self.initialize()
        with self.lock, self.file_lock(exclusive=True), closing(sqlite3.connect(self.path, timeout=30)) as destination:
            live = destination.execute(
                "SELECT COUNT(*) FROM task_locks WHERE expires_at > ?", (now,),
            ).fetchone()
            if live and live[0]:
                raise ValueError("仍有云端任务正在执行，请等待任务完成后恢复快照")
            with closing(sqlite3.connect(source)) as snapshot:
                if snapshot.execute("PRAGMA integrity_check").fetchone() != ("ok",):
                    raise ValueError("快照数据库完整性校验失败")
                # Old archives predate SQLite-backed user permissions. Preserve the live
                # restrictions instead of treating an absent namespace as "allow everyone".
                try:
                    knows_users = snapshot.execute(
                        "SELECT 1 FROM namespace_imports WHERE namespace = 'users'",
                    ).fetchone()
                except sqlite3.OperationalError:
                    knows_users = None
                keep_users = self._read_namespace(destination, "users") if not knows_users else None
                # Take a private rollback copy under the same cross-process maintenance lock.
                with tempfile.TemporaryDirectory(prefix="reamicro-db-restore-") as temporary:
                    with closing(sqlite3.connect(Path(temporary) / "rollback.sqlite3")) as rollback:
                        destination.backup(rollback)
                        try:
                            snapshot.backup(destination)
                            destination.execute("DELETE FROM task_locks")
                            destination.execute(
                                "CREATE TABLE IF NOT EXISTS namespace_imports "
                                "(namespace TEXT PRIMARY KEY, imported_at TEXT NOT NULL)",
                            )
                            if keep_users is not None:
                                stamp = datetime.now(timezone.utc).isoformat()
                                for key, user in keep_users.items():
                                    self._write_document(destination, "users", key, user, stamp)
                                destination.execute(
                                    "INSERT OR IGNORE INTO namespace_imports(namespace, imported_at) VALUES('users', ?)",
                                    (stamp,),
                                )
                            destination.commit()
                            finalize()
                        except Exception:
                            destination.rollback()
                            rollback.backup(destination)
                            raise

    def integrity_check(self) -> str:
        self.initialize()
        with self.lock, self.connect() as connection:
            row = connection.execute("PRAGMA integrity_check").fetchone()
        return str(row[0]) if row else "unknown"

    def get_idempotency(self, owner: str, operation: str, key: str) -> dict[str, Any] | None:
        self.initialize()
        with self.lock, self.connect() as connection:
            row = connection.execute(
                "SELECT response_body FROM idempotency_records WHERE owner = ? AND operation = ? "
                "AND idempotency_key = ? AND created_at > ?",
                (owner, operation, key, int(datetime.now(timezone.utc).timestamp() * 1000) - 86_400_000),
            ).fetchone()
        if not row:
            return None
        try:
            value = json.loads(row[0])
            return value if isinstance(value, dict) else None
        except ValueError:
            return None

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

    def renew_task_lock(self, task_id: str, owner: str, expires_at: int) -> bool:
        self.initialize()
        with self.lock, self.connect() as connection:
            cursor = connection.execute(
                "UPDATE task_locks SET expires_at = ? WHERE task_id = ? AND owner = ?",
                (expires_at, task_id, owner),
            )
            return cursor.rowcount == 1

    def recover_interrupted_tasks(self, now: int) -> int:
        """A running task without a live lease has an unknown external outcome."""
        self.initialize()
        with self.lock, self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            live = {row[0] for row in connection.execute(
                "SELECT task_id FROM task_locks WHERE expires_at > ?", (now,),
            )}
            recovered = 0
            for key, task in self._read_namespace(connection, "tasks").items():
                if task.get("status") != "running" or key in live:
                    continue
                task["status"] = "paused"
                task["enabled"] = False
                task["nextRunAt"] = 0
                task["lastMessage"] = "任务执行中断，结果未知；请核对后手动恢复"
                self._write_document(connection, "tasks", key, task, datetime.now(timezone.utc).isoformat())
                recovered += 1
            return recovered

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
