package com.reamicro.fix.online.search

import com.reamicro.fix.hook.webdav.OnlineBookSearchResult
import org.junit.Assert.*
import org.junit.Test

class OnlineSearchDisplayTest {
    private fun result(platform: String = "", source: String = "晚风里") = OnlineBookSearchResult(
        sourceName = source, name = "书名", author = "作者", coverUrl = "", detailUrl = "", intro = "",
        status = "连载", wordCount = "12万字", chapterCount = 42, updateTime = "2026-11-17",
        platformName = platform,
    )

    @Test fun platformIsShortAndPrecedesAllMetadata() {
        assertEquals("起点 / 连载 / 12万字 / 42章 / 2026-11-17",
            onlineSearchTagLine(result("起点中文网")))
        assertTrue(onlineSearchTagLine(result("晋江文学城")).startsWith("晋江 / "))
        assertEquals("起点", onlineSearchPlatformLabel("qidian"))
        assertEquals("晋江", onlineSearchPlatformLabel("jjwxc"))
    }

    @Test fun aggregateSourceIsNotMistakenForTheOriginalPlatform() {
        assertEquals("连载 / 12万字 / 42章 / 2026-11-17", onlineSearchTagLine(result()))
        assertTrue(onlineSearchTagLine(result(source = "起点中文网")).startsWith("起点 / "))
    }

    @Test fun unknownPlatformsAndMissingMetadataRemainReadable() {
        assertEquals("自建平台", onlineSearchPlatformLabel(" 自建平台 "))
        assertEquals("", onlineSearchPlatformLabel("null"))
        assertEquals("详情加载中", onlineSearchTagLine(result().copy(
            status = "", wordCount = "", chapterCount = 0, updateTime = "",
        )))
    }
}
