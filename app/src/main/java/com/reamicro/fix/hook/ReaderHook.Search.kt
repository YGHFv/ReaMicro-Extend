package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread

import com.reamicro.fix.core.HostEpubRuntimeBindings
import com.reamicro.fix.core.HostReaderRecordBindings
import com.reamicro.fix.core.HostReaderWindowBindings
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.Toast
import com.reamicro.fix.reader.SearchHighlightPlanner
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import com.reamicro.fix.reload.TaskCancellation
import java.io.File
import java.lang.ref.WeakReference
import com.reamicro.fix.hook.reader.*

internal fun ReaderHook.canRunFullTextSearch(): Boolean {
    val snapshot = settingsProvider()
    return snapshot.moduleEnabled
}

internal fun ReaderHook.canSearchCurrentBook(): Boolean {
    val hasPage = currentReaderPage() != null
    if (!hasPage && currentEpubStrong != null && currentSessionRef?.get() != null) {
        currentSearchPagerMode = 3
    }
    return canRunFullTextSearch() && currentEpubRoot() != null &&
        (hasPage || currentScrollElement != null || currentSearchPagerMode == 3)
}

internal fun ReaderHook.injectSearchHighlightIntoReaderCatalog(param: HookCallback.Call) {
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
    searchInProgress = false
    currentSearchPagerMode = null
    searchNavigator?.close()
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
    lastSearchListPosition = null
    searchNavigationState = null
    pendingSearchReturnCaptures = 0
    activeSearchNavigation = null
    searchIndexState = null
    if (removeOverlays) {
        activityProvider()?.runOnModuleUiThread {
            closeSearchPage()
            removeSearchNavigationBar()
        }
    }
    HookRuntime.log("$LOG_PREFIX full-text search state reset: $reason")
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
    HostEpubRuntimeBindings.current?.method("searchIcon")?.invoke(null)
}.getOrNull()

