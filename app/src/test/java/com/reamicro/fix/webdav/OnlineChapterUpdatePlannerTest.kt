package com.reamicro.fix.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineChapterUpdatePlannerTest {
    @Test
    fun `accepts arbitrary volume name from source volume flag`() {
        assertTrue(
            OnlineChapterUpdatePlanner.isVolumeNode(
                explicitVolume = true,
                nodeType = "",
                title = "星海纪元",
                hasChapterUrl = true,
            ),
        )
    }

    @Test
    fun `accepts arbitrary volume name from structural directory node`() {
        assertTrue(
            OnlineChapterUpdatePlanner.isVolumeNode(
                explicitVolume = null,
                nodeType = "",
                title = "星海纪元",
                hasChapterUrl = false,
            ),
        )
    }

    @Test
    fun `accepts arbitrary volume name from generic node type`() {
        assertTrue(
            OnlineChapterUpdatePlanner.isVolumeNode(
                explicitVolume = null,
                nodeType = "group",
                title = "星海纪元",
                hasChapterUrl = true,
            ),
        )
    }

    @Test
    fun `does not infer volume from arbitrary chapter title with url`() {
        assertFalse(
            OnlineChapterUpdatePlanner.isVolumeNode(
                explicitVolume = null,
                nodeType = "chapter",
                title = "星海纪元",
                hasChapterUrl = true,
            ),
        )
    }

    @Test
    fun `adds chapter from newly opened arbitrary named volume`() {
        val stored = listOf(
            stored("chapter:1", "启程", "旧日航线", "Text/chapter_0001.xhtml"),
        )
        val remote = listOf(
            remote("chapter:1", "启程", "旧日航线"),
            remote("chapter:2", "抵达", "星海纪元"),
        )

        val plan = OnlineChapterUpdatePlanner.plan(stored, remote)

        assertFalse(plan[0].isNew)
        assertEquals("Text/chapter_0001.xhtml", plan[0].stored?.href)
        assertTrue(plan[1].isNew)
    }

    @Test
    fun `uses explicit chapter id before the rest of the url`() {
        assertEquals(
            "itemid:12345",
            OnlineChapterUpdatePlanner.stableSourceChapterId(
                "https://example.com/content?token=changing&itemId=12345#reader",
            ),
        )
    }

    @Test
    fun `detects insertion in extra volume and append in main volume`() {
        val stored = listOf(
            stored("1", "正文一", "正文", "Text/chapter_0001.xhtml"),
            stored("2", "番外一", "番外", "Text/chapter_0002.xhtml"),
            stored("3", "正文二", "正文", "Text/chapter_0003.xhtml"),
        )
        val remote = listOf(
            remote("1", "正文一", "正文"),
            remote("2", "番外一", "番外"),
            remote("4", "番外二", "番外"),
            remote("3", "正文二", "正文"),
            remote("5", "正文三", "正文"),
        )

        val plan = OnlineChapterUpdatePlanner.plan(stored, remote)

        assertEquals(listOf(false, false, true, false, true), plan.map { it.isNew })
        assertEquals("Text/chapter_0003.xhtml", plan[3].stored?.href)
    }

    @Test
    fun `bootstraps old book by volume and title when ids are missing`() {
        val stored = listOf(
            stored("", "第一章  开始", "第一卷", "Text/chapter_0001.xhtml"),
        )
        val remote = listOf(remote("100", "第一章 开始", "第一卷"))

        val slot = OnlineChapterUpdatePlanner.plan(stored, remote).single()

        assertFalse(slot.isNew)
        assertEquals("Text/chapter_0001.xhtml", slot.stored?.href)
    }

    @Test
    fun `same title in different volumes is not treated as the same chapter`() {
        val stored = listOf(stored("", "后记", "第一卷", "Text/chapter_0001.xhtml"))
        val remote = listOf(remote("200", "后记", "第二卷"))

        assertTrue(OnlineChapterUpdatePlanner.plan(stored, remote).single().isNew)
    }

    @Test
    fun `different explicit ids are new even when title is unchanged`() {
        val stored = listOf(stored("old-id", "同名章节", "正文", "Text/chapter_0001.xhtml"))
        val remote = listOf(remote("new-id", "同名章节", "正文"))

        assertTrue(OnlineChapterUpdatePlanner.plan(stored, remote).single().isNew)
    }

    @Test
    fun `parses chapter hrefs and inherited volume from legacy ncx`() {
        val chapters = OnlineChapterUpdatePlanner.parseLegacyNcx(
            """<?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
                  <navMap>
                    <navPoint><navLabel><text>正文</text></navLabel><content src="Text/chapter_0001.xhtml"/>
                      <navPoint><navLabel><text>第一章</text></navLabel><content src="Text/chapter_0001.xhtml"/></navPoint>
                    </navPoint>
                    <navPoint><navLabel><text>无卷章节</text></navLabel><content src="Text/chapter_0002.xhtml"/></navPoint>
                  </navMap>
                </ncx>""".trimIndent(),
        )

        assertEquals(2, chapters.size)
        assertEquals("正文", chapters[0].volumeTitle)
        assertEquals("第一章", chapters[0].title)
        assertEquals("Text/chapter_0001.xhtml", chapters[0].href)
        assertEquals("", chapters[1].volumeTitle)
    }

    private fun stored(id: String, title: String, volume: String, href: String) =
        StoredOnlineChapter(id, title, volume, href)

    private fun remote(id: String, title: String, volume: String) =
        RemoteOnlineChapter(id, title, volume)
}
