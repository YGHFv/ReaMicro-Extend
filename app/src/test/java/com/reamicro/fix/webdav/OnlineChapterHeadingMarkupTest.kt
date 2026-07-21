package com.reamicro.fix.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineChapterHeadingMarkupTest {
    @Test
    fun `split heading uses independent sibling elements`() {
        val html = OnlineChapterHeadingMarkup.split("第1章", "奈奈莉·亚希")

        assertEquals(
            "<div class=\"te-chapter-heading\"><div class=\"te-chapter-number\">第1章</div>" +
                "<h1 class=\"te-chapter-title\">奈奈莉·亚希</h1></div>",
            html,
        )
        assertFalse(html.contains("<h1 class=\"te-chapter-title\"><span"))
        assertTrue(html.indexOf("te-chapter-number") < html.indexOf("te-chapter-title"))
    }

    @Test
    fun `single heading keeps title style`() {
        assertEquals(
            "<h1 class=\"te-chapter-title\">序章</h1>",
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
}
