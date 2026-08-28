"""模块面向的 /v1 接口端到端测试。

此前 87 个路由里有 57 个完全没有测试引用，这个文件真实驱动 /v1 全链路：
能力发现、内容包读取与下载、同步密钥增删验证、任务生命周期、心跳与消息回执、备份往返。
"""
import json
import tempfile
import unittest
from pathlib import Path

from tests.conftest_support import (
    HOST_ACCOUNT_ID,
    admin_auth,
    b64,
    base_config,
    client,
    isolate,
    module_headers,
    seed_credential,
    seed_package,
    seed_task,
    unwrap,
)
from app import main


class ApiDiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        self.config = base_config()
        self.client = client()

    def tearDown(self):
        self.temp.cleanup()

    def test_health_endpoints(self):
        self.assertEqual(200, self.client.get("/health/live").status_code)
        ready = self.client.get("/health/ready")
        self.assertIn(ready.status_code, (200, 503))
        health = self.client.get("/v1/health")
        self.assertEqual(200, health.status_code)

    def test_discovery_is_public_and_lists_auth_mode(self):
        response = self.client.get("/v1/discovery")
        self.assertEqual(200, response.status_code)
        data = unwrap(response)
        self.assertEqual(main.API_VERSION, data["apiVersion"])
        self.assertEqual(["host_account_allowlist"], data["authModes"])
        self.assertTrue(data["authRequired"])
        # 发现接口不能泄露密钥或白名单内容。
        raw = response.text
        self.assertNotIn("hostAccountAllowlist", raw)
        self.assertNotIn("passwordHash", raw)

    def test_meta_requires_auth(self):
        self.assertEqual(401, self.client.get("/v1/meta").status_code)
        allowed = self.client.get("/v1/meta", headers=module_headers())
        self.assertEqual(200, allowed.status_code)
        self.assertEqual(main.API_VERSION, unwrap(allowed)["apiVersion"])

    def test_allowlist_rejects_unknown_account(self):
        response = self.client.get("/v1/meta", headers=module_headers(account_id="999"))
        self.assertEqual(401, response.status_code)

    def test_diagnostics_and_metrics_require_auth(self):
        self.assertEqual(401, self.client.get("/v1/diagnostics").status_code)
        diagnostics = self.client.get("/v1/diagnostics", headers=module_headers())
        self.assertEqual(200, diagnostics.status_code)
        self.assertEqual(401, self.client.get("/metrics").status_code)
        metrics = self.client.get("/metrics", headers=module_headers())
        self.assertEqual(200, metrics.status_code)
        self.assertIn("reamicro_http_requests_total", metrics.text)

    def test_health_dependencies(self):
        response = self.client.get("/health/dependencies", headers=module_headers())
        self.assertEqual(200, response.status_code)


class ApiPackageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        self.config = base_config()
        self.manifest = seed_package()
        self.client = client()

    def tearDown(self):
        self.temp.cleanup()

    def test_packages_listing_filters_by_kind(self):
        response = self.client.get("/v1/packages", params={"kind": "online_source"}, headers=module_headers())
        self.assertEqual(200, response.status_code)
        items = unwrap(response)["items"]
        self.assertEqual(1, len(items))
        self.assertEqual("example-com", items[0]["packageId"])
        self.assertEqual("示例书源", items[0]["name"])
        self.assertIn("downloadUrl", items[0])
        empty = self.client.get("/v1/packages", params={"kind": "theme"}, headers=module_headers())
        self.assertEqual([], unwrap(empty)["items"])

    def test_unpublished_packages_are_hidden_from_modules(self):
        seed_package(package_id="draft-source", status="draft", name="草稿书源")
        items = unwrap(self.client.get("/v1/packages", params={"kind": "online_source"}, headers=module_headers()))["items"]
        self.assertEqual(["example-com"], [item["packageId"] for item in items])

    def test_invalid_kind_rejected(self):
        response = self.client.get("/v1/packages", params={"kind": "../etc"}, headers=module_headers())
        self.assertEqual(400, response.status_code)

    def test_package_download_round_trip(self):
        response = self.client.get(
            f"/v1/packages/online_source/{self.manifest['packageId']}/download",
            headers=module_headers(),
        )
        self.assertEqual(200, response.status_code)
        digest = main.hashlib.sha256(response.content).hexdigest()
        self.assertEqual(self.manifest["sha256"], digest, "下载内容摘要必须与清单一致")

    def test_package_download_rejects_traversal(self):
        for package_id in ("..", "../..", "%2e%2e"):
            response = self.client.get(f"/v1/packages/online_source/{package_id}/download", headers=module_headers())
            self.assertIn(response.status_code, (400, 404), f"{package_id} 必须被拒绝")

    def test_package_history(self):
        response = self.client.get(
            f"/v1/packages/online_source/{self.manifest['packageId']}/history",
            headers=module_headers(),
        )
        self.assertEqual(200, response.status_code)
        versions = [item["version"] for item in unwrap(response)["items"]]
        self.assertIn("1.1.0", versions)

    def test_dependency_graph(self):
        response = self.client.get("/v1/packages/dependency-graph", headers=module_headers())
        self.assertEqual(200, response.status_code)
        data = unwrap(response)
        self.assertEqual(["online_source:example-com"], [node["id"] for node in data["nodes"]])
        self.assertEqual([], data["edges"])

    def test_module_upload_policy_reports_disabled_by_default(self):
        response = self.client.get("/v1/packages/upload/policy", headers=module_headers())
        self.assertEqual(200, response.status_code)
        data = unwrap(response)
        self.assertFalse(data["enabled"])
        self.assertFalse(data["allowed"])
        self.assertIn("未启用", data["reason"])

    def test_module_upload_requires_allowlist(self):
        base_config(moduleUploadEnabled=True, moduleUploadAllowlist=["999"], hostAccountAllowlist=[HOST_ACCOUNT_ID])
        policy = unwrap(self.client.get("/v1/packages/upload/policy", headers=module_headers()))
        self.assertTrue(policy["enabled"])
        self.assertFalse(policy["allowed"])
        upload = self.client.post(
            "/v1/packages/upload",
            headers=module_headers(),
            json={"kind": "online_source", "name": "新书源", "domains": ["new.example.org"], "payload": b64(b'{"bookSourceName":"x"}'), "payloadName": "s.json"},
        )
        self.assertEqual(403, upload.status_code)

    def test_module_upload_links_existing_same_name_and_domain(self):
        base_config(moduleUploadEnabled=True, moduleUploadAllowlist=[HOST_ACCOUNT_ID], hostAccountAllowlist=[HOST_ACCOUNT_ID])
        payload = json.dumps({"bookSourceName": "示例书源", "bookSourceUrl": "https://www.example.com/"}, ensure_ascii=False).encode()
        response = self.client.post(
            "/v1/packages/upload",
            headers=module_headers(),
            json={"kind": "online_source", "name": "示例书源", "domains": ["https://www.example.com/"], "payload": b64(payload), "payloadName": "s.json"},
        )
        self.assertEqual(200, response.status_code)
        data = unwrap(response)
        self.assertFalse(data["uploaded"], "同名同域不应重新上传")
        self.assertTrue(data["linked"])
        self.assertEqual("example-com", data["package"]["packageId"])
        # 服务器内容不能被模块覆盖。
        stored = json.loads((main.PACKAGE_ROOT / "online_source" / "example-com" / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("1.2.0", stored["version"])

    def test_module_upload_creates_new_package(self):
        base_config(moduleUploadEnabled=True, moduleUploadAllowlist=[HOST_ACCOUNT_ID], hostAccountAllowlist=[HOST_ACCOUNT_ID])
        payload = json.dumps({"bookSourceName": "全新书源", "bookSourceUrl": "https://brand-new.example.org"}, ensure_ascii=False).encode()
        response = self.client.post(
            "/v1/packages/upload",
            headers=module_headers(),
            json={"kind": "online_source", "name": "全新书源", "domains": ["brand-new.example.org"], "payload": b64(payload), "payloadName": "new.json"},
        )
        self.assertEqual(200, response.status_code)
        data = unwrap(response)
        self.assertTrue(data["uploaded"])
        self.assertTrue(data["linked"])
        self.assertNotEqual("example-com", data["package"]["packageId"])

    def test_module_upload_rejects_oversize_and_bad_base64(self):
        base_config(moduleUploadEnabled=True, moduleUploadAllowlist=[HOST_ACCOUNT_ID], hostAccountAllowlist=[HOST_ACCOUNT_ID])
        bad = self.client.post(
            "/v1/packages/upload",
            headers=module_headers(),
            json={"kind": "online_source", "name": "x", "payload": "not-base64!!!", "payloadName": "s.json"},
        )
        self.assertEqual(400, bad.status_code)
        empty = self.client.post(
            "/v1/packages/upload",
            headers=module_headers(),
            json={"kind": "online_source", "name": "x", "payload": "", "payloadName": "s.json"},
        )
        self.assertEqual(400, empty.status_code)

    def test_package_match_reports_linkable_sources(self):
        response = self.client.post(
            "/v1/packages/match",
            headers=module_headers(),
            json={"items": [
                {"kind": "online_source", "name": "示例书源", "domains": ["example.com"], "contentId": "local_1"},
                {"kind": "online_source", "name": "无关书源", "domains": ["nope.example.net"], "contentId": "local_2"},
            ]},
        )
        self.assertEqual(200, response.status_code)
        data = unwrap(response)
        self.assertEqual(1, data["matched"])
        matched = [item for item in data["items"] if item["matched"]]
        self.assertEqual("example-com", matched[0]["package"]["packageId"])

    def test_package_match_rejects_oversized_batch(self):
        response = self.client.post(
            "/v1/packages/match",
            headers=module_headers(),
            json={"items": [{"kind": "online_source", "name": f"s{i}"} for i in range(501)]},
        )
        self.assertEqual(400, response.status_code)


class ApiCredentialTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        self.client = client()

    def tearDown(self):
        self.temp.cleanup()

    def test_credential_crud_round_trip(self):
        created = self.client.post(
            "/v1/credentials/reamicro",
            headers=module_headers(),
            json={"token": "secret-token-value", "label": "我的阅微", "accountId": HOST_ACCOUNT_ID},
        )
        self.assertEqual(200, created.status_code)
        credential_id = unwrap(created)["id"]
        # 明文密钥绝不能回传。
        self.assertNotIn("secret-token-value", created.text)

        listed = self.client.get("/v1/credentials/reamicro", headers=module_headers())
        self.assertEqual(200, listed.status_code)
        items = unwrap(listed)["items"]
        self.assertEqual([credential_id], [item["id"] for item in items])
        self.assertNotIn("secretEncrypted", items[0])
        self.assertNotIn("secret-token-value", listed.text)

        toggled = self.client.post(
            f"/v1/credentials/reamicro/{credential_id}/toggle",
            headers=module_headers(),
            json={"enabled": False},
        )
        self.assertEqual(200, toggled.status_code)
        self.assertFalse(main.load_credentials()[credential_id]["enabled"])

        deleted = self.client.delete(f"/v1/credentials/reamicro/{credential_id}", headers=module_headers())
        self.assertEqual(200, deleted.status_code)
        self.assertNotIn(credential_id, main.load_credentials())

    def test_credential_isolation_across_accounts(self):
        base_config(hostAccountAllowlist=[HOST_ACCOUNT_ID, "7"])
        seed_credential("rea_mine", owner=f"host:{HOST_ACCOUNT_ID}")
        seed_credential("rea_theirs", owner="host:7")
        items = unwrap(self.client.get("/v1/credentials/reamicro", headers=module_headers()))["items"]
        self.assertEqual(["rea_mine"], [item["id"] for item in items])
        # 不能删除别人的凭据。
        response = self.client.delete("/v1/credentials/reamicro/rea_theirs", headers=module_headers())
        self.assertEqual(404, response.status_code)
        self.assertIn("rea_theirs", main.load_credentials())

    def test_credential_requires_token(self):
        response = self.client.post("/v1/credentials/reamicro", headers=module_headers(), json={"label": "无密钥"})
        self.assertEqual(400, response.status_code)


class ApiTaskLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        seed_credential()
        self.client = client()

    def tearDown(self):
        self.temp.cleanup()

    def test_task_lifecycle(self):
        created = self.client.post(
            "/v1/tasks",
            headers=module_headers(),
            json={
                "taskType": "yeshe_checkin",
                "schedule": {"intervalSeconds": 86400, "timeOfDay": "00:05"},
                "request": {"credentialId": "rea_1"},
            },
        )
        self.assertEqual(200, created.status_code)
        task_id = unwrap(created)["id"]
        self.assertNotIn("requestEncrypted", created.text)

        listed = unwrap(self.client.get("/v1/tasks", headers=module_headers()))
        self.assertEqual([task_id], [item["id"] for item in listed["items"]])

        detail = self.client.get(f"/v1/tasks/{task_id}", headers=module_headers())
        self.assertEqual(200, detail.status_code)

        for action, expected in (("pause", "paused"), ("resume", "scheduled"), ("cancel", "cancelled")):
            response = self.client.post(f"/v1/tasks/{task_id}/{action}", headers=module_headers())
            self.assertEqual(200, response.status_code, f"{action} 失败：{response.text}")
            self.assertEqual(expected, main.load_tasks()[task_id]["status"], f"{action} 后状态不符")

        configured = self.client.post(
            f"/v1/tasks/{task_id}/configure",
            headers=module_headers(),
            json={"enabled": True, "schedule": {"intervalSeconds": 43200, "timeOfDay": "06:30"}},
        )
        self.assertEqual(200, configured.status_code)
        self.assertEqual(43200, main.load_tasks()[task_id]["schedule"]["intervalSeconds"])

        logs = self.client.get(f"/v1/tasks/{task_id}/logs", headers=module_headers())
        self.assertEqual(200, logs.status_code)

        deleted = self.client.delete(f"/v1/tasks/{task_id}", headers=module_headers())
        self.assertEqual(200, deleted.status_code)
        self.assertNotIn(task_id, main.load_tasks())

    def test_task_isolation_across_accounts(self):
        base_config(hostAccountAllowlist=[HOST_ACCOUNT_ID, "7"])
        seed_task("task_mine", owner=f"host:{HOST_ACCOUNT_ID}")
        seed_task("task_theirs", owner="host:7")
        items = unwrap(self.client.get("/v1/tasks", headers=module_headers()))["items"]
        self.assertEqual(["task_mine"], [item["id"] for item in items])
        for path in ("/v1/tasks/task_theirs", "/v1/tasks/task_theirs/logs"):
            self.assertEqual(404, self.client.get(path, headers=module_headers()).status_code, path)
        for action in ("pause", "resume", "cancel", "run"):
            response = self.client.post(f"/v1/tasks/task_theirs/{action}", headers=module_headers())
            self.assertEqual(404, response.status_code, f"不能操作别人的任务 {action}")
        self.assertEqual(404, self.client.delete("/v1/tasks/task_theirs", headers=module_headers()).status_code)

    def test_task_rejects_unknown_type_and_bad_schedule(self):
        bad_type = self.client.post(
            "/v1/tasks",
            headers=module_headers(),
            json={"taskType": "rm -rf", "schedule": {"intervalSeconds": 86400}, "request": {}},
        )
        self.assertEqual(400, bad_type.status_code)

    def test_task_rejects_foreign_credential(self):
        base_config(hostAccountAllowlist=[HOST_ACCOUNT_ID, "7"])
        seed_credential("rea_theirs", owner="host:7")
        response = self.client.post(
            "/v1/tasks",
            headers=module_headers(),
            json={"taskType": "yeshe_checkin", "schedule": {"intervalSeconds": 86400}, "request": {"credentialId": "rea_theirs"}},
        )
        self.assertIn(response.status_code, (400, 403, 404), "不能绑定别人的同步密钥")


class ApiPresenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        seed_credential()
        self.client = client()

    def tearDown(self):
        self.temp.cleanup()

    def test_heartbeat_delivers_and_acks_notifications(self):
        task = seed_task("task_1")
        notification_id = main.enqueue_task_notification(task, "success", "签到完成", 1750000000000)

        heartbeat = self.client.post(
            "/v1/presence/heartbeat",
            headers=module_headers(),
            json={"source": "foreground", "moduleVersion": "2.4.0", "buildTime": "1750000000000"},
        )
        self.assertEqual(200, heartbeat.status_code)
        data = unwrap(heartbeat)
        self.assertGreater(data["onlineUntil"], 0)
        self.assertEqual(1, data["pendingCount"])
        self.assertEqual([notification_id], [item["id"] for item in data["notifications"]])

        listed = unwrap(self.client.get("/v1/notifications", headers=module_headers()))
        self.assertEqual([notification_id], [item["id"] for item in listed["items"]])

        acked = self.client.post("/v1/notifications/ack", headers=module_headers(), json={"ids": [notification_id]})
        self.assertEqual(200, acked.status_code)
        self.assertEqual(1, unwrap(acked)["acknowledged"])
        self.assertTrue(main.load_notifications()[notification_id]["deliveredAt"])

        # 回执后不再重复下发。
        again = unwrap(self.client.post("/v1/presence/heartbeat", headers=module_headers(), json={"source": "alarm"}))
        self.assertEqual(0, again["pendingCount"])

    def test_notifications_isolated_across_accounts(self):
        base_config(hostAccountAllowlist=[HOST_ACCOUNT_ID, "7"])
        other_task = seed_task("task_theirs", owner="host:7")
        foreign_id = main.enqueue_task_notification(other_task, "success", "别人的消息", 1750000000000)
        listed = unwrap(self.client.get("/v1/notifications", headers=module_headers()))
        self.assertEqual([], listed["items"])
        # 也不能替别人回执。
        acked = self.client.post("/v1/notifications/ack", headers=module_headers(), json={"ids": [foreign_id]})
        self.assertEqual(0, unwrap(acked)["acknowledged"])
        self.assertFalse(main.load_notifications()[foreign_id]["deliveredAt"])

    def test_ack_rejects_oversized_list(self):
        response = self.client.post(
            "/v1/notifications/ack",
            headers=module_headers(),
            json={"ids": [f"msg_{i}" for i in range(101)]},
        )
        self.assertEqual(400, response.status_code)


class ApiBackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        self.client = client()

    def tearDown(self):
        self.temp.cleanup()

    def test_module_backup_round_trip(self):
        payload = b"PK\x03\x04module-settings-backup"
        uploaded = self.client.post(
            "/v1/backups/module",
            headers=module_headers(**{"Content-Type": "application/zip"}),
            content=payload,
        )
        self.assertEqual(200, uploaded.status_code)
        downloaded = self.client.get("/v1/backups/module/latest", headers=module_headers())
        self.assertEqual(200, downloaded.status_code)
        self.assertEqual(payload, downloaded.content)

    def test_credentials_backup_rejects_foreign_format(self):
        """服务端要求 RCRED1 魔术头，与模块 CredentialBackupCrypto.PREFIX 一致。"""
        response = self.client.post(
            "/v1/backups/credentials",
            headers=module_headers(**{"Content-Type": "application/octet-stream"}),
            content=b"\x00\x01not-a-credential-backup",
        )
        self.assertEqual(400, response.status_code)

    def test_credentials_backup_round_trip(self):
        # 前缀必须与模块 CredentialBackupCrypto.PREFIX 完全一致，否则服务端拒收。
        payload = b"RCRED1\n" + b"\x00\x01encrypted-credential-blob"
        uploaded = self.client.post(
            "/v1/backups/credentials",
            headers=module_headers(**{"Content-Type": "application/octet-stream"}),
            content=payload,
        )
        self.assertEqual(200, uploaded.status_code)
        downloaded = self.client.get("/v1/backups/credentials/latest", headers=module_headers())
        self.assertEqual(200, downloaded.status_code)
        self.assertEqual(payload, downloaded.content)

    def test_backup_isolated_across_accounts(self):
        base_config(hostAccountAllowlist=[HOST_ACCOUNT_ID, "7"])
        self.client.post("/v1/backups/module", headers=module_headers(**{"Content-Type": "application/zip"}), content=b"mine")
        other = self.client.get("/v1/backups/module/latest", headers=module_headers(account_id="7"))
        self.assertEqual(404, other.status_code, "不能下到别人的备份")

    def test_missing_backup_returns_404(self):
        self.assertEqual(404, self.client.get("/v1/backups/module/latest", headers=module_headers()).status_code)
        self.assertEqual(404, self.client.get("/v1/backups/credentials/latest", headers=module_headers()).status_code)

    def test_backup_rejects_oversize(self):
        response = self.client.post(
            "/v1/backups/module",
            headers=module_headers(**{"Content-Type": "application/zip"}),
            content=b"x" * (60 * 1024 * 1024),
        )
        self.assertIn(response.status_code, (400, 413))


class ApiReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        self.client = client()

    def tearDown(self):
        self.temp.cleanup()

    def _seed_release(self, channel: str = "beta"):
        # 下载路由固定读 RELEASE_ROOT/latest.apk，文件名不能改。
        main.RELEASE_ROOT.mkdir(parents=True, exist_ok=True)
        apk = main.RELEASE_ROOT / "latest.apk"
        apk.write_bytes(b"PK\x03\x04fake-apk-bytes")
        (main.RELEASE_ROOT / "latest.json").write_text(json.dumps({
            "versionName": "2.4.0",
            "versionCode": 2400,
            "channel": channel,
            "apkUrl": "/v1/releases/module/download",
            "apkName": apk.name,
            "sha256": main.hashlib.sha256(apk.read_bytes()).hexdigest(),
            "size": apk.stat().st_size,
            "publishedAt": 1750000000000,
        }, ensure_ascii=False), encoding="utf-8")

    def test_latest_release_channel_filter(self):
        self._seed_release("beta")
        beta = self.client.get("/v1/releases/module/latest", params={"channel": "beta"}, headers=module_headers())
        self.assertEqual(200, beta.status_code)
        self.assertEqual("2.4.0", unwrap(beta)["versionName"])
        # 预发布不能出现在 stable 渠道里。
        stable = self.client.get("/v1/releases/module/latest", params={"channel": "stable"}, headers=module_headers())
        self.assertEqual(404, stable.status_code)

    def test_release_download_and_digest(self):
        self._seed_release("beta")
        response = self.client.get("/v1/releases/module/download", headers=module_headers())
        self.assertEqual(200, response.status_code)
        expected = json.loads((main.RELEASE_ROOT / "latest.json").read_text(encoding="utf-8"))["sha256"]
        self.assertEqual(expected, main.hashlib.sha256(response.content).hexdigest())

    def test_missing_release_reports_not_configured(self):
        """没有已同步版本时返回 200 + NOT_CONFIGURED，模块端按 message 报错。

        这里必须打桩 sync_module_release：latest.json 缺失会触发一次真实 GitHub 调用。
        """
        original = main.sync_module_release
        main.sync_module_release = lambda: None
        try:
            response = self.client.get("/v1/releases/module/latest", params={"channel": "beta"}, headers=module_headers())
        finally:
            main.sync_module_release = original
        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual("NOT_CONFIGURED", body["code"])
        self.assertIsNone(body["data"])
        self.assertTrue(body["message"])

    def test_invalid_channel_rejected(self):
        self._seed_release("beta")
        response = self.client.get("/v1/releases/module/latest", params={"channel": "nightly-x"}, headers=module_headers())
        self.assertEqual(400, response.status_code)

    def test_github_webhook_rejects_bad_signature(self):
        main.GITHUB_WEBHOOK_SECRET = "webhook-secret"
        response = self.client.post(
            "/v1/webhooks/github",
            headers={"X-Hub-Signature-256": "sha256=deadbeef", "X-GitHub-Event": "release"},
            json={"action": "published"},
        )
        self.assertIn(response.status_code, (401, 403))


if __name__ == "__main__":
    unittest.main()
