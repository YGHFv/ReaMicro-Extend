package com.reamicro.fix.epub.editor

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class EpubChapterTitlePreviewTest {
    @Test fun titleWinsAndEntitiesAreDecoded() {
        assertEquals("第一章 & 起点", EpubChapterTitlePreview.extract(
            "<h1>备用</h1><title> 第一章 &amp; <b>起点</b> </title>",
        ))
    }

    @Test fun headingFallbackSupportsNamespaces() {
        assertEquals("序言", EpubChapterTitlePreview.extract("<title> </title><x:h2>序言</x:h2>"))
    }

    @Test fun ignoresCommentsAndScripts() {
        assertEquals("真实标题", EpubChapterTitlePreview.extract(
            "<!-- <title>注释</title> --><script><title>脚本</title></script><h1>真实标题</h1>",
        ))
    }

    @Test fun noTitleKeepsEmptyFallback() {
        assertEquals("", EpubChapterTitlePreview.extract("<html><body><p>正文</p></body></html>"))
        assertEquals("", EpubChapterTitlePreview.extract("<title>截断"))
    }

    @Test fun numericEntitiesAndLongTitles() {
        assertEquals("中 文", EpubChapterTitlePreview.extract("<title>&#x4e2d;&nbsp;&#25991;</title>"))
        assertEquals(240, EpubChapterTitlePreview.extract("<title>${"字".repeat(500)}</title>").length)
    }

    @Test fun neverReadsBeyondPrefixBudget() {
        val bytes = (" ".repeat(EpubChapterTitlePreview.MAX_BYTES) + "<title>超出预算</title>").toByteArray()
        val stream = ByteArrayInputStream(bytes)
        assertEquals("", EpubChapterTitlePreview.read(stream))
        assertEquals(bytes.size - EpubChapterTitlePreview.MAX_BYTES, stream.available())
    }

    @Test fun supportsUtf16Bom() {
        val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte()) +
            "<title>中文章节</title>".toByteArray(Charsets.UTF_16LE)
        assertEquals("中文章节", EpubChapterTitlePreview.read(ByteArrayInputStream(bytes)))
    }

    @Test fun cancellationStopsBeforeReading() {
        val stream = ByteArrayInputStream("<title>测试</title>".toByteArray())
        val before = stream.available()
        try {
            EpubChapterTitlePreview.read(stream) { throw java.util.concurrent.CancellationException() }
            fail("Cancellation must propagate")
        } catch (_: java.util.concurrent.CancellationException) {
            assertEquals(before, stream.available())
        }
    }
}
