package com.reamicro.fix.hook

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.Toast
import com.reamicro.fix.reader.SearchHighlightPlanner
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.reflect.Method
import java.io.File
import java.lang.ref.WeakReference
import com.reamicro.fix.hook.reader.*

internal fun ReaderHook.canRunFullTextSearch(): Boolean {
    val snapshot = settingsProvider()
    return snapshot.moduleEnabled
}

internal fun ReaderHook.canSearchCurrentBook(): Boolean =
    canRunFullTextSearch() && currentEpubRoot() != null && (currentReaderPage() != null || currentScrollElement != null)

internal fun ReaderHook.injectSearchHighlightIntoReaderCatalog(param: XC_MethodHook.MethodHookParam) {
    val args = param.args ?: return
    val chapterItemsMap = args.getOrNull(5) as? Map<*, *> ?: return
    val nextMap = appendActiveSearchHighlightCatalogItemMap(chapterItemsMap) ?: return
    args[5] = nextMap
}

internal fun ReaderHook.clearSearchOverlays(clearNavigationState: Boolean) {
    closeSearchPage()
    if (clearNavigationState) {
        searchNavigator?.cancel()
        activeSearchNavigation = null
        searchNavigationState = null
        clearPersistedSearchOrigin()
    }
    removeSearchNavigationBar()
}

internal fun ReaderHook.hasFullTextSearchState(): Boolean =
    bottomSearchReceiverRef?.get() != null ||
        bottomSearchBookRef?.get() != null ||
        lastCatalogContext != null ||
        lastSearchState != null ||
        activeSearchNavigation != null ||
        searchIndexState != null ||
        searchDocumentCache.isNotEmpty() ||
        searchPageDialogRef?.get() != null ||
        searchNavigationBarRef?.get() != null

internal fun ReaderHook.resetFullTextSearchState(reason: String, removeOverlays: Boolean) {
    searchWorker.cancel()
    currentSearchPagerMode = null
    searchNavigator?.cancel()
    searchNavigator = null
    searchScrollBridge?.clearCache()
    searchDocumentCache.clear()
    currentScrollElement = null
    searchStateGeneration += 1
    searchRunSeq += 1
    activeSearchPageToken = 0L
    activeSearchPageUpdate = null
    clearSearchResultHighlight()
    clearSelectionInjectedHighlight()
    bottomSearchReceiverRef = null
    bottomSearchBookRef = null
    lastCatalogContext = null
    lastSearchState = null
    searchNavigationState = null
    pendingSearchReturnCaptures = 0
    activeSearchNavigation = null
    searchIndexState = null
    if (removeOverlays) {
        activityProvider()?.runOnUiThread {
            closeSearchPage()
            removeSearchNavigationBar()
        }
    }
    XposedBridge.log("$LOG_PREFIX full-text search state reset: $reason")
}

internal fun ReaderHook.updateSearchNavigationForBottomState(activity: Activity) {
    if (readerBottomMenuVisible) {
        searchNavigationBarRef?.get()?.visibility = View.GONE
        return
    }
    if (activeSearchNavigation != null && searchNavigationState != null) {
        ensureSearchNavigationBar(activity)
    } else {
        removeSearchNavigationBar()
    }
}

internal fun ReaderHook.searchImageVector(): Any? = runCatching {
    val outlined = classLoader.loadClass(ICONS_OUTLINED_CLASS).getField("INSTANCE").get(null)
    classLoader.loadClass(SEARCH_ICON_CLASS).declaredMethods.firstOrNull {
        it.name == "getSearch" && it.parameterTypes.size == 1
    }?.apply { isAccessible = true }?.invoke(null, outlined)
}.getOrNull()

internal fun ReaderHook.ensureSearchOverlayThemeCallbacks(activity: Activity) {
    if (searchOverlayThemeCallbacksActivityRef?.get() === activity && searchOverlayThemeCallbacks != null) return
    unregisterSearchOverlayThemeCallbacks()
    val callbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            activity.runOnUiThread {
                refreshSearchNavigationBarTheme()
            }
        }

        override fun onLowMemory() = Unit

        override fun onTrimMemory(level: Int) = Unit
    }
    searchOverlayThemeCallbacks = callbacks
    searchOverlayThemeCallbacksActivityRef = WeakReference(activity)
    activity.registerComponentCallbacks(callbacks)
}

internal fun ReaderHook.refreshSearchNavigationBarTheme() { searchNavigationView?.refresh() }

internal fun ReaderHook.maybeUnregisterSearchOverlayThemeCallbacks() {
    if (searchNavigationBarRef?.get() != null) return
    unregisterSearchOverlayThemeCallbacks()
}

internal fun ReaderHook.unregisterSearchOverlayThemeCallbacks() {
    val callbacks = searchOverlayThemeCallbacks ?: return
    val activity = searchOverlayThemeCallbacksActivityRef?.get()
    runCatching { activity?.unregisterComponentCallbacks(callbacks) }
    searchOverlayThemeCallbacks = null
    searchOverlayThemeCallbacksActivityRef = null
}

internal fun ReaderHook.bottomSearchContext(receiver: Any?, book: Any?): CatalogContext? {
    val existing = lastCatalogContext
    val targetBook = book ?: existing?.book ?: return null
    val catalog = existing?.takeIf { bookKey(it).isNotBlank() }?.catalog.orEmpty()
    return CatalogContext(receiver ?: existing?.intentReceiver, targetBook, catalog)
}

