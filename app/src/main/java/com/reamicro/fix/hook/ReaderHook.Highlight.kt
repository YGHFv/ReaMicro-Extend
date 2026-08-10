package com.reamicro.fix.hook

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.Toast
import com.reamicro.fix.reader.SearchHighlightPlanner
import com.reamicro.fix.settings.ReaderHighlightBookContext
import de.robv.android.xposed.XposedBridge
import com.reamicro.fix.hook.reader.*

// 阅读页高亮簇。
//
// 向宿主 Mark 体系注入模块高亮、刷新高亮窗口。
//
// 从 ReaderHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的结果重新
// 缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
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
    activityProvider()?.window?.decorView?.postDelayed({
        ReaderHighlightBookContext.bumpVersion("book-context-delayed:$source")
    }, READER_HIGHLIGHT_CONTEXT_REFRESH_DELAY_MS)
}

internal fun ReaderHook.openReaderHighlightRuleSheet(globalRules: Boolean) {
    val activity = activityProvider() ?: return
    val (bookKey, bookTitle) = currentHighlightBookIdentity() ?: run {
        Toast.makeText(activity, "\u672a\u83b7\u53d6\u5230\u5f53\u524d\u4e66\u7c4d", Toast.LENGTH_SHORT).show()
        return
    }
    updateReaderHighlightBookContext(bookKey, bookTitle, "highlight sheet open")
    activity.runOnUiThread {
        val receiver = bottomSearchReceiverRef?.get() ?: currentViewModelRef?.get()
        if (receiver == null) {
            Toast.makeText(activity, "\u672a\u83b7\u53d6\u5230\u9605\u8bfb\u5668\u72b6\u6001", Toast.LENGTH_SHORT).show()
            return@runOnUiThread
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

internal fun ReaderHook.highlightImageVector(): Any? {
    val outlined = runCatching {
        classLoader.loadClass(ICONS_OUTLINED_CLASS).getField("INSTANCE").get(null)
    }.getOrNull() ?: return null
    return listOf(
        HIGHLIGHT_ICON_BORDER_COLOR_CLASS to "getBorderColor",
        HIGHLIGHT_ICON_FORMAT_COLOR_FILL_CLASS to "getFormatColorFill",
        HIGHLIGHT_ICON_MODE_EDIT_CLASS to "getModeEdit",
    ).firstNotNullOfOrNull { (className, methodName) ->
        runCatching {
            classLoader.loadClass(className).declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == 1
            }?.apply { isAccessible = true }?.invoke(null, outlined)
        }.getOrNull()
    }
}

internal fun ReaderHook.highlightNativeSelection() {
    val activity = activityProvider() ?: return
    if (!canHighlightReaderSelection()) return
    val selection = currentNativeSelectionPayload()
    val controller = selection.controller
    val quote = selection.quote
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
    callNoArg(controller, "clearSelection")
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
        val markClass = classLoader.loadClass(MARK_CLASS)
        val now = System.currentTimeMillis()
        val bookId = (callNoArg(lastCatalogContext?.book, "getId") as? Number)?.toLong() ?: 0L
        val constructors = markClass.declaredConstructors.onEach { it.isAccessible = true }
        // Host Mark gained cloudId in rm2-a11. Try the new constructor first and fall back so
        // one module build can still run against older compatible hosts.
        val newCtor = constructors.firstOrNull { ctor ->
            val params = ctor.parameterTypes
            params.size == 14 &&
                params[0] == Long::class.javaPrimitiveType &&
                params[1] == Long::class.javaPrimitiveType &&
                params[2] == Long::class.javaPrimitiveType &&
                params[3] == String::class.java
        }
        if (newCtor != null) {
            return@runCatching newCtor.newInstance(
                id,
                bookId,
                0L,
                chapter,
                MARK_KIND_HIGHLIGHT,
                startCfi,
                endCfi,
                quote,
                "",
                style,
                color,
                MARK_SYNCED_NO,
                now,
                now,
            )
        }

        val oldCtor = constructors.firstOrNull { ctor ->
            val params = ctor.parameterTypes
            params.size == 13 &&
                params[0] == Long::class.javaPrimitiveType &&
                params[1] == Long::class.javaPrimitiveType &&
                params[2] == String::class.java
        } ?: error("No compatible Mark constructor found: ${constructors.joinToString { it.toString() }}")
        oldCtor.newInstance(
            id,
            bookId,
            chapter,
            MARK_KIND_HIGHLIGHT,
            startCfi,
            endCfi,
            quote,
            "",
            style,
            color,
            MARK_SYNCED_NO,
            now,
            now,
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX create search highlight mark failed: ${it.stackTraceToString()}")
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
        XposedBridge.log("$LOG_PREFIX reader highlight refresh skipped: no ReaderViewModel source=$source")
    }
}

internal fun ReaderHook.clearSelectionInjectedHighlight(viewModel: Any? = currentViewModelRef?.get()): Boolean {
    return updateSearchMarks(viewModel, "selection-clear") { marks ->
        val filtered = marks.filterNot(::isSelectionInjectedHighlightMark)
        if (filtered.size == marks.size) marks else filtered
    }
}

internal fun ReaderHook.isSelectionInjectedHighlightMark(mark: Any): Boolean {
    val id = (callNoArg(mark, "getId") as? Number)?.toLong() ?: return false
    return id >= SELECTION_HIGHLIGHT_MARK_ID_BASE &&
        id < SELECTION_HIGHLIGHT_MARK_ID_BASE + SELECTION_HIGHLIGHT_MARK_ID_RANGE
}

internal fun ReaderHook.transientHighlightMarkId(mark: Any): Long? =
    searchResultHighlightMarkId(mark) ?: readAloudHighlightMarkId(mark)

internal fun ReaderHook.transientHighlightResolvedMarkId(mark: Any): Long? {
    val id = (callNoArg(mark, "getId") as? Number)?.toLong() ?: return null
    return when {
        SearchHighlightPlanner.isHighlightId(id, SEARCH_HIGHLIGHT_MARK_ID_BASE, SEARCH_HIGHLIGHT_MARK_ID_RANGE) -> id
        id >= READ_ALOUD_HIGHLIGHT_MARK_ID_BASE &&
            id < READ_ALOUD_HIGHLIGHT_MARK_ID_BASE + READ_ALOUD_HIGHLIGHT_MARK_ID_RANGE -> id
        else -> null
    }
}

internal fun ReaderHook.activeTransientHighlightMarks(): List<Any> =
    listOfNotNull(activeSearchHighlightMark, activeReadAloudHighlightMark)

internal fun ReaderHook.currentHighlightBookIdentity(): Pair<String, String>? {
    currentUiStateBook()?.let { book ->
        val title = readableBookTitle(
            readableBookTitleFromBook(book),
            callString(currentPageRef?.get(), "getBookTitle"),
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
            callString(currentPageRef?.get(), "getBookTitle"),
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
