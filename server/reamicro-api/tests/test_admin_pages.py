"""后台页面渲染回归测试。

覆盖三类历史问题：
1. 分区页只渲染标题、不渲染表格（内容表格曾被误缩进在概览分支里）。
2. 各子页各用一套外壳和样式，缺少侧栏。
3. 毫秒时间戳、英文枚举和裸 JSON 直接显示给用户。
"""
import json
import sys
import tempfile
import unittest
import zipfile
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import main, runtime
from tests.conftest_support import isolate


PRIMARY = {"username": "owner", "role": "primary", "needsSetup": False, "permissions": []}
SUBADMIN = {"username": "helper", "role": "subadmin", "needsSetup": False, "permissions": ["packages:write"]}
RAW_TIMESTAMP = "1750000000000"


class AdminPageRenderTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        isolate(root, secret_key="test-secret-key-for-admin-pages")
        self.config = main.save_config({
            **main.default_config(),
            "primaryAdmin": {"username": "owner", "passwordHash": main.password_hash("owner-password-123")},
            "adminAccounts": {
                "helper": {
                    "passwordHash": main.password_hash("helper-password-123"),
                    "enabled": True,
                    "permissions": ["packages:write", "tasks:write"],
                    "createdAt": RAW_TIMESTAMP,
                    "updatedAt": RAW_TIMESTAMP,
                    "createdBy": "owner",
                },
            },
            "moduleUploadEnabled": True,
            "moduleUploadAllowlist": ["10086"],
            "apiKeyRecords": [{
                "id": "key_test",
                "name": "测试密钥",
                "digest": main.api_key_digest("test-key-value"),
                "permissions": ["read"],
                "enabled": True,
                "createdAt": int(RAW_TIMESTAMP),
                "lastUsedAt": 0,
            }],
        })
        self._seed_package()
        self._seed_task_data()
        self._seed_audit()
        self._seed_snapshot()

    def tearDown(self):
        self.temp_dir.cleanup()

    def _seed_package(self):
        package_dir = runtime.PACKAGE_ROOT / "online_source" / "example-com"
        package_dir.mkdir(parents=True, exist_ok=True)
        manifest = {
            "packageId": "example-com",
            "kind": "online_source",
            "version": "1.2.0",
            "buildTime": int(RAW_TIMESTAMP),
            "schemaVersion": 1,
            "sha256": "a" * 64,
            "payload": "source.json",
            "contentId": "online_example",
            "name": "示例书源",
            "domains": ["example.com"],
            "aliases": ["online_example"],
            "status": "published",
            "channel": "stable",
            "dependencies": [],
            "uploadOwner": "host:10086",
        }
        (package_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
        (package_dir / "source.json").write_text(json.dumps({"bookSourceName": "示例书源"}, ensure_ascii=False), encoding="utf-8")
        history = package_dir / "history" / "1.1.0-1740000000000"
        history.mkdir(parents=True, exist_ok=True)
        (history / "manifest.json").write_text(
            json.dumps({**manifest, "version": "1.1.0", "payload": "source.json"}, ensure_ascii=False),
            encoding="utf-8",
        )
        (history / "source.json").write_text(json.dumps({"bookSourceName": "旧版示例书源"}, ensure_ascii=False), encoding="utf-8")

    def _seed_task_data(self):
        main.save_credentials({
            "rea_1": {
                "id": "rea_1",
                "owner": "host:10086",
                "type": "reamicro",
                "label": "阅微账号",
                "accountId": "10086",
                "secretEncrypted": main.encrypt_secret({"token": "token-value"}),
                "createdAt": int(RAW_TIMESTAMP),
                "updatedAt": int(RAW_TIMESTAMP),
                "enabled": True,
            },
        })
        main.save_tasks({
            "task_1": {
                "id": "task_1",
                "owner": "host:10086",
                "taskType": "yeshe_checkin",
                "credentialId": "rea_1",
                "schedule": {"intervalSeconds": 86400, "timeOfDay": "00:05", "timezoneOffsetMinutes": 480},
                "requestEncrypted": main.encrypt_secret({"credentialId": "rea_1"}),
                "status": "success",
                "enabled": True,
                "createdAt": int(RAW_TIMESTAMP),
                "nextRunAt": int(RAW_TIMESTAMP),
                "runCount": 3,
                "lastResult": "success",
                "lastMessage": "签到完成",
                "executionHistory": [
                    {"finishedAt": int(RAW_TIMESTAMP), "result": "success", "durationMs": 1234, "message": "签到完成"},
                ],
            },
        })
        main.save_presence({
            "host:10086": {
                "owner": "host:10086",
                "lastSeenAt": int(RAW_TIMESTAMP),
                "onlineUntil": int(datetime.now(timezone.utc).timestamp() * 1000) + 600_000,
                "moduleVersion": "2.4.0",
                "buildTime": RAW_TIMESTAMP,
                "source": "module",
            },
        })
        main.save_notifications({
            "msg_1": {
                "id": "msg_1",
                "owner": "host:10086",
                "taskId": "task_1",
                "taskType": "yeshe_checkin",
                "result": "success",
                "title": "野社零点签到执行完成",
                "message": "签到完成",
                "createdAt": int(RAW_TIMESTAMP),
                "deliveredAt": 0,
            },
        })

    def _seed_snapshot(self):
        """造一份快照文件，让快照表格渲染出下载和校验按钮。"""
        runtime.SERVER_BACKUP_ROOT.mkdir(parents=True, exist_ok=True)
        self.snapshot_name = "reamicro-server-2026-08-28T010203Z.zip"
        with zipfile.ZipFile(runtime.SERVER_BACKUP_ROOT / self.snapshot_name, "w") as archive:
            archive.writestr("snapshot-manifest.json", json.dumps({"createdAt": int(RAW_TIMESTAMP), "files": []}))

    def _seed_audit(self):
        runtime.AUDIT_ROOT.mkdir(parents=True, exist_ok=True)
        runtime.AUDIT_PATH.write_text(
            json.dumps({
                "at": "2026-08-28T01:02:03+00:00",
                "actor": "admin:owner",
                "action": "package_uploaded",
                "success": True,
                "requestId": "req_test",
                "metadata": {"kind": "online_source", "packageId": "example-com", "version": "1.2.0"},
            }, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )

    def assert_unified_shell(self, page: str, label: str):
        """所有后台页面都必须带同一套侧栏、导航和样式。"""
        self.assertIn("ReaMicro API 管理后台", page, label)
        self.assertIn("<aside>", page, label)
        self.assertIn('href="/admin/tasks"', page, label)
        self.assertIn("--line:#e5e9f0", page, label)
        self.assertIn("/admin/logout", page, label)

    def assert_no_raw_values(self, page: str, label: str):
        """页面上不允许出现毫秒时间戳和英文枚举。"""
        self.assertNotIn(RAW_TIMESTAMP, page, f"{label} 出现了未格式化的毫秒时间戳")
        for token in (">published<", ">stable<", ">draft<", ">success<", ">failed<"):
            self.assertNotIn(token, page, f"{label} 出现了未翻译的英文枚举 {token}")

    def test_every_section_renders_content_table(self):
        # 历史问题：内容表格被误缩进在概览分支，导致分区页只有标题没有表格。
        for section in ("overview", "packages", "online_source", "association_source", "epub_style", "highlight_style", "theme"):
            page = main.admin_page(self.config, actor=PRIMARY, section=section)
            self.assert_unified_shell(page, section)
            self.assertIn("<table>", page, f"{section} 缺少内容表格")
            self.assertIn("搜索名称、包 ID、版本或别名", page, f"{section} 缺少搜索框")
        online = main.admin_page(self.config, actor=PRIMARY, section="online_source")
        self.assertIn("示例书源", online)
        self.assertIn("example.com", online)

    def test_all_sections_share_one_shell(self):
        for section in ("overview", "packages", "tasks", "settings", "security"):
            self.assert_unified_shell(main.admin_page(self.config, actor=PRIMARY, section=section), section)

    def test_overview_shows_presence_and_notifications(self):
        # 历史问题：模块在线状态和离线消息队列在后台完全没有视图。
        page = main.admin_page(self.config, actor=PRIMARY, section="overview")
        self.assertIn("模块在线状态", page)
        self.assertIn("任务消息队列", page)
        self.assertIn("2.4.0", page)
        self.assertIn("在线", page)
        self.assertIn("野社零点签到执行完成", page)
        self.assert_no_raw_values(page, "overview")

    def test_security_page_exposes_every_managed_action(self):
        # 历史问题：重置密码、停用、删除、密钥轮换、API Key 和快照恢复都没有界面入口。
        page = main.admin_page(self.config, actor=PRIMARY, section="security")
        for action in (
            "/admin/admins/create",
            "/admin/admins/reset",
            "/admin/admins/toggle",
            "/admin/admins/delete",
            "/admin/security/rotate-secret",
            "/admin/security/api-keys/create",
            "/admin/security/api-keys/revoke",
            "/admin/backups/server",
            "/admin/security/backups/verify",
            "/admin/security/backups/restore",
            "/admin/audit",
        ):
            self.assertIn(action, page, f"安全页缺少 {action} 的入口")
        self.assertIn("helper", page)
        self.assertIn("测试密钥", page)
        self.assert_no_raw_values(page, "security")

    def test_subadmin_security_page_hides_primary_only_blocks(self):
        page = main.admin_page(self.config, actor=SUBADMIN, section="security")
        self.assert_unified_shell(page, "security-subadmin")
        # 子管理员不能管理其他管理员和 API Key，也不能恢复快照。
        self.assertNotIn("/admin/admins/create", page)
        self.assertNotIn("/admin/security/api-keys/create", page)
        self.assertNotIn("/admin/security/backups/restore", page)
        self.assertIn("当前账号权限", page)

    def test_subadmin_with_backup_permission_sees_snapshot_actions(self):
        actor = {**SUBADMIN, "permissions": ["backup:admin"]}
        page = main.admin_page(self.config, actor=actor, section="security")
        self.assertIn("/admin/security/backups/verify", page)
        self.assertIn("立即创建快照", page)
        # 恢复仍然仅限主管理员。
        self.assertNotIn("/admin/security/backups/restore", page)
        self.assertIn("恢复快照仅限主管理员操作", page)

    def test_settings_page_exposes_prerelease_and_signing_fields(self):
        # 历史问题：包含预发布 Release、签名公钥等做成隐藏字段，界面上无法修改。
        page = main.admin_page(self.config, actor=PRIMARY, section="settings")
        self.assertIn("包含预发布 Release", page)
        self.assertIn("name='github_include_prerelease'", page)
        self.assertIn("name='signing_public_key'", page)
        self.assertIn("name='server_snapshot_enabled'", page)
        self.assertIn("name='release_version_code'", page)
        self.assertNotIn("type='hidden' name='github_include_prerelease'", page)
        self.assertNotIn("type='hidden' name='signing_public_key'", page)
        self.assertIn("模块 Release 同步", page)
        self.assertIn("启用模块上传内容库", page)

    def test_tasks_page_exposes_task_and_credential_actions(self):
        # 历史问题：任务无法删除，凭据表只读，没有验证、停用和删除操作。
        page = main.admin_page(self.config, actor=PRIMARY, section="tasks")
        self.assertIn("/admin/credentials/action", page)
        self.assertIn("value='delete'", page)
        self.assertIn("value='verify'", page)
        self.assertIn("value='toggle'", page)
        self.assertIn("野社零点签到", page)
        self.assert_no_raw_values(page, "tasks")

    def test_time_and_enum_labels_are_readable(self):
        # 1750000000000 毫秒 = UTC 2025-06-15 15:06:40 = 北京时间 23:06。
        self.assertEqual("2025-06-15 23:06", main.__dict__["_admin_time_label"](1750000000000))
        self.assertEqual("未安排", main.__dict__["_admin_time_label"](0))
        self.assertEqual("已发布", main.__dict__["_admin_status_label"]("published"))
        self.assertEqual("正式版", main.__dict__["_admin_channel_label"]("stable"))
        self.assertEqual("成功", main.__dict__["_admin_result_label"]("success"))
        self.assertEqual("1.2 秒", main.__dict__["_admin_duration_label"](1234))
        self.assertEqual("3 分 20 秒", main.__dict__["_admin_duration_label"](200_000))
        self.assertEqual("1.5 KB", main.__dict__["_admin_size_label"](1536))
        self.assertEqual("上传内容包", main.__dict__["_admin_action_label"]("package_uploaded"))
        self.assertEqual(
            "类型：书源；内容包：example-com",
            main.__dict__["_admin_metadata_label"]({"kind": "online_source", "packageId": "example-com"}),
        )
        # ISO 字符串也要能解析成北京时间，审计日志用的就是 ISO。
        self.assertEqual("2026-08-28 09:02", main.__dict__["_admin_time_label"]("2026-08-28T01:02:03+00:00"))

    def test_admin_error_page_replaces_raw_json(self):
        # 历史问题：后台抛异常时返回裸 JSON，浏览器里无法阅读。
        page = main.admin_error_page(403, "当前子管理员缺少权限：packages:write", "req_abc")
        self.assertIn("操作未完成（HTTP 403）", page)
        self.assertIn("缺少权限", page)
        self.assertIn("req_abc", page)
        self.assertIn("返回后台首页", page)
        self.assertNotIn('{"code"', page)
        self.assertIn("重新登录", main.admin_error_page(401, "后台会话已失效"))

    def test_login_and_setup_pages_share_auth_style(self):
        for page in (main.admin_login_page(), main.admin_setup_page()):
            self.assertIn("ReaMicro API 管理后台", page)
            self.assertIn("--line:#e5e9f0", page)
        self.assertIn("用户名或密码错误", main.admin_login_page("用户名或密码错误"))

    def test_legacy_admin_page_helper_removed(self):
        # _legacy_admin_page 是 121 行没有任何调用点的死代码，已删除。
        self.assertFalse(hasattr(main, "_legacy_admin_page"))


if __name__ == "__main__":
    unittest.main()
