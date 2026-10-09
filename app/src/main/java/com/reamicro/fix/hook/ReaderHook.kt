package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.view.View
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.ComposeInterop
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
        resolveClass = classLoader::loadClass,
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
    internal var bottomReadAloudReceiverRef: WeakReference<Any>? = null
    internal var bottomReadAloudBookRef: WeakReference<Any>? = null
    internal var currentReaderNavGraphScopeRef: WeakReference<Any>? = null
    internal var readAloudMenuButtonRef: WeakReference<View>? = null
    internal var readAloudMenuButtonActivityRef: WeakReference<Activity>? = null
    internal val readAloudRestartLock = Any()
    internal val readAloudHighlightReceiverLock = Any()
    internal var readAloudHighlightReceiver: BroadcastReceiver? = null
    internal var readAloudHighlightReceiverContextRef: WeakReference<Context>? = null
    @Volatile internal var activeReadAloudBookKey: String = ""
    @Volatile internal var activeReadAloudSessionId: String = ""
    @Volatile internal var activeReadAloudPaused: Boolean = false
    @Volatile internal var lastReadAloudPageKey: String = ""
    @Volatile internal var suppressReadAloudRestartUntilMs: Long = 0L
    @Volatile internal var readAloudRestartSeq: Long = 0L
    @Volatile internal var readAloudPageProbeLogKey: String = ""
    @Volatile internal var activeReadAloudHighlightId: Long? = null
    @Volatile internal var activeReadAloudHighlightMark: Any? = null
    @Volatile internal var lastReadAloudFollowCfi: String = ""
    @Volatile internal var lastReadAloudFollowAtMs: Long = 0L
    @Volatile internal var isDispatchingReadAloudStatistics: Boolean = false
    @Volatile internal var lastReadAloudStatisticsSessionId: String = ""
    @Volatile internal var lastReadAloudStatisticsCfi: String = ""
    @Volatile internal var lastReadAloudStatisticsElapsedMs: Long = 0L
    @Volatile internal var pendingReadAloudProgressRestore: Boolean = false
    internal val replayingOnDemandJump = ThreadLocal<Boolean>()
    internal val onDemandPrefetchInFlight = ConcurrentHashMap.newKeySet<String>()
    internal val onDemandPrefetchRetryCount = ConcurrentHashMap<String, Int>()
    internal val onDemandRefreshPending = ConcurrentHashMap.newKeySet<String>()
    internal val onDemandRefreshCoordinator = ReaderRefreshCoordinator()
    @Volatile internal var onDemandSpineTable: ReaderOnDemandSpineTable? = null
    @Volatile internal var onDemandVisibleRefreshKey: String? = null
    @Volatile internal var onDemandVisibleBusyRetries = 0
    @Volatile internal var onDemandVisibleSlot: Int? = null
    @Volatile internal var onDemandJumpLease: ReaderRefreshCoordinator.Lease? = null
    @Volatile internal var lastRestoredReadAloudProgressKey: String = ""
    @Volatile internal var lastReadAloudProgressSyncAtMs: Long = 0L
    @Volatile internal var pendingReaderHighlightSheet: ReaderHighlightSheetRequest? = null

    @Volatile internal var readerHighlightScreenBackRef: WeakReference<Any>? = null
    internal val composeMethodCache = HashMap<String, Method>()

    internal val udpValueCache = HashMap<Int, Float>()

    internal val noArgMethodCache = HashMap<String, Method?>()
    internal val renderingHighlightScreenEntry = ThreadLocal.withInitial { false }
    internal val highlightScreenEntryInjected = ThreadLocal.withInitial { false }

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
    @Volatile internal var readerSearchTheme: StructureHost232Colors.Snapshot? = null
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
    private val highlightRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val highlightRefreshQueue = ReaderHighlightRefreshQueue(
        post = { task -> highlightRefreshHandler.post { task() } },
        context = { ReaderHighlightBookContext.bookKey to currentViewModelRef?.get() },
        refresh = { source ->
            val activity = activityProvider()
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                refreshReaderHighlightWindow(source)
            }
        },
    )

    fun install() {
        ReaderHighlightBookContext.refreshRequester = highlightRefreshQueue::request
        ensureReadAloudHighlightReceiver()

        HookInstallReport.installAll(
            FEATURE_ID,
            listOf(
                "contentDomRenderTextWidthFallback" to ::hookContentDomRenderTextWidthFallback,
                "scrollPagerCrashGuard" to ::hookScrollPagerCrashGuard,
                "nativeSelection" to ::installNativeSelectionHooks,
                "readerViewModel" to ::hookReaderViewModel,
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

    fun onHostActivityDestroyed(reason: String) {
        unregisterSearchOverlayThemeCallbacks()
        releaseReaderMemory(reason, releaseEpub = true)
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
