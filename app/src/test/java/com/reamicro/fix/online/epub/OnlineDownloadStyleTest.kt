package com.reamicro.fix.online.epub

import com.reamicro.fix.settings.*
import org.junit.Assert.*
import org.junit.Test

class OnlineDownloadStyleTest {
    @Test fun savedTransitionRetainsItsTemplateAndRendersThatTemplate() {
        val style = OnlineEpubStyle(
            id = "custom-transition", kind = OnlineEpubStyleKind.Transition, name = "测试分割",
            css = ".custom-divider { color: red; }",
            markup = """<p class="custom-divider">场景转换</p>""",
            assetPath = "/images/divider.png", maskAsset = "mask", sampleWidth = 320, sampleHeight = 80,
        )
        val restored = OnlineEpubStyleStore.styleFromJson(OnlineEpubStyleStore.styleJson(style))!!
        assertEquals(style, restored)
        assertTrue(OnlineBodyMarkup.transition(restored.markup, "※※※", null).contains("""class="custom-divider">场景转换</p>"""))
    }

    @Test fun oldOverridesRecoverTheMatchingBuiltInTemplate() {
        val original = OnlineEpubStyleLibrary.byId("transition-css-diamond")!!
        val json = OnlineEpubStyleStore.styleJson(original).apply { remove("markup") }
        val restored = OnlineEpubStyleStore.styleFromJson(json)!!
        assertEquals(original.markup, restored.markup)
        assertTrue(OnlineBodyMarkup.transition(restored.markup, "※※※", null).contains("te-transition--diamond"))
    }

    @Test fun selectedTitleSizeAndWeightOverrideTheDefaultHeading() {
        val style = OnlineEpubStyle("custom-title", OnlineEpubStyleKind.Title, "测试标题",
            css = ".te-chapter-title { font-size: 2.5em; font-weight: normal; }")
        val restored = OnlineEpubStyleStore.styleFromJson(OnlineEpubStyleStore.styleJson(style))!!
        val css = OnlineEpubStyleCss.build(OnlineEpubStyleSettings().withDraft(restored))
        // 按标题元素上匹配规则的 CSS 优先级和顺序求最终值，覆盖原 h1.class 抢占问题。
        val matching = OnlineEpubCssBlocks.parse(css).flatMapIndexed { order, block ->
            block.selector.substringAfterLast("*/").trim().split(',').mapNotNull { selector ->
                val clean = selector.trim()
                if (clean == ".te-chapter-title" || clean == "h1.te-chapter-title")
                    Triple(if (clean.startsWith("h1")) 11 else 10, order, block.declarations) else null
            }
        }.sortedWith(compareBy({ it.first }, { it.second }))
        val properties = linkedMapOf<String, String>()
        matching.forEach { (_, _, declarations) ->
            declarations.split(';').forEach { declaration ->
                val pair = declaration.split(':', limit = 2)
                if (pair.size == 2) properties[pair[0].trim()] = pair[1].trim()
            }
        }
        assertEquals("2.5em", properties["font-size"])
        assertEquals("normal", properties["font-weight"])
    }

    @Test fun aWrongKindSelectionCannotReplaceChapterTitlesWithDividerCss() {
        val settings = OnlineEpubStyleSettings(selection = mapOf(
            OnlineEpubStyleKind.Title to OnlineEpubStyleDefaults.defaultStyleId(OnlineEpubStyleKind.Transition),
        ))
        assertEquals(OnlineEpubStyleKind.Title, settings.selected(OnlineEpubStyleKind.Title)!!.kind)
    }

