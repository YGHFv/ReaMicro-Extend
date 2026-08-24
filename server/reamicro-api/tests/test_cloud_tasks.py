import tempfile
import unittest
from pathlib import Path
import sys

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


if __name__ == "__main__":
    unittest.main()