internal fun ReaderHook.openBottomSearchPage() {
    val activity = activityProvider() ?: return
    if (!canSearchCurrentBook()) {
        XposedBridge.log(
            "$LOG_PREFIX search blocked entry: moduleEnabled=${canRunFullTextSearch()} " +
                "epubRoot=${currentEpubRoot() != null} page=${currentReaderPage() != null} " +
                "epubStrong=${currentEpubStrong != null} epubWeak=${currentEpubRef?.get() != null} " +
                "pageStrong=${currentPageStrong != null} pageWeak=${currentPageRef?.get() != null}",
        )
        Toast.makeText(activity, "\u6682\u65e0\u6cd5\u641c\u7d22\u5f53\u524d\u4e66\u7c4d", Toast.LENGTH_SHORT).show()
        return
    }
    val context = bottomSearchContext(bottomSearchReceiverRef?.get(), bottomSearchBookRef?.get())
    if (context == null) {
        XposedBridge.log(
            "$LOG_PREFIX search blocked context: receiver=${bottomSearchReceiverRef?.get() != null} " +
                "book=${bottomSearchBookRef?.get() != null} lastCatalog=${lastCatalogContext != null} " +
                "lastCatalogBook=${lastCatalogContext?.book != null}",
        )
        Toast.makeText(activity, "\u6682\u65e0\u6cd5\u641c\u7d22\u5f53\u524d\u4e66\u7c4d", Toast.LENGTH_SHORT).show()
        return
    }
    activity.runOnUiThread {
        com.reamicro.fix.core.InjectedModuleContext.runUiAction(activity) {
            showFullTextSearchPage(activity, context)
        }
    }
}

internal fun ReaderHook.cancelSearchPageWork() {
    searchWorker.cancel()
    searchRunSeq += 1
    lastSearchState = null
}

internal fun ReaderHook.closeSearchPage() {
    searchPageDialogRef?.get()?.dismiss()
    searchPageDialogRef = null
    cancelSearchPageWork()
    activeSearchPageToken = 0L
    activeSearchPageUpdate = null
}

internal fun ReaderHook.showFullTextSearchPage(activity: Activity, context: CatalogContext) {
    if (activity.isFinishing || activity.isDestroyed) return
    closeSearchPage()
    val pageBookKey = bookKey(context)
    val pageToken = System.nanoTime()
    clearStaleSearchNavigation()
    cancelSearchPageWork()
    var chosen = false
    lateinit var panel: HostFullTextSearchDialog
    fun submit(keyword: String) {
        if (searchPageDialogRef?.get() !== panel) return
        if (keyword.isBlank()) {
            cancelSearchPageWork()
            panel.render("", emptyList(), false)
            return
        }
        if (lastCatalogContext?.let(::bookKey)?.let { it != pageBookKey } == true) { panel.error("图书已切换，请重新打开搜索"); return }
        val generation = searchStateGeneration
        val runSeq = System.nanoTime()
        searchRunSeq = runSeq
        panel.render(keyword, emptyList(), true)
        val weakPanel = WeakReference(panel)
        val request = ReaderSearchRequest(generation, runSeq, pageToken)
        searchWorker.submit work@ {
            runCatching {
                searchFullTextStreaming(keyword, context, request) { results, done ->
                    activity.runOnUiThread {
                        if (!request.accepts(searchStateGeneration, searchRunSeq, activeSearchPageToken,
                            searchPageDialogRef?.get() === weakPanel.get())) return@runOnUiThread
                        val state = SearchState(pageBookKey, keyword, results)
                        lastSearchState = state
                        activeSearchPageUpdate?.invoke(state, !done)
                    }
                }
            }.onFailure { failure ->
                if (!request.accepts(searchStateGeneration, searchRunSeq, activeSearchPageToken, true)) return@work
                XposedBridge.log("$LOG_PREFIX full-text search failed: ${failure.javaClass.simpleName}")
                activity.runOnUiThread {
                    if (request.accepts(searchStateGeneration, searchRunSeq, activeSearchPageToken, searchPageDialogRef?.get() === weakPanel.get())) {
                        lastSearchState = null
                        if (activeSearchPageToken == pageToken) weakPanel.get()?.error("搜索失败：${failure.message.orEmpty()}")
                    }
                }
            }
        }
    }
    panel = HostFullTextSearchDialog(activity, currentEpubRoot(), "", 0,
        initialTheme = readerSearchTheme,
        fontSelection = { if (settingsProvider().canUseFontSettings) settings?.fontSettings()?.globalFamily.orEmpty() else "" },
        onSearch = ::submit,
        onChoose = { result, index ->
            if (lastCatalogContext?.let(::bookKey)?.let { it != pageBookKey } == true) {
                panel.error("图书已切换，请重新打开搜索"); false
            } else {
                searchNavigationState = lastSearchState
                chosen = runCatching { jumpToSearchResult(result, index) }.getOrElse {
                    XposedBridge.log("ReaMicro search entry failed type=${it.javaClass.simpleName}")
                    false
                }
                if (!chosen) {
                    clearStaleSearchNavigation()
                    panel.error("阅读位置暂不可用，请关闭搜索、等待正文加载完成后重试")
                }
                chosen
            }
        },
        onClosed = { _ ->

            if (activeSearchPageToken == pageToken) {
                cancelSearchPageWork()
                if (!chosen) clearStaleSearchNavigation()
                activeSearchPageToken = 0L
                activeSearchPageUpdate = null
            }
            if (searchPageDialogRef?.get() === panel) searchPageDialogRef = null
        })
    activeSearchPageToken = pageToken
    activeSearchPageUpdate = { state, searching ->
        if (activeSearchPageToken == pageToken && state.bookKey == pageBookKey) {
            panel.render(state.keyword, state.results, searching, activeSearchNavigation?.currentIndex ?: -1)
        }
    }
    searchPageDialogRef = WeakReference(panel)
    panel.show()
}

