package com.reamicro.fix.hook

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ReaderMemorySourceContractTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun source(path: String) = root.resolve("java/com/reamicro/fix/$path").readText(Charsets.UTF_8)

    @Test fun fontCleanupIsWiredForPressureDestructionAndHotReload() {
        val entry = source("hook/ReaMicroHookEntry.kt")
        assertEquals(4, Regex("readerFontCompletionHook\\.releaseMemory\\(").findAll(entry).count())
        assertTrue(entry.contains("TRIM_MEMORY_RUNNING_LOW..ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL"))
        assertTrue(entry.contains("installMemoryCallbacks(activity.application, readerHook, readerFontCompletionHook"))
        assertTrue(entry.contains("readerFontCompletionHook = readerFontCompletionHook"))
        val font = source("hook/ReaderFontCompletionHook.kt")
        assertTrue(font.contains("ReaderFontFamilyCache<Any>()"))
        assertTrue(font.contains("FontSelectionResolution.cacheKey(selection, resolvedFile)"))
        assertTrue(font.contains("fontFamilyCache.clear()"))
    }

    @Test fun drawOptimizationKeepsOldAnnotationCleanupAndDoesNotRetainLayout() {
        val highlight = source("hook/ReaderDialogueHighlightHook.kt")
        val basic = highlight.substringAfter("private fun hookBasicTextNinePatchDraw()")
            .substringBefore("private fun hookTextNodeNinePatchDraw()")
        assertTrue(basic.contains("refreshAnnotatedStringForCurrentTheme"))
        assertFalse(basic.contains("canHighlightReaderDialogue"))
        val draw = highlight.substringAfter("private fun hookTextNodeNinePatchDraw()")
            .substringBefore("private fun rememberNinePatchRanges(")
        assertTrue(draw.contains("!settings.snapshot().canHighlightReaderDialogue"))
        assertTrue(draw.indexOf("!settings.snapshot().canHighlightReaderDialogue") < draw.indexOf("layoutCache.invoke"))
        assertTrue(draw.contains("textNodeDrawStates.remove(node)"))
        assertTrue(draw.contains("if (ranges.isEmpty()) null else NinePatchDrawState(ranges, token)"))
        assertTrue(draw.contains("drawNinePatchBackgrounds(scope, layout, state)"))
        val state = highlight.substringAfter("private class NinePatchDrawState(")
            .substringBefore("private data class RememberedNinePatchText")
        assertFalse(state.contains("textLayoutResult"))
        assertFalse(state.contains("cachedLayoutResult"))
        assertTrue(highlight.substringAfter("private fun clearHighlightRuntimeCaches()")
            .substringBefore("private fun hookBasicTextNinePatchDraw()").contains("textNodeDrawStates.clear()"))
    }

    @Test fun retireStillCancelsEveryRegisteredLiveHandler() {
        val resources = source("reload/ModuleResources.kt")
        assertTrue(resources.contains("WeakResourceRegistry<Handler>()"))
        assertTrue(resources.contains("handlers.add(it)"))
        assertTrue(resources.contains("handlers.drain().forEach { it.removeCallbacksAndMessages(null) }"))
        assertTrue(resources.contains("clearMainCallbacks()"))
    }
}
