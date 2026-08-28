"""按阅微 ID 区分的用户管理，以及书源可用性检测。"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import runtime, security, source_check, users
from app.config_store import load_config, save_config
from app.state import save_credentials, save_presence, save_tasks
from tests.conftest_support import (
    HOST_ACCOUNT_ID,
    admin_auth,
    admin_csrf,
    base_config,
    client,
    isolate,
    module_headers,
    seed_credential,
    seed_package,
    seed_task,
)


class UserProfileTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        self.client = client()

    def tearDown(self):
        self.temp.cleanup()

    def test_account_id_validation(self):
        self.assertEqual("3", users.normalize_account_id(" 3 "))
        self.assertEqual("abc_12-x", users.normalize_account_id("abc_12-x"))
        for bad in ("", "  ", "../etc", "a/b", "x" * 65, "a b"):
            self.assertEqual("", users.normalize_account_id(bad), f"{bad!r} 应被拒绝")

    def test_missing_profile_is_permissive(self):
        """没建档不等于没权限：访问控制由白名单负责，档案只用来收权。"""
        self.assertTrue(users.user_enabled("999"))
        for capability in users.USER_CAPABILITIES:
            self.assertTrue(users.user_has_capability("999", capability), capability)

    def test_new_profile_keeps_all_capabilities(self):
        """一加档案就把人锁在外面是回归，默认必须拥有全部功能。"""
        users.upsert_user("3")
        for capability in users.USER_CAPABILITIES:
            self.assertTrue(users.user_has_capability("3", capability), capability)

    def test_disabled_user_is_rejected_at_auth(self):
        users.upsert_user(HOST_ACCOUNT_ID, enabled=False)
        response = self.client.get("/v1/meta", headers=module_headers())
        self.assertEqual(403, response.status_code)
        self.assertIn("已被管理员停用", response.json()["message"])
        # 恢复后立即可用。
        users.upsert_user(HOST_ACCOUNT_ID, enabled=True)
        self.assertEqual(200, self.client.get("/v1/meta", headers=module_headers()).status_code)

    def test_disabled_user_is_rejected_on_every_endpoint(self):
        """停用必须在所有接口生效。

        check_request_auth 原先有四条独立 return，只在 resolve_identity 加闸门的话，
        /v1/meta、/v1/packages 这些走 authenticated 的接口会全部绕过。
        """
        seed_package()
        users.upsert_user(HOST_ACCOUNT_ID, enabled=False)
        for method, path, kwargs in (
            ("GET", "/v1/meta", {}),
            ("GET", "/v1/tasks", {}),
            ("GET", "/v1/notifications", {}),
            ("GET", "/v1/credentials/reamicro", {}),
            ("GET", "/v1/packages?kind=online_source", {}),
            ("GET", "/v1/diagnostics", {}),
            ("POST", "/v1/presence/heartbeat", {"json": {"source": "test"}}),
        ):
            response = self.client.request(method, path, headers=module_headers(), **kwargs)
            self.assertEqual(403, response.status_code, f"{method} {path} 未拦截停用用户")
        upload = self.client.post(
            "/v1/backups/module",
            headers=module_headers(**{"Content-Type": "application/zip"}),
            content=b"PK\x03\x04x",
        )
        self.assertEqual(403, upload.status_code)

    def test_discovery_stays_public_for_disabled_user(self):
        """能力发现是公开的，停用不该让模块连"服务器长什么样"都问不到。"""
        users.upsert_user(HOST_ACCOUNT_ID, enabled=False)
        self.assertEqual(200, self.client.get("/v1/discovery").status_code)
        self.assertEqual(200, self.client.get("/health/live").status_code)

    def test_upload_capability_can_be_revoked_without_touching_allowlist(self):
        base_config(
            moduleUploadEnabled=True,
            moduleUploadAllowlist=[HOST_ACCOUNT_ID],
            hostAccountAllowlist=[HOST_ACCOUNT_ID],
        )
        allowed = self.client.get("/v1/packages/upload/policy", headers=module_headers()).json()["data"]
        self.assertTrue(allowed["allowed"])
        # 只收回档案里的能力，白名单保持不变。
        users.upsert_user(HOST_ACCOUNT_ID, capabilities=["tasks:use", "backup:use"])
        denied = self.client.get("/v1/packages/upload/policy", headers=module_headers()).json()["data"]
        self.assertFalse(denied["allowed"])
        self.assertIn("上传权限已被管理员关闭", denied["reason"])
        self.assertIn(HOST_ACCOUNT_ID, load_config()["moduleUploadAllowlist"], "不应改动白名单")

    def test_heartbeat_creates_profile_automatically(self):
        seed_credential()
        self.assertIsNone(users.get_user(HOST_ACCOUNT_ID))
        self.client.post("/v1/presence/heartbeat", headers=module_headers(), json={"source": "foreground"})
        profile = users.get_user(HOST_ACCOUNT_ID)
        self.assertIsNotNone(profile, "首次心跳应自动建档")
        self.assertGreater(profile["firstSeenAt"], 0)
        self.assertGreater(profile["lastSeenAt"], 0)

    def test_statistics_aggregate_by_canonical_owner(self):
        """统计按规范归属归集，历史归属写法也要算进同一个用户。"""
        seed_credential("rea_1", owner=f"host:{HOST_ACCOUNT_ID}")
        seed_task("task_1", owner=f"host-public:{HOST_ACCOUNT_ID}")
        seed_task("task_2", owner=f"key:abc:host:{HOST_ACCOUNT_ID}", status="failed")
        seed_task("task_other", owner="host:99")
        stats = users.user_statistics(HOST_ACCOUNT_ID)
        self.assertEqual(1, stats["credentials"])
        self.assertEqual(2, stats["tasks"], "旧归属写法也应归到同一用户")
        self.assertEqual(1, stats["failedTasks"])

    def test_statistics_count_uploads(self):
        seed_package(package_id="mine", uploadOwner=f"host:{HOST_ACCOUNT_ID}")
        seed_package(package_id="theirs", uploadOwner="host:99")
        self.assertEqual(1, users.user_statistics(HOST_ACCOUNT_ID)["uploads"])

    def test_known_account_ids_include_allowlist_and_activity(self):
        base_config(hostAccountAllowlist=["3", "7"], moduleUploadAllowlist=["9"])
        seed_task("task_1", owner="host:11")
        found = users.known_account_ids()
        for expected in ("3", "7", "9", "11"):
            self.assertIn(expected, found)

    def test_sync_creates_profiles_for_known_ids(self):
        base_config(hostAccountAllowlist=["3", "7"])
        added = users.sync_users_from_activity()
        self.assertGreaterEqual(added, 2)
        self.assertIsNotNone(users.get_user("7"))
        # 重复同步不再新增。
        self.assertEqual(0, users.sync_users_from_activity())

    def test_allowlist_membership_toggle(self):
        users.set_allowlist_membership("42", allow_access=True, allow_upload=False)
        config = load_config()
        self.assertIn("42", config["hostAccountAllowlist"])
        self.assertNotIn("42", config["moduleUploadAllowlist"])
        users.set_allowlist_membership("42", allow_access=False, allow_upload=True)
        config = load_config()
        self.assertNotIn("42", config["hostAccountAllowlist"])
        self.assertIn("42", config["moduleUploadAllowlist"])

    def test_upsert_ignores_unknown_fields(self):
        """后台表单不能借这个接口写入任意键。"""
        users.upsert_user("3", note="备注", role="admin", enabled=True)
        profile = users.get_user("3")
        self.assertEqual("备注", profile["note"])
        self.assertNotIn("role", profile)


class AdminUsersPageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config(hostAccountAllowlist=[HOST_ACCOUNT_ID])
        self.client = client()
        self.csrf = admin_csrf()

    def tearDown(self):
        self.temp.cleanup()

    def test_users_page_renders_with_shell(self):
        response = self.client.get("/admin/users", auth=admin_auth())
        self.assertEqual(200, response.status_code)
        self.assertIn("<aside>", response.text)
        self.assertIn("用户管理", response.text)
        self.assertIn(f"阅微账号 {HOST_ACCOUNT_ID}", response.text)

    def test_nav_includes_users_entry(self):
        response = self.client.get("/admin", auth=admin_auth())
        self.assertIn('href="/admin/users"', response.text)

    def test_save_updates_profile_and_allowlists(self):
        response = self.client.post(
            "/admin/users/save",
            auth=admin_auth(),
            data={
                "csrf_token": self.csrf,
                "account_id": HOST_ACCOUNT_ID,
                "note": "主力机",
                "enabled": "on",
                "allow_access": "on",
                "allow_upload": "on",
                "capabilities": ["tasks:use", "content:upload"],
            },
        )
        self.assertEqual(200, response.status_code, response.text[:300])
        profile = users.get_user(HOST_ACCOUNT_ID)
        self.assertEqual("主力机", profile["note"])
        self.assertEqual(["content:upload", "tasks:use"], profile["capabilities"])
        config = load_config()
        self.assertIn(HOST_ACCOUNT_ID, config["moduleUploadAllowlist"])

    def test_unchecked_enabled_disables_user(self):
        self.client.post(
            "/admin/users/save",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "account_id": HOST_ACCOUNT_ID, "allow_access": "on"},
        )
        self.assertFalse(users.get_user(HOST_ACCOUNT_ID)["enabled"])

    def test_delete_and_sync(self):
        users.upsert_user(HOST_ACCOUNT_ID)
        deleted = self.client.post(
            "/admin/users/delete",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "account_id": HOST_ACCOUNT_ID},
        )
        self.assertEqual(200, deleted.status_code)
        self.assertIsNone(users.get_user(HOST_ACCOUNT_ID))
        synced = self.client.post("/admin/users/sync", auth=admin_auth(), data={"csrf_token": self.csrf})
        self.assertEqual(200, synced.status_code)
        self.assertIsNotNone(users.get_user(HOST_ACCOUNT_ID), "白名单里的账号应被补建")

    def test_save_rejects_bad_account_id(self):
        response = self.client.post(
            "/admin/users/save",
            auth=admin_auth(),
            headers={"Accept": "application/json"},
            data={"csrf_token": self.csrf, "account_id": "../etc"},
        )
        self.assertEqual(400, response.status_code)

    def test_save_requires_csrf(self):
        response = self.client.post(
            "/admin/users/save",
            auth=admin_auth(),
            data={"account_id": HOST_ACCOUNT_ID},
        )
        self.assertEqual(403, response.status_code)


class SourceCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        self.client = client()
        self.csrf = admin_csrf()

    def tearDown(self):
        self.temp.cleanup()

    def test_private_and_loopback_addresses_are_blocked(self):
        """检测目标来自上传内容，必须挡住内网探测（SSRF）。"""
        for target in ("http://127.0.0.1/", "http://localhost/", "http://10.0.0.1/",
                       "http://192.168.1.1/", "http://169.254.1.1/", "http://[::1]/"):
            result = source_check.check_url(target, timeout=2)
            self.assertEqual(source_check.STATUS_BLOCKED, result["status"], f"{target} 未被拦截")

    def test_non_http_schemes_blocked(self):
        for target in ("file:///etc/passwd", "ftp://example.com/", "gopher://example.com/"):
            self.assertEqual(source_check.STATUS_BLOCKED, source_check.check_url(target, timeout=2)["status"], target)

    def test_unresolvable_host_is_blocked_not_crash(self):
        result = source_check.check_url("https://this-host-does-not-exist.invalid/", timeout=2)
        self.assertIn(result["status"], (source_check.STATUS_BLOCKED, source_check.STATUS_UNREACHABLE))
        self.assertIsInstance(result["message"], str)

    def test_summarize_prefers_any_reachable_address(self):
        """多地址只要有一个能用就算这个源还活着。"""
        ok = {"status": source_check.STATUS_OK}
        slow = {"status": source_check.STATUS_SLOW}
        dead = {"status": source_check.STATUS_UNREACHABLE}
        blocked = {"status": source_check.STATUS_BLOCKED}
        self.assertEqual(source_check.STATUS_OK, source_check.summarize([dead, ok]))
        self.assertEqual(source_check.STATUS_SLOW, source_check.summarize([dead, slow]))
        self.assertEqual(source_check.STATUS_UNREACHABLE, source_check.summarize([dead, dead]))
        self.assertEqual(source_check.STATUS_BLOCKED, source_check.summarize([blocked, blocked]))
        self.assertEqual(source_check.STATUS_SKIPPED, source_check.summarize([]))

    def test_check_targets_keep_primary_first(self):
        manifest = {"domains": ["https://WWW.Primary.com/x", "mirror.example.net", "primary.com"]}
        targets = source_check.manifest_check_targets(manifest)
        self.assertEqual(["primary.com", "mirror.example.net"], targets, "应去 www、去重并保序")

    def test_check_package_writes_health_to_manifest(self):
        seed_package(package_id="blocked-source", domains=["127.0.0.1"])
        report = source_check.check_package("online_source", "blocked-source", timeout=2)
        self.assertEqual(source_check.STATUS_BLOCKED, report["status"])
        manifest = json.loads((runtime.PACKAGE_ROOT / "online_source" / "blocked-source" / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(source_check.STATUS_BLOCKED, manifest["healthCheck"]["status"])
        self.assertGreater(manifest["healthCheck"]["checkedAt"], 0)

    def test_check_missing_package_is_reported_not_raised(self):
        report = source_check.check_package("online_source", "nope", timeout=2)
        self.assertEqual(source_check.STATUS_SKIPPED, report["status"])
        self.assertIn("不存在", report["message"])

    def test_content_list_shows_health_column(self):
        seed_package(package_id="blocked-source", domains=["127.0.0.1"])
        source_check.check_package("online_source", "blocked-source", timeout=2)
        page = self.client.get("/admin/content/online_source", auth=admin_auth()).text
        self.assertIn("可用性", page)
        self.assertIn("地址被拒", page)
        self.assertIn("检测全部地址", page)

    def test_check_route_updates_and_reports(self):
        seed_package(package_id="blocked-source", domains=["127.0.0.1"])
        response = self.client.post(
            "/admin/packages/online_source/blocked-source/check",
            auth=admin_auth(),
            data={"csrf_token": self.csrf},
        )
        self.assertEqual(200, response.status_code, response.text[:300])
        self.assertIn("检测完成", response.text)

    def test_check_all_route(self):
        seed_package(package_id="blocked-source", domains=["127.0.0.1"])
        response = self.client.post(
            "/admin/content/online_source/check-all",
            auth=admin_auth(),
            data={"csrf_token": self.csrf},
        )
        self.assertEqual(200, response.status_code, response.text[:300])
        self.assertIn("已检测", response.text)

    def test_check_rejects_unknown_kind(self):
        response = self.client.post(
            "/admin/content/not_a_kind/check-all",
            auth=admin_auth(),
            headers={"Accept": "application/json"},
            data={"csrf_token": self.csrf},
        )
        self.assertEqual(404, response.status_code)


class MultiUrlEditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        seed_package()
        self.client = client()
        self.csrf = admin_csrf()

    def tearDown(self):
        self.temp.cleanup()

    def _manifest(self) -> dict:
        return json.loads((runtime.PACKAGE_ROOT / "online_source" / "example-com" / "manifest.json").read_text(encoding="utf-8"))

    def test_edit_form_exposes_domains_names_and_primary(self):
        page = self.client.get("/admin/packages/online_source/example-com/edit", auth=admin_auth()).text
        self.assertIn("name='domains'", page)
        self.assertIn("name='primary_domain'", page)
        self.assertIn("name='names'", page)
        self.assertIn("example.com", page)

    def test_edit_sets_explicit_primary_domain(self):
        response = self.client.post(
            "/admin/packages/online_source/example-com/edit",
            auth=admin_auth(),
            data={
                "csrf_token": self.csrf,
                "version": "1.5.0",
                "filename": "source.json",
                "payload_text": json.dumps({"bookSourceName": "示例书源"}, ensure_ascii=False),
                "domains": "backup.example.net\nmain.example.org",
                "primary_domain": "main.example.org",
                "status_value": "published",
                "channel": "stable",
                "dependencies": "[]",
            },
        )
        self.assertEqual(200, response.status_code, response.text[:300])
        manifest = self._manifest()
        self.assertEqual("main.example.org", manifest["primaryDomain"])
        self.assertEqual("main.example.org", manifest["domains"][0], "主地址要排在最前")
        self.assertIn("backup.example.net", manifest["domains"])

    def test_edit_accumulates_historical_names(self):
        response = self.client.post(
            "/admin/packages/online_source/example-com/edit",
            auth=admin_auth(),
            data={
                "csrf_token": self.csrf,
                "version": "1.6.0",
                "filename": "source.json",
                "payload_text": json.dumps({"bookSourceName": "新名字"}, ensure_ascii=False),
                "name": "新名字",
                "names": "旧名字\n更早的名字",
                "domains": "example.com",
                "status_value": "published",
                "channel": "stable",
                "dependencies": "[]",
            },
        )
        self.assertEqual(200, response.status_code, response.text[:300])
        names = self._manifest()["names"]
        for expected in ("新名字", "旧名字", "更早的名字"):
            self.assertIn(expected, names)

    def test_edit_saves_multiple_domains_in_order(self):
        response = self.client.post(
            "/admin/packages/online_source/example-com/edit",
            auth=admin_auth(),
            data={
                "csrf_token": self.csrf,
                "version": "1.3.0",
                "filename": "source.json",
                "payload_text": json.dumps({"bookSourceName": "示例书源"}, ensure_ascii=False),
                "name": "示例书源",
                "domains": "https://Main.example.org/path\nmirror.example.net\nmain.example.org",
                "status_value": "published",
                "channel": "stable",
                "dependencies": "[]",
            },
        )
        self.assertEqual(200, response.status_code, response.text[:300])
        domains = self._manifest()["domains"]
        self.assertEqual("main.example.org", domains[0], "第一行必须是主地址")
        self.assertIn("mirror.example.net", domains)
        self.assertEqual(len(domains), len(set(domains)), "不能有重复地址")

    def test_changing_domains_invalidates_stale_health(self):
        source_check.check_package("online_source", "example-com", timeout=2)
        self.assertIn("healthCheck", self._manifest())
        self.client.post(
            "/admin/packages/online_source/example-com/edit",
            auth=admin_auth(),
            data={
                "csrf_token": self.csrf,
                "version": "1.4.0",
                "filename": "source.json",
                "payload_text": json.dumps({"bookSourceName": "示例书源"}, ensure_ascii=False),
                "domains": "brand-new.example.org",
                "status_value": "published",
                "channel": "stable",
                "dependencies": "[]",
            },
        )
        self.assertNotIn("healthCheck", self._manifest(), "地址变了要作废旧的检测结果")


if __name__ == "__main__":
    unittest.main()
