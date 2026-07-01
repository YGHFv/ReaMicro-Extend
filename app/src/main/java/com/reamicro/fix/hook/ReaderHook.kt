package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
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
import com.reamicro.fix.ai.AiApiStore
import com.reamicro.fix.ai.AiApiTestResult
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.io.StringReader
import java.util.Locale
import java.util.concurrent.CountDownLatch
import org.xmlpull.v1.XmlPullParser

class ReaderHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot = { ModuleSettingsSnapshot() },
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
    @Volatile private var activeSearchHighlightId: Long? = null
    @Volatile private var activeSearchHighlightMark: Any? = null
    @Volatile private var activeSearchHighlightVisibleId: Long? = null
    @Volatile private var activeSearchHighlightPageSignature: String? = null
    @Volatile private var activeSearchHighlightPageNumber: Int? = null
    @Volatile private var activeSearchHighlightRenderLogId: Long? = null
    @Volatile private var activeSearchHighlightRenderLogCount: Int = 0
    @Volatile private var pendingSearchOriginRestore: Boolean = false
    @Volatile private var scrollCrashMarkerOwnedByThisProcess: Boolean = false

    fun install() {
        hookContentDomRenderTextWidthFallback()
        hookScrollPagerCrashGuard()
        installNativeSelectionHooks()
        hookReaderViewModel()
        hookReaderCatalog()
        hookReaderBottomBar()
        hookHomeBookshelfScreen()
    }

    private fun canEditReaderSelection(): Boolean =
        settingsProvider().canEditReaderSelection

    private fun canShowReaderDictionary(): Boolean =
        settingsProvider().canShowReaderDictionary

    private fun canRunFullTextSearch(): Boolean {
        val snapshot = settingsProvider()
        return snapshot.moduleEnabled && snapshot.readerEnabled
    }

    private fun canShowReaderSearchEntry(): Boolean =
        canRunFullTextSearch() && currentEpubRoot() != null && currentPageRef?.get() != null

    private fun hookReaderViewModel() {
        runCatching {
            val cls = classLoader.loadClass(READER_VIEW_MODEL_CLASS)
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    currentViewModelRef = WeakReference(param.thisObject)
                    param.args?.firstOrNull { it?.javaClass?.name == SESSION_CLASS }
                        ?.let { session ->
                            currentSessionRef = WeakReference(session)
                            restoreTranslateFlipStyleIfScrollCrashed(session, "ReaderViewModel")
                        }
                    XposedBridge.log("$LOG_PREFIX ReaderViewModel created")
                    scheduleRestorePersistedSearchOrigin("viewModel created")
                }
            })
            XposedBridge.hookAllMethods(cls, "onCleared", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    XposedBridge.log("$LOG_PREFIX ReaderViewModel cleared")
                    if (currentViewModelRef?.get() === param.thisObject) currentViewModelRef = null
                    currentSessionRef = null
                    clearScrollCrashPending("ReaderViewModel cleared")
                    currentSelectionControllerRef = null
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
                    currentVisiblePageSignature = epubPageSignature(page)
                    currentVisiblePageNumber = epubPageNumber(page)
                    XposedBridge.log(
                        "$LOG_PREFIX full-text search visible page " +
                            "number=${currentVisiblePageNumber ?: -1} sig=${currentVisiblePageSignature.orEmpty()}",
                    )
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
                        val previousContext = lastCatalogContext
                        if (previousContext != null && isDifferentSearchBook(previousContext, context)) {
                            resetFullTextSearchState("catalog book changed", removeOverlays = true)
                        }
                        lastCatalogContext = context
                        injectSearchHighlightIntoReaderCatalog(param)
                        ensureSearchIndexAsync(context)
                        scheduleRestorePersistedSearchOrigin("catalog context")
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
        if (activeSearchNavigation != null) {
            returnToSearchOrigin(clearNavigation = true, removeBar = false)
        }
        currentEpubRef = null
        currentPageRef = null
        readerBottomMenuVisible = false
        if (hadReaderSearchState) {
            resetFullTextSearchState("home rendered: $source", removeOverlays = true)
        }
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
                        readerBottomMenuVisible = canShowSearchEntry && statusName.isNotBlank() && statusName != "Reader"
                        if (canShowSearchEntry && statusName == "Menu") {
                            param.args?.getOrNull(1)?.let { bottomSearchReceiverRef = WeakReference(it) }
                            param.args?.getOrNull(2)?.let { bottomSearchBookRef = WeakReference(it) }
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!canRunFullTextSearch()) {
                            activityProvider()?.runOnUiThread {
                                readerBottomMenuVisible = false
                                bottomSearchReceiverRef = null
                                bottomSearchBookRef = null
                                removeSearchMenuButton()
                                removeSearchNavigationBar()
                            }
                            return
                        }
                        val receiver = param.args?.getOrNull(1)
                        val book = param.args?.getOrNull(2)
                        val status = param.args?.getOrNull(3)
                        val statusName = status?.toString().orEmpty()
                        val canShowSearchEntry = canShowReaderSearchEntry()
                        readerBottomMenuVisible = canShowSearchEntry && statusName.isNotBlank() && statusName != "Reader"
                        if (canShowSearchEntry && statusName == "Menu") {
                            bottomSearchReceiverRef = receiver?.let { WeakReference(it) }
                            bottomSearchBookRef = book?.let { WeakReference(it) }
                        }
                        val activity = activityProvider() ?: return
                        activity.runOnUiThread {
                            if (statusName == "Menu") {
                                if (canShowSearchEntry) {
                                    showSearchMenuButton(activity, receiver, book)
                                } else {
                                    removeSearchMenuButton()
                                }
                            } else {
                                removeSearchMenuButton()
                            }
                            updateSearchNavigationForBottomState(activity)
                        }
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX reader bottom search hook installed: ${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX reader bottom search hook failed: ${it.stackTraceToString()}")
        }
    }

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

        val resultsContainer = LinearLayout(activity).apply {
            tag = "searchResultsContainer"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.pageBackground)
        }
        val resultsScroll = ScrollView(activity).apply {
            tag = "searchResultsScroll"
            isFillViewport = true
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

        fun renderStatus(message: String) {
            visibleStatus = message
            visibleKeyword = ""
            visibleResults = emptyList()
            visibleSearching = false
            resultsContainer.removeAllViews()
            resultsContainer.addView(TextView(activity).apply {
                text = message
                textSize = 16f
                setTextColor(colors.secondaryText)
                setPadding(dp(activity, 32), dp(activity, 28), dp(activity, 32), 0)
            })
        }

        fun clearVisibleResults() {
            visibleStatus = null
            visibleKeyword = ""
            visibleResults = emptyList()
            visibleSearching = false
            resultsContainer.removeAllViews()
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
            renderSearchResults(activity, resultsContainer, keyword, results, colors, searching, currentResultIndex)
            scrollSearchResultToCenter(resultsScroll, resultsContainer, currentResultIndex)
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

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.pageBackground)
            addView(header, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(resultsScroll, LinearLayout.LayoutParams(
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
                        if (next.size != original.size) param.args[0] = next
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX native SelectionBubbleMenu hook installed: ${menuMethods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX native selection menu hook failed: ${it.stackTraceToString()}")
        }
    }

    private fun createNativeEditAction(icon: Any): Any? =
        runCatching {
            val actionClass = classLoader.loadClass("org.epub.ui.SelectionMenuAction")
            val constructor = actionClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 3 }
                ?: return@runCatching null
            constructor.isAccessible = true
            constructor.newInstance(
                "\u7f16\u8f91",
                icon,
                nativeFunction0 {
                    openNativeSelectionEditor()
                },
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX create native edit action failed: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun createNativeDictionaryAction(icon: Any): Any? =
        runCatching {
            val actionClass = classLoader.loadClass("org.epub.ui.SelectionMenuAction")
            val constructor = actionClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 3 }
                ?: return@runCatching null
            constructor.isAccessible = true
            constructor.newInstance(
                "\u8bcd\u5178",
                icon,
                nativeFunction0 {
                    openNativeSelectionDictionary()
                },
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX create native dictionary action failed: ${it.stackTraceToString()}")
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
                            scheduleRestorePersistedSearchOrigin("page rendered")
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
                        val mark = activeSearchHighlightMark ?: return
                        val id = searchResultHighlightMarkId(mark) ?: return
                        val current = (param.result as? List<*>)?.filterNotNull().orEmpty()
                        if (current.any { searchResultHighlightResolvedMarkId(it) == id }) {
                            logSearchHighlightResolvePage("hit", current.size, mark)
                            return
                        }
                        val resolved = createResolvedSearchHighlightMark(mark) ?: return
                        param.result = ArrayList<Any>(current.size + 1).apply {
                            addAll(current)
                            add(resolved)
                        }
                        logSearchHighlightResolvePage("forced", current.size + 1, mark)
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
                        val resolved = createResolvedSearchHighlightMark(activeSearchHighlightMark ?: return) ?: return
                        val current = (args.getOrNull(1) as? List<*>)?.filterNotNull().orEmpty()
                        val id = searchResultHighlightResolvedMarkId(resolved) ?: return
                        if (current.any { searchResultHighlightResolvedMarkId(it) == id }) return
                        args[1] = ArrayList<Any>(current.size + 1).apply {
                            addAll(current)
                            add(resolved)
                        }
                        logSearchHighlightContentOverlay("input", current.size + 1, activeSearchHighlightMark ?: resolved)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val mark = activeSearchHighlightMark ?: return
                        val id = searchResultHighlightMarkId(mark) ?: return
                        val current = (param.result as? List<*>)?.filterNotNull().orEmpty()
                        if (current.any { searchResultHighlightOverlayMarkId(it) == id }) {
                            logSearchHighlightContentOverlay("output", current.size, mark)
                            return
                        }
                        val args = param.args ?: return
                        val forced = createSearchHighlightContentOverlay(
                            contentDom = args.getOrNull(0),
                            visibleWindow = args.getOrNull(2),
                            renderedTextLength = (args.getOrNull(3) as? Number)?.toInt() ?: return,
                            mark = mark,
                        ) ?: return
                        param.result = ArrayList<Any>(current.size + 1).apply {
                            addAll(current)
                            add(forced)
                        }
                        logSearchHighlightContentOverlay("forced", current.size + 1, mark)
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
        val (controller, quote) = currentNativeSelectionText()
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
        val (_, quote) = currentNativeSelectionText()
        if (quote.isBlank()) {
            Toast.makeText(activity, "\u672a\u83b7\u53d6\u5230\u9009\u4e2d\u6587\u672c", Toast.LENGTH_SHORT).show()
            return
        }
        val config = AiApiStore.enabled(activity.applicationContext)
        if (config == null) {
            Toast.makeText(activity, "\u8bf7\u5148\u5728 AI \u914d\u7f6e\u4e2d\u542f\u7528 API", Toast.LENGTH_SHORT).show()
            return
        }
        activity.runOnUiThread {
            val handle = showDictionaryDialog(activity, quote, config)
            Thread({
                val result = runCatching { AiApiStore.dictionary(config, quote) }
                    .onFailure { XposedBridge.log("$LOG_PREFIX dictionary request failed: ${it.stackTraceToString()}") }
                    .getOrElse { error -> AiApiTestResult(false, error.message ?: error.javaClass.simpleName) }
                activity.runOnUiThread {
                    if (!activity.isFinishing &&
                        (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed)
                    ) {
                        updateDictionaryDialog(handle, result.success, result.message)
                    }
                }
            }, "ReaMicroDictionary").start()
        }
    }

    private fun currentNativeSelectionText(): Pair<Any?, String> {
        val controller = currentSelectionControllerRef?.get()
        val payload = callNoArg(controller, "selectedPayload")
        val quote = callString(payload, "getQuote").ifBlank { callString(controller, "selectedText") }
        return controller to quote.trim()
    }

    private fun renderSearchResults(
        activity: Activity,
        container: LinearLayout,
        keyword: String,
        results: List<FullTextSearchResult>,
        colors: DialogColors,
        searching: Boolean = false,
        currentResultIndex: Int? = null,
    ) {
        container.removeAllViews()
        container.addView(TextView(activity).apply {
            text = if (searching) {
                "\u641c\u7d22\u4e2d\uff0c\u5df2\u627e\u5230 ${results.size} \u5904"
            } else {
                "\u641c\u7d22\u5b8c\u6210\uff0c\u5171\u627e\u5230 ${results.size} \u5904"
            }
            textSize = 15f
            setTextColor(colors.secondaryText)
            setPadding(dp(activity, 24), dp(activity, 12), dp(activity, 24), dp(activity, 4))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        if (results.isEmpty()) {
            return
        }
        var previousGroupKey: String? = null
        results.forEachIndexed { index, result ->
            val groupKey = searchResultGroupKey(result)
            if (groupKey != previousGroupKey) {
                container.addView(TextView(activity).apply {
                    text = result.chapterTitle.ifBlank { result.file.nameWithoutExtension }
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colors.secondaryText)
                    setPadding(
                        dp(activity, 24),
                        if (index == 0) dp(activity, 14) else dp(activity, 22),
                        dp(activity, 24),
                        dp(activity, 6),
                    )
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                previousGroupKey = groupKey
            }
            container.addView(searchResultCard(activity, result, index, colors).apply {
                tag = searchResultViewTag(index)
                if (index == currentResultIndex) {
                    background = GradientDrawable().apply {
                        setColor(Color.argb(34, Color.red(colors.actionBackground), Color.green(colors.actionBackground), Color.blue(colors.actionBackground)))
                        cornerRadius = dp(activity, 6).toFloat()
                    }
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(activity, 24)
                rightMargin = dp(activity, 24)
                bottomMargin = dp(activity, 12)
            })
        }
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
    ): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 4), 0, dp(activity, 4))
            addView(TextView(activity).apply {
                text = redHighlightedSnippet(result)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colors.primaryText)
                setLineSpacing(0f, 1f)
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
        val startCfi = result.startCfi?.takeIf { it.isNotBlank() } ?: result.cfi?.takeIf { it.isNotBlank() } ?: return null
        val endCfi = result.endCfi
            ?.takeIf { it.isNotBlank() && it != startCfi }
            ?: searchHighlightEndCfi(startCfi, result.matchText.length)
            ?: result.cfi?.takeIf { it.isNotBlank() && it != startCfi }
            ?: return null
        return createReaderMark(
            id = SEARCH_HIGHLIGHT_MARK_ID_BASE + resultIndex.coerceAtLeast(0),
            chapter = result.chapterTitle,
            startCfi = startCfi,
            endCfi = endCfi,
            quote = result.matchText,
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
        val mark = activeSearchHighlightMark ?: return null
        val cleanMarks = original.filterNotNull().filterNot(::isSearchResultHighlightMark)
        if (cleanMarks.size == original.size && cleanMarks.any { it === mark }) return null
        return ArrayList<Any>(cleanMarks.size + 1).apply {
            addAll(cleanMarks)
            add(mark)
        }.also { next ->
            label?.let { logSearchHighlightRenderInput(it, original.size, next.size, mark) }
        }
    }

    private fun appendActiveSearchHighlightCatalogItemMap(original: Map<*, *>): Map<Any?, Any?>? {
        val mark = activeSearchHighlightMark ?: return null
        val id = searchResultHighlightMarkId(mark) ?: return null
        if (catalogItemMapContainsSearchHighlight(original, id)) return null
        val key = resolveActiveSearchHighlightCatalogMapKey(original, mark) ?: return null
        val currentItems = (original[key] as? List<*>)?.filterNotNull().orEmpty()
        if (currentItems.any { catalogChapterItemMarkId(it) == id }) return null
        val item = createSearchHighlightCatalogChapterItem(mark) ?: return null
        return LinkedHashMap<Any?, Any?>(original.size + 1).apply {
            original.forEach { (entryKey, entryValue) -> put(entryKey, entryValue) }
            put(key, ArrayList<Any>(currentItems.size + 1).apply {
                addAll(currentItems)
                add(item)
            })
        }.also {
            logSearchHighlightRenderInput("ReaderCatalog", currentItems.size, currentItems.size + 1, mark)
        }
    }

    private fun catalogItemMapContainsSearchHighlight(map: Map<*, *>, id: Long): Boolean =
        map.values.any { value ->
            (value as? Iterable<*>)?.any { item -> catalogChapterItemMarkId(item) == id } == true
        }

    private fun catalogChapterItemMarkId(item: Any?): Long? =
        callNoArg(item, "getMark")?.let(::searchResultHighlightMarkId)

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
        val id = searchResultHighlightMarkId(mark) ?: return
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
        val id = searchResultHighlightMarkId(mark) ?: return
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
        val id = searchResultHighlightMarkId(mark) ?: searchResultHighlightResolvedMarkId(mark) ?: return
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
        val targetNumber = activeSearchHighlightPageNumber
        val currentNumber = currentVisiblePageNumber
        if (targetNumber != null && currentNumber != null) {
            return when {
                targetNumber > currentNumber -> true
                targetNumber < currentNumber -> false
                else -> null
            }
        }
        val targetKey = targetSearchHighlightPageKey()
        val currentKey = currentVisibleSearchPageKey()
        if (targetKey != null && currentKey != null) {
            return if (targetKey != currentKey) true else null
        }
        return if (activeSearchHighlightVisibleId != idOrNull(activeSearchHighlightMark)) true else null
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
            val expectedLocalStart = cfiCharacterOffset(callString(mark, "getStartCfi"))
                ?.let { it - baseOffset }
                ?.takeIf { it in 0..content.length }
            val matchStart = searchHighlightQuoteStart(
                content = content,
                quote = quote,
                windowStart = windowStart,
                windowEnd = windowEnd,
                expectedLocalStart = expectedLocalStart,
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

    private fun searchHighlightEndCfi(startCfi: String, length: Int): String? {
        if (length <= 0) return null
        val match = Regex(""":(\d+)\)?$""").find(startCfi) ?: return null
        val start = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val end = start + length.coerceAtLeast(1)
        return startCfi.replaceRange(match.groups[1]!!.range, end.toString())
    }

    private fun searchHighlightQuoteStart(
        content: String,
        quote: String,
        windowStart: Int,
        windowEnd: Int,
        expectedLocalStart: Int?,
    ): Int? {
        if (expectedLocalStart != null) {
            if (expectedLocalStart !in windowStart..windowEnd || expectedLocalStart + quote.length > windowEnd) {
                return null
            }
            if (content.regionMatches(expectedLocalStart, quote, 0, quote.length)) {
                return expectedLocalStart
            }
            val nearbyStart = (expectedLocalStart - SEARCH_HIGHLIGHT_OFFSET_TOLERANCE).coerceAtLeast(windowStart)
            val nearbyEnd = (expectedLocalStart + SEARCH_HIGHLIGHT_OFFSET_TOLERANCE)
                .coerceAtMost(windowEnd - quote.length)
            if (nearbyEnd >= nearbyStart) {
                val nearbyMatches = generateSequence(content.indexOf(quote, nearbyStart)) { previous ->
                    content.indexOf(quote, previous + 1)
                }.takeWhile { it >= 0 && it <= nearbyEnd }.toList()
                return nearbyMatches.minByOrNull { kotlin.math.abs(it - expectedLocalStart) }
            }
            return null
        }
        val matches = generateSequence(content.indexOf(quote, windowStart)) { previous ->
            content.indexOf(quote, previous + 1)
        }.takeWhile { it >= 0 && it + quote.length <= windowEnd }.toList()
        if (matches.isEmpty()) return null
        return matches.first()
    }

    private fun cfiCharacterOffset(cfi: String): Int? =
        Regex(""":(\d+)\)?$""").find(cfi)?.groupValues?.getOrNull(1)?.toIntOrNull()

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
            XposedBridge.log(
                "$LOG_PREFIX full-text search highlight $label marks ${current.size}->${next.size} " +
                    "active=${activeSearchHighlightId ?: 0L}",
            )
            true
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX update search highlight marks failed: ${it.stackTraceToString()}")
        }.getOrDefault(false)

    private fun isSearchResultHighlightMark(mark: Any): Boolean {
        val id = searchResultHighlightMarkId(mark) ?: return false
        return id >= SEARCH_HIGHLIGHT_MARK_ID_BASE && id < SEARCH_HIGHLIGHT_MARK_ID_BASE + SEARCH_HIGHLIGHT_MARK_ID_RANGE
    }

    private fun searchResultHighlightMarkId(mark: Any): Long? {
        val id = (callNoArg(mark, "getId") as? Number)?.toLong() ?: return null
        return if (id >= SEARCH_HIGHLIGHT_MARK_ID_BASE && id < SEARCH_HIGHLIGHT_MARK_ID_BASE + SEARCH_HIGHLIGHT_MARK_ID_RANGE) {
            id
        } else {
            null
        }
    }

    private fun searchResultHighlightResolvedMarkId(mark: Any): Long? {
        val id = (callNoArg(mark, "getId") as? Number)?.toLong() ?: return null
        return if (id >= SEARCH_HIGHLIGHT_MARK_ID_BASE && id < SEARCH_HIGHLIGHT_MARK_ID_BASE + SEARCH_HIGHLIGHT_MARK_ID_RANGE) {
            id
        } else {
            null
        }
    }

    private fun searchResultHighlightOverlayMarkId(overlay: Any): Long? {
        val mark = callNoArg(overlay, "getMark") ?: return null
        return searchResultHighlightResolvedMarkId(mark)
    }

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
            onUpdate(results.toList(), done)
        }

        val cachedDocuments = searchIndexState?.takeIf { it.bookKey == key }?.documents
        if (cachedDocuments != null) {
            for (document in cachedDocuments) {
                if (generation != searchStateGeneration) return
                if (results.size >= MAX_SEARCH_RESULTS) break
                appendSearchMatches(document, needle, keyword, context, results)
                emit(done = false)
            }
            emit(done = true, force = true)
            return
        }

        val documents = ArrayList<SearchDocument>()
        forEachSearchDocument(context) { document ->
            if (generation != searchStateGeneration) return@forEachSearchDocument false
            if (results.size < MAX_SEARCH_RESULTS) {
                appendSearchMatches(document, needle, keyword, context, results)
                emit(done = false)
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
            val startCfi = document.indexedText.cfiAt(index)
            val cfi = document.indexedText.cfiAtSearchJump(index, keyword.length) ?: startCfi
            val endCfi = document.indexedText.cfiAtBoundary(index + keyword.length)
            results.add(
                FullTextSearchResult(
                    chapterIndex = document.chapterIndex,
                    chapter = document.chapter,
                    chapterTitle = document.chapterTitle,
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
            XposedBridge.log(
                "$LOG_PREFIX full-text search hit chapter=${document.chapterTitle} " +
                    "file=${document.file.name} index=$index startCfi=${startCfi.orEmpty()} " +
                    "jumpCfi=${cfi.orEmpty()} endCfi=${endCfi.orEmpty()} snippet=${snippet.text.take(60)}",
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

    private fun forEachSearchDocument(context: CatalogContext, onDocument: (SearchDocument) -> Boolean) {
        val epub = currentEpubRef?.get()
        val root = currentEpubRoot() ?: return
        val chaptersByHref = context.catalog
            .mapIndexedNotNull { index, chapter ->
                val href = callString(chapter, "getHref").substringBefore('#').trim()
                if (href.isBlank()) null else normalizePath(href) to IndexedChapter(index, chapter)
            }
            .groupBy({ it.first }, { it.second })
        val chaptersByFile = context.catalog
            .mapIndexedNotNull { index, chapter ->
                val file = searchFileForHref(root, callString(chapter, "getHref")) ?: return@mapIndexedNotNull null
                file.absolutePath to IndexedChapter(index, chapter)
            }
            .groupBy({ it.first }, { it.second })
        val itemRefs = (epub?.let { callNoArg(it, "getItemRefs") } as? Iterable<*>)?.filterNotNull().orEmpty()
        val spineCfiIndex = (epub?.let { callNoArg(it, "getSpineCfiIndex") } as? Int) ?: -1
        val files = catalogTextFiles(root, context.catalog, itemRefs)
        for (file in files) {
            val raw = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: continue
            val chapter = chapterForFile(root, file, chaptersByHref, chaptersByFile)
            val cfiBase = cfiBaseForFile(root, file, itemRefs, spineCfiIndex, chapter?.chapter)
            val indexedText = indexedSearchText(raw, cfiBase)
            val text = indexedText.text.ifBlank { htmlToSearchText(raw) }
            if (text.isBlank()) continue
            val document =
                SearchDocument(
                    file = file,
                    chapterIndex = chapter?.index ?: -1,
                    chapter = chapter?.chapter,
                    chapterTitle = searchChapterTitle(raw, chapter?.chapter, file),
                    text = text,
                    lowerText = text.lowercase(Locale.ROOT),
                    indexedText = indexedText,
                )
            if (!onDocument(document)) return
        }
    }

    private fun currentEpubRoot(): File? =
        currentEpubRef?.get()
            ?.let(::epubDirectory)
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }

    private fun epubDirectory(epub: Any?): String =
        callNoArg(epub, "getDirectory")?.toString().orEmpty()

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

    private fun searchChapterTitle(raw: String, chapter: Any?, file: File): String {
        val catalogTitle = chapter?.let(::catalogChapterTitle).orEmpty().normalizeChapterTitle()
        val fileTitle = fileChapterTitleHint(raw)
        return chooseChapterTitle(catalogTitle, fileTitle).ifBlank { file.nameWithoutExtension }
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

    private fun sameSearchContentPath(relative: String, href: String): Boolean {
        val left = normalizePath(relative)
        val right = normalizePath(href)
        if (left.isBlank() || right.isBlank()) return false
        return left == right ||
            left.endsWith("/$right") ||
            right.endsWith("/$left") ||
            left.substringAfterLast('/') == right.substringAfterLast('/')
    }

    private fun snippetFor(text: String, start: Int, end: Int): SearchSnippet {
        val compactRadius = if (text.any { it.code > 127 }) SEARCH_CJK_SNIPPET_RADIUS else SEARCH_SNIPPET_RADIUS
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
        callString(context.book, "getTitle")

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
            text = "\u8bcd\u5178\u91ca\u4e49"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.accent)
        }
        val model = TextView(activity).apply {
            text = config.displayName
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
                addView(footerLabel)
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
        return DictionaryDialogHandle(dialog, body, colors)
    }

    private fun updateDictionaryDialog(handle: DictionaryDialogHandle, success: Boolean, message: String) {
        if (!handle.dialog.isShowing) return
        handle.body.text = message.ifBlank { "\u672a\u83b7\u53d6\u5230\u8bcd\u5178\u7ed3\u679c" }
        handle.body.setTextColor(if (success) handle.colors.primaryText else handle.colors.accent)
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
        val page = currentPageRef?.get()
        val spineIndex = (callNoArg(page, "getSpineIndex") as? Int) ?: -1
        val itemRefs = (currentEpubRef?.get()?.let { callNoArg(it, "getItemRefs") } as? Iterable<*>)?.toList().orEmpty()
        val currentHref = itemRefs.firstOrNull { (callNoArg(it, "getSpineIndex") as? Int) == spineIndex }
            ?.let { callString(it, "getHref") }
            ?.takeIf { it.isNotBlank() }
        val currentFile = currentHref?.let { File(root, it).canonicalFileSafe() }?.takeIf { it.isTextContentFile() }
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

    private companion object {
        const val READER_VIEW_MODEL_CLASS = "app.zhendong.reamicro.ui.reader.ReaderViewModel"
        const val READER_UI_INTENT_CLASS = "app.zhendong.reamicro.ui.reader.ReaderUiIntent"
        const val READER_CATALOG_CLASS = "app.zhendong.reamicro.ui.reader.compose.ReaderCatalogKt"
        const val READER_BOTTOM_BAR_CLASS = "app.zhendong.reamicro.ui.reader.components.ReaderBottomBarKt"
        const val READER_SHARED_STATE_CLASS = "app.zhendong.reamicro.ui.reader.components.ReaderSharedState"
        const val SCROLL_PAGER_KT_CLASS = "app.zhendong.reamicro.ui.reader.components.ScrollPagerKt"
        const val SESSION_CLASS = "app.zhendong.reamicro.repository.core.Session"
        const val PREF_KEYS_CLASS = "app.zhendong.reamicro.constants.PrefKeys"
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
        const val ICONS_OUTLINED_CLASS = "androidx.compose.material.icons.Icons\$Outlined"
        const val LOG_PREFIX = "ReaMicro LSP"
        const val FLIP_STYLE_TRANSLATE = 0
        const val SCROLL_CRASH_PREFS = "reamicro_scroll_crash_guard"
        const val SCROLL_CRASH_PENDING_KEY = "scroll_crash_pending"
        const val KOTLIN_FUNCTION0_CLASS = "kotlin.jvm.functions.Function0"
        const val KOTLIN_UNIT_CLASS = "kotlin.Unit"
        const val KOTLIN_CONTINUATION_CLASS = "kotlin.coroutines.Continuation"
        const val KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS = "kotlin.coroutines.EmptyCoroutineContext"
        const val KOTLIN_INTRINSICS_CLASS = "kotlin.coroutines.intrinsics.IntrinsicsKt"
        const val KOTLIN_COROUTINE_SINGLETONS_CLASS = "kotlin.coroutines.intrinsics.CoroutineSingletons"
        const val KOTLIN_RESULT_KT_CLASS = "kotlin.ResultKt"
        const val MAX_SEARCH_RESULTS = 2000
        const val MAX_MATCHES_PER_FILE = 200
        const val SEARCH_SNIPPET_RADIUS = 16
        const val SEARCH_CJK_SNIPPET_RADIUS = 7
        const val SEARCH_EMIT_BATCH = 8
        const val SEARCH_EMIT_INTERVAL_MS = 220L
        const val SEARCH_NAV_BAR_TAG = 0x524d5331
        const val SEARCH_MENU_BUTTON_TAG = 0x524d5333
        const val SEARCH_MENU_BUTTON_SIZE_DP = 44
        const val SEARCH_MENU_BUTTON_RIGHT_MARGIN_DP = 28
        const val SEARCH_MENU_BUTTON_BOTTOM_MARGIN_DP = 166
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
        const val MARK_KIND_HIGHLIGHT = 0
        const val MARK_STYLE_FILL = 0
        const val MARK_STYLE_LINE = 1
        const val MARK_SYNCED_NO = 0
        const val MARK_COLOR_RED = "red"
        const val MARK_COLOR_YELLOW = "yellow"
        const val SEARCH_HIGHLIGHT_MARK_ID_BASE = -9_223_372_036_854_000_000L
        const val SEARCH_HIGHLIGHT_MARK_ID_RANGE = 100_000L
        val BLOCK_SEARCH_TAGS = setOf(
            "p", "div", "section", "article", "li", "blockquote", "pre",
            "h1", "h2", "h3", "h4", "h5", "h6",
        )
        val SKIPPED_SEARCH_TAGS = setOf("head", "script", "style", "title", "svg", "math")
        val SEARCH_VOID_TAGS = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
        )
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

    private data class SearchIndexState(
        val bookKey: String,
        val documents: List<SearchDocument>,
    )

    private data class SearchDocument(
        val file: File,
        val chapterIndex: Int,
        val chapter: Any?,
        val chapterTitle: String,
        val text: String,
        val lowerText: String,
        val indexedText: IndexedSearchText,
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

    private data class IndexedChapter(
        val index: Int,
        val chapter: Any,
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
        val colors: DialogColors,
    )

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

    private class DialogColors(context: Context) {
        val dark: Boolean = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val pageBackground: Int = if (dark) Color.rgb(17, 19, 24) else Color.WHITE
        val cardBackground: Int = if (dark) Color.rgb(38, 38, 38) else Color.WHITE
        val inputBackground: Int = if (dark) Color.rgb(44, 44, 44) else Color.WHITE
        val primaryText: Int = if (dark) Color.rgb(238, 238, 238) else Color.rgb(25, 25, 25)
        val secondaryText: Int = if (dark) Color.rgb(170, 170, 170) else Color.rgb(118, 118, 118)
        val stroke: Int = if (dark) Color.rgb(68, 68, 68) else Color.rgb(228, 228, 228)
        val searchChipBackground: Int = if (dark) Color.rgb(42, 42, 42) else Color.rgb(246, 246, 248)
        val inputStroke: Int = if (dark) Color.rgb(236, 162, 100) else Color.rgb(238, 118, 62)
        val actionBackground: Int = if (dark) Color.rgb(180, 112, 64) else Color.rgb(238, 118, 62)
        val actionText: Int = Color.WHITE
        val accent: Int = if (dark) Color.rgb(236, 183, 102) else Color.rgb(171, 105, 38)
    }
}

private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()
