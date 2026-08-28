"""模块上传高亮样式。

高亮样式没有域名可比，服务器按"名称 + 稳定标识"两类各命中一项来匹配。
样式内容裹在 `{"schemaVersion":1,"style":{...}}` 里，元数据推断必须下钻到 style，
否则名称会退化成文件名、标识也取不到样式 ID。
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import packages, runtime
from app.config_store import module_upload_kinds
from tests.conftest_support import (
    HOST_ACCOUNT_ID,
    b64,
    base_config,
    client,
    isolate,
    module_headers,
    unwrap,
)

STYLE_ID = "my_highlight"
STYLE_NAME = "我的高亮"


def style_payload(style_id: str = STYLE_ID, name: str = STYLE_NAME, **extra) -> bytes:
    """模块实际上传的载荷形状。

    样式本身不区分深色浅色——深浅在高亮规则（styleId / darkStyleId）和默认样式设置
    两层，所以载荷里没有任何 dark* 字段。
    """
    style = {
        "id": style_id,
        "name": name,
        "color": "#FF8800",
        "fontFamily": "",
        "css": "font-size: 0.9em",
    }
    style.update(extra)
    return json.dumps({"schemaVersion": 1, "style": style}, ensure_ascii=False).encode("utf-8")


class HighlightUploadTest(unittest.TestCase):
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

    def _upload(self, payload: bytes, name: str = STYLE_NAME, content_id: str = STYLE_ID, names=(), identities=()):
        return self.client.post("/v1/packages/upload", headers=module_headers(), json={
            "kind": "highlight_style",
            "name": name,
            "names": list(names),
            "contentId": content_id,
            "identities": list(identities) or [content_id],
            "domains": [],
            "payload": b64(payload),
            "payloadName": f"{content_id}.json",
        })

    def _manifest(self, package_id: str) -> dict:
        path = runtime.PACKAGE_ROOT / "highlight_style" / package_id / "manifest.json"
        return json.loads(path.read_text(encoding="utf-8"))

    def test_policy_lists_highlight_style(self):
        policy = unwrap(self.client.get("/v1/packages/upload/policy", headers=module_headers()))
        self.assertTrue(policy["allowed"])
        self.assertIn("highlight_style", policy["kinds"])

    def test_upload_creates_package(self):
        response = self._upload(style_payload())
        self.assertEqual(200, response.status_code, response.text[:300])
        data = unwrap(response)
        self.assertTrue(data["uploaded"])
        self.assertTrue(data["linked"])
        manifest = self._manifest(data["package"]["packageId"])
        self.assertEqual("highlight_style", manifest["kind"])
        self.assertEqual(STYLE_NAME, manifest["name"], "名称要取 style.name，不能退化成文件名")

    def test_payload_is_stored_verbatim(self):
        """服务器原样保存载荷，不增删字段。"""
        payload = style_payload()
        data = unwrap(self._upload(payload))
        package_id = data["package"]["packageId"]
        stored = json.loads(
            (runtime.PACKAGE_ROOT / "highlight_style" / package_id / f"{STYLE_ID}.json").read_text(encoding="utf-8")
        )
        self.assertEqual(json.loads(payload.decode()), stored)

    def test_no_dark_fields_in_payload(self):
        """样式不区分深浅：深浅在高亮规则与默认样式设置两层，载荷里不该有 dark* 字段。"""
        text = style_payload().decode()
        for field in ("darkUsesLight", "darkColor", "darkFontFamily", "darkCss", "darkNinePatch"):
            self.assertNotIn(field, text)

    def test_second_upload_of_same_style_links_not_duplicates(self):
        first = unwrap(self._upload(style_payload()))
        second = unwrap(self._upload(style_payload(name=STYLE_NAME)))
        self.assertTrue(first["uploaded"])
        self.assertFalse(second["uploaded"], "同名同标识应关联而不是再建一个包")
        self.assertEqual(first["package"]["packageId"], second["package"]["packageId"])
        self.assertEqual("name+identity", second["package"]["matchReason"])

    def test_renamed_style_links_via_historical_name(self):
        self._upload(style_payload())
        renamed = unwrap(self._upload(
            style_payload(name="新名字"), name="新名字", names=["新名字", STYLE_NAME],
        ))
        self.assertFalse(renamed["uploaded"], "带上旧名应命中原包")
        names = self._manifest(renamed["package"]["packageId"])["names"]
        self.assertIn("新名字", names)
        self.assertIn(STYLE_NAME, names)

    def test_different_style_creates_new_package(self):
        first = unwrap(self._upload(style_payload()))
        other = unwrap(self._upload(
            style_payload(style_id="other_style", name="别的高亮"),
            name="别的高亮",
            content_id="other_style",
        ))
        self.assertTrue(other["uploaded"])
        self.assertNotEqual(first["package"]["packageId"], other["package"]["packageId"])

    def test_same_name_different_identity_is_not_linked(self):
        """名称相同但标识不同：没有域名可比时，标识就是第二道闸，不能关联。"""
        first = unwrap(self._upload(style_payload()))
        same_name = unwrap(self._upload(
            style_payload(style_id="unrelated", name=STYLE_NAME),
            content_id="unrelated",
        ))
        self.assertTrue(same_name["uploaded"], "应新建而不是接管别人的样式")
        self.assertNotEqual(first["package"]["packageId"], same_name["package"]["packageId"])

    def test_upload_appears_in_package_listing(self):
        self._upload(style_payload())
        items = unwrap(self.client.get("/v1/packages", params={"kind": "highlight_style"}, headers=module_headers()))["items"]
        self.assertEqual(1, len(items))
        self.assertEqual(STYLE_NAME, items[0]["name"])

    def test_download_returns_uploaded_bytes(self):
        payload = style_payload()
        data = unwrap(self._upload(payload))
        package_id = data["package"]["packageId"]
        response = self.client.get(f"/v1/packages/highlight_style/{package_id}/download", headers=module_headers())
        self.assertEqual(200, response.status_code)
        self.assertEqual(json.loads(payload.decode()), json.loads(response.content.decode()))

    def test_invalid_json_rejected(self):
        response = self._upload(b"not json at all")
        self.assertEqual(400, response.status_code)

    def test_disallowed_kind_still_rejected(self):
        """主题不在允许集合里，即便配置写了也要拒。"""
        base_config(
            moduleUploadEnabled=True,
            moduleUploadAllowlist=[HOST_ACCOUNT_ID],
            hostAccountAllowlist=[HOST_ACCOUNT_ID],
            moduleUploadKinds=["theme"],
        )
        response = self.client.post("/v1/packages/upload", headers=module_headers(), json={
            "kind": "theme", "name": "主题", "domains": [],
            "payload": b64(b'{"a":1}'), "payloadName": "t.json",
        })
        self.assertEqual(400, response.status_code, "theme 不是模块可上传类型")

    def test_kind_restriction_can_exclude_highlight(self):
        base_config(
            moduleUploadEnabled=True,
            moduleUploadAllowlist=[HOST_ACCOUNT_ID],
            hostAccountAllowlist=[HOST_ACCOUNT_ID],
            moduleUploadKinds=["online_source"],
        )
        self.assertEqual(["online_source"], module_upload_kinds(["online_source"]))
        response = self._upload(style_payload())
        self.assertEqual(403, response.status_code)


class HighlightMetadataTest(unittest.TestCase):
    def test_metadata_descends_into_style_wrapper(self):
        metadata = packages.infer_package_metadata("highlight_style", "my_highlight.json", style_payload())
        self.assertEqual(STYLE_NAME, metadata["name"])
        self.assertTrue(metadata["explicitName"])
        self.assertIn(STYLE_ID, metadata["identity"])

    def test_metadata_without_wrapper_still_works(self):
        flat = json.dumps({"id": "flat", "name": "扁平样式", "color": "#123456"}, ensure_ascii=False).encode()
        metadata = packages.infer_package_metadata("highlight_style", "flat.json", flat)
        self.assertEqual("扁平样式", metadata["name"])
        self.assertIn("flat", metadata["identity"])

    def test_highlight_style_has_no_domains(self):
        metadata = packages.infer_package_metadata("highlight_style", "x.json", style_payload())
        self.assertEqual([], metadata["domains"], "颜色值不能被当成域名")


if __name__ == "__main__":
    unittest.main()
