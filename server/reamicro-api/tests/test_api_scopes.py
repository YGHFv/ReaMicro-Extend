"""API Key 权限范围的自洽性。

历史问题：required_api_scope 对 POST /v1/backups 要求 `backup:write`，但该 scope
既不在可勾选列表里，也不在 "write" 的展开集合里，更不在默认全权集合里——
结果任何带 API Key 的备份上传请求都无条件 403，而后台根本没法把这个权限授出去。
"""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import runtime, security
from app.config_store import save_config, default_config
from app.crypto import api_key_digest
from tests.conftest_support import client, isolate, module_headers


class FakeRequest:
    """只带 required_api_scope 需要的两个字段。"""

    def __init__(self, path: str, method: str = "GET"):
        self.url = type("U", (), {"path": path})()
        self.method = method


class ApiScopeConsistencyTest(unittest.TestCase):
    def test_every_required_scope_is_grantable(self):
        """路由要求的每个 scope 都必须能被授予，否则那条路由对 API Key 恒 403。"""
        paths = [
            "/v1/packages", "/v1/packages/upload", "/v1/tasks", "/v1/tasks/x/run",
            "/v1/notifications", "/v1/notifications/ack", "/v1/presence/heartbeat",
            "/v1/credentials/reamicro", "/v1/backups/module", "/v1/backups/credentials",
        ]
        required = set()
        for path in paths:
            for method in ("GET", "POST", "DELETE"):
                scope = security.required_api_scope(FakeRequest(path, method))
                if scope:
                    required.add(scope)
        grantable = set(security.API_KEY_PERMISSIONS)
        missing = sorted(required - grantable)
        self.assertEqual([], missing, f"这些 scope 被要求但无法授予：{missing}")

    def test_write_expands_to_every_write_scope(self):
        """勾选 write 应涵盖所有写权限，不能漏掉某个域。"""
        config = {**default_config(), "apiKeyRecords": [{
            "id": "k1", "digest": api_key_digest("key-value"), "enabled": True,
            "permissions": ["write"],
        }]}
        granted = security.api_key_permissions(config, "key-value")
        for scope in security.API_KEY_PERMISSIONS:
            if scope.endswith(":write"):
                self.assertIn(scope, granted, f"write 未涵盖 {scope}")

    def test_read_expands_to_every_read_scope(self):
        config = {**default_config(), "apiKeyRecords": [{
            "id": "k1", "digest": api_key_digest("key-value"), "enabled": True,
            "permissions": ["read"],
        }]}
        granted = security.api_key_permissions(config, "key-value")
        for scope in security.API_KEY_PERMISSIONS:
            if scope.endswith(":read"):
                self.assertIn(scope, granted, f"read 未涵盖 {scope}")

    def test_empty_permissions_grants_everything(self):
        """未指定权限的旧记录按全权处理，且集合与可授予列表一致。"""
        config = {**default_config(), "apiKeyRecords": [{
            "id": "k1", "digest": api_key_digest("key-value"), "enabled": True, "permissions": [],
        }]}
        self.assertEqual(set(security.API_KEY_PERMISSIONS), security.api_key_permissions(config, "key-value"))

    def test_narrow_key_still_denied(self):
        """收紧后不能反过来放宽：只给 read 的 Key 不该拿到写权限。"""
        config = {**default_config(), "apiKeyRecords": [{
            "id": "k1", "digest": api_key_digest("key-value"), "enabled": True,
            "permissions": ["read"],
        }]}
        granted = security.api_key_permissions(config, "key-value")
        self.assertNotIn("backup:write", granted)
        self.assertNotIn("packages:write", granted)


class BackupUploadScopeTest(unittest.TestCase):
    """端到端：带 API Key 上传模块备份必须成功。"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        self.api_key = "k" * 40
        save_config({
            **default_config(),
            "authMode": "api_key",
            "apiKey": self.api_key,
            "apiKeyRecords": [{
                "id": "k1", "name": "模块", "digest": api_key_digest(self.api_key),
                "enabled": True, "permissions": ["read", "write"],
            }],
        })
        self.client = client()

    def tearDown(self):
        self.temp.cleanup()

    def _headers(self, content_type: str = "application/zip") -> dict:
        return {
            "X-ReaMicro-Api-Key": self.api_key,
            "X-ReaMicro-Host-Account-Id": "3",
            "Content-Type": content_type,
            "Accept": "application/json",
        }

    def test_module_backup_upload_and_download(self):
        payload = b"PK\x03\x04module-settings"
        uploaded = self.client.post("/v1/backups/module", headers=self._headers(), content=payload)
        self.assertEqual(200, uploaded.status_code, uploaded.text[:200])
        downloaded = self.client.get("/v1/backups/module/latest", headers=self._headers())
        self.assertEqual(200, downloaded.status_code)
        self.assertEqual(payload, downloaded.content)

    def test_credentials_backup_upload(self):
        payload = b"RCRED1\n" + b"cipher"
        response = self.client.post(
            "/v1/backups/credentials",
            headers=self._headers("application/octet-stream"),
            content=payload,
        )
        self.assertEqual(200, response.status_code, response.text[:200])

    def test_read_only_key_cannot_upload(self):
        save_config({
            **default_config(),
            "authMode": "api_key",
            "apiKey": self.api_key,
            "apiKeyRecords": [{
                "id": "k1", "digest": api_key_digest(self.api_key),
                "enabled": True, "permissions": ["read"],
            }],
        })
        response = self.client.post("/v1/backups/module", headers=self._headers(), content=b"PK\x03\x04x")
        self.assertEqual(403, response.status_code)


if __name__ == "__main__":
    unittest.main()
