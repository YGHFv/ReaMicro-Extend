package com.reamicro.fix.hook.reader

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.TextView
import java.io.File
import com.reamicro.fix.hook.reader.*

// 从 ReaderHook 提升出来的嵌套类型。
//
// 拆分成同包扩展函数后，这些类型要在多个文件里出现；提升到子包顶层配合包级
// star import，引用点无需加限定名。inner class 需要外部实例，仍留在原类里。
internal data class SearchRenderState(
    val keyword: String,
    val currentIndex: Int?,
    val renderedCount: Int,
    val lastVolumeKey: String?,
    val lastGroupKey: String?,
)

internal data class ReaderHighlightSheetRequest(
    val globalRules: Boolean,
    val bookKey: String,
    val bookTitle: String,
)

internal data class CatalogContext(
    val intentReceiver: Any?,
    val book: Any?,
    val catalog: List<Any>,
)

internal data class SearchState(
    val bookKey: String,
    val keyword: String,
    val results: List<FullTextSearchResult>,
)

internal data class NativeSelectionPayload(
    val controller: Any?,
    val quote: String,
    val startCfi: String,
    val endCfi: String,
)

internal data class SearchIndexState(
    val bookKey: String,
    val documents: List<SearchDocument>,
)

internal data class SearchDocument(
    val file: File,
    val chapterIndex: Int,
    val chapter: Any?,
    val chapterTitle: String,
    val readAloudChapterTitle: String,
    val text: String,
    val lowerText: String,
    val indexedText: IndexedSearchText,
    val chapterAnchors: List<ChapterAnchor>,
)

internal data class ReadAloudSegment(
    val chapterTitle: String,
    val chapterIndex: Int,
    val text: String,
    val highlightText: String = text,
    val startCfi: String = "",
    val endCfi: String = "",
)

internal data class ReadAloudTextPart(
    val text: String,
    val highlightText: String,
    val startOffset: Int,
    val endOffset: Int,
)

internal data class ReadAloudSelectionLocation(
    val documentIndex: Int,
    val offset: Int,
)

internal data class ReadingTarget(
    val cfi: String,
    val chapterIndex: Int,
    val title: String,
    val summary: String,
)

internal data class SearchNavigationState(
    val bookKey: String,
    val returnTarget: ReadingTarget,
    val currentIndex: Int,
)

internal data class PersistedSearchOrigin(
    val timestamp: Long,
    val bookKey: String,
    val epubRoot: String,
    val returnTarget: ReadingTarget,
)

internal data class PersistedReadAloudProgress(
    val timestamp: Long,
    val sessionId: String,
    val bookKey: String,
    val bookIdentity: String,
    val epubRoot: String,
    val bookTitle: String,
    val target: ReadingTarget,
    val endCfi: String,
    val paragraphIndex: Int,
    val elapsedMs: Long,
    val recordedElapsedMs: Long,
)

internal data class IndexedChapter(
    val index: Int,
    val entry: CatalogChapterEntry,
)

internal data class CatalogChapterEntry(
    val index: Int,
    val chapter: Any,
    val titlePath: String,
)

internal data class ChapterAnchor(
    val textStart: Int,
    val index: Int,
    val chapter: Any,
    val title: String,
)

internal data class TocNode(
    var title: String = "",
    var href: String = "",
)

internal data class SearchSnippet(
    val text: String,
    val matchStart: Int,
    val matchEnd: Int,
)

internal data class NormalizedNodeText(
    val text: String,
    val leadingTrim: Int,
)

internal data class CfiBase(
    val spineIndex: Int,
    val itemRefIndex: Int,
)

internal data class OnDemandPageLocation(
    val metadataIndex: Int,
    val hostSpineIndex: Int,
    val href: String,
)

internal data class BlockState(
    val path: List<Int>,
    var offset: Int = 0,
)

internal data class ElementFrame(
    val name: String,
    val step: Int,
    val path: List<Int>,
    var elementChildCount: Int = 0,
    var textChildCount: Int = 0,
    val block: BlockState? = null,
)

internal data class TextSpan(
    val start: Int,
    val end: Int,
    val base: CfiBase,
    val elementSteps: List<Int>,
    val textStep: Int,
    val textOffset: Int,
)

internal data class IndexedSearchText(
    val text: String,
    val spans: List<TextSpan>,
) {
    fun cfiAt(index: Int): String? {
        val span = spans.firstOrNull { index >= it.start && index < it.end } ?: return null
        return span.cfiAt(index - span.start)
    }

    fun cfiAtBoundary(index: Int): String? {
        val bounded = index.coerceIn(0, text.length)
        val span = spans.firstOrNull { bounded > it.start && bounded <= it.end }
            ?: spans.firstOrNull { bounded >= it.start && bounded < it.end }
            ?: return null
        return span.cfiAt(bounded - span.start)
    }

    fun cfiAtSearchJump(startIndex: Int, length: Int): String? {
        if (text.isEmpty()) return null
        val safeStart = startIndex.coerceIn(0, text.lastIndex)
        val safeLength = length.coerceAtLeast(1)
        val safeEnd = safeStart + safeLength
        val lastMatchIndex = (safeStart + safeLength - 1).coerceIn(safeStart, text.lastIndex)
        val candidates = listOf(
            safeEnd + 16,
            safeEnd + 4,
            safeEnd,
            lastMatchIndex,
            (safeStart + safeLength / 2).coerceIn(safeStart, lastMatchIndex),
            safeStart,
        ).distinct()
        return candidates.firstNotNullOfOrNull { cfiAtBoundary(it) ?: cfiAt(it.coerceAtMost(text.lastIndex)) }
    }

    private fun TextSpan.cfiAt(relativeIndex: Int): String {
        val elementPath = elementSteps.joinToString(separator = "") { "/$it" }
        val offset = (textOffset + relativeIndex).coerceAtLeast(0)
        return "epubcfi(/${base.spineIndex}/${base.itemRefIndex}/4$elementPath/${textStep}:$offset)"
    }
}