internal fun ReaderHook.isSearchHighlightContentOverlayMethod(method: Method): Boolean {
    val params = method.parameterTypes
    if (params.size !in 4..6) return false
    if (!List::class.java.isAssignableFrom(params.getOrNull(1) ?: return false)) return false
    if (params.getOrNull(3) != Int::class.javaPrimitiveType) return false
    val names = params.map { it.name }
    val hasContentDom = names.getOrNull(0) == "org.epub.html.node.ContentDom" ||
        names.getOrNull(0)?.endsWith(".ContentDom") == true
    val hasVisibleWindow = names.getOrNull(2) == "org.epub.ui.ContentVisibleTextWindow" ||
        names.getOrNull(2)?.endsWith(".ContentVisibleTextWindow") == true
    return hasContentDom && hasVisibleWindow
}

internal fun ReaderHook.jumpToSearchResult(result: FullTextSearchResult, resultIndex: Int): Boolean {
    val state = searchNavigationState ?: lastSearchState ?: return false
    val origin = activeSearchNavigation?.returnTarget ?: currentReadingTarget() ?: return false
    if (result.cfi.isNullOrBlank()) return false
    searchNavigationState = state
    if (activeSearchNavigation == null) {
        activeSearchNavigation = SearchNavigationState(state.bookKey, origin, -1)
        persistSearchOrigin(activeSearchNavigation!!)
    }
    val navigator = searchNavigator ?: ReaderSearchNavigator(this).also { searchNavigator = it }
    return navigator.submit(result, resultIndex)
}

