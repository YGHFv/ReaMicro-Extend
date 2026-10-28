package com.reamicro.fix.core

import com.reamicro.fix.epub.editor.B4CssCompatibility
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定缓存单键语义、被内联的变量优先级，以及 SVG/字体行的原生接口。 */
class HostB4RemainingPreviewTest {
    private fun main(): File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun source(name: String) = main().resolve("java/com/reamicro/fix/$name").readText(Charsets.UTF_8)
    private fun profile() = HostProfile.parse(
        main().resolve("assets/host-profiles/2.3.3-b4.profile").readText(Charsets.UTF_8),
    )

    @Test fun `css invalidation keeps unrelated cache entries and removes only matching queue entry`() {
        val first = Any()
        val second = Any()
        val entries = ConcurrentHashMap<Any, Any>().apply { put("first", first); put("second", second) }
        val writes = mutableSetOf(first, second)
        val reads = mutableSetOf(first, second)
        B4CssCompatibility.invalidate(entries, "first") { writes.remove(it); reads.remove(it) }
        assertEquals(mapOf("second" to second), entries)
        assertEquals(setOf(second), writes)
        assertEquals(setOf(second), reads)
        var called = false
        B4CssCompatibility.invalidate(entries, "missing") { called = true }
        assertFalse(called)
    }

    @Test fun `custom property precedence preserves important and ignores ordinary declarations`() {
        val rules = listOf(
            listOf("--accent" to " red !important", "--size" to " 12px ", "color" to "green"),
            listOf("--accent" to "blue", "--size" to " 16px ", "--other" to "\tvalue\n"),
            listOf("--accent" to " gold !important"),
        )
        val custom = B4CssCompatibility.customProperties(rules) { it.trim(' ', '\t', '\r', '\n', '\u000c') }
        assertEquals(mapOf("--accent" to "gold", "--size" to "16px", "--other" to "value"), custom)
        assertFalse("color" in custom)
    }

    @Test fun `native css preview retains attach merge and the single key path`() {
        val parsed = profile()
        assertEquals("zna", parsed.classOf("org.epub.css.StyleSheetsProvider"))
        assertEquals("nq8", parsed.classOf("io.github.reactivecircus.cache4k.RealCache"))
        assertEquals("xyd", parsed.methodOwners["org.epub.css.RuleSet\$Companion#merge"])
        assertEquals("J0", parsed.methodOf("org.epub.css.RuleSet\$Companion", "merge"))
        val css = source("epub/editor/B4HostCssPreview.kt")
        assertTrue(css.contains("cacheType.hostMethod(\"expireEntries\")"))
        assertTrue(css.contains("B4CssCompatibility.invalidate(entries, file.absolutePath)"))
        assertTrue(css.contains("listOf(\"writeQueue\", \"accessQueue\")"))
        assertTrue(css.contains("ArrayList::class.java, Map::class.java"))
        assertFalse(css.contains("invalidateAll"))
        assertFalse(css.contains("getMethod(\"clear\""))
        assertFalse(css.contains("HostHtmlParser"))
    }

    @Test fun `svg keeps the same limits and native decoder renderer instead of unsafe allocation`() {
        val parsed = profile()
        assertEquals("kqa", parsed.classOf("ReaMicroHost.SvgDecoder"))
        assertEquals("b", parsed.methodOf("ReaMicroHost.SvgDecoder", "decode"))
        assertEquals("oqa", parsed.classOf("ReaMicroHost.SvgRenderer"))
        assertEquals("b", parsed.methodOf("ReaMicroHost.SvgRenderer", "render"))
        val public = source("epub/editor/HostSvgPreview.kt")
        for (marker in listOf("DOCTYPE|ENTITY", "@import", "SVG 安全预览不支持外部资源引用",
            "maxEdge in 1..2048", "2L * 1024L * 1024L", "EpubTextFiles.load(file)")) {
            assertTrue(marker, public.contains(marker))
        }
        val bridge = source("epub/editor/B4HostSvgPreview.kt")
        assertTrue(bridge.contains("decoder.hostMethod(\"decode\", source)"))
        assertTrue(bridge.contains("renderer.hostMethod(\"render\", Canvas::class.java)"))
        assertTrue(bridge.contains("picture.endRecording()"))
        assertFalse(bridge.contains("allocateInstance"))
        assertFalse(bridge.contains("Unsafe"))
    }

    @Test fun `builtin preview changes the pair argument without replacing row or invoking font configuration`() {
        val parsed = profile()
        assertEquals("i1e", parsed.classOf("ReaMicroHost.BuiltInFontRow"))
        assertEquals("ei6", parsed.classOf("ReaMicroHost.BuiltInFontItems"))
        assertEquals("y", parsed.methodOf("ReaMicroHost.BuiltInFontItems", "invoke"))
        val row = source("hook/ReaderFontCompletionHook.kt")
            .substringAfter("private fun hookB4BuiltInFontRow()").substringBefore("private fun fontFamilyArgumentNames")
        assertTrue(row.contains("param.args[1] = kotlinPair(mappedLabel(label, mapped), family)"))
        assertTrue(row.contains("and 0x70.inv()"))
        assertTrue(row.contains("if (!settings.snapshot().canUseFontSettings) return"))
        assertTrue(row.contains("if (mapped.isBlank()) return"))
        assertTrue(row.contains("resolveFontFamily(mapped) ?: return"))
        assertFalse(row.contains("param.result"))
        assertFalse(row.contains("setSongFont"))
        assertFalse(row.contains("setKaiFont"))
    }
}
