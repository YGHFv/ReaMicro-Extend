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
    val complete: Boolean = true,
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
    val chapterTitleHint: String,
    val text: String,
    val indexedText: IndexedSearchText,
    val chapterAnchors: List<ChapterAnchor>,
    val sourceLastModified: Long = file.lastModified(),
    val sourceLength: Long = file.length(),
    val titleParts: List<String> = emptyList(),
)

internal data class ReadingTarget(
    val cfi: String,
    val chapterIndex: Int,
    val title: String,
    val summary: String,
    val scrollOffsetPx: Int? = null,
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

internal data class IndexedChapter(
    val index: Int,
    val entry: CatalogChapterEntry,
)

internal data class CatalogChapterEntry(
    val index: Int,
    val chapter: Any,
    val titlePath: String,
    val titleParts: List<String> = emptyList(),
)

internal data class ChapterAnchor(
    val textStart: Int,
    val index: Int,
    val chapter: Any,
    val title: String,
    val titleParts: List<String> = emptyList(),
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
    val volumeTitle: String = "",
    val displayChapterTitle: String = chapterTitle,
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