internal fun ReaderHook.returnToSearchOrigin(
    clearNavigation: Boolean = true,
    removeBar: Boolean = true,
) {
    searchNavigator?.cancel()
    cancelSelectionPagerConfirmation()
    val navigation = activeSearchNavigation ?: return
    if (!isSearchNavigationCurrent(navigation)) {
        clearStaleSearchNavigation()
        return
    }
    val target = navigation.returnTarget
    val jumped = runCatching {
        jumpToSearchCfi(
            receiver = lastCatalogContext?.intentReceiver,
            viewModel = currentViewModelRef?.get(),
            cfi = target.cfi,
            chapterIndex = target.chapterIndex,
            title = target.title,
            summary = target.summary,
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX full-text search return failed: ${it.stackTraceToString()}")
    }.getOrDefault(false)
    if (clearNavigation) { activeSearchNavigation = null; searchNavigationState = null }
    if (jumped || clearNavigation) clearPersistedSearchOrigin()
    clearSearchResultHighlight()
    activityProvider()?.let { activity ->
        activity.runOnUiThread {
            if (!jumped) Toast.makeText(activity, "\u8fd4\u56de\u8fdb\u5ea6\u5931\u8d25", Toast.LENGTH_SHORT).show()
            if (removeBar) removeSearchNavigationBar()
        }
    }
}

internal fun ReaderHook.jumpRelativeSearchResult(step: Int) {
    val state = searchNavigationState ?: return
    val navigation = activeSearchNavigation ?: return
    if (navigation.bookKey != state.bookKey) { clearStaleSearchNavigation(); return }
    if (state.results.isEmpty()) return
    val from = searchNavigator?.cursorIndex ?: navigation.currentIndex
    val next = (from + step).coerceIn(0, state.results.lastIndex)
    if (next == from) return
    val navigator = searchNavigator ?: ReaderSearchNavigator(this).also { searchNavigator = it }
    if (!navigator.submit(state.results[next], next)) {
        activityProvider()?.let {
            Toast.makeText(it, "阅读会话已变化，请重新打开全文搜索", Toast.LENGTH_LONG).show()
        }
    }
}

internal fun ReaderHook.ensureSearchNavigationBar(activity: Activity) {
    if (activity.isFinishing || activity.isDestroyed) return
    val nav = activeSearchNavigation ?: return
    val state = searchNavigationState ?: return
    if (nav.bookKey != state.bookKey) { clearStaleSearchNavigation(); return }
    var bar = searchNavigationView?.takeIf { it.activity === activity }
    if (bar == null) {
        removeSearchNavigationBar()
        val candidate = try {
            val candidate = ReaderSearchNavigationBar(activity, this)
            candidate.render(searchNavigator?.cursorIndex ?: nav.currentIndex, state.results.size, nav.currentIndex >= 0, searchNavigator?.failedIndex != null)
            candidate.visible(!readerBottomMenuVisible)
            candidate.attach()
            candidate
        } catch (error: Exception) {

            XposedBridge.logError("$LOG_PREFIX search navigation bar attach failed", error)
            Toast.makeText(activity, "已定位结果，但搜索导航栏加载失败，请重新打开全文搜索", Toast.LENGTH_LONG).show()
            return
        }
        searchNavigationView = candidate
        bar = candidate
    }

    ensureSearchOverlayThemeCallbacks(activity)
    bar.render(searchNavigator?.cursorIndex ?: nav.currentIndex, state.results.size, nav.currentIndex >= 0, searchNavigator?.failedIndex != null)
    bar.visible(!readerBottomMenuVisible)
}
internal fun ReaderHook.updateSearchNavigationBarPosition() {
    searchNavigationView?.visible(!readerBottomMenuVisible)
}
internal fun ReaderHook.isSearchNavigationCurrent(navigation: SearchNavigationState): Boolean =
    searchNavigationState?.bookKey == navigation.bookKey

internal fun ReaderHook.clearStaleSearchNavigation() {
    searchNavigator?.cancel()
    val owned = activeSearchNavigation != null || searchNavigationState != null
    activeSearchNavigation = null
    searchNavigationState = null
    if (owned || activeSearchHighlightSession != null) {
        clearPersistedSearchOrigin()
        clearSearchResultHighlight()
        clearHostSearchJump()
    }
    activityProvider()?.runOnUiThread { removeSearchNavigationBar() }
}

internal fun ReaderHook.scheduleRestorePersistedSearchOrigin(reason: String) {
    if (activeSearchNavigation != null || pendingSearchOriginRestore) return
    val activity = activityProvider() ?: return
    val persisted = readPersistedSearchOrigin() ?: return
    if (!isPersistedSearchOriginForCurrentBook(persisted)) return
    pendingSearchOriginRestore = true
    activity.window?.decorView?.postDelayed({
        restorePersistedSearchOrigin(reason)
    }, SEARCH_ORIGIN_RESTORE_DELAY_MS)
}

internal fun ReaderHook.restorePersistedSearchOrigin(reason: String) {
    val persisted = readPersistedSearchOrigin()
    if (persisted == null) {
        pendingSearchOriginRestore = false
        return
    }
    if (!isPersistedSearchOriginForCurrentBook(persisted)) {
        pendingSearchOriginRestore = false
        return
    }
    val viewModel = currentViewModelRef?.get()
    if (viewModel == null && lastCatalogContext?.intentReceiver == null) {
        pendingSearchOriginRestore = false
        return
    }
    val jumped = runCatching {
        jumpToSearchCfi(
            receiver = lastCatalogContext?.intentReceiver,
            viewModel = viewModel,
            cfi = persisted.returnTarget.cfi,
            chapterIndex = persisted.returnTarget.chapterIndex,
            title = persisted.returnTarget.title,
            summary = persisted.returnTarget.summary,
        )
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX persisted search origin restore failed: ${it.stackTraceToString()}")
    }.getOrDefault(false)
    if (jumped) {
        XposedBridge.log("$LOG_PREFIX persisted search origin restored reason=$reason cfi=${persisted.returnTarget.cfi}")
        clearPersistedSearchOrigin()
        activeSearchNavigation = null
        clearSearchResultHighlight()
        activityProvider()?.runOnUiThread { removeSearchNavigationBar() }
    }
    pendingSearchOriginRestore = false
}

internal fun ReaderHook.isPersistedSearchOriginForCurrentBook(persisted: PersistedSearchOrigin): Boolean {
    if (System.currentTimeMillis() - persisted.timestamp > SEARCH_ORIGIN_MAX_AGE_MS) {
        clearPersistedSearchOrigin()
        return false
    }
    lastCatalogContext?.let { context ->
        val currentKey = bookKey(context)
        if (currentKey.isNotBlank() && persisted.bookKey.isNotBlank()) {
            return currentKey == persisted.bookKey
        }
    }
    val currentRoot = currentEpubRoot()?.absolutePath.orEmpty()
    return currentRoot.isNotBlank() &&
        persisted.epubRoot.isNotBlank() &&
        currentRoot == persisted.epubRoot
}

internal fun ReaderHook.persistSearchOrigin(navigation: SearchNavigationState) {
    val activity = activityProvider() ?: return
    val target = navigation.returnTarget
    activity.applicationContext
        .getSharedPreferences(SEARCH_ORIGIN_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putLong(SEARCH_ORIGIN_KEY_TIMESTAMP, System.currentTimeMillis())
        .putString(SEARCH_ORIGIN_KEY_BOOK, navigation.bookKey)
        .putString(SEARCH_ORIGIN_KEY_EPUB_ROOT, currentEpubRoot()?.absolutePath.orEmpty())
        .putString(SEARCH_ORIGIN_KEY_CFI, target.cfi)
        .putInt(SEARCH_ORIGIN_KEY_CHAPTER_INDEX, target.chapterIndex)
        .putString(SEARCH_ORIGIN_KEY_TITLE, target.title)
        .putString(SEARCH_ORIGIN_KEY_SUMMARY, target.summary)
        .apply()
}

internal fun ReaderHook.readPersistedSearchOrigin(): PersistedSearchOrigin? {
    val activity = activityProvider() ?: return null
    val prefs = activity.applicationContext.getSharedPreferences(SEARCH_ORIGIN_PREFS, Context.MODE_PRIVATE)
    val cfi = prefs.getString(SEARCH_ORIGIN_KEY_CFI, null)?.takeIf { it.isNotBlank() } ?: return null
    return PersistedSearchOrigin(
        timestamp = prefs.getLong(SEARCH_ORIGIN_KEY_TIMESTAMP, 0L),
        bookKey = prefs.getString(SEARCH_ORIGIN_KEY_BOOK, null).orEmpty(),
        epubRoot = prefs.getString(SEARCH_ORIGIN_KEY_EPUB_ROOT, null).orEmpty(),
        returnTarget = ReadingTarget(
            cfi = cfi,
            chapterIndex = prefs.getInt(SEARCH_ORIGIN_KEY_CHAPTER_INDEX, 0),
            title = prefs.getString(SEARCH_ORIGIN_KEY_TITLE, null).orEmpty().ifBlank { "\u539f\u6765\u8fdb\u5ea6" },
            summary = prefs.getString(SEARCH_ORIGIN_KEY_SUMMARY, null).orEmpty(),
        ),
    )
}

internal fun ReaderHook.clearPersistedSearchOrigin() {
    val activity = activityProvider() ?: return
    activity.applicationContext
        .getSharedPreferences(SEARCH_ORIGIN_PREFS, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .apply()
}

internal fun ReaderHook.createSearchResultHighlightMark(result: FullTextSearchResult, resultIndex: Int): Any? {
    val draft = SearchHighlightPlanner.markDraft(
        resultIndex = resultIndex,
        chapter = result.chapterTitle,
        startCfi = result.startCfi,
        fallbackCfi = result.cfi,
        endCfi = result.endCfi,
        matchText = result.matchText,
        base = SEARCH_HIGHLIGHT_MARK_ID_BASE,
    ) ?: return null
    return createReaderMark(
        id = draft.id,
        chapter = draft.chapter,
        startCfi = draft.startCfi,
        endCfi = draft.endCfi,
        quote = draft.quote,
        style = MARK_STYLE_FILL,
        color = MARK_COLOR_RED,
    )
}

internal fun ReaderHook.applySearchResultHighlight(viewModel: Any?, mark: Any,
    ranges: List<com.reamicro.fix.reader.SearchHighlightMarkDraft> = emptyList(),
    owner: Any? = currentEpubStrong ?: currentEpubRef?.get(), generation: Long = searchStateGeneration) {
    if (viewModel !== currentViewModelRef?.get() || owner !== (currentEpubStrong ?: currentEpubRef?.get()) || generation != searchStateGeneration) return
    val id = searchResultHighlightMarkId(mark) ?: return
    activeSearchHighlightId = id
    activeSearchHighlightMark = mark
    activeSearchHighlightSession = checkNotNull(createSearchHighlightSession(mark, ranges, owner, generation)) {
        "搜索高亮会话创建失败，请重新搜索"
    }
    activeSearchHighlightRenderLogId = null
    activeSearchHighlightRenderLogCount = 0
    check(invalidateSearchPaint()) { "宿主高亮刷新接口不可用，请重新进入阅读页" }
}

internal fun ReaderHook.clearSearchResultHighlight(viewModel: Any? = currentViewModelRef?.get()): Boolean {
    val hadPaint = activeSearchHighlightSession != null
    activeSearchHighlightSession = null
    activeSearchHighlightId = null
    activeSearchHighlightMark = null
    activeSearchHighlightRenderLogId = null
    activeSearchHighlightRenderLogCount = 0
    if (hadPaint) invalidateSearchPaint()
    return updateSearchMarks(viewModel, "clear") { marks ->
        val filtered = marks.filterNot(::isSearchResultHighlightMark)
        if (filtered.size == marks.size) marks else filtered
    }
}

internal fun ReaderHook.appendActiveSearchHighlightMark(original: List<*>, label: String? = null): List<Any>? {
    val activeMarks = activeTransientHighlightMarks().let { marks ->
        if (label in setOf("ReaderSharedState", "CatalogPrecompute")) marks.filterNot(::isSearchResultHighlightMark) else marks
    }

    if (activeMarks.isEmpty() && original.none { it != null && isInjectedTransientHighlightMark(it) }) {
        return null
    }
    val cleanMarks = original
        .filterNotNull()
        .filterNot(::isSearchResultHighlightMark)
        .filterNot(::isReadAloudHighlightMark)
        .filterNot(::isSelectionInjectedHighlightMark)
    if (activeMarks.isEmpty()) {
        return if (cleanMarks.size == original.filterNotNull().size) null else ArrayList(cleanMarks)
    }
    val combined = cleanMarks + activeMarks
    if (combined == original) return null
    return ArrayList<Any>(cleanMarks.size + activeMarks.size).apply {
        addAll(cleanMarks)
        addAll(activeMarks)
    }.also { next ->
        label?.let { labelValue ->
            activeMarks.forEach { mark ->
                logSearchHighlightRenderInput(labelValue, original.size, next.size, mark)
            }
        }
    }
}

internal fun ReaderHook.appendActiveSearchHighlightCatalogItemMap(original: Map<*, *>): Map<Any?, Any?>? {
    val marks = activeTransientHighlightMarks().filterNot(::isSearchResultHighlightMark)
    if (marks.isEmpty()) return null
    val next = LinkedHashMap<Any?, Any?>(original.size + marks.size).apply {
        original.forEach { (entryKey, entryValue) -> put(entryKey, entryValue) }
    }
    var changed = false
    marks.forEach { mark ->
        val id = transientHighlightMarkId(mark) ?: return@forEach
        if (catalogItemMapContainsSearchHighlight(next, id)) return@forEach
        val key = resolveActiveSearchHighlightCatalogMapKey(next, mark) ?: return@forEach
        val currentItems = (next[key] as? List<*>)?.filterNotNull().orEmpty()
        if (currentItems.any { catalogChapterItemMarkId(it) == id }) return@forEach
        val item = createSearchHighlightCatalogChapterItem(mark) ?: return@forEach
        next[key] = ArrayList<Any>(currentItems.size + 1).apply {
            addAll(currentItems)
            add(item)
        }
        changed = true
        logSearchHighlightRenderInput("ReaderCatalog", currentItems.size, currentItems.size + 1, mark)
    }
    return if (changed) next else null
}

internal fun ReaderHook.catalogItemMapContainsSearchHighlight(map: Map<*, *>, id: Long): Boolean =
    map.values.any { value ->
        (value as? Iterable<*>)?.any { item -> catalogChapterItemMarkId(item) == id } == true
    }

internal fun ReaderHook.createSearchHighlightCatalogChapterItem(mark: Any): Any? =
    runCatching {
        val cfi = createEpubCfi(callString(mark, "getStartCfi")) ?: return@runCatching null
        val itemClass = classLoader.loadClass(CATALOG_CHAPTER_ITEM_CLASS)
        val ctor = itemClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 4 }
            ?: return@runCatching null
        ctor.isAccessible = true
        ctor.newInstance(null, mark, cfi, false)
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX create search highlight catalog item failed: ${it.stackTraceToString()}")
    }.getOrNull()

internal fun ReaderHook.resolveActiveSearchHighlightCatalogMapKey(map: Map<*, *>, mark: Any): Any? {
    val context = lastCatalogContext ?: return map.keys.firstOrNull()
    val catalog = context.catalog
    val startCfi = callString(mark, "getStartCfi")
    val index = resolveCatalogIndexForCfi(startCfi, catalog)
    val byIndex = index
        ?.takeIf { it in catalog.indices }
        ?.let { callNoArg(catalog[it], "getId") }
        ?.let { id -> map.keys.firstOrNull { key -> key.toString() == id.toString() } ?: id }
    if (byIndex != null) return byIndex

    val chapter = callString(mark, "getChapter")
    if (chapter.isNotBlank()) {
        catalog.firstOrNull { catalogChapterTitle(it) == chapter }
            ?.let { callNoArg(it, "getId") }
            ?.let { id -> return map.keys.firstOrNull { key -> key.toString() == id.toString() } ?: id }
    }
    return map.keys.firstOrNull()
}

internal fun ReaderHook.logSearchHighlightRenderInput(label: String, before: Int, after: Int, mark: Any) {
    if (!settingsProvider().readerHighlightPerformanceLogEnabled) return
    val id = transientHighlightMarkId(mark) ?: return
    if (activeSearchHighlightRenderLogId != id) {
        activeSearchHighlightRenderLogId = id
        activeSearchHighlightRenderLogCount = 0
    }
    if (activeSearchHighlightRenderLogCount >= 8) return
    activeSearchHighlightRenderLogCount++
    XposedBridge.log(
        "$LOG_PREFIX full-text search highlight render $label marks $before->$after " +
            describeSearchHighlightMark(mark),
    )
}

internal fun ReaderHook.logSearchHighlightResolvePage(status: String, count: Int, mark: Any) {
    if (!settingsProvider().readerHighlightPerformanceLogEnabled) return
    val id = transientHighlightMarkId(mark) ?: return
    if (activeSearchHighlightRenderLogId != id) {
        activeSearchHighlightRenderLogId = id
        activeSearchHighlightRenderLogCount = 0
    }
    if (activeSearchHighlightRenderLogCount >= 8) return
    activeSearchHighlightRenderLogCount++
    XposedBridge.log(
        "$LOG_PREFIX full-text search highlight ResolvePage $status result=$count " +
            describeSearchHighlightMark(mark),
    )
}

internal fun ReaderHook.logSearchHighlightContentOverlay(status: String, count: Int, mark: Any) {
    if (!settingsProvider().readerHighlightPerformanceLogEnabled) return
    val id = transientHighlightMarkId(mark) ?: transientHighlightResolvedMarkId(mark) ?: return
    if (activeSearchHighlightRenderLogId != id) {
        activeSearchHighlightRenderLogId = id
        activeSearchHighlightRenderLogCount = 0
    }
    if (activeSearchHighlightRenderLogCount >= 12) return
    activeSearchHighlightRenderLogCount++
    XposedBridge.log(
        "$LOG_PREFIX full-text search highlight ContentOverlay $status count=$count " +
            describeSearchHighlightMark(mark),
    )
}

internal fun ReaderHook.createResolvedSearchHighlightMark(mark: Any): Any? =
    runCatching {
        val cfiClass = classLoader.loadClass("org.epub.html.EpubCFI")
        val cfiObject = companionObject(cfiClass) ?: return@runCatching null
        val create = cfiObject.javaClass.methods.firstOrNull {
            it.name == "create" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
        } ?: return@runCatching null
        val start = create.invoke(cfiObject, callString(mark, "getStartCfi")) ?: return@runCatching null
        val end = create.invoke(cfiObject, callString(mark, "getEndCfi")) ?: return@runCatching null
        val id = (callNoArg(mark, "getId") as? Number)?.toLong() ?: return@runCatching null
        val kind = (callNoArg(mark, "getKind") as? Number)?.toInt() ?: MARK_KIND_HIGHLIGHT
        val style = (callNoArg(mark, "getStyle") as? Number)?.toInt() ?: MARK_STYLE_FILL
        val color = callString(mark, "getColor")
        val note = callString(mark, "getNote")
        val resolvedClass = classLoader.loadClass("org.epub.ui.ResolvedMark")

        resolvedClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 9 }?.let { ctor ->
            ctor.isAccessible = true
            return@let ctor.newInstance(
                id,
                kind,
                start,
                end,
                style,
                color,
                note,
                callString(mark, "getQuote"),
                (callNoArg(mark, "getCreatedAt") as? Number)?.toLong() ?: System.currentTimeMillis(),
            )
        } ?: resolvedClass.getDeclaredConstructor(
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            cfiClass,
            cfiClass,
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
        ).newInstance(id, kind, start, end, style, color, note)
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX create resolved search highlight failed: ${it.stackTraceToString()}")
    }.getOrNull()

private val epubCfiSpinePrefixRegex = Regex("""^epubcfi\(/\d+/\d+""")

internal fun epubCfiSpinePrefix(cfi: String): String? = epubCfiSpinePrefixRegex.find(cfi)?.value

internal fun ReaderHook.createSearchHighlightContentOverlay(
    contentDom: Any?,
    visibleWindow: Any?,
    renderedTextLength: Int,
    mark: Any,
): Any? =
    runCatching {
        if (renderedTextLength <= 0) return@runCatching null
        val quote = callString(mark, "getQuote").takeIf { it.isNotBlank() } ?: return@runCatching null

        val renderingPage = renderingEpubPage.get()
        val markCfiPrefix = epubCfiSpinePrefix(callString(mark, "getStartCfi"))
        val pageCfiPrefix = renderingPage
            ?.let { callNoArg(it, "getStart")?.toString() }
            ?.let(::epubCfiSpinePrefix)
        if (markCfiPrefix != null && pageCfiPrefix != null && markCfiPrefix != pageCfiPrefix) {
            return@runCatching null
        }
        val location = callNoArg(contentDom, "getLocation")
        val baseOffset = ((callNoArg(callNoArg(location, "getOffset"), "getOffset") as? Number)?.toInt() ?: 0)
        val visibleStart = ((callNoArg(visibleWindow, "getStart") as? Number)?.toInt() ?: baseOffset)
        val visibleEnd = ((callNoArg(visibleWindow, "getEndExclusive") as? Number)?.toInt()
            ?: (baseOffset + Int.MAX_VALUE))

        val cfiOffset = SearchHighlightPlanner.cfiCharacterOffset(callString(mark, "getStartCfi"))
        if (cfiOffset != null) {
            val tolerance = SEARCH_HIGHLIGHT_OFFSET_TOLERANCE
            if (cfiOffset + tolerance < visibleStart || cfiOffset - tolerance > visibleEnd) {
                return@runCatching null
            }
        }
        val content = contentDomPlainText(contentDom).takeIf { it.isNotBlank() } ?: return@runCatching null
        val realVisibleEnd = ((callNoArg(visibleWindow, "getEndExclusive") as? Number)?.toInt()
            ?: (baseOffset + content.length))
        val windowStart = (visibleStart - baseOffset).coerceIn(0, content.length)
        val windowEnd = (realVisibleEnd - baseOffset).coerceIn(windowStart, content.length)
        val expectedLocalStart = cfiOffset
            ?.let { it - baseOffset }
            ?.takeIf { it in 0..content.length }

        if (cfiOffset != null && expectedLocalStart == null) return@runCatching null

        if (expectedLocalStart != null &&
            (expectedLocalStart < windowStart || expectedLocalStart + quote.length > windowEnd)
        ) return@runCatching null
        val matchStart = SearchHighlightPlanner.quoteStart(
            content = content,
            quote = quote,
            windowStart = windowStart,
            windowEnd = windowEnd,
            expectedLocalStart = expectedLocalStart,
            tolerance = SEARCH_HIGHLIGHT_OFFSET_TOLERANCE,
        ) ?: return@runCatching null
        val matchEnd = (matchStart + quote.length).coerceAtMost(content.length)
        val localStart = (baseOffset + matchStart - visibleStart).coerceIn(0, renderedTextLength)
        val localEnd = (baseOffset + matchEnd - visibleStart).coerceIn(localStart, renderedTextLength)
        if (localEnd <= localStart) return@runCatching null
        val resolved = createResolvedSearchHighlightMark(mark) ?: return@runCatching null
        val textRange = textRange(localStart, localEnd) ?: return@runCatching null
        val color = markColorTokenToColor(callString(mark, "getColor")) ?: return@runCatching null
        val overlayClass = classLoader.loadClass("org.epub.ui.ContentMarkOverlay")
        val ctor = overlayClass.declaredConstructors.firstOrNull { ctor ->
            val params = ctor.parameterTypes
            params.size == 5 &&
                params[0].name == "org.epub.ui.ResolvedMark" &&
                params[1] == Long::class.javaPrimitiveType &&
                params[2] == Int::class.javaPrimitiveType &&
                params[3] == Long::class.javaPrimitiveType
        } ?: overlayClass.declaredConstructors.firstOrNull { ctor ->
            val params = ctor.parameterTypes
            params.size == 4 &&
                params[0].name == "org.epub.ui.ResolvedMark" &&
                params[1] == Long::class.javaPrimitiveType &&
                params[2] == Int::class.javaPrimitiveType &&
                params[3] == Long::class.javaPrimitiveType
        }
            ?: return@runCatching null
        ctor.isAccessible = true
        val style = (callNoArg(mark, "getStyle") as? Number)?.toInt() ?: MARK_STYLE_FILL
        if (ctor.parameterTypes.size == 5) {
            ctor.newInstance(resolved, textRange, style, color, null)
        } else {
            ctor.newInstance(resolved, textRange, style, color)
        }
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX create search highlight overlay failed: ${it.stackTraceToString()}")
    }.getOrNull()

internal fun ReaderHook.describeSearchHighlightMark(mark: Any): String {
    val id = (callNoArg(mark, "getId") as? Number)?.toLong() ?: 0L
    val style = (callNoArg(mark, "getStyle") as? Number)?.toInt() ?: -1
    val color = callString(mark, "getColor")
    val start = callString(mark, "getStartCfi")
    val end = callString(mark, "getEndCfi")
    val quote = callString(mark, "getQuote").take(24)
    return "id=$id style=$style color=$color start=$start end=$end quote=$quote"
}

internal fun ReaderHook.updateSearchMarks(viewModel: Any?, label: String, transform: (List<Any>) -> List<Any>): Boolean =
    runCatching {
        val target = viewModel ?: return@runCatching false.also {
            XposedBridge.log("$LOG_PREFIX full-text search highlight $label skipped: no viewModel")
        }
        var cls: Class<*>? = target.javaClass
        var marksField: java.lang.reflect.Field? = null
        while (cls != null && marksField == null) {
            marksField = cls.declaredFields.firstOrNull { it.name == "_marks" }
            cls = cls.superclass
        }
        marksField ?: return@runCatching false.also {
            XposedBridge.log("$LOG_PREFIX full-text search highlight $label skipped: _marks not found")
        }
        val marksFlow = marksField
            .apply { isAccessible = true }
            .get(target)
            ?: return@runCatching false.also {
                XposedBridge.log("$LOG_PREFIX full-text search highlight $label skipped: marksFlow null")
            }
        val current = (XposedHelpers.callMethod(marksFlow, "getValue") as? List<*>)?.filterNotNull().orEmpty()
        val next = transform(current)
        if (next === current) return@runCatching true
        val updated = runCatching {
            XposedHelpers.callMethod(marksFlow, "setValue", next)
            true
        }.recoverCatching {
            val compareResult = XposedHelpers.callMethod(marksFlow, "compareAndSet", current, next)
            compareResult == true
        }.getOrElse { error ->
            XposedBridge.log(
                "$LOG_PREFIX full-text search highlight $label skipped: marksFlow update failed " +
                    "${marksFlow.javaClass.name}: ${error.message}",
            )
            false
        }
        if (!updated) return@runCatching false
        val activeId = activeSearchHighlightId
            ?: activeReadAloudHighlightId
            ?: 0L
        XposedBridge.log(
            "$LOG_PREFIX full-text search highlight $label marks ${current.size}->${next.size} " +
                "active=$activeId",
        )
        true
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX update search highlight marks failed: ${it.stackTraceToString()}")
    }.getOrDefault(false)

internal fun ReaderHook.isSearchResultHighlightMark(mark: Any): Boolean {
    val id = searchResultHighlightMarkId(mark) ?: return false
    return SearchHighlightPlanner.isHighlightId(id, SEARCH_HIGHLIGHT_MARK_ID_BASE, SEARCH_HIGHLIGHT_MARK_ID_RANGE)
}

internal fun ReaderHook.isInjectedTransientHighlightMark(mark: Any): Boolean {
    val id = (callNoArg(mark, "getId") as? Number)?.toLong() ?: return false
    if (SearchHighlightPlanner.isHighlightId(id, SEARCH_HIGHLIGHT_MARK_ID_BASE, SEARCH_HIGHLIGHT_MARK_ID_RANGE)) {
        return true
    }
    if (id >= READ_ALOUD_HIGHLIGHT_MARK_ID_BASE &&
        id < READ_ALOUD_HIGHLIGHT_MARK_ID_BASE + READ_ALOUD_HIGHLIGHT_MARK_ID_RANGE
    ) {
        return true
    }
    return id >= SELECTION_HIGHLIGHT_MARK_ID_BASE &&
        id < SELECTION_HIGHLIGHT_MARK_ID_BASE + SELECTION_HIGHLIGHT_MARK_ID_RANGE
}

internal fun ReaderHook.searchResultHighlightMarkId(mark: Any): Long? {
    val id = (callNoArg(mark, "getId") as? Number)?.toLong() ?: return null
    return if (SearchHighlightPlanner.isHighlightId(id, SEARCH_HIGHLIGHT_MARK_ID_BASE, SEARCH_HIGHLIGHT_MARK_ID_RANGE)) {
        id
    } else {
        null
    }
}

internal fun ReaderHook.searchResultHighlightResolvedMarkId(mark: Any): Long? {
    val id = (callNoArg(mark, "getId") as? Number)?.toLong() ?: return null
    return if (SearchHighlightPlanner.isHighlightId(id, SEARCH_HIGHLIGHT_MARK_ID_BASE, SEARCH_HIGHLIGHT_MARK_ID_RANGE)) {
        id
    } else {
        null
    }
}

internal fun ReaderHook.searchResultHighlightOverlayMarkId(overlay: Any): Long? {
    val mark = callNoArg(overlay, "getMark") ?: return null
    return transientHighlightResolvedMarkId(mark)
}

internal fun ReaderHook.removeSearchNavigationBar() {
    searchNavigationView?.dispose()
    searchNavigationView = null
    val activity = searchNavigationBarActivityRef?.get() ?: activityProvider()
    searchNavigationBarRef = null
    searchNavigationBarActivityRef = null
    postRemoveTaggedViews(activity, SEARCH_NAV_BAR_TAG)
    maybeUnregisterSearchOverlayThemeCallbacks()
}

internal fun ReaderHook.searchFileForHref(root: File, href: String): File? {
    val normalized = normalizePath(href.substringBefore('#'))
    if (normalized.isBlank()) return null
    val candidates = buildList {
        add(File(root, normalized))
        if ('/' in normalized) add(File(root, normalized.substringAfter('/')))
        if ('/' in normalized) add(File(root, normalized.substringAfterLast('/')))
    }
    return candidates.firstNotNullOfOrNull { candidate ->
        candidate.canonicalFileSafe()?.takeIf { it.isFile && it.isTextContentFile() }
    }
}

internal fun ReaderHook.searchChapterTitle(
    raw: String,
    chapter: CatalogChapterEntry?,
    file: File,
    fallbackTitlePath: String = "",
): String {
    val catalogTitle = chapter?.titlePath.orEmpty().ifBlank { fallbackTitlePath }.normalizeChapterTitle()
    val fileTitle = fileChapterTitleHint(raw)
    return chooseChapterTitle(catalogTitle, fileTitle).ifBlank { file.nameWithoutExtension }
}

internal fun ReaderHook.indexedSearchText(raw: String, cfiBase: CfiBase?): IndexedSearchText =
    hostIndexedSearchText(raw, cfiBase)

internal fun ReaderHook.htmlToSearchText(value: String): String =
    bodyOnlyHtml(value)
        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</(p|div|h[1-6]|li|section|article)>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .decodeBasicHtmlEntities()
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex("\\n\\s+"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

internal fun ReaderHook.isDifferentSearchBook(previous: CatalogContext, next: CatalogContext): Boolean {
    val previousKey = searchBookIdentity(previous)
    val nextKey = searchBookIdentity(next)
    return previousKey.isNotBlank() && nextKey.isNotBlank() && previousKey != nextKey
}

internal fun ReaderHook.searchBookIdentity(context: CatalogContext): String =
    listOf(
        callString(context.book, "getId"),
        callString(context.book, "getBookId"),
        bookTitle(context),
    ).firstOrNull { it.isNotBlank() }.orEmpty()
