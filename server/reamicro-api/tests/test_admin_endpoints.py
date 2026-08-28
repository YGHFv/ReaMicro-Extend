"""后台路由端到端测试。

覆盖此前完全没有测试的 22 个 /admin 路由：登录会话、内容包上传与编辑、
回滚删除、差异对比、任务与凭据操作、子管理员管理、API Key、快照校验恢复。
"""
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from tests.conftest_support import (
    ADMIN_PASSWORD,
    ADMIN_USER,
    HOST_ACCOUNT_ID,
    admin_auth,
    admin_csrf,
    base_config,
    client,
    isolate,
    seed_credential,
    seed_package,
    seed_task,
)
from app import main, runtime

# 真实浏览器的 Accept 头。后台错误页按它决定返回 HTML 还是 JSON。
BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"


class AdminAuthTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        self.client = client()

    def tearDown(self):
        self.temp.cleanup()

    def test_admin_requires_auth(self):
        anonymous = client()
        response = anonymous.get("/admin", follow_redirects=False)
        self.assertIn(response.status_code, (302, 303, 401))

    def test_login_page_renders(self):
        response = self.client.get("/admin/login")
        self.assertEqual(200, response.status_code)
        self.assertIn("ReaMicro", response.text)

    def test_login_sets_session_and_grants_access(self):
        login = self.client.post(
            "/admin/login",
            data={"username": ADMIN_USER, "password": ADMIN_PASSWORD},
            follow_redirects=False,
        )
        self.assertIn(login.status_code, (302, 303))
        self.assertIn(runtime.ADMIN_SESSION_COOKIE, login.cookies)
        page = self.client.get("/admin")
        self.assertEqual(200, page.status_code)
        self.assertIn("概览", page.text)

    def test_login_rejects_wrong_password(self):
        response = self.client.post(
            "/admin/login",
            data={"username": ADMIN_USER, "password": "wrong-password"},
            follow_redirects=False,
        )
        self.assertNotIn(runtime.ADMIN_SESSION_COOKIE, response.cookies)
        self.assertNotEqual(303, response.status_code)

    def test_logout_clears_session(self):
        self.client.post("/admin/login", data={"username": ADMIN_USER, "password": ADMIN_PASSWORD})
        response = self.client.post(
            "/admin/logout",
            data={"csrf_token": admin_csrf()},
            follow_redirects=False,
        )
        self.assertIn(response.status_code, (302, 303))

    def test_all_admin_sections_render_with_basic_auth(self):
        for path in ("/admin", "/admin/content", "/admin/tasks", "/admin/settings", "/admin/security"):
            response = self.client.get(path, auth=admin_auth())
            self.assertEqual(200, response.status_code, f"{path} 返回 {response.status_code}")
            self.assertIn("<aside>", response.text, f"{path} 缺少统一侧栏")

    def test_content_kind_sections_render(self):
        seed_package()
        for kind in sorted(runtime.PACKAGE_KINDS):
            response = self.client.get(f"/admin/content/{kind}", auth=admin_auth())
            self.assertEqual(200, response.status_code, f"{kind} 返回 {response.status_code}")
            self.assertIn("<table>", response.text, f"{kind} 缺少内容表格")

    def test_unknown_content_kind_returns_readable_error(self):
        response = self.client.get("/admin/content/not_a_kind", auth=admin_auth(), headers={"Accept": BROWSER_ACCEPT})
        self.assertEqual(404, response.status_code)
        # 浏览器请求必须拿到 HTML 提示页，不是裸 JSON。
        self.assertNotIn('{"code"', response.text)
        self.assertIn("操作未完成", response.text)

    def test_csrf_required_for_mutations(self):
        response = self.client.post(
            "/admin/admins/create",
            data={"username": "helper", "password": "helper-password-1", "permissions": "packages:write"},
            auth=admin_auth(),
        )
        self.assertEqual(403, response.status_code)


class AdminPackageOperationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        self.manifest = seed_package()
        self.client = client()
        self.csrf = admin_csrf()

    def tearDown(self):
        self.temp.cleanup()

    def test_new_package_form_renders(self):
        response = self.client.get("/admin/packages/new", auth=admin_auth())
        self.assertEqual(200, response.status_code)
        self.assertIn("<form", response.text)

    def test_preview_history_and_diff_render(self):
        for suffix in ("preview", "history", "edit", "diff"):
            response = self.client.get(
                f"/admin/packages/online_source/example-com/{suffix}",
                auth=admin_auth(),
            )
            self.assertEqual(200, response.status_code, f"{suffix} 返回 {response.status_code}: {response.text[:200]}")
            self.assertIn("<aside>", response.text, f"{suffix} 缺少统一侧栏")

    def test_upload_creates_new_package(self):
        payload = json.dumps({"bookSourceName": "上传书源", "bookSourceUrl": "https://upload.example.org"}, ensure_ascii=False).encode()
        response = self.client.post(
            "/admin/packages/upload",
            auth=admin_auth(),
            data={"kind": "online_source", "csrf_token": self.csrf, "status_value": "published", "channel": "stable"},
            files={"payload": ("upload.json", payload, "application/json")},
        )
        self.assertEqual(200, response.status_code, response.text[:400])
        kinds = {path.parent.name for path in (runtime.PACKAGE_ROOT / "online_source").glob("*/manifest.json")}
        # 包 ID 由域名推导，点号是合法字符。
        self.assertIn("upload.example.org", kinds)

    def test_upload_rejects_invalid_kind(self):
        response = self.client.post(
            "/admin/packages/upload",
            auth=admin_auth(),
            data={"kind": "bogus_kind", "csrf_token": self.csrf},
            files={"payload": ("x.json", b"{}", "application/json")},
        )
        self.assertEqual(400, response.status_code)

    def test_edit_updates_metadata(self):
        response = self.client.post(
            "/admin/packages/online_source/example-com/edit",
            auth=admin_auth(),
            data={
                "csrf_token": self.csrf,
                # version/filename 是必填项，编辑表单里也确实渲染了这两个字段。
                "version": "1.3.0",
                "filename": "source.json",
                # payload_text 会整份写回成新内容，编辑表单里是预填好的 textarea。
                "payload_text": json.dumps({"bookSourceName": "改名后的书源", "bookSourceUrl": "https://example.com"}, ensure_ascii=False),
                "name": "改名后的书源",
                "status_value": "published",
                "channel": "beta",
                "aliases": "alias-one",
                "dependencies": "[]",
            },
        )
        self.assertEqual(200, response.status_code, response.text[:400])
        stored = json.loads((runtime.PACKAGE_ROOT / "online_source" / "example-com" / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("改名后的书源", stored["name"])
        self.assertEqual("beta", stored["channel"])

    def test_rollback_restores_previous_version(self):
        response = self.client.post(
            "/admin/packages/online_source/example-com/rollback",
            auth=admin_auth(),
            # 回滚按清单里的 version 值定位，不是历史目录名。
            data={"csrf_token": self.csrf, "version": "1.1.0"},
        )
        self.assertEqual(200, response.status_code, response.text[:400])
        stored = json.loads((runtime.PACKAGE_ROOT / "online_source" / "example-com" / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("1.1.0", stored["version"])

    def test_published_package_cannot_be_deleted_directly(self):
        response = self.client.post(
            "/admin/packages/online_source/example-com/delete",
            auth=admin_auth(),
            headers={"Accept": BROWSER_ACCEPT},
            data={"csrf_token": self.csrf},
        )
        self.assertEqual(409, response.status_code)
        self.assertTrue((runtime.PACKAGE_ROOT / "online_source" / "example-com" / "manifest.json").is_file())
        # 浏览器 Accept 必须拿到 HTML 提示页；API 客户端才拿 JSON。
        self.assertNotIn('{"code"', response.text)
        self.assertIn("操作未完成", response.text)

    def test_api_client_still_gets_json_error(self):
        response = self.client.post(
            "/admin/packages/online_source/example-com/delete",
            auth=admin_auth(),
            headers={"Accept": "application/json"},
            data={"csrf_token": self.csrf},
        )
        self.assertEqual(409, response.status_code)
        self.assertEqual("HTTP_ERROR", response.json()["code"])

    def test_unpublished_package_can_be_deleted(self):
        seed_package(package_id="draft-source", status="unpublished")
        response = self.client.post(
            "/admin/packages/online_source/draft-source/delete",
            auth=admin_auth(),
            data={"csrf_token": self.csrf},
        )
        self.assertEqual(200, response.status_code, response.text[:400])
        self.assertFalse((runtime.PACKAGE_ROOT / "online_source" / "draft-source").exists())

    def test_package_paths_reject_traversal(self):
        for package_id in ("..", "../..", "....", "a/b"):
            response = self.client.get(
                f"/admin/packages/online_source/{package_id}/preview",
                auth=admin_auth(),
            )
            self.assertIn(response.status_code, (400, 404), f"{package_id} 必须被拒绝")


class AdminTaskOperationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        seed_credential()
        seed_task()
        self.client = client()
        self.csrf = admin_csrf()

    def tearDown(self):
        self.temp.cleanup()

    def test_task_actions(self):
        for action, expected in (("pause", "paused"), ("resume", "scheduled"), ("cancel", "cancelled")):
            response = self.client.post(
                "/admin/tasks/action",
                auth=admin_auth(),
                data={"csrf_token": self.csrf, "task_id": "task_1", "action": action},
            )
            self.assertEqual(200, response.status_code, f"{action}: {response.text[:300]}")
            self.assertEqual(expected, main.load_tasks()["task_1"]["status"], f"{action} 后状态不符")

    def test_task_delete(self):
        response = self.client.post(
            "/admin/tasks/action",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "task_id": "task_1", "action": "delete"},
        )
        self.assertEqual(200, response.status_code, response.text[:300])
        self.assertNotIn("task_1", main.load_tasks())

    def test_task_logs_page(self):
        main.task_log("task_1", "测试日志行")
        response = self.client.get("/admin/tasks/task_1/logs", auth=admin_auth())
        self.assertEqual(200, response.status_code)
        self.assertIn("测试日志行", response.text)
        self.assertIn("<aside>", response.text)

    def test_credential_actions(self):
        toggled = self.client.post(
            "/admin/credentials/action",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "credential_id": "rea_1", "action": "toggle"},
        )
        self.assertEqual(200, toggled.status_code, toggled.text[:300])
        self.assertFalse(main.load_credentials()["rea_1"]["enabled"])

        deleted = self.client.post(
            "/admin/credentials/action",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "credential_id": "rea_1", "action": "delete"},
        )
        self.assertEqual(200, deleted.status_code, deleted.text[:300])
        self.assertNotIn("rea_1", main.load_credentials())

    def test_deleting_credential_pauses_dependent_tasks(self):
        self.client.post(
            "/admin/credentials/action",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "credential_id": "rea_1", "action": "delete"},
        )
        task = main.load_tasks().get("task_1")
        self.assertIsNotNone(task)
        self.assertEqual("paused", task["status"], "删除密钥后依赖任务必须暂停，否则会反复失败")

    def test_admin_create_task(self):
        response = self.client.post(
            "/admin/tasks/create",
            auth=admin_auth(),
            data={
                "csrf_token": self.csrf,
                "task_type": "yeshe_checkin",
                "credential_id": "rea_1",
                "time_of_day": "00:05",
                "interval_seconds": "86400",
            },
        )
        self.assertEqual(200, response.status_code, response.text[:400])
        checkins = [t for t in main.load_tasks().values() if t.get("taskType") == "yeshe_checkin"]
        self.assertGreaterEqual(len(checkins), 1)


class AdminSecurityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        self.client = client()
        self.csrf = admin_csrf()

    def tearDown(self):
        self.temp.cleanup()

    def test_subadmin_lifecycle(self):
        created = self.client.post(
            "/admin/admins/create",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "username": "helper", "password": "helper-password-123", "permissions": ["packages:write", "tasks:write"]},
        )
        self.assertEqual(200, created.status_code, created.text[:400])
        self.assertIn("helper", main.load_config()["adminAccounts"])

        toggled = self.client.post(
            "/admin/admins/toggle",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "username": "helper"},
        )
        self.assertEqual(200, toggled.status_code)
        self.assertFalse(main.load_config()["adminAccounts"]["helper"]["enabled"])

        reset = self.client.post(
            "/admin/admins/reset",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "username": "helper"},
        )
        self.assertEqual(200, reset.status_code)
        # 重置后必须展示一次新口令，且不能是明文存储。
        self.assertNotIn(main.load_config()["adminAccounts"]["helper"]["passwordHash"], reset.text)

        deleted = self.client.post(
            "/admin/admins/delete",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "username": "helper"},
        )
        self.assertEqual(200, deleted.status_code)
        self.assertNotIn("helper", main.load_config()["adminAccounts"])

    def test_subadmin_cannot_manage_admins(self):
        main.save_config({
            **main.load_config(),
            "adminAccounts": {
                "helper": {
                    "passwordHash": main.password_hash("helper-password-123"),
                    "enabled": True,
                    "permissions": ["packages:write"],
                },
            },
        })
        actor = {"username": "helper", "role": "subadmin", "permissions": ["packages:write"], "needsSetup": False}
        token = main.admin_csrf_token(main.load_config(), actor)
        response = self.client.post(
            "/admin/admins/create",
            auth=("helper", "helper-password-123"),
            data={"csrf_token": token, "username": "another", "password": "another-password-1"},
        )
        self.assertEqual(403, response.status_code)
        self.assertNotIn("another", main.load_config()["adminAccounts"])

    def test_api_key_create_and_revoke_via_forms(self):
        created = self.client.post(
            "/admin/security/api-keys/create",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "name": "同步脚本", "permissions": ["read"]},
        )
        self.assertEqual(200, created.status_code, created.text[:400])
        records = main.load_config()["apiKeyRecords"]
        self.assertEqual(1, len(records))
        # 明文密钥只展示一次，不能落盘。
        self.assertNotIn("plaintext", json.dumps(records))
        self.assertIn("digest", records[0])

        revoked = self.client.post(
            "/admin/security/api-keys/revoke",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "key_id": records[0]["id"]},
        )
        self.assertEqual(200, revoked.status_code, revoked.text[:400])
        self.assertFalse(main.load_config()["apiKeyRecords"][0]["enabled"])

    def test_snapshot_create_verify_download(self):
        created = self.client.post(
            "/admin/backups/server",
            auth=admin_auth(),
            data={"csrf_token": self.csrf},
        )
        self.assertEqual(200, created.status_code, created.text[:400])
        snapshots = sorted(runtime.SERVER_BACKUP_ROOT.glob("*.zip"))
        self.assertEqual(1, len(snapshots))

        verified = self.client.post(
            "/admin/security/backups/verify",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "filename": snapshots[0].name},
        )
        self.assertEqual(200, verified.status_code, verified.text[:400])

        downloaded = self.client.get(f"/admin/backups/server/{snapshots[0].name}", auth=admin_auth())
        self.assertEqual(200, downloaded.status_code)
        self.assertTrue(downloaded.content.startswith(b"PK"))

    def test_snapshot_filename_rejects_traversal(self):
        for name in ("../server.json", "..%2Fserver.json", "/etc/passwd"):
            response = self.client.get(f"/admin/backups/server/{name}", auth=admin_auth())
            self.assertIn(response.status_code, (400, 404), f"{name} 必须被拒绝")

    def test_snapshot_restore_requires_confirmation(self):
        self.client.post("/admin/backups/server", auth=admin_auth(), data={"csrf_token": self.csrf})
        name = sorted(runtime.SERVER_BACKUP_ROOT.glob("*.zip"))[0].name
        mismatch = self.client.post(
            "/admin/security/backups/restore",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "filename": name, "confirm": "wrong-name.zip"},
        )
        self.assertEqual(400, mismatch.status_code)
        matched = self.client.post(
            "/admin/security/backups/restore",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "filename": name, "confirm": name},
        )
        self.assertEqual(200, matched.status_code, matched.text[:400])

    def test_rotate_secret_reencrypts_credentials(self):
        seed_credential()
        original = main.decrypt_secret(main.load_credentials()["rea_1"]["secretEncrypted"])
        response = self.client.post(
            "/admin/security/rotate-secret",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "new_secret_key": "brand-new-secret-key-value-1234"},
        )
        self.assertEqual(200, response.status_code, response.text[:400])
        # 轮换后旧密文必须能用新密钥解出同样内容。
        self.assertEqual(original, main.decrypt_secret(main.load_credentials()["rea_1"]["secretEncrypted"]))

    def test_settings_update_persists_module_upload_config(self):
        response = self.client.post(
            "/admin/settings",
            auth=admin_auth(),
            data={
                "csrf_token": self.csrf,
                "server_id": "reamicro-api",
                "auth_mode": "host_account_allowlist",
                "host_account_allowlist": HOST_ACCOUNT_ID,
                "features": "association_source,backup",
                "min_module_version": "2.0.0",
                "github_repository": "YGHFv/ReaMicro-Extend",
                "github_include_prerelease": "on",
                "release_sync_seconds": "1800",
                "release_version_code": "0",
                "server_snapshot_enabled": "on",
                "server_snapshot_seconds": "86400",
                "server_snapshot_retention": "30",
                "signing_public_key": "",
                "module_upload_enabled": "on",
                "module_upload_allowlist": f"{HOST_ACCOUNT_ID}\n10086",
                "module_upload_kinds": "online_source,association_source",
            },
        )
        self.assertEqual(200, response.status_code, response.text[:500])
        config = main.load_config()
        self.assertTrue(config["moduleUploadEnabled"])
        self.assertEqual({HOST_ACCOUNT_ID, "10086"}, set(config["moduleUploadAllowlist"]))
        self.assertTrue(config["githubIncludePrerelease"], "预发布开关必须能真正保存")

    def test_audit_page_requires_permission(self):
        response = self.client.get("/admin/audit", auth=admin_auth())
        self.assertEqual(200, response.status_code)
        self.assertIn("<aside>", response.text)


if __name__ == "__main__":
    unittest.main()
