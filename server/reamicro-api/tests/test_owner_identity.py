"""归属标识一致性回归测试。

历史问题：resolve_identity 按认证模式拼归属（`host-public:3` / `key:<hash>:host:3` /
`host:3`），管理员在后台改一次认证模式，同一台设备的归属就变了，导致：
1. 已上传的同步密钥在模块里"消失"（列表按新归属查，数据存在旧归属下）。
2. 任务消息永远推不出去（消息按任务的旧归属入队，心跳按新归属查询）。
3. 后台在线列表同一个阅微账号出现两行。
"""
import json
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import main


ACCOUNT_ID = "3"


class OwnerIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        main.CONFIG_ROOT = root / "config"
        main.CONFIG_PATH = main.CONFIG_ROOT / "server.json"
        main.TASK_ROOT = root / "tasks"
        main.TASKS_PATH = main.TASK_ROOT / "tasks.json"
        main.TASK_LOG_ROOT = main.TASK_ROOT / "logs"
        main.ACCOUNT_ROOT = root / "accounts"
        main.ACCOUNT_PATH = main.ACCOUNT_ROOT / "credentials.json"
        main.AUDIT_ROOT = root / "audit"
        main.AUDIT_PATH = main.AUDIT_ROOT / "events.jsonl"
        main.BACKUP_ROOT = root / "backups"
        main.SECRET_BACKUP_ROOT = root / "backups" / "secrets"
        main.STATE_DB_PATH = root / "state" / "reamicro.sqlite3"
        main.SECRET_KEY = "test-secret-key-for-owner-identity"
        main.state_store = None

    def tearDown(self):
        self.temp_dir.cleanup()

    def _configure(self, mode: str, **extra):
        config = {**main.default_config(), "authMode": mode, **extra}
        return main.save_config(config)

    def test_identity_is_account_scoped_in_every_auth_mode(self):
        """同一个阅微账号在四种认证模式下都必须解析成同一个归属。"""
        api_key = "k" * 40
        self._configure("public")
        public_identity = main.resolve_identity(None, None, None, ACCOUNT_ID)

        self._configure("host_account_allowlist", hostAccountAllowlist=[ACCOUNT_ID])
        allowlist_identity = main.resolve_identity(None, None, None, ACCOUNT_ID)

        self._configure("api_key", apiKey=api_key)
        key_identity = main.resolve_identity(api_key, None, None, ACCOUNT_ID)

        self._configure("account", accounts={"user": main.password_hash("user-password-1")})
        account_identity = main.resolve_identity(None, "user", "user-password-1", ACCOUNT_ID)

        self.assertEqual(f"host:{ACCOUNT_ID}", public_identity)
        self.assertEqual(public_identity, allowlist_identity)
        self.assertEqual(public_identity, key_identity)
        self.assertEqual(public_identity, account_identity)

    def test_identity_falls_back_when_no_host_account(self):
        """没有阅微账号时才按认证方式区分归属。"""
        api_key = "k" * 40
        self._configure("api_key", apiKey=api_key)
        self.assertTrue(main.resolve_identity(api_key, None, None, None).startswith("key:"))
        self._configure("account", accounts={"user": main.password_hash("user-password-1")})
        self.assertEqual("account:user", main.resolve_identity(None, "user", "user-password-1", None))
        self._configure("public")
        self.assertEqual("public", main.resolve_identity(None, None, None, None))

    def test_invalid_credentials_still_rejected(self):
        """归属统一不能放松认证：白名单外的账号和错误密钥必须仍然 401。"""
        self._configure("host_account_allowlist", hostAccountAllowlist=["999"])
        with self.assertRaises(main.HTTPException) as caught:
            main.resolve_identity(None, None, None, ACCOUNT_ID)
        self.assertEqual(401, caught.exception.status_code)
        self._configure("api_key", apiKey="k" * 40)
        with self.assertRaises(main.HTTPException):
            main.resolve_identity("wrong-key", None, None, ACCOUNT_ID)
        self._configure("account", accounts={"user": main.password_hash("user-password-1")})
        with self.assertRaises(main.HTTPException):
            main.resolve_identity(None, "user", "wrong-password", ACCOUNT_ID)

    def test_backup_dir_is_stable_across_auth_modes(self):
        """备份目录名跟着归属走，改认证模式后不能失联。"""
        self._configure("public")
        first = main.backup_owner_dir_name(main.resolve_identity(None, None, None, ACCOUNT_ID))
        self._configure("host_account_allowlist", hostAccountAllowlist=[ACCOUNT_ID])
        second = main.backup_owner_dir_name(main.resolve_identity(None, None, None, ACCOUNT_ID))
        self.assertEqual(first, second)

    def test_migration_folds_legacy_owners_and_merges_presence(self):
        """启动迁移把旧归属折叠，并把同一账号的多行在线记录合并成最近一次心跳。"""
        self._configure("host_account_allowlist", hostAccountAllowlist=[ACCOUNT_ID])
        legacy = f"host-public:{ACCOUNT_ID}"
        canonical = f"host:{ACCOUNT_ID}"
        main.save_credentials({
            "rea_old": {
                "id": "rea_old", "owner": legacy, "type": "reamicro", "label": "阅微账号",
                "accountId": ACCOUNT_ID, "secretEncrypted": main.encrypt_secret({"token": "t"}),
                "createdAt": 1, "updatedAt": 1, "enabled": True,
            },
        })
        main.save_tasks({
            "task_old": {
                "id": "task_old", "owner": legacy, "taskType": "yeshe_checkin",
                "credentialId": "rea_old", "schedule": {"intervalSeconds": 86400},
                "status": "success", "enabled": True, "createdAt": 1, "nextRunAt": 0,
            },
        })
        main.save_notifications({
            "msg_old": {
                "id": "msg_old", "owner": legacy, "taskId": "task_old", "result": "success",
                "title": "野社零点签到执行完成", "message": "签到完成", "createdAt": 1, "deliveredAt": 0,
            },
        })
        main.save_presence({
            legacy: {"owner": legacy, "lastSeenAt": 1000, "onlineUntil": 2000, "moduleVersion": "2.0.0", "source": "foreground"},
            canonical: {"owner": canonical, "lastSeenAt": 9000, "onlineUntil": 99999999999999, "moduleVersion": "2.0.0", "source": "foreground"},
        })

        stats = main.canonicalize_owner_identities()

        self.assertEqual(canonical, main.load_credentials()["rea_old"]["owner"])
        self.assertEqual(canonical, main.load_tasks()["task_old"]["owner"])
        self.assertEqual(canonical, main.load_notifications()["msg_old"]["owner"])
        presence = main.load_presence()
        self.assertEqual([canonical], list(presence))
        self.assertEqual(9000, presence[canonical]["lastSeenAt"], "应保留最近一次心跳")
        self.assertEqual(1, stats["presence"])

    def test_notifications_reach_module_after_auth_mode_change(self):
        """端到端：任务在公开模式下建立，管理员改成白名单模式后消息仍能推送并回执。"""
        self._configure("public")
        owner_before = main.resolve_identity(None, None, None, ACCOUNT_ID)
        task = {
            "id": "task_1", "owner": owner_before, "taskType": "yeshe_checkin",
            "credentialId": "rea_1", "schedule": {"intervalSeconds": 86400},
            "status": "success", "enabled": True, "createdAt": 1, "nextRunAt": 0,
        }
        main.save_tasks({"task_1": task})
        notification_id = main.enqueue_task_notification(task, "success", "签到完成", 1)

        # 管理员切换认证模式，随后模块重新上报心跳。
        self._configure("host_account_allowlist", hostAccountAllowlist=[ACCOUNT_ID])
        main.canonicalize_owner_identities()
        owner_after = main.resolve_identity(None, None, None, ACCOUNT_ID)

        pending = [item for item in main.load_notifications().values() if item.get("owner") == owner_after and not item.get("deliveredAt")]
        self.assertEqual(1, len(pending), "改认证模式后消息必须仍然属于同一归属，否则永远推不出去")
        self.assertEqual(notification_id, pending[0]["id"])

    def test_admin_presence_panel_dedupes_by_account(self):
        """即使存量数据未折叠，后台在线列表也只显示同一账号一行。"""
        self._configure("host_account_allowlist", hostAccountAllowlist=[ACCOUNT_ID])
        now = int(datetime.now(timezone.utc).timestamp() * 1000)
        main.save_presence({
            f"host-public:{ACCOUNT_ID}": {"owner": f"host-public:{ACCOUNT_ID}", "lastSeenAt": now - 86_400_000, "onlineUntil": now - 80_000_000, "moduleVersion": "2.0.0", "source": "foreground"},
            f"host:{ACCOUNT_ID}": {"owner": f"host:{ACCOUNT_ID}", "lastSeenAt": now, "onlineUntil": now + 600_000, "moduleVersion": "2.0.0", "source": "foreground"},
        })
        panel = main.__dict__["_admin_presence_panel"]()
        self.assertEqual(1, panel.count("阅微账号 3"), "同一个阅微账号只能显示一行")
        self.assertIn("在线", panel)

    def test_overview_online_count_dedupes_by_account(self):
        self._configure("host_account_allowlist", hostAccountAllowlist=[ACCOUNT_ID])
        now = int(datetime.now(timezone.utc).timestamp() * 1000)
        main.save_presence({
            f"host-public:{ACCOUNT_ID}": {"owner": f"host-public:{ACCOUNT_ID}", "lastSeenAt": now, "onlineUntil": now + 600_000, "moduleVersion": "2.0.0", "source": "alarm"},
            f"host:{ACCOUNT_ID}": {"owner": f"host:{ACCOUNT_ID}", "lastSeenAt": now, "onlineUntil": now + 600_000, "moduleVersion": "2.0.0", "source": "foreground"},
        })
        stats = main.__dict__["_admin_overview_stats"](main.load_config(), [])
        self.assertIn(">1</strong><span>模块在线设备", stats.replace("<strong", ">").replace(">>", ">"))

    def test_canonical_owner_of_handles_every_legacy_form(self):
        self.assertEqual("host:3", main.canonical_owner_of("host:3"))
        self.assertEqual("host:3", main.canonical_owner_of("host-public:3"))
        self.assertEqual("host:3", main.canonical_owner_of("key:abcdef:host:3"))
        self.assertEqual("host:3", main.canonical_owner_of("account:user:host:3"))
        self.assertEqual("key:abcdef", main.canonical_owner_of("key:abcdef"))
        self.assertEqual("public", main.canonical_owner_of("public"))


if __name__ == "__main__":
    unittest.main()
