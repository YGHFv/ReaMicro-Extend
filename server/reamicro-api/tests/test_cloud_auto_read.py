"""云端自动阅读接口与响应解析回归测试。"""
import asyncio
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import crypto, executors, state
from tests.conftest_support import isolate


class CloudAutoReadTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        isolate(Path(self.temp_dir.name), secret_key="test-secret-key-for-auto-read")
        state.save_credentials({
            "rea_1": {
                "id": "rea_1",
                "owner": "host:3",
                "type": "reamicro",
                "accountId": "3",
                "secretEncrypted": crypto.encrypt_secret({
                    "token": "token-value",
                    "baseUrl": "https://example.invalid/",
                }),
                "enabled": True,
            },
        })
        self.original_request = executors.json_http_request
        self.calls: list[tuple[str, dict]] = []

    def tearDown(self):
        executors.json_http_request = self.original_request
        self.temp_dir.cleanup()

    def task(self):
        return {
            "id": "task_1",
            "owner": "host:3",
            "taskType": "cloud_auto_read",
            "credentialId": "rea_1",
            "requestEncrypted": crypto.encrypt_secret({
                "credentialId": "rea_1",
                "durationMinutes": 30,
                "recentLimit": 1,
                "bookLimit": 1,
            }),
            "status": "scheduled",
            "enabled": True,
        }

    def test_recent_book_uses_current_reader_api_contract(self):
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload))
            if endpoint == "rest/reader/get-read-record-list":
                return 200, {
                    "code": 0,
                    "message": "success",
                    "data": {"list": [{
                        "bookId": "123",
                        "cloudBookId": "999",
                        "progress": 0.42,
                        "name": "测试图书",
                        "cfi": "epubcfi(/6/2!/4/1:0)",
                    }]},
                }, "{}"
            if endpoint == "rest/reader/update-read-time-by-date":
                return 200, {"code": 0, "message": "success", "data": {}}, "{}"
            self.fail(f"调用了未预期的阅微接口：{endpoint}")

        executors.json_http_request = fake
        result, message = executors.execute_reamicro_task(self.task())

        self.assertEqual("success", result)
        self.assertIn("完成 1 本", message)
        self.assertIn("测试图书", message)
        endpoints = [endpoint for endpoint, _ in self.calls]
        self.assertEqual([
            "rest/reader/get-read-record-list",
            "rest/reader/update-read-time-by-date",
        ], endpoints)
        self.assertNotIn("rest/reader/update-read-progress", endpoints)
        self.assertEqual(123, self.calls[1][1]["list"][0]["bookId"])

    def test_recent_book_does_not_fall_back_to_public_cloud_book_id(self):
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload))
            if endpoint == "rest/reader/get-read-record-list":
                return 200, {
                    "code": 0,
                    "message": "success",
                    "data": {"list": [{
                        "cloudBookId": "999",
                        "name": "缺少用户图书 ID",
                    }]},
                }, "{}"
            self.fail(f"调用了未预期的阅微接口：{endpoint}")

        executors.json_http_request = fake
        result, message = executors.execute_reamicro_task(self.task())

        self.assertEqual("failed", result)
        self.assertEqual("没有找到可阅读的图书", message)
        self.assertEqual(["rest/reader/get-read-record-list"], [endpoint for endpoint, _ in self.calls])

    def test_legacy_custom_cloud_book_id_remains_compatible(self):
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload))
            if endpoint == "rest/reader/update-read-time-by-date":
                return 200, {"code": 0, "message": "success", "data": {}}, "{}"
            self.fail(f"调用了未预期的阅微接口：{endpoint}")

        task = self.task()
        task["requestEncrypted"] = crypto.encrypt_secret({
            "credentialId": "rea_1",
            "durationMinutes": 30,
            "books": [{"cloudBookId": "456", "name": "旧版自定义图书"}],
        })
        executors.json_http_request = fake
        result, message = executors.execute_reamicro_task(task)

        self.assertEqual("success", result)
        self.assertIn("旧版自定义图书", message)
        self.assertEqual(["rest/reader/update-read-time-by-date"], [endpoint for endpoint, _ in self.calls])
        self.assertEqual(456, self.calls[0][1]["list"][0]["bookId"])

    def test_http_200_business_error_is_not_reported_as_empty_book_list(self):
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload))
            return 200, {"code": 404, "message": "接口不存在", "data": None}, "{}"

        executors.json_http_request = fake
        result, message = executors.execute_reamicro_task(self.task())

        self.assertEqual("failed", result)
        self.assertIn("阅微业务码 404", message)
        self.assertIn("接口不存在", message)
        self.assertNotIn("没有找到可阅读的图书", message)
        self.assertEqual(1, len(self.calls))

    def test_secret_verification_uses_recent_reader_endpoint(self):
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload))
            return 200, {"code": 0, "message": "success", "data": {"list": []}}, "{}"

        executors.json_http_request = fake
        verified, message = asyncio.run(executors.verify_reamicro_secret("token", "https://example.invalid/"))

        self.assertTrue(verified)
        self.assertEqual("验证成功", message)
        self.assertEqual("rest/reader/get-read-record-list", self.calls[0][0])

    def test_secret_verification_rejects_http_200_business_error(self):
        def fake(url, token, payload, endpoint="", timeout=45):
            return 200, {"code": 401, "message": "登录已失效", "data": None}, "{}"

        executors.json_http_request = fake
        verified, message = asyncio.run(executors.verify_reamicro_secret("token", "https://example.invalid/"))

        self.assertFalse(verified)
        self.assertIn("阅微业务码 401", message)
        self.assertIn("登录已失效", message)


if __name__ == "__main__":
    unittest.main()
