package com.reamicro.fix.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 防止把改变字宽的高亮再次放到正式版分页、两端对齐计算之后。 */
class HostHighlightMeasurementContractTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val source by lazy {
        main.resolve("java/com/reamicro/fix/hook/ReaderDialogueHighlightHook.kt").readText(Charsets.UTF_8)
    }

    @Test
    fun releaseUsesVerifiedPreMeasurementStageOnlyForInitialContent() {
        val hook = source.substringAfter("private fun hookContentDom(")
            .substringBefore("private fun applyDialogueHighlight(")
        assertTrue(hook.contains("HostSymbols.profileId == \"2.3.3\""))
        assertTrue(hook.contains("AtomicReference::class.java"))
        assertTrue(hook.contains("Int::class.javaPrimitiveType, ArrayList::class.java"))
        assertTrue(hook.contains("override fun beforeHookedMethod"))
        assertTrue(hook.contains("if (param.args[1] == 0) applyDialogueHighlight(param.thisObject)"))
        assertTrue(hook.contains("methods.forEach(XposedBridge::deoptimize)"))
    }

    @Test
    fun releaseDoesNotAppendFontSpansAfterNativeAdaptiveSpacing() {
        val hook = source.substringAfter("private fun hookContentDom(")
            .substringBefore("private fun applyDialogueHighlight(")
        val after = hook.substringAfter("override fun afterHookedMethod")
        assertTrue(after.contains("if (!highlightBeforeMeasure) applyDialogueHighlight(param.thisObject)"))
        assertFalse(after.contains("\n                        applyDialogueHighlight(param.thisObject)"))
    }

    @Test
    fun highlightingRetainsOriginalParagraphAndInlineAnnotations() {
        val rebuild = source.substringAfter("private fun newAnnotatedStringWithNinePatchAnnotations(")
            .substringBefore("private fun hookJustifyTextNinePatchDraw(")
        assertTrue(rebuild.contains("allAnnotatedRanges(original)"))
        assertTrue(rebuild.contains("!spanStyleClass.isInstance(item)"))
        assertTrue(rebuild.contains("ranges.addAll(spanStyles)"))
        assertTrue(rebuild.contains("newAnnotatedStringFromRanges(original, ranges)"))
        val spans = source.substringAfter("val originalSpanStyles = spanStylesWithoutInjectedHighlights(original)")
            .substringBefore("private fun ")
        assertTrue(spans.indexOf("addAll(originalSpanStyles)") < spans.indexOf("addAll(rangeObjects)"))
    }
}
