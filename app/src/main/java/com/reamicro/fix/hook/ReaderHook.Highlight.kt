package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.Toast
import com.reamicro.fix.reader.SearchHighlightPlanner
import com.reamicro.fix.settings.ReaderHighlightBookContext
import com.reamicro.fix.xposed.HookRuntime
import com.reamicro.fix.hook.reader.*

internal fun ReaderHook.canHighlightReaderSelection(): Boolean =
    settingsProvider().canHighlightReaderSelection

internal fun ReaderHook.updateReaderHighlightBookContext(
    bookKey: String,
    bookTitle: String,
    source: String,
    requestRefresh: Boolean = true,
) {
    val normalizedKey = bookKey.trim()
    val normalizedTitle = bookTitle.trim()
    val nextIdentity = "$normalizedKey\n$normalizedTitle"
    val changed = nextIdentity != lastReaderHighlightBookIdentity
    ReaderHighlightBookContext.update(normalizedKey, normalizedTitle)
    if (!changed) return
    lastReaderHighlightBookIdentity = nextIdentity
    if (normalizedKey.isBlank()) return
    ReaderHighlightBookContext.bumpVersion("book-context:$source", requestRefresh = requestRefresh)
    if (!requestRefresh) return
    highlightRefreshHandler.removeCallbacks(highlightContextRefresh)
    highlightRefreshHandler.postDelayed(highlightContextRefresh, READER_HIGHLIGHT_CONTEXT_REFRESH_DELAY_MS)
}

