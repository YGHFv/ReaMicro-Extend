package com.reamicro.fix.hook.reader

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.TextView
import java.io.File
import com.reamicro.fix.hook.reader.*

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
    val completedQuery: SearchState? = null,
)

internal data class SearchDocument(
    val file: File,
    val chapterIndex: Int,
    val chapter: Any?,
    val chapterTitle: String,
    val readAloudChapterTitle: String,
    val text: String,
    val indexedText: IndexedSearchText,
    val chapterAnchors: List<ChapterAnchor>,
    val sourceLastModified: Long = file.lastModified(),
    val sourceLength: Long = file.length(),
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

internal data class CfiBase(
    val spineIndex: Int,
    val itemRefIndex: Int,
)

internal data class OnDemandPageLocation(
    val metadataIndex: Int,
    val hostSpineIndex: Int,
    val href: String,
)

internal data class TextSpan(
    val start: Int,
    val end: Int,
    val base: CfiBase,
    val elementSteps: List<Int>,
    val textStep: Int,
    val textOffset: Int,
    val sourceCfiPrefix: String? = null,
)

internal data class IndexedSearchText(
    val text: String,
    val spans: List<TextSpan>,
    val sourceDigest: String = "",
) {
    private fun spanAt(index: Int): TextSpan? {
        var low = 0; var high = spans.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val span = spans[mid]
            if (index < span.start) high = mid - 1
            else if (index >= span.end) low = mid + 1
            else return span
        }
        return null
    }
    fun cfiAt(index: Int): String? = spanAt(index)?.let { it.cfiAt(index - it.start) }

    fun cfiAtBoundary(index: Int): String? {
        val bounded = index.coerceIn(0, text.length)
        val span = spanAt(bounded - 1) ?: spanAt(bounded) ?: return null
        return span.cfiAt(bounded - span.start)
    }

    private fun TextSpan.cfiAt(relativeIndex: Int): String {
        sourceCfiPrefix?.let { return "$it${(textOffset + relativeIndex).coerceAtLeast(0)})" }
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
    val sourceDigest: String = "",
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
    private val palette = com.reamicro.fix.hook.ModuleDialogTheme.palette(context)
    val dark: Boolean = palette.dark ?: ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
    val pageBackground = palette.pageBackground
    val cardBackground = palette.rowBackground
    val inputBackground = palette.rowBackground
    val primaryText = palette.title
    val secondaryText = palette.body
    val stroke = palette.border
    val searchChipBackground = palette.rowBackground
    val inputStroke = palette.border
    val actionBackground = palette.primary
    val actionText = palette.onPrimary
    val accent = palette.primary
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