internal data class FullTextSearchResult(
    val chapterIndex: Int,
    val chapter: Any?,
    val chapterTitle: String,
    val intentReceiver: Any?,
    val startCfi: String?,
    val cfi: String?,
    val endCfi: String?,
    val file: File,
    val snippet: String,
    val snippetMatchStart: Int,
    val snippetMatchEnd: Int,
    val matchText: String,
)

internal data class DictionaryDialogHandle(
    val dialog: Dialog,
    val body: TextView,
    val footerLabel: TextView,
    val colors: DialogColors,
    var currentPresetId: String,
) {
    var requestId: Long = 0L
}

internal class SearchMenuButtonView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(context, 1).toFloat()
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(context, 3).toFloat()
        strokeCap = Paint.Cap.ROUND
    }

    init {
        refreshColors()
    }

    fun refreshColors() {
        val colors = DialogColors(context)
        fillPaint.color = colors.cardBackground
        strokePaint.color = colors.stroke
        iconPaint.color = colors.actionBackground
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - dp(context, 1)
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, strokePaint)
        val lensRadius = dp(context, 7).toFloat()
        val lensCx = cx - dp(context, 3)
        val lensCy = cy - dp(context, 3)
        canvas.drawCircle(lensCx, lensCy, lensRadius, iconPaint)
        canvas.drawLine(
            lensCx + lensRadius * 0.72f,
            lensCy + lensRadius * 0.72f,
            lensCx + lensRadius * 1.55f,
            lensCy + lensRadius * 1.55f,
            iconPaint,
        )
    }
}

internal class ReadAloudMenuButtonView(context: Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(context, 1).toFloat()
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(context, 3).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        refreshColors()
    }

    fun refreshColors() {
        val colors = DialogColors(context)
        fillPaint.color = colors.cardBackground
        strokePaint.color = colors.stroke
        iconPaint.color = colors.actionBackground
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - dp(context, 1)
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, strokePaint)
        val left = cx - dp(context, 10)
        val top = cy - dp(context, 7)
        val mid = cy
        val bottom = cy + dp(context, 7)
        val right = cx - dp(context, 2)
        canvas.drawLine(left, top, right, top - dp(context, 2), iconPaint)
        canvas.drawLine(left, bottom, right, bottom + dp(context, 2), iconPaint)
        canvas.drawLine(left, top, left, bottom, iconPaint)
        canvas.drawLine(right, top - dp(context, 2), right, bottom + dp(context, 2), iconPaint)
        canvas.drawArc(
            cx - dp(context, 3).toFloat(),
            cy - dp(context, 12).toFloat(),
            cx + dp(context, 14).toFloat(),
            cy + dp(context, 12).toFloat(),
            -38f,
            76f,
            false,
            iconPaint,
        )
        canvas.drawArc(
            cx + dp(context, 2).toFloat(),
            cy - dp(context, 7).toFloat(),
            cx + dp(context, 10).toFloat(),
            cy + dp(context, 7).toFloat(),
            -38f,
            76f,
            false,
            iconPaint,
        )
        canvas.drawPoint(right, mid, iconPaint)
    }
}

internal class DialogColors(context: Context) {
    val dark: Boolean = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val theme: ThemeColors? = latestThemeColors
    val pageBackground: Int = theme?.pageBackground ?: if (dark) Color.rgb(17, 19, 24) else Color.WHITE
    val cardBackground: Int = theme?.cardBackground ?: if (dark) Color.rgb(38, 38, 38) else Color.WHITE
    val inputBackground: Int = theme?.inputBackground ?: if (dark) Color.rgb(44, 44, 44) else Color.WHITE
    val primaryText: Int = theme?.primaryText ?: if (dark) Color.rgb(238, 238, 238) else Color.rgb(25, 25, 25)
    val secondaryText: Int = theme?.secondaryText ?: if (dark) Color.rgb(170, 170, 170) else Color.rgb(118, 118, 118)
    val stroke: Int = theme?.stroke ?: if (dark) Color.rgb(68, 68, 68) else Color.rgb(228, 228, 228)
    val searchChipBackground: Int = theme?.chipBackground ?: if (dark) Color.rgb(42, 42, 42) else Color.rgb(246, 246, 248)
    val inputStroke: Int = if (dark) Color.rgb(236, 162, 100) else Color.rgb(238, 118, 62)
    val actionBackground: Int = theme?.action ?: if (dark) Color.rgb(180, 112, 64) else Color.rgb(238, 118, 62)
    val actionText: Int = Color.WHITE
    val accent: Int = actionBackground
}

internal data class ThemeColors(
    val pageBackground: Int,
    val cardBackground: Int,
    val inputBackground: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val stroke: Int,
    val chipBackground: Int,
    val action: Int,
)
