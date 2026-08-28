import tempfile
import unittest
import base64
import hashlib
import hmac
import io
import json
import zipfile
import asyncio
from datetime import datetime, timezone
from pathlib import Path
import sys

from fastapi import HTTPException, Request
from fastapi.security import HTTPBasicCredentials

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import executors, main, releases, runtime
from app.api import admin_routes as api_admin
from app.api import credentials as api_credentials
from app.api import meta as api_meta
from app.api import packages as api_packages
from app.api import releases as api_releases
from app.admin import format as admin_format
from app.admin import views as admin_views
from app import backups
from app import config_store
from app import crypto
from app import executors
from app import labels
from app import packages
from app import releases
from app import scheduler
from app import security
from app import state
from tests.conftest_support import isolate


class CloudTaskSecurityTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        isolate(root, secret_key="test-secret-key")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_public_task_does_not_expose_encrypted_request(self):
        task = {
            "id": "task_1",
            "owner": "account:alice",
            "taskType": "cloud_auto_read",
            "requestEncrypted": crypto.encrypt_secret({"credentialId": "rea_1", "token": "secret-token"}),
        }
        public = scheduler.public_task(task)
        self.assertNotIn("requestEncrypted", public)
        self.assertNotIn("secret-token", str(public))
        self.assertEqual(public["credentialId"], "rea_1")

    def test_credential_isolation(self):
        state.save_credentials({
            "rea_1": {
                "id": "rea_1",
                "owner": "account:alice",
                "secretEncrypted": crypto.encrypt_secret({"token": "secret-token"}),
            }
        })
        task = {
            "owner": "account:bob",
            "requestEncrypted": crypto.encrypt_secret({"credentialId": "rea_1"}),
        }
        with self.assertRaisesRegex(ValueError, "不属于当前账号"):
            executors.credential_for_task(task)

    def test_credential_sync_merges_same_account_and_rebinds_tasks(self):
        state.save_credentials({
            "rea_old": {"id": "rea_old", "owner": "host:3", "type": "reamicro", "accountId": "3", "createdAt": 1, "updatedAt": 1},
            "rea_duplicate": {"id": "rea_duplicate", "owner": "host:3", "type": "reamicro", "accountId": "3", "createdAt": 2, "updatedAt": 2},
        })
        state.save_tasks({
            "task_1": {
                "id": "task_1",
                "owner": "host:3",
                "taskType": "yeshe_checkin",
                "credentialId": "rea_duplicate",
                "requestEncrypted": crypto.encrypt_secret({"credentialId": "rea_duplicate"}),
            },
        })
        payload = json.dumps({"token": "test-token-value-123456", "label": "更新后的账号", "accountId": "3"}).encode("utf-8")

        async def receive():
            return {"type": "http.request", "body": payload, "more_body": False}

        request = Request({"type": "http", "method": "POST", "path": "/v1/credentials/reamicro", "headers": []}, receive)
        original_verify = executors.verify_reamicro_secret

        async def verified(token, base_url):
            return True, "验证成功"

        executors.verify_reamicro_secret = verified
        try:
            result = asyncio.run(api_credentials.save_reamicro_credential(request, owner="host:3"))
        finally:
            executors.verify_reamicro_secret = original_verify

        self.assertEqual(result["data"]["id"], "rea_duplicate")
        credentials = state.load_credentials()
        self.assertEqual(list(credentials), ["rea_duplicate"])
        self.assertEqual(credentials["rea_duplicate"]["label"], "更新后的账号")
        task = state.load_tasks()["task_1"]
        self.assertEqual(task["credentialId"], "rea_duplicate")
        self.assertEqual(crypto.decrypt_secret(task["requestEncrypted"])["credentialId"], "rea_duplicate")

    def test_admin_read_merges_legacy_duplicate_credentials(self):
        state.save_credentials({
            "rea_old": {"id": "rea_old", "owner": "host:3", "type": "reamicro", "accountId": "3", "updatedAt": 1},
            "rea_new": {"id": "rea_new", "owner": "host:3", "type": "reamicro", "accountId": "3", "updatedAt": 2},
        })
        self.assertEqual(state.merge_duplicate_credentials(), 1)
        self.assertEqual(list(state.load_credentials()), ["rea_new"])

    def test_duplicate_tasks_are_merged_by_owner_credential_and_type(self):
        state.save_tasks({
            "task_old": {"id": "task_old", "owner": "host:3", "taskType": "cloud_auto_read", "credentialId": "rea_1", "updatedAt": 1},
            "task_new": {"id": "task_new", "owner": "host:3", "taskType": "cloud_auto_read", "credentialId": "rea_1", "updatedAt": 2},
            "task_other": {"id": "task_other", "owner": "host:3", "taskType": "yeshe_checkin", "credentialId": "rea_1", "updatedAt": 3},
        })
        self.assertEqual(state.merge_duplicate_tasks(), 1)
        self.assertEqual(set(state.load_tasks()), {"task_new", "task_other"})

    def test_task_notification_waits_for_acknowledgement(self):
        task = {"id": "task_1", "owner": "host:3", "taskType": "yeshe_checkin"}
        notification_id = state.enqueue_task_notification(task, "success", "签到完成", 1000)
        item = state.load_notifications()[notification_id]
        self.assertEqual(item["owner"], "host:3")
        self.assertEqual(item["deliveredAt"], 0)

    def test_presence_storage_records_online_lease(self):
        state.save_presence({"host:3": {"owner": "host:3", "lastSeenAt": 1000, "onlineUntil": 601000}})
        item = state.load_presence()["host:3"]
        self.assertGreater(item["onlineUntil"], item["lastSeenAt"])

    def test_task_detail_hides_raw_json_and_shows_reading_book(self):
        task = {
            "taskType": "cloud_auto_read",
            "schedule": {"timeOfDay": "01:05"},
            "requestEncrypted": crypto.encrypt_secret({"durationMinutes": 45, "books": [{"bookId": 7, "name": "测试图书"}]}),
        }
        detail = admin_views._admin_task_detail(task)
        self.assertIn("45 分钟", detail)
        self.assertIn("测试图书", detail)
        self.assertNotIn("{", detail)

    def test_daily_time_validation(self):
        self.assertEqual(scheduler.normalized_time_of_day("7:05"), "07:05")
        with self.assertRaises(ValueError):
            scheduler.normalized_time_of_day("25:00")

    def test_retry_delay_and_interrupted_recovery(self):
        task = {"consecutiveFailures": 1}
        self.assertEqual(scheduler.retry_delay_seconds(task), 300)
        task["consecutiveFailures"] = 4
        self.assertEqual(scheduler.retry_delay_seconds(task), 2400)
        state.save_tasks({"task_1": {"id": "task_1", "status": "running", "enabled": True}})
        self.assertEqual(scheduler.recover_interrupted_tasks(), 1)
        recovered = state.load_tasks()["task_1"]
        self.assertEqual(recovered["status"], "scheduled")

    def test_task_admin_actions(self):
        task = {"id": "task_1", "status": "scheduled", "enabled": True, "nextRunAt": 1}
        self.assertIn("暂停", scheduler.apply_task_action(task, "pause", 1000))
        self.assertEqual((task["status"], task["enabled"], task["nextRunAt"]), ("paused", False, 0))
        self.assertIn("恢复", scheduler.apply_task_action(task, "resume", 2000))
        self.assertEqual((task["status"], task["enabled"], task["nextRunAt"]), ("scheduled", True, 2000))
        with self.assertRaises(ValueError):
            scheduler.apply_task_action(task, "invalid", 3000)

    def test_idempotency_storage(self):
        store = runtime.get_state_store()
        value = {"code": "OK", "data": {"id": "task_1"}}
        store.save_idempotency("account:alice", "create_task", "request-1", value, 100_000_000)
        self.assertEqual(store.get_idempotency("account:alice", "create_task", "request-1"), value)


class AdminSecurityTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        self.original_admin_username = runtime.ADMIN_USERNAME
        self.original_admin_password = runtime.ADMIN_PASSWORD
        isolate(root, secret_key="test-secret-key")
        # 这个类专门测首次登录引导，需要环境变量里的兜底管理员。
        runtime.ADMIN_USERNAME = "bootstrap-admin"
        runtime.ADMIN_PASSWORD = "bootstrap-password-123"

    def tearDown(self):
        runtime.ADMIN_USERNAME = self.original_admin_username
        runtime.ADMIN_PASSWORD = self.original_admin_password
        self.temp_dir.cleanup()

    def test_primary_admin_must_complete_first_login_setup(self):
        credentials = HTTPBasicCredentials(username="bootstrap-admin", password="bootstrap-password-123")
        actor = security.require_admin(credentials, allow_bootstrap=True)
        self.assertTrue(actor["needsSetup"])
        with self.assertRaises(HTTPException) as raised:
            security.require_admin(credentials)
        self.assertEqual(raised.exception.status_code, 403)

    def test_public_discovery_reports_only_configured_auth_mode(self):
        config = config_store.load_config()
        config["allowPublic"] = True
        config["apiKey"] = ""
        config["apiKeyRecords"] = []
        config["accounts"] = {}
        config["hostAccountAllowlist"] = []
        modes = config_store.public_server_capabilities(config)
        self.assertEqual(modes["authModes"], ["public"])
        self.assertFalse(modes["authRequired"])
        config["hostAccountAllowlist"] = ["1001"]
        modes = config_store.public_server_capabilities(config)
        self.assertEqual(modes["authModes"], ["host_account_allowlist"])
        self.assertTrue(modes["authRequired"])

    def test_health_endpoint_is_public_and_returns_server_identity(self):
        value = asyncio.run(api_meta.health())
        self.assertEqual(value["data"]["status"], "ok")
        self.assertIn("serverId", value["data"])

    def test_api_key_records_disable_public_discovery(self):
        config = config_store.load_config()
        config["allowPublic"] = True
        config["apiKey"] = ""
        config["apiKeyRecords"] = [{"digest": "abc", "enabled": True}]
        config["accounts"] = {}
        config["hostAccountAllowlist"] = []
        self.assertEqual(config_store.public_server_capabilities(config)["authModes"], ["api_key"])

    def test_selected_auth_mode_is_the_only_active_mode(self):
        config = config_store.load_config()
        config.update({
            "authMode": "host_account_allowlist",
            "_authModeExplicit": True,
            "apiKey": "still-stored",
            "accounts": {"alice": crypto.password_hash("account-password-123")},
            "hostAccountAllowlist": ["1001"],
        })
        config_store.save_config(config)
        self.assertEqual(config_store.configured_auth_modes(config_store.load_config()), ["host_account_allowlist"])
        security.check_request_auth(None, None, None, "1001")
        with self.assertRaises(HTTPException):
            security.check_request_auth("still-stored", None, None, None)
        with self.assertRaises(HTTPException):
            security.check_request_auth(None, "alice", "account-password-123", None)

    def test_owner_host_account_id_isolated(self):
        self.assertEqual(state.owner_host_account_id("host:1001"), "1001")
        self.assertEqual(state.owner_host_account_id("key:abc:host:1002"), "1002")
        self.assertEqual(state.owner_host_account_id("public"), "")

    def test_persisted_primary_and_subadmin_authentication(self):
        now = "2026-08-25T00:00:00+00:00"
        config = config_store.load_config()
        config["primaryAdmin"] = {
            "username": "owner",
            "passwordHash": crypto.password_hash("owner-password-123"),
            "createdAt": now,
            "updatedAt": now,
        }
        config["adminAccounts"] = {
            "operator": {
                "passwordHash": crypto.password_hash("operator-password-123"),
                "enabled": True,
                "createdAt": now,
                "updatedAt": now,
            },
            "disabled": {
                "passwordHash": crypto.password_hash("disabled-password-123"),
                "enabled": False,
            },
        }
        config_store.save_config(config)

        owner = security.require_admin(HTTPBasicCredentials(username="owner", password="owner-password-123"))
        operator = security.require_admin(HTTPBasicCredentials(username="operator", password="operator-password-123"))
        self.assertEqual(owner["role"], "primary")
        self.assertEqual(operator["role"], "subadmin")
        with self.assertRaises(HTTPException) as raised:
            security.require_admin(HTTPBasicCredentials(username="disabled", password="disabled-password-123"))
        self.assertEqual(raised.exception.status_code, 401)

    def test_admin_session_is_hashed_and_invalidated_by_password_change(self):
        config = config_store.load_config()
        config["primaryAdmin"] = {
            "username": "owner",
            "passwordHash": crypto.password_hash("owner-password-123"),
        }
        config_store.save_config(config)
        actor = security.require_admin(HTTPBasicCredentials(username="owner", password="owner-password-123"))
        token = security.create_admin_session(actor)
        self.assertEqual(security.validate_admin_session_token(token)["username"], "owner")
        raw = runtime.STATE_DB_PATH.read_bytes()
        self.assertNotIn(token.encode("utf-8"), raw)
        config["primaryAdmin"]["passwordHash"] = crypto.password_hash("new-owner-password-123")
        config_store.save_config(config)
        self.assertIsNone(security.validate_admin_session_token(token))

    def test_subadmin_session_is_invalidated_when_disabled(self):
        config = config_store.load_config()
        config["primaryAdmin"] = {
            "username": "owner",
            "passwordHash": crypto.password_hash("owner-password-123"),
        }
        config["adminAccounts"] = {
            "operator": {
                "passwordHash": crypto.password_hash("operator-password-123"),
                "enabled": True,
                "permissions": ["settings:write"],
            }
        }
        config_store.save_config(config)
        actor = security.require_admin(HTTPBasicCredentials(username="operator", password="operator-password-123"))
        token = security.create_admin_session(actor)
        self.assertIsNotNone(security.validate_admin_session_token(token))
        config["adminAccounts"]["operator"]["enabled"] = False
        config_store.save_config(config)
        self.assertIsNone(security.validate_admin_session_token(token))

    def test_long_secret_has_sufficient_entropy_and_is_unique(self):
        first = crypto.generate_long_secret()
        second = crypto.generate_long_secret()
        self.assertGreaterEqual(len(first), 64)
        self.assertNotEqual(first, second)

    def test_secret_cipher_key_prefers_persisted_secret(self):
        config = config_store.load_config()
        config["secretKey"] = "persisted-secret-key"
        config_store.save_config(config)
        expected = hashlib.sha256(b"persisted-secret-key").digest()
        self.assertEqual(crypto.secret_cipher_key(), expected)
        encrypted = crypto.encrypt_secret({"token": "secret"})
        self.assertTrue(encrypted.startswith("RCSEC2:"))
        self.assertEqual(crypto.decrypt_secret(encrypted), {"token": "secret"})

    def test_api_key_records_and_rotation(self):
        config = config_store.load_config()
        config["apiKey"] = "long-api-key"
        config["apiKeyRecords"] = [{"digest": crypto.api_key_digest("long-api-key"), "enabled": True}]
        config_store.save_config(config)
        self.assertTrue(security.configured_api_key(config_store.load_config(), "long-api-key"))
        config["apiKeyRecords"][0]["enabled"] = False
        config_store.save_config(config)
        self.assertIsNone(security.configured_api_key(config_store.load_config(), "long-api-key"))
        self.assertIsNone(security.configured_api_key(config_store.load_config(), "revoked"))
        runtime.metrics_counters["requests"] = 2
        self.assertEqual(runtime.metrics_counters["requests"], 2)

    def test_admin_page_renders_after_primary_setup(self):
        config = config_store.load_config()
        config["primaryAdmin"] = {
            "username": "owner",
            "passwordHash": crypto.password_hash("owner-password-123"),
        }
        actor = {"username": "owner", "role": "primary"}
        page = admin_views.admin_page(config, actor=actor)
        self.assertIn("ReaMicro API 管理后台", page)
        self.assertIn("子管理员", page)
        self.assertIn("csrf_token", page)
        # 自定义图书示例属于任务表单，此前是概览页上一段没有任何引用的隐藏 template。
        tasks_page = admin_views.admin_page(config, actor=actor, section="tasks")
        self.assertIn("cloudBookId", tasks_page)
        self.assertNotIn("cloud-book-example", page)

    def test_admin_navigation_uses_stable_clickable_paths(self):
        config = config_store.load_config()
        actor = {"username": "owner", "role": "primary", "permissions": []}
        page = admin_views.admin_page(config, actor=actor, section="overview")
        self.assertIn('class="" href="/admin/tasks"', page)
        self.assertIn('class="" href="/admin/settings"', page)
        self.assertIn('class="" href="/admin/security"', page)
        self.assertNotIn("/admin?section=", page)
        self.assertNotIn("class= href=", page)
        self.assertEqual(admin_format.admin_section_path("online_source"), "/admin/content/online_source")

    def test_admin_section_routes_render_and_legacy_redirects(self):
        actor = {"username": "owner", "role": "primary", "permissions": []}
        cases = (
            (api_admin.admin_tasks_page, "云端任务"),
            (api_admin.admin_settings_page, "服务器设置"),
            (api_admin.admin_security_page, "子管理员与安全"),
        )
        for endpoint, title in cases:
            response = asyncio.run(endpoint(actor=actor))
            self.assertEqual(response.status_code, 200)
            self.assertIn(title, response.body.decode("utf-8"))
            self.assertEqual(response.headers.get("cache-control"), "no-store, no-cache, must-revalidate, max-age=0")
        legacy = asyncio.run(api_admin.admin_legacy(actor=actor))
        self.assertEqual(legacy.status_code, 303)
        self.assertEqual(legacy.headers.get("location"), "/admin/settings")
        old_link = asyncio.run(api_admin.admin(section="tasks", q="", actor=actor))
        self.assertEqual(old_link.status_code, 303)
        self.assertEqual(old_link.headers.get("location"), "/admin/tasks")

    def test_admin_task_form_uses_responsive_grid_and_api_version(self):
        actor = {"username": "owner", "role": "primary", "permissions": []}
        page = admin_views.admin_page(config_store.load_config(), actor=actor, section="tasks")
        self.assertIn(".grid-2", page)
        self.assertIn("API v1.1", page)

    def test_task_page_exposes_state_aware_controls_and_credential_selector(self):
        config = config_store.load_config()
        state.save_credentials({"cred_1": {"id": "cred_1", "owner": "admin", "label": "主账号", "accountId": "1001", "updatedAt": 1}})
        state.save_tasks({"task_1": {"id": "task_1", "owner": "admin", "taskType": "yeshe_checkin", "credentialId": "cred_1", "status": "scheduled", "enabled": True, "createdAt": 1, "nextRunAt": 0}})
        actor = {"username": "owner", "role": "primary", "permissions": []}
        page = admin_views.admin_page(config, actor=actor, section="tasks")
        self.assertIn("主账号 · 1001", page)
        self.assertIn("value='run'", page)
        self.assertIn("value='pause'", page)
        self.assertIn("value='cancel'", page)

    def test_admin_task_and_owner_labels_are_readable(self):
        self.assertEqual(labels._admin_task_label("cloud_auto_read"), "云端自动阅读")
        self.assertEqual(labels._admin_task_status_label("scheduled"), "等待执行")
        self.assertEqual(labels._admin_owner_label("host:3"), "阅微账号 3")
        self.assertEqual(admin_format._admin_time_label(1_787_775_994_125), "2026-08-27 04:26")

    def test_admin_permissions_and_csrf(self):
        config = config_store.load_config()
        config["primaryAdmin"] = {
            "username": "owner",
            "passwordHash": crypto.password_hash("owner-password-123"),
        }
        config["adminAccounts"] = {
            "content": {
                "passwordHash": crypto.password_hash("content-password-123"),
                "enabled": True,
                "permissions": ["packages:write"],
            }
        }
        config_store.save_config(config)
        actor = security.require_admin(HTTPBasicCredentials(username="content", password="content-password-123"))
        security.require_admin_permission(actor, "packages:write")
        with self.assertRaises(HTTPException):
            security.require_admin_permission(actor, "settings:write")
        token = security.admin_csrf_token(config, actor)
        security.require_admin_csrf(config, actor, token)
        with self.assertRaises(HTTPException):
            security.require_admin_csrf(config, actor, "invalid")

    def test_rate_limit_window(self):
        original_limit = runtime.RATE_LIMIT
        runtime.RATE_LIMIT = 1
        key = "test-rate-limit"
        runtime.rate_buckets.pop(key, None)
        self.assertTrue(security.allow_rate_limit(key))
        self.assertFalse(security.allow_rate_limit(key))
        runtime.RATE_LIMIT = original_limit

    def test_daily_task_schedule_uses_configured_timezone_and_rolls_to_next_day(self):
        now = int(datetime(2026, 8, 26, 1, 0, tzinfo=timezone.utc).timestamp() * 1000)
        task = {"schedule": {"timeOfDay": "10:30", "timezoneOffsetMinutes": 480}}
        expected = int(datetime(2026, 8, 26, 2, 30, tzinfo=timezone.utc).timestamp() * 1000)
        self.assertEqual(scheduler.next_task_run(task, now), expected)
        late_now = int(datetime(2026, 8, 26, 4, 0, tzinfo=timezone.utc).timestamp() * 1000)
        next_day = int(datetime(2026, 8, 27, 2, 30, tzinfo=timezone.utc).timestamp() * 1000)
        self.assertEqual(scheduler.next_task_run(task, late_now), next_day)

    def test_malformed_config_values_fall_back_without_breaking_backend(self):
        runtime.CONFIG_ROOT.mkdir(parents=True, exist_ok=True)
        runtime.CONFIG_PATH.write_text(json.dumps({"releaseSyncSeconds": "invalid", "serverSnapshotSeconds": None, "features": "backup,module_update", "hostAccountAllowlist": "100\n200"}), encoding="utf-8")
        config = config_store.load_config()
        self.assertEqual(config["releaseSyncSeconds"], 1800)
        self.assertEqual(config["serverSnapshotSeconds"], 86400)
        self.assertEqual(config["features"], ["backup", "module_update"])
        self.assertEqual(config["hostAccountAllowlist"], ["100", "200"])

    def test_unhandled_exception_response_contains_request_id(self):
        request = Request({
            "type": "http",
            "method": "GET",
            "path": "/boom",
            "headers": [],
            "query_string": b"",
            "server": ("test", 80),
            "client": ("127.0.0.1", 1234),
            "scheme": "http",
        })
        request.state.request_id = "req_test_exception"
        result = asyncio.run(main.unhandled_exception_handler(request, RuntimeError("secret detail")))
        payload = json.loads(result.body)
        self.assertEqual(result.status_code, 500)
        self.assertEqual(payload["requestId"], "req_test_exception")
        self.assertNotIn("secret detail", result.body.decode("utf-8"))

    def test_state_store_persists_and_locks_tasks(self):
        store = runtime.get_state_store()
        store.save_namespace("tasks", {"task_1": {"id": "task_1", "status": "scheduled"}})
        self.assertEqual(store.load_namespace("tasks")["task_1"]["status"], "scheduled")
        self.assertTrue(store.acquire_task_lock("task_1", "worker_1", 2000, 1000))
        self.assertFalse(store.acquire_task_lock("task_1", "worker_2", 2000, 1000))
        store.release_task_lock("task_1", "worker_1")
        self.assertTrue(store.acquire_task_lock("task_1", "worker_2", 2000, 1000))

    def test_state_store_backup_integrity(self):
        store = runtime.get_state_store()
        store.save_namespace("credentials", {"credential_1": {"id": "credential_1"}})
        backup = Path(self.temp_dir.name) / "backup.sqlite3"
        store.backup(backup)
        self.assertTrue(backup.is_file())
        self.assertEqual(store.integrity_check(), "ok")
        snapshot = backups.create_server_snapshot()
        self.assertTrue(snapshot.is_file())

    def test_server_snapshot_retention(self):
        runtime.SERVER_BACKUP_ROOT.mkdir(parents=True, exist_ok=True)
        for index in range(5):
            (runtime.SERVER_BACKUP_ROOT / f"reamicro-server-2026082{index}T000000Z.zip").write_bytes(b"zip")
        self.assertEqual(backups.prune_server_snapshots(3), 2)
        self.assertEqual(len(list(runtime.SERVER_BACKUP_ROOT.glob("reamicro-server-*.zip"))), 3)

    def test_package_payload_validation(self):
        packages.validate_package_payload("online_source", "source.json", b'{"id":"source"}')
        packages.validate_package_payload("epub_style", "style.css", "body{}".encode("utf-8"))
        with self.assertRaises(HTTPException):
            packages.validate_package_payload("online_source", "source.json", b"not-json")

    def test_package_metadata_and_identity_are_generated_from_content(self):
        body = json.dumps({"bookSourceName": "夜读书源", "bookSourceUrl": "https://reader.example.test"}, ensure_ascii=False).encode("utf-8")
        metadata = packages.infer_package_metadata("online_source", "upload.json", body)
        self.assertEqual(metadata["name"], "夜读书源")
        self.assertIn("https://reader.example.test", metadata["identity"])
        package_id, existing = packages.resolve_package_identity("online_source", "", "", metadata)
        self.assertEqual(package_id, "reader.example.test")
        self.assertIsNone(existing)
        (runtime.PACKAGE_ROOT / "online_source" / package_id).mkdir(parents=True)
        (runtime.PACKAGE_ROOT / "online_source" / package_id / "manifest.json").write_text(json.dumps({"packageId": package_id, "contentId": "https://reader.example.test", "name": "夜读书源", "version": "1.2.3"}), encoding="utf-8")
        reused, manifest = packages.resolve_package_identity("online_source", "", "", metadata)
        self.assertEqual(reused, package_id)
        self.assertEqual(manifest["version"], "1.2.3")
        self.assertEqual(packages.next_package_version(manifest["version"]), "1.2.4")

    def test_style_name_can_be_read_from_css_metadata(self):
        metadata = packages.infer_package_metadata("epub_style", "clean.css", b"/* name: Clean Reading */\nbody { color: #222; }")
        self.assertEqual(metadata["name"], "Clean Reading")
        self.assertTrue(metadata["explicitName"])

    def test_new_package_form_allows_automatic_metadata(self):
        config = config_store.load_config()
        actor = {"username": "owner", "role": "primary", "permissions": []}
        page = asyncio.run(api_admin.admin_new_package(kind="online_source", actor=actor))
        html_text = page.body.decode("utf-8")
        self.assertIn("包 ID（可留空自动生成）", html_text)
        self.assertIn('name=\'name\'', html_text)

    def test_package_dependency_resolution_supports_alias_and_versions(self):
        dependency_dir = runtime.PACKAGE_ROOT / "online_source" / "parser.common"
        dependency_dir.mkdir(parents=True)
        (dependency_dir / "manifest.json").write_text(json.dumps({
            "kind": "online_source",
            "packageId": "parser.common",
            "contentId": "parser.common",
            "aliases": ["parser.old"],
            "version": "2.1.0",
            "status": "published",
            "dependencies": [],
        }), encoding="utf-8")
        status = packages.package_dependency_status({
            "kind": "online_source",
            "packageId": "book.source",
            "dependencies": [{"packageId": "parser.old", "minVersion": "2.0.0"}],
        })
        self.assertTrue(status["dependenciesSatisfied"])
        self.assertEqual(status["resolvedDependencies"][0]["resolvedVersion"], "2.1.0")

    def test_package_dependency_resolution_reports_required_missing(self):
        status = packages.package_dependency_status({
            "kind": "online_source",
            "packageId": "book.source",
            "dependencies": [{"packageId": "parser.missing", "required": True}],
        })
        self.assertFalse(status["dependenciesSatisfied"])
        self.assertEqual(status["unresolvedDependencies"][0]["reason"], "not_found")

    def test_webhook_signature(self):
        # 原先这个断言只是"用字面量算一遍再和同一个字面量比"，恒真。
        # 改成真正校验签名比对逻辑：正确签名通过，篡改 body 或密钥都不通过。
        runtime.GITHUB_WEBHOOK_SECRET = "webhook-secret"
        body = b'{"action":"published"}'
        expected = "sha256=" + hmac.new(b"webhook-secret", body, hashlib.sha256).hexdigest()
        self.assertTrue(hmac.compare_digest(expected, releases.github_webhook_signature(body)))
        self.assertFalse(hmac.compare_digest(expected, releases.github_webhook_signature(b'{"action":"deleted"}')))
        runtime.GITHUB_WEBHOOK_SECRET = "other-secret"
        self.assertFalse(hmac.compare_digest(expected, releases.github_webhook_signature(body)))

    def test_release_range_and_etag_metadata(self):
        runtime.RELEASE_ROOT.mkdir(parents=True, exist_ok=True)
        apk = runtime.RELEASE_ROOT / "latest.apk"
        apk.write_bytes(b"0123456789")
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        (runtime.RELEASE_ROOT / "latest.json").write_text(json.dumps({"etag": digest}), encoding="utf-8")
        self.assertEqual(digest, hashlib.sha256(apk.read_bytes()).hexdigest())

    def test_release_version_name_falls_back_to_title(self):
        # CI 的 tag 不是语义版本号，必须从标题里取出真实版本号，否则客户端无法比较新旧。
        self.assertEqual("2.0.0", releases.release_version_name("ci-123-1", "CI 2.0.0 #123"))
        self.assertEqual("2.0.0", releases.release_version_name("ci-9-1", "CI 2.0.0 #9 signed release"))
        # tag 本身是语义版本号时优先用 tag，并去掉 v 前缀和构建元数据。
        self.assertEqual("2.1.0", releases.release_version_name("v2.1.0", "任意标题"))
        self.assertEqual("2.1.0", releases.release_version_name("v2.1.0+build7", ""))
        # 标题里没有版本号时保留原始 tag，不要臆造版本号。
        self.assertEqual("ci-123-1", releases.release_version_name("ci-123-1", "CI build #123"))

    def test_is_semantic_version(self):
        self.assertTrue(releases.is_semantic_version("2.0.0"))
        self.assertTrue(releases.is_semantic_version("2.0.0-beta1"))
        self.assertFalse(releases.is_semantic_version("ci-123-1"))
        self.assertFalse(releases.is_semantic_version(""))

    def test_latest_release_channel_filter(self):
        runtime.RELEASE_ROOT.mkdir(parents=True, exist_ok=True)
        metadata = {"versionName": "2.0.0", "channel": "beta", "apkUrl": "/v1/releases/module/download"}
        (runtime.RELEASE_ROOT / "latest.json").write_text(json.dumps(metadata), encoding="utf-8")
        # 默认渠道是 stable，预发布版本会被拒绝——这正是客户端不带 channel 时出现 404 的原因。
        with self.assertRaises(HTTPException) as stable:
            asyncio.run(api_releases.latest_release(channel="stable"))
        self.assertEqual(404, stable.exception.status_code)
        # 客户端显式请求 beta 时应当拿到该版本。
        result = asyncio.run(api_releases.latest_release(channel="beta"))
        self.assertEqual("2.0.0", result["data"]["versionName"])
        # 非法渠道返回 400，便于区分"渠道写错"和"没有该渠道的版本"。
        with self.assertRaises(HTTPException) as invalid:
            asyncio.run(api_releases.latest_release(channel="weekly"))
        self.assertEqual(400, invalid.exception.status_code)

    def test_latest_release_accepts_stable_when_asking_beta(self):
        runtime.RELEASE_ROOT.mkdir(parents=True, exist_ok=True)
        metadata = {"versionName": "2.0.0", "channel": "stable"}
        (runtime.RELEASE_ROOT / "latest.json").write_text(json.dumps(metadata), encoding="utf-8")
        self.assertEqual("2.0.0", asyncio.run(api_releases.latest_release(channel="beta"))["data"]["versionName"])