internal fun ReaderHook.openReaderHighlightRuleSheet(globalRules: Boolean) {
    val activity = activityProvider() ?: return
    val (bookKey, bookTitle) = currentHighlightBookIdentity() ?: run {
        Toast.makeText(activity, "\u672a\u83b7\u53d6\u5230\u5f53\u524d\u4e66\u7c4d", Toast.LENGTH_SHORT).show()
        return
    }
    updateReaderHighlightBookContext(bookKey, bookTitle, "highlight sheet open")
    activity.runOnModuleUiThread {
        val receiver = bottomSearchReceiverRef?.get() ?: currentViewModelRef?.get()
        if (receiver == null) {
            Toast.makeText(activity, "\u672a\u83b7\u53d6\u5230\u9605\u8bfb\u5668\u72b6\u6001", Toast.LENGTH_SHORT).show()
            return@runOnModuleUiThread
        }
        pendingReaderHighlightSheet = ReaderHighlightSheetRequest(globalRules, bookKey, bookTitle)
        if (!sendReaderUiSheetStatus(receiver, "EpubFamily")) {
            pendingReaderHighlightSheet = null
            Toast.makeText(activity, "\u672a\u80fd\u6253\u5f00\u9ad8\u4eae\u89c4\u5219\u5f39\u7a97", Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun ReaderHook.createNativeHighlightAction(icon: Any): Any? =
    createSelectionMenuAction(
        if (settingsProvider().canUseCompactReaderSelectionMenu) "" else "\u9ad8\u4eae",
        icon,
        nativeFunction0 {
            highlightNativeSelection()
        },
        "create native highlight action",
    )

internal fun ReaderHook.highlightNativeSelection() {
    val activity = activityProvider() ?: return
    if (!canHighlightReaderSelection()) return
    val selection = currentNativeSelectionPayload()
    val controller = selection.controller
    val quote = selection.quote
    HookRuntime.log(
        "$LOG_PREFIX selection highlight requested quote=${quote.take(160)} " +
            "start=${selection.startCfi} end=${selection.endCfi}",
    )
    if (quote.isBlank()) {
        Toast.makeText(activity, "\u672a\u83b7\u53d6\u5230\u9009\u4e2d\u6587\u672c", Toast.LENGTH_SHORT).show()
        return
    }
    val (bookKey, bookTitle) = currentHighlightBookIdentity() ?: run {
        Toast.makeText(activity, "\u672a\u83b7\u53d6\u5230\u5f53\u524d\u4e66\u7c4d", Toast.LENGTH_SHORT).show()
        return
    }
    updateReaderHighlightBookContext(bookKey, bookTitle, "selection highlight")
    val rule = settings?.addReaderBookHighlightRule(bookKey, bookTitle, quote)
    if (rule == null) {
        Toast.makeText(activity, "\u6dfb\u52a0\u9ad8\u4eae\u89c4\u5219\u5931\u8d25", Toast.LENGTH_SHORT).show()
        return
    }
    HookRuntime.log(
        "$LOG_PREFIX selection highlight rule added id=${rule.id} book=${bookKey.take(160)} " +
            "pattern=${rule.pattern.take(160)}",
    )
    controller?.let { requireNotNull(com.reamicro.fix.core.HostSelectionBindings.current).method("clear").invoke(it) }
    refreshReaderAfterSelectionHighlight()
    Toast.makeText(activity, "\u5df2\u6dfb\u52a0\u672c\u4e66\u9ad8\u4eae", Toast.LENGTH_SHORT).show()
}

internal fun ReaderHook.redHighlightedSnippet(result: FullTextSearchResult): SpannableString =
    SpannableString(result.snippet).apply {
        val start = result.snippetMatchStart.coerceIn(0, result.snippet.length)
        val end = result.snippetMatchEnd.coerceIn(start, result.snippet.length)
        if (end > start) {
            setSpan(ForegroundColorSpan(Color.RED), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

internal fun ReaderHook.createReaderMark(
    id: Long,
    chapter: String,
    startCfi: String,
    endCfi: String,
    quote: String,
    style: Int,
    color: String,
): Any? =
    runCatching {
        val symbols = requireNotNull(com.reamicro.fix.core.HostReaderRecordBindings.current)
        val now = System.currentTimeMillis()
        val bookId = (com.reamicro.fix.core.HostEpubRuntimeBindings.current?.value("book.id", lastCatalogContext?.book) as? Number)?.toLong() ?: 0L
        symbols.record("mark", mapOf(
            "id" to id, "bookId" to bookId, "cloudId" to 0L, "chapter" to chapter,
            "kind" to MARK_KIND_HIGHLIGHT, "startCfi" to startCfi, "endCfi" to endCfi,
            "quote" to quote, "note" to "", "style" to style, "color" to color,
            "synced" to MARK_SYNCED_NO, "createdAt" to now, "updatedAt" to now,
        ))
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX create search highlight mark failed: ${it.stackTraceToString()}")
    }.getOrNull()

internal fun ReaderHook.refreshReaderAfterSelectionHighlight() {
    ReaderHighlightBookContext.bumpVersion("selection-highlight")
}

internal fun ReaderHook.refreshReaderHighlightWindow(source: String) {
    val activity = activityProvider()
    val refreshed = forceRefreshReaderWindow(source)
    val decor = activity?.window?.decorView
    runCatching {
        decor?.invalidate()
        decor?.requestLayout()
    }
    if (!refreshed) {
        HookRuntime.log("$LOG_PREFIX reader highlight refresh skipped: no ReaderViewModel source=$source")
    }
}

internal fun ReaderHook.clearSelectionInjectedHighlight(viewModel: Any? = currentViewModelRef?.get()): Boolean {
    return updateSearchMarks(viewModel, "selection-clear") { marks ->
        val filtered = marks.filterNot(::isSelectionInjectedHighlightMark)
        if (filtered.size == marks.size) marks else filtered
    }
}

internal fun ReaderHook.isSelectionInjectedHighlightMark(mark: Any): Boolean {
    val symbols = requireNotNull(com.reamicro.fix.core.HostReaderRecordBindings.current)
    if (!symbols.type("mark").isInstance(mark)) return false
    val id = symbols.field("mark.id").getLong(mark)
    return id >= SELECTION_HIGHLIGHT_MARK_ID_BASE &&
        id < SELECTION_HIGHLIGHT_MARK_ID_BASE + SELECTION_HIGHLIGHT_MARK_ID_RANGE
}

internal fun ReaderHook.transientHighlightMarkId(mark: Any): Long? =
    searchResultHighlightMarkId(mark)

internal fun ReaderHook.transientHighlightResolvedMarkId(mark: Any): Long? {
    val symbols = requireNotNull(com.reamicro.fix.core.HostReaderRecordBindings.current)
    if (!symbols.type("resolved").isInstance(mark)) return null
    val id = symbols.field("resolved.id").getLong(mark)
    return when {
        SearchHighlightPlanner.isHighlightId(id, SEARCH_HIGHLIGHT_MARK_ID_BASE, SEARCH_HIGHLIGHT_MARK_ID_RANGE) -> id
        else -> null
    }
}

internal fun ReaderHook.activeTransientHighlightMarks(): List<Any> =
    listOfNotNull(activeSearchHighlightMark?.takeIf { currentSearchPaintSession() != null })

internal fun ReaderHook.currentHighlightBookIdentity(): Pair<String, String>? {
    currentUiStateBook()?.let { book ->
        val title = readableBookTitle(
            readableBookTitleFromBook(book),
            titleFromCurrentEpubRoot(),
        ).ifBlank { fallbackCurrentBookTitle() }
        val key = bookKeyFromBook(book, title)
        if (key.isNotBlank()) return key to title
    }
    lastCatalogContext?.let { context ->
        val key = bookKey(context)
        if (key.isNotBlank()) return key to bookTitle(context).ifBlank { fallbackCurrentBookTitle() }
    }
    bottomSearchBookRef?.get()?.let { book ->
        val title = readableBookTitle(
            readableBookTitleFromBook(book),
            titleFromCurrentEpubRoot(),
        ).ifBlank { fallbackCurrentBookTitle() }
        val key = bookKeyFromBook(book, title)
        if (key.isNotBlank()) return key to title
    }
    val root = currentEpubRoot() ?: return null
    val title = fallbackCurrentBookTitle()
        .ifBlank { root.nameWithoutExtension }
        .ifBlank { root.name }
    return root.absolutePath to title
}
