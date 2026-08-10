package com.reamicro.fix.webdav

import com.reamicro.fix.settings.OnlineEpubStyleKind
import com.reamicro.fix.settings.OnlineEpubStyleLibrary
import com.reamicro.fix.settings.OnlineEpubStyleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineEpubStylePreviewTest {
    private val settings = OnlineEpubStyleSettings()

    @Test
    fun `preview carries the very css that ships in the book`() {
        val draft = requireNotNull(OnlineEpubStyleLibrary.byId("title-ink-line"))
        val bookCss = OnlineEpubStyleCss.build(settings.withDraft(draft))

        val html = OnlineEpubStylePreview.html(settings, draft)

        assertTrue("预览缺少成书 CSS", html.contains(bookCss))
    }

    @Test
    fun `preview does not hardcode paragraph layout`() {
        val draft = requireNotNull(OnlineEpubStyleLibrary.byId("title-classic-red"))
        val head = OnlineEpubStylePreview.html(settings, draft).substringBefore("</style>")

        // 只允许 reset 里的 html/body/img 三条；正文排版必须来自成书 CSS。
        assertFalse(head.contains("p{"))
        assertFalse(head.contains("p {\n            margin"))
        assertTrue(head.contains("background: #F5EFE0;"))
    }

    @Test
    fun `draft css shows up in the preview`() {
        val draft = requireNotNull(OnlineEpubStyleLibrary.byId("title-classic-red"))
            .copy(css = ".te-chapter-title {\n  color: #abcdef;\n}")

        assertTrue(OnlineEpubStylePreview.html(settings, draft).contains("color: #abcdef;"))
    }

    @Test
    fun `header preview renders even before a scope is chosen`() {
        val draft = requireNotNull(OnlineEpubStyleLibrary.byId("header-standard-edge"))

        val html = OnlineEpubStylePreview.html(settings, draft)

        assertTrue(html.contains(".te-header-figure"))
        assertTrue(html.contains("""<figure class="te-header-figure">"""))
    }

    @Test
    fun `selected asset is used by the preview body`() {
        val draft = requireNotNull(OnlineEpubStyleLibrary.byId("transition-image-divider"))

        val html = OnlineEpubStylePreview.html(settings, draft, assetUrl = "file:///sdcard/divider.png")

        assertTrue(html.contains("""<img class="te-divider-img" src="file:///sdcard/divider.png""""))
    }

    @Test
    fun `transition preview falls back to the text divider without an image`() {
        val draft = requireNotNull(OnlineEpubStyleLibrary.byId("transition-fg1-stars"))

        val html = OnlineEpubStylePreview.html(settings, draft)

        assertTrue(html.contains("""<p class="te-divider-line fg1">"""))
    }

    @Test
    fun `preview font is loaded from the device path`() {
        val draft = requireNotNull(OnlineEpubStyleLibrary.byId("title-classic-red"))

        val html = OnlineEpubStylePreview.html(settings, draft, fontUrl = "file:///sdcard/a.ttf")

        assertTrue(html.contains("@font-face"))
        assertTrue(html.contains("file:///sdcard/a.ttf"))
    }

    @Test
    fun `every kind renders a preview body`() {
        OnlineEpubStyleKind.entries.forEach { kind ->
            val draft = requireNotNull(OnlineEpubStyleLibrary.byKind(kind).firstOrNull()) { "$kind 无内置样式" }
            val html = OnlineEpubStylePreview.html(settings, draft)
            assertEquals("$kind 预览缺少 body", 1, Regex("<body>").findAll(html).count())
        }
    }
}
