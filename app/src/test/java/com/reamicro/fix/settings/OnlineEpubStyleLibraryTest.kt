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

    @Test
    fun `template header styles ship a mask`() {
        val templates = OnlineEpubStyleLibrary.byKind(OnlineEpubStyleKind.Header)
            .filter { it.id.startsWith("header-template-") }

        assertTrue("样板头图未移植", templates.isNotEmpty())
        templates.forEach { style ->
            assertTrue("${style.id} 缺少蒙版", style.maskAsset.startsWith("epub_header_mask/"))
            assertTrue("${style.id} 缺少样板尺寸", style.sampleWidth > 0 && style.sampleHeight > 0)
        }
    }

    @Test
    fun `styles needing an image are detected`() {
        val imageDivider = requireNotNull(OnlineEpubStyleLibrary.byId("transition-image-divider"))
        val textDivider = requireNotNull(OnlineEpubStyleLibrary.byId("transition-fg1-stars"))
        val header = requireNotNull(OnlineEpubStyleLibrary.byId("header-standard-edge"))

        assertTrue(imageDivider.needsAsset)
        assertFalse(textDivider.needsAsset)
        assertTrue(header.needsAsset)
    }

    /**
     * 配置弹窗把 CSS 拆成若干段分别编辑，保存时再拼回去。
     *
     * 曾经有个 bug：首次进入就把还空着的输入框回写到第一段，`.te-chapter-title` 的
     * text-align/font-size/margin 被整段抹掉，标题变成左对齐超大字。这里守住往返不丢内容。
     */
    @Test
    fun `section round trip keeps every declaration`() {
        OnlineEpubStyleLibrary.BUILT_INS.forEach { style ->
            val drafts = LinkedHashMap<String, String>()
            OnlineEpubStyleDefaults.selectors(style.kind).forEach { drafts[it] = "" }
            OnlineEpubCssBlocks.parse(style.css).forEach { drafts[it.selector] = it.declarations }

            val rebuilt = OnlineEpubCssBlocks.compose(
                drafts.map { (selector, body) -> OnlineEpubCssBlock(selector, body) },
            )

            val rebuiltBlocks = OnlineEpubCssBlocks.parse(rebuilt).associate { it.selector to it.declarations }
            OnlineEpubCssBlocks.parse(style.css).forEach { block ->
                assertEquals(
                    "${style.id} 的 ${block.selector} 往返后变了",
                    block.declarations,
                    rebuiltBlocks[block.selector],
                )
            }
        }
    }

    @Test
    fun `built-in title style keeps its centering declaration`() {
        val style = requireNotNull(OnlineEpubStyleLibrary.byId("title-classic-red"))
        val blocks = OnlineEpubCssBlocks.parse(style.css)

        val title = requireNotNull(blocks.firstOrNull { it.selector == ".te-chapter-title" })
        assertTrue(title.declarations.contains("text-align: center;"))
        assertTrue(title.declarations.contains("font-size: 1.2em;"))
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
