package com.reamicro.fix.webdav

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
    fun `header styles never reach the book`() {
        assertFalse(OnlineEpubStyleCss.APPLIED_KINDS.contains(OnlineEpubStyleKind.Header))
        val css = OnlineEpubStyleCss.build(
            OnlineEpubStyleSettings(selection = mapOf(OnlineEpubStyleKind.Header to "header-card-shadow")),
        )
        assertFalse(css.contains(".te-header-figure"))
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

    @Test
    fun `selection falls back to defaults when the style is gone`() {
        val settings = OnlineEpubStyleSettings(selection = mapOf(OnlineEpubStyleKind.Title to "missing-style"))

        assertTrue(settings.selectedId(OnlineEpubStyleKind.Title) == OnlineEpubStyleDefaults.defaultStyleId(OnlineEpubStyleKind.Title))
    }
}
