package com.reamicro.fix.webdav

import com.reamicro.fix.settings.OnlineEpubHeaderScope
import com.reamicro.fix.settings.OnlineEpubStyle
import com.reamicro.fix.settings.OnlineEpubStyleDefaults
import com.reamicro.fix.settings.OnlineEpubStyleKind
import com.reamicro.fix.settings.OnlineEpubStyleLibrary
import com.reamicro.fix.settings.OnlineEpubStyleSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineEpubStyleCssTest {
    @Test
    fun `default build carries base layout and applied kinds`() {
        val css = OnlineEpubStyleCss.build(OnlineEpubStyleSettings())

        assertTrue(css.contains(".te-volume-page"))
        assertTrue(css.contains(".te-chapter-title"))
        assertTrue(css.contains(".te-volume-title"))
        assertTrue(css.contains(".te-illustration"))
        assertTrue(css.contains("p.te-divider-line"))
    }

    @Test
    fun `header styles stay out of the book until a scope is picked`() {
        assertFalse(OnlineEpubStyleCss.APPLIED_KINDS.contains(OnlineEpubStyleKind.Header))
        val css = OnlineEpubStyleCss.build(
            OnlineEpubStyleSettings(selection = mapOf(OnlineEpubStyleKind.Header to "header-card-shadow")),
        )
        assertFalse(css.contains(".te-header-figure"))
    }

    @Test
    fun `header styles join the css once a scope is picked`() {
        val css = OnlineEpubStyleCss.build(
            OnlineEpubStyleSettings(headerScope = OnlineEpubHeaderScope.EveryChapter),
        )

        assertTrue(css.contains(".te-header-figure"))
    }

    @Test
    fun `declare-only font writes the file name as a family`() {
        val style = OnlineEpubStyle(
            id = "custom-title",
            kind = OnlineEpubStyleKind.Title,
            name = "自定义",
            css = OnlineEpubStyleDefaults.blankCss(OnlineEpubStyleKind.Title),
            fontFamily = "/sdcard/Fonts/SourceHanSerif.ttf",
            embedFont = false,
        )

        val css = OnlineEpubStyleCss.styleCss(style, null)

        assertTrue(css.contains("""font-family: "SourceHanSerif", serif;"""))
        assertFalse(css.contains("@font-face"))
    }

    @Test
    fun `draft overrides the selected style for preview`() {
        val draft = OnlineEpubStyle(
            id = "draft-title",
            kind = OnlineEpubStyleKind.Title,
            name = "草稿",
            css = ".te-chapter-title {\n  color: #123456;\n}",
        )

        val css = OnlineEpubStyleCss.build(OnlineEpubStyleSettings().withDraft(draft))

        assertTrue(css.contains("color: #123456;"))
    }

    @Test
    fun `header is only enabled with both scope and image`() {
        val withScope = OnlineEpubStyleSettings(headerScope = OnlineEpubHeaderScope.EveryChapter)
        assertFalse(withScope.headerEnabled)

        val style = requireNotNull(OnlineEpubStyleLibrary.byId("header-standard-edge"))
        val ready = withScope.withDraft(style.copy(assetPath = "/sdcard/header.png"))
        assertTrue(ready.headerEnabled)
    }

    @Test
    fun `embedded font is declared and bound to the primary selector`() {
        val style = requireNotNull(OnlineEpubStyleLibrary.byId("title-classic-red"))
        val css = OnlineEpubStyleCss.build(
            OnlineEpubStyleSettings(),
            mapOf(style.id to OnlineEpubFontFace("rm-font-abc123", "../Fonts/rm-font-abc123.ttf", "truetype")),
        )

        assertTrue(css.contains("@font-face"))
        assertTrue(css.contains("""src: url("../Fonts/rm-font-abc123.ttf") format("truetype");"""))
        val titleBlock = css.substringAfter(".te-chapter-title {").substringBefore("}")
        assertTrue(titleBlock.contains("""font-family: "rm-font-abc123", serif;"""))
    }

    @Test
    fun `builtin font family is written by name without embedding`() {
        val style = OnlineEpubStyle(
            id = "custom-title",
            kind = OnlineEpubStyleKind.Title,
            name = "自定义",
            css = OnlineEpubStyleDefaults.blankCss(OnlineEpubStyleKind.Title),
            fontFamily = "楷体",
        )

        val css = OnlineEpubStyleCss.styleCss(style, null)

        assertTrue(css.contains("""font-family: "楷体", serif;"""))
    }

    @Test
    fun `font path without embedding is not written as a family name`() {
        val style = OnlineEpubStyle(
            id = "custom-title",
            kind = OnlineEpubStyleKind.Title,
            name = "自定义",
            css = OnlineEpubStyleDefaults.blankCss(OnlineEpubStyleKind.Title),
            fontFamily = "/sdcard/Fonts/custom.ttf",
        )

        assertFalse(OnlineEpubStyleCss.styleCss(style, null).contains("font-family"))
    }
}
