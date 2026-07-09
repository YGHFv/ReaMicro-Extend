package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Base64
import android.util.Xml
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.ai.AiApiConfig
import com.reamicro.fix.ai.AiDictionaryPreset
import com.reamicro.fix.ai.AiApiStore
import com.reamicro.fix.ai.AiApiTestResult
import com.reamicro.fix.reader.SearchHighlightPlanner
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.settings.ReaderHighlightBookContext
import com.reamicro.fix.settings.XposedModuleSettings
import com.reamicro.fix.tts.ReadAloudIntents
import com.reamicro.fix.tts.TtsSourceEntry
import com.reamicro.fix.tts.TtsSourceStore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.io.StringReader
import java.util.Locale
import java.util.concurrent.CountDownLatch
import org.xmlpull.v1.XmlPullParser

/**
 * Reader-surface hooks: selection actions, dictionary lookup, full-text search, and
 * defensive compatibility fixes for host reader regressions.
 */
class ReaderHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot = { ModuleSettingsSnapshot() },
    private val settings: XposedModuleSettings? = null,
    private val isActivityResumedProvider: () -> Boolean = { true },
) {
    private var nativeSelectionHookInstalled: Boolean = false
    private var currentSelectionControllerRef: WeakReference<Any>? = null
    private var currentEpubRef: WeakReference<Any>? = null
    private var currentPageRef: WeakReference<Any>? = null
    private var currentViewModelRef: WeakReference<Any>? = null
    private var currentSessionRef: WeakReference<Any>? = null
    private var searchPageDialogRef: WeakReference<Dialog>? = null
    private var searchMenuButtonRef: WeakReference<View>? = null
    private var searchMenuButtonActivityRef: WeakReference<Activity>? = null
    private var searchNavigationBarRef: WeakReference<View>? = null
    private var searchNavigationBarActivityRef: WeakReference<Activity>? = null
    private var searchOverlayThemeCallbacks: ComponentCallbacks2? = null
    private var searchOverlayThemeCallbacksActivityRef: WeakReference<Activity>? = null
    private var bottomSearchReceiverRef: WeakReference<Any>? = null
    private var bottomSearchBookRef: WeakReference<Any>? = null
    private var bottomReadAloudReceiverRef: WeakReference<Any>? = null
    private var bottomReadAloudBookRef: WeakReference<Any>? = null
    private var currentReaderNavGraphScopeRef: WeakReference<Any>? = null
    private var readAloudMenuButtonRef: WeakReference<View>? = null
    private var readAloudMenuButtonActivityRef: WeakReference<Activity>? = null
    private val readAloudRestartLock = Any()
    private val readAloudHighlightReceiverLock = Any()
    private var readAloudHighlightReceiver: BroadcastReceiver? = null
    private var readAloudHighlightReceiverContextRef: WeakReference<Context>? = null
    @Volatile private var activeReadAloudBookKey: String = ""
    @Volatile private var activeReadAloudSessionId: String = ""
    @Volatile private var activeReadAloudPaused: Boolean = false
    @Volatile private var lastReadAloudPageKey: String = ""
    @Volatile private var suppressReadAloudRestartUntilMs: Long = 0L
    @Volatile private var readAloudRestartSeq: Long = 0L
    @Volatile private var readAloudPageProbeLogKey: String = ""
    @Volatile private var activeReadAloudHighlightId: Long? = null
    @Volatile private var activeReadAloudHighlightMark: Any? = null
    @Volatile private var lastReadAloudFollowCfi: String = ""
    @Volatile private var lastReadAloudFollowAtMs: Long = 0L
    @Volatile private var isDispatchingReadAloudStatistics: Boolean = false
    @Volatile private var lastReadAloudStatisticsSessionId: String = ""
    @Volatile private var lastReadAloudStatisticsCfi: String = ""
    @Volatile private var lastReadAloudStatisticsElapsedMs: Long = 0L
    @Volatile private var pendingReadAloudProgressRestore: Boolean = false
    @Volatile private var lastRestoredReadAloudProgressKey: String = ""
    @Volatile private var lastReadAloudProgressSyncAtMs: Long = 0L
    @Volatile private var pendingReaderHighlightSheet: ReaderHighlightSheetRequest? = null
    private val composeMethodCache = HashMap<String, Method>()
    private val renderingHighlightQuickRow = ThreadLocal.withInitial { false }
    private val pendingReaderTypeSettingFontTitle = ThreadLocal<PendingReaderTypeSettingTitle?>()
    private val readerTypeSettingDepth = ThreadLocal.withInitial { 0 }
    private val readerTypeSettingInjected = ThreadLocal.withInitial { false }
    private val readerTypeSettingRowCount = ThreadLocal.withInitial { 0 }
    private val readerTypeSettingRowDepth = ThreadLocal.withInitial { 0 }
    private val readerTypeSettingRowTopLevelStack = ThreadLocal.withInitial { ArrayDeque<Boolean>() }
    // Rendering hooks run on the host reader pipeline. ThreadLocal keeps the current page
    // available to downstream text/cfi hooks without leaking it across concurrent renders.
    private val renderingEpubPage = ThreadLocal<Any?>()
    @Volatile private var lastCatalogContext: CatalogContext? = null
    @Volatile private var lastSearchState: SearchState? = null
    @Volatile private var activeSearchNavigation: SearchNavigationState? = null
    @Volatile private var currentVisiblePageSignature: String? = null
    @Volatile private var currentVisiblePageNumber: Int? = null
    @Volatile private var searchIndexState: SearchIndexState? = null
    @Volatile private var searchIndexBuildingKey: String? = null
    @Volatile private var searchStateGeneration: Long = 0L
    @Volatile private var searchRunSeq: Long = 0L
    @Volatile private var activeSearchJobKey: String? = null
    @Volatile private var activeSearchPageToken: Long = 0L
    @Volatile private var activeSearchPageUpdate: ((SearchState, Boolean) -> Unit)? = null
    @Volatile private var readerBottomMenuVisible: Boolean = false
    @Volatile private var searchRenderState: SearchRenderState? = null
    @Volatile private var cachedThemeColors: ThemeColors? = null
    @Volatile private var activeSearchHighlightId: Long? = null
    @Volatile private var activeSearchHighlightMark: Any? = null
    @Volatile private var activeSearchHighlightVisibleId: Long? = null
    @Volatile private var activeSearchHighlightPageSignature: String? = null
    @Volatile private var activeSearchHighlightPageNumber: Int? = null
    @Volatile private var activeSearchHighlightRenderLogId: Long? = null
    @Volatile private var activeSearchHighlightRenderLogCount: Int = 0
    @Volatile private var pendingSearchOriginRestore: Boolean = false
    @Volatile private var scrollCrashMarkerOwnedByThisProcess: Boolean = false
    @Volatile private var catalogDumpLoggedForKey: String? = null
    @Volatile private var lastReaderHighlightBookIdentity: String = ""

    fun install() {
        ReaderHighlightBookContext.refreshRequester = { source ->
            activityProvider()?.window?.decorView?.post {
                refreshReaderHighlightWindow(source)
            } ?: refreshReaderHighlightWindow(source)
        }
        ensureReadAloudHighlightReceiver()
        hookContentDomRenderTextWidthFallback()
        hookScrollPagerCrashGuard()
        installNativeSelectionHooks()
        hookReaderViewModel()
        hookReaderCatalog()
        hookReaderBottomBar()
        hookInlineSearchIcon()
        hookReaderTypeSettingHighlightRow()
        hookReaderHighlightRuleSheet()
        hookReaderFamilySheetHeight()
        hookHomeBookshelfScreen()
    }

    private fun canEditReaderSelection(): Boolean =
        settingsProvider().canEditReaderSelection

    private fun canShowReaderDictionary(): Boolean =
        settingsProvider().canShowReaderDictionary

    private fun canHighlightReaderSelection(): Boolean =
        settingsProvider().canHighlightReaderSelection

    private fun canRunFullTextSearch(): Boolean {
        val snapshot = settingsProvider()
        return snapshot.moduleEnabled
    }

    private fun canRunReadAloud(): Boolean =
        settingsProvider().canRunReaderReadAloud

    private fun canUseReadAloudSelection(): Boolean =
        settingsProvider().canUseReaderReadAloudSelection && currentEpubRoot() != null

    private fun canShowReaderSearchEntry(): Boolean =
        canRunFullTextSearch() && currentEpubRoot() != null && currentPageRef?.get() != null

    private fun canShowReaderReadAloudEntry(): Boolean =
        canRunReadAloud() && currentEpubRoot() != null && currentPageRef?.get() != null

    private fun hookReaderViewModel() {
        runCatching {
            val cls = classLoader.loadClass(READER_VIEW_MODEL_CLASS)
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    currentViewModelRef = WeakReference(param.thisObject)
                    ensureReadAloudHighlightReceiver()
                    requestReadAloudProgressSync(reason = "viewModel created")
                    param.args?.firstOrNull { it?.javaClass?.name == SESSION_CLASS }
                        ?.let { session ->
                            currentSessionRef = WeakReference(session)
                            restoreTranslateFlipStyleIfScrollCrashed(session, "ReaderViewModel")
                    }
                    XposedBridge.log("$LOG_PREFIX ReaderViewModel created")
                    scheduleRestorePersistedSearchOrigin("viewModel created")
                    scheduleRestorePersistedReadAloudProgress("viewModel created")
                }
            })
            XposedBridge.hookAllMethods(cls, "onCleared", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    XposedBridge.log("$LOG_PREFIX ReaderViewModel cleared")
                    clearReadAloudHighlight(param.thisObject)
                    if (currentViewModelRef?.get() === param.thisObject) currentViewModelRef = null
                    currentSessionRef = null
                    clearScrollCrashPending("ReaderViewModel cleared")
                    currentSelectionControllerRef = null
                    bottomReadAloudReceiverRef = null
                    bottomReadAloudBookRef = null
                    activityProvider()?.runOnUiThread { removeReadAloudMenuButton() }
                    currentEpubRef = null
                    currentPageRef = null
                    resetFullTextSearchState("ReaderViewModel cleared", removeOverlays = true)
                }
            })
            XposedBridge.hookAllMethods(cls, "intent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args?.getOrNull(0) ?: return
                    if (intent.javaClass.name != "$READER_UI_INTENT_CLASS\$Statistics") return
                    val page = callNoArg(intent, "getPage") ?: return
                    currentPageRef = WeakReference(page)
                    currentVisiblePageSignature = epubPageSignature(page)
                    currentVisiblePageNumber = epubPageNumber(page)
                    XposedBridge.log(
                        "$LOG_PREFIX full-text search visible page " +
                            "number=${currentVisiblePageNumber ?: -1} sig=${currentVisiblePageSignature.orEmpty()}",
                    )
                    if (!isDispatchingReadAloudStatistics) {
                        scheduleReadAloudRestartFromPage(page)
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX ReaderViewModel hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookScrollPagerCrashGuard() {
        runCatching {
            val scrollPagerClass = classLoader.loadClass(SCROLL_PAGER_KT_CLASS)
            val methods = scrollPagerClass.declaredMethods.filter {
                it.name == "ScrollPager" && it.parameterTypes.size >= 9
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val session = currentSessionRef?.get()
                        // If the previous process died while ScrollPager was entering render,
                        // switch back to translate paging before letting ScrollPager compose again.
                        if (isPreviousScrollCrashPending()) {
                            session?.let { restoreTranslateFlipStyleIfScrollCrashed(it, "ScrollPager") }
                            param.result = null
                            XposedBridge.log("$LOG_PREFIX ScrollPager blocked while fallback to translate is pending")
                            return
                        }
                        markScrollCrashPending()
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX scroll crash guard hook installed count=${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX scroll crash guard hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun restoreTranslateFlipStyleIfScrollCrashed(session: Any, source: String) {
        if (!isPreviousScrollCrashPending()) return
        Thread {
            runCatching {
                forceTranslateFlipStyle(session)
                clearScrollCrashPending("fallback updated by $source")
                val activity = activityProvider()
                activity?.runOnUiThread {
                    Toast.makeText(activity, "\u5df2\u81ea\u52a8\u5207\u6362\u4e3a\u5e73\u79fb\u7ffb\u9875", Toast.LENGTH_SHORT).show()
                }
                XposedBridge.log("$LOG_PREFIX scroll crash fallback switched flip_style to translate from $source")
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX scroll crash fallback update failed from $source: ${it.stackTraceToString()}")
            }
        }.apply {
            name = "ReaMicroScrollCrashFallback"
            isDaemon = true
            start()
        }
    }

    private fun forceTranslateFlipStyle(session: Any) {
        val prefKeysClass = classLoader.loadClass(PREF_KEYS_CLASS)
        val prefKeys = prefKeysClass.getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)
        val flipStyleKey = prefKeysClass.methods.first {
            it.name == "getFLIP_STYLE" && it.parameterTypes.isEmpty()
        }.invoke(prefKeys)
        val method = session.javaClass.methods.first {
            it.name == "update" && it.parameterTypes.size == 3
        }.apply { isAccessible = true }
        invokeSuspendBlocking(method, session, flipStyleKey, Integer.valueOf(FLIP_STYLE_TRANSLATE))
    }

    private fun markScrollCrashPending() {
        val context = activityProvider()?.applicationContext ?: return
        context.getSharedPreferences(SCROLL_CRASH_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SCROLL_CRASH_PENDING_KEY, true)
            .apply()
        scrollCrashMarkerOwnedByThisProcess = true
    }

    private fun clearScrollCrashPending(reason: String) {
        val context = activityProvider()?.applicationContext ?: return
        val prefs = context.getSharedPreferences(SCROLL_CRASH_PREFS, Context.MODE_PRIVATE)
        scrollCrashMarkerOwnedByThisProcess = false
        if (!prefs.getBoolean(SCROLL_CRASH_PENDING_KEY, false)) return
        prefs.edit().remove(SCROLL_CRASH_PENDING_KEY).apply()
        XposedBridge.log("$LOG_PREFIX scroll crash pending cleared: $reason")
    }

    private fun isScrollCrashPending(): Boolean =
        activityProvider()?.applicationContext
            ?.getSharedPreferences(SCROLL_CRASH_PREFS, Context.MODE_PRIVATE)
            ?.getBoolean(SCROLL_CRASH_PENDING_KEY, false)
            ?: false

    private fun isPreviousScrollCrashPending(): Boolean =
        isScrollCrashPending() && !scrollCrashMarkerOwnedByThisProcess

    private fun hookContentDomRenderTextWidthFallback() {
        runCatching {
            val contentDomClass = classLoader.loadClass(CONTENT_DOM_CLASS)
            val methods = contentDomClass.declaredMethods.filter {
                it.name == "getRenderTextWidthDp" && it.parameterTypes.isEmpty()
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val error = param.throwable ?: return
                        if (!isUninitializedContentDomParent(error)) return
                        // Host a11 can call ContentDom width before parent is initialized.
                        // The text layout width is enough for pagination, so recover from it.
                        val fallback = fallbackRenderTextWidthDp(param.thisObject) ?: return
                        param.result = fallback
                        XposedBridge.log("$LOG_PREFIX ContentDom parent fallback render width=$fallback")
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX ContentDom render width fallback hook installed count=${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX ContentDom render width fallback hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun isUninitializedContentDomParent(error: Throwable): Boolean =
        error is UninitializedPropertyAccessException &&
            error.message?.contains("parent") == true

    private fun fallbackRenderTextWidthDp(contentDom: Any?): Float? =
        runCatching {
            val textLayout = callNoArg(contentDom, "getTextLayout") ?: return@runCatching null
            val size = callNoArg(textLayout, "getSize") as? Number ?: return@runCatching null
            val widthPx = (size.toLong() shr 32).toInt()
            if (widthPx <= 0) return@runCatching 0f
            val epubWindowClass = classLoader.loadClass(UI_EPUB_WINDOW_CLASS)
            val instance = epubWindowClass.getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
                .get(null)
            val dpFloat = epubWindowClass.methods.firstOrNull {
                it.name == "dpFloat" && it.parameterTypes.size == 1
            } ?: return@runCatching widthPx.toFloat()
            val dp = (dpFloat.invoke(instance, Integer.valueOf(widthPx)) as? Number)?.toFloat()
                ?: widthPx.toFloat()
            kotlin.math.ceil(dp.toDouble()).toFloat()
        }.getOrNull()

    private fun installNativeSelectionHooks() {
        if (nativeSelectionHookInstalled) return
        nativeSelectionHookInstalled = true
        hookNativeSelectionController()
        hookNativeSelectionMenu()
        hookCurrentEpub()
        hookCurrentEpubPage()
        hookSearchHighlightRenderInputs()
        hookReaderSharedStateMarks()
        hookReaderCatalogHighlightPrecomputations()
    }

    private fun hookReaderCatalog() {
        runCatching {
            val catalogClass = classLoader.loadClass(READER_CATALOG_CLASS)
            val methods = catalogClass.declaredMethods.filter {
                it.name == "ReaderCatalog" &&
                    it.parameterTypes.size >= 8 &&
                    it.parameterTypes.getOrNull(4)?.let { type -> List::class.java.isAssignableFrom(type) } == true
            }
            if (methods.isEmpty()) error("ReaderCatalog composable not found")
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val status = param.args?.getOrNull(1)
                        param.args?.let { args ->
                            val composer = args.getOrNull(args.size - 2)
                            if (composer != null) cacheThemeColors(composer)
                        }
                        if (!canRunFullTextSearch()) {
                            activityProvider()?.runOnUiThread {
                                clearSearchOverlays(clearNavigationState = true)
                            }
                            return
                        }
                        if (status?.toString() != "Catalog") return
                        val catalog = (param.args?.getOrNull(4) as? List<*>)?.filterNotNull().orEmpty()
                        val context = CatalogContext(
                            intentReceiver = param.args?.getOrNull(0),
                            book = param.args?.getOrNull(2),
                            catalog = catalog,
                        )
                        ensureReadAloudHighlightReceiver()
                        val previousContext = lastCatalogContext
                        if (previousContext != null && isDifferentSearchBook(previousContext, context)) {
                            resetFullTextSearchState("catalog book changed", removeOverlays = true)
                        }
                        lastCatalogContext = context
                        updateReaderHighlightBookContext(bookKey(context), bookTitle(context), "catalog context")
                        injectSearchHighlightIntoReaderCatalog(param)
                        scheduleRestorePersistedSearchOrigin("catalog context")
                        scheduleRestorePersistedReadAloudProgress("catalog context")
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX reader catalog full-text search hook installed: ${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX reader catalog full-text search hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun injectSearchHighlightIntoReaderCatalog(param: XC_MethodHook.MethodHookParam) {
        val args = param.args ?: return
        val chapterItemsMap = args.getOrNull(5) as? Map<*, *> ?: return
        val nextMap = appendActiveSearchHighlightCatalogItemMap(chapterItemsMap) ?: return
        args[5] = nextMap
    }

    private fun clearSearchOverlays(clearNavigationState: Boolean) {
        closeSearchPage()
        removeSearchMenuButton()
        if (clearNavigationState) {
            activeSearchNavigation = null
            clearPersistedSearchOrigin()
        }
        removeSearchNavigationBar()
    }

    private fun hookHomeBookshelfScreen() {
        val targets = listOf(
            HOME_SCREEN_CLASS to "HomeScreen",
            BOOKSHELF_SCREEN_CLASS to "BookshelfScreen",
        )
        targets.forEach { (className, methodName) ->
            runCatching {
                val cls = classLoader.loadClass(className)
                val methods = cls.declaredMethods.filter { it.name == methodName }
                if (methods.isEmpty()) error("$methodName not found")
                methods.forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            handleHomeBookshelfRendered(methodName)
                        }
                    })
                }
                XposedBridge.log("$LOG_PREFIX home search cleanup hook installed: $methodName/${methods.size}")
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX home search cleanup hook failed for $className: ${it.message}")
            }
        }
    }

    private fun handleHomeBookshelfRendered(source: String) {
        val hadReaderSearchState = hasFullTextSearchState()
        stopReadAloudIfPausedOnLeaveReader("home rendered: $source")
        if (activeSearchNavigation != null) {
            returnToSearchOrigin(clearNavigation = true, removeBar = false)
        }
        removeReadAloudMenuButton()
        currentEpubRef = null
        currentPageRef = null
        bottomReadAloudReceiverRef = null
        bottomReadAloudBookRef = null
        readerBottomMenuVisible = false
        updateReaderHighlightBookContext("", "", "home rendered: $source", requestRefresh = false)
        if (hadReaderSearchState) {
            resetFullTextSearchState("home rendered: $source", removeOverlays = true)
        }
    }

    private fun updateReaderHighlightBookContext(
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

    private fun hasFullTextSearchState(): Boolean =
        bottomSearchReceiverRef?.get() != null ||
            bottomSearchBookRef?.get() != null ||
            lastCatalogContext != null ||
            lastSearchState != null ||
            activeSearchNavigation != null ||
            searchIndexState != null ||
            searchIndexBuildingKey != null ||
            searchPageDialogRef?.get() != null ||
            searchMenuButtonRef?.get() != null ||
            searchNavigationBarRef?.get() != null

    private fun resetFullTextSearchState(reason: String, removeOverlays: Boolean) {
        searchStateGeneration += 1
        searchRunSeq += 1
        activeSearchJobKey = null
        activeSearchPageToken = 0L
        activeSearchPageUpdate = null
        clearSearchResultHighlight()
        clearSelectionInjectedHighlight()
        bottomSearchReceiverRef = null
        bottomSearchBookRef = null
        lastCatalogContext = null
        lastSearchState = null
        activeSearchNavigation = null
        searchIndexState = null
        searchIndexBuildingKey = null
        if (removeOverlays) {
            activityProvider()?.runOnUiThread {
                closeSearchPage()
                removeSearchMenuButton()
                removeSearchNavigationBar()
            }
        }
        XposedBridge.log("$LOG_PREFIX full-text search state reset: $reason")
    }

    private fun postRemoveTaggedViews(activity: Activity?, tagValue: Int) {
        val targetActivity = activity ?: activityProvider() ?: return
        val decor = targetActivity.window?.decorView as? ViewGroup ?: return
        decor.post {
            runCatching {
                removeTaggedViews(decor, tagValue)
                removeTaggedViews(targetActivity.findViewById(android.R.id.content), tagValue)
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX failed to remove overlay tag=$tagValue: ${it.stackTraceToString()}")
            }
        }
    }

    private fun updateSearchNavigationForBottomState(activity: Activity) {
        if (readerBottomMenuVisible) {
            searchNavigationBarRef?.get()?.visibility = View.GONE
            return
        }
        if (activeSearchNavigation != null && lastSearchState != null) {
            ensureSearchNavigationBar(activity)
        } else {
            removeSearchNavigationBar()
        }
    }

    private fun hookReaderBottomBar() {
        runCatching {
            val cls = classLoader.loadClass(READER_BOTTOM_BAR_CLASS)
            val methods = cls.declaredMethods.filter {
                it.name == "ReaderBottomBar" && it.parameterTypes.size >= 7
            }
            if (methods.isEmpty()) error("ReaderBottomBar composable not found")
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val statusName = param.args?.getOrNull(3)?.toString().orEmpty()
                        val canShowSearchEntry = canShowReaderSearchEntry()
                        val canShowReadAloudEntry = canShowReaderReadAloudEntry()
                        readerBottomMenuVisible = canShowSearchEntry && statusName.isNotBlank() && statusName != "Reader"
                        if (canShowSearchEntry && statusName == "Menu") {
                            param.args?.getOrNull(1)?.let { bottomSearchReceiverRef = WeakReference(it) }
                            param.args?.getOrNull(2)?.let { bottomSearchBookRef = WeakReference(it) }
                        }
                        if (canShowReadAloudEntry && statusName == "Menu") {
                            param.args?.getOrNull(1)?.let { bottomReadAloudReceiverRef = WeakReference(it) }
                            param.args?.getOrNull(2)?.let { bottomReadAloudBookRef = WeakReference(it) }
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!canRunFullTextSearch()) {
                            activityProvider()?.runOnUiThread {
                                readerBottomMenuVisible = false
                                bottomSearchReceiverRef = null
                                bottomSearchBookRef = null
                                bottomReadAloudReceiverRef = null
                                bottomReadAloudBookRef = null
                                removeSearchMenuButton()
                                removeSearchNavigationBar()
                                removeReadAloudMenuButton()
                            }
                            return
                        }
                        val receiver = param.args?.getOrNull(1)
                        val book = param.args?.getOrNull(2)
                        val status = param.args?.getOrNull(3)
                        val statusName = status?.toString().orEmpty()
                        val canShowSearchEntry = canShowReaderSearchEntry()
                        val canShowReadAloudEntry = canShowReaderReadAloudEntry()
                        readerBottomMenuVisible = canShowSearchEntry && statusName.isNotBlank() && statusName != "Reader"
                        if (canShowSearchEntry && statusName == "Menu") {
                            bottomSearchReceiverRef = receiver?.let { WeakReference(it) }
                            bottomSearchBookRef = book?.let { WeakReference(it) }
                        }
                        if (canShowReadAloudEntry && statusName == "Menu") {
                            bottomReadAloudReceiverRef = receiver?.let { WeakReference(it) }
                            bottomReadAloudBookRef = book?.let { WeakReference(it) }
                        }
                        val activity = activityProvider() ?: return
                        activity.runOnUiThread {
                            removeSearchMenuButton()
                            updateSearchNavigationForBottomState(activity)
                            removeReadAloudMenuButton()
                        }
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX reader bottom search hook installed: ${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX reader bottom search hook failed: ${it.stackTraceToString()}")
        }
    }

    @Volatile private var nextIconIsDarkLightToggle = false
    @Volatile private var nextIconIsReaderBack = false

    private fun hookInlineSearchIcon() {
        runCatching {
            // hook getDarkMode/getLightMode：设置标志位，下一个 Icon 调用替换为搜索图标
            listOf(DARK_MODE_ICON_CLASS to "getDarkMode", LIGHT_MODE_ICON_CLASS to "getLightMode").forEach { (className, methodName) ->
                runCatching {
                    val cls = classLoader.loadClass(className)
                    cls.declaredMethods.filter { it.name == methodName && it.parameterTypes.size == 1 }.forEach { m ->
                        m.isAccessible = true
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!settingsProvider().inlineSearchIconEnabled) return
                                if (!canRunFullTextSearch() || !readerBottomMenuVisible) return
                                nextIconIsDarkLightToggle = true
                            }
                        })
                    }
                }
            }
            // hook Icon-ww6aTOc：替换 imageVector 为 Search 图标
            runCatching {
                val cls = classLoader.loadClass(ARROW_BACK_ICON_CLASS)
                cls.declaredMethods.filter { it.name == "getArrowBack" && it.parameterTypes.size == 1 }.forEach { m ->
                    m.isAccessible = true
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (settingsProvider().inlineSearchIconEnabled) return
                            if (!canRunFullTextSearch() || !readerBottomMenuVisible) return
                            nextIconIsReaderBack = true
                        }
                    })
                }
            }
            val iconClass = classLoader.loadClass("androidx.compose.material3.IconKt")
            iconClass.declaredMethods.filter {
                it.name == "Icon-ww6aTOc" && it.parameterTypes.size == 7
            }.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!canRunFullTextSearch() || !readerBottomMenuVisible) return
                        val replace = if (settingsProvider().inlineSearchIconEnabled) {
                            nextIconIsDarkLightToggle.also { nextIconIsDarkLightToggle = false }
                        } else {
                            nextIconIsReaderBack.also { nextIconIsReaderBack = false }
                        }
                        if (!replace) return
                        val searchIcon = searchImageVector() ?: return
                        // 替换 imageVector 参数（位置 0）为搜索图标
                        param.args?.set(0, searchIcon)
                    }
                })
            }
            // hook clickable-oSLSa3U$default：将深浅色按钮的点击回调替换为打开搜索
            runCatching {
                val clickableClass = classLoader.loadClass("androidx.compose.foundation.ClickableKt")
                clickableClass.declaredMethods.filter {
                    it.name.startsWith("clickable-") && it.name.endsWith("\$default") && it.parameterTypes.size == 8
                }.forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!canRunFullTextSearch() || !readerBottomMenuVisible) return
                            val onClick = param.args?.getOrNull(5) ?: return
                            extractNavGraphScopeFromLambda(onClick)?.let { navGraphScope ->
                                currentReaderNavGraphScopeRef = WeakReference(navGraphScope)
                            }
                            val replace = if (settingsProvider().inlineSearchIconEnabled) {
                                isReaderBottomBarDarkLightToggleClick(onClick)
                            } else {
                                isReaderBottomBarBackClick(onClick)
                            }
                            if (!replace) return
                            param.args[5] = nativeFunction0 { openBottomSearchPage() }
                        }
                    })
                }
            }
            XposedBridge.log("$LOG_PREFIX inline search icon replacement hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX inline search icon hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun isReaderBottomBarDarkLightToggleClick(onClick: Any): Boolean =
        runCatching {
            val cls = onClick.javaClass
            val className = cls.name
            if (!className.contains("ReaderBottomBarKt") ||
                !className.contains("ExternalSyntheticLambda")
            ) {
                return@runCatching false
            }
            val fields = cls.declaredFields
            val fieldTypes = fields.map { it.type.name }.toSet()
            if (fieldTypes.contains(NAV_GRAPH_SCOPE_CLASS)) return@runCatching false
            fieldTypes.contains("kotlinx.coroutines.CoroutineScope") &&
                fieldTypes.contains("androidx.compose.runtime.MutableState") &&
                fieldTypes.contains(SESSION_CLASS) &&
                fields.any { it.type == java.lang.Boolean.TYPE }
        }.getOrDefault(false)

    private fun isReaderBottomBarBackClick(onClick: Any): Boolean =
        runCatching {
            val cls = onClick.javaClass
            val className = cls.name
            if (!className.contains("ReaderBottomBarKt") ||
                !className.contains("ExternalSyntheticLambda")
            ) {
                return@runCatching false
            }
            cls.declaredFields.any { it.type.name == NAV_GRAPH_SCOPE_CLASS }
        }.getOrDefault(false)

    private fun hookReaderTypeSettingHighlightRow() {
        runCatching {
            val typeSettingClass = classLoader.loadClass(READER_TYPE_SETTING_CLASS)
            val composerClass = classLoader.loadClass(COMPOSER_CLASS)
            var scopeHookCount = 0
            typeSettingClass.declaredMethods
                .filter { isReaderTypeSettingScopeMethod(it, composerClass) }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val depth = readerTypeSettingDepth.get()
                            if (depth == 0) {
                                readerTypeSettingInjected.set(false)
                                readerTypeSettingRowCount.set(0)
                                readerTypeSettingRowDepth.set(0)
                                readerTypeSettingRowTopLevelStack.get().clear()
                                pendingReaderTypeSettingFontTitle.remove()
                            }
                            readerTypeSettingDepth.set(depth + 1)
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            readerTypeSettingDepth.set((readerTypeSettingDepth.get() - 1).coerceAtLeast(0))
                        }
                    })
                    scopeHookCount++
                }
            val textClass = classLoader.loadClass(MATERIAL3_TEXT_CLASS)
            val methods = textClass.declaredMethods.filter { method ->
                method.name.startsWith("Text") &&
                    method.parameterTypes.size == 22 &&
                    method.parameterTypes.firstOrNull() == String::class.java
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (renderingHighlightQuickRow.get() == true) return
                        val text = param.args?.getOrNull(0) as? String ?: return
                        if (text !in READER_TYPE_SETTING_TITLE_TEXTS) return
                        if (readerTypeSettingDepth.get() <= 0 && !isInReaderTypeSettingCompose()) return
                        val args = param.args?.copyOf() ?: return
                        pendingReaderTypeSettingFontTitle.set(PendingReaderTypeSettingTitle(method, args))
                        if (text == "\u5b57\u4f53" && !readerTypeSettingInjected.get()) {
                            val composer = args.getOrNull(18) ?: return
                            readerTypeSettingInjected.set(true)
                            renderingHighlightQuickRow.set(true)
                            try {
                                renderReaderHighlightQuickSection(composer)
                            } finally {
                                renderingHighlightQuickRow.set(false)
                            }
                        }
                    }
                })
            }
            composeMethod(ROW_KT_CLASS, ROW_METHOD, 7).let { rowMethod ->
                XposedBridge.hookMethod(rowMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (renderingHighlightQuickRow.get() == true) return
                        if (readerTypeSettingDepth.get() <= 0) return
                        val depth = readerTypeSettingRowDepth.get()
                        readerTypeSettingRowTopLevelStack.get().addLast(depth == 0)
                        readerTypeSettingRowDepth.set(depth + 1)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (renderingHighlightQuickRow.get() == true) return
                        if (readerTypeSettingDepth.get() <= 0) return
                        val depth = (readerTypeSettingRowDepth.get() - 1).coerceAtLeast(0)
                        readerTypeSettingRowDepth.set(depth)
                        val isTopLevel = readerTypeSettingRowTopLevelStack.get().removeLastOrNull() == true
                        if (!isTopLevel || readerTypeSettingInjected.get()) return
                        val rowIndex = readerTypeSettingRowCount.get()
                        readerTypeSettingRowCount.set(rowIndex + 1)
                        if (rowIndex != READER_TYPE_SETTING_INSERT_AFTER_ROW_INDEX) return
                        val composer = param.args?.getOrNull(4) ?: return
                        readerTypeSettingInjected.set(true)
                        renderingHighlightQuickRow.set(true)
                        try {
                            renderReaderHighlightQuickSection(composer)
                        } finally {
                            renderingHighlightQuickRow.set(false)
                        }
                    }
                })
            }
            XposedBridge.log(
                "$LOG_PREFIX reader highlight type-setting row hook installed: " +
                    "scope=$scopeHookCount text=${methods.size}",
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX reader highlight type-setting row hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun isReaderTypeSettingScopeMethod(method: Method, composerClass: Class<*>): Boolean {
        val parameterTypes = method.parameterTypes
        return method.name == "TypeSetting" &&
            parameterTypes.size == 4 &&
            parameterTypes.getOrNull(2) == composerClass &&
            parameterTypes.getOrNull(3) == Int::class.javaPrimitiveType
    }

    private fun isInReaderTypeSettingCompose(): Boolean =
        Thread.currentThread().stackTrace.any { frame ->
            frame.className == READER_TYPE_SETTING_CLASS && frame.methodName.contains("TypeSetting")
        }

    private fun renderReaderHighlightQuickSection(composer: Any) {
        val pendingTitle = pendingReaderTypeSettingFontTitle.get() ?: return
        val highlightTitleArgs = pendingTitle.args.copyOf()
        highlightTitleArgs[0] = "\u9ad8\u4eae"
        val composerIndex = pendingTitle.method.parameterTypes.indexOfFirst { it.name == COMPOSER_CLASS }
        if (composerIndex >= 0 && composerIndex < highlightTitleArgs.size) {
            highlightTitleArgs[composerIndex] = composer
        }
        highlightTitleArgs[1] = sectionTitleModifier(
            top = NATIVE_TYPE_GROUP_TOP_PADDING,
            bottom = NATIVE_SECTION_TITLE_BOTTOM_PADDING,
        )
        pendingTitle.method.invoke(null, *highlightTitleArgs)
        val content = functionProxy("ReaderHighlightTypeSettingRowContent", KOTLIN_FUNCTION3_CLASS) { args ->
            val rowScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            val innerComposer = args.getOrNull(1) ?: return@functionProxy targetUnit()
            renderReaderHighlightTypeSettingButton(
                rowScope = rowScope,
                title = "\u5168\u5c40\u9ad8\u4eae\u89c4\u5219",
                onClick = { openReaderHighlightRuleSheet(globalRules = true) },
                composer = innerComposer,
            )
            renderReaderHighlightTypeSettingButton(
                rowScope = rowScope,
                title = "\u5355\u4e66\u9ad8\u4eae\u89c4\u5219",
                onClick = { openReaderHighlightRuleSheet(globalRules = false) },
                composer = innerComposer,
            )
            targetUnit()
        }
        composeMethod(ROW_KT_CLASS, ROW_METHOD, 7).invoke(
            null,
            fillMaxWidth(sectionTitleModifier(top = 0, bottom = 0)),
            arrangementSpacedBy(20),
            alignmentTop(),
            content,
            composer,
            0,
            0,
        )
    }

    private fun renderReaderHighlightTypeSettingButton(
        rowScope: Any,
        title: String,
        onClick: () -> Unit,
        composer: Any,
    ) {
        composeMethod(READER_TYPE_SETTING_CLASS, TYPE_SETTING_FAMILY_METHOD, 5).invoke(
            null,
            rowScope,
            title,
            nativeFunction0(onClick),
            composer,
            0,
        )
    }

    private fun openReaderHighlightRuleSheet(globalRules: Boolean) {
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

    private fun hookReaderHighlightRuleSheet() {
        runCatching {
            val cls = classLoader.loadClass(READER_FAMILY_EPUB_CLASS)
            val clearMethods = cls.declaredMethods.filter {
                it.name == "ReaderFamilyEpub" && it.parameterTypes.size == 5
            }
            clearMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val status = param.args?.getOrNull(1)
                        if (isReaderSheetStatus(status, "Hidden")) {
                            pendingReaderHighlightSheet = null
                        }
                    }
                })
            }
            val contentMethods = cls.declaredMethods.filter {
                it.name == "ReaderFamilyEpub\$lambda\$2" && it.parameterTypes.size == 6
            }
            contentMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val request = pendingReaderHighlightSheet ?: return
                        val status = param.args?.getOrNull(0)
                        if (!isReaderSheetStatus(status, "EpubFamily")) return
                        val receiver = param.args?.getOrNull(1) ?: return
                        val composer = param.args?.getOrNull(4) ?: return
                        updateReaderHighlightBookContext(request.bookKey, request.bookTitle, "highlight sheet render")
                        val rendered = ReaMicroSettingsHook.renderReaderHighlightRulesSheetFromReader(
                            globalRules = request.globalRules,
                            bookKey = request.bookKey,
                            bookTitle = request.bookTitle,
                            composer = composer,
                        ) {
                            pendingReaderHighlightSheet = null
                            sendReaderUiSheetStatus(receiver, "Hidden")
                        }
                        if (rendered) param.result = targetUnit()
                    }
                })
            }
            XposedBridge.log(
                "$LOG_PREFIX reader highlight rule sheet hook installed: " +
                    "clear=${clearMethods.size} content=${contentMethods.size}",
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX reader highlight rule sheet hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookReaderFamilySheetHeight() {
        runCatching {
            val heightMethod = composeMethod(SIZE_KT_CLASS, HEIGHT_METHOD, 2)
            XposedBridge.hookMethod(heightMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isInReaderFamilySheetCompose()) return
                    val currentHeight = param.args?.getOrNull(1) as? Float ?: return
                    val hostHeight = udp(HOST_READER_FAMILY_SHEET_HEIGHT_DP)
                    if (currentHeight - hostHeight > 0.01f || hostHeight - currentHeight > 0.01f) return
                    param.args[1] = udp(READER_RULE_SHEET_HEIGHT_DP)
                }
            })
            XposedBridge.log("$LOG_PREFIX reader family sheet height hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX reader family sheet height hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun isInReaderFamilySheetCompose(): Boolean =
        Thread.currentThread().stackTrace.any { frame ->
            frame.className == READER_FAMILY_EPUB_CLASS ||
                frame.className == READER_FAMILY_USER_CLASS ||
                frame.className == READER_FAMILY_BUILD_IN_CLASS
        }

    private fun sendReaderUiSheetStatus(receiver: Any, statusName: String): Boolean =
        runCatching {
            val status = readerSheetStatus(statusName)
            val intentClass = classLoader.loadClass("$READER_UI_INTENT_CLASS\$UpdateUISheetStatus")
            val intent = intentClass.declaredConstructors.first { it.parameterTypes.size == 1 }
                .apply { isAccessible = true }
                .newInstance(status)
            receiver.javaClass.methods.first {
                it.name == "intent" && it.parameterTypes.size == 1
            }.apply { isAccessible = true }.invoke(receiver, intent)
            true
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX update reader sheet status failed: ${it.stackTraceToString()}")
        }.getOrDefault(false)

    private fun readerSheetStatus(statusName: String): Any =
        classLoader.loadClass("$UI_SHEET_STATUS_CLASS\$$statusName")
            .getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)
            ?: error("UiSheetStatus.$statusName unavailable")

    private fun isReaderSheetStatus(status: Any?, statusName: String): Boolean =
        runCatching { status == readerSheetStatus(statusName) || status?.toString() == statusName }.getOrDefault(false)

    private fun extractNavGraphScopeFromLambda(onClick: Any): Any? =
        onClick.javaClass.declaredFields.firstNotNullOfOrNull { field ->
            runCatching {
                field.isAccessible = true
                field.get(onClick)?.takeIf { it.javaClass.name == NAV_GRAPH_SCOPE_CLASS }
            }.getOrNull()
        }

    private fun searchImageVector(): Any? = runCatching {
        val outlined = classLoader.loadClass(ICONS_OUTLINED_CLASS).getField("INSTANCE").get(null)
        classLoader.loadClass(SEARCH_ICON_CLASS).declaredMethods.firstOrNull {
            it.name == "getSearch" && it.parameterTypes.size == 1
        }?.apply { isAccessible = true }?.invoke(null, outlined)
    }.getOrNull()

    private fun showSearchMenuButton(activity: Activity, receiver: Any?, book: Any?) {
        if (!canShowReaderSearchEntry()) {
            removeSearchMenuButton()
            return
        }
        val decor = activity.window?.decorView as? ViewGroup ?: return
        ensureSearchOverlayThemeCallbacks(activity)
        val existing = searchMenuButtonRef?.get()
        if (existing != null && searchMenuButtonActivityRef?.get() === activity && existing.parent === decor) {
            (existing as? SearchMenuButtonView)?.refreshColors()
            existing.visibility = View.VISIBLE
            existing.bringToFront()
            bottomSearchReceiverRef = receiver?.let { WeakReference(it) } ?: bottomSearchReceiverRef
            bottomSearchBookRef = book?.let { WeakReference(it) } ?: bottomSearchBookRef
            return
        }
        searchMenuButtonRef = null
        searchMenuButtonActivityRef = null
        bottomSearchReceiverRef = receiver?.let { WeakReference(it) } ?: bottomSearchReceiverRef
        bottomSearchBookRef = book?.let { WeakReference(it) } ?: bottomSearchBookRef
        decor.post {
            if (!canShowReaderSearchEntry()) return@post
            removeTaggedViews(decor, SEARCH_MENU_BUTTON_TAG)
            removeTaggedViews(activity.findViewById(android.R.id.content), SEARCH_MENU_BUTTON_TAG)
            val current = searchMenuButtonRef?.get()
            if (current != null && searchMenuButtonActivityRef?.get() === activity && current.parent === decor) {
                (current as? SearchMenuButtonView)?.refreshColors()
                current.visibility = View.VISIBLE
                current.bringToFront()
                return@post
            }
            val button = SearchMenuButtonView(activity).apply {
                tag = SEARCH_MENU_BUTTON_TAG
                contentDescription = "\u641c\u7d22\u5168\u4e66"
                alpha = 0.94f
                elevation = dp(activity, 6).toFloat()
                setOnClickListener { openBottomSearchPage() }
            }
            decor.addView(button, FrameLayout.LayoutParams(
                dp(activity, SEARCH_MENU_BUTTON_SIZE_DP),
                dp(activity, SEARCH_MENU_BUTTON_SIZE_DP),
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(activity, SEARCH_MENU_BUTTON_RIGHT_MARGIN_DP)
                bottomMargin = dp(activity, SEARCH_MENU_BUTTON_BOTTOM_MARGIN_DP)
            })
            button.bringToFront()
            searchMenuButtonRef = WeakReference(button)
            searchMenuButtonActivityRef = WeakReference(activity)
            bottomSearchReceiverRef = receiver?.let { WeakReference(it) } ?: bottomSearchReceiverRef
            bottomSearchBookRef = book?.let { WeakReference(it) } ?: bottomSearchBookRef
        }
    }

    private fun removeSearchMenuButton() {
        val activity = searchMenuButtonActivityRef?.get() ?: activityProvider()
        searchMenuButtonRef = null
        searchMenuButtonActivityRef = null
        postRemoveTaggedViews(activity, SEARCH_MENU_BUTTON_TAG)
        maybeUnregisterSearchOverlayThemeCallbacks()
    }

    private fun showReadAloudMenuButton(activity: Activity, receiver: Any?, book: Any?) {
        if (!canShowReaderReadAloudEntry()) {
            removeReadAloudMenuButton()
            return
        }
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val existing = readAloudMenuButtonRef?.get()
        if (existing != null && readAloudMenuButtonActivityRef?.get() === activity && existing.parent === decor) {
            (existing as? ReadAloudMenuButtonView)?.refreshColors()
            existing.visibility = View.VISIBLE
            existing.bringToFront()
            bottomReadAloudReceiverRef = receiver?.let { WeakReference(it) } ?: bottomReadAloudReceiverRef
            bottomReadAloudBookRef = book?.let { WeakReference(it) } ?: bottomReadAloudBookRef
            return
        }
        readAloudMenuButtonRef = null
        readAloudMenuButtonActivityRef = null
        bottomReadAloudReceiverRef = receiver?.let { WeakReference(it) } ?: bottomReadAloudReceiverRef
        bottomReadAloudBookRef = book?.let { WeakReference(it) } ?: bottomReadAloudBookRef
        decor.post {
            if (!canShowReaderReadAloudEntry()) return@post
            removeTaggedViews(decor, READ_ALOUD_MENU_BUTTON_TAG)
            removeTaggedViews(activity.findViewById(android.R.id.content), READ_ALOUD_MENU_BUTTON_TAG)
            val button = ReadAloudMenuButtonView(activity).apply {
                tag = READ_ALOUD_MENU_BUTTON_TAG
                contentDescription = "\u542c\u4e66"
                alpha = 0.94f
                elevation = dp(activity, 6).toFloat()
                setOnClickListener { openReadAloud() }
            }
            decor.addView(button, FrameLayout.LayoutParams(
                dp(activity, SEARCH_MENU_BUTTON_SIZE_DP),
                dp(activity, SEARCH_MENU_BUTTON_SIZE_DP),
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(activity, READ_ALOUD_MENU_BUTTON_RIGHT_MARGIN_DP)
                bottomMargin = dp(activity, SEARCH_MENU_BUTTON_BOTTOM_MARGIN_DP)
            })
            button.bringToFront()
            readAloudMenuButtonRef = WeakReference(button)
            readAloudMenuButtonActivityRef = WeakReference(activity)
        }
    }

    private fun removeReadAloudMenuButton() {
        val activity = readAloudMenuButtonActivityRef?.get() ?: activityProvider()
        readAloudMenuButtonRef = null
        readAloudMenuButtonActivityRef = null
        postRemoveTaggedViews(activity, READ_ALOUD_MENU_BUTTON_TAG)
    }

    private fun ensureSearchOverlayThemeCallbacks(activity: Activity) {
        if (searchOverlayThemeCallbacksActivityRef?.get() === activity && searchOverlayThemeCallbacks != null) return
        unregisterSearchOverlayThemeCallbacks()
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) {
                activity.runOnUiThread {
                    refreshSearchMenuButtonTheme()
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

    private fun refreshSearchMenuButtonTheme() {
        (searchMenuButtonRef?.get() as? SearchMenuButtonView)?.refreshColors()
    }

    private fun refreshSearchNavigationBarTheme() {
        val bar = searchNavigationBarRef?.get() ?: return
        val activity = searchNavigationBarActivityRef?.get() ?: bar.context
        applySearchNavigationBarTheme(bar, DialogColors(activity))
    }

    private fun maybeUnregisterSearchOverlayThemeCallbacks() {
        if (searchMenuButtonRef?.get() != null || searchNavigationBarRef?.get() != null) return
        unregisterSearchOverlayThemeCallbacks()
    }

    private fun unregisterSearchOverlayThemeCallbacks() {
        val callbacks = searchOverlayThemeCallbacks ?: return
        val activity = searchOverlayThemeCallbacksActivityRef?.get()
        runCatching { activity?.unregisterComponentCallbacks(callbacks) }
        searchOverlayThemeCallbacks = null
        searchOverlayThemeCallbacksActivityRef = null
    }

    private fun bottomSearchContext(receiver: Any?, book: Any?): CatalogContext? {
        val existing = lastCatalogContext
        val targetBook = book ?: existing?.book ?: return null
        val catalog = existing?.takeIf { bookKey(it).isNotBlank() }?.catalog.orEmpty()
        return CatalogContext(receiver ?: existing?.intentReceiver, targetBook, catalog)
    }

    private fun openBottomSearchPage() {
        val activity = activityProvider() ?: return
        if (!canShowReaderSearchEntry()) {
            removeSearchMenuButton()
            Toast.makeText(activity, "\u6682\u65e0\u6cd5\u641c\u7d22\u5f53\u524d\u4e66\u7c4d", Toast.LENGTH_SHORT).show()
            return
        }
        val context = bottomSearchContext(bottomSearchReceiverRef?.get(), bottomSearchBookRef?.get())
        if (context == null) {
            Toast.makeText(activity, "\u6682\u65e0\u6cd5\u641c\u7d22\u5f53\u524d\u4e66\u7c4d", Toast.LENGTH_SHORT).show()
            return
        }
        activity.runOnUiThread {
            ensureSearchIndexAsync(context)
            showFullTextSearchPage(activity, context)
        }
    }

    private fun bottomReadAloudContext(receiver: Any?, book: Any?): CatalogContext? {
        val root = currentEpubRoot()
        val existing = lastCatalogContext
        val targetBook = book
            ?: existing?.book
            ?: currentUiStateBook()
            ?: bottomReadAloudBookRef?.get()
            ?: bottomSearchBookRef?.get()
        if (root == null && targetBook == null && existing == null) {
            XposedBridge.log(
                "$LOG_PREFIX read aloud context unavailable root=false uiBook=false " +
                    "page=${currentPageRef?.get()?.javaClass?.name.orEmpty()}",
            )
            return null
        }
        val catalog = existing?.takeIf { bookKey(it).isNotBlank() }?.catalog.orEmpty()
        return CatalogContext(receiver ?: existing?.intentReceiver ?: bottomSearchReceiverRef?.get(), targetBook, catalog)
    }

    private fun ensureReadAloudHighlightReceiver() {
        val context = activityProvider()?.applicationContext ?: return
        synchronized(readAloudHighlightReceiverLock) {
            val existingContext = readAloudHighlightReceiverContextRef?.get()
            if (readAloudHighlightReceiver != null && existingContext === context) return
            readAloudHighlightReceiver?.let { receiver ->
                runCatching { existingContext?.unregisterReceiver(receiver) }
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    handleReadAloudHighlightBroadcast(intent ?: return)
                }
            }
            val filter = IntentFilter().apply {
                addAction(ReadAloudIntents.ACTION_CURRENT)
                addAction(ReadAloudIntents.ACTION_CLEAR)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    context.registerReceiver(receiver, filter)
                }
                readAloudHighlightReceiver = receiver
                readAloudHighlightReceiverContextRef = WeakReference(context)
                XposedBridge.log("$LOG_PREFIX read aloud highlight receiver registered")
                requestReadAloudProgressSync(context, "receiver registered")
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX read aloud highlight receiver failed: ${it.stackTraceToString()}")
            }
        }
    }

    private fun hostApplicationContext(): Context? =
        activityProvider()?.applicationContext ?: readAloudHighlightReceiverContextRef?.get()

    private fun requestReadAloudProgressSync(context: Context? = hostApplicationContext(), reason: String) {
        val appContext = context ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReadAloudProgressSyncAtMs < READ_ALOUD_PROGRESS_SYNC_MIN_INTERVAL_MS) return
        lastReadAloudProgressSyncAtMs = now
        runCatching {
            val intent = Intent(ReadAloudIntents.ACTION_SYNC_PROGRESS).apply {
                setClassName(ReadAloudIntents.MODULE_PACKAGE_NAME, ReadAloudIntents.COMMAND_RECEIVER_CLASS_NAME)
            }
            appContext.sendBroadcast(intent)
            XposedBridge.log("$LOG_PREFIX read aloud progress sync requested reason=$reason")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud progress sync request failed: ${it.stackTraceToString()}")
        }
    }

    private fun handleReadAloudHighlightBroadcast(intent: Intent) {
        when (intent.action) {
            ReadAloudIntents.ACTION_CURRENT -> applyReadAloudHighlight(intent)
            ReadAloudIntents.ACTION_CLEAR -> clearReadAloudHighlightFromIntent(intent)
        }
    }

    private fun clearReadAloudHighlightFromIntent(intent: Intent) {
        val sessionId = intent.getStringExtra(ReadAloudIntents.EXTRA_SESSION_ID).orEmpty()
        val bookKey = intent.getStringExtra(ReadAloudIntents.EXTRA_BOOK_KEY).orEmpty()
        val activeSession = activeReadAloudSessionId
        val activeBook = activeReadAloudBookKey
        if (sessionId.isNotBlank() && activeSession.isNotBlank() && sessionId != activeSession &&
            (bookKey.isBlank() || bookKey != activeBook)
        ) {
            return
        }
        if (intent.getBooleanExtra(ReadAloudIntents.EXTRA_CLEAR_SESSION, false)) {
            synchronized(readAloudRestartLock) {
                readAloudRestartSeq += 1
            }
            activeReadAloudSessionId = ""
            activeReadAloudBookKey = ""
            activeReadAloudPaused = false
            lastReadAloudFollowCfi = ""
            lastReadAloudFollowAtMs = 0L
            lastReadAloudPageKey = ""
        }
        clearReadAloudHighlight()
    }

    private fun applyReadAloudHighlight(intent: Intent) {
        val sessionId = intent.getStringExtra(ReadAloudIntents.EXTRA_SESSION_ID).orEmpty()
        val bookKey = intent.getStringExtra(ReadAloudIntents.EXTRA_BOOK_KEY).orEmpty()
        val currentBookKey = lastCatalogContext?.let(::bookKey).orEmpty()
        val isCurrentReaderBook = isReadAloudBookCurrentForUi(bookKey, currentBookKey)
        val playing = intent.getBooleanExtra(ReadAloudIntents.EXTRA_PLAYING, true)
        val restoredProgress = intent.getBooleanExtra(ReadAloudIntents.EXTRA_RESTORED_PROGRESS, false)
        val playbackStarted = intent.getBooleanExtra(ReadAloudIntents.EXTRA_PLAYBACK_STARTED, restoredProgress)
        val progressRecordable = intent.getBooleanExtra(ReadAloudIntents.EXTRA_PROGRESS_RECORDABLE, restoredProgress)
        if (!restoredProgress && sessionId.isNotBlank() && activeReadAloudSessionId.isNotBlank() && sessionId != activeReadAloudSessionId) {
            return
        }
        if (!restoredProgress && bookKey.isNotBlank() && activeReadAloudBookKey.isNotBlank() && bookKey != activeReadAloudBookKey) {
            return
        }
        if (!restoredProgress || playing) {
            if (activeReadAloudSessionId.isBlank()) activeReadAloudSessionId = sessionId
            if (activeReadAloudBookKey.isBlank()) activeReadAloudBookKey = bookKey
            activeReadAloudPaused = !playing
        }
        if (!playbackStarted) {
            if (!playing) clearReadAloudHighlight()
            XposedBridge.log("$LOG_PREFIX read aloud progress ignored before playback start session=$sessionId")
            return
        }

        val startCfi = intent.getStringExtra(ReadAloudIntents.EXTRA_START_CFI).orEmpty()
        if (startCfi.isBlank()) {
            clearReadAloudHighlight()
            return
        }
        val spokenText = intent.getStringExtra(ReadAloudIntents.EXTRA_TEXT).orEmpty().trim()
        if (spokenText.isBlank()) return
        val highlightText = intent.getStringExtra(ReadAloudIntents.EXTRA_HIGHLIGHT_TEXT)
            .orEmpty()
            .trim()
            .ifBlank { spokenText }
        val endCfi = intent.getStringExtra(ReadAloudIntents.EXTRA_END_CFI).orEmpty().ifBlank { startCfi }
        val paragraphIndex = intent.getIntExtra(ReadAloudIntents.EXTRA_PARAGRAPH_INDEX, 0).coerceAtLeast(0)
        val chapterIndex = intent.getIntExtra(ReadAloudIntents.EXTRA_CHAPTER_INDEX, 0).coerceAtLeast(0)
        val chapterTitle = intent.getStringExtra(ReadAloudIntents.EXTRA_CHAPTER_TITLE).orEmpty()
            .ifBlank { fallbackCurrentChapterTitle() }
        val elapsedMs = intent.getLongExtra(ReadAloudIntents.EXTRA_PLAYBACK_ELAPSED_MS, 0L).coerceAtLeast(0L)
        val progress = if (progressRecordable) {
            rememberReadAloudProgress(
                sessionId = sessionId,
                bookKey = bookKey,
                bookTitle = intent.getStringExtra(ReadAloudIntents.EXTRA_BOOK_TITLE).orEmpty(),
                startCfi = startCfi,
                endCfi = endCfi,
                paragraphIndex = paragraphIndex,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                summary = spokenText,
                elapsedMs = elapsedMs,
            )
        } else {
            null
        }
        if (isCurrentReaderBook) {
            if (progress != null) dispatchReadAloudStatistics(progress, "current")
            if (progress != null && (restoredProgress || !playing)) {
                scheduleRestorePersistedReadAloudProgress("read aloud progress broadcast")
            }
        } else {
            XposedBridge.log("$LOG_PREFIX read aloud progress kept for background book cfi=$startCfi")
            return
        }
        val id = READ_ALOUD_HIGHLIGHT_MARK_ID_BASE + (paragraphIndex % READ_ALOUD_HIGHLIGHT_MARK_ID_RANGE)
        val mark = createReaderMark(
            id = id,
            chapter = chapterTitle,
            startCfi = startCfi,
            endCfi = endCfi,
            quote = highlightText,
            style = MARK_STYLE_FILL,
            color = MARK_COLOR_RED,
        ) ?: return
        activeReadAloudHighlightId = id
        activeReadAloudHighlightMark = mark
        XposedBridge.log("$LOG_PREFIX read aloud highlight active ${describeSearchHighlightMark(mark)}")
        injectReadAloudHighlight(mark)
        if (playing) {
            scheduleReadAloudHighlightFollow(
                expectedBookKey = bookKey,
                startCfi = startCfi,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                text = spokenText,
                mark = mark,
            )
        }
    }

    private fun scheduleReadAloudHighlightFollow(
        expectedBookKey: String,
        startCfi: String,
        chapterIndex: Int,
        chapterTitle: String,
        text: String,
        mark: Any,
    ) {
        if (!shouldFollowReadAloudHighlight(expectedBookKey, startCfi)) return
        val now = SystemClock.elapsedRealtime()
        if (startCfi == lastReadAloudFollowCfi && now - lastReadAloudFollowAtMs < READ_ALOUD_FOLLOW_MIN_INTERVAL_MS) {
            return
        }
        lastReadAloudFollowCfi = startCfi
        lastReadAloudFollowAtMs = now
        val activity = activityProvider() ?: return
        val block = {
            if (shouldFollowReadAloudHighlight(expectedBookKey, startCfi)) {
                suppressReadAloudRestartUntilMs = SystemClock.elapsedRealtime() + READ_ALOUD_RESTART_SUPPRESS_MS
                val jumped = runCatching {
                    jumpToCfi(
                        receiver = lastCatalogContext?.intentReceiver,
                        viewModel = currentViewModelRef?.get(),
                        cfi = startCfi,
                        mark = mark,
                        chapterIndex = chapterIndex,
                        title = chapterTitle,
                        summary = text.take(120),
                    )
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX read aloud follow jump failed: ${it.stackTraceToString()}")
                }.getOrDefault(false)
                XposedBridge.log("$LOG_PREFIX read aloud follow jump dispatched=$jumped cfi=$startCfi")
                if (jumped) {
                    injectReadAloudHighlight(mark)
                }
            }
        }
        activity.runOnUiThread {
            activity.window?.decorView?.post(block) ?: block()
        }
    }

    private fun shouldFollowReadAloudHighlight(expectedBookKey: String, startCfi: String): Boolean {
        if (!isActivityResumedProvider()) return false
        if (startCfi.isBlank() || !isValidEpubCfi(startCfi)) return false
        if (currentPageRef?.get() == null || currentEpubRoot() == null) return false
        val currentContext = lastCatalogContext ?: return false
        val currentBookKey = bookKey(currentContext)
        if (currentBookKey.isBlank()) return false
        val normalizedExpectedBookKey = expectedBookKey.ifBlank { activeReadAloudBookKey }
        return normalizedExpectedBookKey.isNotBlank() &&
            currentBookKey == normalizedExpectedBookKey &&
            activeReadAloudBookKey == normalizedExpectedBookKey
    }

    private fun isReadAloudBookCurrentForUi(incomingBookKey: String, currentBookKey: String): Boolean {
        if (incomingBookKey.isBlank()) return true
        if (currentBookKey.isNotBlank()) return currentBookKey == incomingBookKey
        val currentRoot = currentEpubRoot()?.absolutePath.orEmpty()
        if (currentRoot.isNotBlank()) return incomingBookKey.contains(currentRoot)
        return currentViewModelRef?.get() == null && lastCatalogContext == null
    }

    private fun rememberReadAloudProgress(
        sessionId: String,
        bookKey: String,
        bookTitle: String,
        startCfi: String,
        endCfi: String,
        paragraphIndex: Int,
        chapterIndex: Int,
        chapterTitle: String,
        summary: String,
        elapsedMs: Long,
    ): PersistedReadAloudProgress {
        val existing = readPersistedReadAloudProgress()
        val recordedElapsedMs = existing
            ?.takeIf { it.sessionId == sessionId && it.bookKey == bookKey }
            ?.recordedElapsedMs
            ?.coerceAtMost(elapsedMs)
            ?: 0L
        val context = lastCatalogContext
        val progress = PersistedReadAloudProgress(
            timestamp = System.currentTimeMillis(),
            sessionId = sessionId,
            bookKey = bookKey,
            bookIdentity = context?.let(::searchBookIdentity).orEmpty(),
            epubRoot = currentEpubRoot()?.absolutePath.orEmpty(),
            bookTitle = bookTitle.ifBlank { context?.let(::bookTitle).orEmpty() }.ifBlank { fallbackCurrentBookTitle() },
            target = ReadingTarget(
                cfi = startCfi,
                chapterIndex = chapterIndex,
                title = chapterTitle.ifBlank { fallbackCurrentChapterTitle() },
                summary = summary.take(240),
            ),
            endCfi = endCfi.ifBlank { startCfi },
            paragraphIndex = paragraphIndex,
            elapsedMs = elapsedMs,
            recordedElapsedMs = recordedElapsedMs,
        )
        persistReadAloudProgress(progress)
        return progress
    }

    private fun dispatchReadAloudStatistics(progress: PersistedReadAloudProgress, reason: String): Boolean {
        val viewModel = currentViewModelRef?.get()
        val receiver = lastCatalogContext?.intentReceiver
        if (viewModel == null && receiver == null) return false
        if (!isPersistedReadAloudProgressForCurrentBook(progress)) return false
        val page = createReadAloudStatisticsPage(progress) ?: return false
        val intent = createReaderStatisticsIntent(page) ?: return false
        if (
            progress.sessionId == lastReadAloudStatisticsSessionId &&
            progress.target.cfi == lastReadAloudStatisticsCfi &&
            progress.elapsedMs <= lastReadAloudStatisticsElapsedMs
        ) {
            return false
        }
        val durationDeltaMs = (progress.elapsedMs - progress.recordedElapsedMs).coerceAtLeast(0L)
        if (viewModel != null) {
            setReaderStatisticsTimer(viewModel, durationDeltaMs)
        }
        val sent = runCatching {
            isDispatchingReadAloudStatistics = true
            dispatchReaderIntent(receiver, viewModel, intent, "ReadAloudStatistics")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud statistics dispatch failed: ${it.stackTraceToString()}")
        }.getOrDefault(false).also {
            isDispatchingReadAloudStatistics = false
        }
        if (!sent) return false

        lastReadAloudStatisticsSessionId = progress.sessionId
        lastReadAloudStatisticsCfi = progress.target.cfi
        lastReadAloudStatisticsElapsedMs = progress.elapsedMs
        persistReadAloudProgress(progress.copy(recordedElapsedMs = progress.elapsedMs))
        XposedBridge.log(
            "$LOG_PREFIX read aloud statistics recorded reason=$reason " +
                "deltaMs=$durationDeltaMs elapsedMs=${progress.elapsedMs} cfi=${progress.target.cfi}",
        )
        return true
    }

    private fun createReadAloudStatisticsPage(progress: PersistedReadAloudProgress): Any? =
        runCatching {
            val template = currentPageRef?.get() ?: return@runCatching null
            val document = callNoArg(template, "getDocument") ?: return@runCatching null
            val start = createEpubCfi(progress.target.cfi) ?: return@runCatching null
            val end = createEpubCfi(progress.endCfi.ifBlank { progress.target.cfi }) ?: start
            val pageClass = classLoader.loadClass(EPUB_PAGE_CLASS)
            val documentClass = classLoader.loadClass(HTML_DOCUMENT_CLASS)
            val cfiClass = classLoader.loadClass(EPUB_CFI_CLASS)
            val page = pageClass
                .getDeclaredConstructor(documentClass, cfiClass, cfiClass, String::class.java)
                .newInstance(document, start, end, progress.target.summary)
            setString(page, "setBookTitle", progress.bookTitle.ifBlank { fallbackCurrentBookTitle() })
            setInt(page, "setContentType", EPUB_PAGE_CONTENT)
            setInt(page, "setChapterIndex", progress.target.chapterIndex.coerceAtLeast(0))
            setInt(page, "setChapterTotal", readAloudChapterTotal(template))
            setInt(page, "setSpineIndex", readAloudSpineIndex(start, template))
            setString(page, "setChapter", progress.target.title)
            setFloat(page, "setPercentage", estimateReadAloudPercentage(progress, template))
            setInt(page, "setNumber", (callNoArg(template, "getNumber") as? Number)?.toInt() ?: 0)
            setInt(page, "setMaxNumber", (callNoArg(template, "getMaxNumber") as? Number)?.toInt() ?: 0)
            page
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud statistics page failed: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun createReaderStatisticsIntent(page: Any): Any? =
        runCatching {
            val pageClass = classLoader.loadClass(EPUB_PAGE_CLASS)
            val intentClass = classLoader.loadClass("$READER_UI_INTENT_CLASS\$Statistics")
            intentClass.getDeclaredConstructor(pageClass).newInstance(page)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud statistics intent failed: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun setReaderStatisticsTimer(viewModel: Any, durationDeltaMs: Long) {
        val timer = System.currentTimeMillis() - durationDeltaMs.coerceAtLeast(0L)
        val fieldSet = runCatching {
            findDeclaredField(viewModel.javaClass, "_timer")?.let { field ->
                field.isAccessible = true
                field.setLong(viewModel, timer)
                true
            } == true
        }.getOrDefault(false)
        if (fieldSet) return
        runCatching {
            val accessor = viewModel.javaClass.declaredMethods.firstOrNull { method ->
                method.name == "access\$set_timer\$p" && method.parameterTypes.size == 2
            } ?: return
            accessor.isAccessible = true
            accessor.invoke(null, viewModel, timer)
        }.onFailure { error ->
            XposedBridge.log("$LOG_PREFIX read aloud statistics timer failed: ${error.stackTraceToString()}")
        }
    }

    private fun findDeclaredField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            val field = current.declaredFields.firstOrNull { it.name == name }
            if (field != null) return field
            current = current.superclass
        }
        return null
    }

    private fun readAloudChapterTotal(template: Any): Int {
        val fromTemplate = (callNoArg(template, "getChapterTotal") as? Number)?.toInt() ?: 0
        if (fromTemplate > 0) return fromTemplate
        return lastCatalogContext?.catalog?.size?.takeIf { it > 0 } ?: 0
    }

    private fun readAloudSpineIndex(startCfi: Any, template: Any): Int {
        val fromCfi = (callNoArg(startCfi, "getSpineIndex") as? Number)?.toInt()
        if (fromCfi != null && fromCfi >= 0) return fromCfi
        return (callNoArg(template, "getSpineIndex") as? Number)?.toInt() ?: 0
    }

    private fun estimateReadAloudPercentage(progress: PersistedReadAloudProgress, template: Any): Float {
        val templateStart = callNoArg(template, "getStart")?.toString().orEmpty()
        val templateAnchor = callString(template, "getAnchor")
        if (progress.target.cfi == templateStart || progress.target.cfi == templateAnchor) {
            return ((callNoArg(template, "getPercentage") as? Number)?.toFloat() ?: 0f).coerceIn(0f, 1f)
        }
        val total = readAloudChapterTotal(template)
        if (total > 0 && progress.target.chapterIndex >= 0) {
            return (progress.target.chapterIndex.toFloat() / total.toFloat()).coerceIn(0f, 0.9999f)
        }
        return ((callNoArg(template, "getPercentage") as? Number)?.toFloat() ?: 0f).coerceIn(0f, 1f)
    }

    private fun scheduleRestorePersistedReadAloudProgress(reason: String) {
        if (pendingReadAloudProgressRestore) return
        val activity = activityProvider() ?: return
        val persisted = readPersistedReadAloudProgress() ?: return
        if (!isPersistedReadAloudProgressForCurrentBook(persisted)) return
        val restoreKey = readAloudProgressRestoreKey(persisted)
        if (restoreKey == lastRestoredReadAloudProgressKey) return
        pendingReadAloudProgressRestore = true
        activity.window?.decorView?.postDelayed({
            restorePersistedReadAloudProgress(reason)
        }, READ_ALOUD_PROGRESS_RESTORE_DELAY_MS)
    }

    private fun restorePersistedReadAloudProgress(reason: String) {
        val persisted = readPersistedReadAloudProgress()
        if (persisted == null) {
            pendingReadAloudProgressRestore = false
            return
        }
        if (!isPersistedReadAloudProgressForCurrentBook(persisted)) {
            pendingReadAloudProgressRestore = false
            return
        }
        val viewModel = currentViewModelRef?.get()
        val receiver = lastCatalogContext?.intentReceiver
        if (viewModel == null && receiver == null) {
            pendingReadAloudProgressRestore = false
            return
        }
        val restoreKey = readAloudProgressRestoreKey(persisted)
        suppressReadAloudRestartUntilMs = SystemClock.elapsedRealtime() + READ_ALOUD_RESTART_SUPPRESS_MS
        val jumped = runCatching {
            jumpToCfi(
                receiver = receiver,
                viewModel = viewModel,
                cfi = persisted.target.cfi,
                chapterIndex = persisted.target.chapterIndex,
                title = persisted.target.title,
                summary = persisted.target.summary,
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud progress restore failed: ${it.stackTraceToString()}")
        }.getOrDefault(false)
        if (jumped) {
            lastRestoredReadAloudProgressKey = restoreKey
            dispatchReadAloudStatistics(persisted, "restore")
            XposedBridge.log("$LOG_PREFIX read aloud progress restored reason=$reason cfi=${persisted.target.cfi}")
        }
        pendingReadAloudProgressRestore = false
    }

    private fun persistReadAloudProgress(progress: PersistedReadAloudProgress) {
        val context = hostApplicationContext() ?: return
        context
            .getSharedPreferences(READ_ALOUD_PROGRESS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(READ_ALOUD_PROGRESS_KEY_TIMESTAMP, progress.timestamp)
            .putString(READ_ALOUD_PROGRESS_KEY_SESSION, progress.sessionId)
            .putString(READ_ALOUD_PROGRESS_KEY_BOOK, progress.bookKey)
            .putString(READ_ALOUD_PROGRESS_KEY_BOOK_IDENTITY, progress.bookIdentity)
            .putString(READ_ALOUD_PROGRESS_KEY_EPUB_ROOT, progress.epubRoot)
            .putString(READ_ALOUD_PROGRESS_KEY_BOOK_TITLE, progress.bookTitle)
            .putString(READ_ALOUD_PROGRESS_KEY_CFI, progress.target.cfi)
            .putString(READ_ALOUD_PROGRESS_KEY_END_CFI, progress.endCfi)
            .putInt(READ_ALOUD_PROGRESS_KEY_PARAGRAPH_INDEX, progress.paragraphIndex)
            .putInt(READ_ALOUD_PROGRESS_KEY_CHAPTER_INDEX, progress.target.chapterIndex)
            .putString(READ_ALOUD_PROGRESS_KEY_TITLE, progress.target.title)
            .putString(READ_ALOUD_PROGRESS_KEY_SUMMARY, progress.target.summary)
            .putLong(READ_ALOUD_PROGRESS_KEY_ELAPSED_MS, progress.elapsedMs)
            .putLong(READ_ALOUD_PROGRESS_KEY_RECORDED_ELAPSED_MS, progress.recordedElapsedMs)
            .putBoolean(READ_ALOUD_PROGRESS_KEY_PLAYBACK_STARTED, true)
            .apply()
    }

    private fun readPersistedReadAloudProgress(): PersistedReadAloudProgress? {
        val context = hostApplicationContext() ?: return null
        val prefs = context.getSharedPreferences(READ_ALOUD_PROGRESS_PREFS, Context.MODE_PRIVATE)
        val cfi = prefs.getString(READ_ALOUD_PROGRESS_KEY_CFI, null)?.takeIf { it.isNotBlank() } ?: return null
        if (!prefs.getBoolean(READ_ALOUD_PROGRESS_KEY_PLAYBACK_STARTED, false)) return null
        return PersistedReadAloudProgress(
            timestamp = prefs.getLong(READ_ALOUD_PROGRESS_KEY_TIMESTAMP, 0L),
            sessionId = prefs.getString(READ_ALOUD_PROGRESS_KEY_SESSION, null).orEmpty(),
            bookKey = prefs.getString(READ_ALOUD_PROGRESS_KEY_BOOK, null).orEmpty(),
            bookIdentity = prefs.getString(READ_ALOUD_PROGRESS_KEY_BOOK_IDENTITY, null).orEmpty(),
            epubRoot = prefs.getString(READ_ALOUD_PROGRESS_KEY_EPUB_ROOT, null).orEmpty(),
            bookTitle = prefs.getString(READ_ALOUD_PROGRESS_KEY_BOOK_TITLE, null).orEmpty(),
            target = ReadingTarget(
                cfi = cfi,
                chapterIndex = prefs.getInt(READ_ALOUD_PROGRESS_KEY_CHAPTER_INDEX, 0),
                title = prefs.getString(READ_ALOUD_PROGRESS_KEY_TITLE, null).orEmpty().ifBlank { "\u542c\u4e66\u8fdb\u5ea6" },
                summary = prefs.getString(READ_ALOUD_PROGRESS_KEY_SUMMARY, null).orEmpty(),
            ),
            endCfi = prefs.getString(READ_ALOUD_PROGRESS_KEY_END_CFI, null).orEmpty().ifBlank { cfi },
            paragraphIndex = prefs.getInt(READ_ALOUD_PROGRESS_KEY_PARAGRAPH_INDEX, 0),
            elapsedMs = prefs.getLong(READ_ALOUD_PROGRESS_KEY_ELAPSED_MS, 0L).coerceAtLeast(0L),
            recordedElapsedMs = prefs.getLong(READ_ALOUD_PROGRESS_KEY_RECORDED_ELAPSED_MS, 0L).coerceAtLeast(0L),
        )
    }

    private fun isPersistedReadAloudProgressForCurrentBook(persisted: PersistedReadAloudProgress): Boolean {
        if (System.currentTimeMillis() - persisted.timestamp > READ_ALOUD_PROGRESS_MAX_AGE_MS) return false
        lastCatalogContext?.let { context ->
            val currentKey = bookKey(context)
            if (currentKey.isNotBlank() && persisted.bookKey.isNotBlank() && currentKey == persisted.bookKey) {
                return true
            }
            val currentIdentity = searchBookIdentity(context)
            if (
                currentIdentity.isNotBlank() &&
                persisted.bookIdentity.isNotBlank() &&
                currentIdentity == persisted.bookIdentity
            ) {
                return true
            }
        }
        val currentRoot = currentEpubRoot()?.absolutePath.orEmpty()
        return currentRoot.isNotBlank() &&
            persisted.epubRoot.isNotBlank() &&
            currentRoot == persisted.epubRoot
    }

    private fun readAloudProgressRestoreKey(progress: PersistedReadAloudProgress): String =
        listOf(progress.sessionId, progress.bookKey, progress.target.cfi, progress.timestamp.toString()).joinToString("|")

    private fun openReadAloud() {
        val activity = activityProvider() ?: return
        if (!canShowReaderReadAloudEntry()) {
            removeReadAloudMenuButton()
            Toast.makeText(activity, "\u6682\u65e0\u6cd5\u542c\u5f53\u524d\u4e66\u7c4d", Toast.LENGTH_SHORT).show()
            return
        }
        val context = bottomReadAloudContext(bottomReadAloudReceiverRef?.get(), bottomReadAloudBookRef?.get())
        if (context == null) {
            Toast.makeText(activity, "\u6682\u65e0\u6cd5\u542c\u5f53\u524d\u4e66\u7c4d", Toast.LENGTH_SHORT).show()
            return
        }
        ensureReadAloudHighlightReceiver()
        Toast.makeText(activity, "\u6b63\u5728\u51c6\u5907\u542c\u4e66", Toast.LENGTH_SHORT).show()
        val requestedAt = SystemClock.elapsedRealtime()
        Thread {
            var sessionId = ""
            runCatching {
                val buildStartedAt = SystemClock.elapsedRealtime()
                val segments = buildReadAloudSegments(
                    context = context,
                    maxSegments = READ_ALOUD_INITIAL_SEGMENTS,
                    currentFileOnly = true,
                )
                val buildMs = SystemClock.elapsedRealtime() - buildStartedAt
                XposedBridge.log(
                    "$LOG_PREFIX read aloud initial queue built segments=${segments.size} " +
                        "chars=${segments.sumOf { it.text.length }} buildMs=$buildMs",
                )
                if (segments.isEmpty()) error("empty read-aloud queue")
                val queueStartedAt = SystemClock.elapsedRealtime()
                val source = selectedTtsSource(activity)
                sessionId = startReadAloudService(
                    activity = activity,
                    bookKey = bookKey(context),
                    bookTitle = bookTitle(context).ifBlank { titleFromCurrentEpubRoot() },
                    coverUri = currentBookCoverUri(context),
                    segments = segments,
                    source = source,
                )
                appendReadAloudRemainderAsync(activity, context, sessionId, segments.size)
                XposedBridge.log(
                    "$LOG_PREFIX read aloud play requested session=$sessionId " +
                        "source=${source?.name ?: "system"} " +
                        "queueMs=${SystemClock.elapsedRealtime() - queueStartedAt} " +
                        "totalMs=${SystemClock.elapsedRealtime() - requestedAt}",
                )
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        if (source != null) {
                            "\u5df2\u5f00\u59cb\u542c\u4e66\uff1a${source.name}"
                        } else {
                            "\u5df2\u5f00\u59cb\u542c\u4e66\uff1a\u7cfb\u7edf TTS"
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX read aloud start failed: ${it.stackTraceToString()}")
                if (sessionId.isNotBlank()) stopReadAloudSession(activity, sessionId, "start-failed")
                activity.runOnUiThread {
                    Toast.makeText(activity, "\u542c\u4e66\u542f\u52a8\u5931\u8d25", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply {
            name = "ReaMicroReadAloudPrepare"
            isDaemon = true
            start()
        }
    }

    private fun scheduleReadAloudRestartFromPage(page: Any) {
        val snapshot = settingsProvider()
        if (!snapshot.canRestartReadAloudOnPageTurn) return
        if (activeReadAloudSessionId.isBlank() || activeReadAloudBookKey.isBlank()) return
        val pageKey = currentReadAloudPageKey(page)
        if (pageKey.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (now < suppressReadAloudRestartUntilMs) {
            lastReadAloudPageKey = pageKey
            return
        }
        if (pageKey == lastReadAloudPageKey) return
        val context = bottomReadAloudContext(bottomReadAloudReceiverRef?.get(), bottomReadAloudBookRef?.get())
            ?: lastCatalogContext
            ?: return
        val currentBookKey = bookKey(context)
        if (currentBookKey.isBlank() || currentBookKey != activeReadAloudBookKey) return
        lastReadAloudPageKey = pageKey
        val seq = synchronized(readAloudRestartLock) {
            readAloudRestartSeq += 1
            readAloudRestartSeq
        }
        Thread {
            Thread.sleep(READ_ALOUD_PAGE_RESTART_DELAY_MS)
            if (seq != readAloudRestartSeq) return@Thread
            restartReadAloudFromCurrentPage(context, currentBookKey, pageKey)
        }.apply {
            name = "ReaMicroReadAloudPageRestart"
            isDaemon = true
            start()
        }
    }

    private fun restartReadAloudFromCurrentPage(context: CatalogContext, expectedBookKey: String, pageKey: String) {
        val activity = activityProvider() ?: return
        if (!settingsProvider().canRestartReadAloudOnPageTurn) return
        if (activeReadAloudBookKey != expectedBookKey) return
        runCatching {
            val segments = buildReadAloudSegments(
                context = context,
                maxSegments = READ_ALOUD_INITIAL_SEGMENTS,
                currentFileOnly = true,
            )
            if (segments.isEmpty()) error("empty read-aloud queue")
            val source = selectedTtsSource(activity)
            val sessionId = startReadAloudService(
                activity = activity,
                bookKey = expectedBookKey,
                bookTitle = bookTitle(context).ifBlank { titleFromCurrentEpubRoot() },
                coverUri = currentBookCoverUri(context),
                segments = segments,
                source = source,
            )
            appendReadAloudRemainderAsync(activity, context, sessionId, segments.size)
            XposedBridge.log("$LOG_PREFIX read aloud restarted after page turn key=$pageKey")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud page restart failed: ${it.stackTraceToString()}")
        }
    }

    private fun currentReadAloudPageKey(page: Any?): String =
        epubPageSignature(page)
            ?: epubPageNumber(page)?.let { "n=$it" }
            ?: callString(page, "getAnchor")
                .ifBlank { callNoArg(page, "getStart")?.toString().orEmpty() }

    private fun closeSearchPage() {
        searchPageDialogRef?.get()?.dismiss()
        searchPageDialogRef = null
        activeSearchPageToken = 0L
        activeSearchPageUpdate = null
    }

    private fun showFullTextSearchPage(activity: Activity, context: CatalogContext) {
        closeSearchPage()
        val pageBookKey = bookKey(context)
        val pageToken = System.nanoTime()
        var colors = DialogColors(activity)
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var visibleKeyword = ""
        var visibleResults: List<FullTextSearchResult> = emptyList()
        var visibleSearching = false
        var visibleStatus: String? = null
        var refreshStickyHeader: () -> Unit = {}

        val resultsContainer = LinearLayout(activity).apply {
            tag = "searchResultsContainer"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.pageBackground)
        }
        val resultsScroll = ScrollView(activity).apply {
            tag = "searchResultsScroll"
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarSize = dp(activity, 8)
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                verticalScrollbarThumbDrawable = GradientDrawable().apply {
                    setColor(Color.argb(150, Color.red(colors.secondaryText), Color.green(colors.secondaryText), Color.blue(colors.secondaryText)))
                    cornerRadius = dp(activity, 4).toFloat()
                    setSize(dp(activity, 8), dp(activity, 64))
                }
            }
            setBackgroundColor(colors.pageBackground)
            addView(resultsContainer, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val keywordInput = EditText(activity).apply {
            tag = "searchKeywordInput"
            setSingleLine(true)
            hint = "\u641c\u7d22\u5168\u4e66"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.primaryText)
            setHintTextColor(colors.secondaryText)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(activity, 16), 0, dp(activity, 16), 0)
            background = searchKeywordInputBackground(activity, colors)
        }
        val statusLine = TextView(activity).apply {
            tag = "searchStatusLine"
            textSize = 15f
            setTextColor(colors.secondaryText)
            setBackgroundColor(colors.pageBackground)
            setPadding(dp(activity, 20), dp(activity, 3), dp(activity, 20), dp(activity, 2))
            visibility = View.GONE
        }

        fun renderStatus(message: String) {
            visibleStatus = message
            visibleKeyword = ""
            visibleResults = emptyList()
            visibleSearching = false
            searchRenderState = null
            statusLine.visibility = View.GONE
            resultsContainer.removeAllViews()
            resultsContainer.addView(TextView(activity).apply {
                text = message
                textSize = 16f
                setTextColor(colors.secondaryText)
                setPadding(dp(activity, 32), dp(activity, 28), dp(activity, 32), 0)
            })
            resultsScroll.post { refreshStickyHeader() }
        }

        fun clearVisibleResults() {
            visibleStatus = null
            visibleKeyword = ""
            visibleResults = emptyList()
            visibleSearching = false
            searchRenderState = null
            statusLine.visibility = View.GONE
            resultsContainer.removeAllViews()
            resultsScroll.post { refreshStickyHeader() }
        }

        fun renderVisibleResults(
            keyword: String,
            results: List<FullTextSearchResult>,
            searching: Boolean = false,
        ) {
            visibleStatus = null
            visibleKeyword = keyword
            visibleResults = results
            visibleSearching = searching
            val currentResultIndex = activeSearchNavigation
                ?.takeIf { it.bookKey == bookKey(context) }
                ?.currentIndex
                ?.takeIf { it in results.indices }
            renderSearchResults(activity, resultsContainer, statusLine, keyword, results, colors, searching, currentResultIndex)
            scrollSearchResultToCenter(resultsScroll, resultsContainer, currentResultIndex)
            resultsScroll.post { refreshStickyHeader() }
        }

        activeSearchPageToken = pageToken
        activeSearchPageUpdate = { state, searching ->
            activity.runOnUiThread {
                if (activeSearchPageToken != pageToken) return@runOnUiThread
                if (searchPageDialogRef?.get() !== dialog) return@runOnUiThread
                if (state.bookKey != pageBookKey) return@runOnUiThread
                if (keywordInput.text?.toString()?.trim().orEmpty() != state.keyword) return@runOnUiThread
                renderVisibleResults(state.keyword, state.results, searching)
            }
        }

        fun renderCached(): Boolean {
            val cached = lastSearchState?.takeIf { it.bookKey == pageBookKey }
            if (cached == null) {
                clearVisibleResults()
                return false
            }
            keywordInput.setText(cached.keyword)
            keywordInput.setSelection(keywordInput.text?.length ?: 0)
            val searching = activeSearchJobKey == fullTextSearchJobKey(cached.bookKey, cached.keyword)
            renderVisibleResults(cached.keyword, cached.results, searching)
            return cached.results.isNotEmpty() || searching
        }

        fun runSearch() {
            val keyword = keywordInput.text?.toString().orEmpty().trim()
            if (keyword.isBlank()) {
                clearVisibleResults()
                return
            }
            renderVisibleResults(keyword, emptyList(), searching = true)
            val runSeq = System.currentTimeMillis()
            searchRunSeq = runSeq
            val jobKey = fullTextSearchJobKey(bookKey(context), keyword)
            activeSearchJobKey = jobKey
            hideKeyboard(keywordInput)
            Thread {
                runCatching {
                    searchFullTextStreaming(keyword, context) { results, done ->
                        activity.runOnUiThread {
                            val currentBookKey = bookKey(context)
                            if (activeSearchJobKey != jobKey && searchRunSeq != runSeq) return@runOnUiThread
                            val state = SearchState(currentBookKey, keyword, results)
                            lastSearchState = state
                            if (done && activeSearchJobKey == jobKey) activeSearchJobKey = null
                            activeSearchPageUpdate?.invoke(state, !done)
                        }
                    }
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX full-text search failed: ${it.stackTraceToString()}")
                    activity.runOnUiThread {
                        if (searchRunSeq == runSeq && searchPageDialogRef?.get() === dialog) {
                            renderStatus("\u641c\u7d22\u5931\u8d25")
                        }
                    }
                }
            }.apply {
                name = "ReaMicroFullTextSearch"
                isDaemon = true
                start()
            }
        }

        keywordInput.setOnEditorActionListener { _, actionId, event ->
            val enterUp = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enterUp) {
                runSearch()
                true
            } else {
                false
            }
        }

        val searchAction = TextView(activity).apply {
            text = "\u641c\u7d22"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(colors.actionBackground)
            setOnClickListener { runSearch() }
        }
        val closeAction = TextView(activity).apply {
            text = "\u5173\u95ed"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(colors.primaryText)
            setOnClickListener { dialog.dismiss() }
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 14), dp(activity, 16), dp(activity, 14), dp(activity, 12))
            addView(keywordInput, LinearLayout.LayoutParams(0, dp(activity, 40), 1f).apply {
                rightMargin = dp(activity, 10)
            })
            addView(searchAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 44)).apply {
                rightMargin = dp(activity, 16)
            })
            addView(closeAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 44)))
        }

        val stickyVolumeHeader = TextView(activity).apply {
            tag = "searchStickyVolumeHeader"
            textSize = 13f
            setTextColor(colors.primaryText)
            setBackgroundColor(colors.pageBackground)
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 2))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        val stickyHeader = TextView(activity).apply {
            tag = "searchStickyHeader"
            textSize = 13f
            setTextColor(colors.primaryText)
            setBackgroundColor(colors.pageBackground)
            setPadding(dp(activity, 20), dp(activity, 2), dp(activity, 20), dp(activity, 8))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }

        fun updateStickyHeader() {
            val scrollY = resultsScroll.scrollY
            var currentVolume: CharSequence? = null
            var currentChapter: CharSequence? = null
            for (i in 0 until resultsContainer.childCount) {
                val child = resultsContainer.getChildAt(i)
                val childTag = child.tag as? String ?: continue
                if (child.top >= scrollY) break
                when {
                    childTag.startsWith("searchVolume:") -> currentVolume = (child as? TextView)?.text
                    childTag.startsWith("searchGroup:") -> currentChapter = (child as? TextView)?.text
                }
            }
            if (currentVolume == null) {
                stickyVolumeHeader.visibility = View.GONE
            } else {
                stickyVolumeHeader.text = currentVolume
                stickyVolumeHeader.visibility = View.VISIBLE
            }
            if (currentChapter == null) {
                stickyHeader.visibility = View.GONE
            } else {
                stickyHeader.text = currentChapter
                stickyHeader.visibility = View.VISIBLE
            }
        }

        val stickyColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.pageBackground)
            addView(stickyVolumeHeader, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(stickyHeader, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        val resultsWrapper = FrameLayout(activity).apply {
            addView(resultsScroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(stickyColumn, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        resultsScroll.setOnScrollChangeListener { _, _, _, _, _ -> updateStickyHeader() }
        refreshStickyHeader = { updateStickyHeader() }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.pageBackground)
            addView(header, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(statusLine, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(resultsWrapper, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }

        fun applySearchPageTheme() {
            colors = DialogColors(activity)
            root.setBackgroundColor(colors.pageBackground)
            resultsScroll.setBackgroundColor(colors.pageBackground)
            resultsContainer.setBackgroundColor(colors.pageBackground)
            statusLine.setTextColor(colors.secondaryText)
            statusLine.setBackgroundColor(colors.pageBackground)
            stickyColumn.setBackgroundColor(colors.pageBackground)
            stickyVolumeHeader.setTextColor(colors.primaryText)
            stickyVolumeHeader.setBackgroundColor(colors.pageBackground)
            stickyHeader.setTextColor(colors.secondaryText)
            stickyHeader.setBackgroundColor(colors.pageBackground)
            keywordInput.setTextColor(colors.primaryText)
            keywordInput.setHintTextColor(colors.secondaryText)
            keywordInput.background = searchKeywordInputBackground(activity, colors)
            searchAction.setTextColor(colors.actionBackground)
            closeAction.setTextColor(colors.primaryText)
            dialog.window?.let { configureFullTextSearchWindow(it, colors, requestKeyboard = false) }
            val status = visibleStatus
            if (status != null) {
                renderStatus(status)
            } else if (visibleKeyword.isNotBlank() || visibleResults.isNotEmpty() || visibleSearching) {
                renderVisibleResults(visibleKeyword, visibleResults, visibleSearching)
            }
        }

        val themeCallbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) {
                activity.runOnUiThread {
                    if (searchPageDialogRef?.get() === dialog) {
                        applySearchPageTheme()
                    }
                }
            }

            override fun onLowMemory() = Unit

            override fun onTrimMemory(level: Int) = Unit
        }

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            if (searchPageDialogRef?.get() === dialog) searchPageDialogRef = null
            if (activeSearchPageToken == pageToken) {
                activeSearchPageToken = 0L
                activeSearchPageUpdate = null
            }
            runCatching { activity.unregisterComponentCallbacks(themeCallbacks) }
            hideKeyboard(keywordInput)
        }
        searchPageDialogRef = WeakReference(dialog)
        activity.registerComponentCallbacks(themeCallbacks)
        dialog.show()
        dialog.window?.let { window ->
            configureFullTextSearchWindow(window, colors, requestKeyboard = lastSearchState?.takeIf {
                it.bookKey == pageBookKey
            }?.results.isNullOrEmpty())
        }
        val hasCachedResults = renderCached()
        if (hasCachedResults) {
            hideKeyboard(keywordInput)
        } else {
            focusEditorAndShowKeyboard(activity, keywordInput)
        }
    }

    private fun searchKeywordInputBackground(context: Context, colors: DialogColors): GradientDrawable =
        GradientDrawable().apply {
            setColor(colors.inputBackground)
            cornerRadius = dp(context, 18).toFloat()
            setStroke(dp(context, 1), colors.stroke)
        }

    private fun configureFullTextSearchWindow(
        window: Window,
        colors: DialogColors,
        requestKeyboard: Boolean = true,
    ) {
        val bg = colors.pageBackground
        val dark = colors.dark
        window.apply {
            setBackgroundDrawable(ColorDrawable(bg))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                statusBarColor = bg
                navigationBarColor = bg
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                navigationBarDividerColor = Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isStatusBarContrastEnforced = false
                isNavigationBarContrastEnforced = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setDecorFitsSystemWindows(false)
            }
            decorView.setPadding(0, 0, 0, 0)
            decorView.setBackgroundColor(bg)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                decorView.systemUiVisibility = fullTextSearchSystemUiFlags(dark)
            }
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or (if (requestKeyboard) {
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                } else {
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                }),
            )
            applyFullTextSearchSystemBarAppearance(this, dark)
        }
    }

    private fun applyFullTextSearchSystemBarAppearance(window: Window, dark: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(
            if (dark) 0 else lightBars,
            lightBars,
        )
    }

    private fun fullTextSearchSystemUiFlags(dark: Boolean): Int {
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (!dark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (!dark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        return flags
    }

    private fun hookNativeSelectionController() {
        runCatching {
            val controllerClass = classLoader.loadClass("org.epub.ui.EpubSelectionController")
            XposedBridge.hookAllConstructors(controllerClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    currentSelectionControllerRef = WeakReference(param.thisObject)
                }
            })
            controllerClass.declaredMethods
                .filter { method ->
                    method.name.contains("Selection", ignoreCase = true) ||
                        method.name == "selectedPayload" ||
                        method.name == "selectedText"
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            currentSelectionControllerRef = WeakReference(param.thisObject)
                        }
                    })
                }
            XposedBridge.log("$LOG_PREFIX native EpubSelectionController hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX native selection controller hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookNativeSelectionMenu() {
        runCatching {
            val bodyClass = classLoader.loadClass("org.epub.ui.BodyKt")
            val menuMethods = bodyClass.declaredMethods.filter {
                it.name == "SelectionBubbleMenu" &&
                    it.parameterTypes.size >= 2 &&
                    List::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            if (menuMethods.isEmpty()) error("SelectionBubbleMenu not found")
            menuMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val original = (param.args?.getOrNull(0) as? List<*>) ?: return
                        val next = ArrayList<Any>(original.filterNotNull())
                        val fallbackIcon = original.firstNotNullOfOrNull { callNoArg(it, "getIcon") }
                        if (canShowReaderDictionary() && original.none { callString(it, "getTitle") == "\u8bcd\u5178" }) {
                            val action = (dictionaryImageVector() ?: fallbackIcon)?.let(::createNativeDictionaryAction)
                            if (action != null) next.add(action)
                        }
                        if (canEditReaderSelection() && original.none { callString(it, "getTitle") == "\u7f16\u8f91" }) {
                            val action = (editImageVector() ?: fallbackIcon)?.let(::createNativeEditAction)
                            if (action != null) next.add(action)
                        }
                        if (canHighlightReaderSelection() && original.none { callString(it, "getTitle") == "\u9ad8\u4eae" }) {
                            val action = (highlightImageVector() ?: fallbackIcon)?.let(::createNativeHighlightAction)
                            if (action != null) next.add(action)
                        }
                        if (canUseReadAloudSelection() && original.none { callString(it, "getTitle") == "\u968f\u542c" }) {
                            val action = (readAloudImageVector() ?: fallbackIcon)?.let(::createNativeReadAloudAction)
                            if (action != null) next.add(action)
                        }
                        val compact = if (settingsProvider().canUseCompactReaderSelectionMenu) {
                            compactSelectionMenuActions(next)
                        } else {
                            next
                        }
                        if (next.size != original.size || compact !== next) param.args[0] = compact
                    }
                })
            }
            hookNativeSelectionMenuContent(bodyClass)
            XposedBridge.log("$LOG_PREFIX native SelectionBubbleMenu hook installed: ${menuMethods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX native selection menu hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookNativeSelectionMenuContent(bodyClass: Class<*>) {
        val contentMethods = bodyClass.declaredMethods.filter { method ->
            method.name.contains("SelectionBubbleMenu") &&
                method.name.contains("lambda") &&
                method.parameterTypes.size == 4 &&
                List::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                method.parameterTypes[1] == Long::class.javaPrimitiveType
        }
        if (contentMethods.isEmpty()) {
            XposedBridge.log("$LOG_PREFIX native SelectionBubbleMenu content hook skipped: lambda not found")
            return
        }
        contentMethods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val actions = (param.args?.getOrNull(0) as? List<*>)?.filterNotNull().orEmpty()
                    val maxPerRow = selectionMenuMaxItemsPerRow(actions.size) ?: return
                    val tint = (param.args?.getOrNull(1) as? Number)?.toLong() ?: return
                    val composer = param.args?.getOrNull(2) ?: return
                    runCatching {
                        renderWrappedSelectionMenu(actions, tint, composer, maxPerRow)
                    }.onSuccess {
                        param.result = targetUnit()
                    }.onFailure {
                        XposedBridge.log("$LOG_PREFIX wrap native selection menu failed: ${it.stackTraceToString()}")
                    }
                }
            })
        }
        XposedBridge.log("$LOG_PREFIX native SelectionBubbleMenu content hook installed: ${contentMethods.size}")
    }

    private fun selectionMenuMaxItemsPerRow(actionCount: Int): Int? {
        if (settingsProvider().canUseCompactReaderSelectionMenu) {
            return if (actionCount > 6) 6 else null
        }
        return when {
            actionCount <= 4 -> null
            actionCount <= 6 -> 3
            else -> 4
        }
    }

    private fun renderWrappedSelectionMenu(actions: List<Any>, tint: Long, composer: Any, maxPerRow: Int) {
        val content = functionProxy("ReaderSelectionFlowRow", KOTLIN_FUNCTION3_CLASS) { args ->
            val innerComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
            actions.forEach { action ->
                renderNativeSelectionMenuItem(action, tint, innerComposer)
            }
            targetUnit()
        }
        composeMethod(FLOW_LAYOUT_KT_CLASS, FLOW_ROW_METHOD, 10).invoke(
            null,
            selectionMenuPaddingModifier(),
            arrangementSpacedBy(2),
            arrangementSpacedBy(2),
            null,
            maxPerRow,
            Int.MAX_VALUE,
            content,
            composer,
            0,
            8,
        )
    }

    private fun renderNativeSelectionMenuItem(action: Any, tint: Long, composer: Any) {
        nativeSelectionMenuItemMethod().invoke(
            null,
            callString(action, "getTitle"),
            callNoArg(action, "getIcon") ?: return,
            tint,
            selectionMenuActionCallback(action) ?: return,
            composer,
            0,
        )
    }

    private fun nativeSelectionMenuItemMethod(): Method =
        synchronized(composeMethodCache) {
            composeMethodCache.getOrPut("org.epub.ui.BodyKt#SelectionMenuItem/6") {
                classLoader.loadClass("org.epub.ui.BodyKt").declaredMethods.firstOrNull { method ->
                    method.name.contains("SelectionMenuItem") &&
                        method.parameterTypes.size == 6 &&
                        method.parameterTypes[0] == String::class.java &&
                        method.parameterTypes[2] == Long::class.javaPrimitiveType
                }?.apply { isAccessible = true }
                    ?: error("SelectionMenuItem method not found")
            }
        }

    private fun selectionMenuPaddingModifier(): Any =
        composeMethod(PADDING_KT_CLASS, PADDING_METHOD, 5).invoke(
            null,
            modifierInstance(),
            udp(8),
            udp(6),
            udp(8),
            udp(6),
        )

    private fun arrangementSpacedBy(dp: Int): Any =
        ARRANGEMENT_SPACED_BY_METHOD_CANDIDATES.firstNotNullOfOrNull { name ->
            runCatching {
                staticObject(ARRANGEMENT_CLASS, "INSTANCE").javaClass.methods.firstOrNull { method ->
                    method.name == name && method.parameterTypes.size == 1
                }?.invoke(staticObject(ARRANGEMENT_CLASS, "INSTANCE"), udp(dp))
            }.getOrNull()
        } ?: error("Arrangement.spacedBy method not found")

    private fun sectionTitleModifier(top: Int, bottom: Int): Any =
        composeMethod(PADDING_KT_CLASS, PADDING_DEFAULT_METHOD, 7).invoke(
            null,
            modifierInstance(),
            0f,
            udp(top),
            0f,
            udp(bottom),
            5,
            null,
        )

    private fun fillMaxWidth(modifier: Any): Any =
        composeMethod(SIZE_KT_CLASS, FILL_MAX_WIDTH_DEFAULT_METHOD, 4).invoke(null, modifier, 0f, 1, null)

    private fun alignmentTop(): Any =
        callNoArg(staticObject(ALIGNMENT_CLASS, "INSTANCE"), "getTop") ?: error("Alignment.Top not found")

    private fun modifierInstance(): Any =
        staticObject(MODIFIER_CLASS, "INSTANCE")

    private fun udp(value: Int): Float =
        classLoader.loadClass(UNIT_EXT_KT_CLASS).declaredMethods.first {
            it.name == UDP_METHOD && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }.apply { isAccessible = true }.invoke(null, value) as Float

    private fun functionProxy(name: String, functionClassName: String, block: (Array<Any?>?) -> Any?): Any {
        val functionClass = classLoader.loadClass(functionClassName)
        return Proxy.newProxyInstance(classLoader, arrayOf(functionClass)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> runCatching {
                    block(args)
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX failed in $name callback: ${it.stackTraceToString()}")
                }.getOrElse { targetUnit() }
                "toString" -> "ReaMicro$name"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
    }

    private fun composeMethod(className: String, methodName: String, parameterCount: Int): Method {
        val cacheKey = "$className#$methodName/$parameterCount"
        return synchronized(composeMethodCache) {
            composeMethodCache.getOrPut(cacheKey) {
                classLoader.loadClass(className).declaredMethods.firstOrNull { method ->
                    method.name == methodName && method.parameterTypes.size == parameterCount
                }?.apply { isAccessible = true }
                    ?: error("$className.$methodName/$parameterCount not found")
            }
        }
    }

    private fun cacheThemeColors(composer: Any) {
        runCatching {
            val colorScheme = hostColorScheme(composer)
            cachedThemeColors = ThemeColors(
                pageBackground = hostThemeColor("getBackgroundAuto", colorScheme),
                cardBackground = hostThemeColor("getBackgroundBright", colorScheme),
                inputBackground = hostThemeColor("getBackgroundBright", colorScheme),
                primaryText = hostColorSchemeColor(colorScheme, "getOnBackground-0d7_KjU"),
                secondaryText = hostThemeColor("getOnBackgroundVariant", colorScheme),
                stroke = hostThemeColor("getBorderVariant", colorScheme),
                chipBackground = hostThemeColor("getBackgroundDim", colorScheme),
                action = hostColorSchemeColor(colorScheme, "getPrimary-0d7_KjU"),
            )
            latestThemeColors = cachedThemeColors
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX cache host theme colors failed: ${it.stackTraceToString()}")
        }
    }

    private fun hostColorScheme(composer: Any): Any {
        val materialTheme = staticObject(MATERIAL_THEME_CLASS, "INSTANCE")
        val stable = staticInt(MATERIAL_THEME_CLASS, "\$stable")
        return composeMethod(MATERIAL_THEME_CLASS, "getColorScheme", 2).invoke(materialTheme, composer, stable)
            ?: error("MaterialTheme.colorScheme unavailable")
    }

    private fun hostThemeColor(methodName: String, colorScheme: Any): Int =
        composeColorToArgb(
            composeMethod(THEME_KT_CLASS, methodName, 1).invoke(null, colorScheme) as Long,
        )

    private fun hostColorSchemeColor(colorScheme: Any, methodName: String): Int =
        composeColorToArgb(
            colorScheme.javaClass.methods.first {
                it.name == methodName && it.parameterTypes.isEmpty()
            }.apply { isAccessible = true }.invoke(colorScheme) as Long,
        )

    private fun composeColorToArgb(color: Long): Int =
        runCatching {
            classLoader.loadClass(COLOR_KT_CLASS).declaredMethods.firstOrNull { method ->
                method.name.contains("toArgb", ignoreCase = true) &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Long::class.javaPrimitiveType
            }?.apply { isAccessible = true }?.invoke(null, color) as? Int
        }.getOrNull() ?: composeColorFallbackToArgb(color)

    private fun composeColorFallbackToArgb(color: Long): Int =
        if ((color and 0x3fL) == 0L) {
            (color ushr 32).toInt()
        } else {
            0
        }

    private fun staticInt(className: String, fieldName: String): Int =
        classLoader.loadClass(className).getDeclaredField(fieldName).apply { isAccessible = true }.getInt(null)

    private fun staticObject(className: String, fieldName: String): Any {
        val clazz = classLoader.loadClass(className)
        val field = runCatching { clazz.getDeclaredField(fieldName) }
            .recoverCatching { clazz.getField(fieldName) }
            .recoverCatching { clazz.getDeclaredField("Companion") }
            .getOrElse {
                val fields = clazz.declaredFields.joinToString { field -> "${field.name}:${field.type.name}" }
                error("$className.$fieldName not found; fields=[$fields]")
            }
        field.isAccessible = true
        return field.get(null)
    }

    private fun compactSelectionMenuActions(actions: List<Any>): List<Any> {
        val compact = actions.map { action -> compactSelectionMenuAction(action) ?: action }
        return if (compact.indices.any { index -> compact[index] !== actions[index] }) {
            ArrayList(compact)
        } else {
            actions
        }
    }

    private fun compactSelectionMenuAction(action: Any): Any? {
        val title = callString(action, "getTitle")
        if (title.isBlank()) return null
        val icon = callNoArg(action, "getIcon") ?: return null
        val callback = selectionMenuActionCallback(action) ?: return null
        return createSelectionMenuAction("", icon, callback, "compact native selection action")
    }

    private fun selectionMenuActionCallback(action: Any): Any? {
        val function0Class = runCatching { classLoader.loadClass(KOTLIN_FUNCTION0_CLASS) }.getOrNull() ?: return null
        val preferredNames = listOf("getOnClick", "getOnTap", "getAction", "getCallback", "component3")
        preferredNames.forEach { name ->
            callNoArg(action, name)?.takeIf { function0Class.isInstance(it) }?.let { return it }
        }
        return (action.javaClass.methods.asSequence() + action.javaClass.declaredMethods.asSequence())
            .filter { method ->
                method.parameterTypes.isEmpty() && function0Class.isAssignableFrom(method.returnType)
            }
            .firstNotNullOfOrNull { method ->
                runCatching {
                    method.isAccessible = true
                    method.invoke(action)
                }.getOrNull()?.takeIf { function0Class.isInstance(it) }
            }
    }

    private fun createNativeEditAction(icon: Any): Any? =
        createSelectionMenuAction(
            if (settingsProvider().canUseCompactReaderSelectionMenu) "" else "\u7f16\u8f91",
            icon,
            nativeFunction0 {
                openNativeSelectionEditor()
            },
            "create native edit action",
        )

    private fun createNativeDictionaryAction(icon: Any): Any? =
        createSelectionMenuAction(
            if (settingsProvider().canUseCompactReaderSelectionMenu) "" else "\u8bcd\u5178",
            icon,
            nativeFunction0 {
                openNativeSelectionDictionary()
            },
            "create native dictionary action",
        )

    private fun createNativeHighlightAction(icon: Any): Any? =
        createSelectionMenuAction(
            if (settingsProvider().canUseCompactReaderSelectionMenu) "" else "\u9ad8\u4eae",
            icon,
            nativeFunction0 {
                highlightNativeSelection()
            },
            "create native highlight action",
        )

    private fun createNativeReadAloudAction(icon: Any): Any? =
        createSelectionMenuAction(
            if (settingsProvider().canUseCompactReaderSelectionMenu) "" else "\u968f\u542c",
            icon,
            nativeFunction0 {
                openNativeSelectionReadAloud()
            },
            "create native read aloud action",
        )

    private fun createSelectionMenuAction(title: String, icon: Any, callback: Any, logLabel: String): Any? =
        runCatching {
            val actionClass = classLoader.loadClass("org.epub.ui.SelectionMenuAction")
            val constructor = actionClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 3 }
                ?: return@runCatching null
            constructor.isAccessible = true
            constructor.newInstance(
                title,
                icon,
                callback,
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX $logLabel failed: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun editImageVector(): Any? =
        runCatching {
            val outlined = classLoader.loadClass(ICONS_OUTLINED_CLASS).getField("INSTANCE").get(null)
            classLoader.loadClass(EDIT_ICON_CLASS).declaredMethods.firstOrNull {
                it.name == "getEdit" && it.parameterTypes.size == 1
            }?.apply { isAccessible = true }?.invoke(null, outlined)
        }.getOrNull()

    private fun dictionaryImageVector(): Any? {
        val outlined = runCatching {
            classLoader.loadClass(ICONS_OUTLINED_CLASS).getField("INSTANCE").get(null)
        }.getOrNull() ?: return null
        return listOf(
            DICTIONARY_ICON_TRANSLATE_CLASS to "getTranslate",
            DICTIONARY_ICON_MENU_BOOK_CLASS to "getMenuBook",
            DICTIONARY_ICON_AUTO_STORIES_CLASS to "getAutoStories",
            DICTIONARY_ICON_BOOK_CLASS to "getBook",
        ).firstNotNullOfOrNull { (className, methodName) ->
            runCatching {
                classLoader.loadClass(className).declaredMethods.firstOrNull {
                    it.name == methodName && it.parameterTypes.size == 1
                }?.apply { isAccessible = true }?.invoke(null, outlined)
            }.getOrNull()
        }
    }

    private fun highlightImageVector(): Any? {
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

    private fun readAloudImageVector(): Any? {
        val outlined = runCatching {
            classLoader.loadClass(ICONS_OUTLINED_CLASS).getField("INSTANCE").get(null)
        }.getOrNull() ?: return null
        return listOf(
            READ_ALOUD_ICON_VOLUME_UP_CLASS to "getVolumeUp",
            READ_ALOUD_ICON_RECORD_VOICE_OVER_CLASS to "getRecordVoiceOver",
            DICTIONARY_ICON_AUTO_STORIES_CLASS to "getAutoStories",
        ).firstNotNullOfOrNull { (className, methodName) ->
            runCatching {
                classLoader.loadClass(className).declaredMethods.firstOrNull {
                    it.name == methodName && it.parameterTypes.size == 1
                }?.apply { isAccessible = true }?.invoke(null, outlined)
            }.getOrNull()
        }
    }

    private fun nativeFunction0(block: () -> Unit): Any {
        val function0Class = classLoader.loadClass("kotlin.jvm.functions.Function0")
        return Proxy.newProxyInstance(classLoader, arrayOf(function0Class)) { _, method, _ ->
            when (method.name) {
                "invoke" -> {
                    block()
                    targetKotlinUnit()
                }
                "toString" -> "ReaMicroEditAction"
                "hashCode" -> System.identityHashCode(block)
                "equals" -> false
                else -> null
            }
        }
    }

    private fun targetKotlinUnit(): Any? =
        runCatching { classLoader.loadClass("kotlin.Unit").getField("INSTANCE").get(null) }.getOrNull()

    private fun hookCurrentEpub() {
        runCatching {
            val epubClass = classLoader.loadClass("app.zhendong.reamicro.data.epub.Epub")
            epubClass.declaredMethods
                .filter { it.name == "read" }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val previousEpub = currentEpubRef?.get()
                            if (previousEpub != null && previousEpub !== param.thisObject) {
                                val previousDirectory = epubDirectory(previousEpub)
                                val nextDirectory = epubDirectory(param.thisObject)
                                if (
                                    previousDirectory.isBlank() ||
                                    nextDirectory.isBlank() ||
                                    previousDirectory != nextDirectory
                                ) {
                                    currentPageRef = null
                                    resetFullTextSearchState("epub changed", removeOverlays = true)
                                }
                            }
                            currentEpubRef = WeakReference(param.thisObject)
                            currentHighlightBookIdentity()?.let { (bookKey, bookTitle) ->
                                updateReaderHighlightBookContext(
                                    bookKey = bookKey,
                                    bookTitle = bookTitle,
                                    source = "epub read",
                                    requestRefresh = false,
                                )
                            }
                        }
                    })
                }
            XposedBridge.log("$LOG_PREFIX current Epub hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX current Epub hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookCurrentEpubPage() {
        runCatching {
            val containerClass = classLoader.loadClass("app.zhendong.reamicro.ui.reader.components.EpubContainerKt")
            containerClass.declaredMethods
                .filter { it.name == "EpubContainer" && it.parameterTypes.size >= 2 }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val page = param.args?.getOrNull(1)
                            currentPageRef = WeakReference(page)
                            renderingEpubPage.set(page)
                            currentHighlightBookIdentity()?.let { (bookKey, bookTitle) ->
                                updateReaderHighlightBookContext(bookKey, bookTitle, "page rendered")
                            }
                            scheduleRestorePersistedSearchOrigin("page rendered")
                            scheduleRestorePersistedReadAloudProgress("page rendered")
                            val args = param.args ?: return
                            val marksIndex = 4
                            val originalMarks = args.getOrNull(marksIndex) as? List<*> ?: return
                            val nextMarks = appendActiveSearchHighlightMark(originalMarks, "EpubContainer") ?: return
                            args[marksIndex] = nextMarks
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            renderingEpubPage.remove()
                        }
                    })
                }
            XposedBridge.log("$LOG_PREFIX current EpubPage hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX current EpubPage hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookReaderSharedStateMarks() {
        runCatching {
            val sharedStateClass = classLoader.loadClass(READER_SHARED_STATE_CLASS)
            XposedBridge.hookAllMethods(sharedStateClass, "getMarks", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // a11 moved visible reader marks into ReaderSharedState; append the active
                    // search highlight here so normal mark loading does not need to know about it.
                    val original = (param.result as? List<*>) ?: return
                    val nextMarks = appendActiveSearchHighlightMark(original, "ReaderSharedState") ?: return
                    param.result = nextMarks
                }
            })
            XposedBridge.log("$LOG_PREFIX full-text search highlight ReaderSharedState hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX full-text search highlight ReaderSharedState hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookReaderCatalogHighlightPrecomputations() {
        runCatching {
            val viewModelClass = classLoader.loadClass(READER_VIEW_MODEL_CLASS)
            val methods = viewModelClass.declaredMethods.filter { method ->
                method.name == "computeCatalogPrecomputations" &&
                    method.parameterTypes.size >= 3 &&
                    List::class.java.isAssignableFrom(method.parameterTypes[2])
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args ?: return
                        val originalMarks = args.getOrNull(2) as? List<*> ?: return
                        val nextMarks = appendActiveSearchHighlightMark(originalMarks, "CatalogPrecompute") ?: return
                        args[2] = nextMarks
                    }
                })
            }
            XposedBridge.log(
                "$LOG_PREFIX full-text search highlight CatalogPrecompute hook installed count=${methods.size}",
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX full-text search highlight CatalogPrecompute hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookSearchHighlightRenderInputs() {
        hookSearchHighlightMarksArgument(
            className = "org.epub.ui.BodyKt",
            methodName = "Body",
            marksIndex = 4,
            label = "Body",
        )
        hookSearchHighlightResolvedPage()
        hookSearchHighlightContentOverlays()
        hookSearchHighlightMarksArgument(
            className = "app.zhendong.reamicro.ui.reader.components.ScrollPagerKt",
            methodName = "resolveMarksForElement",
            marksIndex = 1,
            label = "ScrollResolve",
        )
    }

    private fun hookSearchHighlightMarksArgument(
        className: String,
        methodName: String,
        marksIndex: Int,
        label: String,
    ) {
        runCatching {
            val targetClass = classLoader.loadClass(className)
            var count = 0
            targetClass.declaredMethods
                .filter { it.name == methodName && it.parameterTypes.size > marksIndex }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val args = param.args ?: return
                            val originalMarks = args.getOrNull(marksIndex) as? List<*> ?: return
                            val nextMarks = appendActiveSearchHighlightMark(originalMarks, label) ?: return
                            args[marksIndex] = nextMarks
                        }
                    })
                    count++
                }
            XposedBridge.log("$LOG_PREFIX full-text search highlight $label hook installed count=$count")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX full-text search highlight $label hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookSearchHighlightResolvedPage() {
        runCatching {
            val bodyClass = classLoader.loadClass("org.epub.ui.BodyKt")
            val methods = bodyClass.declaredMethods.filter {
                it.name == "resolveMarksForPage" && it.parameterTypes.size >= 2
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args ?: return
                        val originalMarks = args.getOrNull(0) as? List<*> ?: return
                        val nextMarks = appendActiveSearchHighlightMark(originalMarks, "ResolvePageInput") ?: return
                        args[0] = nextMarks
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val current = (param.result as? List<*>)?.filterNotNull().orEmpty()
                        val additions = ArrayList<Any>()
                        activeTransientHighlightMarks().forEach { mark ->
                            val id = transientHighlightMarkId(mark) ?: return@forEach
                            if (current.any { transientHighlightResolvedMarkId(it) == id } ||
                                additions.any { transientHighlightResolvedMarkId(it) == id }
                            ) {
                                logSearchHighlightResolvePage("hit", current.size, mark)
                                return@forEach
                            }
                            val resolved = createResolvedSearchHighlightMark(mark) ?: return@forEach
                            additions += resolved
                            logSearchHighlightResolvePage("forced", current.size + additions.size, mark)
                        }
                        if (additions.isEmpty()) return
                        param.result = ArrayList<Any>(current.size + 1).apply {
                            addAll(current)
                            addAll(additions)
                        }
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX full-text search highlight ResolvePage hook installed count=${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX full-text search highlight ResolvePage hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun hookSearchHighlightContentOverlays() {
        runCatching {
            val contentClass = classLoader.loadClass("org.epub.ui.ContentKt")
            val candidates = contentClass.declaredMethods.filter { method ->
                List::class.java.isAssignableFrom(method.returnType) &&
                    method.parameterTypes.any { List::class.java.isAssignableFrom(it) } &&
                    method.parameterTypes.any { it == Int::class.javaPrimitiveType }
            }
            val methods = candidates.filter(::isSearchHighlightContentOverlayMethod)
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args ?: return
                        val current = (args.getOrNull(1) as? List<*>)?.filterNotNull().orEmpty()
                        val additions = ArrayList<Any>()
                        activeTransientHighlightMarks().forEach { mark ->
                            val resolved = createResolvedSearchHighlightMark(mark) ?: return@forEach
                            val id = transientHighlightResolvedMarkId(resolved) ?: return@forEach
                            if (current.any { transientHighlightResolvedMarkId(it) == id } ||
                                additions.any { transientHighlightResolvedMarkId(it) == id }
                            ) {
                                return@forEach
                            }
                            additions += resolved
                            logSearchHighlightContentOverlay("input", current.size + additions.size, mark)
                        }
                        if (additions.isEmpty()) return
                        args[1] = ArrayList<Any>(current.size + 1).apply {
                            addAll(current)
                            addAll(additions)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val current = (param.result as? List<*>)?.filterNotNull().orEmpty()
                        val args = param.args ?: return
                        val additions = ArrayList<Any>()
                        activeTransientHighlightMarks().forEach { mark ->
                            val id = transientHighlightMarkId(mark) ?: return@forEach
                            if (current.any { searchResultHighlightOverlayMarkId(it) == id } ||
                                additions.any { searchResultHighlightOverlayMarkId(it) == id }
                            ) {
                                logSearchHighlightContentOverlay("output", current.size, mark)
                                return@forEach
                            }
                            val forced = createSearchHighlightContentOverlay(
                                contentDom = args.getOrNull(0),
                                visibleWindow = args.getOrNull(2),
                                renderedTextLength = (args.getOrNull(3) as? Number)?.toInt() ?: return,
                                mark = mark,
                            ) ?: return@forEach
                            additions += forced
                            logSearchHighlightContentOverlay("forced", current.size + additions.size, mark)
                        }
                        if (additions.isEmpty()) return
                        param.result = ArrayList<Any>(current.size + 1).apply {
                            addAll(current)
                            addAll(additions)
                        }
                    }
                })
            }
            XposedBridge.log(
                "$LOG_PREFIX full-text search highlight ContentOverlay hook installed " +
                    "count=${methods.size}, candidates=${candidates.size}",
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX full-text search highlight ContentOverlay hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun isSearchHighlightContentOverlayMethod(method: Method): Boolean {
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

    private fun openNativeSelectionEditor() {
        val activity = activityProvider() ?: return
        if (!canEditReaderSelection()) return
        val selection = currentNativeSelectionPayload()
        val controller = selection.controller
        val quote = selection.quote
        if (quote.isBlank()) {
            Toast.makeText(activity, "\u672a\u83b7\u53d6\u5230\u9009\u4e2d\u6587\u672c", Toast.LENGTH_SHORT).show()
            return
        }
        activity.runOnUiThread {
            showSelectionEditDialog(activity, quote) { edited ->
                if (edited == quote) {
                    Toast.makeText(activity, "\u6587\u672c\u672a\u4fee\u6539", Toast.LENGTH_SHORT).show()
                    return@showSelectionEditDialog
                }
                val result = runCatching { writeSelectionTextBack(quote, edited) }
                    .onFailure { XposedBridge.log("$LOG_PREFIX selection edit save failed: ${it.stackTraceToString()}") }
                    .getOrDefault(false)
                if (result) {
                    callNoArg(controller, "clearSelection")
                    Toast.makeText(activity, "\u5df2\u4fdd\u5b58\u5230 EPUB", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "\u672a\u5728\u5f53\u524d EPUB \u6587\u4ef6\u4e2d\u627e\u5230\u552f\u4e00\u5339\u914d\u6587\u672c", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openNativeSelectionDictionary() {
        val activity = activityProvider() ?: return
        if (!canShowReaderDictionary()) return
        val quote = currentNativeSelectionPayload().quote
        if (quote.isBlank()) {
            Toast.makeText(activity, "\u672a\u83b7\u53d6\u5230\u9009\u4e2d\u6587\u672c", Toast.LENGTH_SHORT).show()
            return
        }
        val config = AiApiStore.dictionaryApi(activity.applicationContext)
        if (config == null) {
            Toast.makeText(activity, "\u8bf7\u5148\u5728 API \u914d\u7f6e\u4e2d\u6dfb\u52a0\u6216\u9009\u62e9 API", Toast.LENGTH_SHORT).show()
            return
        }
        val settings = AiApiStore.dictionarySettings(activity.applicationContext)
        val initialPreset = AiApiStore.dictionaryPreset(activity.applicationContext, settings.presetId)
        activity.runOnUiThread {
            val handle = showDictionaryDialog(activity, quote, config, initialPreset) { dialogHandle, selectedPreset ->
                val latestSettings = AiApiStore.dictionarySettings(activity.applicationContext)
                if (!latestSettings.singleUsePreset) {
                    AiApiStore.setDictionaryPresetId(activity.applicationContext, selectedPreset.id)
                }
                requestDictionaryPreset(activity, config, quote, selectedPreset, dialogHandle)
            }
            requestDictionaryPreset(activity, config, quote, initialPreset, handle)
        }
    }

    private fun highlightNativeSelection() {
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

    private fun openNativeSelectionReadAloud() {
        val activity = activityProvider() ?: return
        if (!canUseReadAloudSelection()) return
        val selection = currentNativeSelectionPayload()
        val quote = selection.quote
        if (quote.isBlank()) {
            Toast.makeText(activity, "\u672a\u83b7\u53d6\u5230\u9009\u4e2d\u6587\u672c", Toast.LENGTH_SHORT).show()
            return
        }
        val context = bottomReadAloudContext(bottomReadAloudReceiverRef?.get(), bottomReadAloudBookRef?.get())
            ?: lastCatalogContext
        if (context == null) {
            Toast.makeText(activity, "\u6682\u65e0\u6cd5\u542c\u5f53\u524d\u4e66\u7c4d", Toast.LENGTH_SHORT).show()
            return
        }
        ensureReadAloudHighlightReceiver()
        callNoArg(selection.controller, "clearSelection")
        Toast.makeText(activity, "\u6b63\u5728\u51c6\u5907\u968f\u542c", Toast.LENGTH_SHORT).show()
        Thread {
            runCatching {
                val segments = buildReadAloudSegmentsFromSelection(
                    context = context,
                    selection = selection,
                    maxSegments = READ_ALOUD_INITIAL_SEGMENTS,
                    currentFileOnly = true,
                )
                if (segments.isEmpty()) error("empty read-aloud selection queue")
                val source = selectedTtsSource(activity)
                val sessionId = startReadAloudService(
                    activity = activity,
                    bookKey = bookKey(context),
                    bookTitle = bookTitle(context).ifBlank { titleFromCurrentEpubRoot() },
                    coverUri = currentBookCoverUri(context),
                    segments = segments,
                    source = source,
                )
                appendReadAloudSelectionRemainderAsync(activity, context, selection, sessionId, segments.size)
                activity.runOnUiThread {
                    Toast.makeText(activity, "\u5df2\u4ece\u9009\u4e2d\u6bb5\u843d\u5f00\u59cb\u542c\u4e66", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX read aloud selection failed: ${it.stackTraceToString()}")
                activity.runOnUiThread {
                    Toast.makeText(activity, "\u968f\u542c\u542f\u52a8\u5931\u8d25", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply {
            name = "ReaMicroReadAloudSelection"
            isDaemon = true
            start()
        }
    }

    private fun requestDictionaryPreset(
        activity: Activity,
        config: AiApiConfig,
        quote: String,
        preset: AiDictionaryPreset,
        handle: DictionaryDialogHandle,
    ) {
        val requestId = ++handle.requestId
        handle.currentPresetId = preset.id
        handle.footerLabel.text = preset.name
        handle.body.text = "\u6b63\u5728\u89e3\u6790..."
        handle.body.setTextColor(handle.colors.primaryText)
        Thread({
            val result = runCatching { AiApiStore.dictionary(activity.applicationContext, config, quote, preset) }
                .onFailure { XposedBridge.log("$LOG_PREFIX dictionary request failed: ${it.stackTraceToString()}") }
                .getOrElse { error -> AiApiTestResult(false, error.message ?: error.javaClass.simpleName) }
            activity.runOnUiThread {
                if (!activity.isFinishing &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed) &&
                    requestId == handle.requestId
                ) {
                    updateDictionaryDialog(handle, result.success, result.message)
                }
            }
        }, "ReaMicroDictionary").start()
    }

    private fun currentNativeSelectionPayload(): NativeSelectionPayload {
        val controller = currentSelectionControllerRef?.get()
        val payload = callNoArg(controller, "selectedPayload")
        val quote = callString(payload, "getQuote").ifBlank { callString(controller, "selectedText") }
        return NativeSelectionPayload(
            controller = controller,
            quote = quote.trim(),
            startCfi = callString(payload, "getStartCfi"),
            endCfi = callString(payload, "getEndCfi"),
        )
    }

    private fun renderSearchResults(
        activity: Activity,
        container: LinearLayout,
        statusLine: TextView,
        keyword: String,
        results: List<FullTextSearchResult>,
        colors: DialogColors,
        searching: Boolean = false,
        currentResultIndex: Int? = null,
    ) {
        val stateTag = searchRenderState
        val canAppend = stateTag != null &&
            stateTag.keyword == keyword &&
            stateTag.currentIndex == currentResultIndex &&
            results.size >= stateTag.renderedCount &&
            stateTag.renderedCount > 0 &&
            container.childCount > 0

        val statusText = if (searching) {
            "\u641c\u7d22\u4e2d\uff0c\u5df2\u627e\u5230 ${results.size} \u5904"
        } else {
            "\u641c\u7d22\u5b8c\u6210\uff0c\u5171\u627e\u5230 ${results.size} \u5904"
        }
        statusLine.text = statusText
        statusLine.visibility = View.VISIBLE

        if (canAppend) {
            var previousVolumeKey = stateTag!!.lastVolumeKey
            var previousGroupKey = stateTag.lastGroupKey
            for (index in stateTag.renderedCount until results.size) {
                val result = results[index]
                val volumeKey = searchResultVolumeKey(result)
                if (volumeKey != previousVolumeKey && volumeKey.isNotBlank()) {
                    container.addView(searchVolumeHeaderView(activity, result, colors).apply {
                        tag = searchVolumeHeaderTag(volumeKey)
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ))
                    previousVolumeKey = volumeKey
                }
                val groupKey = searchResultGroupKey(result)
                if (groupKey != previousGroupKey) {
                    container.addView(searchGroupHeaderView(activity, result, colors, first = index == 0).apply {
                        tag = searchGroupHeaderTag(groupKey)
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ))
                    previousGroupKey = groupKey
                }
                container.addView(searchResultCard(activity, result, index, colors, index == currentResultIndex).apply {
                    tag = searchResultViewTag(index)
                }, resultCardLayoutParams(activity))
            }
            searchRenderState = SearchRenderState(keyword, currentResultIndex, results.size, previousVolumeKey, previousGroupKey)
            return
        }

        container.removeAllViews()
        if (results.isEmpty()) {
            searchRenderState = SearchRenderState(keyword, currentResultIndex, 0, null, null)
            return
        }
        var previousVolumeKey: String? = null
        var previousGroupKey: String? = null
        results.forEachIndexed { index, result ->
            val volumeKey = searchResultVolumeKey(result)
            if (volumeKey != previousVolumeKey && volumeKey.isNotBlank()) {
                container.addView(searchVolumeHeaderView(activity, result, colors).apply {
                    tag = searchVolumeHeaderTag(volumeKey)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                previousVolumeKey = volumeKey
            }
            val groupKey = searchResultGroupKey(result)
            if (groupKey != previousGroupKey) {
                container.addView(searchGroupHeaderView(activity, result, colors, first = index == 0).apply {
                    tag = searchGroupHeaderTag(groupKey)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                previousGroupKey = groupKey
            }
            container.addView(searchResultCard(activity, result, index, colors, index == currentResultIndex).apply {
                tag = searchResultViewTag(index)
            }, resultCardLayoutParams(activity))
        }
        searchRenderState = SearchRenderState(keyword, currentResultIndex, results.size, previousVolumeKey, previousGroupKey)
    }

    private fun resultCardLayoutParams(activity: Activity): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dp(activity, 14)
            rightMargin = dp(activity, 14)
            bottomMargin = dp(activity, 2)
        }

    private data class SearchRenderState(
        val keyword: String,
        val currentIndex: Int?,
        val renderedCount: Int,
        val lastVolumeKey: String?,
        val lastGroupKey: String?,
    )

    private fun searchVolumeHeaderView(
        activity: Activity,
        result: FullTextSearchResult,
        colors: DialogColors,
    ): TextView = TextView(activity).apply {
        text = searchResultVolumeTitle(result)
        textSize = 13f
        setTextColor(colors.primaryText)
        setBackgroundColor(colors.pageBackground)
        setPadding(dp(activity, 20), dp(activity, 10), dp(activity, 20), dp(activity, 2))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun searchGroupHeaderView(
        activity: Activity,
        result: FullTextSearchResult,
        colors: DialogColors,
        first: Boolean,
    ): TextView = TextView(activity).apply {
        text = searchResultChapterTitle(result).ifBlank { result.file.nameWithoutExtension }
        textSize = 13f
        setTextColor(colors.primaryText)
        setBackgroundColor(colors.pageBackground)
        setPadding(
            dp(activity, 20),
            if (first) dp(activity, 10) else dp(activity, 10),
            dp(activity, 20),
            dp(activity, 6),
        )
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun scrollSearchResultToCenter(scrollView: ScrollView, container: LinearLayout, index: Int?) {
        if (index == null) return
        val action = Runnable {
            val target = findSearchResultView(container, index) ?: return@Runnable
            val viewport = scrollView.height.takeIf { it > 0 } ?: return@Runnable
            val targetCenter = target.top + target.height / 2
            val maxScroll = (container.height - viewport).coerceAtLeast(0)
            val desired = (targetCenter - viewport / 2).coerceIn(0, maxScroll)
            scrollView.scrollTo(0, desired)
        }
        scrollView.post(action)
        scrollView.postDelayed(action, 120L)
    }

    private fun findSearchResultView(container: LinearLayout, index: Int): View? {
        val tag = searchResultViewTag(index)
        for (childIndex in 0 until container.childCount) {
            val child = container.getChildAt(childIndex)
            if (child?.tag == tag) return child
        }
        return null
    }

    private fun searchResultViewTag(index: Int): String = "searchResult:$index"

    private fun searchVolumeHeaderTag(volumeKey: String): String = "searchVolume:$volumeKey"

    private fun searchGroupHeaderTag(groupKey: String): String = "searchGroup:$groupKey"

    private fun searchResultVolumeKey(result: FullTextSearchResult): String =
        searchResultVolumeTitle(result).ifBlank { "" }

    private fun searchResultVolumeTitle(result: FullTextSearchResult): String {
        val parts = result.chapterTitle.split(' ').filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        if (!isVolumeCatalogTitle(parts.first())) return ""
        val chapterIndex = parts.indexOfFirstIndexed { index, part ->
            index > 0 && isChapterCatalogTitle(part)
        }
        return parts.take(if (chapterIndex > 0) chapterIndex else 1).joinToString(" ")
    }

    private fun List<String>.indexOfFirstIndexed(predicate: (Int, String) -> Boolean): Int {
        forEachIndexed { index, value ->
            if (predicate(index, value)) return index
        }
        return -1
    }

    private fun searchResultChapterTitle(result: FullTextSearchResult): String {
        val volume = searchResultVolumeTitle(result)
        val title = result.chapterTitle.trim()
        return if (volume.isNotBlank() && title.startsWith(volume)) {
            title.removePrefix(volume).trim().ifBlank { title }
        } else {
            title
        }
    }

    private fun searchResultGroupKey(result: FullTextSearchResult): String =
        if (result.chapterIndex >= 0) {
            "chapter:${result.chapterIndex}"
        } else {
            "file:${result.file.absolutePath}"
        }

    private fun searchResultCard(
        activity: Activity,
        result: FullTextSearchResult,
        resultIndex: Int,
        colors: DialogColors,
        current: Boolean,
    ): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 12).toFloat()
                if (current) {
                    setColor(
                        Color.argb(
                            40,
                            Color.red(colors.actionBackground),
                            Color.green(colors.actionBackground),
                            Color.blue(colors.actionBackground),
                        ),
                    )
                    setStroke(dp(activity, 1), colors.actionBackground)
                } else {
                    setColor(colors.searchChipBackground)
                }
            }
            addView(TextView(activity).apply {
                text = redHighlightedSnippet(result)
                textSize = 14f
                setTextColor(colors.primaryText)
                setLineSpacing(dp(activity, 3).toFloat(), 1f)
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener {
                jumpToSearchResult(result, resultIndex)
                closeSearchPage()
            }
        }

    private fun redHighlightedSnippet(result: FullTextSearchResult): SpannableString =
        SpannableString(result.snippet).apply {
            val start = result.snippetMatchStart.coerceIn(0, result.snippet.length)
            val end = result.snippetMatchEnd.coerceIn(start, result.snippet.length)
            if (end > start) {
                setSpan(ForegroundColorSpan(Color.RED), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

    private fun jumpToSearchResult(result: FullTextSearchResult, resultIndex: Int) {
        val activity = activityProvider()
        val viewModel = currentViewModelRef?.get()
        val receiver = result.intentReceiver
        if (receiver == null && viewModel == null) {
            activity?.let {
                Toast.makeText(it, "\u6682\u65e0\u6cd5\u8df3\u8f6c\u5230\u8be5\u7ed3\u679c", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val returnTarget = activeSearchNavigation?.returnTarget ?: currentReadingTarget()
        var jumped = false
        if (!result.cfi.isNullOrBlank()) {
            XposedBridge.log(
                "$LOG_PREFIX full-text search jump result index=$resultIndex chapter=${result.chapterTitle} " +
                    "file=${result.file.name} jumpCfi=${result.cfi} startCfi=${result.startCfi.orEmpty()} " +
                    "endCfi=${result.endCfi.orEmpty()} snippet=${result.snippet.take(80)}",
            )
            if (!isValidEpubCfi(result.cfi)) {
                XposedBridge.log("$LOG_PREFIX full-text search invalid cfi: ${result.cfi}")
                activity?.let {
                    Toast.makeText(it, "\u641c\u7d22\u7ed3\u679c\u5b9a\u4f4d\u5931\u8d25\uff1aCFI \u65e0\u6548", Toast.LENGTH_SHORT).show()
                }
                return
            }
            jumped = runCatching {
                val highlightMark = createSearchResultHighlightMark(result, resultIndex)
                if (highlightMark != null) {
                    applySearchResultHighlight(viewModel, highlightMark)
                } else {
                    clearSearchResultHighlight(viewModel)
                }
                jumpToCfi(
                    receiver = receiver,
                    viewModel = viewModel,
                    cfi = result.cfi,
                    chapterIndex = result.chapterIndex.coerceAtLeast(0),
                    title = result.chapterTitle,
                    summary = result.snippet,
                ).also { jumped ->
                    if (jumped && highlightMark != null) {
                        scheduleSearchJumpVisibilityCorrection(receiver, viewModel, highlightMark)
                    }
                }
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX full-text search cfi jump failed: ${it.stackTraceToString()}")
            }.getOrDefault(false)
        }
        if (!jumped) {
            activity?.let {
                Toast.makeText(it, "\u65e0\u6cd5\u7cbe\u786e\u5b9a\u4f4d\u5230\u8be5\u7ed3\u679c", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (jumped && activity != null && returnTarget != null) {
            val navigation = SearchNavigationState(
                bookKey = lastSearchState?.bookKey ?: lastCatalogContext?.let(::bookKey).orEmpty(),
                returnTarget = returnTarget,
                currentIndex = resultIndex,
            )
            activeSearchNavigation = navigation
            persistSearchOrigin(navigation)
            activity.runOnUiThread { ensureSearchNavigationBar(activity) }
        }
    }

    private fun jumpToCfi(
        receiver: Any?,
        viewModel: Any?,
        cfi: String,
        mark: Any? = null,
        chapterIndex: Int,
        title: String,
        summary: String,
    ): Boolean {
        val markJumped = runCatching {
            val markClass = classLoader.loadClass(MARK_CLASS)
            val targetMark = mark ?: createReaderMark(
                id = -System.currentTimeMillis(),
                chapter = title,
                startCfi = cfi,
                endCfi = cfi,
                quote = summary,
                style = MARK_STYLE_LINE,
                color = MARK_COLOR_RED,
            ) ?: return@runCatching false
            val intentClass = classLoader.loadClass("$READER_UI_INTENT_CLASS\$MarkJump")
            val intent = intentClass.getDeclaredConstructor(markClass).newInstance(targetMark)
            dispatchReaderIntent(receiver, viewModel, intent, "MarkJump")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX full-text search mark jump failed: ${it.stackTraceToString()}")
        }.getOrDefault(false)
        if (markJumped) {
            XposedBridge.log("$LOG_PREFIX full-text search mark jump dispatched cfi=$cfi")
            return true
        }

        val bookmarkClass = classLoader.loadClass(BOOKMARK_CLASS)
        val bookmark = bookmarkClass
            .getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            .newInstance(
                chapterIndex,
                title,
                summary,
                cfi,
                "",
                0,
            )
        val intentClass = classLoader.loadClass("$READER_UI_INTENT_CLASS\$BookmarkJump")
        val intent = intentClass.getDeclaredConstructor(bookmarkClass).newInstance(bookmark)
        return dispatchReaderIntent(receiver, viewModel, intent, "BookmarkJump").also {
            XposedBridge.log("$LOG_PREFIX full-text search bookmark jump dispatched=$it cfi=$cfi")
        }
    }

    private fun isValidEpubCfi(cfi: String): Boolean {
        val looksValid = Regex("""^epubcfi\(/\d+/\d+/4(?:/\d+)*(?::\d+)?\)$""").matches(cfi)
        return runCatching {
            val epubCfiClass = classLoader.loadClass("org.epub.html.EpubCFI")
            val companion = runCatching { epubCfiClass.getField("INSTANCE").get(null) }.getOrNull()
                ?: runCatching { epubCfiClass.getField("Companion").get(null) }.getOrNull()
                ?: return@runCatching looksValid
            val create = companion.javaClass.methods.firstOrNull {
                it.name == "create" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
            } ?: return@runCatching looksValid
            create.invoke(companion, cfi) != null
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX full-text search cfi validation failed: ${it.stackTraceToString()}")
        }.getOrDefault(looksValid)
    }

    private fun currentReadingTarget(): ReadingTarget? {
        val page = currentPageRef?.get() ?: return null
        val cfi = callString(page, "getAnchor")
            .ifBlank { callNoArg(page, "getStart")?.toString().orEmpty() }
            .takeIf { it.isNotBlank() }
            ?: return null
        return ReadingTarget(
            cfi = cfi,
            chapterIndex = (callNoArg(page, "getChapterIndex") as? Int) ?: 0,
            title = callString(callNoArg(page, "getChapter"), "getTitle").ifBlank { "\u539f\u6765\u8fdb\u5ea6" },
            summary = callString(page, "getSummary"),
        )
    }

    private fun returnToSearchOrigin(
        clearNavigation: Boolean = true,
        removeBar: Boolean = true,
    ) {
        val navigation = activeSearchNavigation ?: return
        if (!isSearchNavigationCurrent(navigation)) {
            clearStaleSearchNavigation()
            return
        }
        val target = navigation.returnTarget
        val jumped = runCatching {
            jumpToCfi(
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
        if (clearNavigation) activeSearchNavigation = null
        if (jumped || clearNavigation) clearPersistedSearchOrigin()
        clearSearchResultHighlight()
        activityProvider()?.let { activity ->
            activity.runOnUiThread {
                if (!jumped) Toast.makeText(activity, "\u8fd4\u56de\u8fdb\u5ea6\u5931\u8d25", Toast.LENGTH_SHORT).show()
                if (removeBar) removeSearchNavigationBar()
            }
        }
    }

    private fun jumpRelativeSearchResult(step: Int) {
        val state = lastSearchState ?: return
        val navigation = activeSearchNavigation ?: return
        if (navigation.bookKey != state.bookKey) {
            clearStaleSearchNavigation()
            return
        }
        if (state.results.isEmpty()) return
        val nextIndex = (navigation.currentIndex + step).coerceIn(0, state.results.lastIndex)
        if (nextIndex == navigation.currentIndex) return
        val result = state.results[nextIndex]
        val cfi = result.cfi
        if (cfi.isNullOrBlank()) {
            activityProvider()?.let { activity ->
                activity.runOnUiThread {
                    Toast.makeText(activity, "\u65e0\u6cd5\u7cbe\u786e\u5b9a\u4f4d\u5230\u8be5\u7ed3\u679c", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        val receiver = result.intentReceiver ?: lastCatalogContext?.intentReceiver
        val viewModel = currentViewModelRef?.get()
        val jumped = runCatching {
            val highlightMark = createSearchResultHighlightMark(result, nextIndex)
            if (highlightMark != null) {
                applySearchResultHighlight(viewModel, highlightMark)
            } else {
                clearSearchResultHighlight(viewModel)
            }
            jumpToCfi(
                receiver = receiver,
                viewModel = viewModel,
                cfi = cfi,
                chapterIndex = result.chapterIndex.coerceAtLeast(0),
                title = result.chapterTitle,
                summary = result.snippet,
            ).also { jumped ->
                if (jumped && highlightMark != null) {
                    scheduleSearchJumpVisibilityCorrection(receiver, viewModel, highlightMark)
                }
            }
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX full-text search relative cfi jump failed: ${it.stackTraceToString()}")
        }.getOrDefault(false)
        if (jumped) {
            activeSearchNavigation = navigation.copy(currentIndex = nextIndex)
            activityProvider()?.let { activity ->
                activity.runOnUiThread {
                    ensureSearchNavigationBar(activity)
                }
            }
        } else {
            activityProvider()?.let { activity ->
                activity.runOnUiThread {
                    Toast.makeText(activity, "\u65e0\u6cd5\u7cbe\u786e\u5b9a\u4f4d\u5230\u8be5\u7ed3\u679c", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun ensureSearchNavigationBar(activity: Activity) {
        if (readerBottomMenuVisible) {
            searchNavigationBarRef?.get()?.visibility = View.GONE
            return
        }
        val decor = activity.window?.decorView as? ViewGroup ?: return
        ensureSearchOverlayThemeCallbacks(activity)
        val existing = searchNavigationBarRef?.get()
        if (existing != null && searchNavigationBarActivityRef?.get() === activity && existing.parent === decor) {
            applySearchNavigationBarTheme(existing, DialogColors(activity))
            existing.visibility = View.VISIBLE
            updateSearchNavigationBar(existing)
            return
        }
        searchNavigationBarRef = null
        searchNavigationBarActivityRef = null
        decor.post {
            if (readerBottomMenuVisible || activeSearchNavigation == null || lastSearchState == null) return@post
            removeTaggedViews(decor, SEARCH_NAV_BAR_TAG)
            removeTaggedViews(activity.findViewById(android.R.id.content), SEARCH_NAV_BAR_TAG)
            val colors = DialogColors(activity)
            val bar = LinearLayout(activity).apply {
                tag = SEARCH_NAV_BAR_TAG
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 6), dp(activity, 4))
                addView(searchNavigationButton(activity, "\u4e0a\u4e00\u5904", colors).apply {
                    tag = "prev"
                    setOnClickListener { jumpRelativeSearchResult(-1) }
                })
                addView(searchNavigationButton(activity, "\u8fd4\u56de\u8fdb\u5ea6", colors).apply {
                    tag = "return"
                    setOnClickListener { returnToSearchOrigin() }
                    setOnLongClickListener {
                        activeSearchNavigation = null
                        clearPersistedSearchOrigin()
                        clearSearchResultHighlight()
                        removeSearchNavigationBar()
                        true
                    }
                })
                addView(searchNavigationButton(activity, "\u4e0b\u4e00\u5904", colors).apply {
                    tag = "next"
                    setOnClickListener { jumpRelativeSearchResult(1) }
                })
                applySearchNavigationBarTheme(this, colors)
            }
            decor.addView(bar, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = searchNavigationBottomMargin(activity)
            })
            searchNavigationBarRef = WeakReference(bar)
            searchNavigationBarActivityRef = WeakReference(activity)
            updateSearchNavigationBar(bar)
        }
    }

    private fun updateSearchNavigationBarPosition() {
        val bar = searchNavigationBarRef?.get() ?: return
        if (readerBottomMenuVisible) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        val context = searchNavigationBarActivityRef?.get() ?: bar.context
        val params = bar.layoutParams as? FrameLayout.LayoutParams ?: return
        val desiredMargin = searchNavigationBottomMargin(context)
        if (params.bottomMargin != desiredMargin) {
            params.bottomMargin = desiredMargin
            bar.layoutParams = params
        }
        bar.bringToFront()
    }

    private fun searchNavigationBottomMargin(context: Context): Int =
        dp(
            context,
            if (readerBottomMenuVisible) {
                SEARCH_NAVIGATION_MENU_BOTTOM_MARGIN_DP
            } else {
                SEARCH_NAVIGATION_READER_BOTTOM_MARGIN_DP
            },
        )

    private fun updateSearchNavigationBar(bar: View) {
        val state = lastSearchState
        val navigation = activeSearchNavigation
        val navigationCurrent = state != null && navigation != null && navigation.bookKey == state.bookKey
        val canPrev = navigationCurrent && navigation != null && navigation.currentIndex > 0
        val canNext = navigationCurrent && navigation != null && state != null && navigation.currentIndex < state.results.lastIndex
        fun update(tag: String, enabled: Boolean) {
            val child = (bar as? ViewGroup)?.let { group ->
                (0 until group.childCount).map { group.getChildAt(it) }.firstOrNull { it.tag == tag }
            } ?: return
            child.isEnabled = enabled
            child.alpha = if (enabled) 1f else 0.42f
        }
        update("prev", canPrev)
        update("next", canNext)
    }

    private fun isSearchNavigationCurrent(navigation: SearchNavigationState): Boolean =
        lastSearchState?.bookKey == navigation.bookKey

    private fun clearStaleSearchNavigation() {
        activeSearchNavigation = null
        clearPersistedSearchOrigin()
        clearSearchResultHighlight()
        activityProvider()?.runOnUiThread { removeSearchNavigationBar() }
    }

    private fun scheduleRestorePersistedSearchOrigin(reason: String) {
        if (activeSearchNavigation != null || pendingSearchOriginRestore) return
        val activity = activityProvider() ?: return
        val persisted = readPersistedSearchOrigin() ?: return
        if (!isPersistedSearchOriginForCurrentBook(persisted)) return
        pendingSearchOriginRestore = true
        activity.window?.decorView?.postDelayed({
            restorePersistedSearchOrigin(reason)
        }, SEARCH_ORIGIN_RESTORE_DELAY_MS)
    }

    private fun restorePersistedSearchOrigin(reason: String) {
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
            jumpToCfi(
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

    private fun isPersistedSearchOriginForCurrentBook(persisted: PersistedSearchOrigin): Boolean {
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

    private fun persistSearchOrigin(navigation: SearchNavigationState) {
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

    private fun readPersistedSearchOrigin(): PersistedSearchOrigin? {
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

    private fun clearPersistedSearchOrigin() {
        val activity = activityProvider() ?: return
        activity.applicationContext
            .getSharedPreferences(SEARCH_ORIGIN_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun createSearchResultHighlightMark(result: FullTextSearchResult, resultIndex: Int): Any? {
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
            color = MARK_COLOR_YELLOW,
        )
    }

    private fun createReaderMark(
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

    private fun refreshReaderAfterSelectionHighlight() {
        ReaderHighlightBookContext.bumpVersion("selection-highlight")
    }

    private fun refreshReaderHighlightWindow(source: String) {
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

    private fun forceRefreshReaderWindow(source: String): Boolean {
        val viewModel = currentViewModelRef?.get() ?: return false
        return runCatching {
            val windowKey = XposedHelpers.getObjectField(viewModel, "windowKey")
            XposedHelpers.callMethod(windowKey, "setValue", "reamicro_${source}_${System.currentTimeMillis()}")
            XposedBridge.log("$LOG_PREFIX reader window refresh requested: $source")
            true
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX reader window refresh failed: ${it.stackTraceToString()}")
        }.getOrDefault(false)
    }

    private fun clearSelectionInjectedHighlight(viewModel: Any? = currentViewModelRef?.get()): Boolean {
        return updateSearchMarks(viewModel, "selection-clear") { marks ->
            val filtered = marks.filterNot(::isSelectionInjectedHighlightMark)
            if (filtered.size == marks.size) marks else filtered
        }
    }

    private fun applySearchResultHighlight(viewModel: Any?, mark: Any) {
        val id = searchResultHighlightMarkId(mark) ?: return
        activeSearchHighlightId = id
        activeSearchHighlightMark = mark
        activeSearchHighlightVisibleId = null
        activeSearchHighlightPageSignature = null
        activeSearchHighlightPageNumber = null
        activeSearchHighlightRenderLogId = null
        activeSearchHighlightRenderLogCount = 0
        XposedBridge.log("$LOG_PREFIX full-text search highlight active ${describeSearchHighlightMark(mark)}")
        injectSearchResultHighlight(viewModel, mark)
        scheduleSearchResultHighlightRefresh(viewModel, mark, id, 350L)
        scheduleSearchResultHighlightRefresh(viewModel, mark, id, 900L)
    }

    private fun scheduleSearchResultHighlightRefresh(viewModel: Any?, mark: Any, id: Long, delayMs: Long) {
        val view = activityProvider()?.window?.decorView
        val block = {
            if (activeSearchHighlightId == id) {
                injectSearchResultHighlight(viewModel ?: currentViewModelRef?.get(), mark)
            }
        }
        if (view != null) {
            view.postDelayed(block, delayMs)
        } else {
            Thread {
                runCatching {
                    Thread.sleep(delayMs)
                    block()
                }
            }.apply {
                name = "ReaMicroSearchHighlight"
                isDaemon = true
                start()
            }
        }
    }

    private fun injectSearchResultHighlight(viewModel: Any?, mark: Any): Boolean =
        updateSearchMarks(viewModel, "inject") { marks ->
            marks.filterNot(::isSearchResultHighlightMark) + mark
        }

    private fun injectReadAloudHighlight(mark: Any, viewModel: Any? = currentViewModelRef?.get()): Boolean =
        updateSearchMarks(viewModel, "read-aloud-inject") { marks ->
            marks.filterNot(::isReadAloudHighlightMark) + mark
        }

    private fun clearReadAloudHighlight(viewModel: Any? = currentViewModelRef?.get()): Boolean {
        activeReadAloudHighlightId = null
        activeReadAloudHighlightMark = null
        return updateSearchMarks(viewModel, "read-aloud-clear") { marks ->
            val filtered = marks.filterNot(::isReadAloudHighlightMark)
            if (filtered.size == marks.size) marks else filtered
        }
    }

    private fun clearSearchResultHighlight(viewModel: Any? = currentViewModelRef?.get()): Boolean {
        activeSearchHighlightId = null
        activeSearchHighlightMark = null
        activeSearchHighlightVisibleId = null
        activeSearchHighlightPageSignature = null
        activeSearchHighlightPageNumber = null
        activeSearchHighlightRenderLogId = null
        activeSearchHighlightRenderLogCount = 0
        return updateSearchMarks(viewModel, "clear") { marks ->
            val filtered = marks.filterNot(::isSearchResultHighlightMark)
            if (filtered.size == marks.size) marks else filtered
        }
    }

    private fun appendActiveSearchHighlightMark(original: List<*>, label: String? = null): List<Any>? {
        val activeMarks = activeTransientHighlightMarks()
        val cleanMarks = original
            .filterNotNull()
            .filterNot(::isSearchResultHighlightMark)
            .filterNot(::isReadAloudHighlightMark)
            .filterNot(::isSelectionInjectedHighlightMark)
        if (activeMarks.isEmpty()) {
            return if (cleanMarks.size == original.filterNotNull().size) null else ArrayList(cleanMarks)
        }
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

    private fun appendActiveSearchHighlightCatalogItemMap(original: Map<*, *>): Map<Any?, Any?>? {
        val marks = activeTransientHighlightMarks()
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

    private fun catalogItemMapContainsSearchHighlight(map: Map<*, *>, id: Long): Boolean =
        map.values.any { value ->
            (value as? Iterable<*>)?.any { item -> catalogChapterItemMarkId(item) == id } == true
        }

    private fun catalogChapterItemMarkId(item: Any?): Long? =
        callNoArg(item, "getMark")?.let(::transientHighlightMarkId)

    private fun createSearchHighlightCatalogChapterItem(mark: Any): Any? =
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

    private fun resolveActiveSearchHighlightCatalogMapKey(map: Map<*, *>, mark: Any): Any? {
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

    private fun resolveCatalogIndexForCfi(cfi: String, catalog: List<Any>): Int? =
        runCatching {
            if (cfi.isBlank() || catalog.isEmpty()) return@runCatching null
            val cfiObject = createEpubCfi(cfi) ?: return@runCatching null
            val viewModel = currentViewModelRef?.get() ?: return@runCatching null
            (XposedHelpers.callMethod(viewModel, "resolveCatalogIndexByCfi", cfiObject, catalog) as? Number)?.toInt()
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX resolve search highlight catalog index failed: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun createEpubCfi(cfi: String): Any? =
        runCatching {
            if (cfi.isBlank()) return@runCatching null
            val cfiClass = classLoader.loadClass("org.epub.html.EpubCFI")
            val cfiObject = companionObject(cfiClass) ?: return@runCatching null
            val create = cfiObject.javaClass.methods.firstOrNull {
                it.name == "create" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
            } ?: return@runCatching null
            create.invoke(cfiObject, cfi)
        }.getOrNull()

    private fun logSearchHighlightRenderInput(label: String, before: Int, after: Int, mark: Any) {
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

    private fun logSearchHighlightResolvePage(status: String, count: Int, mark: Any) {
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

    private fun logSearchHighlightContentOverlay(status: String, count: Int, mark: Any) {
        val id = transientHighlightMarkId(mark) ?: transientHighlightResolvedMarkId(mark) ?: return
        if (status == "output" || status == "forced") {
            activeSearchHighlightVisibleId = id
            renderingEpubPage.get()?.let { page ->
                activeSearchHighlightPageSignature = epubPageSignature(page)
                activeSearchHighlightPageNumber = epubPageNumber(page)
            }
        }
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

    private fun scheduleSearchJumpVisibilityCorrection(receiver: Any?, viewModel: Any?, mark: Any) {
        val id = searchResultHighlightMarkId(mark) ?: return
        var corrected = false
        val block = correctionBlock@{
            if (corrected || activeSearchHighlightId != id || isSearchHighlightOnCurrentVisiblePage(id)) {
                return@correctionBlock
            }
            val correction = searchHighlightCorrectionDirection()
            if (correction != null) {
                corrected = true
                XposedBridge.log(
                    "$LOG_PREFIX full-text search single page correction id=$id next=$correction " +
                        "current=${currentVisiblePageNumber ?: -1}/${currentVisiblePageSignature.orEmpty()} " +
                        "target=${activeSearchHighlightPageNumber ?: -1}/${activeSearchHighlightPageSignature.orEmpty()}",
                )
                dispatchTapDirection(receiver, viewModel, next = correction)
                scheduleSearchResultHighlightRefresh(viewModel ?: currentViewModelRef?.get(), mark, id, 250L)
            }
        }
        val view = activityProvider()?.window?.decorView
        if (view != null) {
            view.postDelayed(block, SEARCH_JUMP_SINGLE_CORRECTION_DELAY_MS)
            view.postDelayed(block, SEARCH_JUMP_SINGLE_CORRECTION_FALLBACK_DELAY_MS)
        } else {
            Thread {
                runCatching {
                    Thread.sleep(SEARCH_JUMP_SINGLE_CORRECTION_DELAY_MS)
                    block()
                    Thread.sleep(SEARCH_JUMP_SINGLE_CORRECTION_FALLBACK_DELAY_MS - SEARCH_JUMP_SINGLE_CORRECTION_DELAY_MS)
                    block()
                }
            }.apply {
                name = "ReaMicroSearchJumpCorrection"
                isDaemon = true
                start()
            }
        }
    }

    private fun isSearchHighlightOnCurrentVisiblePage(id: Long): Boolean {
        if (activeSearchHighlightId != id) return false
        val targetSignature = activeSearchHighlightPageSignature
        val currentSignature = currentVisiblePageSignature
        if (!targetSignature.isNullOrBlank() && !currentSignature.isNullOrBlank()) {
            return targetSignature == currentSignature
        }
        val targetNumber = activeSearchHighlightPageNumber
        val currentNumber = currentVisiblePageNumber
        if (targetNumber != null && currentNumber != null) {
            return targetNumber == currentNumber
        }
        return activeSearchHighlightVisibleId == id &&
            targetSignature.isNullOrBlank() &&
            currentSignature.isNullOrBlank()
    }

    private fun searchHighlightCorrectionDirection(): Boolean? {
        return SearchHighlightPlanner.correctionDirection(
            targetNumber = activeSearchHighlightPageNumber,
            currentNumber = currentVisiblePageNumber,
            targetKey = targetSearchHighlightPageKey(),
            currentKey = currentVisibleSearchPageKey(),
            activeVisibleMatches = activeSearchHighlightVisibleId == idOrNull(activeSearchHighlightMark),
        )
    }

    private fun idOrNull(mark: Any?): Long? =
        mark?.let(::searchResultHighlightMarkId)

    private fun currentVisibleSearchPageKey(): String? =
        currentVisiblePageSignature?.takeIf { it.isNotBlank() }
            ?: currentVisiblePageNumber?.let { "n=$it" }

    private fun targetSearchHighlightPageKey(): String? =
        activeSearchHighlightPageSignature?.takeIf { it.isNotBlank() }
            ?: activeSearchHighlightPageNumber?.let { "n=$it" }

    private fun dispatchTapDirection(receiver: Any?, viewModel: Any?, next: Boolean): Boolean =
        runCatching {
            val intentClass = classLoader.loadClass("$READER_UI_INTENT_CLASS\$TapDirection")
            val direction = System.currentTimeMillis() * if (next) 1L else -1L
            val intent = intentClass.getDeclaredConstructor(Long::class.javaPrimitiveType).newInstance(direction)
            dispatchReaderIntent(receiver, viewModel, intent, if (next) "TapDirectionNext" else "TapDirectionPrev")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX full-text search tap direction correction failed: ${it.stackTraceToString()}")
        }.getOrDefault(false)

    private fun createResolvedSearchHighlightMark(mark: Any): Any? =
        runCatching {
            val cfiClass = classLoader.loadClass("org.epub.html.EpubCFI")
            val cfiObject = companionObject(cfiClass) ?: return@runCatching null
            val create = cfiObject.javaClass.methods.firstOrNull {
                it.name == "create" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
            } ?: return@runCatching null
            val start = create.invoke(cfiObject, callString(mark, "getStartCfi")) ?: return@runCatching null
            val end = create.invoke(cfiObject, callString(mark, "getEndCfi")) ?: return@runCatching null
            val resolvedClass = classLoader.loadClass("org.epub.ui.ResolvedMark")
            resolvedClass.getDeclaredConstructor(
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                cfiClass,
                cfiClass,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
            ).newInstance(
                (callNoArg(mark, "getId") as? Number)?.toLong() ?: return@runCatching null,
                (callNoArg(mark, "getKind") as? Number)?.toInt() ?: MARK_KIND_HIGHLIGHT,
                start,
                end,
                (callNoArg(mark, "getStyle") as? Number)?.toInt() ?: MARK_STYLE_FILL,
                callString(mark, "getColor"),
                callString(mark, "getNote"),
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX create resolved search highlight failed: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun createSearchHighlightContentOverlay(
        contentDom: Any?,
        visibleWindow: Any?,
        renderedTextLength: Int,
        mark: Any,
    ): Any? =
        runCatching {
            if (renderedTextLength <= 0) return@runCatching null
            val quote = callString(mark, "getQuote").takeIf { it.isNotBlank() } ?: return@runCatching null
            val content = contentDomPlainText(contentDom).takeIf { it.isNotBlank() } ?: return@runCatching null
            val location = callNoArg(contentDom, "getLocation")
            val baseOffset = ((callNoArg(callNoArg(location, "getOffset"), "getOffset") as? Number)?.toInt() ?: 0)
            val visibleStart = ((callNoArg(visibleWindow, "getStart") as? Number)?.toInt() ?: baseOffset)
            val visibleEnd = ((callNoArg(visibleWindow, "getEndExclusive") as? Number)?.toInt()
                ?: (baseOffset + content.length))
            val windowStart = (visibleStart - baseOffset).coerceIn(0, content.length)
            val windowEnd = (visibleEnd - baseOffset).coerceIn(windowStart, content.length)
            val expectedLocalStart = SearchHighlightPlanner.cfiCharacterOffset(callString(mark, "getStartCfi"))
                ?.let { it - baseOffset }
                ?.takeIf { it in 0..content.length }
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

    private fun contentDomPlainText(contentDom: Any?): String =
        runCatching {
            val annotated = callNoArg(contentDom, "getContent") ?: return@runCatching ""
            callNoArg(annotated, "getText")?.toString()
                ?: (annotated as? CharSequence)?.toString()
                ?: annotated.toString()
        }.getOrDefault("")

    private fun textRange(start: Int, end: Int): Long? =
        runCatching {
            val cls = classLoader.loadClass("androidx.compose.ui.text.TextRangeKt")
            val method = cls.methods.firstOrNull {
                it.name == "TextRange" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            } ?: return@runCatching null
            (method.invoke(null, start, end) as? Number)?.toLong()
        }.getOrNull()

    private fun markColorTokenToColor(token: String): Long? =
        runCatching {
            val cls = classLoader.loadClass("org.epub.ui.BodyKt")
            val method = cls.methods.firstOrNull {
                it.name == "markColorTokenToColor" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java
            } ?: return@runCatching null
            (method.invoke(null, token) as? Number)?.toLong()
        }.getOrNull()

    private fun companionObject(cls: Class<*>): Any? {
        for (fieldName in listOf("INSTANCE", "Companion")) {
            runCatching {
                return cls.getDeclaredField(fieldName).apply { isAccessible = true }.get(null)
            }
        }
        return cls.declaredFields.firstNotNullOfOrNull { field ->
            runCatching {
                field.takeIf { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    ?.takeIf { it.type.name.endsWith("\$Companion") || it.type.simpleName == "Companion" }
                    ?.apply { isAccessible = true }
                    ?.get(null)
            }.getOrNull()
        }
    }

    private fun describeSearchHighlightMark(mark: Any): String {
        val id = (callNoArg(mark, "getId") as? Number)?.toLong() ?: 0L
        val style = (callNoArg(mark, "getStyle") as? Number)?.toInt() ?: -1
        val color = callString(mark, "getColor")
        val start = callString(mark, "getStartCfi")
        val end = callString(mark, "getEndCfi")
        val quote = callString(mark, "getQuote").take(24)
        return "id=$id style=$style color=$color start=$start end=$end quote=$quote"
    }

    private fun epubPageNumber(page: Any?): Int? =
        (callNoArg(page, "getNumber") as? Number)?.toInt()
            ?: (callNoArg(page, "getIndex") as? Number)?.toInt()

    private fun epubPageSignature(page: Any?): String? {
        val range = callNoArg(page, "getRange")?.toString().orEmpty()
        val anchor = callString(page, "getAnchor")
            .ifBlank { callNoArg(page, "getStart")?.toString().orEmpty() }
        val number = epubPageNumber(page)
        val signature = listOfNotNull(
            number?.let { "n=$it" },
            range.takeIf { it.isNotBlank() }?.let { "r=$it" },
            anchor.takeIf { it.isNotBlank() }?.let { "a=$it" },
        ).joinToString("|")
        return signature.takeIf { it.isNotBlank() }
    }

    private fun updateSearchMarks(viewModel: Any?, label: String, transform: (List<Any>) -> List<Any>): Boolean =
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

    private fun isSearchResultHighlightMark(mark: Any): Boolean {
        val id = searchResultHighlightMarkId(mark) ?: return false
        return SearchHighlightPlanner.isHighlightId(id, SEARCH_HIGHLIGHT_MARK_ID_BASE, SEARCH_HIGHLIGHT_MARK_ID_RANGE)
    }

    private fun isReadAloudHighlightMark(mark: Any): Boolean {
        val id = readAloudHighlightMarkId(mark) ?: return false
        return id >= READ_ALOUD_HIGHLIGHT_MARK_ID_BASE &&
            id < READ_ALOUD_HIGHLIGHT_MARK_ID_BASE + READ_ALOUD_HIGHLIGHT_MARK_ID_RANGE
    }

    private fun isSelectionInjectedHighlightMark(mark: Any): Boolean {
        val id = (callNoArg(mark, "getId") as? Number)?.toLong() ?: return false
        return id >= SELECTION_HIGHLIGHT_MARK_ID_BASE &&
            id < SELECTION_HIGHLIGHT_MARK_ID_BASE + SELECTION_HIGHLIGHT_MARK_ID_RANGE
    }

    private fun searchResultHighlightMarkId(mark: Any): Long? {
        val id = (callNoArg(mark, "getId") as? Number)?.toLong() ?: return null
        return if (SearchHighlightPlanner.isHighlightId(id, SEARCH_HIGHLIGHT_MARK_ID_BASE, SEARCH_HIGHLIGHT_MARK_ID_RANGE)) {
            id
        } else {
            null
        }
    }

    private fun readAloudHighlightMarkId(mark: Any): Long? {
        val id = (callNoArg(mark, "getId") as? Number)?.toLong() ?: return null
        return if (id >= READ_ALOUD_HIGHLIGHT_MARK_ID_BASE &&
            id < READ_ALOUD_HIGHLIGHT_MARK_ID_BASE + READ_ALOUD_HIGHLIGHT_MARK_ID_RANGE
        ) {
            id
        } else {
            null
        }
    }

    private fun transientHighlightMarkId(mark: Any): Long? =
        searchResultHighlightMarkId(mark) ?: readAloudHighlightMarkId(mark)

    private fun searchResultHighlightResolvedMarkId(mark: Any): Long? {
        val id = (callNoArg(mark, "getId") as? Number)?.toLong() ?: return null
        return if (SearchHighlightPlanner.isHighlightId(id, SEARCH_HIGHLIGHT_MARK_ID_BASE, SEARCH_HIGHLIGHT_MARK_ID_RANGE)) {
            id
        } else {
            null
        }
    }

    private fun transientHighlightResolvedMarkId(mark: Any): Long? {
        val id = (callNoArg(mark, "getId") as? Number)?.toLong() ?: return null
        return when {
            SearchHighlightPlanner.isHighlightId(id, SEARCH_HIGHLIGHT_MARK_ID_BASE, SEARCH_HIGHLIGHT_MARK_ID_RANGE) -> id
            id >= READ_ALOUD_HIGHLIGHT_MARK_ID_BASE &&
                id < READ_ALOUD_HIGHLIGHT_MARK_ID_BASE + READ_ALOUD_HIGHLIGHT_MARK_ID_RANGE -> id
            else -> null
        }
    }

    private fun searchResultHighlightOverlayMarkId(overlay: Any): Long? {
        val mark = callNoArg(overlay, "getMark") ?: return null
        return transientHighlightResolvedMarkId(mark)
    }

    private fun activeTransientHighlightMarks(): List<Any> =
        listOfNotNull(activeSearchHighlightMark, activeReadAloudHighlightMark)

    private fun applySearchNavigationBarTheme(bar: View, colors: DialogColors) {
        val context = bar.context
        bar.background = GradientDrawable().apply {
            setColor(colors.searchChipBackground)
            cornerRadius = dp(context, 20).toFloat()
            setStroke(dp(context, 1), colors.stroke)
        }
        val group = bar as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            (group.getChildAt(index) as? TextView)?.let { applySearchNavigationButtonTheme(it, colors) }
        }
    }

    private fun applySearchNavigationButtonTheme(button: TextView, colors: DialogColors) {
        val context = button.context
        button.setTextColor(colors.primaryText)
        button.background = GradientDrawable().apply {
            setColor(colors.cardBackground)
            cornerRadius = dp(context, 15).toFloat()
            setStroke(dp(context, 1), colors.stroke)
        }
    }

    private fun searchNavigationButton(activity: Activity, label: String, colors: DialogColors): TextView =
        TextView(activity).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = 0
            minHeight = 0
            setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
            applySearchNavigationButtonTheme(this, colors)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(activity, 30),
            ).apply {
                leftMargin = dp(activity, 3)
                rightMargin = dp(activity, 3)
            }
        }

    private fun removeSearchNavigationBar() {
        val activity = searchNavigationBarActivityRef?.get() ?: activityProvider()
        searchNavigationBarRef = null
        searchNavigationBarActivityRef = null
        postRemoveTaggedViews(activity, SEARCH_NAV_BAR_TAG)
        maybeUnregisterSearchOverlayThemeCallbacks()
    }

    private fun removeTaggedViews(root: ViewGroup?, tagValue: Int) {
        if (root == null) return
        for (index in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(index) ?: continue
            if (child.tag == tagValue) {
                root.removeViewAt(index)
            } else {
                removeTaggedViews(child as? ViewGroup, tagValue)
            }
        }
    }

    private fun dispatchReaderIntent(receiver: Any?, viewModel: Any?, intent: Any, label: String): Boolean {
        val targetViewModel = viewModel
        if (targetViewModel != null) {
            val viewModelIntent = targetViewModel.javaClass.methods.firstOrNull {
                it.name == "intent" && it.parameterTypes.size == 1
            }
            if (viewModelIntent != null) {
                viewModelIntent.invoke(targetViewModel, intent)
                XposedBridge.log("$LOG_PREFIX full-text search $label sent to ReaderViewModel")
                return true
            }
        }
        if (receiver != null) {
            val receiverIntent = receiver.javaClass.methods.firstOrNull {
                it.name == "intent" && it.parameterTypes.size == 1
            }
            if (receiverIntent != null) {
                receiverIntent.invoke(receiver, intent)
                XposedBridge.log("$LOG_PREFIX full-text search $label sent to receiver")
                return true
            }
        }
        XposedBridge.log(
            "$LOG_PREFIX full-text search $label dispatch failed: " +
                "viewModel=${targetViewModel?.javaClass?.name.orEmpty()} receiver=${receiver?.javaClass?.name.orEmpty()}",
        )
        return false
    }

    private fun ensureSearchIndexAsync(context: CatalogContext) {
        val key = bookKey(context)
        if (key.isBlank()) return
        if (searchIndexState?.bookKey == key || searchIndexBuildingKey == key) return
        val generation = searchStateGeneration
        searchIndexBuildingKey = key
        Thread {
            val state = runCatching { SearchIndexState(key, buildSearchDocuments(context)) }
                .onFailure { XposedBridge.log("$LOG_PREFIX full-text index build failed: ${it.stackTraceToString()}") }
                .getOrNull()
            if (generation == searchStateGeneration && bookKey(context) == key) {
                if (state != null) searchIndexState = state
            }
            if (generation == searchStateGeneration && searchIndexBuildingKey == key) searchIndexBuildingKey = null
        }.apply {
            name = "ReaMicroFullTextIndex"
            isDaemon = true
            start()
        }
    }

    private fun searchFullTextStreaming(
        keyword: String,
        context: CatalogContext,
        onUpdate: (List<FullTextSearchResult>, Boolean) -> Unit,
    ) {
        val key = bookKey(context)
        val needle = keyword.lowercase(Locale.ROOT)
        val generation = searchStateGeneration
        val results = ArrayList<FullTextSearchResult>()
        var lastEmitSize = 0
        var lastEmitAt = 0L

        fun emit(done: Boolean, force: Boolean = false) {
            if (generation != searchStateGeneration) return
            val now = System.currentTimeMillis()
            if (!force && !done && results.size == lastEmitSize) return
            if (!force && !done && results.size - lastEmitSize < SEARCH_EMIT_BATCH && now - lastEmitAt < SEARCH_EMIT_INTERVAL_MS) {
                return
            }
            lastEmitSize = results.size
            lastEmitAt = now
            onUpdate(ArrayList(results), done)
        }

        val cachedDocuments = searchIndexState?.takeIf { it.bookKey == key }?.documents
        if (cachedDocuments != null) {
            for (document in cachedDocuments) {
                if (generation != searchStateGeneration) return
                if (results.size >= MAX_SEARCH_RESULTS) break
                val previousSize = results.size
                appendSearchMatches(document, needle, keyword, context, results)
                if (results.size != previousSize) emit(done = false)
            }
            emit(done = true, force = true)
            return
        }

        val documents = ArrayList<SearchDocument>()
        forEachSearchDocument(context) { document ->
            if (generation != searchStateGeneration) return@forEachSearchDocument false
            if (results.size < MAX_SEARCH_RESULTS) {
                val previousSize = results.size
                appendSearchMatches(document, needle, keyword, context, results)
                if (results.size != previousSize) emit(done = false)
            }
            documents.add(document)
            results.size < MAX_SEARCH_RESULTS
        }
        if (generation == searchStateGeneration && bookKey(context) == key) {
            searchIndexState = SearchIndexState(key, documents)
        }
        emit(done = true, force = true)
    }

    private fun appendSearchMatches(
        document: SearchDocument,
        needle: String,
        keyword: String,
        context: CatalogContext,
        results: ArrayList<FullTextSearchResult>,
    ) {
        var from = 0
        var countInFile = 0
        while (results.size < MAX_SEARCH_RESULTS && countInFile < MAX_MATCHES_PER_FILE) {
            val index = document.lowerText.indexOf(needle, from)
            if (index < 0) break
            val snippet = snippetFor(document.text, index, index + keyword.length)
            val chapterAnchor = document.chapterAnchors.lastOrNull { it.textStart <= index }
            val resultChapter = chapterAnchor?.chapter ?: document.chapter
            val resultChapterIndex = chapterAnchor?.index ?: document.chapterIndex
            val resultChapterTitle = chapterAnchor?.title ?: document.chapterTitle
            val startCfi = document.indexedText.cfiAt(index)
            val cfi = document.indexedText.cfiAtSearchJump(index, keyword.length) ?: startCfi
            val endCfi = document.indexedText.cfiAtBoundary(index + keyword.length)
            results.add(
                FullTextSearchResult(
                    chapterIndex = resultChapterIndex,
                    chapter = resultChapter,
                    chapterTitle = resultChapterTitle,
                    intentReceiver = context.intentReceiver,
                    startCfi = startCfi,
                    cfi = cfi,
                    endCfi = endCfi,
                    file = document.file,
                    snippet = snippet.text,
                    snippetMatchStart = snippet.matchStart,
                    snippetMatchEnd = snippet.matchEnd,
                    matchText = document.text.substring(index, (index + keyword.length).coerceAtMost(document.text.length)),
                ),
            )
            countInFile++
            from = index + needle.length.coerceAtLeast(1)
        }
    }

    private fun buildSearchDocuments(context: CatalogContext): List<SearchDocument> {
        val documents = ArrayList<SearchDocument>()
        forEachSearchDocument(context) { document ->
            documents.add(document)
            true
        }
        return documents
    }

    private fun buildReadAloudSegments(
        context: CatalogContext,
        maxSegments: Int = MAX_READ_ALOUD_SEGMENTS,
        currentFileOnly: Boolean = false,
    ): List<ReadAloudSegment> {
        val key = bookKey(context)
        val startChapterIndex = currentPageRef?.get()
            ?.let { callNoArg(it, "getChapterIndex") as? Number }
            ?.toInt()
            ?: -1
        val cached = searchIndexState
            ?.takeIf { it.bookKey == key && !currentFileOnly }
            ?.documents
        val documents = cached ?: buildReadAloudDocuments(context, maxSegments, currentFileOnly)
        val filtered = if (currentFileOnly) {
            documents
        } else if (startChapterIndex >= 0) {
            documents.filter { it.chapterIndex < 0 || it.chapterIndex >= startChapterIndex }
        } else {
            documents
        }
        val result = ArrayList<ReadAloudSegment>()
        for ((documentIndex, document) in filtered.withIndex()) {
            val offset = readAloudStartOffsetForDocument(document)
            splitReadAloudTextParts(document.text, offset).forEach { part ->
                if (result.size >= maxSegments) return result
                result += ReadAloudSegment(
                    chapterTitle = readAloudSegmentChapterTitle(
                        document,
                        isCurrentDocument = documentIndex == 0,
                        textOffset = part.startOffset,
                    ),
                    chapterIndex = document.chapterIndex,
                    text = part.text,
                    highlightText = part.highlightText,
                    startCfi = document.indexedText.cfiAt(part.startOffset).orEmpty(),
                    endCfi = document.indexedText.cfiAtBoundary(part.endOffset)
                        ?: document.indexedText.cfiAt(part.startOffset).orEmpty(),
                )
            }
            if (result.size >= maxSegments) return result
        }
        return result
    }

    private fun buildReadAloudDocuments(
        context: CatalogContext,
        maxSegments: Int,
        currentFileOnly: Boolean,
    ): List<SearchDocument> {
        val root = currentEpubRoot()
        val epub = currentEpubRef?.get()
        val itemRefs = (epub?.let { callNoArg(it, "getItemRefs") } as? Iterable<*>)?.filterNotNull().orEmpty()
        if (root == null) return emptyList()
        val files = readAloudDocumentFiles(root, context.catalog, itemRefs, currentFileOnly)
        if (files.isEmpty()) {
            logReadAloudCurrentPageProbe(root, itemRefs, "no-current-file")
            return emptyList()
        }
        val documents = ArrayList<SearchDocument>()
        var segmentCount = 0
        forEachSearchDocument(context, files) { document ->
            documents += document
            val offset = readAloudStartOffsetForDocument(document)
            segmentCount += splitReadAloudTextParts(document.text, offset).size
            !currentFileOnly && segmentCount < maxSegments
        }
        return documents
    }

    private fun readAloudDocumentFiles(
        root: File,
        catalog: List<Any>,
        itemRefs: List<Any>,
        currentFileOnly: Boolean,
    ): List<File> {
        val currentFile = currentTextContentFile(root, itemRefs)
        if (currentFileOnly) return listOfNotNull(currentFile)
        val catalogFiles = catalogTextFiles(root, catalog, itemRefs)
        if (currentFile == null) return catalogFiles
        val currentKey = currentFile.absolutePath
        val currentIndex = catalogFiles.indexOfFirst { file ->
            (file.canonicalFileSafe() ?: file).absolutePath == currentKey
        }
        return if (currentIndex >= 0) {
            catalogFiles.drop(currentIndex)
        } else {
            listOf(currentFile) + catalogFiles
        }
    }

    private fun readAloudStartOffsetForDocument(document: SearchDocument): Int {
        val page = currentPageRef?.get() ?: return 0
        val currentChapterIndex = (callNoArg(page, "getChapterIndex") as? Number)?.toInt() ?: -1
        if (currentChapterIndex >= 0 && document.chapterIndex >= 0 && document.chapterIndex != currentChapterIndex) {
            return 0
        }
        val summary = callString(page, "getSummary").trim()
        if (summary.length >= 8) {
            val offset = selectionOffsetInDocument(document.text, summary)
            if (offset >= 0) return offset
        }
        return 0
    }

    private fun buildReadAloudSegmentsFromSelection(
        context: CatalogContext,
        selection: NativeSelectionPayload,
        maxSegments: Int = MAX_READ_ALOUD_SEGMENTS,
        currentFileOnly: Boolean = false,
    ): List<ReadAloudSegment> {
        val key = bookKey(context)
        val documents = if (currentFileOnly) {
            buildReadAloudDocuments(context, maxSegments, currentFileOnly = true)
        } else {
            searchIndexState
                ?.takeIf { it.bookKey == key }
                ?.documents
                ?: buildReadAloudDocuments(context, maxSegments, currentFileOnly = false)
        }
        if (documents.isEmpty()) {
            return selection.quote.takeIf { it.isNotBlank() }?.let {
                listOf(
                    ReadAloudSegment(
                        fallbackCurrentChapterTitle(),
                        -1,
                        it,
                        it,
                        selection.startCfi,
                        selection.endCfi,
                    ),
                )
            }.orEmpty()
        }
        val startChapterIndex = currentPageRef?.get()
            ?.let { callNoArg(it, "getChapterIndex") as? Number }
            ?.toInt()
            ?: -1
        val candidateStart = documents.indexOfFirst { document ->
            startChapterIndex < 0 || document.chapterIndex < 0 || document.chapterIndex >= startChapterIndex
        }.takeIf { it >= 0 } ?: 0
        val location = locateReadAloudSelection(documents, selection.quote, candidateStart)
        val result = ArrayList<ReadAloudSegment>()
        if (location == null && selection.quote.isNotBlank()) {
            result += ReadAloudSegment(
                chapterTitle = fallbackCurrentChapterTitle(),
                chapterIndex = startChapterIndex,
                text = selection.quote,
                highlightText = selection.quote,
                startCfi = selection.startCfi,
                endCfi = selection.endCfi,
            )
        }
        val startIndex = location?.documentIndex ?: candidateStart
        for (index in startIndex until documents.size) {
            val document = documents[index]
            val startOffset = if (index == location?.documentIndex) {
                location.offset.coerceIn(0, document.text.length)
            } else {
                0
            }
            splitReadAloudTextParts(document.text, startOffset).forEach { part ->
                if (result.size >= maxSegments) return@forEach
                result += ReadAloudSegment(
                    chapterTitle = readAloudSegmentChapterTitle(
                        document,
                        isCurrentDocument = index == startIndex,
                        textOffset = part.startOffset,
                    ),
                    chapterIndex = document.chapterIndex,
                    text = part.text,
                    highlightText = part.highlightText,
                    startCfi = document.indexedText.cfiAt(part.startOffset).orEmpty(),
                    endCfi = document.indexedText.cfiAtBoundary(part.endOffset)
                        ?: document.indexedText.cfiAt(part.startOffset).orEmpty(),
                )
            }
            if (result.size >= maxSegments) break
        }
        return result
    }

    private fun locateReadAloudSelection(
        documents: List<SearchDocument>,
        quote: String,
        startIndex: Int,
    ): ReadAloudSelectionLocation? {
        val cleanQuote = quote.trim()
        if (cleanQuote.isBlank()) return null
        val searchOrder = (startIndex until documents.size).asSequence() + (0 until startIndex).asSequence()
        searchOrder.forEach { index ->
            val document = documents[index]
            val offset = selectionOffsetInDocument(document.text, cleanQuote)
            if (offset >= 0) return ReadAloudSelectionLocation(index, offset)
        }
        return null
    }

    private fun selectionOffsetInDocument(text: String, quote: String): Int {
        val exact = text.indexOf(quote)
        if (exact >= 0) return exact
        val compactQuote = quote.replace(Regex("\\s+"), " ").trim()
        if (compactQuote.length >= 12) {
            val prefix = compactQuote.take(32)
            val prefixOffset = text.indexOf(prefix)
            if (prefixOffset >= 0) return prefixOffset
        }
        val shortPrefix = quote.take(16).trim()
        if (shortPrefix.length >= 6) {
            val shortOffset = text.indexOf(shortPrefix)
            if (shortOffset >= 0) return shortOffset
        }
        return -1
    }

    private fun splitReadAloudTextParts(
        text: String,
        startOffset: Int = 0,
        endOffset: Int = text.length,
    ): List<ReadAloudTextPart> {
        val result = ArrayList<ReadAloudTextPart>()
        val safeStart = startOffset.coerceIn(0, text.length)
        val safeEnd = endOffset.coerceIn(safeStart, text.length)
        var lineStart = safeStart
        var index = safeStart
        while (index <= safeEnd) {
            val atEnd = index == safeEnd
            val char = if (atEnd) '\u0000' else text[index]
            if (atEnd || char == '\n' || char == '\r') {
                appendReadAloudChunks(text, lineStart, index, result)
                if (!atEnd && char == '\r' && index + 1 < safeEnd && text[index + 1] == '\n') {
                    index++
                }
                lineStart = index + 1
            }
            index++
        }
        if (result.isEmpty()) {
            appendReadAloudChunks(text, safeStart, safeEnd, result)
        }
        return result
    }

    private fun appendReadAloudChunks(
        source: String,
        rangeStart: Int,
        rangeEnd: Int,
        output: MutableList<ReadAloudTextPart>,
    ) {
        var start = rangeStart.coerceIn(0, source.length)
        var end = rangeEnd.coerceIn(start, source.length)
        while (start < end && source[start].isReadAloudWhitespace()) start++
        while (end > start && source[end - 1].isReadAloudWhitespace()) end--
        if (start >= end) return
        val builder = StringBuilder()
        var chunkStart = start
        var previousWasSpace = false
        fun emit(endExclusive: Int) {
            val spoken = builder.toString().trim()
            if (spoken.isNotBlank()) {
                val safeEnd = endExclusive.coerceIn(chunkStart, source.length)
                val highlight = source.substring(chunkStart, safeEnd).trim()
                output += ReadAloudTextPart(
                    text = spoken,
                    highlightText = highlight.ifBlank { spoken },
                    startOffset = chunkStart,
                    endOffset = safeEnd,
                )
            }
            builder.clear()
            previousWasSpace = false
        }
        var index = start
        while (index < end) {
            val char = source[index]
            if (builder.isEmpty()) {
                chunkStart = index
            }
            val normalized = if (char.isReadAloudWhitespace()) ' ' else char
            if (normalized == ' ' && (builder.isEmpty() || previousWasSpace)) {
                if (builder.isEmpty()) chunkStart = index + 1
                index++
                continue
            }
            builder.append(normalized)
            previousWasSpace = normalized == ' '
            val softBreak = char in READ_ALOUD_CHUNK_SOFT_BREAK_CHARS
            var emitEnd = index + 1
            if (softBreak) {
                while (emitEnd < end && source[emitEnd] in READ_ALOUD_CHUNK_TRAILING_CHARS) {
                    builder.append(source[emitEnd])
                    previousWasSpace = false
                    emitEnd++
                }
            }
            val canBreakAtSoftPoint = softBreak && builder.length >= READ_ALOUD_SEGMENT_TARGET_CHARS
            val forcedBreak = builder.length >= READ_ALOUD_SEGMENT_MAX_CHARS
            if (canBreakAtSoftPoint || forcedBreak) {
                emit(emitEnd)
                index = emitEnd
                continue
            }
            index++
        }
        if (builder.isNotBlank()) emit(end)
    }

    private fun mergeShortReadAloudParts(parts: List<ReadAloudTextPart>): List<ReadAloudTextPart> {
        if (parts.size <= 1) return parts
        val merged = ArrayList<ReadAloudTextPart>(parts.size)
        parts.forEach { part ->
            if (merged.isNotEmpty() && shouldMergeReadAloudPart(part.text)) {
                val previous = merged.removeAt(merged.lastIndex)
                merged += previous.mergeWith(part)
            } else {
                merged += part
            }
        }
        return merged
    }

    private fun shouldMergeReadAloudPart(text: String): Boolean {
        val clean = text.trim()
        if (clean.isBlank()) return true
        val core = clean.trim(*READ_ALOUD_WRAPPER_CHARS)
        return clean.firstOrNull() in READ_ALOUD_OPEN_WRAPPER_CHARS &&
            clean.lastOrNull() in READ_ALOUD_CLOSE_WRAPPER_CHARS &&
            core.none { it in READ_ALOUD_INNER_PUNCTUATION_CHARS }
    }

    private fun ReadAloudTextPart.mergeWith(next: ReadAloudTextPart): ReadAloudTextPart =
        ReadAloudTextPart(
            text = listOf(text, next.text).joinToString("").trim(),
            highlightText = listOf(highlightText, next.highlightText).joinToString("").trim(),
            startOffset = minOf(startOffset, next.startOffset),
            endOffset = maxOf(endOffset, next.endOffset),
        )

    private fun Char.isReadAloudWhitespace(): Boolean =
        this == ' ' || this == '\t' || this == '\u000B' || this == '\u000C' || this == '\n' || this == '\r'

    private fun selectedTtsSource(activity: Activity): TtsSourceEntry? {
        TtsSourceStore.list(activity.applicationContext)
            .firstOrNull { source -> settings?.isTtsSourceEnabled(source.id) == true }
            ?.let { return it }
        if (settings?.isTtsSourceEnabled(ModuleSettings.SYSTEM_TTS_SOURCE_ID) != false) {
            return null
        }
        error("no read-aloud TTS source enabled")
    }

    private fun startReadAloudService(
        activity: Activity,
        bookKey: String,
        bookTitle: String,
        coverUri: String,
        segments: List<ReadAloudSegment>,
        source: TtsSourceEntry?,
    ): String {
        val chunks = readAloudChunks(segments)
        if (chunks.isEmpty()) error("empty read-aloud chunks")
        val sessionId = prepareReadAloudService(
            activity = activity,
            bookKey = bookKey,
            bookTitle = bookTitle,
            coverUri = coverUri,
            source = source,
            totalChunks = chunks.size,
            initialChunk = chunks.first(),
            autoPlay = true,
        )
        sendReadAloudChunks(activity, sessionId, chunks.drop(1), chunkOffset = 1, totalChunks = chunks.size)
        return sessionId
    }

    private fun prepareReadAloudService(
        activity: Activity,
        bookKey: String,
        bookTitle: String,
        coverUri: String,
        source: TtsSourceEntry?,
        totalChunks: Int,
        initialChunk: List<ReadAloudSegment> = emptyList(),
        autoPlay: Boolean = false,
    ): String {
        val sessionId = "read_aloud_${System.currentTimeMillis()}"
        synchronized(readAloudRestartLock) {
            readAloudRestartSeq += 1
        }
        activeReadAloudBookKey = bookKey
        activeReadAloudSessionId = sessionId
        activeReadAloudPaused = false
        lastReadAloudFollowCfi = ""
        lastReadAloudFollowAtMs = 0L
        lastReadAloudPageKey = currentReadAloudPageKey(currentPageRef?.get())
        suppressReadAloudRestartUntilMs = SystemClock.elapsedRealtime() + READ_ALOUD_RESTART_SUPPRESS_MS
        sendReadAloudIntent(
            activity,
            Intent(ReadAloudIntents.ACTION_PREPARE)
                .setReadAloudService()
                .putExtra(ReadAloudIntents.EXTRA_SESSION_ID, sessionId)
                .putExtra(ReadAloudIntents.EXTRA_BOOK_KEY, bookKey)
                .putExtra(ReadAloudIntents.EXTRA_BOOK_TITLE, bookTitle)
                .putExtra(ReadAloudIntents.EXTRA_COVER_URI, coverUri)
                .putExtra(ReadAloudIntents.EXTRA_SOURCE_JSON, source?.toJson().orEmpty())
                .putExtra(ReadAloudIntents.EXTRA_TOTAL_CHUNKS, totalChunks.coerceAtLeast(0))
                .putExtra(ReadAloudIntents.EXTRA_AUTO_PLAY, autoPlay)
                .putReadAloudChunkExtras(initialChunk)
                .putExtra(
                    ReadAloudIntents.EXTRA_IGNORE_AUDIO_FOCUS,
                    settingsProvider().canIgnoreReaderReadAloudAudioFocus,
                )
                .putExtra(
                    ReadAloudIntents.EXTRA_LYRICON_ENABLED,
                    settingsProvider().canUseReaderReadAloudLyricon,
                ),
        )
        return sessionId
    }

    private fun queuePreparedReadAloudService(
        activity: Activity,
        sessionId: String,
        chunks: List<List<ReadAloudSegment>>,
    ) {
        sendReadAloudChunks(activity, sessionId, chunks, chunkOffset = 0, totalChunks = chunks.size)
        sendReadAloudIntent(
            activity,
            Intent(ReadAloudIntents.ACTION_PLAY)
                .setReadAloudService()
                .putExtra(ReadAloudIntents.EXTRA_SESSION_ID, sessionId),
        )
    }

    private fun stopReadAloudSession(activity: Activity, sessionId: String, reason: String) {
        if (sessionId != activeReadAloudSessionId) return
        XposedBridge.log("$LOG_PREFIX read aloud stop requested session=$sessionId reason=$reason")
        activeReadAloudSessionId = ""
        activeReadAloudBookKey = ""
        activeReadAloudPaused = false
        lastReadAloudFollowCfi = ""
        lastReadAloudFollowAtMs = 0L
        lastReadAloudPageKey = ""
        clearReadAloudHighlight()
        runCatching {
            sendReadAloudIntent(
                activity,
                Intent(ReadAloudIntents.ACTION_STOP)
                    .setReadAloudService()
                    .putExtra(ReadAloudIntents.EXTRA_SESSION_ID, sessionId),
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud stop delivery failed: ${it.stackTraceToString()}")
        }
    }

    private fun stopReadAloudIfPausedOnLeaveReader(reason: String) {
        val sessionId = activeReadAloudSessionId
        if (sessionId.isBlank() || !activeReadAloudPaused) return
        val activity = activityProvider() ?: return
        XposedBridge.log("$LOG_PREFIX read aloud paused on reader leave, stopping session=$sessionId reason=$reason")
        stopReadAloudSession(activity, sessionId, reason)
    }

    private fun appendReadAloudRemainderAsync(
        activity: Activity,
        context: CatalogContext,
        sessionId: String,
        alreadyQueuedSegments: Int,
    ) {
        Thread {
            runCatching {
                Thread.sleep(READ_ALOUD_BACKGROUND_APPEND_DELAY_MS)
                if (sessionId != activeReadAloudSessionId) return@Thread
                val allSegments = buildReadAloudSegments(
                    context = context,
                    maxSegments = MAX_READ_ALOUD_SEGMENTS,
                    currentFileOnly = false,
                )
                if (sessionId != activeReadAloudSessionId) return@Thread
                val remaining = allSegments.drop(alreadyQueuedSegments)
                if (remaining.isEmpty()) return@Thread
                val initialChunkCount = readAloudChunks(allSegments.take(alreadyQueuedSegments)).size
                val remainingChunks = readAloudChunks(remaining)
                sendReadAloudChunks(
                    activity = activity,
                    sessionId = sessionId,
                    chunks = remainingChunks,
                    chunkOffset = initialChunkCount,
                    totalChunks = initialChunkCount + remainingChunks.size,
                    delayBetweenChunksMs = READ_ALOUD_REMAINDER_CHUNK_DELAY_MS,
                )
                XposedBridge.log(
                    "$LOG_PREFIX read aloud appended remainder chunks=${remainingChunks.size} segments=${remaining.size}",
                )
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX read aloud append remainder failed: ${it.stackTraceToString()}")
            }
        }.apply {
            name = "ReaMicroReadAloudAppend"
            isDaemon = true
            start()
        }
    }

    private fun appendReadAloudSelectionRemainderAsync(
        activity: Activity,
        context: CatalogContext,
        selection: NativeSelectionPayload,
        sessionId: String,
        alreadyQueuedSegments: Int,
    ) {
        Thread {
            runCatching {
                Thread.sleep(READ_ALOUD_BACKGROUND_APPEND_DELAY_MS)
                if (sessionId != activeReadAloudSessionId) return@Thread
                val allSegments = buildReadAloudSegmentsFromSelection(
                    context = context,
                    selection = selection,
                    maxSegments = MAX_READ_ALOUD_SEGMENTS,
                    currentFileOnly = false,
                )
                if (sessionId != activeReadAloudSessionId) return@Thread
                val remaining = allSegments.drop(alreadyQueuedSegments)
                if (remaining.isEmpty()) return@Thread
                val initialChunkCount = readAloudChunks(allSegments.take(alreadyQueuedSegments)).size
                val remainingChunks = readAloudChunks(remaining)
                sendReadAloudChunks(
                    activity = activity,
                    sessionId = sessionId,
                    chunks = remainingChunks,
                    chunkOffset = initialChunkCount,
                    totalChunks = initialChunkCount + remainingChunks.size,
                    delayBetweenChunksMs = READ_ALOUD_REMAINDER_CHUNK_DELAY_MS,
                )
                XposedBridge.log(
                    "$LOG_PREFIX read aloud appended selection remainder chunks=${remainingChunks.size} " +
                        "segments=${remaining.size}",
                )
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX read aloud append selection remainder failed: ${it.stackTraceToString()}")
            }
        }.apply {
            name = "ReaMicroReadAloudSelectionAppend"
            isDaemon = true
            start()
        }
    }

    private fun sendReadAloudChunks(
        activity: Activity,
        sessionId: String,
        chunks: List<List<ReadAloudSegment>>,
        chunkOffset: Int,
        totalChunks: Int,
        delayBetweenChunksMs: Long = 0L,
    ) {
        chunks.forEachIndexed { index, chunk ->
            if (sessionId != activeReadAloudSessionId) return
            sendReadAloudIntent(
                activity,
                Intent(ReadAloudIntents.ACTION_APPEND)
                    .setReadAloudService()
                    .putExtra(ReadAloudIntents.EXTRA_SESSION_ID, sessionId)
                    .putExtra(ReadAloudIntents.EXTRA_CHUNK_INDEX, chunkOffset + index)
                    .putExtra(ReadAloudIntents.EXTRA_TOTAL_CHUNKS, totalChunks)
                    .putReadAloudChunkExtras(chunk),
            )
            if (delayBetweenChunksMs > 0L && index < chunks.lastIndex) {
                Thread.sleep(delayBetweenChunksMs)
            }
        }
    }

    private fun Intent.putReadAloudChunkExtras(chunk: List<ReadAloudSegment>): Intent =
        putStringArrayListExtra(ReadAloudIntents.EXTRA_TEXTS, ArrayList(chunk.map { it.text }))
            .putStringArrayListExtra(ReadAloudIntents.EXTRA_HIGHLIGHT_TEXTS, ArrayList(chunk.map { it.highlightText }))
            .putStringArrayListExtra(ReadAloudIntents.EXTRA_TITLES, ArrayList(chunk.map { it.chapterTitle }))
            .putIntegerArrayListExtra(ReadAloudIntents.EXTRA_CHAPTER_INDICES, ArrayList(chunk.map { it.chapterIndex }))
            .putStringArrayListExtra(ReadAloudIntents.EXTRA_START_CFIS, ArrayList(chunk.map { it.startCfi }))
            .putStringArrayListExtra(ReadAloudIntents.EXTRA_END_CFIS, ArrayList(chunk.map { it.endCfi }))

    private fun readAloudChunks(segments: List<ReadAloudSegment>): List<List<ReadAloudSegment>> {
        val chunks = ArrayList<List<ReadAloudSegment>>()
        var current = ArrayList<ReadAloudSegment>()
        var currentChars = 0
        segments.forEach { segment ->
            val nextChars = currentChars + segment.text.length
            if (current.isNotEmpty() && (current.size >= READ_ALOUD_SEGMENTS_PER_CHUNK || nextChars >= READ_ALOUD_CHUNK_MAX_CHARS)) {
                chunks += current
                current = ArrayList()
                currentChars = 0
            }
            current += segment
            currentChars += segment.text.length
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    private fun Intent.setReadAloudService(): Intent =
        setClassName(ReadAloudIntents.MODULE_PACKAGE_NAME, ReadAloudIntents.SERVICE_CLASS_NAME)

    private fun Intent.setReadAloudCommandActivity(): Intent =
        setClassName(ReadAloudIntents.MODULE_PACKAGE_NAME, ReadAloudIntents.COMMAND_ACTIVITY_CLASS_NAME)

    private fun sendReadAloudIntent(activity: Activity, intent: Intent) {
        val action = intent.action.orEmpty()
        if (startReadAloudServiceFromContext(activity, intent, "host")) return
        val moduleContext = runCatching {
            activity.createPackageContext(ReadAloudIntents.MODULE_PACKAGE_NAME, Context.CONTEXT_IGNORE_SECURITY)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud module context unavailable action=$action: ${it.message}")
        }.getOrNull()
        if (moduleContext != null && startReadAloudServiceFromContext(moduleContext, intent, "module-context")) return
        val preferActivity = action == ReadAloudIntents.ACTION_PREPARE
        if (preferActivity && startReadAloudCommandActivity(activity, intent)) return
        if (sendReadAloudCommandBroadcast(activity, intent)) return
        if (!preferActivity && startReadAloudCommandActivity(activity, intent)) return
        error("read aloud command delivery failed action=$action")
    }

    private fun sendReadAloudCommandBroadcast(activity: Activity, intent: Intent): Boolean {
        val action = intent.action.orEmpty()
        return runCatching {
            val command = Intent(intent).apply {
                setClassName(ReadAloudIntents.MODULE_PACKAGE_NAME, ReadAloudIntents.COMMAND_RECEIVER_CLASS_NAME)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            }
            activity.sendBroadcast(command)
            XposedBridge.log("$LOG_PREFIX read aloud command broadcast sent action=$action")
            true
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud command broadcast failed action=$action: ${it.stackTraceToString()}")
        }.getOrDefault(false)
    }

    private fun startReadAloudServiceFromContext(context: Context, intent: Intent, label: String): Boolean {
        val action = intent.action.orEmpty()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(intent).setReadAloudService())
            } else {
                context.startService(Intent(intent).setReadAloudService())
            }
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud $label service intent failed action=$action: ${it.stackTraceToString()}")
        }.getOrNull()
        if (started == null) {
            XposedBridge.log("$LOG_PREFIX read aloud $label service returned null action=$action")
            return false
        }
        XposedBridge.log("$LOG_PREFIX read aloud $label service started action=$action component=$started")
        return true
    }

    private fun startReadAloudCommandActivity(activity: Activity, intent: Intent): Boolean {
        val action = intent.action.orEmpty()
        return runCatching {
            val command = Intent(intent).apply {
                setReadAloudCommandActivity()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            activity.startActivity(command)
            XposedBridge.log("$LOG_PREFIX read aloud command activity started action=$action")
            true
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud command activity failed action=$action: ${it.stackTraceToString()}")
        }.getOrDefault(false)
    }

    private fun forEachSearchDocument(
        context: CatalogContext,
        explicitFiles: List<File>? = null,
        onDocument: (SearchDocument) -> Boolean,
    ) {
        val epub = currentEpubRef?.get()
        val root = currentEpubRoot() ?: return
        val epubTitlePaths = epubCatalogTitlePaths(root)
        val indexedCatalog = indexedCatalogChapters(context.catalog, root, epubTitlePaths)
        var pathHitCount = 0
        val chaptersByHref = indexedCatalog
            .mapIndexedNotNull { _, chapter ->
                val href = normalizeCatalogHref(callString(chapter.chapter, "getHref"))
                if (chapter.titlePath.count { it == ' ' } > 0) pathHitCount++
                if (href.isBlank()) null else href to IndexedChapter(chapter.index, chapter)
            }
            .groupBy({ it.first }, { it.second })
        val chaptersByFile = indexedCatalog
            .mapIndexedNotNull { _, chapter ->
                val file = searchFileForHref(root, callString(chapter.chapter, "getHref")) ?: return@mapIndexedNotNull null
                file.absolutePath to IndexedChapter(chapter.index, chapter)
            }
            .groupBy({ it.first }, { it.second })
        val itemRefs = (epub?.let { callNoArg(it, "getItemRefs") } as? Iterable<*>)?.filterNotNull().orEmpty()
        val spineCfiIndex = (epub?.let { callNoArg(it, "getSpineCfiIndex") } as? Int) ?: -1
        val catalogFiles = catalogTextFiles(root, context.catalog, itemRefs)
        val files = explicitFiles
            ?.map { it.canonicalFileSafe() ?: it }
            ?.filter { it.isFile && it.isTextContentFile() }
            ?.distinctBy { it.absolutePath }
            ?.takeIf { it.isNotEmpty() }
            ?: catalogFiles
        XposedBridge.log(
            "$LOG_PREFIX full-text catalog title paths catalog=${indexedCatalog.size} " +
                "pathHits=$pathHitCount hrefKeys=${chaptersByHref.size} files=${files.size}",
        )
        logCatalogDumpIfNeeded(context, indexedCatalog, root, pathHitCount)
        for (file in files) {
            val raw = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: continue
            val chapter = chapterForFile(root, file, chaptersByHref, chaptersByFile)
            val chapterAnchors = chapterAnchorsForFile(raw, chaptersForFile(root, file, chaptersByHref, chaptersByFile))
            val fallbackTitlePath = epubTitlePaths.titlePathForFile(root, file)
            val cfiBase = cfiBaseForFile(root, file, itemRefs, spineCfiIndex, chapter?.entry?.chapter)
            val indexedText = indexedSearchText(raw, cfiBase)
            val text = indexedText.text.ifBlank { htmlToSearchText(raw) }
            if (text.isBlank()) continue
            val document =
                SearchDocument(
                    file = file,
                    chapterIndex = chapter?.index ?: -1,
                    chapter = chapter?.entry?.chapter,
                    chapterTitle = searchChapterTitle(raw, chapter?.entry, file, fallbackTitlePath),
                    readAloudChapterTitle = chooseReadAloudDirectTitle(
                        readAloudHtmlChapterTitleHint(raw),
                        directReadAloudChapterTitle(chapter?.entry),
                    ),
                    text = text,
                    lowerText = text.lowercase(Locale.ROOT),
                    indexedText = indexedText,
                    chapterAnchors = chapterAnchors,
                )
            if (!onDocument(document)) return
        }
    }

    private fun logCatalogDumpIfNeeded(
        context: CatalogContext,
        catalog: List<CatalogChapterEntry>,
        root: File,
        pathHitCount: Int,
    ) {
        val key = "${bookKey(context)}:${catalog.size}:${root.absolutePath}"
        if (catalogDumpLoggedForKey == key) return
        catalogDumpLoggedForKey = key
        XposedBridge.log("$LOG_PREFIX catalog dump root=${root.absolutePath} count=${catalog.size} pathHits=$pathHitCount")
        catalog.take(12).forEach { entry ->
            val chapter = entry.chapter
            XposedBridge.log(
                "$LOG_PREFIX catalog dump #${entry.index} class=${chapter.javaClass.name} " +
                    "title=${catalogChapterTitle(chapter)} href=${callString(chapter, "getHref")} " +
                    "id=${catalogChapterId(chapter)} parent=${catalogChapterParentId(chapter)} " +
                    "level=${catalogChapterLevel(chapter, entry.index)} path=${entry.titlePath} " +
                    "fields=${catalogChapterFields(chapter)}",
            )
        }
    }

    private fun catalogChapterFields(chapter: Any): String =
        chapter.javaClass.declaredFields.take(12).joinToString(";") { field ->
            runCatching {
                field.isAccessible = true
                "${field.name}=${field.get(chapter)?.toString()?.take(80)}"
            }.getOrDefault("${field.name}=?")
        }

    private fun indexedCatalogChapters(
        catalog: List<Any>,
        root: File? = null,
        epubTitlePaths: Map<String, String> = root?.let(::epubCatalogTitlePaths).orEmpty(),
    ): List<CatalogChapterEntry> {
        val parentTitlePaths = catalogParentTitlePaths(catalog)
        val titleStack = ArrayList<String>()
        var currentVolumeTitle = ""
        return catalog.mapIndexed { index, chapter ->
            val title = catalogChapterTitle(chapter).normalizeChapterTitle()
            val id = catalogChapterId(chapter)
            val href = normalizeCatalogHref(callString(chapter, "getHref"))
            val epubTitlePath = epubTitlePaths.titlePathForHref(href)
            val level = catalogChapterLevel(chapter, index).coerceAtLeast(0)
            while (titleStack.size > level) titleStack.removeAt(titleStack.lastIndex)
            if (titleStack.size == level) {
                titleStack.add(title)
            } else {
                titleStack[titleStack.lastIndex] = title
            }
            val sequentialTitlePath = if (isVolumeCatalogTitle(title)) {
                currentVolumeTitle = title
                title
            } else if (currentVolumeTitle.isNotBlank() && title.isNotBlank() && !title.contains(currentVolumeTitle)) {
                listOf(currentVolumeTitle, title).dedupeAdjacent().joinToString(" ")
            } else {
                ""
            }
            CatalogChapterEntry(
                index = index,
                chapter = chapter,
                titlePath = epubTitlePath.ifBlank {
                    parentTitlePaths[id].orEmpty().ifBlank {
                        sequentialTitlePath.ifBlank {
                            titleStack.filter { it.isNotBlank() }.dedupeAdjacent().joinToString(" ")
                        }
                    }
                },
            )
        }
    }

    private fun isVolumeCatalogTitle(value: String): Boolean {
        val compact = value.replace(Regex("\\s+"), "")
        return Regex("""^第[0-9一二三四五六七八九十百千万〇零两]+[卷部篇集].*""").matches(compact) ||
            compact in setOf("正文", "番外", "外传", "后日谈")
    }

    private fun isChapterCatalogTitle(value: String): Boolean {
        val compact = value.replace(Regex("\\s+"), "")
        return Regex("""^第[0-9一二三四五六七八九十百千万〇零两]+[章节回话幕].*""").matches(compact) ||
            Regex("""^(序章|楔子|终章|尾声|后记).*""").matches(compact)
    }

    private fun catalogParentTitlePaths(catalog: List<Any>): Map<Long, String> {
        val byId = catalog.mapNotNull { chapter ->
            val id = catalogChapterId(chapter).takeIf { it != 0L } ?: return@mapNotNull null
            id to chapter
        }.toMap()
        val result = linkedMapOf<Long, String>()
        fun pathFor(chapter: Any, visiting: Set<Long> = emptySet()): String {
            val id = catalogChapterId(chapter)
            result[id]?.let { return it }
            val title = catalogChapterTitle(chapter).normalizeChapterTitle()
            val parentId = catalogChapterParentId(chapter).takeIf { it != 0L && it != id }
            val parent = parentId?.takeIf { it !in visiting }?.let { byId[it] }
            val path = if (parent != null) {
                listOf(pathFor(parent, visiting + id), title)
                    .filter { it.isNotBlank() }
                    .dedupeAdjacent()
                    .joinToString(" ")
            } else {
                title
            }
            if (id != 0L) result[id] = path
            return path
        }
        catalog.forEach { pathFor(it) }
        return result
    }

    private fun Map<String, String>.titlePathForHref(href: String): String {
        if (href.isBlank()) return ""
        get(href)?.let { return it }
        get(href.substringBefore('#'))?.let { return it }
        return entries.firstOrNull { (key, _) -> sameSearchContentPath(href, key) }?.value.orEmpty()
    }

    private fun Map<String, String>.titlePathForFile(root: File, file: File): String {
        val relative = normalizePath(relativePath(root, file))
        return titlePathForHref(relative)
    }

    private fun epubCatalogTitlePaths(root: File): Map<String, String> {
        val result = linkedMapOf<String, String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("ncx", ignoreCase = true) }
            .forEach { file -> result.putAll(parseNcxTitlePaths(root, file)) }
        root.walkTopDown()
            .filter { file ->
                file.isFile &&
                    (file.extension.equals("xhtml", ignoreCase = true) || file.extension.equals("html", ignoreCase = true))
            }
            .forEach { file -> result.putAll(parseNavTitlePaths(root, file)) }
        return result
    }

    private fun parseNcxTitlePaths(root: File, file: File): Map<String, String> = runCatching {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(file.inputStream().bufferedReader(StandardCharsets.UTF_8))
        val stack = ArrayList<TocNode>()
        val result = linkedMapOf<String, String>()
        var captureText = false
        val text = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                    "navpoint" -> stack.add(TocNode())
                    "content" -> stack.lastOrNull()?.href = normalizeHref(parser.getAttributeValue(null, "src").orEmpty())
                    "text" -> if (stack.isNotEmpty()) {
                        captureText = true
                        text.setLength(0)
                    }
                }
                XmlPullParser.TEXT -> if (captureText) text.append(parser.text.orEmpty())
                XmlPullParser.END_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                    "text" -> if (captureText) {
                        stack.lastOrNull()?.title = text.toString().normalizeChapterTitle()
                        captureText = false
                    }
                    "navpoint" -> if (stack.isNotEmpty()) {
                        val node = stack.removeAt(stack.lastIndex)
                        putTocNodeTitlePath(result, root, file, stack, node)
                    }
                }
            }
            parser.next()
        }
        result
    }.getOrDefault(emptyMap())

    private fun parseNavTitlePaths(root: File, file: File): Map<String, String> = runCatching {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(file.inputStream().bufferedReader(StandardCharsets.UTF_8))
        val stack = ArrayList<TocNode>()
        val result = linkedMapOf<String, String>()
        var tocNavDepth = 0
        var captureDepth = 0
        val text = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase(Locale.ROOT)
                    if (tocNavDepth > 0) tocNavDepth++
                    if (name == "nav" && tocNavDepth == 0 && tagHasTocType(parser)) tocNavDepth = 1
                    if (captureDepth > 0) captureDepth++
                    when {
                        tocNavDepth == 0 -> Unit
                        name == "li" -> stack.add(TocNode())
                        name == "a" && stack.isNotEmpty() -> {
                            stack.last().href = normalizeHref(parser.getAttributeValue(null, "href").orEmpty())
                            captureDepth = 1
                            text.setLength(0)
                        }
                    }
                }
                XmlPullParser.TEXT -> if (captureDepth > 0) text.append(parser.text.orEmpty())
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase(Locale.ROOT)
                    if (captureDepth > 0) {
                        captureDepth--
                        if (captureDepth == 0) {
                            stack.lastOrNull()?.title = text.toString().normalizeChapterTitle()
                        }
                    }
                    if (tocNavDepth > 0 && name == "li" && stack.isNotEmpty()) {
                        val node = stack.removeAt(stack.lastIndex)
                        putTocNodeTitlePath(result, root, file, stack, node)
                    }
                    if (tocNavDepth > 0) tocNavDepth--
                }
            }
            parser.next()
        }
        result
    }.getOrDefault(emptyMap())

    private fun tagHasTocType(parser: XmlPullParser): Boolean {
        for (index in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(index).orEmpty()
            val value = parser.getAttributeValue(index).orEmpty()
            if ((name == "type" || name.endsWith(":type") || name == "role") &&
                value.lowercase(Locale.ROOT).contains("toc")) {
                return true
            }
        }
        return false
    }

    private fun putTocNodeTitlePath(
        result: MutableMap<String, String>,
        root: File,
        source: File,
        stack: List<TocNode>,
        node: TocNode,
    ) {
        val title = node.title.normalizeChapterTitle()
        val href = normalizeCatalogHref(node.href)
        if (title.isBlank() || href.isBlank()) return
        val fileHref = href.substringBefore('#')
        val anchor = href.substringAfter('#', "").takeIf { it.isNotBlank() }
        val target = File(source.parentFile ?: root, fileHref).canonicalFileSafe() ?: File(source.parentFile ?: root, fileHref)
        val key = normalizePath(relativePath(root, target)) + anchor.orEmpty().let { if (it.isBlank()) "" else "#$it" }
        val fileKey = key.substringBefore('#')
        val path = (stack.map { it.title.normalizeChapterTitle() } + title)
            .filter { it.isNotBlank() }
            .dedupeAdjacent()
            .joinToString(" ")
        if (key.isNotBlank() && path.isNotBlank()) {
            result.putIfAbsent(key, path)
            result.putIfAbsent(fileKey, path)
        }
    }

    private fun normalizeCatalogHref(value: String): String {
        val normalized = normalizeHref(value)
        val file = normalizePath(normalized.substringBefore('#'))
        val anchor = normalized.substringAfter('#', "").takeIf { it.isNotBlank() }
        return file + anchor.orEmpty().let { if (it.isBlank()) "" else "#$it" }
    }

    private fun normalizeHref(value: String): String =
        runCatching { URLDecoder.decode(value.trim(), "UTF-8") }
            .getOrDefault(value.trim())
            .substringBefore('?')
            .replace('\uFEFF', ' ')
            .trim()

    private fun List<String>.dedupeAdjacent(): List<String> {
        val result = ArrayList<String>(size)
        forEach { value ->
            if (result.lastOrNull() != value) result.add(value)
        }
        return result
    }

    private fun catalogChapterLevel(chapter: Any, index: Int): Int {
        listOf("getLevel", "getDepth", "getIndent", "getTier", "getLayer").forEach { method ->
            (callNoArg(chapter, method) as? Number)?.toInt()?.let { return it }
        }
        listOf("level", "depth", "indent", "tier", "layer").forEach { fieldName ->
            runCatching {
                chapter.javaClass.declaredFields.firstOrNull { it.name == fieldName }?.let { field ->
                    field.isAccessible = true
                    (field.get(chapter) as? Number)?.toInt()
                }
            }.getOrNull()?.let { return it }
        }
        val value = chapter.toString()
        Regex("""(?:level|depth|indent|tier|layer)=(-?\d+)""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }
        return if (index == 0) 0 else 0
    }

    private fun catalogChapterId(chapter: Any): Long =
        catalogChapterLongValue(chapter, listOf("getId", "getID", "getChapterId", "getChapterID"), listOf("id", "chapterId"))

    private fun catalogChapterParentId(chapter: Any): Long =
        catalogChapterLongValue(
            chapter,
            listOf("getParentId", "getParentID", "getPid", "getPId", "getParentChapterId", "getParentChapterID"),
            listOf("parentId", "parentID", "pid", "pId", "parentChapterId"),
        )

    private fun catalogChapterLongValue(chapter: Any, methods: List<String>, fields: List<String>): Long {
        methods.forEach { method ->
            (callNoArg(chapter, method) as? Number)?.toLong()?.let { return it }
        }
        fields.forEach { fieldName ->
            runCatching {
                chapter.javaClass.declaredFields.firstOrNull { it.name == fieldName }?.let { field ->
                    field.isAccessible = true
                    val value = field.get(chapter)
                    (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull()
                }
            }.getOrNull()?.let { return it }
        }
        return 0L
    }

    private fun currentEpubRoot(): File? =
        currentEpubRef?.get()
            ?.let(::epubDirectory)
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }

    private fun epubDirectory(epub: Any?): String =
        callNoArg(epub, "getDirectory")?.toString().orEmpty()

    private fun currentBookCoverUri(context: CatalogContext): String {
        val root = currentEpubRoot()
        listOf(
            currentUiStateBook(),
            context.book,
            bottomReadAloudBookRef?.get(),
            bottomSearchBookRef?.get(),
            lastCatalogContext?.book,
        ).filterNotNull().forEach { book ->
            coverUriFromBook(book, root)?.let { return prepareCoverUriForService(it) }
        }
        return root?.let(::coverUriFromEpubRoot)?.let(::prepareCoverUriForService).orEmpty()
    }

    private fun prepareCoverUriForService(value: String): String {
        val raw = value.trim()
        if (raw.isBlank() || raw.startsWith("data:", ignoreCase = true)) return raw
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) return raw
        val bytes = when {
            raw.startsWith("content://", ignoreCase = true) -> runCatching {
                activityProvider()?.contentResolver?.openInputStream(android.net.Uri.parse(raw))?.use { it.readBytes() }
            }.getOrNull()
            raw.startsWith("file://", ignoreCase = true) -> runCatching {
                File(android.net.Uri.parse(raw).path.orEmpty()).takeIf { it.isFile }?.readBytes()
            }.getOrNull()
            else -> runCatching {
                File(raw).takeIf { it.isFile }?.readBytes()
            }.getOrNull()
        } ?: return raw
        return coverDataUriForService(bytes) ?: raw
    }

    private fun coverDataUriForService(bytes: ByteArray): String? =
        runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
            val sample = coverSampleSizeForService(options.outWidth, options.outHeight)
            val bitmap = BitmapFactory.Options().run {
                inSampleSize = sample
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
            } ?: return@runCatching null
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            bitmap.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX read aloud cover encode failed: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun coverSampleSizeForService(width: Int, height: Int): Int {
        var sample = 1
        var maxEdge = maxOf(width, height)
        while (maxEdge / sample > READ_ALOUD_COVER_MAX_EDGE_PX) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun coverUriFromBook(book: Any, root: File?): String? {
        val methodCandidates = listOf(
            "getCover",
            "getCoverUrl",
            "getCoverUri",
            "getCoverPath",
            "getImg",
            "getImage",
            "getImageUrl",
            "getThumbnail",
            "getThumbnailUrl",
        )
        methodCandidates
            .asSequence()
            .map { callString(book, it) }
            .mapNotNull { normalizeCoverCandidate(it, root) }
            .firstOrNull()
            ?.let { return it }

        val fieldCandidates = setOf(
            "cover",
            "coverUrl",
            "coverUri",
            "coverPath",
            "img",
            "image",
            "imageUrl",
            "thumbnail",
            "thumbnailUrl",
        )
        return (book.javaClass.fields.asSequence() + book.javaClass.declaredFields.asSequence())
            .filter { it.name in fieldCandidates }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    normalizeCoverCandidate(field.get(book)?.toString().orEmpty(), root)
                }.getOrNull()
            }
            .firstOrNull()
    }

    private fun normalizeCoverCandidate(value: String, root: File?): String? {
        val raw = value.trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) } ?: return null
        if (raw.startsWith("data:", ignoreCase = true) ||
            raw.startsWith("content://", ignoreCase = true) ||
            raw.startsWith("file://", ignoreCase = true) ||
            raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
        ) {
            return raw
        }
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        File(decoded).takeIf { it.isFile && it.isCoverImageFile() }?.let { return it.absolutePath }
        val normalized = normalizePath(decoded.substringBefore('#'))
        if (root != null && normalized.isNotBlank()) {
            coverFileForHref(root, root, normalized)?.let { return it.absolutePath }
        }
        return null
    }

    private fun coverUriFromEpubRoot(root: File): String {
        findOpfCoverFile(root)?.let { return it.absolutePath }
        val commonNames = listOf(
            "cover.jpg",
            "cover.jpeg",
            "cover.png",
            "cover.webp",
            "Images/cover.jpg",
            "Images/cover.jpeg",
            "Images/cover.png",
            "Images/cover.webp",
            "OEBPS/cover.jpg",
            "OEBPS/cover.jpeg",
            "OEBPS/cover.png",
            "OEBPS/cover.webp",
            "OEBPS/Images/cover.jpg",
            "OEBPS/Images/cover.jpeg",
            "OEBPS/Images/cover.png",
            "OEBPS/Images/cover.webp",
        )
        commonNames.forEach { path ->
            File(root, path).takeIf { it.isFile && it.isCoverImageFile() }?.let { return it.absolutePath }
        }
        return root.walkTopDown()
            .filter { it.isFile && it.isCoverImageFile() }
            .firstOrNull { it.nameWithoutExtension.contains("cover", ignoreCase = true) }
            ?.absolutePath
            .orEmpty()
    }

    private fun findOpfCoverFile(root: File): File? {
        for (opf in root.walkTopDown().filter { it.isFile && it.extension.equals("opf", ignoreCase = true) }) {
            val href = opfCoverHref(opf) ?: continue
            val base = opf.parentFile ?: root
            coverFileForHref(base, root, href)?.let { return it }
        }
        return null
    }

    private fun opfCoverHref(opf: File): String? = runCatching {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(opf.inputStream().bufferedReader(StandardCharsets.UTF_8))
        val manifest = linkedMapOf<String, String>()
        var coverId = ""
        var coverImageHref = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.lowercase(Locale.ROOT)) {
                    "meta" -> {
                        val name = parser.getAttributeValue(null, "name").orEmpty()
                        if (name.equals("cover", ignoreCase = true)) {
                            coverId = parser.getAttributeValue(null, "content").orEmpty()
                        }
                    }
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id").orEmpty()
                        val href = parser.getAttributeValue(null, "href").orEmpty()
                        val properties = parser.getAttributeValue(null, "properties").orEmpty()
                        if (id.isNotBlank() && href.isNotBlank()) manifest[id] = href
                        if (href.isNotBlank() && properties.split(Regex("\\s+")).any { it == "cover-image" }) {
                            coverImageHref = href
                        }
                    }
                }
            }
            parser.next()
        }
        coverImageHref.ifBlank { manifest[coverId].orEmpty() }.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun coverFileForHref(base: File, root: File, href: String): File? {
        val normalized = normalizePath(URLDecoder.decode(href.substringBefore('#'), "UTF-8"))
        if (normalized.isBlank()) return null
        val candidates = buildList {
            add(File(base, normalized))
            add(File(root, normalized))
            if ('/' in normalized) add(File(root, normalized.substringAfter('/')))
            if ('/' in normalized) add(File(root, normalized.substringAfterLast('/')))
        }
        return candidates.firstNotNullOfOrNull { candidate ->
            candidate.canonicalFileSafe()?.takeIf { it.isFile && it.isCoverImageFile() }
        }
    }

    private fun File.isCoverImageFile(): Boolean =
        isFile && extension.lowercase(Locale.ROOT) in COVER_IMAGE_EXTENSIONS

    private fun catalogTextFiles(root: File, catalog: List<Any>, itemRefs: List<Any>): List<File> {
        val fromSpine = itemRefs
            .mapIndexedNotNull { position, item ->
                val file = searchFileForHref(root, callString(item, "getHref")) ?: return@mapIndexedNotNull null
                val index = (callNoArg(item, "getIndex") as? Number)?.toInt() ?: position
                index to file
            }
            .sortedBy { it.first }
            .map { it.second }
            .distinctBy { it.absolutePath }
        if (fromSpine.isNotEmpty()) return fromSpine

        val fromCatalog = catalog
            .mapNotNull { searchFileForHref(root, callString(it, "getHref")) }
            .distinctBy { it.absolutePath }
        if (fromCatalog.isNotEmpty()) return fromCatalog
        return root.walkTopDown()
            .filter { it.isFile && it.isTextContentFile() }
            .map { it.canonicalFileSafe() ?: it }
            .sortedBy { it.absolutePath }
            .toList()
    }

    private fun searchFileForHref(root: File, href: String): File? {
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

    private fun searchChapterTitle(
        raw: String,
        chapter: CatalogChapterEntry?,
        file: File,
        fallbackTitlePath: String = "",
    ): String {
        val catalogTitle = chapter?.titlePath.orEmpty().ifBlank { fallbackTitlePath }.normalizeChapterTitle()
        val fileTitle = fileChapterTitleHint(raw)
        return chooseChapterTitle(catalogTitle, fileTitle).ifBlank { file.nameWithoutExtension }
    }

    private fun directReadAloudChapterTitle(chapter: CatalogChapterEntry?): String =
        chapter?.chapter
            ?.let(::catalogChapterTitle)
            ?.normalizeChapterTitle()
            .orEmpty()

    private fun readAloudSegmentChapterTitle(
        document: SearchDocument,
        isCurrentDocument: Boolean,
        textOffset: Int,
    ): String {
        val anchorTitle = document.chapterAnchors
            .lastOrNull { it.textStart <= textOffset }
            ?.let { anchor -> catalogChapterTitle(anchor.chapter).normalizeChapterTitle() }
            .orEmpty()
        return chooseReadAloudDirectTitle(document.readAloudChapterTitle, anchorTitle)
            .ifBlank {
                if (isCurrentDocument) fallbackCurrentChapterTitle().normalizeChapterTitle() else ""
            }
            .ifBlank { document.chapterTitle }
    }

    private fun chooseReadAloudDirectTitle(primary: String, fallback: String): String {
        val cleanPrimary = primary.normalizeChapterTitle()
        val cleanFallback = fallback.normalizeChapterTitle()
        return when {
            cleanPrimary.isBlank() -> cleanFallback
            cleanFallback.isBlank() -> cleanPrimary
            cleanPrimary == cleanFallback -> cleanPrimary
            cleanFallback.length >= 4 && cleanPrimary.endsWith(cleanFallback) -> cleanFallback
            cleanFallback.length >= 4 && cleanPrimary.contains(cleanFallback) -> cleanFallback
            else -> cleanPrimary
        }
    }

    private fun readAloudHtmlChapterTitleHint(raw: String): String {
        Regex("""(?is)<h[1-3]\b[^>]*>(.*?)</h[1-3]>""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.normalizeChapterTitle()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return Regex("""(?is)<title\b[^>]*>(.*?)</title>""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.normalizeChapterTitle()
            .orEmpty()
    }

    private fun fileChapterTitleHint(raw: String): String {
        val headTitle = Regex("""(?is)<title\b[^>]*>(.*?)</title>""")
            .find(raw)?.groupValues?.getOrNull(1)
            ?.normalizeChapterTitle()
            .orEmpty()
        if (headTitle.isNotBlank()) return headTitle
        return Regex("""(?is)<h[1-3]\b[^>]*>(.*?)</h[1-3]>""")
            .find(raw)?.groupValues?.getOrNull(1)
            ?.normalizeChapterTitle()
            .orEmpty()
    }

    private fun chooseChapterTitle(catalogTitle: String, fileTitle: String): String {
        if (fileTitle.isBlank()) return catalogTitle
        if (catalogTitle.isBlank()) return fileTitle
        if (fileTitle == catalogTitle) return catalogTitle
        if (isBareChapterLabel(catalogTitle) && !isBareChapterLabel(fileTitle)) return fileTitle
        if (fileTitle.length > catalogTitle.length && fileTitle.contains(catalogTitle)) return fileTitle
        return catalogTitle
    }

    private fun String.normalizeChapterTitle(): String =
        replace(Regex("<[^>]+>"), " ")
            .decodeBasicHtmlEntities()
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isBareChapterLabel(value: String): Boolean {
        if (value.isBlank()) return false
        val compact = value.replace(Regex("\\s+"), "")
        return Regex("""^第[0-9一二三四五六七八九十百千万〇零两]+[章节卷回集部篇]$""").matches(compact) ||
            compact in setOf("番外", "序章", "楔子", "后记", "尾声", "前言")
    }

    private fun catalogChapterTitle(chapter: Any): String {
        listOf("getTitle", "getName", "getLabel", "getText", "getChapterName").forEach { method ->
            callString(chapter, method).takeIf { it.isNotBlank() }?.let { return it }
        }
        listOf("title", "name", "label", "text", "chapterName").forEach { fieldName ->
            runCatching {
                chapter.javaClass.declaredFields.firstOrNull { it.name == fieldName }?.let { field ->
                    field.isAccessible = true
                    field.get(chapter)?.toString()?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()?.let { return it }
        }
        val value = chapter.toString()
        Regex("""(?:title|name|label|text|chapterName)=([^,\)]+)""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return ""
    }

    private fun cfiBaseForFile(
        root: File,
        file: File,
        itemRefs: List<Any>,
        spineCfiIndex: Int,
        chapter: Any?,
    ): CfiBase? {
        cfiBaseFromChapter(chapter)?.let { return it }
        if (spineCfiIndex < 0) return null
        val relative = normalizePath(relativePath(root, file))
        val itemRef = itemRefs.firstOrNull { item ->
            val href = normalizePath(callString(item, "getHref").substringBefore('#'))
            href.isNotBlank() && (relative == href || relative.endsWith("/$href") || href.endsWith("/$relative"))
        } ?: return null
        val itemRefIndex = (callNoArg(itemRef, "getIndex") as? Number)?.toInt() ?: return null
        return CfiBase(spineCfiIndex, itemRefIndex)
    }

    private fun cfiBaseFromChapter(chapter: Any?): CfiBase? {
        val cfi = callNoArg(chapter, "getCfi")?.toString().orEmpty()
        val match = Regex("""epubcfi\(/(\d+)/(\d+)""").find(cfi) ?: return null
        val spineIndex = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val itemRefIndex = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        return CfiBase(spineIndex, itemRefIndex)
    }

    private fun indexedSearchText(raw: String, cfiBase: CfiBase?): IndexedSearchText {
        if (cfiBase == null) return IndexedSearchText("", emptyList())
        return runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(searchBodyXml(raw)))
            val builder = StringBuilder()
            val spans = ArrayList<TextSpan>()
            val frames = ArrayList<ElementFrame>()
            var inBody = false
            var skipDepth = 0

            fun appendSeparator() {
                if (builder.isNotEmpty() && builder.last() != '\n') builder.append('\n')
            }

            fun nextElementStep(): Int {
                val parent = frames.lastOrNull() ?: return 1
                parent.elementChildCount += 1
                return parent.elementChildCount * 2
            }

            fun nextTextStep(): Int {
                val parent = frames.lastOrNull() ?: return 1
                parent.textChildCount += 1
                return parent.textChildCount * 2 - 1
            }

            fun currentPath(): List<Int> =
                frames.lastOrNull()?.path.orEmpty()

            fun currentBlock(): BlockState? =
                frames.asReversed().firstNotNullOfOrNull { it.block }

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name?.lowercase(Locale.ROOT).orEmpty()
                        if (skipDepth > 0) {
                            skipDepth++
                        } else if (name in SKIPPED_SEARCH_TAGS) {
                            if (inBody) nextElementStep()
                            skipDepth = 1
                        } else if (name == "body") {
                            frames.add(ElementFrame(name = name, step = 4, path = emptyList()))
                            inBody = true
                        } else if (inBody) {
                            val step = nextElementStep()
                            val path = currentPath() + step
                            val block = if (name in BLOCK_SEARCH_TAGS) BlockState(path) else null
                            if (name in BLOCK_SEARCH_TAGS) {
                                appendSeparator()
                            }
                            frames.add(ElementFrame(name = name, step = step, path = path, block = block))
                            if (name == "br") {
                                currentBlock()?.let { blockState ->
                                    if (builder.isNotEmpty() && builder.last() != '\n') {
                                        builder.append('\n')
                                    }
                                    blockState.offset += 1
                                }
                            }
                        }
                    }

                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        if (skipDepth == 0 && inBody) {
                            val rawText = parser.text.orEmpty()
                            val normalized = normalizeSearchNodeText(rawText)
                            if (normalized.text.isNotEmpty()) {
                                val textStep = nextTextStep()
                                val start = builder.length
                                builder.append(normalized.text)
                                val end = builder.length
                                spans.add(
                                    TextSpan(
                                        start = start,
                                        end = end,
                                        base = cfiBase,
                                        elementSteps = currentPath(),
                                        textStep = textStep,
                                        textOffset = normalized.leadingTrim,
                                    ),
                                )
                            }
                        }
                    }

                    XmlPullParser.COMMENT -> {
                        // Comments are not addressable text content for generated CFI targets.
                    }

                    XmlPullParser.END_TAG -> {
                        if (skipDepth > 0) {
                            skipDepth--
                        } else {
                            val name = parser.name?.lowercase(Locale.ROOT).orEmpty()
                            if (name == "body") {
                                while (frames.isNotEmpty()) frames.removeAt(frames.lastIndex)
                                inBody = false
                            } else if (inBody && frames.isNotEmpty()) {
                                val frame = frames.removeAt(frames.lastIndex)
                                if (frame.name in BLOCK_SEARCH_TAGS) appendSeparator()
                            }
                        }
                    }
                }
                event = parser.next()
            }
            IndexedSearchText(builder.toString().trimEnd(), spans).also {
                XposedBridge.log(
                    "$LOG_PREFIX full-text cfi index ok chars=${it.text.length} spans=${it.spans.size}",
                )
            }
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX full-text cfi index failed: ${it.message}")
        }.getOrDefault(IndexedSearchText("", emptyList()))
    }

    private fun searchBodyXml(raw: String): String =
        "<body>${sanitizeXmlForSearch(bodyOnlyHtml(raw)).normalizeSearchVoidTags()}</body>"

    private fun sanitizeXmlForSearch(raw: String): String =
        raw
            .replace(Regex("<!DOCTYPE[\\s\\S]*?>", RegexOption.IGNORE_CASE), "")
            .replace("&nbsp;", " ")
            .replace("&copy;", "(c)")
            .replace("&mdash;", "-")
            .replace("&ndash;", "-")
            .replace(Regex("&(?!amp;|lt;|gt;|quot;|apos;|#\\d+;|#x[0-9a-fA-F]+;)"), "&amp;")

    private fun String.normalizeSearchVoidTags(): String {
        var value = this
        for (tag in SEARCH_VOID_TAGS) {
            value = value.replace(Regex("<$tag\\b([^>]*)>", RegexOption.IGNORE_CASE)) { match ->
                val raw = match.value
                if (raw.endsWith("/>")) raw else "<$tag${match.groupValues.getOrNull(1).orEmpty()}/>"
            }
        }
        return value
    }

    private fun normalizeSearchNodeText(value: String): NormalizedNodeText {
        val start = value.indexOfFirst { it != ' ' && it != '\n' && it != '\r' && it != '\t' }
        if (start < 0) return NormalizedNodeText("", 0)
        val end = value.indexOfLast { it != ' ' && it != '\n' && it != '\r' && it != '\t' }
        return NormalizedNodeText(value.substring(start, end + 1), start)
    }

    private fun chapterForFile(
        root: File,
        file: File,
        chaptersByHref: Map<String, List<IndexedChapter>>,
        chaptersByFile: Map<String, List<IndexedChapter>>,
    ): IndexedChapter? {
        val relative = normalizePath(relativePath(root, file))
        val canonicalPath = (file.canonicalFileSafe() ?: file).absolutePath
        return chaptersByFile[canonicalPath]?.firstOrNull()
            ?: chaptersByHref[relative]?.firstOrNull()
            ?: chaptersByHref.entries.firstOrNull { (href, _) -> sameSearchContentPath(relative, href) }?.value?.firstOrNull()
    }

    private fun chaptersForFile(
        root: File,
        file: File,
        chaptersByHref: Map<String, List<IndexedChapter>>,
        chaptersByFile: Map<String, List<IndexedChapter>>,
    ): List<IndexedChapter> {
        val relative = normalizePath(relativePath(root, file))
        val canonicalPath = (file.canonicalFileSafe() ?: file).absolutePath
        return buildList {
            chaptersByFile[canonicalPath]?.let(::addAll)
            chaptersByHref[relative]?.let(::addAll)
            chaptersByHref.forEach { (href, chapters) ->
                if (sameSearchContentPath(relative, href)) addAll(chapters)
            }
        }.distinctBy { it.index }
            .sortedBy { it.index }
    }

    private fun chapterAnchorsForFile(raw: String, chapters: List<IndexedChapter>): List<ChapterAnchor> =
        chapters.mapNotNull { chapter ->
            val href = normalizeCatalogHref(callString(chapter.entry.chapter, "getHref"))
            val anchor = href.substringAfter('#', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val textStart = htmlAnchorTextStart(raw, anchor) ?: return@mapNotNull null
            ChapterAnchor(
                textStart = textStart,
                index = chapter.index,
                chapter = chapter.entry.chapter,
                title = chapter.entry.titlePath,
            )
        }.sortedBy { it.textStart }

    private fun htmlAnchorTextStart(raw: String, anchor: String): Int? {
        val decoded = normalizeHref(anchor)
        val escaped = Regex.escape(decoded)
        val match = Regex("""(?is)<[^>]+\b(?:id|name)\s*=\s*(['"])$escaped\1[^>]*>""")
            .find(raw) ?: return null
        return htmlToSearchText(raw.substring(0, match.range.first)).length
    }

    private fun sameSearchContentPath(relative: String, href: String): Boolean {
        val left = normalizePath(relative).substringBefore('#')
        val right = normalizePath(href).substringBefore('#')
        if (left.isBlank() || right.isBlank()) return false
        return left == right ||
            left.endsWith("/$right") ||
            right.endsWith("/$left") ||
            left.substringAfterLast('/') == right.substringAfterLast('/')
    }

    private fun snippetFor(text: String, start: Int, end: Int): SearchSnippet {
        val compactRadius = if (text.any { it.code > 127 }) {
            SEARCH_CJK_SNIPPET_RADIUS + SEARCH_SNIPPET_EXTRA_RADIUS
        } else {
            SEARCH_SNIPPET_RADIUS + SEARCH_SNIPPET_EXTRA_RADIUS
        }
        val from = (start - compactRadius).coerceAtLeast(0)
        val to = (end + compactRadius).coerceAtMost(text.length)
        val prefix = if (from > 0) "\u2026" else ""
        val suffix = if (to < text.length) "\u2026" else ""
        val body = text.substring(from, to).replace(Regex("\\s+"), " ").trim()
        val leadingTrim = text.substring(from, start).length -
            text.substring(from, start).replace(Regex("^\\s+"), "").length
        val matchStart = prefix.length + (start - from - leadingTrim).coerceAtLeast(0)
        val matchEnd = (matchStart + (end - start)).coerceAtMost(prefix.length + body.length)
        return SearchSnippet(prefix + body + suffix, matchStart, matchEnd)
    }

    private fun htmlToSearchText(value: String): String =
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

    private fun bodyOnlyHtml(value: String): String =
        Regex("<body\\b[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?: value
                .replace(Regex("<head\\b[\\s\\S]*?</head>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<title\\b[\\s\\S]*?</title>", RegexOption.IGNORE_CASE), " ")

    private fun String.decodeBasicHtmlEntities(): String =
        replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")

    private fun relativePath(root: File, file: File): String {
        val rootPath = (root.canonicalFileSafe() ?: root).absolutePath.trimEnd(File.separatorChar)
        val filePath = (file.canonicalFileSafe() ?: file).absolutePath
        return filePath.removePrefix(rootPath).trimStart(File.separatorChar)
    }

    private fun normalizePath(value: String): String =
        value.replace('\\', '/').trimStart('/')

    private fun bookTitle(context: CatalogContext): String =
        readableBookTitle(
            callString(currentPageRef?.get(), "getBookTitle"),
            readableBookTitleFromBook(currentUiStateBook()),
            readableBookTitleFromBook(context.book),
            titleFromCurrentEpubRoot(),
        )

    private fun currentHighlightBookIdentity(): Pair<String, String>? {
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

    private fun fallbackCurrentBookTitle(): String {
        val title = readableBookTitle(
            callString(currentPageRef?.get(), "getBookTitle"),
            readableBookTitleFromBook(currentUiStateBook()),
            lastCatalogContext?.let { readableBookTitleFromBook(it.book) }.orEmpty(),
            titleFromCurrentEpubRoot(),
        )
        if (title.isNotBlank()) return title
        return fallbackCurrentChapterTitle()
    }

    private fun currentUiStateBook(): Any? {
        val viewModel = currentViewModelRef?.get() ?: return null
        val uiStateFlow = callNoArg(viewModel, "getUiState") ?: return null
        val uiState = callNoArg(uiStateFlow, "getValue") ?: return null
        return callNoArg(uiState, "getBook")
    }

    private fun readableBookTitleFromBook(book: Any?): String =
        readableBookTitle(
            callString(book, "getTitle"),
            callString(book, "getSubtitle"),
            callString(book, "getUri"),
            callString(book, "getUuid"),
        )

    private fun bookKeyFromBook(book: Any, title: String): String =
        listOf(
            currentEpubRoot()?.absolutePath.orEmpty(),
            callString(book, "getId"),
            callString(book, "getBookId"),
            title,
        ).filter { it.isNotBlank() }.joinToString(separator = "|")

    private fun fallbackCurrentChapterTitle(): String {
        val page = currentPageRef?.get()
        return callString(callNoArg(page, "getChapter"), "getTitle")
            .ifBlank { callString(page, "getTitle") }
    }

    private fun titleFromCurrentEpubRoot(): String =
        currentEpubRoot()
            ?.nameWithoutExtension
            ?.takeIf { it.isNotBlank() }
            .orEmpty()

    private fun readableBookTitle(vararg candidates: String): String =
        candidates
            .map { cleanBookTitleCandidate(it) }
            .firstOrNull { it.isNotBlank() && !isInternalBookTitle(it) && !isGenericBookTitle(it) }
            .orEmpty()

    private fun cleanBookTitleCandidate(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return ""
        val segment = trimmed
            .substringAfterLast('|')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .removeSuffix(".epub")
            .removeSuffix(".EPUB")
            .trim()
        return segment
    }

    private fun isInternalBookTitle(value: String): Boolean =
        value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) ||
            value.matches(Regex("^[0-9a-fA-F]{16,}$")) ||
            value.contains('|')

    private fun isGenericBookTitle(value: String): Boolean =
        value == "\u672c\u4e66" || value == "\u56fe\u4e66"

    private fun isDifferentSearchBook(previous: CatalogContext, next: CatalogContext): Boolean {
        val previousKey = searchBookIdentity(previous)
        val nextKey = searchBookIdentity(next)
        return previousKey.isNotBlank() && nextKey.isNotBlank() && previousKey != nextKey
    }

    private fun searchBookIdentity(context: CatalogContext): String =
        listOf(
            callString(context.book, "getId"),
            callString(context.book, "getBookId"),
            bookTitle(context),
        ).firstOrNull { it.isNotBlank() }.orEmpty()

    private fun bookKey(context: CatalogContext): String =
        listOf(
            currentEpubRoot()?.absolutePath.orEmpty(),
            callString(context.book, "getId"),
            callString(context.book, "getBookId"),
            bookTitle(context),
        ).filter { it.isNotBlank() }.joinToString(separator = "|")

    private fun fullTextSearchJobKey(bookKey: String, keyword: String): String =
        "$bookKey\n${keyword.lowercase(Locale.ROOT)}"

    private fun showSelectionEditDialog(activity: Activity, text: String, onSave: (String) -> Unit) {
        val colors = DialogColors(activity)
        val dialog = Dialog(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val editor = createThoughtStyleEditor(activity, text, colors, ::dp)
        val save = thoughtStyleSaveButton(activity, colors, ::dp).apply {
            setOnClickListener {
                dialog.dismiss()
                onSave(editor.text?.toString().orEmpty())
            }
        }
        val inputCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = GradientDrawable().apply {
                setColor(colors.inputBackground)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(2), colors.inputStroke)
            }
            addView(
                editor,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(138),
                ),
            )
            addView(
                LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(android.view.View(activity), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(save)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            addView(
                inputCard,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.BOTTOM)
            decorView.setPadding(0, 0, 0, 0)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            )
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        focusEditorAndShowKeyboard(activity, editor)
    }

    private fun showDictionaryDialog(
        activity: Activity,
        selectedText: String,
        config: AiApiConfig,
        initialPreset: AiDictionaryPreset,
        onPresetSelected: (DictionaryDialogHandle, AiDictionaryPreset) -> Unit,
    ): DictionaryDialogHandle {
        val colors = DialogColors(activity)
        val dialog = Dialog(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val title = TextView(activity).apply {
            text = selectedText
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.primaryText)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val body = TextView(activity).apply {
            text = "\u6b63\u5728\u89e3\u6790..."
            textSize = 16f
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setTextColor(colors.primaryText)
            setTextIsSelectable(true)
        }
        val footerLabel = TextView(activity).apply {
            text = initialPreset.name
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.accent)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), dp(12), dp(6))
        }
        val model = TextView(activity).apply {
            text = config.model
            textSize = 13f
            setTextColor(colors.secondaryText)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = GradientDrawable().apply {
                setColor(colors.cardBackground)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), colors.stroke)
            }
            addView(title, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(ScrollView(activity).apply {
                setPadding(0, dp(12), 0, dp(10))
                addView(body, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(168),
            ))
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(footerLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(model, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
            addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.BOTTOM)
            decorView.setPadding(0, 0, 0, 0)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val handle = DictionaryDialogHandle(dialog, body, footerLabel, colors, initialPreset.id)
        footerLabel.setOnClickListener {
            showDictionaryPresetPicker(activity, handle) { preset ->
                onPresetSelected(handle, preset)
            }
        }
        return handle
    }

    private fun updateDictionaryDialog(handle: DictionaryDialogHandle, success: Boolean, message: String) {
        if (!handle.dialog.isShowing) return
        handle.body.text = message.ifBlank { "\u672a\u83b7\u53d6\u5230\u8bcd\u5178\u7ed3\u679c" }
        handle.body.setTextColor(if (success) handle.colors.primaryText else handle.colors.accent)
    }

    private fun showDictionaryPresetPicker(
        activity: Activity,
        handle: DictionaryDialogHandle,
        onSelected: (AiDictionaryPreset) -> Unit,
    ) {
        val presets = AiApiStore.dictionaryPresets(activity.applicationContext)
        if (presets.isEmpty()) return
        val colors = handle.colors
        val dialog = Dialog(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val currentPresetId = handle.currentPresetId
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(colors.cardBackground)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), colors.stroke)
            }
        }
        presets.forEach { preset ->
            list.addView(TextView(activity).apply {
                val selected = preset.id == currentPresetId
                text = if (selected) "\u2713 ${preset.name}" else preset.name
                textSize = 16f
                setTextColor(if (selected) colors.accent else colors.primaryText)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener {
                    dialog.dismiss()
                    if (handle.dialog.isShowing && preset.id != currentPresetId) {
                        onSelected(preset)
                    }
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
            addView(list, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.BOTTOM)
            decorView.setPadding(0, 0, 0, 0)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun createThoughtStyleEditor(
        activity: Activity,
        text: String,
        colors: DialogColors,
        dp: (Int) -> Int,
    ): EditText =
        EditText(activity).apply {
            setText(text)
            setSelection(length())
            setTextColor(colors.primaryText)
            setHintTextColor(colors.secondaryText)
            hint = "\u8bb0\u5f55\u6b64\u523b\u7684\u60f3\u6cd5"
            textSize = 18f
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(0, 0, 0, dp(10))
            background = null
        }

    private fun thoughtStyleSaveButton(
        activity: Activity,
        colors: DialogColors,
        dp: (Int) -> Int,
    ): TextView =
        TextView(activity).apply {
            text = "\u4fdd\u5b58"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(colors.actionText)
            setPadding(dp(20), dp(4), dp(20), dp(4))
            background = GradientDrawable().apply {
                setColor(colors.actionBackground)
                cornerRadius = dp(16).toFloat()
            }
            minWidth = dp(80)
            minHeight = dp(32)
        }

    private fun focusEditorAndShowKeyboard(activity: Activity, editor: EditText) {
        editor.requestFocus()
        editor.postDelayed(
            {
                editor.requestFocus()
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            },
            120L,
        )
    }

    private fun hideKeyboard(view: View) {
        runCatching {
            (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
    }

    private fun writeSelectionTextBack(oldText: String, newText: String): Boolean {
        val files = candidateCurrentTextFiles()
        if (files.isEmpty()) return false
        return files.any { file -> replaceUniqueTextInFile(file, oldText, newText) }
    }

    private fun candidateCurrentTextFiles(): List<File> {
        val root = currentEpubRef?.get()
            ?.let { callNoArg(it, "getDirectory")?.toString() }
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
            ?: return emptyList()
        val itemRefs = (currentEpubRef?.get()?.let { callNoArg(it, "getItemRefs") } as? Iterable<*>)?.toList().orEmpty()
        val currentFile = currentTextContentFile(root, itemRefs)
        val allTextFiles by lazy {
            root.walkTopDown()
                .filter { it.isFile && it.isTextContentFile() }
                .map { it.canonicalFileSafe() ?: it }
                .toList()
        }
        return buildList {
            if (currentFile != null) add(currentFile)
            addAll(allTextFiles.filter { currentFile == null || it.absolutePath != currentFile.absolutePath })
        }
    }

    private fun currentTextContentFile(root: File, itemRefs: Iterable<*>): File? {
        val page = currentPageRef?.get() ?: return null
        readAloudPageHrefCandidates(page).forEach { href ->
            searchFileForHref(root, href)?.let { file ->
                XposedBridge.log("$LOG_PREFIX read aloud current file by href href=$href file=${relativePath(root, file)}")
                return file
            }
        }
        val cfi = callString(page, "getAnchor")
            .ifBlank { callNoArg(page, "getStart")?.toString().orEmpty() }
        val cfiItemRefIndex = Regex("""epubcfi\(/\d+/(\d+)""")
            .find(cfi)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: -1
        if (cfiItemRefIndex >= 0) {
            val byCfi = itemRefs.firstOrNull { item ->
                (callNoArg(item, "getIndex") as? Number)?.toInt() == cfiItemRefIndex ||
                    (callNoArg(item, "getSpineIndex") as? Number)?.toInt() == cfiItemRefIndex
            }?.let { item ->
                searchFileForHref(root, callString(item, "getHref"))
            }
            if (byCfi != null) {
                XposedBridge.log(
                    "$LOG_PREFIX read aloud current file by cfi itemRef=$cfiItemRefIndex file=${relativePath(root, byCfi)}",
                )
                return byCfi
            }
        }
        val spineIndex = (callNoArg(page, "getSpineIndex") as? Number)?.toInt() ?: -1
        if (spineIndex < 0) {
            logReadAloudCurrentPageProbe(root, itemRefs, "missing-spine-index")
            return null
        }
        val currentHref = itemRefs.firstOrNull {
            (callNoArg(it, "getSpineIndex") as? Number)?.toInt() == spineIndex ||
                (callNoArg(it, "getIndex") as? Number)?.toInt() == spineIndex
        }
            ?.let { callString(it, "getHref") }
            ?.takeIf { it.isNotBlank() }
            ?: run {
                logReadAloudCurrentPageProbe(root, itemRefs, "spine-index-not-in-itemRefs")
                return null
            }
        return searchFileForHref(root, currentHref).also { file ->
            if (file == null) logReadAloudCurrentPageProbe(root, itemRefs, "href-not-found:$currentHref")
        }
    }

    private fun readAloudPageHrefCandidates(page: Any): List<String> =
        buildList {
            listOf(
                "getHref",
                "getPath",
                "getFile",
                "getFileName",
                "getSource",
                "getSrc",
            ).forEach { method ->
                callString(page, method).takeIf { it.isNotBlank() }?.let(::add)
            }
            val chapter = callNoArg(page, "getChapter")
            listOf("getHref", "getPath", "getFile", "getSource", "getSrc").forEach { method ->
                callString(chapter, method).takeIf { it.isNotBlank() }?.let(::add)
            }
        }.distinct()

    private fun logReadAloudCurrentPageProbe(root: File, itemRefs: Iterable<*>, reason: String) {
        val page = currentPageRef?.get()
        val key = listOf(
            reason,
            root.absolutePath,
            page?.javaClass?.name.orEmpty(),
            callString(page, "getAnchor"),
            callString(page, "getSummary").take(48),
        ).joinToString("|")
        if (key == readAloudPageProbeLogKey) return
        readAloudPageProbeLogKey = key
        val methodDump = listOf(
            "getAnchor",
            "getStart",
            "getSpineIndex",
            "getChapterIndex",
            "getPageIndex",
            "getSummary",
            "getHref",
            "getPath",
            "getFile",
        ).joinToString(";") { method ->
            "$method=${callNoArg(page, method)?.toString()?.take(120).orEmpty()}"
        }
        val fieldDump = page?.javaClass?.declaredFields
            ?.take(16)
            ?.joinToString(";") { field ->
                runCatching {
                    field.isAccessible = true
                    "${field.name}=${field.get(page)?.toString()?.take(120).orEmpty()}"
                }.getOrDefault("${field.name}=?")
            }
            .orEmpty()
        val itemRefDump = itemRefs.take(8).joinToString(";") { item ->
            "href=${callString(item, "getHref")},index=${callNoArg(item, "getIndex")},spine=${callNoArg(item, "getSpineIndex")}"
        }
        XposedBridge.log(
            "$LOG_PREFIX read aloud current file probe reason=$reason page=${page?.javaClass?.name} " +
                "methods=[$methodDump] fields=[$fieldDump] itemRefs=[$itemRefDump]",
        )
    }

    private fun replaceUniqueTextInFile(file: File, oldText: String, newText: String): Boolean {
        val content = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return false
        val candidates = listOf(oldText, escapeXmlText(oldText)).distinct().filter { it.isNotBlank() }
        for (candidate in candidates) {
            val first = content.indexOf(candidate)
            if (first < 0) continue
            if (content.indexOf(candidate, first + candidate.length) >= 0) return false
            val replacement = if (candidate == oldText) newText else escapeXmlText(newText)
            file.writeText(content.replaceRange(first, first + candidate.length, replacement), StandardCharsets.UTF_8)
            return true
        }
        return false
    }

    private fun File.isTextContentFile(): Boolean =
        isFile && extension.lowercase() in setOf("xhtml", "html", "htm", "xml", "txt")

    private fun File.canonicalFileSafe(): File? = runCatching { canonicalFile }.getOrNull()

    private fun escapeXmlText(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun invokeSuspendBlocking(method: Method, target: Any?, vararg args: Any?): Any? {
        val latch = CountDownLatch(1)
        var value: Any? = null
        var error: Throwable? = null
        val continuationClass = XposedHelpers.findClass(KOTLIN_CONTINUATION_CLASS, classLoader)
        val throwOnFailure = XposedHelpers.findClass(KOTLIN_RESULT_KT_CLASS, classLoader).declaredMethods.first {
            it.name == "throwOnFailure" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }
        val continuation = Proxy.newProxyInstance(classLoader, arrayOf(continuationClass)) { proxy, proxyMethod, proxyArgs ->
            when (proxyMethod.name) {
                "getContext" -> emptyCoroutineContext()
                "resumeWith" -> {
                    val result = proxyArgs?.getOrNull(0)
                    runCatching {
                        throwOnFailure.invoke(null, result)
                        value = result
                    }.onFailure {
                        error = if (it is InvocationTargetException) it.targetException ?: it else it
                    }
                    latch.countDown()
                    targetUnit()
                }
                "toString" -> "ReaMicroReaderContinuation"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === proxyArgs?.getOrNull(0)
                else -> null
            }
        }
        val returned = try {
            method.invoke(target, *args.toMutableList().apply { add(continuation) }.toTypedArray())
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
        if (returned !== coroutineSuspended()) return returned
        latch.await()
        error?.let { throw it }
        return value
    }

    private fun emptyCoroutineContext(): Any =
        XposedHelpers.findClass(KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS, classLoader)
            .getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)

    private fun coroutineSuspended(): Any =
        runCatching {
            XposedHelpers.findClass(KOTLIN_INTRINSICS_CLASS, classLoader).methods.first {
                it.name == "getCOROUTINE_SUSPENDED" && it.parameterTypes.isEmpty()
            }.apply { isAccessible = true }.invoke(null)
        }.getOrElse {
            (XposedHelpers.findClass(KOTLIN_COROUTINE_SINGLETONS_CLASS, classLoader).enumConstants ?: emptyArray())
                .first { value -> value.toString() == "COROUTINE_SUSPENDED" }
        }

    private fun targetUnit(): Any? = runCatching {
        XposedHelpers.findClass(KOTLIN_UNIT_CLASS, classLoader)
            .getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)
    }.getOrNull()

    private fun callNoArg(target: Any?, name: String): Any? =
        runCatching {
            target?.javaClass?.methods?.firstOrNull {
                it.parameterTypes.isEmpty() && it.name == name
            }?.invoke(target)
        }.getOrNull()

    private fun callString(target: Any?, name: String): String =
        callNoArg(target, name)?.toString().orEmpty()

    private fun setString(target: Any, name: String, value: String) {
        target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
        }?.invoke(target, value)
    }

    private fun setInt(target: Any, name: String, value: Int) {
        target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        }?.invoke(target, value)
    }

    private fun setFloat(target: Any, name: String, value: Float) {
        target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == Float::class.javaPrimitiveType
        }?.invoke(target, value)
    }

    private data class ReaderHighlightSheetRequest(
        val globalRules: Boolean,
        val bookKey: String,
        val bookTitle: String,
    )

    private data class PendingReaderTypeSettingTitle(
        val method: Method,
        val args: Array<Any?>,
    )

    private companion object {
        const val READER_VIEW_MODEL_CLASS = "app.zhendong.reamicro.ui.reader.ReaderViewModel"
        const val READER_UI_INTENT_CLASS = "app.zhendong.reamicro.ui.reader.ReaderUiIntent"
        const val NAV_GRAPH_SCOPE_CLASS = "app.zhendong.reamicro.NavGraphScope"
        const val READER_CATALOG_CLASS = "app.zhendong.reamicro.ui.reader.compose.ReaderCatalogKt"
        const val READER_TYPE_SETTING_CLASS = "app.zhendong.reamicro.ui.reader.compose.ReaderTypeSettingKt"
        const val READER_FAMILY_EPUB_CLASS = "app.zhendong.reamicro.ui.reader.compose.ReaderFamilyEpubKt"
        const val READER_FAMILY_USER_CLASS = "app.zhendong.reamicro.ui.reader.compose.ReaderFamilyUserKt"
        const val READER_FAMILY_BUILD_IN_CLASS = "app.zhendong.reamicro.ui.reader.compose.ReaderFamilyBuildInKt"
        const val READER_BOTTOM_BAR_CLASS = "app.zhendong.reamicro.ui.reader.components.ReaderBottomBarKt"
        const val UI_SHEET_STATUS_CLASS = "app.zhendong.reamicro.ui.reader.UiSheetStatus"
        const val READER_SHARED_STATE_CLASS = "app.zhendong.reamicro.ui.reader.components.ReaderSharedState"
        const val SCROLL_PAGER_KT_CLASS = "app.zhendong.reamicro.ui.reader.components.ScrollPagerKt"
        const val SESSION_CLASS = "app.zhendong.reamicro.repository.core.Session"
        const val PREF_KEYS_CLASS = "app.zhendong.reamicro.constants.PrefKeys"
        const val EPUB_PAGE_CLASS = "app.zhendong.reamicro.data.epub.EpubPage"
        const val HTML_DOCUMENT_CLASS = "org.epub.html.HtmlDocument"
        const val EPUB_CFI_CLASS = "org.epub.html.EpubCFI"
        const val CONTENT_DOM_CLASS = "org.epub.html.node.ContentDom"
        const val UI_EPUB_WINDOW_CLASS = "org.epub.UIEpubWindow"
        const val HOME_SCREEN_CLASS = "app.zhendong.reamicro.ui.home.HomeScreenKt"
        const val BOOKSHELF_SCREEN_CLASS = "app.zhendong.reamicro.ui.home.BookshelfScreenKt"
        const val BOOKMARK_CLASS = "app.zhendong.reamicro.data.reader.Bookmark"
        const val MARK_CLASS = "app.zhendong.reamicro.data.db.entity.Mark"
        const val CATALOG_CHAPTER_ITEM_CLASS = "app.zhendong.reamicro.ui.reader.CatalogChapterItem"
        const val EDIT_ICON_CLASS = "androidx.compose.material.icons.outlined.EditKt"
        const val DICTIONARY_ICON_TRANSLATE_CLASS = "androidx.compose.material.icons.outlined.TranslateKt"
        const val DICTIONARY_ICON_MENU_BOOK_CLASS = "androidx.compose.material.icons.outlined.MenuBookKt"
        const val DICTIONARY_ICON_AUTO_STORIES_CLASS = "androidx.compose.material.icons.outlined.AutoStoriesKt"
        const val DICTIONARY_ICON_BOOK_CLASS = "androidx.compose.material.icons.outlined.BookKt"
        const val HIGHLIGHT_ICON_BORDER_COLOR_CLASS = "androidx.compose.material.icons.outlined.BorderColorKt"
        const val HIGHLIGHT_ICON_FORMAT_COLOR_FILL_CLASS = "androidx.compose.material.icons.outlined.FormatColorFillKt"
        const val HIGHLIGHT_ICON_MODE_EDIT_CLASS = "androidx.compose.material.icons.outlined.ModeEditKt"
        const val READ_ALOUD_ICON_VOLUME_UP_CLASS = "androidx.compose.material.icons.outlined.VolumeUpKt"
        const val READ_ALOUD_ICON_RECORD_VOICE_OVER_CLASS = "androidx.compose.material.icons.outlined.RecordVoiceOverKt"
        const val ICONS_OUTLINED_CLASS = "androidx.compose.material.icons.Icons\$Outlined"
        const val LOG_PREFIX = "ReaMicro LSP"
        const val FLIP_STYLE_TRANSLATE = 0
        const val SCROLL_CRASH_PREFS = "reamicro_scroll_crash_guard"
        const val SCROLL_CRASH_PENDING_KEY = "scroll_crash_pending"
        const val KOTLIN_FUNCTION0_CLASS = "kotlin.jvm.functions.Function0"
        const val KOTLIN_FUNCTION1_CLASS = "kotlin.jvm.functions.Function1"
        const val KOTLIN_FUNCTION3_CLASS = "kotlin.jvm.functions.Function3"
        const val DARK_MODE_ICON_CLASS = "androidx.compose.material.icons.outlined.DarkModeKt"
        const val LIGHT_MODE_ICON_CLASS = "androidx.compose.material.icons.outlined.LightModeKt"
        const val ARROW_BACK_ICON_CLASS = "androidx.compose.material.icons.automirrored.outlined.ArrowBackKt"
        const val SEARCH_ICON_CLASS = "androidx.compose.material.icons.outlined.SearchKt"
        const val KOTLIN_UNIT_CLASS = "kotlin.Unit"
        const val KOTLIN_CONTINUATION_CLASS = "kotlin.coroutines.Continuation"
        const val COMPOSER_CLASS = "androidx.compose.runtime.Composer"
        const val KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS = "kotlin.coroutines.EmptyCoroutineContext"
        const val KOTLIN_INTRINSICS_CLASS = "kotlin.coroutines.intrinsics.IntrinsicsKt"
        const val KOTLIN_COROUTINE_SINGLETONS_CLASS = "kotlin.coroutines.intrinsics.CoroutineSingletons"
        const val KOTLIN_RESULT_KT_CLASS = "kotlin.ResultKt"
        const val FLOW_LAYOUT_KT_CLASS = "androidx.compose.foundation.layout.FlowLayoutKt"
        const val FLOW_ROW_METHOD = "FlowRow"
        const val ROW_KT_CLASS = "androidx.compose.foundation.layout.RowKt"
        const val ROW_METHOD = "Row"
        const val PADDING_KT_CLASS = "androidx.compose.foundation.layout.PaddingKt"
        const val PADDING_METHOD = "padding-qDBjuR0"
        const val PADDING_DEFAULT_METHOD = "padding-qDBjuR0\$default"
        const val ARRANGEMENT_CLASS = "androidx.compose.foundation.layout.Arrangement"
        const val ALIGNMENT_CLASS = "androidx.compose.ui.Alignment"
        const val MATERIAL_THEME_CLASS = "androidx.compose.material3.MaterialTheme"
        const val MATERIAL3_TEXT_CLASS = "androidx.compose.material3.TextKt"
        const val THEME_KT_CLASS = "app.zhendong.reamicro.arch.theme.ThemeKt"
        const val COLOR_KT_CLASS = "androidx.compose.ui.graphics.ColorKt"
        const val MODIFIER_CLASS = "androidx.compose.ui.Modifier"
        const val SIZE_KT_CLASS = "androidx.compose.foundation.layout.SizeKt"
        const val HEIGHT_METHOD = "height-3ABfNKs"
        const val FILL_MAX_WIDTH_METHOD = "fillMaxWidth"
        const val FILL_MAX_WIDTH_DEFAULT_METHOD = "fillMaxWidth\$default"
        const val UNIT_EXT_KT_CLASS = "app.zhendong.reamicro.arch.extensions.UnitExtKt"
        const val UDP_METHOD = "getUdp"
        const val TYPE_SETTING_FAMILY_METHOD = "TypeSettingFamily"
        const val HOST_READER_FAMILY_SHEET_HEIGHT_DP = 305
        const val READER_RULE_SHEET_HEIGHT_DP = 355
        const val READER_HIGHLIGHT_CONTEXT_REFRESH_DELAY_MS = 250L
        const val READER_TYPE_SETTING_INSERT_AFTER_ROW_INDEX = 0
        const val NATIVE_TYPE_GROUP_TOP_PADDING = 14
        const val NATIVE_SECTION_TITLE_BOTTOM_PADDING = 6
        @Volatile var latestThemeColors: ThemeColors? = null
        val READER_TYPE_SETTING_TITLE_TEXTS = setOf("\u5b57\u53f7", "\u95f4\u8ddd", "\u5b57\u4f53")
        val ARRANGEMENT_SPACED_BY_METHOD_CANDIDATES = listOf(
            "spacedBy-0680j_4",
            "m837spacedBy0680j_4",
            "m874spacedBy0680j_4",
        )
        const val MAX_SEARCH_RESULTS = 2000
        const val MAX_MATCHES_PER_FILE = 200
        const val SEARCH_SNIPPET_RADIUS = 16
        const val SEARCH_CJK_SNIPPET_RADIUS = 7
        const val SEARCH_SNIPPET_EXTRA_RADIUS = 3
        const val SEARCH_EMIT_BATCH = 24
        const val SEARCH_EMIT_INTERVAL_MS = 320L
        const val SEARCH_NAV_BAR_TAG = 0x524d5331
        const val SEARCH_MENU_BUTTON_TAG = 0x524d5333
        const val READ_ALOUD_MENU_BUTTON_TAG = 0x524d5334
        const val SEARCH_MENU_BUTTON_SIZE_DP = 44
        const val SEARCH_MENU_BUTTON_RIGHT_MARGIN_DP = 28
        const val READ_ALOUD_MENU_BUTTON_RIGHT_MARGIN_DP = 84
        const val SEARCH_MENU_BUTTON_BOTTOM_MARGIN_DP = 166
        const val READ_ALOUD_SEGMENT_TARGET_CHARS = 160
        const val READ_ALOUD_SEGMENT_MAX_CHARS = 260
        const val READ_ALOUD_INITIAL_SEGMENTS = 24
        const val READ_ALOUD_SEGMENTS_PER_CHUNK = 48
        const val READ_ALOUD_CHUNK_MAX_CHARS = 18_000
        const val READ_ALOUD_BACKGROUND_APPEND_DELAY_MS = 8_000L
        const val READ_ALOUD_REMAINDER_CHUNK_DELAY_MS = 80L
        const val MAX_READ_ALOUD_SEGMENTS = 5000
        const val READ_ALOUD_PAGE_RESTART_DELAY_MS = 450L
        const val READ_ALOUD_RESTART_SUPPRESS_MS = 1_500L
        const val READ_ALOUD_FOLLOW_MIN_INTERVAL_MS = 650L
        const val READ_ALOUD_COVER_MAX_EDGE_PX = 512
        const val READ_ALOUD_PROGRESS_RESTORE_DELAY_MS = 420L
        const val READ_ALOUD_PROGRESS_SYNC_MIN_INTERVAL_MS = 1_500L
        const val READ_ALOUD_PROGRESS_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        const val EPUB_PAGE_CONTENT = 0
        const val SEARCH_NAVIGATION_READER_BOTTOM_MARGIN_DP = 8
        const val SEARCH_NAVIGATION_MENU_BOTTOM_MARGIN_DP = 190
        const val SEARCH_JUMP_SINGLE_CORRECTION_DELAY_MS = 760L
        const val SEARCH_JUMP_SINGLE_CORRECTION_FALLBACK_DELAY_MS = 1250L
        const val SEARCH_HIGHLIGHT_OFFSET_TOLERANCE = 8
        const val SEARCH_ORIGIN_PREFS = "reamicro_search_origin"
        const val SEARCH_ORIGIN_KEY_TIMESTAMP = "timestamp"
        const val SEARCH_ORIGIN_KEY_BOOK = "book"
        const val SEARCH_ORIGIN_KEY_EPUB_ROOT = "epub_root"
        const val SEARCH_ORIGIN_KEY_CFI = "cfi"
        const val SEARCH_ORIGIN_KEY_CHAPTER_INDEX = "chapter_index"
        const val SEARCH_ORIGIN_KEY_TITLE = "title"
        const val SEARCH_ORIGIN_KEY_SUMMARY = "summary"
        const val SEARCH_ORIGIN_RESTORE_DELAY_MS = 360L
        const val SEARCH_ORIGIN_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        const val READ_ALOUD_PROGRESS_PREFS = "reamicro_read_aloud_progress"
        const val READ_ALOUD_PROGRESS_KEY_TIMESTAMP = "timestamp"
        const val READ_ALOUD_PROGRESS_KEY_SESSION = "session"
        const val READ_ALOUD_PROGRESS_KEY_BOOK = "book"
        const val READ_ALOUD_PROGRESS_KEY_BOOK_IDENTITY = "book_identity"
        const val READ_ALOUD_PROGRESS_KEY_EPUB_ROOT = "epub_root"
        const val READ_ALOUD_PROGRESS_KEY_BOOK_TITLE = "book_title"
        const val READ_ALOUD_PROGRESS_KEY_CFI = "cfi"
        const val READ_ALOUD_PROGRESS_KEY_END_CFI = "end_cfi"
        const val READ_ALOUD_PROGRESS_KEY_PARAGRAPH_INDEX = "paragraph_index"
        const val READ_ALOUD_PROGRESS_KEY_CHAPTER_INDEX = "chapter_index"
        const val READ_ALOUD_PROGRESS_KEY_TITLE = "title"
        const val READ_ALOUD_PROGRESS_KEY_SUMMARY = "summary"
        const val READ_ALOUD_PROGRESS_KEY_ELAPSED_MS = "elapsed_ms"
        const val READ_ALOUD_PROGRESS_KEY_RECORDED_ELAPSED_MS = "recorded_elapsed_ms"
        const val READ_ALOUD_PROGRESS_KEY_PLAYBACK_STARTED = "playback_started"
        const val MARK_KIND_HIGHLIGHT = 0
        const val MARK_STYLE_FILL = 0
        const val MARK_STYLE_LINE = 1
        const val MARK_SYNCED_NO = 0
        const val MARK_COLOR_RED = "red"
        const val MARK_COLOR_YELLOW = "yellow"
        const val SEARCH_HIGHLIGHT_MARK_ID_BASE = -9_223_372_036_854_000_000L
        const val SEARCH_HIGHLIGHT_MARK_ID_RANGE = 100_000L
        const val SELECTION_HIGHLIGHT_MARK_ID_BASE = -9_223_372_036_853_800_000L
        const val SELECTION_HIGHLIGHT_MARK_ID_RANGE = 100_000L
        const val READ_ALOUD_HIGHLIGHT_MARK_ID_BASE = -9_223_372_036_853_700_000L
        const val READ_ALOUD_HIGHLIGHT_MARK_ID_RANGE = 100_000L
        val BLOCK_SEARCH_TAGS = setOf(
            "p", "div", "section", "article", "li", "blockquote", "pre",
            "h1", "h2", "h3", "h4", "h5", "h6",
        )
        val SKIPPED_SEARCH_TAGS = setOf("head", "script", "style", "title", "svg", "math")
        val SEARCH_VOID_TAGS = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
        )
        val READ_ALOUD_CHUNK_SOFT_BREAK_CHARS = setOf(
            '\u3002', '\uff01', '\uff1f', '\uff1b', '\uff0c', '\u3001', '\uff1a',
            '.', '!', '?', ';', ',', ':',
        )
        val READ_ALOUD_CHUNK_TRAILING_CHARS = setOf(
            '\u3002', '\uff01', '\uff1f', '\uff1b', '\uff0c', '\u3001', '\uff1a',
            '.', '!', '?', ';', ',', ':',
            '\u2019', '\u201d', '\u3011', '\uff09', '\u300b', '\u3009',
            '\'', '"', ')', ']', '}', '>',
        )
        val READ_ALOUD_OPEN_WRAPPER_CHARS = setOf(
            '\u2018', '\u201c', '\u3010', '\uff08', '(', '[', '{', '<', '\u300a', '\u3008', '\'', '"',
        )
        val READ_ALOUD_CLOSE_WRAPPER_CHARS = setOf(
            '\u2019', '\u201d', '\u3011', '\uff09', ')', ']', '}', '>', '\u300b', '\u3009', '\'', '"',
        )
        val READ_ALOUD_WRAPPER_CHARS = charArrayOf(
            '\u2018', '\u2019', '\u201c', '\u201d', '\u3010', '\u3011', '\uff08', '\uff09',
            '(', ')', '[', ']', '{', '}', '<', '>', '\u300a', '\u300b', '\u3008', '\u3009', '\'', '"',
        )
        val READ_ALOUD_INNER_PUNCTUATION_CHARS = setOf(
            '\u3002', '\uff01', '\uff1f', '\uff1b', '\uff0c', '\u3001', '\uff1a',
            '.', '!', '?', ';', ',', ':',
        )
        val COVER_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }

    private data class CatalogContext(
        val intentReceiver: Any?,
        val book: Any?,
        val catalog: List<Any>,
    )

    private data class SearchState(
        val bookKey: String,
        val keyword: String,
        val results: List<FullTextSearchResult>,
    )

    private data class NativeSelectionPayload(
        val controller: Any?,
        val quote: String,
        val startCfi: String,
        val endCfi: String,
    )

    private data class SearchIndexState(
        val bookKey: String,
        val documents: List<SearchDocument>,
    )

    private data class SearchDocument(
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

    private data class ReadAloudSegment(
        val chapterTitle: String,
        val chapterIndex: Int,
        val text: String,
        val highlightText: String = text,
        val startCfi: String = "",
        val endCfi: String = "",
    )

    private data class ReadAloudTextPart(
        val text: String,
        val highlightText: String,
        val startOffset: Int,
        val endOffset: Int,
    )

    private data class ReadAloudSelectionLocation(
        val documentIndex: Int,
        val offset: Int,
    )

    private data class ReadingTarget(
        val cfi: String,
        val chapterIndex: Int,
        val title: String,
        val summary: String,
    )

    private data class SearchNavigationState(
        val bookKey: String,
        val returnTarget: ReadingTarget,
        val currentIndex: Int,
    )

    private data class PersistedSearchOrigin(
        val timestamp: Long,
        val bookKey: String,
        val epubRoot: String,
        val returnTarget: ReadingTarget,
    )

    private data class PersistedReadAloudProgress(
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

    private data class IndexedChapter(
        val index: Int,
        val entry: CatalogChapterEntry,
    )

    private data class CatalogChapterEntry(
        val index: Int,
        val chapter: Any,
        val titlePath: String,
    )

    private data class ChapterAnchor(
        val textStart: Int,
        val index: Int,
        val chapter: Any,
        val title: String,
    )

    private data class TocNode(
        var title: String = "",
        var href: String = "",
    )

    private data class SearchSnippet(
        val text: String,
        val matchStart: Int,
        val matchEnd: Int,
    )

    private data class NormalizedNodeText(
        val text: String,
        val leadingTrim: Int,
    )

    private data class CfiBase(
        val spineIndex: Int,
        val itemRefIndex: Int,
    )

    private data class BlockState(
        val path: List<Int>,
        var offset: Int = 0,
    )

    private data class ElementFrame(
        val name: String,
        val step: Int,
        val path: List<Int>,
        var elementChildCount: Int = 0,
        var textChildCount: Int = 0,
        val block: BlockState? = null,
    )

    private data class TextSpan(
        val start: Int,
        val end: Int,
        val base: CfiBase,
        val elementSteps: List<Int>,
        val textStep: Int,
        val textOffset: Int,
    )

    private data class IndexedSearchText(
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

    private data class FullTextSearchResult(
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

    private data class DictionaryDialogHandle(
        val dialog: Dialog,
        val body: TextView,
        val footerLabel: TextView,
        val colors: DialogColors,
        var currentPresetId: String,
    ) {
        var requestId: Long = 0L
    }

    private class SearchMenuButtonView(context: Context) : View(context) {
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

    private class ReadAloudMenuButtonView(context: Context) : View(context) {
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

    private class DialogColors(context: Context) {
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

    private data class ThemeColors(
        val pageBackground: Int,
        val cardBackground: Int,
        val inputBackground: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val stroke: Int,
        val chipBackground: Int,
        val action: Int,
    )

}

private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()
