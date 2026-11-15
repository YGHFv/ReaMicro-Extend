package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ComponentCallbacks2
import android.view.View
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.ComposeInterop
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.settings.ReaderHighlightBookContext
import com.reamicro.fix.settings.XposedModuleSettings
import java.lang.reflect.Method
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import com.reamicro.fix.hook.reader.*

class ReaderHook(
    internal val classLoader: ClassLoader,
    internal val activityProvider: () -> Activity?,
    internal val settingsProvider: () -> ModuleSettingsSnapshot = { ModuleSettingsSnapshot() },
    internal val settings: XposedModuleSettings? = null,
    internal val isActivityResumedProvider: () -> Boolean = { true },
) {

    internal val composeInterop = ComposeInterop(
        classLoader = classLoader,
        resolveClass = classLoader::hostClass,
        unitInstance = ::targetUnit,
        logPrefix = LOG_PREFIX,
    )

    internal val selectionEditSaving = java.util.concurrent.atomic.AtomicBoolean(false)
    internal val selectionEditPreparing = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile internal var selectionPagerTarget: ReaderSelectionPagerTarget? = null
    @Volatile internal var selectionWindow: ReaderSelectionWindow? = null
    @Volatile internal var selectionWindowHookInstalled = false
    internal var nativeSelectionHookInstalled: Boolean = false
    internal var currentSelectionControllerRef: WeakReference<Any>? = null
    @Volatile internal var currentEpubRef: WeakReference<Any>? = null
    @Volatile internal var currentPageRef: WeakReference<Any>? = null

    @Volatile internal var currentEpubStrong: Any? = null
    @Volatile internal var currentPageStrong: Any? = null
    @Volatile internal var currentViewModelRef: WeakReference<Any>? = null
    internal var currentSessionRef: WeakReference<Any>? = null
    internal var searchPageDialogRef: WeakReference<Dialog>? = null
    internal var searchNavigationView: ReaderSearchNavigationBar? = null
    @Volatile internal var searchNavigationState: SearchState? = null
    @Volatile internal var pendingSearchReturnCaptures = 0
    internal var dispatchingSearchJump = false
    internal var searchPaintEpoch: ReaderSearchPaintEpoch? = null
    @Volatile internal var searchPaintUnavailable = false
    internal var searchNavigator: ReaderSearchNavigator? = null
    @Volatile internal var searchSourceBuilder: HostSearchIndexBuilder? = null
    internal val searchDocumentCache = SearchDocumentCache()
    internal val searchWorker = LatestSearchWorker("ReaMicroFullTextSearch")
    internal var searchScrollBridge: ReaderSearchScrollBridge? = null
    internal var searchPageBridge: ReaderSearchPageBridge? = null
    internal var currentScrollElement: Any? = null
    internal var searchNavigationBarRef: WeakReference<View>? = null
    internal var searchNavigationBarActivityRef: WeakReference<Activity>? = null
    internal var searchOverlayThemeCallbacks: ComponentCallbacks2? = null
    internal var searchOverlayThemeCallbacksActivityRef: WeakReference<Activity>? = null
    internal var bottomSearchReceiverRef: WeakReference<Any>? = null
    internal var bottomSearchBookRef: WeakReference<Any>? = null
    internal var currentReaderNavGraphScopeRef: WeakReference<Any>? = null
    internal val replayingOnDemandJump = ThreadLocal<Boolean>()
    internal val onDemandPrefetchInFlight = ConcurrentHashMap.newKeySet<String>()
    internal val onDemandPrefetchRetryCount = ConcurrentHashMap<String, Int>()
    internal val onDemandRefreshPending = ConcurrentHashMap.newKeySet<String>()
    internal val onDemandRefreshCoordinator = ReaderRefreshCoordinator()
    internal val onDemandHandler = com.reamicro.fix.reload.ModuleResources.handler(android.os.Looper.getMainLooper())
    internal val readerUiHandler = com.reamicro.fix.reload.ModuleResources.handler(android.os.Looper.getMainLooper())
    @Volatile internal var readerActivityPaused = true
    @Volatile internal var readerActivityRef: WeakReference<Activity>? = null
    @Volatile internal var onDemandSpineTable: ReaderOnDemandSpineTable? = null
    @Volatile internal var onDemandVisibleRefreshKey: String? = null
    @Volatile internal var onDemandVisibleBusyRetries = 0
    @Volatile internal var onDemandVisibleSlot: Int? = null
    @Volatile internal var onDemandJumpLease: ReaderRefreshCoordinator.Lease? = null
    // 当前页面定位失败时的一次性诊断日志去重键（选中文本编辑与全文搜索共用）。
    @Volatile internal var currentPageProbeLogKey: String = ""
    @Volatile internal var pendingReaderHighlightSheet: ReaderHighlightSheetRequest? = null

    @Volatile internal var readerHighlightScreenBackRef: WeakReference<Any>? = null
    internal val composeMethodCache = HashMap<String, Method>()

    internal val udpValueCache = HashMap<Int, Float>()

    internal val renderingHighlightScreenEntry = ThreadLocal.withInitial { false }

    internal val renderingEpubPage = ThreadLocal<Any?>()
    @Volatile internal var lastCatalogContext: CatalogContext? = null
    @Volatile internal var lastSearchState: SearchState? = null
    internal var lastSearchListPosition: ReaderSearchListPosition? = null
    @Volatile internal var activeSearchNavigation: SearchNavigationState? = null
    @Volatile internal var currentVisiblePageSignature: String? = null
    @Volatile internal var currentVisiblePageNumber: Int? = null

    @Volatile internal var lastHandledReaderStatisticsKey: String = ""
    @Volatile internal var lastOnDemandPrefetchSpineKey: String = ""

    @Volatile internal var onDemandNormalizedHrefsCache: Pair<String, List<String>>? = null
    @Volatile internal var searchIndexState: SearchIndexState? = null
    @Volatile internal var searchStateGeneration: Long = 0L
    @Volatile internal var searchRunSeq: Long = 0L
    @Volatile internal var searchInProgress = false
    @Volatile internal var activeSearchPageToken: Long = 0L
    @Volatile internal var activeSearchPageUpdate: ((SearchState, Boolean) -> Unit)? = null
    @Volatile internal var readerBottomMenuVisible: Boolean = false
    @Volatile internal var cachedThemeColors: ThemeColors? = null
    @Volatile internal var readerSearchTheme: HostThemeColors.Snapshot? = null
    @Volatile internal var activeSearchHighlightSession: ReaderSearchHighlightSession? = null
    @Volatile internal var activeSearchHighlightId: Long? = null
    @Volatile internal var activeSearchHighlightMark: Any? = null
    @Volatile internal var activeSearchHighlightRenderLogId: Long? = null
    @Volatile internal var activeSearchHighlightRenderLogCount: Int = 0
    @Volatile internal var pendingSearchOriginRestore: Boolean = false
    @Volatile internal var scrollCrashMarkerOwnedByThisProcess: Boolean = false
    @Volatile internal var scrollCrashRecoveryInitialized: Boolean = false
    @Volatile internal var scrollCrashRecoveryPending: Boolean = false
    internal val scrollCrashRecoveryInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile internal var catalogDumpLoggedForKey: String? = null
    @Volatile internal var lastReaderHighlightBookIdentity: String = ""
    internal val highlightRefreshHandler = com.reamicro.fix.reload.ModuleResources.handler(android.os.Looper.getMainLooper())
    internal val highlightContextRefresh = Runnable {
        if (readerActivityPaused || !isActivityResumedProvider()) return@Runnable
        val identity = currentHighlightBookIdentity() ?: return@Runnable
        if ("${identity.first.trim()}\n${identity.second.trim()}" == lastReaderHighlightBookIdentity) {
            ReaderHighlightBookContext.bumpVersion("book-context-delayed")
        }
    }
    private val highlightRefreshQueue = ReaderHighlightRefreshQueue(
        post = { task ->
            !readerActivityPaused && isActivityResumedProvider() && highlightRefreshHandler.post { task() }
        },
        context = { ReaderHighlightBookContext.bookKey to currentViewModelRef?.get() },
        refresh = { source ->
            val activity = activityProvider()
            if (!readerActivityPaused && isActivityResumedProvider() &&
                activity != null && !activity.isFinishing && !activity.isDestroyed
            ) {
                refreshReaderHighlightWindow(source)
            }
        },
    )
    private val requestHighlightRefresh: (String) -> Unit = highlightRefreshQueue::request

    internal fun cancelHighlightRefresh() {
        highlightRefreshQueue.cancel()
        highlightRefreshHandler.removeCallbacksAndMessages(null)
    }

    fun install() {
        ReaderHighlightBookContext.refreshRequester = requestHighlightRefresh
        com.reamicro.fix.reload.ModuleResources.cleanup {
            if (ReaderHighlightBookContext.refreshRequester === requestHighlightRefresh) {
                ReaderHighlightBookContext.refreshRequester = null
            }
            try {
                onHostActivityDestroyed("module generation retired")
            } finally {
                searchWorker.close()
                onDemandRefreshCoordinator.close()
            }
        }

        HookInstallReport.installAll(
            FEATURE_ID,
            listOf(
                "readerTypographyGuard" to ::hookReaderTypographyGuard,
                "contentDomRenderTextWidthFallback" to ::hookContentDomRenderTextWidthFallback,
                "scrollPagerCrashGuard" to ::hookScrollPagerCrashGuard,
                "nativeSelection" to ::installNativeSelectionHooks,
                "readerViewModel" to ::hookReaderViewModel,
                "readerCatalogInvalidation" to { ReaderCatalogFlowGuard.install(classLoader) },
                "readerBackKey" to ::hookReaderBackKey,
                "searchJumpBridge" to { ReaderSearchJumpBridge(this).install() },
                "readerCatalog" to ::hookReaderCatalog,
                "readerBottomBar" to ::hookReaderBottomBar,
                "inlineSearchIcon" to ::hookInlineSearchIcon,
                "readerHighlightScreenEntry" to ::hookReaderHighlightScreenEntry,
                "readerHighlightRuleSheet" to ::hookReaderHighlightRuleSheet,
                "readerFamilySheetHeight" to ::hookReaderFamilySheetHeight,
                "homeBookshelfScreen" to ::hookHomeBookshelfScreen,
            ),
        )
    }

    internal fun onHostActivityCreated(activity: Activity) {
        if (readerActivityRef?.get() === activity) return
        if (readerActivityRef != null) onHostActivityDestroyed("MainActivity replaced")
        readerActivityRef = WeakReference(activity)
        readerActivityPaused = true
    }

    internal fun onHostActivityResumed(activity: Activity) {
        onHostActivityCreated(activity)
        if (!readerActivityPaused) return
        readerActivityPaused = false
        // 后台返回沿用宿主的阅读窗口；高亮重排由配置或内容变更触发。
        resumeSelectionPagerTarget()
        pendingSearchOriginRestore = false
        resumeSearchPageWork()
        refreshSearchNavigationBarTheme()
        val viewModel = currentViewModelRef?.get()
        val page = currentReaderPage()
        if (viewModel != null && page != null) {
            scheduleOnDemandVisiblePageRefresh(viewModel, page)
            prefetchOnlineOnDemandChapters(page)
        }
    }

    internal fun onHostActivityPaused(activity: Activity) {
        if (readerActivityRef?.get() === activity) pauseReaderWork()
    }

    private fun pauseReaderWork() {
        readerActivityPaused = true
        cancelSearchPageWork()
        searchNavigator?.cancel()
        pendingSearchOriginRestore = false
        cancelHighlightRefresh()
        cancelSelectionPagerConfirmation()
        onDemandHandler.removeCallbacksAndMessages(null)
        readerUiHandler.removeCallbacksAndMessages(null)
        suspendOnDemandRefreshes()
        lastOnDemandPrefetchSpineKey = ""
    }

    fun onHostActivityDestroyed(reason: String) {
        pauseReaderWork()
        readerActivityRef = null
        cancelHighlightRefresh()
        cancelSelectionPagerConfirmation()
        unregisterSearchOverlayThemeCallbacks()
        releaseReaderMemory(reason, releaseEpub = true, removeOverlays = true)
        currentViewModelRef = null
        currentSessionRef = null
        currentSelectionControllerRef = null
        currentReaderNavGraphScopeRef = null
        pendingReaderHighlightSheet = null
        readerHighlightScreenBackRef = null
        selectionWindow = null
        readerInlineSearchClick = null
        readerBottomMenuVisible = false
        renderingHighlightScreenEntry.remove()
        renderingEpubPage.remove()
        replayingOnDemandJump.remove()
        updateReaderHighlightBookContext("", "", reason, requestRefresh = false)
    }

    fun onTrimMemory(level: Int) {
        val runningUnderPressure = level in
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW..ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (runningUnderPressure || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {

            searchDocumentCache.clear()
            searchIndexState = null
            cachedThemeColors = null
            readerSearchTheme = null
        }
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
                releaseReaderStrongReferences("trim memory level=$level", releaseEpub = true)
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
                releaseReaderStrongReferences("trim memory level=$level", releaseEpub = false)
        }
    }

    fun onLowMemory() {
        releaseReaderMemory("system low memory", releaseEpub = true)
    }

    internal val readerBottomBarComposeScope = ReaderBottomBarComposeScope()
    internal var readerInlineSearchClick: Any? = null
    @Volatile internal var currentSearchPagerMode: Int? = null
    @Volatile internal var nextIconIsReaderBack = false
    @Volatile internal var nextIconIsAutoPage = false

    private companion object {
    }

}
