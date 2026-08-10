package com.reamicro.fix.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineEpubStyleLibraryTest {
    @Test
    fun `every kind ships built-in styles`() {
        OnlineEpubStyleKind.entries.forEach { kind ->
            assertTrue("$kind 没有内置样式", OnlineEpubStyleLibrary.byKind(kind).isNotEmpty())
        }
    }

    @Test
    fun `built-in ids are unique and css is parsable`() {
        val ids = OnlineEpubStyleLibrary.BUILT_INS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        OnlineEpubStyleLibrary.BUILT_INS.forEach { style ->
            assertTrue("${style.id} 的 CSS 为空", style.css.isNotBlank())
            assertTrue("${style.id} 的 CSS 解析不出规则", OnlineEpubCssBlocks.parse(style.css).isNotEmpty())
        }
    }

    @Test
    fun `volume styles never reference chapter selectors`() {
        OnlineEpubStyleLibrary.byKind(OnlineEpubStyleKind.Volume).forEach { style ->
            assertFalse("${style.id} 残留章节选择器", style.css.contains("te-chapter-"))
            assertTrue("${style.id} 缺少卷标选择器", style.css.contains(".te-volume-title"))
        }
    }

    @Test
    fun `each kind default style exists`() {
        OnlineEpubStyleKind.entries.forEach { kind ->
            val id = OnlineEpubStyleDefaults.defaultStyleId(kind)
            val style = OnlineEpubStyleLibrary.byId(id)
            assertTrue("$kind 的默认样式 $id 不存在", style != null)
            assertEquals(kind, style?.kind)
        }
    }

    @Test
    fun `blank template exposes the standard selectors`() {
        OnlineEpubStyleKind.entries.forEach { kind ->
            val blocks = OnlineEpubCssBlocks.parse(OnlineEpubStyleDefaults.blankCss(kind))
            assertEquals(OnlineEpubStyleDefaults.selectors(kind), blocks.map { it.selector })
        }
    }

    @Test
    fun `preview body matches the generated markup structure`() {
        assertTrue(
            OnlineEpubStyleDefaults.previewBody(OnlineEpubStyleKind.Title)
                .contains("""<h1 class="te-chapter-title"><span class="te-chapter-number">"""),
        )
        assertTrue(
            OnlineEpubStyleDefaults.previewBody(OnlineEpubStyleKind.Illustration)
                .contains("""<figure class="te-illustration">"""),
        )
        assertTrue(
            OnlineEpubStyleDefaults.previewBody(OnlineEpubStyleKind.Transition)
                .contains("""<p class="te-divider-line fg1">"""),
        )
    }
}

class OnlineEpubCssBlocksTest {
    @Test
    fun `parses selector blocks`() {
        val blocks = OnlineEpubCssBlocks.parse(
            """
            .te-chapter-title {
              color: #c2181e;
              text-align: center;
            }

            .te-chapter-number {
              display: block;
            }
            """.trimIndent(),
        )

        assertEquals(listOf(".te-chapter-title", ".te-chapter-number"), blocks.map { it.selector })
        assertEquals("color: #c2181e;\ntext-align: center;", blocks[0].declarations)
    }

    @Test
    fun `compose then parse round trips`() {
        val original = OnlineEpubStyleLibrary.byId("title-classic-red")?.css.orEmpty()
        val blocks = OnlineEpubCssBlocks.parse(original)

        assertEquals(blocks, OnlineEpubCssBlocks.parse(OnlineEpubCssBlocks.compose(blocks)))
    }

    @Test
    fun `replace updates an existing selector`() {
        val css = OnlineEpubCssBlocks.replace(
            ".te-chapter-title {\n  color: red;\n}",
            ".te-chapter-title",
            "color: blue;",
        )

        assertTrue(css.contains("color: blue;"))
        assertFalse(css.contains("color: red;"))
    }

    @Test
    fun `replace appends an unknown selector`() {
        val css = OnlineEpubCssBlocks.replace(".te-chapter-title {\n}", ".te-chapter-name", "font-weight: 700;")

        assertEquals(listOf(".te-chapter-title", ".te-chapter-name"), OnlineEpubCssBlocks.parse(css).map { it.selector })
    }

    @Test
    fun `nested at rules survive parsing as a single block`() {
        val blocks = OnlineEpubCssBlocks.parse(
            "@media screen {\n  .te-chapter-title {\n    color: red;\n  }\n}",
        )

        assertEquals(listOf("@media screen"), blocks.map { it.selector })
    }
}
