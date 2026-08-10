package com.reamicro.fix.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineChapterHeadingMarkupTest {
    @Test
    fun `split heading uses standard interface spans`() {
        val html = OnlineChapterHeadingMarkup.split("第1章", "奈奈莉·亚希")

        assertEquals(
            "<h1 class=\"te-chapter-title\"><span class=\"te-chapter-number\">第1章</span>" +
                "<span class=\"te-chapter-name\">奈奈莉·亚希</span></h1>",
            html,
        )
        assertFalse(html.contains("te-chapter-heading"))
        assertTrue(html.indexOf("te-chapter-number") < html.indexOf("te-chapter-name"))
    }

    @Test
    fun `single heading wraps the title in a name span`() {
        assertEquals(
            "<h1 class=\"te-chapter-title\"><span class=\"te-chapter-name\">序章</span></h1>",
            OnlineChapterHeadingMarkup.single("序章"),
        )
    }

    @Test
    fun `migrates legacy html break heading`() {
        assertEquals(
            OnlineChapterHeadingMarkup.split("第1章", "奈奈莉·亚希"),
            OnlineChapterHeadingMarkup.migrateSplit("第1章<br>奈奈莉·亚希"),
        )
    }

    @Test
    fun `migrates legacy number span heading`() {
        assertEquals(
            OnlineChapterHeadingMarkup.split("第2章", "抵达"),
            OnlineChapterHeadingMarkup.migrateSplit(
                "<span class=\"te-chapter-number\">第2章</span><br />抵达",
            ),
        )
    }

    @Test
    fun `already migrated heading is left alone`() {
        assertEquals(null, OnlineChapterHeadingMarkup.migrateSplit("<span class=\"te-chapter-name\">抵达</span>"))
    }

    @Test
    fun `migrates legacy heading block into the new h1`() {
        val migrated = OnlineChapterHeadingMarkup.migrateLegacyHeadingBlock(
            "<body><div class=\"te-chapter-heading\">" +
                "<div class=\"te-chapter-number\">第3章</div>" +
                "<h1 class=\"te-chapter-title\">计划不如变化</h1>" +
                "</div><p>正文</p></body>",
        )

        assertEquals(
            "<body>${OnlineChapterHeadingMarkup.split("第3章", "计划不如变化")}<p>正文</p></body>",
            migrated,
        )
    }

    @Test
    fun `migrates legacy heading block without a number`() {
        val migrated = OnlineChapterHeadingMarkup.migrateLegacyHeadingBlock(
            "<div class=\"te-chapter-heading\"><h1 class=\"te-chapter-title\">尾声</h1></div>",
        )

        assertEquals(OnlineChapterHeadingMarkup.single("尾声"), migrated)
    }

    @Test
    fun `legacy heading migration is idempotent on new markup`() {
        val current = OnlineChapterHeadingMarkup.split("第1章", "抵达")

        assertEquals(current, OnlineChapterHeadingMarkup.migrateLegacyHeadingBlock(current))
    }
}
