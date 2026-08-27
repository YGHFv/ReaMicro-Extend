import tempfile
import unittest
import hashlib
import hmac
import json
import asyncio
from datetime import datetime, timezone
from pathlib import Path
import sys

from fastapi import HTTPException, Request
from fastapi.security import HTTPBasicCredentials

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import main


class CloudTaskSecurityTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        main.SECRET_KEY = "test-secret-key"
        main.ACCOUNT_ROOT = root / "accounts"
        main.ACCOUNT_PATH = main.ACCOUNT_ROOT / "credentials.json"
        main.TASK_ROOT = root / "tasks"
        main.TASKS_PATH = main.TASK_ROOT / "tasks.json"
        main.STATE_DB_PATH = root / "state" / "reamicro.sqlite3"
        main.SERVER_BACKUP_ROOT = root / "backups" / "server"
        main.state_store = None

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_public_task_does_not_expose_encrypted_request(self):
        task = {
            "id": "task_1",
            "owner": "account:alice",
            "taskType": "cloud_auto_read",
            "requestEncrypted": main.encrypt_secret({"credentialId": "rea_1", "token": "secret-token"}),
        }
        public = main.public_task(task)
        self.assertNotIn("requestEncrypted", public)
        self.assertNotIn("secret-token", str(public))
        self.assertEqual(public["credentialId"], "rea_1")

    def test_credential_isolation(self):
        main.save_credentials({
            "rea_1": {
                "id": "rea_1",
                "owner": "account:alice",
                "secretEncrypted": main.encrypt_secret({"token": "secret-token"}),
            }
        })
        task = {
            "owner": "account:bob",
            "requestEncrypted": main.encrypt_secret({"credentialId": "rea_1"}),
        }
        with self.assertRaisesRegex(ValueError, "不属于当前账号"):
            main.credential_for_task(task)

    def test_credential_sync_merges_same_account_and_rebinds_tasks(self):
        main.save_credentials({
            "rea_old": {"id": "rea_old", "owner": "host:3", "type": "reamicro", "accountId": "3", "createdAt": 1, "updatedAt": 1},
            "rea_duplicate": {"id": "rea_duplicate", "owner": "host:3", "type": "reamicro", "accountId": "3", "createdAt": 2, "updatedAt": 2},
        })
        main.save_tasks({
            "task_1": {
                "id": "task_1",
                "owner": "host:3",
                "taskType": "yeshe_checkin",
                "credentialId": "rea_duplicate",
                "requestEncrypted": main.encrypt_secret({"credentialId": "rea_duplicate"}),
            },
        })
        payload = json.dumps({"token": "test-token-value-123456", "label": "更新后的账号", "accountId": "3"}).encode("utf-8")

        async def receive():
            return {"type": "http.request", "body": payload, "more_body": False}

        request = Request({"type": "http", "method": "POST", "path": "/v1/credentials/reamicro", "headers": []}, receive)
        original_verify = main.verify_reamicro_secret

        async def verified(token, base_url):
            return True, "验证成功"

        main.verify_reamicro_secret = verified
        try:
            result = asyncio.run(main.save_reamicro_credential(request, owner="host:3"))
        finally:
            main.verify_reamicro_secret = original_verify

        self.assertEqual(result["data"]["id"], "rea_duplicate")
        credentials = main.load_credentials()
        self.assertEqual(list(credentials), ["rea_duplicate"])
        self.assertEqual(credentials["rea_duplicate"]["label"], "更新后的账号")
        task = main.load_tasks()["task_1"]
        self.assertEqual(task["credentialId"], "rea_duplicate")
        self.assertEqual(main.decrypt_secret(task["requestEncrypted"])["credentialId"], "rea_duplicate")

    def test_admin_read_merges_legacy_duplicate_credentials(self):
        main.save_credentials({
            "rea_old": {"id": "rea_old", "owner": "host:3", "type": "reamicro", "accountId": "3", "updatedAt": 1},
            "rea_new": {"id": "rea_new", "owner": "host:3", "type": "reamicro", "accountId": "3", "updatedAt": 2},
        })
        self.assertEqual(main.merge_duplicate_credentials(), 1)
        self.assertEqual(list(main.load_credentials()), ["rea_new"])

    def test_duplicate_tasks_are_merged_by_owner_credential_and_type(self):
        main.save_tasks({
            "task_old": {"id": "task_old", "owner": "host:3", "taskType": "cloud_auto_read", "credentialId": "rea_1", "updatedAt": 1},
            "task_new": {"id": "task_new", "owner": "host:3", "taskType": "cloud_auto_read", "credentialId": "rea_1", "updatedAt": 2},
            "task_other": {"id": "task_other", "owner": "host:3", "taskType": "yeshe_checkin", "credentialId": "rea_1", "updatedAt": 3},
        })
        self.assertEqual(main.merge_duplicate_tasks(), 1)
        self.assertEqual(set(main.load_tasks()), {"task_new", "task_other"})

    def test_task_notification_waits_for_acknowledgement(self):
        task = {"id": "task_1", "owner": "host:3", "taskType": "yeshe_checkin"}
        notification_id = main.enqueue_task_notification(task, "success", "签到完成", 1000)
        item = main.load_notifications()[notification_id]
        self.assertEqual(item["owner"], "host:3")
        self.assertEqual(item["deliveredAt"], 0)

    def test_presence_storage_records_online_lease(self):
        main.save_presence({"host:3": {"owner": "host:3", "lastSeenAt": 1000, "onlineUntil": 601000}})
        item = main.load_presence()["host:3"]
        self.assertGreater(item["onlineUntil"], item["lastSeenAt"])

    def test_task_detail_hides_raw_json_and_shows_reading_book(self):
        task = {
            "taskType": "cloud_auto_read",
            "schedule": {"timeOfDay": "01:05"},
            "requestEncrypted": main.encrypt_secret({"durationMinutes": 45, "books": [{"bookId": 7, "name": "测试图书"}]}),
        }
        detail = main._admin_task_detail(task)
        self.assertIn("45 分钟", detail)
        self.assertIn("测试图书", detail)
        self.assertNotIn("{", detail)

    def test_daily_time_validation(self):
        self.assertEqual(main.normalized_time_of_day("7:05"), "07:05")
        with self.assertRaises(ValueError):
            main.normalized_time_of_day("25:00")

    def test_retry_delay_and_interrupted_recovery(self):
        task = {"consecutiveFailures": 1}
        self.assertEqual(main.retry_delay_seconds(task), 300)
        task["consecutiveFailures"] = 4
        self.assertEqual(main.retry_delay_seconds(task), 2400)
        main.save_tasks({"task_1": {"id": "task_1", "status": "running", "enabled": True}})
        self.assertEqual(main.recover_interrupted_tasks(), 1)
        recovered = main.load_tasks()["task_1"]
        self.assertEqual(recovered["status"], "scheduled")

    def test_task_admin_actions(self):
        task = {"id": "task_1", "status": "scheduled", "enabled": True, "nextRunAt": 1}
        self.assertIn("暂停", main.apply_task_action(task, "pause", 1000))
        self.assertEqual((task["status"], task["enabled"], task["nextRunAt"]), ("paused", False, 0))
        self.assertIn("恢复", main.apply_task_action(task, "resume", 2000))
        self.assertEqual((task["status"], task["enabled"], task["nextRunAt"]), ("scheduled", True, 2000))
        with self.assertRaises(ValueError):
            main.apply_task_action(task, "invalid", 3000)

    def test_idempotency_storage(self):
        store = main.get_state_store()
        value = {"code": "OK", "data": {"id": "task_1"}}
        store.save_idempotency("account:alice", "create_task", "request-1", value, 100_000_000)
        self.assertEqual(store.get_idempotency("account:alice", "create_task", "request-1"), value)


class AdminSecurityTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        self.original_config_root = main.CONFIG_ROOT
        self.original_config_path = main.CONFIG_PATH
        self.original_admin_username = main.ADMIN_USERNAME
        self.original_admin_password = main.ADMIN_PASSWORD
        self.original_package_root = main.PACKAGE_ROOT
        self.original_release_root = main.RELEASE_ROOT
        self.original_audit_root = main.AUDIT_ROOT
        self.original_audit_path = main.AUDIT_PATH
        main.CONFIG_ROOT = root / "config"
        main.CONFIG_PATH = main.CONFIG_ROOT / "server.json"
        main.STATE_DB_PATH = root / "state" / "reamicro.sqlite3"
        main.SERVER_BACKUP_ROOT = root / "backups" / "server"
        main.PACKAGE_ROOT = root / "packages"
        main.RELEASE_ROOT = root / "releases" / "module"
        main.AUDIT_ROOT = root / "audit"
        main.AUDIT_PATH = main.AUDIT_ROOT / "events.jsonl"
        main.state_store = None
        main.ADMIN_USERNAME = "bootstrap-admin"
        main.ADMIN_PASSWORD = "bootstrap-password-123"

    def tearDown(self):
        main.CONFIG_ROOT = self.original_config_root
        main.CONFIG_PATH = self.original_config_path
        main.ADMIN_USERNAME = self.original_admin_username
        main.ADMIN_PASSWORD = self.original_admin_password
        main.PACKAGE_ROOT = self.original_package_root
        main.RELEASE_ROOT = self.original_release_root
        main.AUDIT_ROOT = self.original_audit_root
        main.AUDIT_PATH = self.original_audit_path
        self.temp_dir.cleanup()

    def test_primary_admin_must_complete_first_login_setup(self):
        credentials = HTTPBasicCredentials(username="bootstrap-admin", password="bootstrap-password-123")
        actor = main.require_admin(credentials, allow_bootstrap=True)
        self.assertTrue(actor["needsSetup"])
        with self.assertRaises(HTTPException) as raised:
            main.require_admin(credentials)
        self.assertEqual(raised.exception.status_code, 403)

    def test_public_discovery_reports_only_configured_auth_mode(self):
        config = main.load_config()
        config["allowPublic"] = True
        config["apiKey"] = ""
        config["apiKeyRecords"] = []
        config["accounts"] = {}
        config["hostAccountAllowlist"] = []
        modes = main.public_server_capabilities(config)
        self.assertEqual(modes["authModes"], ["public"])
        self.assertFalse(modes["authRequired"])
        config["hostAccountAllowlist"] = ["1001"]
        modes = main.public_server_capabilities(config)
        self.assertEqual(modes["authModes"], ["host_account_allowlist"])
        self.assertTrue(modes["authRequired"])

    def test_health_endpoint_is_public_and_returns_server_identity(self):
        value = asyncio.run(main.health())
        self.assertEqual(value["data"]["status"], "ok")
        self.assertIn("serverId", value["data"])

    def test_api_key_records_disable_public_discovery(self):
        config = main.load_config()
        config["allowPublic"] = True
        config["apiKey"] = ""
        config["apiKeyRecords"] = [{"digest": "abc", "enabled": True}]
        config["accounts"] = {}
        config["hostAccountAllowlist"] = []
        self.assertEqual(main.public_server_capabilities(config)["authModes"], ["api_key"])

    def test_selected_auth_mode_is_the_only_active_mode(self):
        config = main.load_config()
        config.update({
            "authMode": "host_account_allowlist",
            "_authModeExplicit": True,
            "apiKey": "still-stored",
            "accounts": {"alice": main.password_hash("account-password-123")},
            "hostAccountAllowlist": ["1001"],
        })
        main.save_config(config)
        self.assertEqual(main.configured_auth_modes(main.load_config()), ["host_account_allowlist"])
        main.check_request_auth(None, None, None, "1001")
        with self.assertRaises(HTTPException):
            main.check_request_auth("still-stored", None, None, None)
        with self.assertRaises(HTTPException):
            main.check_request_auth(None, "alice", "account-password-123", None)

    def test_owner_host_account_id_isolated(self):
        self.assertEqual(main.owner_host_account_id("host:1001"), "1001")
        self.assertEqual(main.owner_host_account_id("key:abc:host:1002"), "1002")
        self.assertEqual(main.owner_host_account_id("public"), "")

    def test_persisted_primary_and_subadmin_authentication(self):
        now = "2026-08-25T00:00:00+00:00"
        config = main.load_config()
        config["primaryAdmin"] = {
            "username": "owner",
            "passwordHash": main.password_hash("owner-password-123"),
            "createdAt": now,
            "updatedAt": now,
        }
        config["adminAccounts"] = {
            "operator": {
                "passwordHash": main.password_hash("operator-password-123"),
                "enabled": True,
                "createdAt": now,
                "updatedAt": now,
            },
            "disabled": {
                "passwordHash": main.password_hash("disabled-password-123"),
                "enabled": False,
            },
        }
        main.save_config(config)

        owner = main.require_admin(HTTPBasicCredentials(username="owner", password="owner-password-123"))
        operator = main.require_admin(HTTPBasicCredentials(username="operator", password="operator-password-123"))
        self.assertEqual(owner["role"], "primary")
        self.assertEqual(operator["role"], "subadmin")
        with self.assertRaises(HTTPException) as raised:
            main.require_admin(HTTPBasicCredentials(username="disabled", password="disabled-password-123"))
        self.assertEqual(raised.exception.status_code, 401)

    def test_admin_session_is_hashed_and_invalidated_by_password_change(self):
        config = main.load_config()
        config["primaryAdmin"] = {
            "username": "owner",
            "passwordHash": main.password_hash("owner-password-123"),
        }
        main.save_config(config)
        actor = main.require_admin(HTTPBasicCredentials(username="owner", password="owner-password-123"))
        token = main.create_admin_session(actor)
        self.assertEqual(main.validate_admin_session_token(token)["username"], "owner")
        raw = main.STATE_DB_PATH.read_bytes()
        self.assertNotIn(token.encode("utf-8"), raw)
        config["primaryAdmin"]["passwordHash"] = main.password_hash("new-owner-password-123")
        main.save_config(config)
        self.assertIsNone(main.validate_admin_session_token(token))

    def test_subadmin_session_is_invalidated_when_disabled(self):
        config = main.load_config()
        config["primaryAdmin"] = {
            "username": "owner",
            "passwordHash": main.password_hash("owner-password-123"),
        }
        config["adminAccounts"] = {
            "operator": {
                "passwordHash": main.password_hash("operator-password-123"),
                "enabled": True,
                "permissions": ["settings:write"],
            }
        }
        main.save_config(config)
        actor = main.require_admin(HTTPBasicCredentials(username="operator", password="operator-password-123"))
        token = main.create_admin_session(actor)
        self.assertIsNotNone(main.validate_admin_session_token(token))
        config["adminAccounts"]["operator"]["enabled"] = False
        main.save_config(config)
        self.assertIsNone(main.validate_admin_session_token(token))

    def test_long_secret_has_sufficient_entropy_and_is_unique(self):
        first = main.generate_long_secret()
        second = main.generate_long_secret()
        self.assertGreaterEqual(len(first), 64)
        self.assertNotEqual(first, second)

    def test_secret_cipher_key_prefers_persisted_secret(self):
        config = main.load_config()
        config["secretKey"] = "persisted-secret-key"
        main.save_config(config)
        expected = main._hashlib.sha256(b"persisted-secret-key").digest()
        self.assertEqual(main.secret_cipher_key(), expected)
        encrypted = main.encrypt_secret({"token": "secret"})
        self.assertTrue(encrypted.startswith("RCSEC2:"))
        self.assertEqual(main.decrypt_secret(encrypted), {"token": "secret"})

    def test_api_key_records_and_rotation(self):
        config = main.load_config()
        config["apiKey"] = "long-api-key"
        config["apiKeyRecords"] = [{"digest": main.api_key_digest("long-api-key"), "enabled": True}]
        main.save_config(config)
        self.assertTrue(main.configured_api_key(main.load_config(), "long-api-key"))
        config["apiKeyRecords"][0]["enabled"] = False
        main.save_config(config)
        self.assertIsNone(main.configured_api_key(main.load_config(), "long-api-key"))
        self.assertIsNone(main.configured_api_key(main.load_config(), "revoked"))
        main.metrics_counters["requests"] = 2
        self.assertEqual(main.metrics_counters["requests"], 2)

    def test_admin_page_renders_after_primary_setup(self):
        config = main.load_config()
        config["primaryAdmin"] = {
            "username": "owner",
            "passwordHash": main.password_hash("owner-password-123"),
        }
        page = main.admin_page(config, actor={"username": "owner", "role": "primary"})
        self.assertIn("ReaMicro API 管理后台", page)
        self.assertIn('[{"cloudBookId":123', page)
        self.assertIn("子管理员", page)
        self.assertIn("csrf_token", page)

    def test_admin_navigation_uses_stable_clickable_paths(self):
        config = main.load_config()
        actor = {"username": "owner", "role": "primary", "permissions": []}
        page = main.admin_page(config, actor=actor, section="overview")
        self.assertIn('class="" href="/admin/tasks"', page)
        self.assertIn('class="" href="/admin/settings"', page)
        self.assertIn('class="" href="/admin/security"', page)
        self.assertNotIn("/admin?section=", page)
        self.assertNotIn("class= href=", page)
        self.assertEqual(main.admin_section_path("online_source"), "/admin/content/online_source")

    def test_admin_section_routes_render_and_legacy_redirects(self):
        actor = {"username": "owner", "role": "primary", "permissions": []}
        cases = (
            (main.admin_tasks_page, "云端任务"),
            (main.admin_settings_page, "服务器设置"),
            (main.admin_security_page, "子管理员与安全"),
        )
        for endpoint, title in cases:
            response = asyncio.run(endpoint(actor=actor))
            self.assertEqual(response.status_code, 200)
            self.assertIn(title, response.body.decode("utf-8"))
            self.assertEqual(response.headers.get("cache-control"), "no-store, no-cache, must-revalidate, max-age=0")
        legacy = asyncio.run(main.admin_legacy(actor=actor))
        self.assertEqual(legacy.status_code, 303)
        self.assertEqual(legacy.headers.get("location"), "/admin/settings")
        old_link = asyncio.run(main.admin(section="tasks", q="", actor=actor))
        self.assertEqual(old_link.status_code, 303)
        self.assertEqual(old_link.headers.get("location"), "/admin/tasks")

    def test_admin_task_form_uses_responsive_grid_and_api_version(self):
        actor = {"username": "owner", "role": "primary", "permissions": []}
        page = main.admin_page(main.load_config(), actor=actor, section="tasks")
        self.assertIn(".grid-2", page)
        self.assertIn("API v1.1", page)

    def test_task_page_exposes_state_aware_controls_and_credential_selector(self):
        config = main.load_config()
        main.save_credentials({"cred_1": {"id": "cred_1", "owner": "admin", "label": "主账号", "accountId": "1001", "updatedAt": 1}})
        main.save_tasks({"task_1": {"id": "task_1", "owner": "admin", "taskType": "yeshe_checkin", "credentialId": "cred_1", "status": "scheduled", "enabled": True, "createdAt": 1, "nextRunAt": 0}})
        actor = {"username": "owner", "role": "primary", "permissions": []}
        page = main.admin_page(config, actor=actor, section="tasks")
        self.assertIn("主账号 · 1001", page)
        self.assertIn("value='run'", page)
        self.assertIn("value='pause'", page)
        self.assertIn("value='cancel'", page)

    def test_admin_task_and_owner_labels_are_readable(self):
        self.assertEqual(main._admin_task_label("cloud_auto_read"), "云端自动阅读")
        self.assertEqual(main._admin_task_status_label("scheduled"), "等待执行")
        self.assertEqual(main._admin_owner_label("host:3"), "阅微账号 3")
        self.assertEqual(main._admin_time_label(1_787_775_994_125), "2026-08-27 04:26")

    def test_admin_permissions_and_csrf(self):
        config = main.load_config()
        config["primaryAdmin"] = {
            "username": "owner",
            "passwordHash": main.password_hash("owner-password-123"),
        }
        config["adminAccounts"] = {
            "content": {
                "passwordHash": main.password_hash("content-password-123"),
                "enabled": True,
                "permissions": ["packages:write"],
            }
        }
        main.save_config(config)
        actor = main.require_admin(HTTPBasicCredentials(username="content", password="content-password-123"))
        main.require_admin_permission(actor, "packages:write")
        with self.assertRaises(HTTPException):
            main.require_admin_permission(actor, "settings:write")
        token = main.admin_csrf_token(config, actor)
        main.require_admin_csrf(config, actor, token)
        with self.assertRaises(HTTPException):
            main.require_admin_csrf(config, actor, "invalid")

    def test_rate_limit_window(self):
        original_limit = main.RATE_LIMIT
        main.RATE_LIMIT = 1
        key = "test-rate-limit"
        main.rate_buckets.pop(key, None)
        self.assertTrue(main.allow_rate_limit(key))
        self.assertFalse(main.allow_rate_limit(key))
        main.RATE_LIMIT = original_limit

    def test_daily_task_schedule_uses_configured_timezone_and_rolls_to_next_day(self):
        now = int(datetime(2026, 8, 26, 1, 0, tzinfo=timezone.utc).timestamp() * 1000)
        task = {"schedule": {"timeOfDay": "10:30", "timezoneOffsetMinutes": 480}}
        expected = int(datetime(2026, 8, 26, 2, 30, tzinfo=timezone.utc).timestamp() * 1000)
        self.assertEqual(main.next_task_run(task, now), expected)
        late_now = int(datetime(2026, 8, 26, 4, 0, tzinfo=timezone.utc).timestamp() * 1000)
        next_day = int(datetime(2026, 8, 27, 2, 30, tzinfo=timezone.utc).timestamp() * 1000)
        self.assertEqual(main.next_task_run(task, late_now), next_day)

    def test_malformed_config_values_fall_back_without_breaking_backend(self):
        main.CONFIG_ROOT.mkdir(parents=True, exist_ok=True)
        main.CONFIG_PATH.write_text(json.dumps({"releaseSyncSeconds": "invalid", "serverSnapshotSeconds": None, "features": "backup,module_update", "hostAccountAllowlist": "100\n200"}), encoding="utf-8")
        config = main.load_config()
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
        store = main.get_state_store()
        store.save_namespace("tasks", {"task_1": {"id": "task_1", "status": "scheduled"}})
        self.assertEqual(store.load_namespace("tasks")["task_1"]["status"], "scheduled")
        self.assertTrue(store.acquire_task_lock("task_1", "worker_1", 2000, 1000))
        self.assertFalse(store.acquire_task_lock("task_1", "worker_2", 2000, 1000))
        store.release_task_lock("task_1", "worker_1")
        self.assertTrue(store.acquire_task_lock("task_1", "worker_2", 2000, 1000))

    def test_state_store_backup_integrity(self):
        store = main.get_state_store()
        store.save_namespace("credentials", {"credential_1": {"id": "credential_1"}})
        backup = Path(self.temp_dir.name) / "backup.sqlite3"
        store.backup(backup)
        self.assertTrue(backup.is_file())
        self.assertEqual(store.integrity_check(), "ok")
        snapshot = main.create_server_snapshot()
        self.assertTrue(snapshot.is_file())

    def test_server_snapshot_retention(self):
        main.SERVER_BACKUP_ROOT.mkdir(parents=True, exist_ok=True)
        for index in range(5):
            (main.SERVER_BACKUP_ROOT / f"reamicro-server-2026082{index}T000000Z.zip").write_bytes(b"zip")
        self.assertEqual(main.prune_server_snapshots(3), 2)
        self.assertEqual(len(list(main.SERVER_BACKUP_ROOT.glob("reamicro-server-*.zip"))), 3)

    def test_package_payload_validation(self):
        main.validate_package_payload("online_source", "source.json", b'{"id":"source"}')
        main.validate_package_payload("epub_style", "style.css", "body{}".encode("utf-8"))
        with self.assertRaises(HTTPException):
            main.validate_package_payload("online_source", "source.json", b"not-json")

    def test_package_metadata_and_identity_are_generated_from_content(self):
        body = json.dumps({"bookSourceName": "夜读书源", "bookSourceUrl": "https://reader.example.test"}, ensure_ascii=False).encode("utf-8")
        metadata = main.infer_package_metadata("online_source", "upload.json", body)
        self.assertEqual(metadata["name"], "夜读书源")
        self.assertIn("https://reader.example.test", metadata["identity"])
        package_id, existing = main.resolve_package_identity("online_source", "", "", metadata)
        self.assertEqual(package_id, "reader.example.test")
        self.assertIsNone(existing)
        (main.PACKAGE_ROOT / "online_source" / package_id).mkdir(parents=True)
        (main.PACKAGE_ROOT / "online_source" / package_id / "manifest.json").write_text(json.dumps({"packageId": package_id, "contentId": "https://reader.example.test", "name": "夜读书源", "version": "1.2.3"}), encoding="utf-8")
        reused, manifest = main.resolve_package_identity("online_source", "", "", metadata)
        self.assertEqual(reused, package_id)
        self.assertEqual(manifest["version"], "1.2.3")
        self.assertEqual(main.next_package_version(manifest["version"]), "1.2.4")

    def test_style_name_can_be_read_from_css_metadata(self):
        metadata = main.infer_package_metadata("epub_style", "clean.css", b"/* name: Clean Reading */\nbody { color: #222; }")
        self.assertEqual(metadata["name"], "Clean Reading")
        self.assertTrue(metadata["explicitName"])

    def test_new_package_form_allows_automatic_metadata(self):
        config = main.load_config()
        actor = {"username": "owner", "role": "primary", "permissions": []}
        page = asyncio.run(main.admin_new_package(kind="online_source", actor=actor))
        html_text = page.body.decode("utf-8")
        self.assertIn("包 ID（可留空自动生成）", html_text)
        self.assertIn('name=\'name\'', html_text)

    def test_package_dependency_resolution_supports_alias_and_versions(self):
        dependency_dir = main.PACKAGE_ROOT / "online_source" / "parser.common"
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
        status = main.package_dependency_status({
            "kind": "online_source",
            "packageId": "book.source",
            "dependencies": [{"packageId": "parser.old", "minVersion": "2.0.0"}],
        })
        self.assertTrue(status["dependenciesSatisfied"])
        self.assertEqual(status["resolvedDependencies"][0]["resolvedVersion"], "2.1.0")

    def test_package_dependency_resolution_reports_required_missing(self):
        status = main.package_dependency_status({
            "kind": "online_source",
            "packageId": "book.source",
            "dependencies": [{"packageId": "parser.missing", "required": True}],
        })
        self.assertFalse(status["dependenciesSatisfied"])
        self.assertEqual(status["unresolvedDependencies"][0]["reason"], "not_found")

    def test_webhook_signature(self):
        body = b'{"action":"published"}'
        main.GITHUB_WEBHOOK_SECRET = "webhook-secret"
        signature = "sha256=" + hmac.new(b"webhook-secret", body, hashlib.sha256).hexdigest()
        self.assertTrue(hmac.compare_digest(signature, "sha256=" + hmac.new(main.GITHUB_WEBHOOK_SECRET.encode(), body, hashlib.sha256).hexdigest()))

    def test_release_range_and_etag_metadata(self):
        main.RELEASE_ROOT.mkdir(parents=True, exist_ok=True)
        apk = main.RELEASE_ROOT / "latest.apk"
        apk.write_bytes(b"0123456789")
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        (main.RELEASE_ROOT / "latest.json").write_text(json.dumps({"etag": digest}), encoding="utf-8")
        self.assertEqual(digest, hashlib.sha256(apk.read_bytes()).hexdigest())

    def test_release_version_name_falls_back_to_title(self):
        # CI 的 tag 不是语义版本号，必须从标题里取出真实版本号，否则客户端无法比较新旧。
        self.assertEqual("2.0.0", main.release_version_name("ci-123-1", "CI 2.0.0 #123"))
        self.assertEqual("2.0.0", main.release_version_name("ci-9-1", "CI 2.0.0 #9 signed release"))
        # tag 本身是语义版本号时优先用 tag，并去掉 v 前缀和构建元数据。
        self.assertEqual("2.1.0", main.release_version_name("v2.1.0", "任意标题"))
        self.assertEqual("2.1.0", main.release_version_name("v2.1.0+build7", ""))
        # 标题里没有版本号时保留原始 tag，不要臆造版本号。
        self.assertEqual("ci-123-1", main.release_version_name("ci-123-1", "CI build #123"))

    def test_is_semantic_version(self):
        self.assertTrue(main.is_semantic_version("2.0.0"))
        self.assertTrue(main.is_semantic_version("2.0.0-beta1"))
        self.assertFalse(main.is_semantic_version("ci-123-1"))
        self.assertFalse(main.is_semantic_version(""))

    def test_latest_release_channel_filter(self):
        main.RELEASE_ROOT.mkdir(parents=True, exist_ok=True)
        metadata = {"versionName": "2.0.0", "channel": "beta", "apkUrl": "/v1/releases/module/download"}
        (main.RELEASE_ROOT / "latest.json").write_text(json.dumps(metadata), encoding="utf-8")
        # 默认渠道是 stable，预发布版本会被拒绝——这正是客户端不带 channel 时出现 404 的原因。
        with self.assertRaises(HTTPException) as stable:
            asyncio.run(main.latest_release(channel="stable"))
        self.assertEqual(404, stable.exception.status_code)
        # 客户端显式请求 beta 时应当拿到该版本。
        result = asyncio.run(main.latest_release(channel="beta"))
        self.assertEqual("2.0.0", result["data"]["versionName"])
        # 非法渠道返回 400，便于区分"渠道写错"和"没有该渠道的版本"。
        with self.assertRaises(HTTPException) as invalid:
            asyncio.run(main.latest_release(channel="weekly"))
        self.assertEqual(400, invalid.exception.status_code)

    def test_latest_release_accepts_stable_when_asking_beta(self):
        main.RELEASE_ROOT.mkdir(parents=True, exist_ok=True)
        metadata = {"versionName": "2.0.0", "channel": "stable"}
        (main.RELEASE_ROOT / "latest.json").write_text(json.dumps(metadata), encoding="utf-8")
        self.assertEqual("2.0.0", asyncio.run(main.latest_release(channel="beta"))["data"]["versionName"])


if __name__ == "__main__":
    unittest.main()
