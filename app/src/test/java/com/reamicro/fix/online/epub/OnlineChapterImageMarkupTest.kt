package com.reamicro.fix.online.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineChapterImageMarkupTest {
    @Test
    fun preservesImgSrcAsMarkerAndDecodesEntities() {
        val html =
            """<p>前文</p><div><p><img src="https://cdn.example.com/a.jpg?x=1&amp;y=2" alt="" loading="lazy"></p></div><p>后文</p>"""
        val preserved = OnlineChapterImageMarkup.preserve(html)

        assertTrue(OnlineChapterImageMarkup.hasImages(preserved))
        assertEquals(
            listOf("https://cdn.example.com/a.jpg?x=1&y=2"),
            OnlineChapterImageMarkup.imageUrls(preserved),
        )
    }

    @Test
    fun markerSurvivesPlainTextNormalizationAndIsDetectedPerLine() {
        val html = """<p>正文一</p><p><img src='https://img/1.png'></p><p>正文二</p>"""
        // 模拟模块正文归一化：去标签 + 折叠空白 + 逐行 trim。
        val normalized = OnlineChapterImageMarkup.preserve(html)
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace(Regex("[ \\t\\r\\f]+"), " ")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")

        val markerLines = normalized.lineSequence()
            .mapNotNull { OnlineChapterImageMarkup.markerUrl(it) }
            .toList()

        assertEquals(listOf("https://img/1.png"), markerLines)
        assertTrue(normalized.contains("正文一"))
        assertTrue(normalized.contains("正文二"))
    }

    @Test
    fun deduplicatesRepeatedImageUrls() {
        val html = """<img src="https://img/x.jpg"><p>a</p><img src="https://img/x.jpg">"""
        assertEquals(
            listOf("https://img/x.jpg"),
            OnlineChapterImageMarkup.imageUrls(OnlineChapterImageMarkup.preserve(html)),
        )
    }

    @Test
    fun plainTextIsUntouched() {
        val text = "普通段落，没有任何图片。"
        assertEquals(text, OnlineChapterImageMarkup.preserve(text))
        assertFalse(OnlineChapterImageMarkup.hasImages(text))
        assertNull(OnlineChapterImageMarkup.markerUrl(text))
        assertTrue(OnlineChapterImageMarkup.imageUrls(text).isEmpty())
    }
}
