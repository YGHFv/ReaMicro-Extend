"""后台样式统一与列表分页。

两类历史问题：

1. 状态徽标把原始枚举值当 CSS 类名（`status-{raw}`），CSS 里没定义的值就渲染成无样式的
   灰块——`scheduled`、`running`、`ok`、`slow`、`unreachable`、`blocked` 全都中招，
   而且不报错、很难发现。现在统一走 `labels.status_tone` 归到五种语义色调。
2. 审计页把整个日志文件 `read_text()` 进内存再渲染最近 800 条；内容列表一次渲染全部条目。
   现在都分页，审计还改为从文件尾部按块回读。
"""
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import labels, runtime
from app.admin import paging
from app.admin.style import ADMIN_AUTH_STYLE, ADMIN_STYLE
from tests.conftest_support import (
    admin_auth,
    base_config,
    client,
    isolate,
    seed_credential,
    seed_package,
    seed_task,
)

TONE_CLASSES = {"ok", "warn", "bad", "idle", "info"}


class StatusToneTest(unittest.TestCase):
    def test_every_tone_has_css(self):
        for tone in TONE_CLASSES:
            self.assertIn(f".tone-{tone}{{", ADMIN_STYLE, f"tone-{tone} 缺少 CSS")

    def test_known_statuses_map_to_real_tones(self):
        """所有会出现在界面上的状态值都必须映射到有 CSS 的色调。"""
        statuses = [
            # 内容包
            "published", "draft", "testing", "unpublished",
            # 任务与结果
            "success", "failed", "running", "scheduled", "paused", "cancelled", "skipped",
            # 凭据健康
            "valid", "invalid", "unverified",
            # 在线
            "online", "offline",
            # 书源可用性
            "ok", "slow", "unreachable", "blocked",
            # 渠道
            "stable", "beta", "nightly",
        ]
        for status in statuses:
            tone = labels.status_tone(status)
            self.assertIn(tone, TONE_CLASSES, f"{status} 映射到未知色调 {tone}")

    def test_unknown_status_falls_back_to_neutral(self):
        """新增枚举值不能变成无样式的灰块。"""
        for unknown in ("brand_new_state", "", None, "什么都不是"):
            self.assertIn(labels.status_tone(unknown), TONE_CLASSES)

    def test_semantically_opposite_statuses_differ(self):
        self.assertNotEqual(labels.status_tone("success"), labels.status_tone("failed"))
        self.assertNotEqual(labels.status_tone("ok"), labels.status_tone("unreachable"))
        self.assertNotEqual(labels.status_tone("online"), labels.status_tone("offline"))
        self.assertNotEqual(labels.status_tone("published"), labels.status_tone("draft"))

    def test_badge_escapes_text(self):
        badge = labels.status_badge("failed", "<script>x</script>")
        self.assertIn("tone-bad", badge)
        self.assertNotIn("<script>", badge)


class StyleConsistencyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        seed_package()
        seed_credential()
        seed_task()
        self.client = client()

    def tearDown(self):
        self.temp.cleanup()

    def _pages(self) -> list[str]:
        return [
            "/admin", "/admin/content", "/admin/content/online_source", "/admin/users",
            "/admin/tasks", "/admin/settings", "/admin/security", "/admin/audit",
            "/admin/packages/online_source/example-com/edit",
            "/admin/packages/online_source/example-com/preview",
            "/admin/packages/online_source/example-com/history",
        ]

    def test_no_inline_styles_anywhere(self):
        """内联样式会让同一种间距在各处漂移，统一走语义类。"""
        for path in self._pages():
            page = self.client.get(path, auth=admin_auth()).text
            self.assertNotIn("style='", page, f"{path} 仍有内联样式")

    def test_no_duplicate_or_empty_class_attributes(self):
        for path in self._pages():
            page = self.client.get(path, auth=admin_auth()).text
            self.assertNotIn("class=''", page, f"{path} 有空 class")
            self.assertIsNone(
                re.search(r"<\w+[^>]*class='[^']*'[^>]*class='", page),
                f"{path} 有重复 class 属性",
            )

    def test_no_raw_status_class_names(self):
        """不能再出现 status-xxx 这种直接用枚举值当类名的写法。"""
        for path in self._pages():
            page = self.client.get(path, auth=admin_auth()).text
            self.assertIsNone(re.search(r"class='[^']*\bstatus-\w", page), f"{path} 残留 status- 类名")

    def test_every_rendered_tone_has_css(self):
        for path in self._pages():
            page = self.client.get(path, auth=admin_auth()).text
            for tone in set(re.findall(r"\btone-(\w+)", page)):
                self.assertIn(tone, TONE_CLASSES, f"{path} 用了未定义的色调 tone-{tone}")

    def test_auth_pages_share_tokens_with_admin(self):
        """登录页与后台同源配色，不再各写一套。"""
        for token in ("--brand:", "--line:", "--ink:"):
            self.assertIn(token, ADMIN_STYLE)
            self.assertIn(token, ADMIN_AUTH_STYLE)
        page = self.client.get("/admin/login").text
        self.assertIn("auth-card", page)
        self.assertNotIn("style='", page)

    def test_dark_mode_and_reduced_motion_supported(self):
        self.assertIn("prefers-color-scheme: dark", ADMIN_STYLE)
        self.assertIn("prefers-reduced-motion", ADMIN_STYLE)

    def test_focus_states_present(self):
        """键盘操作要看得见焦点。"""
        self.assertIn(":focus-visible", ADMIN_STYLE)
        self.assertIn("--focus:", ADMIN_STYLE)

    def test_stats_grid_is_auto_fit(self):
        """卡片数量变化时不该再靠内联样式覆盖列数。"""
        self.assertIn("auto-fit", ADMIN_STYLE)


class PaginationTest(unittest.TestCase):
    def test_paginate_slices_and_reports(self):
        items = list(range(130))
        page, info = paging.paginate(items, 1, page_size=50)
        self.assertEqual(list(range(50)), page)
        self.assertEqual({"page": 1, "pages": 3, "total": 130, "start": 1, "end": 50}, {
            key: info[key] for key in ("page", "pages", "total", "start", "end")
        })
        last, info = paging.paginate(items, 3, page_size=50)
        self.assertEqual(30, len(last))
        self.assertEqual(130, info["end"])

    def test_out_of_range_page_falls_back_to_last(self):
        """删数据后停在末页仍要看到内容，而不是空列表。"""
        page, info = paging.paginate(list(range(10)), 99, page_size=5)
        self.assertEqual(2, info["page"])
        self.assertEqual([5, 6, 7, 8, 9], page)

    def test_invalid_page_values_are_tolerated(self):
        for bad in ("abc", None, 0, -3, ""):
            _, info = paging.paginate(list(range(10)), bad, page_size=5)
            self.assertEqual(1, info["page"], f"{bad!r} 应回落到第 1 页")

    def test_page_size_is_capped(self):
        self.assertEqual(paging.MAX_PAGE_SIZE, paging.normalize_page_size(99999))
        self.assertEqual(1, paging.normalize_page_size(0))

    def test_empty_list(self):
        page, info = paging.paginate([], 1)
        self.assertEqual([], page)
        self.assertEqual(0, info["total"])
        self.assertIn("暂无记录", paging.pager_html(info, "/admin/audit"))

    def test_single_page_hides_navigation(self):
        _, info = paging.paginate(list(range(5)), 1, page_size=50)
        markup = paging.pager_html(info, "/admin/audit")
        self.assertIn("共 5 条", markup)
        self.assertNotIn("下一页", markup, "只有一页时不该显示翻页按钮")

    def test_pager_preserves_query_and_escapes(self):
        _, info = paging.paginate(list(range(130)), 2, page_size=50)
        markup = paging.pager_html(info, "/admin/audit", {"q": "a&b"})
        self.assertIn("q=a&amp;b", markup, "搜索词要带进翻页链接并转义")
        self.assertIn("page=3", markup)
        # 第 2 页（共 3 页）四个方向都可用，不该出现禁用项。
        self.assertNotIn("aria-disabled", markup)

    def test_pager_disables_boundary_buttons(self):
        _, first = paging.paginate(list(range(130)), 1, page_size=50)
        _, last = paging.paginate(list(range(130)), 3, page_size=50)
        self.assertIn("aria-disabled", paging.pager_html(first, "/admin/audit"), "第 1 页应禁用首页与上一页")
        self.assertIn("aria-disabled", paging.pager_html(last, "/admin/audit"), "末页应禁用下一页与末页")


class AuditTailReadTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        runtime.AUDIT_ROOT.mkdir(parents=True, exist_ok=True)
        self.client = client()

    def tearDown(self):
        self.temp.cleanup()

    def _write_events(self, count: int) -> None:
        lines = [
            json.dumps({
                "at": 1750000000000 + index,
                "actor": "admin:owner",
                "action": "package_uploaded",
                "success": index % 7 != 0,
                "requestId": f"req_{index}",
                "metadata": {"kind": "online_source"},
            }, ensure_ascii=False)
            for index in range(count)
        ]
        runtime.AUDIT_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def test_tail_read_returns_last_lines(self):
        runtime.AUDIT_PATH.write_text("\n".join(f"line-{i}" for i in range(5000)) + "\n", encoding="utf-8")
        tail = paging.read_tail_lines(runtime.AUDIT_PATH, 10)
        self.assertEqual(10, len(tail))
        self.assertEqual("line-4999", tail[-1])
        self.assertEqual("line-4990", tail[0])

    def test_tail_read_across_chunk_boundary(self):
        """按块回读时不能把边界那行截断成半行。"""
        runtime.AUDIT_PATH.write_text("\n".join(f"{i}-{'x' * 200}" for i in range(2000)) + "\n", encoding="utf-8")
        tail = paging.read_tail_lines(runtime.AUDIT_PATH, 500, chunk_size=1024)
        self.assertEqual(500, len(tail))
        for line in tail:
            self.assertRegex(line, r"^\d+-x+$", "出现被截断的行")

    def test_tail_read_handles_small_and_missing_files(self):
        self.assertEqual([], paging.read_tail_lines(runtime.AUDIT_ROOT / "nope.jsonl", 10))
        runtime.AUDIT_PATH.write_text("only\n", encoding="utf-8")
        self.assertEqual(["only"], paging.read_tail_lines(runtime.AUDIT_PATH, 10))
        runtime.AUDIT_PATH.write_text("", encoding="utf-8")
        self.assertEqual([], paging.read_tail_lines(runtime.AUDIT_PATH, 10))

    def test_audit_page_paginates(self):
        self._write_events(130)
        first = self.client.get("/admin/audit", auth=admin_auth()).text
        third = self.client.get("/admin/audit?page=3", auth=admin_auth()).text
        self.assertEqual(50, first.count("<tr><td>"))
        self.assertEqual(30, third.count("<tr><td>"))
        self.assertIn("共 130 条", first)
        self.assertIn("pager", first)

    def test_audit_search_is_reflected_in_pager_links(self):
        self._write_events(130)
        page = self.client.get("/admin/audit", params={"q": "上传内容包"}, auth=admin_auth()).text
        self.assertIn("q=", page)
        self.assertIn("清除", page)

    def test_audit_page_out_of_range_shows_last_page(self):
        self._write_events(60)
        page = self.client.get("/admin/audit?page=99", auth=admin_auth()).text
        self.assertIn("共 60 条", page)
        self.assertEqual(10, page.count("<tr><td>"))


def count_body_rows(page: str) -> int:
    """统计表格数据行。不能用 `<tr><td>` 计数——首列可能是勾选框单元格。"""
    body = re.search(r"<tbody>(.*?)</tbody>", page, re.S)
    return body.group(1).count("<tr>") if body else 0


class ContentListPaginationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        for index in range(120):
            seed_package(package_id=f"src-{index:03d}", name=f"书源 {index}")
        self.client = client()

    def tearDown(self):
        self.temp.cleanup()

    def test_content_list_paginates(self):
        first = self.client.get("/admin/content/online_source", auth=admin_auth()).text
        third = self.client.get("/admin/content/online_source?page=3", auth=admin_auth()).text
        self.assertEqual(50, count_body_rows(first))
        self.assertEqual(20, count_body_rows(third))
        self.assertIn("共 120 条", first)

    def test_search_and_page_combine(self):
        page = self.client.get("/admin/content/online_source", params={"q": "书源 1"}, auth=admin_auth()).text
        # "书源 1"、"书源 1x"、"书源 1xx" 都会命中，条数应少于全部。
        self.assertLess(count_body_rows(page), 120)
        self.assertIn("q=", page)


if __name__ == "__main__":
    unittest.main()
