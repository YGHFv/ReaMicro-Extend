package com.reamicro.fix.online.epub

import com.reamicro.fix.settings.OnlineEpubStyleKind
import com.reamicro.fix.settings.OnlineEpubStyleLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 头图「是否该贴住页顶」的判定。
 *
 * 这条判据同时决定两件事：预览里要不要抵消 body 的上内边距，以及成书 CSS 要不要阻止
 * 上外边距折叠。判错的表现就是本该留白的头图贴在页顶，或者本该贴边的头图掉下来一截。
 */
class OnlineEpubHeaderFlushTest {

    @Test
    fun `上外边距为零视为贴边`() {
        assertTrue(OnlineEpubStyleCss.headerSitsFlushToTop(".te-header-figure { margin: 0 -1.5em 1.6em; }"))
        assertTrue(OnlineEpubStyleCss.headerSitsFlushToTop(".te-header-figure { margin-top: 0; }"))
        assertTrue(OnlineEpubStyleCss.headerSitsFlushToTop(".te-header-figure { margin: 0; }"))
    }

    @Test
    fun `上外边距为正视为留白`() {
        assertFalse(OnlineEpubStyleCss.headerSitsFlushToTop(".te-header-figure { margin: 1.2em auto 1.8em; }"))
        assertFalse(OnlineEpubStyleCss.headerSitsFlushToTop(".te-header-figure { margin-top: 12px; }"))
        assertFalse(OnlineEpubStyleCss.headerSitsFlushToTop(".te-header-figure { margin: 1.1em auto 2em; }"))
    }

    @Test
    fun `负上外边距同样算贴边`() {
        assertTrue(OnlineEpubStyleCss.headerSitsFlushToTop(".te-header-figure { margin-top: -8px; }"))
    }

    @Test
    fun `同一块里后写的声明生效`() {
        val css = ".te-header-figure { margin: 2em auto; margin-top: 0; }"
        assertTrue(OnlineEpubStyleCss.headerSitsFlushToTop(css))
        val reversed = ".te-header-figure { margin-top: 0; margin: 2em auto; }"
        assertFalse(OnlineEpubStyleCss.headerSitsFlushToTop(reversed))
    }

    @Test
    fun `没有头图选择器时按贴边处理，不额外注入规则`() {
        assertTrue(OnlineEpubStyleCss.headerSitsFlushToTop(""))
        assertTrue(OnlineEpubStyleCss.headerSitsFlushToTop(".te-header-image { width: 100%; }"))
    }

    @Test
    fun `只看头图选择器，不受其它块的外边距干扰`() {
        val css = """
            .te-header-caption { margin: 3em 0; }
            .te-header-figure { margin: 0 -1.5em 1.6em; }
        """.trimIndent()
        assertTrue(OnlineEpubStyleCss.headerSitsFlushToTop(css))
    }

    @Test
    fun `内置头图样式的贴边判定符合各自描述`() {
        // 贴边的一律写 margin-top: 0；要留白的写正值。duokan-bleed 不能作为判据——
        // 电影裁幅只声明了 leftright、细线装帧根本没声明，但两者都贴顶。
        val expectedNotFlush = setOf("卡片头图", "浮印留白头图")
        val headers = OnlineEpubStyleLibrary.BUILT_INS.filter { it.kind == OnlineEpubStyleKind.Header }
        assertEquals(14, headers.size)
        headers.forEach { style ->
            val flush = OnlineEpubStyleCss.headerSitsFlushToTop(style.css)
            assertEquals(style.name, style.name !in expectedNotFlush, flush)
        }
    }
}