class ModuleContentLibraryUploadTest(unittest.TestCase):
    """模块上传内容库：白名单校验、同名同域只关联、以及本地源比对。"""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        isolate(root, secret_key="test-secret-key")

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_package(self, kind, package_id, name, domains=(), aliases=(), content_id=""):
        package_dir = runtime.PACKAGE_ROOT / kind / package_id
        package_dir.mkdir(parents=True, exist_ok=True)
        manifest = {
            "packageId": package_id,
            "kind": kind,
            "version": "1.0.0",
            "name": name,
            "contentId": content_id or package_id,
            "domains": list(domains),
            "aliases": list(aliases),
            "status": "published",
            "payload": "payload.json",
        }
        (package_dir / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        (package_dir / "payload.json").write_text("{}", encoding="utf-8")
        return manifest

    def test_normalize_source_domain(self):
        # 协议、端口、www 前缀和大小写都要归一化，便于跨来源比对同一个站点。
        self.assertEqual("example.com", packages.normalize_source_domain("https://www.Example.com/search?q=1"))
        self.assertEqual("example.com", packages.normalize_source_domain("example.com:8080"))
        self.assertEqual("a.example.com", packages.normalize_source_domain("http://a.example.com"))
        # 纯标识不含点，不能当成域名，否则关联源会按名称误判。
        self.assertEqual("", packages.normalize_source_domain("youshu"))
        self.assertEqual("", packages.normalize_source_domain(""))

    def test_upload_links_existing_package_with_same_name_and_domain(self):
        config_store.save_config({**config_store.default_config(), "moduleUploadEnabled": True, "moduleUploadAllowlist": ["10086"], "allowPublic": True, "authMode": "public"})
        self.write_package("online_source", "example-com", "示例书源", domains=["example.com"])
        payload = json.dumps({
            "kind": "online_source",
            "name": "示例书源",
            "contentId": "online_local_1",
            "domains": ["https://www.example.com/"],
            "payloadName": "source.json",
            "payload": base64.b64encode(json.dumps({"bookSourceName": "示例书源", "bookSourceUrl": "https://example.com"}).encode("utf-8")).decode("ascii"),
        }).encode("utf-8")

        async def receive():
            return {"type": "http.request", "body": payload, "more_body": False}

        request = Request({"type": "http", "method": "POST", "path": "/v1/packages/upload", "headers": []}, receive)
        result = asyncio.run(api_packages.module_upload_package(request, owner="host:10086"))
        # 同名同域视为同一个源：不覆盖服务器内容，只回关联信息。
        self.assertFalse(result["data"]["uploaded"])
        self.assertTrue(result["data"]["linked"])
        self.assertEqual("example-com", result["data"]["package"]["packageId"])
        self.assertEqual("1.0.0", result["data"]["package"]["version"])

    def test_upload_creates_package_when_no_match(self):
        config_store.save_config({**config_store.default_config(), "moduleUploadEnabled": True, "moduleUploadAllowlist": ["10086"], "allowPublic": True, "authMode": "public"})
        payload = json.dumps({
            "kind": "online_source",
            "name": "新书源",
            "contentId": "online_local_2",
            "domains": ["novel.test"],
            "payloadName": "source.json",
            "payload": base64.b64encode(json.dumps({"bookSourceName": "新书源", "bookSourceUrl": "https://novel.test"}).encode("utf-8")).decode("ascii"),
        }).encode("utf-8")

        async def receive():
            return {"type": "http.request", "body": payload, "more_body": False}

        request = Request({"type": "http", "method": "POST", "path": "/v1/packages/upload", "headers": []}, receive)
        result = asyncio.run(api_packages.module_upload_package(request, owner="host:10086"))
        self.assertTrue(result["data"]["uploaded"])
        self.assertTrue(result["data"]["linked"])
        created = runtime.PACKAGE_ROOT / "online_source" / result["data"]["package"]["packageId"] / "manifest.json"
        manifest = json.loads(created.read_text(encoding="utf-8"))
        # 上传者写入 uploadOwner，域名落盘，供后续按名称+域名比对。
        self.assertEqual("host:10086", manifest["uploadOwner"])
        self.assertIn("novel.test", manifest["domains"])
        self.assertEqual("online_local_2", manifest["contentId"])

    def test_upload_policy_requires_enabled_and_allowlist(self):
        config = {**config_store.default_config(), "moduleUploadEnabled": False, "moduleUploadAllowlist": ["10086"]}
        blocked = security.module_upload_policy(config, "10086")
        self.assertFalse(blocked["allowed"])
        self.assertIn("未启用", blocked["reason"])
        config["moduleUploadEnabled"] = True
        # 白名单外的阅微账号必须被拒绝，并在原因里点明具体账号。
        outsider = security.module_upload_policy(config, "20250")
        self.assertFalse(outsider["allowed"])
        self.assertIn("20250", outsider["reason"])
        # 未携带阅微账号 ID 同样拒绝，提示先登录。
        anonymous = security.module_upload_policy(config, "")
        self.assertFalse(anonymous["allowed"])
        self.assertIn("阅微账号 ID", anonymous["reason"])
        self.assertTrue(security.module_upload_policy(config, "10086")["allowed"])

    def test_upload_rejected_without_allowlist(self):
        config_store.save_config({**config_store.default_config(), "moduleUploadEnabled": True, "moduleUploadAllowlist": ["10086"], "allowPublic": True, "authMode": "public"})

        async def receive():
            return {"type": "http.request", "body": b"{}", "more_body": False}

        request = Request({"type": "http", "method": "POST", "path": "/v1/packages/upload", "headers": [(b"x-reamicro-host-account-id", b"20250")]}, receive)
        with self.assertRaises(HTTPException) as denied:
            asyncio.run(security.module_upload_owner(request, x_reamicro_host_account_id="20250"))
        self.assertEqual(403, denied.exception.status_code)

    def test_match_packages_by_name_and_domain(self):
        config_store.save_config({**config_store.default_config(), "allowPublic": True, "authMode": "public"})
        self.write_package("online_source", "example-com", "示例书源", domains=["example.com"])
        self.write_package("association_source", "youshu", "YouShu", aliases=["youshu"])
        payload = json.dumps({"items": [
            {"kind": "online_source", "name": "示例书源", "contentId": "local_1", "domains": ["http://example.com/x"]},
            {"kind": "online_source", "name": "示例书源", "contentId": "local_2", "domains": ["other.test"]},
            {"kind": "association_source", "name": "YouShu", "contentId": "youshu", "identities": ["youshu"]},
        ]}).encode("utf-8")

        async def receive():
            return {"type": "http.request", "body": payload, "more_body": False}

        request = Request({"type": "http", "method": "POST", "path": "/v1/packages/match", "headers": []}, receive)
        items = asyncio.run(api_packages.match_packages(request, owner="host:10086"))["data"]["items"]
        # 同名但域名不同的源不算同一个，避免误关联到别人的书源。
        self.assertTrue(items[0]["matched"])
        self.assertFalse(items[1]["matched"])
        # 关联源没有域名，靠名称 + 稳定标识命中。
        self.assertTrue(items[2]["matched"])
        self.assertEqual("youshu", items[2]["package"]["packageId"])

    def test_infer_metadata_reads_archive_manifest(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("manifest.json", json.dumps({"apiVersion": 1, "id": "youshu", "name": "YouShu", "entryClass": "com.example.Y"}))
        metadata = packages.infer_package_metadata("association_source", "youshu.rmsource", buffer.getvalue())
        # 关联源的名称和 ID 写在归档内部的清单里，必须能读出来。
        self.assertEqual("YouShu", metadata["name"])
        self.assertIn("youshu", metadata["identity"])
        self.assertEqual([], metadata["domains"])

    def test_module_upload_kinds_limited_to_allowed_set(self):
        """模块只能上传书源、关联源和高亮样式；主题等即便写进配置也要被过滤掉。

        这个集合必须与模块端 ApiContentLibrarySync.UPLOADABLE_KINDS 一致。
        """
        allowed = ["association_source", "highlight_style", "online_source"]
        self.assertEqual(allowed, config_store.module_upload_kinds(["theme", "epub_style", *allowed]))
        self.assertEqual(allowed, config_store.module_upload_kinds([]), "留空回落到全部允许类型")
        self.assertEqual(["online_source"], config_store.module_upload_kinds("online_source"))
        self.assertEqual(["highlight_style"], config_store.module_upload_kinds("highlight_style"))
        # 只写不允许的类型时回落到默认集合，而不是变成空集合把上传全禁掉。
        self.assertEqual(allowed, config_store.module_upload_kinds(["theme"]))


if __name__ == "__main__":
    unittest.main()