    private fun document(body: String) =
        """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>测试</title></head><body>$body</body></html>"""
    private val prose = """<p class="te-paragraph">完整正文 &amp; 保留</p>"""
    private fun validXml(html: String) {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        factory.newDocumentBuilder().parse(java.io.ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
    }

    @Test fun transitionsAtTheBeginningAndEndAndInsideNestedLayoutsAreAlwaysReplaced() {
        val old = document("""<div class="layout"><div class="te-divider-image"><img src="../Images/old.png"/></div>$prose<p class="te-transition te-transition--diamond"></p></div>""")
        val next = OnlineBodyMarkup.migrateTransitions(old, { it == "……" },
            """<p class="te-transition te-transition--text"><span>新分割</span></p>""", null)
        assertEquals(2, Regex("新分割").findAll(next).count())
        assertFalse(next.contains("old.png"))
        assertFalse(next.contains("te-transition--diamond"))
        assertTrue(next.contains(prose))
        validXml(next)
        assertEquals(next, OnlineBodyMarkup.migrateTransitions(next, { it == "……" },
            """<p class="te-transition te-transition--text"><span>新分割</span></p>""", null))
    }

    @Test fun everyBuiltInTransitionCanBeSwitchedToEveryOtherTemplate() {
        val styles = OnlineEpubStyleLibrary.BUILT_INS.filter { it.kind == OnlineEpubStyleKind.Transition }
        for (previous in styles) for (next in styles) {
            val original = document(prose + OnlineBodyMarkup.transition(previous.markup, "※※※", "../Images/divider.png") + prose)
            val migrated = OnlineBodyMarkup.migrateTransitions(original, { it == "※※※" }, next.markup, "../Images/divider.png")
            assertEquals(document(prose + OnlineBodyMarkup.transition(next.markup, "※※※", "../Images/divider.png") + prose), migrated)
            validXml(migrated)
        }
    }

    @Test fun standaloneAsciiAndUnicodeEllipsesUseTheSelectedStyleInNewAndExistingChapters() {
        val decor = com.reamicro.fix.hook.webdav.OnlineEpubDecor(
            transitionMarkup = """<p class="custom-divider"><span>场景转换</span></p>""",
        )
        // 第一章实际使用六个英文句点；同时覆盖两种省略号和连续分割段的合并。
        for (separator in listOf("...", "......", "……", "⋯⋯", "......\n……")) {
            val content = "场景之前的完整正文\n$separator\n场景之后的完整正文"
            val fresh = chapterXhtml("第1章 故事开始的那天", content, decor = decor)
            val oldBody = content.lineSequence().joinToString("") { OnlineBodyMarkup.paragraph(it) }
            val migrated = migrateOnlineCompletionChapterStyle(document(oldBody), decor)
            for (chapter in listOf(fresh, migrated)) {
                assertEquals(separator, 1, Regex("data-rm-transition=").findAll(chapter).count())
                assertTrue(chapter.contains("""class="custom-divider"><span>场景转换</span>"""))
                assertTrue(chapter.contains("场景之前的完整正文"))
                assertTrue(chapter.contains("场景之后的完整正文"))
                validXml(chapter)
                assertEquals(chapter, migrateOnlineCompletionChapterStyle(chapter, decor))
            }
        }
    }

    @Test fun ellipsesInDialogueDecimalsAndPathsRemainUnchangedDuringCreationAndMigration() {
        val lines = listOf(
            "之前的正文",
            "“……”清水优斜眼看她。",
            "“......”清水优斜眼看她。",
            "值为3.14",
            "3.14",
            "../Images/cover.png",
            ".",
            "..",
            "之后的正文",
        )
        val fresh = chapterXhtml("测试章节", lines.joinToString("\n"))
        val old = document(lines.joinToString("") { OnlineBodyMarkup.paragraph(it) })
        val migrated = migrateOnlineCompletionChapterStyle(old)
        for (chapter in listOf(fresh, migrated)) {
            assertFalse(chapter.contains("te-divider-line"))
            assertFalse(chapter.contains("data-rm-transition"))
            lines.forEach { assertTrue(it, chapter.contains(OnlineBodyMarkup.paragraph(it))) }
            validXml(chapter)
        }
    }

    @Test fun illustrationTemplatesApplyWithoutImportingSampleTextAndSurviveAnotherSwitch() {
        val styles = OnlineEpubStyleLibrary.BUILT_INS.filter { it.kind == OnlineEpubStyleKind.Illustration }
        for (style in styles) {
            val original = document(prose + OnlineBodyMarkup.illustration("../Images/real.png", "真实图注") + prose)
            var next = OnlineBodyMarkup.migrateIllustrations(original, style.markup)
            if (next.contains("epub:type")) next = next.replaceFirst("<html ", """<html xmlns:epub="http://www.idpf.org/2007/ops" """)
            assertTrue(next.contains("../Images/real.png"))
            assertTrue(next.contains("真实图注"))
            assertFalse(next.contains("旧站台"))
            assertFalse(next.contains("档案编号"))
            assertTrue(next.contains(prose))
            validXml(next)
            val restored = OnlineBodyMarkup.migrateIllustrations(next, "")
            assertTrue(restored.contains(OnlineBodyMarkup.illustration("../Images/real.png", "真实图注")))
            assertFalse(restored.contains("te-annotation-toggle"))
        }
    }

    @Test fun headerScopeMigrationAddsRemovesAndReplacesExistingHeaderMarkup() {
        val original = document(OnlineBodyMarkup.header("../Images/old.png") + prose)
        val expected = document(OnlineBodyMarkup.header("../Images/new.png") + prose)
        assertEquals(expected, OnlineBodyMarkup.migrateHeader(original, OnlineBodyMarkup.header("../Images/new.png")))
        assertEquals(document(prose), OnlineBodyMarkup.migrateHeader(expected, ""))
        for (scope in OnlineEpubHeaderScope.entries) {
            val decor = com.reamicro.fix.hook.webdav.OnlineEpubDecor(
                headerImageHref = "../Images/new.png", headerScope = scope)
            for (volume in listOf(false, true)) for (first in listOf(false, true)) {
                val migrated = migrateOnlineCompletionChapterStyle(original, decor, volume, first)
                assertEquals(if (decor.headerHtml(volume, first).isBlank()) 0 else 1,
                    Regex("te-header-figure").findAll(migrated).count())
                assertFalse(migrated.contains("old.png"))
                assertTrue(migrated.contains(prose))
                validXml(migrated)
            }
        }
    }

    @Test fun anExplicitFontChoiceOverridesTheBuiltInFontDeclaration() {
        val style = OnlineEpubStyle("font", OnlineEpubStyleKind.Title, "字体",
            css = """.te-chapter-title { font-family: "旧字体"; }""", fontFamily = "新字体")
        val css = OnlineEpubStyleCss.styleCss(style, null)
        assertTrue(css.indexOf("新字体") > css.indexOf("旧字体"))
    }
}
