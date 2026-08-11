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

/**
 * Reader-surface hooks: selection actions, dictionary lookup, full-text search, and
 * defensive compatibility fixes for host reader regressions.
 */
class ReaderHook(
    internal val classLoader: ClassLoader,
    internal val activityProvider: () -> Activity?,
    internal val settingsProvider: () -> ModuleSettingsSnapshot = { ModuleSettingsSnapshot() },
    internal val settings: XposedModuleSettings? = null,
    internal val isActivityResumedProvider: () -> Boolean = { true },
) {
    // Compose 反射互操作的共用实现，避免各 hook 各存一份逐渐漂移的副本。
    internal val composeInterop = ComposeInterop(
        classLoader = classLoader,
        resolveClass = classLoader::loadClass,
        unitInstance = ::targetUnit,
        logPrefix = LOG_PREFIX,
    )

    internal var nativeSelectionHookInstalled: Boolean = false
    internal var currentSelectionControllerRef: WeakReference<Any>? = null
    internal var currentEpubRef: WeakReference<Any>? = null
    internal var currentPageRef: WeakReference<Any>? = null
    // 强引用镜像：currentEpubRef/currentPageRef 是弱引用，会被 GC 随机回收，
    // 导致 canShowReaderSearchEntry() 判定失败、宿主弹"暂无法搜索当前书籍"。
    // 阅读会话期间用强引用持有当前 epub/page，防止被回收；退出阅读/切书时清空避免泄漏。
    internal var currentEpubStrong: Any? = null
    internal var currentPageStrong: Any? = null
    internal var currentViewModelRef: WeakReference<Any>? = null
    internal var currentSessionRef: WeakReference<Any>? = null
    internal var searchPageDialogRef: WeakReference<Dialog>? = null
    internal var searchMenuButtonRef: WeakReference<View>? = null
    internal var searchMenuButtonActivityRef: WeakReference<Activity>? = null
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
    internal val onDemandRefreshInFlight = ConcurrentHashMap.newKeySet<String>()
    @Volatile internal var lastRestoredReadAloudProgressKey: String = ""
    @Volatile internal var lastReadAloudProgressSyncAtMs: Long = 0L
    @Volatile internal var pendingReaderHighlightSheet: ReaderHighlightSheetRequest? = null
    // 高亮页面（ReaderHighlightScreen）的 onBack 回调（Function0），点击"补全计划"时先关闭高亮页
    // 再打开阅读页底部的高亮规则 sheet，避免 sheet 被高亮页盖住。
    @Volatile internal var readerHighlightScreenBackRef: WeakReference<Any>? = null
    internal val composeMethodCache = HashMap<String, Method>()
    /** dp→px 换算结果缓存，见 cachedUdp。进程内 density 不变，值不会过期。 */
    internal val udpValueCache = HashMap<Int, Float>()
    /** callNoArg 的无参方法缓存，key 为「类名#方法名」，null 值表示该类没有这个方法。 */
    internal val noArgMethodCache = HashMap<String, Method?>()
    internal val renderingHighlightScreenEntry = ThreadLocal.withInitial { false }
    internal val highlightScreenEntryInjected = ThreadLocal.withInitial { false }

    // Rendering hooks run on the host reader pipeline. ThreadLocal keeps the current page
    // available to downstream text/cfi hooks without leaking it across concurrent renders.
    internal val renderingEpubPage = ThreadLocal<Any?>()
    @Volatile internal var lastCatalogContext: CatalogContext? = null
    @Volatile internal var lastSearchState: SearchState? = null
    @Volatile internal var activeSearchNavigation: SearchNavigationState? = null
    @Volatile internal var currentVisiblePageSignature: String? = null
    @Volatile internal var currentVisiblePageNumber: Int? = null
    // Statistics 在翻页、重组与预布局时会对同一页高频重复派发；只处理首次可见状态，
    // 避免在主线程重复反射、预取和日志写入而导致翻页卡顿。
    @Volatile internal var lastHandledReaderStatisticsKey: String = ""
    @Volatile internal var lastOnDemandPrefetchSpineKey: String = ""
    @Volatile internal var searchIndexState: SearchIndexState? = null
    @Volatile internal var searchIndexBuildingKey: String? = null
    @Volatile internal var searchStateGeneration: Long = 0L
    @Volatile internal var searchRunSeq: Long = 0L
    @Volatile internal var activeSearchJobKey: String? = null
    @Volatile internal var activeSearchPageToken: Long = 0L
    @Volatile internal var activeSearchPageUpdate: ((SearchState, Boolean) -> Unit)? = null
    @Volatile internal var readerBottomMenuVisible: Boolean = false
    @Volatile internal var searchRenderState: SearchRenderState? = null
    @Volatile internal var cachedThemeColors: ThemeColors? = null
    @Volatile internal var activeSearchHighlightId: Long? = null
    @Volatile internal var activeSearchHighlightMark: Any? = null
    @Volatile internal var activeSearchHighlightVisibleId: Long? = null
    @Volatile internal var activeSearchHighlightPageSignature: String? = null
    @Volatile internal var activeSearchHighlightPageNumber: Int? = null
    @Volatile internal var activeSearchHighlightRenderLogId: Long? = null
    @Volatile internal var activeSearchHighlightRenderLogCount: Int = 0
    @Volatile internal var pendingSearchOriginRestore: Boolean = false
    @Volatile internal var scrollCrashMarkerOwnedByThisProcess: Boolean = false
    @Volatile internal var catalogDumpLoggedForKey: String? = null
    @Volatile internal var lastReaderHighlightBookIdentity: String = ""

    fun install() {
        ReaderHighlightBookContext.refreshRequester = { source ->
            activityProvider()?.window?.decorView?.post {
                refreshReaderHighlightWindow(source)
            } ?: refreshReaderHighlightWindow(source)
        }
        ensureReadAloudHighlightReceiver()
        // 逐个登记安装结果，宿主升级后靠启动汇总定位掉线的 hook。
        HookInstallReport.installAll(
            FEATURE_ID,
            listOf(
                "contentDomRenderTextWidthFallback" to ::hookContentDomRenderTextWidthFallback,
                "scrollPagerCrashGuard" to ::hookScrollPagerCrashGuard,
                "nativeSelection" to ::installNativeSelectionHooks,
                "readerViewModel" to ::hookReaderViewModel,
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
            resetFullTextSearchState("trim memory level=$level", removeOverlays = false)
            cachedThemeColors = null
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

    @Volatile internal var nextIconIsDarkLightToggle = false
    @Volatile internal var nextIconIsReaderBack = false
    @Volatile internal var nextIconIsAutoPage = false

    private companion object {
    }

}
