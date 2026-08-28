"""书源关联匹配的语义。

规则：**名称与地址两类各命中至少一项**才算同一个源。两边都支持多值——
名称集合含历史名称，地址集合含主地址与全部备用镜像。

这样源改名（地址不变）或换域名（名称不变）都能继续关联并通过服务器更新，
同时避免只靠单一维度带来的冒用：只对名称，两个无关的源可能同名；
只对地址，同一站点上的不同源会被撞在一起。
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import packages, runtime
from tests.conftest_support import (
    HOST_ACCOUNT_ID,
    b64,
    base_config,
    client,
    isolate,
    module_headers,
    unwrap,
)


def write_package(package_id: str, name: str, domains=(), names=(), primary="", content_id="", kind="online_source"):
    directory = runtime.PACKAGE_ROOT / kind / package_id
    directory.mkdir(parents=True, exist_ok=True)
    payload = json.dumps({"bookSourceName": name}, ensure_ascii=False).encode("utf-8")
    (directory / "payload.json").write_bytes(payload)
    (directory / "manifest.json").write_text(json.dumps({
        "packageId": package_id,
        "kind": kind,
        "version": "1.0.0",
        "name": name,
        "names": list(names),
        "domains": list(domains),
        "primaryDomain": primary,
        "contentId": content_id or package_id,
        "aliases": [],
        "status": "published",
        "payload": "payload.json",
        "sha256": "0" * 64,
    }, ensure_ascii=False), encoding="utf-8")


class MatchSemanticsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()

    def tearDown(self):
        self.temp.cleanup()

    def match(self, name, domains=(), identities=(), names=()):
        return packages.find_matching_package(
            "online_source", name, set(domains), set(identities), set(names),
        )

    # ---------- 应该匹配 ----------

    def test_same_name_and_domain(self):
        write_package("a", "示例书源", domains=["example.com"])
        matched = self.match("示例书源", ["example.com"])
        self.assertIsNotNone(matched)
        self.assertEqual("name+domain", matched["_matchReason"])

    def test_renamed_source_matches_via_historical_name(self):
        """源改了名，但旧名还在服务器的 names 里，地址不变 → 应关联。"""
        write_package("a", "新名字", names=["新名字", "旧名字"], domains=["example.com"])
        self.assertIsNotNone(self.match("旧名字", ["example.com"]))

    def test_client_sends_historical_name_set(self):
        """客户端把自己记过的历史名一起发来，任一与服务器名称集合相交即可。"""
        write_package("a", "服务器上的名字", domains=["example.com"])
        matched = self.match("客户端当前名", ["example.com"], names=["客户端当前名", "服务器上的名字"])
        self.assertIsNotNone(matched)

    def test_moved_domain_matches_via_backup_address(self):
        """源换了主域名，旧域名仍在备用地址里，名称不变 → 应关联。"""
        write_package("a", "示例书源", domains=["new.example.org", "old.example.com"], primary="new.example.org")
        self.assertIsNotNone(self.match("示例书源", ["old.example.com"]))

    def test_client_sends_multiple_domains(self):
        write_package("a", "示例书源", domains=["mirror.example.net"])
        self.assertIsNotNone(self.match("示例书源", ["unrelated.example.io", "mirror.example.net"]))

    def test_www_and_scheme_are_normalized(self):
        write_package("a", "示例书源", domains=["example.com"])
        self.assertIsNotNone(self.match("示例书源", ["https://WWW.Example.com/search?q=1"]))

    def test_name_is_normalized(self):
        write_package("a", "示例 书源", domains=["example.com"])
        self.assertIsNotNone(self.match("示例书源", ["example.com"]), "名称比对应忽略空白与大小写")

    def test_association_source_falls_back_to_identity(self):
        """没有地址可比的内容，退化为名称 + 稳定标识，仍要求两项都中。"""
        write_package("a", "关联源", kind="association_source", content_id="youshu")
        matched = packages.find_matching_package(
            "association_source", "关联源", set(), {"youshu"}, {"关联源"},
        )
        self.assertIsNotNone(matched)
        self.assertEqual("name+identity", matched["_matchReason"])

    # ---------- 不应该匹配（防冒用） ----------

    def test_same_name_different_domain_is_not_matched(self):
        """同名但地址完全不重叠：很可能是两个无关的源，不能关联。"""
        write_package("a", "书源", domains=["example.com"])
        self.assertIsNone(self.match("书源", ["totally-different.org"]))

    def test_same_domain_different_name_is_not_matched(self):
        """同一站点上的不同源不能被撞在一起。"""
        write_package("a", "甲书源", domains=["example.com"])
        self.assertIsNone(self.match("乙书源", ["example.com"]))

    def test_identity_alone_is_not_enough(self):
        write_package("a", "甲书源", kind="association_source", content_id="youshu")
        self.assertIsNone(packages.find_matching_package(
            "association_source", "乙书源", set(), {"youshu"}, {"乙书源"},
        ))

    def test_blank_name_never_matches(self):
        write_package("a", "示例书源", domains=["example.com"])
        self.assertIsNone(self.match("", ["example.com"]))

    def test_picks_the_candidate_with_both_signals(self):
        """多个候选时选名称与地址都命中的那个。"""
        write_package("only-name", "示例书源", domains=["other.example.net"])
        write_package("both", "示例书源", domains=["example.com"])
        matched = self.match("示例书源", ["example.com"])
        self.assertEqual("both", matched["packageId"])


class PrimaryDomainTest(unittest.TestCase):
    def test_explicit_primary_wins(self):
        manifest = {"domains": ["a.example.com", "b.example.net"], "primaryDomain": "b.example.net"}
        self.assertEqual("b.example.net", packages.primary_domain(manifest))
        self.assertEqual(["b.example.net", "a.example.com"], packages.ordered_domains(manifest))

    def test_falls_back_to_first_domain(self):
        manifest = {"domains": ["a.example.com", "b.example.net"]}
        self.assertEqual("a.example.com", packages.primary_domain(manifest))

    def test_no_domains(self):
        self.assertEqual("", packages.primary_domain({}))
        self.assertEqual([], packages.ordered_domains({}))

    def test_merge_domains_keeps_old_addresses(self):
        """换域名时旧域名必须保留，否则用旧域名的客户端会失联。"""
        merged, primary = packages.merge_domains(
            ["old.example.com"], ["new.example.org"], primary="new.example.org",
        )
        self.assertEqual("new.example.org", primary)
        self.assertEqual(["new.example.org", "old.example.com"], merged)

    def test_merge_domains_dedupes_and_normalizes(self):
        merged, _ = packages.merge_domains(["https://WWW.Example.com/"], ["example.com", "example.com/x"])
        self.assertEqual(["example.com"], merged)

    def test_merge_names_keeps_old_names(self):
        merged = packages.merge_match_names(["旧名字"], "新名字")
        self.assertIn("旧名字", merged)
        self.assertIn("新名字", merged)

    def test_merge_names_dedupes_by_normalized_form(self):
        merged = packages.merge_match_names(["示例 书源"], "示例书源")
        self.assertEqual(1, len(merged), "归一化后相同的名称只保留一个")


class UploadLinkAccumulationTest(unittest.TestCase):
    """端到端：关联时把新名称与新地址并入服务器清单，但不改动内容。"""

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

    def _manifest(self, package_id="a"):
        return json.loads((runtime.PACKAGE_ROOT / "online_source" / package_id / "manifest.json").read_text(encoding="utf-8"))

    def _upload(self, name, domains, names=(), primary=""):
        payload = json.dumps({"bookSourceName": name}, ensure_ascii=False).encode("utf-8")
        return self.client.post("/v1/packages/upload", headers=module_headers(), json={
            "kind": "online_source",
            "name": name,
            "names": list(names),
            "domains": list(domains),
            "primaryDomain": primary,
            "payload": b64(payload),
            "payloadName": "s.json",
        })

    def test_link_absorbs_new_domain_without_touching_content(self):
        write_package("a", "示例书源", domains=["old.example.com"])
        before = self._manifest()
        data = unwrap(self._upload("示例书源", ["old.example.com", "new.example.org"]))
        self.assertFalse(data["uploaded"], "同一个源不应重新上传")
        self.assertTrue(data["linked"])
        after = self._manifest()
        self.assertIn("new.example.org", after["domains"], "新地址应并入")
        self.assertIn("old.example.com", after["domains"], "旧地址必须保留")
        # 内容与版本不能被模块改写。
        self.assertEqual(before["version"], after["version"])
        self.assertEqual(before["sha256"], after["sha256"])

    def test_link_absorbs_new_name(self):
        """改名后要能关联，前提是至少一边同时知道新旧名。

        模块本地会累积 reamicroSourceNames，所以改名后上报的名称集合里带着旧名，
        与服务器的名称集合相交，从而命中。
        """
        write_package("a", "旧名字", domains=["example.com"])
        self._upload("新名字", ["example.com"], names=["新名字", "旧名字"])
        names = self._manifest()["names"]
        self.assertIn("新名字", names, "新名字应并入")
        self.assertIn("旧名字", names, "旧名字必须保留，否则用旧名的客户端会失联")

    def test_rename_without_shared_name_is_not_linked(self):
        """两边名称集合完全不相交时不关联——否则等于只按地址匹配，同站不同源会撞。"""
        write_package("a", "旧名字", domains=["example.com"])
        data = unwrap(self._upload("全新名字", ["example.com"], names=["全新名字"]))
        self.assertTrue(data["uploaded"], "应新建而不是接管别人的包")
        self.assertNotEqual("a", data["package"]["packageId"])

    def test_module_cannot_seize_primary_domain(self):
        """主地址由服务器管理员决定，模块关联时不能抢占。"""
        write_package("a", "示例书源", domains=["main.example.com", "x.example.net"], primary="main.example.com")
        self._upload("示例书源", ["x.example.net"], primary="x.example.net")
        self.assertEqual("main.example.com", self._manifest()["primaryDomain"])

    def test_response_reports_match_reason(self):
        write_package("a", "示例书源", domains=["example.com"])
        body = self._upload("示例书源", ["example.com"]).json()
        self.assertIn("名称与访问地址", body["message"])
        self.assertEqual("name+domain", body["data"]["package"]["matchReason"])

    def test_response_exposes_ordered_domains_and_primary(self):
        write_package("a", "示例书源", domains=["backup.example.net", "main.example.com"], primary="main.example.com")
        summary = unwrap(self._upload("示例书源", ["main.example.com"]))["package"]
        self.assertEqual("main.example.com", summary["primaryDomain"])
        self.assertEqual("main.example.com", summary["domains"][0])

    def test_unrelated_source_creates_new_package(self):
        write_package("a", "示例书源", domains=["example.com"])
        data = unwrap(self._upload("另一个书源", ["another.example.org"]))
        self.assertTrue(data["uploaded"])
        self.assertNotEqual("a", data["package"]["packageId"])

    def test_same_name_other_domain_creates_new_package(self):
        """同名但地址不重叠 → 视为不同源，新建而不是覆盖。"""
        write_package("a", "书源", domains=["example.com"])
        data = unwrap(self._upload("书源", ["different.example.org"]))
        self.assertTrue(data["uploaded"])
        self.assertNotEqual("a", data["package"]["packageId"])
        self.assertEqual("1.0.0", self._manifest("a")["version"], "原包不能被动到")

    def test_match_endpoint_uses_same_rule(self):
        write_package("a", "示例书源", domains=["example.com"])
        response = self.client.post("/v1/packages/match", headers=module_headers(), json={"items": [
            {"kind": "online_source", "name": "示例书源", "domains": ["example.com"]},
            {"kind": "online_source", "name": "示例书源", "domains": ["nope.example.io"]},
            {"kind": "online_source", "name": "无关", "domains": ["example.com"]},
        ]})
        data = unwrap(response)
        self.assertEqual([True, False, False], [item["matched"] for item in data["items"]])
        self.assertEqual(1, data["matched"])


if __name__ == "__main__":
    unittest.main()
