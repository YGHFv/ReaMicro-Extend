"""运行期配置与可变全局状态。

**所有可变状态都集中在这里**，其他模块必须通过 `runtime.X` 在调用时读取，
不要写 `from app.runtime import CONFIG_PATH` —— 那样拿到的是导入时的快照，
测试重定向路径就会失效。测试也统一改这里的属性来隔离数据目录。
"""
import os
import threading
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.storage import StateStore

API_VERSION = "1.1"


def env_bool(name: str, default: bool) -> bool:
    return os.getenv(name, str(default)).lower() == "true"


CONFIG_ROOT = Path(os.getenv("REAMICRO_CONFIG_ROOT", "/data/config"))
CONFIG_PATH = CONFIG_ROOT / "server.json"
DEFAULT_FEATURES = {
    item.strip()
    for item in os.getenv(
        "REAMICRO_FEATURES",
        "association_source,theme_library,style_library,book_source_library,backup,module_update",
    ).split(",")
    if item.strip()
}
PACKAGE_ROOT = Path(os.getenv("REAMICRO_PACKAGE_ROOT", "/data/packages"))
RELEASE_ROOT = Path(os.getenv("REAMICRO_RELEASE_ROOT", "/data/releases/module"))
RELEASE_STATUS_PATH = RELEASE_ROOT / "sync-status.json"
BACKUP_ROOT = Path(os.getenv("REAMICRO_BACKUP_ROOT", "/data/backups"))
SECRET_BACKUP_ROOT = Path(os.getenv("REAMICRO_SECRET_BACKUP_ROOT", "/data/backups/secrets"))
SERVER_BACKUP_ROOT = Path(os.getenv("REAMICRO_SERVER_BACKUP_ROOT", "/data/backups/server"))
TASK_ROOT = Path(os.getenv("REAMICRO_TASK_ROOT", "/data/tasks"))
TASKS_PATH = TASK_ROOT / "tasks.json"
STATE_DB_PATH = Path(os.getenv("REAMICRO_STATE_DB", "/data/state/reamicro.sqlite3"))
TASK_LOG_ROOT = TASK_ROOT / "logs"
AUDIT_ROOT = Path(os.getenv("REAMICRO_AUDIT_ROOT", "/data/audit"))
AUDIT_PATH = AUDIT_ROOT / "events.jsonl"
ACCOUNT_ROOT = Path(os.getenv("REAMICRO_ACCOUNT_ROOT", "/data/accounts"))
ACCOUNT_PATH = ACCOUNT_ROOT / "credentials.json"

SECRET_KEY = os.getenv("REAMICRO_SECRET_KEY", "")
ADMIN_USERNAME = os.getenv("REAMICRO_ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = os.getenv("REAMICRO_ADMIN_PASSWORD", "")
SIGNING_PRIVATE_KEY_FILE = os.getenv("REAMICRO_SIGNING_PRIVATE_KEY_FILE", "")
GITHUB_WEBHOOK_SECRET = os.getenv("REAMICRO_GITHUB_WEBHOOK_SECRET", "")

config_lock = threading.RLock()
state_store_lock = threading.RLock()
state_store: "StateStore | None" = None

rate_lock = threading.RLock()
rate_buckets: dict[str, list[float]] = {}
RATE_LIMIT = max(int(os.getenv("REAMICRO_RATE_LIMIT", "120")), 10)
RATE_WINDOW_SECONDS = 60

ADMIN_SESSION_SECONDS = max(int(os.getenv("REAMICRO_ADMIN_SESSION_SECONDS", "43200")), 900)
ADMIN_COOKIE_SECURE = os.getenv("REAMICRO_ADMIN_COOKIE_SECURE", "true").lower() == "true"
ADMIN_SESSION_COOKIE = "reamicro_admin_session"

metrics_lock = threading.RLock()
metrics_counters: dict[str, int] = {"requests": 0, "errors": 0, "rate_limited": 0}
metrics_routes: dict[str, int] = {}

RUN_SCHEDULER = env_bool("REAMICRO_RUN_SCHEDULER", True)
RUN_RELEASE_SYNC = env_bool("REAMICRO_RUN_RELEASE_SYNC", True)

MODULE_UPLOAD_MAX_BYTES = 20 * 1024 * 1024
MODULE_UPLOAD_DEFAULT_KINDS = frozenset({"online_source", "association_source"})

PACKAGE_KINDS = frozenset({
    "online_source",
    "epub_style",
    "highlight_style",
    "association_source",
    "theme",
})

AUTH_MODES = frozenset({"public", "api_key", "account", "host_account_allowlist"})
PACKAGE_STATUSES = frozenset({"draft", "testing", "published", "unpublished"})
PACKAGE_CHANNELS = frozenset({"stable", "beta", "nightly"})


def get_state_store() -> "StateStore":
    """惰性建库。测试把 state_store 置 None 即可强制换到新的临时数据库。"""
    global state_store
    with state_store_lock:
        if state_store is None:
            from app.storage import StateStore

            STATE_DB_PATH.parent.mkdir(parents=True, exist_ok=True)
            state_store = StateStore(STATE_DB_PATH)
        return state_store
