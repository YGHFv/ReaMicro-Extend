"""书源规则级检测与 JSONPath 移植。

`source_check` 只验证域名可达，但站点最常见的失效方式是**改版**：域名还在、首页 200，
搜索接口字段变了，`ruleSearch` 取不到任何东西。那种源在列表里显示"可用"，实际搜不出书。

`app/jsonpath.py` 是模块端 `OnlineJsonPathCompat.kt` 的移植，语义必须与模块一致——
两边解析结果不同，检测结论就没有意义，所以这里逐条锁语法。
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import jsonpath as jp
from app import rule_check as rc
from app import runtime
from app.source_check import STATUS_BLOCKED, STATUS_SKIPPED, STATUS_UNREACHABLE
from tests.conftest_support import admin_auth, admin_csrf, base_config, client, isolate

DOC = {
    "code": 0,
    "msg": "ok",
    "data": {
        "books": [
            {"name": "书甲", "author": "作者甲", "tags": ["武侠", "长篇"], "hot": True, "score": 9.0},
            {"name": "书乙", "author": "作者乙", "tags": ["科幻"], "hot": False, "score": 8.5},
        ],
        "total": 2,
    },
}


class JsonPathPortTest(unittest.TestCase):
    """逐条锁定与模块端一致的语法语义。"""

    def test_field_paths(self):
        self.assertEqual([DOC["data"]["books"]], jp.values(DOC, "$.data.books"))
        self.assertEqual([DOC["data"]["books"]], jp.values(DOC, "data.books"))
        self.assertEqual([DOC["data"]["books"]], jp.values(DOC, ".data.books"))
        self.assertEqual([DOC], jp.values(DOC, "$"))

    def test_array_selectors(self):
        self.assertEqual(2, len(jp.values(DOC, "$.data.books[*]")))
        self.assertEqual([DOC["data"]["books"][0]], jp.values(DOC, "$.data.books[0]"))
        self.assertEqual([], jp.values(DOC, "$.data.books[9]"), "越界下标应返回空而不是报错")

    def test_field_across_array_elements(self):
        self.assertEqual(["书甲", "书乙"], jp.values(DOC, "data.books[*].name"))

    def test_wildcard_children(self):
        self.assertEqual(3, len(jp.values(DOC, "$.data.books[*].tags[*]")))

    def test_recursive_descent_continues_remaining_path(self):
        """`$..name` 之后的剩余路径必须继续执行，不能被截断。"""
        self.assertEqual(["书甲", "书乙"], jp.values(DOC, "$..name"))
        self.assertEqual(3, len(jp.values(DOC, "$..tags[*]")))
        self.assertEqual(["书甲"], jp.values(DOC, "$..books[0].name"))

    def test_or_takes_first_non_empty(self):
        self.assertEqual(["ok"], jp.values(DOC, "$.nope||$.msg"))
        self.assertEqual(["ok"], jp.values(DOC, "$.msg||$.data.total"), "前一分支有值就不取后面")

    def test_and_merges_all(self):
        self.assertEqual(["ok", 2], jp.values(DOC, "$.msg&&$.data.total"))

    def test_missing_and_blank_rules(self):
        self.assertEqual([], jp.values(DOC, ""))
        self.assertEqual([], jp.values(None, "$.a"))
        self.assertEqual([], jp.values(DOC, "$.does.not.exist"))

    def test_primitive_formatting(self):
        self.assertEqual("3", jp.primitive(3.0), "整数不要显示成 3.0")
        self.assertEqual("2.5", jp.primitive(2.5))
        self.assertEqual("true", jp.primitive(True))
        self.assertEqual("", jp.primitive(None))
        self.assertEqual("文本", jp.primitive("  文本  "))

    def test_candidate_roots_probe_wrappers(self):
        """书源常把列表埋在固定的几个包裹键里，取值要逐层试。

        包裹键是固定的 data / result / book / chapter / rows / ret_data
        （与模块端一致），业务字段名如 books 不在其中——它靠"先试 data 这一层，
        再在该层上跑规则"命中。
        """
        roots = jp.candidate_roots(DOC)
        self.assertIn(DOC, roots)
        self.assertIn(DOC["data"], roots, "data 层要作为候选根")

        nested = {"data": {"rows": [{"name": "甲"}]}}
        self.assertIn(nested["data"]["rows"], jp.candidate_roots(nested), "data 下的包裹键也要试")

    def test_rule_values_finds_list_through_wrapper(self):
        self.assertEqual(2, len(jp.rule_values(DOC, "books[*]")), "规则没写全路径也应能命中")

    def test_rule_values_strips_script_segment(self):
        """规则里的 <js> / @js: 段截掉，脚本不执行。"""
        self.assertEqual(2, len(jp.rule_values(DOC, "$.data.books[*]<js>foo()</js>")))
        self.assertEqual(2, len(jp.rule_values(DOC, "$.data.books[*]@js:bar()")))
        self.assertEqual([], jp.rule_values(DOC, "@js:onlyScript()"))

    def test_expansion_is_bounded(self):
        """畸形规则不能在大响应上无限展开。"""
        wide = {"items": [{"n": index} for index in range(20000)]}
        self.assertLessEqual(len(jp.values(wide, "$..n")), jp.MAX_VALUES)


class SearchUrlParseTest(unittest.TestCase):
    def test_plain_url(self):
        spec = rc.parse_search_url("https://example.com/s?k={{key}}")
        self.assertEqual("https://example.com/s?k={{key}}", spec["url"])
        self.assertEqual("GET", spec["method"])

    def test_options_block(self):
        spec = rc.parse_search_url(
            'https://example.com/s,{"method":"POST","body":"searchkey={{key}}","charset":"gbk",'
            '"headers":{"Referer":"https://example.com"}}'
        )
        self.assertEqual("https://example.com/s", spec["url"])
        self.assertEqual("POST", spec["method"])
        self.assertEqual("gbk", spec["charset"])
        self.assertEqual("https://example.com", spec["headers"]["Referer"])

    def test_broken_options_falls_back_to_get(self):
        """附加参数写坏不该让整个源被判失效。"""
        spec = rc.parse_search_url("https://example.com/s,{not json}")
        self.assertEqual("https://example.com/s", spec["url"])
        self.assertEqual("GET", spec["method"])

    def test_script_url_is_unsupported(self):
        for raw in ("@js:buildUrl()", "<js>buildUrl()</js>"):
            self.assertIn("error", rc.parse_search_url(raw))

    def test_blank_url(self):
        self.assertIn("error", rc.parse_search_url(""))

    def test_only_first_line_used(self):
        spec = rc.parse_search_url("https://example.com/a\nhttps://example.com/b")
        self.assertEqual("https://example.com/a", spec["url"])

    def test_template_substitution_and_encoding(self):
        rendered = rc.apply_template("https://e.com/s?k={{key}}&p={{page}}", "剑 来", "https://e.com")
        self.assertIn("p=1", rendered)
        self.assertNotIn("{{", rendered)
        self.assertIn("%", rendered, "关键词要 URL 编码")

    def test_unknown_placeholders_are_removed(self):
        """留着未识别的占位符会让 URL 非法，去掉至少还能发出请求。"""
        self.assertEqual("https://e.com/s?x=", rc.apply_template("https://e.com/s?x={{unknown}}", "k", "https://e.com"))


class ExtractResultsTest(unittest.TestCase):
    def test_json_with_rules(self):
        body = json.dumps({"data": {"books": [{"bookName": "剑来"}, {"bookName": "剑气长城"}]}})
        rules = json.dumps({"bookList": "$.data.books[*]", "name": "$.bookName"})
        found = rc.extract_results(body, rules)
        self.assertEqual("json", found["kind"])
        self.assertEqual(["剑来", "剑气长城"], found["names"])

    def test_json_falls_back_to_common_name_keys(self):
        """规则没写 name 时试常见字段，尽量给出有意义的判定。"""
        body = json.dumps({"data": {"books": [{"title": "剑来"}]}})
        found = rc.extract_results(body, json.dumps({"bookList": "$.data.books[*]"}))
        self.assertEqual(["剑来"], found["names"])

    def test_json_rules_missing_target(self):
        body = json.dumps({"result": {"list": [{"title": "剑来"}]}})
        rules = json.dumps({"bookList": "$.data.books[*]", "name": "$.bookName"})
        self.assertEqual([], rc.extract_results(body, rules)["names"])

    def test_html_anchor_fallback(self):
        body = "<div><a href='/b/1'>剑来</a><a href='/b/2'>剑气长城</a></div>"
        found = rc.extract_results(body, "")
        self.assertEqual("html", found["kind"])
        self.assertIn("剑来", found["names"])

    def test_script_rules_reported(self):
        found = rc.extract_results("{}", json.dumps({"bookList": "@js:x()", "name": "$.n"}))
        self.assertEqual("script", found["kind"])

    def test_invalid_json_body_uses_html_path(self):
        found = rc.extract_results("<html><a href='/x'>书</a></html>", json.dumps({"bookList": "$.a"}))
        self.assertEqual("html", found["kind"])


class RuleCheckClassificationTest(unittest.TestCase):
    """四类判定：规则可用、规则失效、无法检测、不可达。"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        self.original_fetch = rc.fetch
        self.manifest = {"domains": ["example.com"]}
        self.payload = {
            "bookSourceUrl": "https://example.com",
            "searchUrl": "https://example.com/s?k={{key}}",
            "ruleSearch": json.dumps({"bookList": "$.data.books[*]", "name": "$.bookName"}),
        }

    def tearDown(self):
        rc.fetch = self.original_fetch
        self.temp.cleanup()

    def _stub_body(self, body: str, http_status: int = 200) -> None:
        rc.fetch = lambda spec, base, query, timeout: {
            "status": "fetched", "httpStatus": http_status, "body": body, "url": "https://example.com/s",
        }

    def test_rules_ok(self):
        self._stub_body(json.dumps({"data": {"books": [{"bookName": "剑来"}, {"bookName": "剑气长城"}]}}))
        report = rc.check_source_rules(self.manifest, self.payload)
        self.assertEqual(rc.STATUS_RULES_OK, report["status"])
        self.assertEqual(2, report["matched"])
        self.assertIn("剑来", report["names"])

    def test_rules_stale_when_site_changed(self):
        """站点还活着但字段名变了——这正是地址检测发现不了的失效。"""
        self._stub_body(json.dumps({"result": {"list": [{"title": "剑来"}]}}))
        report = rc.check_source_rules(self.manifest, self.payload)
        self.assertEqual(rc.STATUS_RULES_STALE, report["status"])
        self.assertIn("可能已改版", report["message"])

    def test_script_rules_unsupported(self):
        payload = {**self.payload, "ruleSearch": json.dumps({"bookList": "@js:f()", "name": "$.n"})}
        report = rc.check_source_rules(self.manifest, payload)
        self.assertEqual(rc.STATUS_UNSUPPORTED, report["status"])

    def test_script_search_url_unsupported(self):
        report = rc.check_source_rules(self.manifest, {**self.payload, "searchUrl": "@js:buildUrl()"})
        self.assertEqual(rc.STATUS_UNSUPPORTED, report["status"])

    def test_missing_base_url_unsupported(self):
        report = rc.check_source_rules({}, {"searchUrl": "/s?k={{key}}", "ruleSearch": "{}"})
        self.assertEqual(rc.STATUS_UNSUPPORTED, report["status"])

    def test_unsupported_is_not_treated_as_failure(self):
        """无法检测不等于失效，色调上必须与"规则失效"区分。"""
        from app.labels import status_tone

        self.assertNotEqual(status_tone(rc.STATUS_UNSUPPORTED), status_tone(rc.STATUS_RULES_STALE))

    def test_private_address_blocked(self):
        """检测目标来自上传内容，必须挡住内网探测。"""
        report = rc.check_source_rules(
            {"domains": ["127.0.0.1"]},
            {"bookSourceUrl": "http://127.0.0.1", "searchUrl": "http://127.0.0.1/s?k={{key}}",
             "ruleSearch": json.dumps({"bookList": "$.a", "name": "$.b"})},
        )
        self.assertEqual(STATUS_BLOCKED, report["status"])

    def test_html_source_without_rules_is_unsupported(self):
        """HTML 源没写 ruleSearch 时不能断言规则失效。"""
        self._stub_body("<html><body>没有链接</body></html>")
        report = rc.check_source_rules(self.manifest, {**self.payload, "ruleSearch": ""})
        self.assertEqual(rc.STATUS_UNSUPPORTED, report["status"])


class PackageRuleCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        self.original_fetch = rc.fetch
        self.client = client()
        self.csrf = admin_csrf()
        self._write_source()

    def tearDown(self):
        rc.fetch = self.original_fetch
        self.temp.cleanup()

    def _write_source(self, package_id: str = "example-com") -> None:
        directory = runtime.PACKAGE_ROOT / "online_source" / package_id
        directory.mkdir(parents=True, exist_ok=True)
        payload = json.dumps({
            "bookSourceName": "示例书源",
            "bookSourceUrl": "https://example.com",
            "searchUrl": "https://example.com/s?k={{key}}",
            "ruleSearch": {"bookList": "$.data.books[*]", "name": "$.bookName"},
        }, ensure_ascii=False)
        (directory / "source.json").write_text(payload, encoding="utf-8")
        (directory / "manifest.json").write_text(json.dumps({
            "packageId": package_id, "kind": "online_source", "version": "1.0.0",
            "name": "示例书源", "domains": ["example.com"], "primaryDomain": "example.com",
            "status": "published", "payload": "source.json", "sha256": "0" * 64,
        }, ensure_ascii=False), encoding="utf-8")

    def _manifest(self, package_id: str = "example-com") -> dict:
        path = runtime.PACKAGE_ROOT / "online_source" / package_id / "manifest.json"
        return json.loads(path.read_text(encoding="utf-8"))

    def test_check_writes_result_to_manifest(self):
        rc.fetch = lambda *args, **kwargs: {
            "status": "fetched", "httpStatus": 200,
            "body": json.dumps({"data": {"books": [{"bookName": "剑来"}]}}), "url": "x",
        }
        report = rc.check_package_rules("online_source", "example-com")
        self.assertEqual(rc.STATUS_RULES_OK, report["status"])
        stored = self._manifest()["ruleCheck"]
        self.assertEqual(rc.STATUS_RULES_OK, stored["status"])
        self.assertGreater(stored["checkedAt"], 0)
        self.assertEqual(["剑来"], stored["samples"])

    def test_non_source_kinds_unsupported(self):
        report = rc.check_package_rules("theme", "whatever")
        self.assertEqual(STATUS_SKIPPED, report["status"], "不存在的包先报不存在")
        directory = runtime.PACKAGE_ROOT / "theme" / "t1"
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "manifest.json").write_text(json.dumps({"packageId": "t1", "kind": "theme"}), encoding="utf-8")
        self.assertEqual(rc.STATUS_UNSUPPORTED, rc.check_package_rules("theme", "t1")["status"])

    def test_missing_package_reported(self):
        report = rc.check_package_rules("online_source", "nope")
        self.assertEqual(STATUS_SKIPPED, report["status"])
        self.assertIn("不存在", report["message"])

    def test_stored_rule_check_defaults(self):
        self.assertEqual(STATUS_SKIPPED, rc.stored_rule_check({})["status"])
        self.assertEqual(STATUS_SKIPPED, rc.stored_rule_check({"ruleCheck": "坏数据"})["status"])

    def test_list_shows_rule_column(self):
        page = self.client.get("/admin/content/online_source", auth=admin_auth()).text
        self.assertIn("规则可用性", page)
        self.assertIn("从未检测", page)
        self.assertIn("check-rules", page)

    def test_route_checks_single_package(self):
        rc.fetch = lambda *args, **kwargs: {
            "status": "fetched", "httpStatus": 200,
            "body": json.dumps({"data": {"books": [{"bookName": "剑来"}]}}), "url": "x",
        }
        response = self.client.post(
            "/admin/packages/online_source/example-com/check-rules",
            auth=admin_auth(),
            data={"csrf_token": self.csrf},
        )
        self.assertEqual(200, response.status_code, response.text[:300])
        self.assertIn("规则可用", response.text)

    def test_route_accepts_custom_query(self):
        seen = {}

        def fake(spec, base, query, timeout):
            seen["query"] = query
            return {"status": "fetched", "httpStatus": 200, "body": json.dumps({"data": {"books": []}}), "url": "x"}

        rc.fetch = fake
        self.client.post(
            "/admin/packages/online_source/example-com/check-rules",
            auth=admin_auth(),
            data={"csrf_token": self.csrf, "query": "长安"},
        )
        self.assertEqual("长安", seen["query"])

    def test_batch_route(self):
        self._write_source("second-com")
        rc.fetch = lambda *args, **kwargs: {
            "status": "fetched", "httpStatus": 200,
            "body": json.dumps({"data": {"books": [{"bookName": "剑来"}]}}), "url": "x",
        }
        response = self.client.post(
            "/admin/content/online_source/check-rules-all",
            auth=admin_auth(),
            data={"csrf_token": self.csrf},
        )
        self.assertEqual(200, response.status_code, response.text[:300])
        self.assertIn("规则可用 2 个", response.text)
        self.assertIn("ruleCheck", json.dumps(self._manifest("second-com"), ensure_ascii=False))

    def test_batch_requires_csrf_and_valid_kind(self):
        denied = self.client.post(
            "/admin/content/online_source/check-rules-all",
            auth=admin_auth(),
            data={},
        )
        self.assertEqual(403, denied.status_code)
        unknown = self.client.post(
            "/admin/content/not_a_kind/check-rules-all",
            auth=admin_auth(),
            headers={"Accept": "application/json"},
            data={"csrf_token": self.csrf},
        )
        self.assertEqual(404, unknown.status_code)

    def test_audit_records_batch_rule_check(self):
        rc.fetch = lambda *args, **kwargs: {
            "status": "fetched", "httpStatus": 200, "body": json.dumps({"data": {"books": []}}), "url": "x",
        }
        self.client.post(
            "/admin/content/online_source/check-rules-all",
            auth=admin_auth(),
            data={"csrf_token": self.csrf},
        )
        self.assertIn("source_rules_checked", runtime.AUDIT_PATH.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
