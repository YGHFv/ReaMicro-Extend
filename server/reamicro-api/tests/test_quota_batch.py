"""上传配额执行与内容包批量操作。

`uploadQuota` 字段此前只能存能读，没有任何强制逻辑——配了也不生效。
批量操作此前完全没有，上百个内容包只能逐个点下架、删除。
"""
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import runtime, users
from tests.conftest_support import (
    HOST_ACCOUNT_ID,
    admin_auth,
    admin_csrf,
    b64,
    base_config,
    client,
    isolate,
    module_headers,
    seed_package,
    unwrap,
)


def source_payload(name: str, domain: str) -> bytes:
    return json.dumps({"bookSourceName": name, "bookSourceUrl": f"https://{domain}"}, ensure_ascii=False).encode()


class UploadQuotaTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config(
            moduleUploadEnabled=True,
            moduleUploadAllowlist=[HOST_ACCOUNT_ID],
            hostAccountAllowlist=[HOST_ACCOUNT_ID],
        )
        self.client = client()

    def tearDown(self):
        self.temp.cleanup()

    def _upload(self, name: str, domain: str):
        return self.client.post("/v1/packages/upload", headers=module_headers(), json={
            "kind": "online_source",
            "name": name,
            "domains": [domain],
            "payload": b64(source_payload(name, domain)),
            "payloadName": f"{domain}.json",
        })

    def test_no_quota_means_unlimited(self):
        for index in range(4):
            response = self._upload(f"源{index}", f"s{index}.example.com")
            self.assertEqual(200, response.status_code, response.text[:200])

    def test_quota_blocks_new_packages(self):
        users.upsert_user(HOST_ACCOUNT_ID, uploadQuota=2)
        self.assertEqual(200, self._upload("源A", "a.example.com").status_code)
        self.assertEqual(200, self._upload("源B", "b.example.com").status_code)
        third = self._upload("源C", "c.example.com")
        self.assertEqual(409, third.status_code)
        body = third.json()
        self.assertEqual("UPLOAD_QUOTA_EXCEEDED", body["code"])
        self.assertIn("已达到上传上限", body["message"])
        self.assertEqual({"quota": 2, "used": 2, "remaining": 0}, body["data"])

    def test_linking_existing_package_does_not_consume_quota(self):
        """关联已有内容包不占额度，否则用户想更新自己的源会被自己的配额挡住。"""
        users.upsert_user(HOST_ACCOUNT_ID, uploadQuota=1)
        first = self._upload("源A", "a.example.com")
        self.assertEqual(200, first.status_code)
        self.assertTrue(unwrap(first)["uploaded"])
        # 同名同域再传一次：走关联路径，不该被配额拦。
        again = self._upload("源A", "a.example.com")
        self.assertEqual(200, again.status_code, again.text[:200])
        self.assertFalse(unwrap(again)["uploaded"])
        self.assertTrue(unwrap(again)["linked"])

    def test_admin_uploads_do_not_count_against_user(self):
        """后台上传的包没有 uploadOwner，不该算进任何用户的配额。"""
        seed_package(package_id="admin-one")
        seed_package(package_id="admin-two")
        users.upsert_user(HOST_ACCOUNT_ID, uploadQuota=1)
        self.assertEqual(0, users.count_user_uploads(HOST_ACCOUNT_ID))
        self.assertEqual(200, self._upload("源A", "a.example.com").status_code)

    def test_quota_counts_legacy_owner_forms(self):
        """历史归属写法也要算进同一个用户，否则改过认证模式的用户配额会失准。"""
        seed_package(package_id="old", uploadOwner=f"host-public:{HOST_ACCOUNT_ID}")
        seed_package(package_id="keyed", uploadOwner=f"key:abc:host:{HOST_ACCOUNT_ID}")
        self.assertEqual(2, users.count_user_uploads(HOST_ACCOUNT_ID))

    def test_deleting_package_frees_quota(self):
        users.upsert_user(HOST_ACCOUNT_ID, uploadQuota=1)
        self.assertEqual(200, self._upload("源A", "a.example.com").status_code)
        self.assertEqual(409, self._upload("源B", "b.example.com").status_code)
        # 删掉后额度释放。
        target = next(runtime.PACKAGE_ROOT.joinpath("online_source").glob("*/manifest.json")).parent
        import shutil

        shutil.rmtree(target)
        self.assertEqual(200, self._upload("源B", "b.example.com").status_code)

    def test_policy_reports_quota_usage(self):
        """模块在上传前就能看到还能传几个，不用先撞墙。"""
        users.upsert_user(HOST_ACCOUNT_ID, uploadQuota=3)
        self._upload("源A", "a.example.com")
        policy = unwrap(self.client.get("/v1/packages/upload/policy", headers=module_headers()))
        self.assertEqual(3, policy["quota"])
        self.assertEqual(1, policy["used"])
        self.assertEqual(2, policy["remaining"])

    def test_quota_check_helper(self):
        self.assertEqual((True, "", {"quota": 0, "used": 0, "remaining": 0}), users.check_upload_quota(HOST_ACCOUNT_ID))
        users.upsert_user(HOST_ACCOUNT_ID, uploadQuota=2)
        allowed, reason, usage = users.check_upload_quota(HOST_ACCOUNT_ID)
        self.assertTrue(allowed)
        self.assertEqual("", reason)
        self.assertEqual(2, usage["remaining"])

    def test_admin_page_shows_and_saves_quota(self):
        base_config(hostAccountAllowlist=[HOST_ACCOUNT_ID])
        page = self.client.get("/admin/users", auth=admin_auth()).text
        self.assertIn("name='upload_quota'", page)
        response = self.client.post("/admin/users/save", auth=admin_auth(), data={
            "csrf_token": admin_csrf(), "account_id": HOST_ACCOUNT_ID,
            "enabled": "on", "upload_quota": "7",
        })
        self.assertEqual(200, response.status_code)
        self.assertEqual(7, users.get_user(HOST_ACCOUNT_ID)["uploadQuota"])
        self.assertIn("7 个", self.client.get("/admin/users", auth=admin_auth()).text)

    def test_negative_quota_normalizes_to_unlimited(self):
        users.upsert_user(HOST_ACCOUNT_ID, uploadQuota=-5)
        self.assertEqual(0, users.get_user(HOST_ACCOUNT_ID)["uploadQuota"])
        self.assertTrue(users.check_upload_quota(HOST_ACCOUNT_ID)[0])


class BatchOperationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        for index in range(4):
            seed_package(package_id=f"p{index}", name=f"源{index}")
        self.client = client()
        self.csrf = admin_csrf()

    def tearDown(self):
        self.temp.cleanup()

    def _status(self, package_id: str) -> str:
        path = runtime.PACKAGE_ROOT / "online_source" / package_id / "manifest.json"
        return json.loads(path.read_text(encoding="utf-8"))["status"]

    def _exists(self, package_id: str) -> bool:
        return (runtime.PACKAGE_ROOT / "online_source" / package_id).exists()

    def _batch(self, action: str, selected: list[str], **extra):
        return self.client.post(
            "/admin/content/online_source/batch",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "action": action, "selected": selected, **extra},
        )

    def test_list_exposes_batch_controls(self):
        page = self.client.get("/admin/content/online_source", auth=admin_auth()).text
        self.assertIn("batch-bar", page)
        self.assertIn("name='selected'", page)
        self.assertIn("/admin/content/online_source/batch", page)

    def test_batch_unpublish_and_publish(self):
        response = self._batch("unpublish", ["p0", "p1"])
        self.assertEqual(200, response.status_code, response.text[:300])
        self.assertEqual("unpublished", self._status("p0"))
        self.assertEqual("unpublished", self._status("p1"))
        self.assertEqual("published", self._status("p2"), "未勾选的不能被动到")
        self._batch("publish", ["p0"])
        self.assertEqual("published", self._status("p0"))

    def test_batch_delete_skips_published(self):
        """批量删除不放宽单个删除的规则：已发布的必须先下架。"""
        self._batch("unpublish", ["p0"])
        response = self._batch("delete", ["p0", "p1"])
        self.assertEqual(200, response.status_code)
        self.assertFalse(self._exists("p0"), "已下架的应被删除")
        self.assertTrue(self._exists("p1"), "已发布的应被跳过")
        self.assertIn("请先下架", response.text)

    def test_batch_check_records_health(self):
        seed_package(package_id="blocked", domains=["127.0.0.1"])
        response = self._batch("check", ["blocked"])
        self.assertEqual(200, response.status_code, response.text[:300])
        manifest = json.loads(
            (runtime.PACKAGE_ROOT / "online_source" / "blocked" / "manifest.json").read_text(encoding="utf-8")
        )
        self.assertIn("healthCheck", manifest)

    def test_empty_selection_is_reported(self):
        response = self._batch("unpublish", [])
        self.assertEqual(200, response.status_code)
        self.assertIn("没有勾选", response.text)

    def test_unknown_action_rejected(self):
        response = self.client.post(
            "/admin/content/online_source/batch",
            auth=admin_auth(),
            headers={"Accept": "application/json"},
            data={"csrf_token": self.csrf, "action": "rm -rf", "selected": ["p0"]},
        )
        self.assertEqual(400, response.status_code)

    def test_unknown_kind_rejected(self):
        response = self.client.post(
            "/admin/content/not_a_kind/batch",
            auth=admin_auth(),
            headers={"Accept": "application/json"},
            data={"csrf_token": self.csrf, "action": "unpublish", "selected": ["p0"]},
        )
        self.assertEqual(404, response.status_code)

    def test_csrf_required(self):
        response = self.client.post(
            "/admin/content/online_source/batch",
            auth=admin_auth(),
            data={"action": "unpublish", "selected": ["p0"]},
        )
        self.assertEqual(403, response.status_code)

    def test_missing_package_is_reported_not_fatal(self):
        response = self._batch("unpublish", ["p0", "does-not-exist"])
        self.assertEqual(200, response.status_code)
        self.assertEqual("unpublished", self._status("p0"), "存在的仍应处理")
        self.assertIn("失败 1", response.text)

    def test_duplicate_selection_processed_once(self):
        response = self._batch("unpublish", ["p0", "p0", "p0"])
        self.assertEqual(200, response.status_code)
        self.assertIn("成功 1 个", response.text)

    def test_path_traversal_in_selection_rejected(self):
        response = self.client.post(
            "/admin/content/online_source/batch",
            auth=admin_auth(),
            headers={"Accept": "application/json"},
            data={"csrf_token": self.csrf, "action": "delete", "selected": ["../../etc"]},
        )
        self.assertEqual(400, response.status_code)
        self.assertTrue(self._exists("p0"), "不能因为一个恶意条目影响其他内容")

    def test_batch_records_audit_event(self):
        self._batch("unpublish", ["p0"])
        events = runtime.AUDIT_PATH.read_text(encoding="utf-8")
        self.assertIn("packages_batch_unpublish", events)


if __name__ == "__main__":
    unittest.main()
