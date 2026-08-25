import tempfile
import unittest
import hashlib
import hmac
import json
from pathlib import Path
import sys

from fastapi import HTTPException
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
        main.CONFIG_ROOT = root / "config"
        main.CONFIG_PATH = main.CONFIG_ROOT / "server.json"
        main.STATE_DB_PATH = root / "state" / "reamicro.sqlite3"
        main.SERVER_BACKUP_ROOT = root / "backups" / "server"
        main.state_store = None
        main.ADMIN_USERNAME = "bootstrap-admin"
        main.ADMIN_PASSWORD = "bootstrap-password-123"

    def tearDown(self):
        main.CONFIG_ROOT = self.original_config_root
        main.CONFIG_PATH = self.original_config_path
        main.ADMIN_USERNAME = self.original_admin_username
        main.ADMIN_PASSWORD = self.original_admin_password
        self.temp_dir.cleanup()

    def test_primary_admin_must_complete_first_login_setup(self):
        credentials = HTTPBasicCredentials(username="bootstrap-admin", password="bootstrap-password-123")
        actor = main.require_admin(credentials, allow_bootstrap=True)
        self.assertTrue(actor["needsSetup"])
        with self.assertRaises(HTTPException) as raised:
            main.require_admin(credentials)
        self.assertEqual(raised.exception.status_code, 403)

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
        config["apiKeyRecords"] = [{"digest": main.api_key_digest("long-api-key"), "enabled": True}]
        main.save_config(config)
        self.assertTrue(main.configured_api_key(main.load_config(), "long-api-key"))
        self.assertIsNone(main.configured_api_key(main.load_config(), "revoked"))

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

    def test_package_payload_validation(self):
        main.validate_package_payload("online_source", "source.json", b'{"id":"source"}')
        main.validate_package_payload("epub_style", "style.css", "body{}".encode("utf-8"))
        with self.assertRaises(HTTPException):
            main.validate_package_payload("online_source", "source.json", b"not-json")

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


if __name__ == "__main__":
    unittest.main()
