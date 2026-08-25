import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