internal fun ReaderHook.ensureSearchOverlayThemeCallbacks(activity: Activity) {
    if (searchOverlayThemeCallbacksActivityRef?.get() === activity && searchOverlayThemeCallbacks != null) return
    unregisterSearchOverlayThemeCallbacks()
    val callbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            activity.runOnModuleUiThread {
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
        HookRuntime.log(
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
        HookRuntime.log(
            "$LOG_PREFIX search blocked context: receiver=${bottomSearchReceiverRef?.get() != null} " +
                "book=${bottomSearchBookRef?.get() != null} lastCatalog=${lastCatalogContext != null} " +
                "lastCatalogBook=${lastCatalogContext?.book != null}",
        )
        Toast.makeText(activity, "\u6682\u65e0\u6cd5\u641c\u7d22\u5f53\u524d\u4e66\u7c4d", Toast.LENGTH_SHORT).show()
        return
    }
    activity.runOnModuleUiThread {
        com.reamicro.fix.core.InjectedModuleContext.runUiAction(activity) {
            showFullTextSearchPage(activity, context)
        }
    }
}

internal fun ReaderHook.cancelSearchPageWork() {
    searchWorker.cancel()
    searchRunSeq += 1
    searchInProgress = false
}

internal fun ReaderHook.closeSearchPage(cancelWork: Boolean = true) {
    searchPageDialogRef?.get()?.dismiss()
    searchPageDialogRef = null
    if (cancelWork) cancelSearchPageWork()
    activeSearchPageToken = 0L
    activeSearchPageUpdate = null
}

internal fun ReaderHook.publishSearchState(state: SearchState) {
    lastSearchState = state
    val previous = searchNavigationState
    if (previous?.bookKey == state.bookKey && previous.keyword == state.keyword) {
        searchNavigationState = state
        activityProvider()?.let(::ensureSearchNavigationBar)
    }
    activeSearchPageUpdate?.invoke(state, !state.complete)
}

internal fun ReaderHook.startFullTextSearch(keyword: String, context: CatalogContext, retainedResults: List<FullTextSearchResult>) {
    val key = bookKey(context)
    val request = ReaderSearchRequest(searchStateGeneration, searchRunSeq, activeSearchPageToken)
    val owner = WeakReference(activeReaderActivity() ?: return)
    val presentation = onDemandRefreshCoordinator.presentation()
    fun current() = owner.get()?.let { it === activeReaderActivity() } == true &&
        onDemandRefreshCoordinator.isPresentation(presentation) &&
        request.acceptsSearch(searchStateGeneration, searchRunSeq)
    val updates = LatestSearchUpdate<SearchState>(post = { current() && readerUiHandler.post(it) }) { state ->
        if (current()) {
            if (state.complete) searchInProgress = false
            publishSearchState(state)
        }
    }
    searchInProgress = true
    try {
        searchWorker.submit work@ { ticket ->
            try {
                if (!current() || !ticket.isCurrent()) return@work
                searchFullTextStreaming(keyword, context, request) { results, done ->
                    if (current() && ticket.isCurrent() && (done || results.size >= retainedResults.size))
                        updates.offer(SearchState(key, keyword, results, complete = done))
                }
            } catch (failure: Throwable) {
                TaskCancellation.rethrowIfCancelled(failure)
                if (!current() || !ticket.isCurrent()) return@work
                HookRuntime.log("$LOG_PREFIX full-text search failed: ${failure.javaClass.simpleName}")
                readerUiHandler.post {
                    if (current() && ticket.isCurrent()) {
                        searchInProgress = false
                        (searchPageDialogRef?.get() as? HostFullTextSearchDialog)?.error("搜索失败：${failure.message.orEmpty()}")
                    }
                }
            }
        }
    } catch (failure: Throwable) {
        if (!current()) return
        searchInProgress = false
        HookRuntime.logError("$LOG_PREFIX full-text search start failed", failure)
        (searchPageDialogRef?.get() as? HostFullTextSearchDialog)?.error("搜索暂不可用，请重试")
    }
}

internal fun ReaderHook.resumeSearchPageWork() {
    if (searchInProgress || activeReaderActivity() == null || searchPageDialogRef?.get()?.isShowing != true) return
    val state = lastSearchState?.takeIf { !it.complete && it.keyword.isNotBlank() } ?: return
    val context = lastCatalogContext?.takeIf { bookKey(it) == state.bookKey } ?: return
    if (activeSearchPageToken == 0L) return
    activeSearchPageUpdate?.invoke(state, true)
    startFullTextSearch(state.keyword, context, state.results)
}

internal fun ReaderHook.showFullTextSearchPage(activity: Activity, context: CatalogContext) {
    if (activeReaderActivity() !== activity) return
    closeSearchPage(cancelWork = false)
    val pageBookKey = bookKey(context)
    val pageToken = System.nanoTime()
    val restored = lastSearchState?.takeIf { it.bookKey == pageBookKey }
        ?.let { it.copy(results = it.resultsForReceiver(context.intentReceiver)) }
    if (restored == null && searchInProgress) cancelSearchPageWork()
    val position = restored?.let { lastSearchListPosition?.forSearch(pageBookKey, it.keyword) }
    if (activeSearchNavigation?.bookKey?.let { it != pageBookKey } == true) clearStaleSearchNavigation()
    lastSearchState = restored
    var chosen = false
    lateinit var panel: HostFullTextSearchDialog
    fun submit(keyword: String, resume: Boolean = false) {
        if (searchPageDialogRef?.get() !== panel) return
        if (!resume && keyword.isNotBlank() && keyword == lastSearchState?.keyword && lastSearchState?.bookKey == pageBookKey &&
            (searchInProgress || lastSearchState?.complete == true)) return
        cancelSearchPageWork()
        if (!resume) {
            clearStaleSearchNavigation()
            lastSearchState = null
            lastSearchListPosition = null
        }
        if (keyword.isBlank()) {
            panel.render("", emptyList(), false, resetScroll = true)
            return
        }
        if (lastCatalogContext?.let(::bookKey)?.let { it != pageBookKey } == true) { panel.error("图书已切换，请重新打开搜索"); return }
        val retainedResults = if (resume) restored?.results.orEmpty() else emptyList()
        lastSearchState = SearchState(pageBookKey, keyword, retainedResults, complete = false)
        panel.render(keyword, retainedResults, true, activeSearchNavigation?.currentIndex ?: -1, resetScroll = !resume)
        startFullTextSearch(keyword, context, retainedResults)
    }
    val active = activeSearchNavigation?.currentIndex ?: -1
    val selectedRow = restored?.let { ReaderSearchPresentation.listIndex(it.results.map { r -> r.volumeTitle }, active) }
    panel = HostFullTextSearchDialog(activity, restored?.keyword.orEmpty(),
        selectedRow?.let { (it - 1).coerceAtLeast(0) } ?: position?.index ?: 0, if (selectedRow != null) 0 else position?.offset ?: 0,
        initialTheme = readerSearchTheme,
        fontSelection = { if (settingsProvider().canUseFontSettings) settings?.fontSettings()?.globalFamily.orEmpty() else "" },
        onSearch = { submit(it) },
        onChoose = { result, index ->
            if (lastCatalogContext?.let(::bookKey)?.let { it != pageBookKey } == true) {
                panel.error("图书已切换，请重新打开搜索"); false
            } else {
                searchNavigationState = lastSearchState
                chosen = runCatching { jumpToSearchResult(result, index) }.getOrElse {
                    HookRuntime.logError("ReaMicro search entry failed", it)
                    false
                }
                if (!chosen) {
                    clearStaleSearchNavigation()
                    panel.error("阅读位置暂不可用，请关闭搜索、等待正文加载完成后重试")
                }
                chosen
            }
        },
        onClosed = { index, offset ->

            if (activeSearchPageToken == pageToken) {
                lastSearchState?.takeIf { it.bookKey == pageBookKey }?.let {
                    lastSearchListPosition = ReaderSearchListPosition(pageBookKey, it.keyword, index, offset)
                }
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
    restored?.let { panel.render(it.keyword, it.results, !it.complete, activeSearchNavigation?.currentIndex ?: -1) }
    panel.show()
    if (restored?.complete == false && !searchInProgress) submit(restored.keyword, resume = true)
}

internal fun ReaderHook.jumpToSearchResult(result: FullTextSearchResult, resultIndex: Int): Boolean {
    val state = searchNavigationState ?: lastSearchState
    if (state == null) return false.also { HookRuntime.log("ReaMicro search jump rejected: state") }
    val origin = activeSearchNavigation?.returnTarget ?: currentReadingTarget()
    if (origin == null) return false.also { HookRuntime.log("ReaMicro search jump rejected: origin") }
    if (result.cfi.isNullOrBlank()) return false.also { HookRuntime.log("ReaMicro search jump rejected: cfi") }
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
    // 先结束搜索并释放索引，再让宿主加载原章节；标记等目标页确认到达后再清除。
    cancelSearchPageWork()
    searchDocumentCache.clear()
    searchIndexState = null
    val previousState = searchNavigationState
    activeSearchNavigation = null
    clearHostSearchJump()
    pendingSearchReturnCaptures = 0
    clearSearchResultHighlight()
    val target = navigation.returnTarget
    val jumped = runCatching {
        val vm = currentViewModelRef?.get()
        if (vm != null && searchScrollBridge?.originCommand(vm, target) != null) return@runCatching true
        jumpToSearchCfi(
            receiver = lastCatalogContext?.intentReceiver,
            viewModel = vm,
            cfi = target.cfi,
            chapterIndex = target.chapterIndex,
            title = target.title,
            summary = target.summary,
        )
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX full-text search return failed: ${it.stackTraceToString()}")
    }.getOrDefault(false)
    if (jumped && clearNavigation) searchNavigationState = null
    else {
        activeSearchNavigation = navigation
        searchNavigationState = previousState
        if (!jumped) persistSearchOrigin(navigation)
    }
    activityProvider()?.let { activity ->
        activity.runOnModuleUiThread {
            if (!jumped) Toast.makeText(activity, "\u8fd4\u56de\u8fdb\u5ea6\u5931\u8d25", Toast.LENGTH_SHORT).show()
            if (removeBar && jumped) removeSearchNavigationBar()
        }
    }
    if (jumped) currentViewModelRef?.get()?.let { scheduleSearchOriginClear(target, it) }
}

/** 跳转只代表意图已派发；缓存必须在真实页面确认到达后才释放。 */
private fun ReaderHook.scheduleSearchOriginClear(target: ReadingTarget, viewModel: Any) {
    val root = currentEpubRoot() ?: return
    val session = captureRefreshSession(viewModel, root) ?: return
    val sequence = searchRunSeq
    fun current() = isRefreshSessionCurrent(session) && searchRunSeq == sequence && activeSearchNavigation == null
    var attempts = 0
    var scrollRequested = false
    val targetCfi = createEpubCfi(target.cfi)
    val check = object : Runnable {
        override fun run() {
            if (!current()) return
            if (target.scrollOffsetPx != null && currentSearchPagerMode == 3 && !scrollRequested) {
                searchScrollBridge?.originCommand(viewModel, target)?.let { command ->
                    scrollRequested = true
                    val bridge = searchScrollBridge
                    try {
                        searchWorker.submit { ticket ->
                            try {
                                bridge?.execute(command) { current() && ticket.isCurrent() }
                            } catch (error: Throwable) {
                                TaskCancellation.rethrowIfCancelled(error)
                                if (current() && ticket.isCurrent())
                                    HookRuntime.logError("$LOG_PREFIX scroll origin restore failed", error)
                            }
                        }
                    } catch (error: Throwable) {
                        if (current()) HookRuntime.logError("$LOG_PREFIX scroll origin restore start failed", error)
                    }
                }
            }
            val position = currentReadingTarget()
            val page = runCatching { searchPageBridge?.page(viewModel) }.getOrNull()
            val arrived = if (target.scrollOffsetPx != null && currentSearchPagerMode == 3) {
                position?.cfi == target.cfi && position.scrollOffsetPx?.let {
                    kotlin.math.abs(it.toLong() - target.scrollOffsetPx.toLong()) <= 2
                } == true
            } else position?.cfi == target.cfi ||
                (targetCfi != null && page != null &&
                    runCatching { HostReaderWindowBindings.current?.contains(page, targetCfi) == true }.getOrDefault(false))
            if (arrived && current()) {
                clearPersistedSearchOrigin()
                HookRuntime.log("$LOG_PREFIX search origin arrival confirmed cfi=${target.cfi}")
                return
            }
            if (++attempts < 24) readerUiHandler.postDelayed(this, 125L)
            else HookRuntime.log("$LOG_PREFIX search origin arrival unconfirmed; preserving recovery marker")
        }
    }
    readerUiHandler.postDelayed(check, 200L)
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

            HookRuntime.logError("$LOG_PREFIX search navigation bar attach failed", error)
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
    if (owned) {
        cancelSearchPageWork()
        searchDocumentCache.clear()
        searchIndexState = null
    }
    activeSearchNavigation = null
    searchNavigationState = null
    if (owned || activeSearchHighlightSession != null) {
        clearPersistedSearchOrigin()
        clearSearchResultHighlight()
        clearHostSearchJump()
    }
    activityProvider()?.runOnModuleUiThread { removeSearchNavigationBar() }
}

internal fun ReaderHook.scheduleRestorePersistedSearchOrigin(reason: String) {
    if (activeSearchNavigation != null || pendingSearchOriginRestore) return
    val owner = WeakReference(activeReaderActivity() ?: return)
    val presentation = onDemandRefreshCoordinator.presentation()
    val generation = onDemandRefreshCoordinator.generation()
    val sequence = searchRunSeq
    val persisted = readPersistedSearchOrigin() ?: return
    if (!isPersistedSearchOriginForCurrentBook(persisted)) return
    pendingSearchOriginRestore = true
    val accepted = readerUiHandler.postDelayed({
        if (!onDemandRefreshCoordinator.isGeneration(generation) ||
            !onDemandRefreshCoordinator.isPresentation(presentation) ||
            owner.get()?.let { it === activeReaderActivity() } != true) return@postDelayed
        if (searchRunSeq != sequence) { pendingSearchOriginRestore = false; return@postDelayed }
        restorePersistedSearchOrigin(reason)
    }, SEARCH_ORIGIN_RESTORE_DELAY_MS)
    if (!accepted) pendingSearchOriginRestore = false
}

internal fun ReaderHook.restorePersistedSearchOrigin(reason: String) {
    if (activeReaderActivity() == null) { pendingSearchOriginRestore = false; return }
    if (activeSearchNavigation != null) { pendingSearchOriginRestore = false; return }
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
        if (viewModel != null && searchScrollBridge?.originCommand(viewModel, persisted.returnTarget) != null) {
            return@runCatching true
        }
        jumpToSearchCfi(
            receiver = lastCatalogContext?.intentReceiver,
            viewModel = viewModel,
            cfi = persisted.returnTarget.cfi,
            chapterIndex = persisted.returnTarget.chapterIndex,
            title = persisted.returnTarget.title,
            summary = persisted.returnTarget.summary,
        )
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX persisted search origin restore failed: ${it.stackTraceToString()}")
    }.getOrDefault(false)
    if (jumped) {
        HookRuntime.log("$LOG_PREFIX persisted search origin dispatched reason=$reason cfi=${persisted.returnTarget.cfi}")
        viewModel?.let { scheduleSearchOriginClear(persisted.returnTarget, it) }
        activeSearchNavigation = null
        clearSearchResultHighlight()
        activityProvider()?.runOnModuleUiThread { removeSearchNavigationBar() }
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
        .putInt(SEARCH_ORIGIN_KEY_SCROLL_OFFSET, target.scrollOffsetPx ?: -1)
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
            scrollOffsetPx = prefs.getInt(SEARCH_ORIGIN_KEY_SCROLL_OFFSET, -1).takeIf { it >= 0 },
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

internal fun ReaderHook.createSearchHighlightCatalogChapterItem(mark: Any): Any? = runCatching {
    val cfi = createEpubCfi(readerMarkValue(mark, "startCfi")?.toString().orEmpty()) ?: return@runCatching null
    requireNotNull(HostEpubRuntimeBindings.current).catalogItem(mark, cfi)
}.onFailure { HookRuntime.log("$LOG_PREFIX create search highlight catalog item failed: ${it.stackTraceToString()}") }.getOrNull()

internal fun ReaderHook.resolveActiveSearchHighlightCatalogMapKey(map: Map<*, *>, mark: Any): Any? {
    val context = lastCatalogContext ?: return map.keys.firstOrNull()
    val catalog = context.catalog
    val startCfi = readerMarkValue(mark, "startCfi")?.toString().orEmpty()
    val index = resolveCatalogIndexForCfi(startCfi, catalog)
    val byIndex = index
        ?.takeIf { it in catalog.indices }
        ?.let { catalogChapterId(catalog[it]) }
        ?.let { id -> map.keys.firstOrNull { key -> key.toString() == id.toString() } ?: id }
    if (byIndex != null) return byIndex

    val chapter = readerMarkValue(mark, "chapter")?.toString().orEmpty()
    if (chapter.isNotBlank()) {
        catalog.firstOrNull { catalogChapterTitle(it) == chapter }
            ?.let { catalogChapterId(it) }
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
    HookRuntime.log(
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
    HookRuntime.log(
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
    HookRuntime.log(
        "$LOG_PREFIX full-text search highlight ContentOverlay $status count=$count " +
            describeSearchHighlightMark(mark),
    )
}

internal fun ReaderHook.createResolvedSearchHighlightMark(mark: Any): Any? =
    runCatching {
        requireNotNull(com.reamicro.fix.core.HostReaderRecordBindings.current).resolved(mark)
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX create resolved search highlight failed: ${it.stackTraceToString()}")
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
        if (renderedTextLength <= 0 || contentDom == null) return@runCatching null
        val symbols = requireNotNull(com.reamicro.fix.core.HostReaderRecordBindings.current)
        val quote = readerMarkValue(mark, "quote")?.toString().orEmpty().takeIf { it.isNotBlank() } ?: return@runCatching null

        val renderingPage = renderingEpubPage.get()
        val markCfiPrefix = epubCfiSpinePrefix(readerMarkValue(mark, "startCfi")?.toString().orEmpty())
        val pageCfiPrefix = renderingPage
            ?.let { symbols.field("page.start").get(it)?.toString() }
            ?.let(::epubCfiSpinePrefix)
        if (markCfiPrefix != null && pageCfiPrefix != null && markCfiPrefix != pageCfiPrefix) {
            return@runCatching null
        }
        val location = symbols.field("dom.location").get(contentDom)?.toString().orEmpty()
        val baseOffset = com.reamicro.fix.reader.SearchCfiAddress.parse(location)?.offset ?: 0
        val visibleStart = visibleWindow?.let { symbols.field("window.start").getInt(it) } ?: baseOffset
        val visibleEnd = visibleWindow?.let { symbols.field("window.endExclusive").getInt(it) }
            ?: (baseOffset.toLong() + renderedTextLength).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val cfiOffset = SearchHighlightPlanner.cfiCharacterOffset(readerMarkValue(mark, "startCfi")?.toString().orEmpty())
        if (cfiOffset != null) {
            val tolerance = SEARCH_HIGHLIGHT_OFFSET_TOLERANCE
            if (cfiOffset + tolerance < visibleStart || cfiOffset - tolerance > visibleEnd) {
                return@runCatching null
            }
        }
        val content = symbols.text(contentDom).takeIf { it.isNotBlank() } ?: return@runCatching null
        val realVisibleEnd = visibleWindow?.let { symbols.field("window.endExclusive").getInt(it) }
            ?: (baseOffset + content.length)
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
        val color = markColorTokenToColor(readerMarkValue(mark, "color")?.toString().orEmpty()) ?: return@runCatching null
        val style = symbols.field("mark.style").getInt(mark)
        symbols.overlay(resolved, textRange, style, color)
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX create search highlight overlay failed: ${it.stackTraceToString()}")
    }.getOrNull()

internal fun ReaderHook.describeSearchHighlightMark(mark: Any): String {
    val id = (readerMarkValue(mark, "id") as? Number)?.toLong() ?: 0L
    val style = (readerMarkValue(mark, "style") as? Number)?.toInt() ?: -1
    val color = readerMarkValue(mark, "color")?.toString().orEmpty()
    val start = readerMarkValue(mark, "startCfi")?.toString().orEmpty()
    val end = readerMarkValue(mark, "endCfi")?.toString().orEmpty()
    val quote = readerMarkValue(mark, "quote")?.toString().orEmpty().take(24)
    return "id=$id style=$style color=$color start=$start end=$end quote=$quote"
}

internal fun ReaderHook.updateSearchMarks(viewModel: Any?, label: String, transform: (List<Any>) -> List<Any>): Boolean = runCatching {
    val target = viewModel ?: return@runCatching false
    val result = requireNotNull(HostEpubRuntimeBindings.current).updateMarks(target, transform)
        ?: return@runCatching false.also { HookRuntime.log("$LOG_PREFIX search marks $label deferred: concurrent state updates") }
    HookRuntime.log("$LOG_PREFIX search marks $label ${result.first}->${result.second} active=${activeSearchHighlightId ?: 0L}")
    true
}.onFailure { HookRuntime.logError("$LOG_PREFIX update search highlight marks failed", it) }.getOrDefault(false)

private fun readerMarkValue(mark: Any, name: String, role: String = "mark"): Any? {
    val host = HostReaderRecordBindings.current ?: return null
    return if (host.type(role).isInstance(mark)) host.field("$role.$name").get(mark) else null
}

internal fun ReaderHook.isSearchResultHighlightMark(mark: Any): Boolean {
    val id = searchResultHighlightMarkId(mark) ?: return false
    return SearchHighlightPlanner.isHighlightId(id, SEARCH_HIGHLIGHT_MARK_ID_BASE, SEARCH_HIGHLIGHT_MARK_ID_RANGE)
}

internal fun ReaderHook.isInjectedTransientHighlightMark(mark: Any): Boolean {
    val id = (readerMarkValue(mark, "id") as? Number)?.toLong() ?: return false
    if (SearchHighlightPlanner.isHighlightId(id, SEARCH_HIGHLIGHT_MARK_ID_BASE, SEARCH_HIGHLIGHT_MARK_ID_RANGE)) {
        return true
    }
    return id >= SELECTION_HIGHLIGHT_MARK_ID_BASE &&
        id < SELECTION_HIGHLIGHT_MARK_ID_BASE + SELECTION_HIGHLIGHT_MARK_ID_RANGE
}

internal fun ReaderHook.searchResultHighlightMarkId(mark: Any): Long? {
    val id = (readerMarkValue(mark, "id") as? Number)?.toLong() ?: return null
    return if (SearchHighlightPlanner.isHighlightId(id, SEARCH_HIGHLIGHT_MARK_ID_BASE, SEARCH_HIGHLIGHT_MARK_ID_RANGE)) {
        id
    } else {
        null
    }
}

internal fun ReaderHook.searchResultHighlightResolvedMarkId(mark: Any): Long? {
    val id = (readerMarkValue(mark, "id", "resolved") as? Number)?.toLong() ?: return null
    return if (SearchHighlightPlanner.isHighlightId(id, SEARCH_HIGHLIGHT_MARK_ID_BASE, SEARCH_HIGHLIGHT_MARK_ID_RANGE)) {
        id
    } else {
        null
    }
}

internal fun ReaderHook.searchResultHighlightOverlayMarkId(overlay: Any): Long? {
    val symbols = com.reamicro.fix.core.HostReaderRecordBindings.current ?: return null
    if (!symbols.type("overlay").isInstance(overlay)) return null
    val mark = symbols.field("overlay.mark").get(overlay) ?: return null
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
        HostEpubRuntimeBindings.current?.text("book.id", context.book).orEmpty(),
        HostEpubRuntimeBindings.current?.text("book.uuid", context.book).orEmpty(),
        bookTitle(context),
    ).firstOrNull { it.isNotBlank() }.orEmpty()
