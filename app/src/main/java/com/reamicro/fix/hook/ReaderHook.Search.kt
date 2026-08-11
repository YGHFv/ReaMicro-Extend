package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.Xml
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.reader.SearchHighlightPlanner
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.io.File
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.io.StringReader
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import com.reamicro.fix.hook.reader.*

// 阅读页全文搜索簇。
//
// 本地索引构建、结果列表页、跳转定位、高亮注入，以及跳转后高亮未渲染时的
// 翻页纠错。
//
// 从 ReaderHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的结果重新
// 缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun ReaderHook.canRunFullTextSearch(): Boolean {
    val snapshot = settingsProvider()
    return snapshot.moduleEnabled
}

internal fun ReaderHook.canShowReaderSearchEntry(): Boolean =
    canRunFullTextSearch() && currentEpubRoot() != null && currentReaderPage() != null

internal fun ReaderHook.injectSearchHighlightIntoReaderCatalog(param: XC_MethodHook.MethodHookParam) {
    val args = param.args ?: return
    val chapterItemsMap = args.getOrNull(5) as? Map<*, *> ?: return
    val nextMap = appendActiveSearchHighlightCatalogItemMap(chapterItemsMap) ?: return
    args[5] = nextMap
}

internal fun ReaderHook.clearSearchOverlays(clearNavigationState: Boolean) {
    closeSearchPage()
    removeSearchMenuButton()
    if (clearNavigationState) {
        activeSearchNavigation = null
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
        searchIndexBuildingKey != null ||
        searchPageDialogRef?.get() != null ||
        searchMenuButtonRef?.get() != null ||
        searchNavigationBarRef?.get() != null

internal fun ReaderHook.resetFullTextSearchState(reason: String, removeOverlays: Boolean) {
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

internal fun ReaderHook.updateSearchNavigationForBottomState(activity: Activity) {
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

internal fun ReaderHook.searchImageVector(): Any? = runCatching {
    val outlined = classLoader.loadClass(ICONS_OUTLINED_CLASS).getField("INSTANCE").get(null)
    classLoader.loadClass(SEARCH_ICON_CLASS).declaredMethods.firstOrNull {
        it.name == "getSearch" && it.parameterTypes.size == 1
    }?.apply { isAccessible = true }?.invoke(null, outlined)
}.getOrNull()

internal fun ReaderHook.showSearchMenuButton(activity: Activity, receiver: Any?, book: Any?) {
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

internal fun ReaderHook.removeSearchMenuButton() {
    val activity = searchMenuButtonActivityRef?.get() ?: activityProvider()
    searchMenuButtonRef = null
    searchMenuButtonActivityRef = null
    postRemoveTaggedViews(activity, SEARCH_MENU_BUTTON_TAG)
    maybeUnregisterSearchOverlayThemeCallbacks()
}

internal fun ReaderHook.ensureSearchOverlayThemeCallbacks(activity: Activity) {
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

internal fun ReaderHook.refreshSearchMenuButtonTheme() {
    (searchMenuButtonRef?.get() as? SearchMenuButtonView)?.refreshColors()
}

internal fun ReaderHook.refreshSearchNavigationBarTheme() {
    val bar = searchNavigationBarRef?.get() ?: return
    val activity = searchNavigationBarActivityRef?.get() ?: bar.context
    applySearchNavigationBarTheme(bar, DialogColors(activity))
}

internal fun ReaderHook.maybeUnregisterSearchOverlayThemeCallbacks() {
    if (searchMenuButtonRef?.get() != null || searchNavigationBarRef?.get() != null) return
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
    if (!canShowReaderSearchEntry()) {
        XposedBridge.log(
            "$LOG_PREFIX search blocked entry: moduleEnabled=${canRunFullTextSearch()} " +
                "epubRoot=${currentEpubRoot() != null} page=${currentReaderPage() != null} " +
                "epubStrong=${currentEpubStrong != null} epubWeak=${currentEpubRef?.get() != null} " +
                "pageStrong=${currentPageStrong != null} pageWeak=${currentPageRef?.get() != null}",
        )
        removeSearchMenuButton()
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
        ensureSearchIndexAsync(context)
        showFullTextSearchPage(activity, context)
    }
}

internal fun ReaderHook.closeSearchPage() {
    searchPageDialogRef?.get()?.dismiss()
    searchPageDialogRef = null
    activeSearchPageToken = 0L
    activeSearchPageUpdate = null
}

internal fun ReaderHook.showFullTextSearchPage(activity: Activity, context: CatalogContext) {
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

internal fun ReaderHook.searchKeywordInputBackground(context: Context, colors: DialogColors): GradientDrawable =
    GradientDrawable().apply {
        setColor(colors.inputBackground)
        cornerRadius = dp(context, 18).toFloat()
        setStroke(dp(context, 1), colors.stroke)
    }

internal fun ReaderHook.configureFullTextSearchWindow(
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

internal fun ReaderHook.applyFullTextSearchSystemBarAppearance(window: Window, dark: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    window.insetsController?.setSystemBarsAppearance(
        if (dark) 0 else lightBars,
        lightBars,
    )
}

internal fun ReaderHook.fullTextSearchSystemUiFlags(dark: Boolean): Int {
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

internal fun ReaderHook.renderSearchResults(
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

internal fun ReaderHook.searchVolumeHeaderView(
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

internal fun ReaderHook.searchGroupHeaderView(
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

internal fun ReaderHook.scrollSearchResultToCenter(scrollView: ScrollView, container: LinearLayout, index: Int?) {
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

internal fun ReaderHook.findSearchResultView(container: LinearLayout, index: Int): View? {
    val tag = searchResultViewTag(index)
    for (childIndex in 0 until container.childCount) {
        val child = container.getChildAt(childIndex)
        if (child?.tag == tag) return child
    }
    return null
}

internal fun ReaderHook.searchResultViewTag(index: Int): String = "searchResult:$index"

internal fun ReaderHook.searchVolumeHeaderTag(volumeKey: String): String = "searchVolume:$volumeKey"

internal fun ReaderHook.searchGroupHeaderTag(groupKey: String): String = "searchGroup:$groupKey"

internal fun ReaderHook.searchResultVolumeKey(result: FullTextSearchResult): String =
    searchResultVolumeTitle(result).ifBlank { "" }

internal fun ReaderHook.searchResultVolumeTitle(result: FullTextSearchResult): String {
    val parts = result.chapterTitle.split(' ').filter { it.isNotBlank() }
    if (parts.isEmpty()) return ""
    if (!isVolumeCatalogTitle(parts.first())) return ""
    val chapterIndex = parts.indexOfFirstIndexed { index, part ->
        index > 0 && isChapterCatalogTitle(part)
    }
    return parts.take(if (chapterIndex > 0) chapterIndex else 1).joinToString(" ")
}

internal fun ReaderHook.searchResultChapterTitle(result: FullTextSearchResult): String {
    val volume = searchResultVolumeTitle(result)
    val title = result.chapterTitle.trim()
    return if (volume.isNotBlank() && title.startsWith(volume)) {
        title.removePrefix(volume).trim().ifBlank { title }
    } else {
        title
    }
}

internal fun ReaderHook.searchResultGroupKey(result: FullTextSearchResult): String =
    if (result.chapterIndex >= 0) {
        "chapter:${result.chapterIndex}"
    } else {
        "file:${result.file.absolutePath}"
    }

internal fun ReaderHook.searchResultCard(
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

internal fun ReaderHook.jumpToSearchResult(result: FullTextSearchResult, resultIndex: Int) {
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

internal fun ReaderHook.returnToSearchOrigin(
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

internal fun ReaderHook.jumpRelativeSearchResult(step: Int) {
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

internal fun ReaderHook.ensureSearchNavigationBar(activity: Activity) {
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

internal fun ReaderHook.updateSearchNavigationBarPosition() {
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

internal fun ReaderHook.searchNavigationBottomMargin(context: Context): Int =
    dp(
        context,
        if (readerBottomMenuVisible) {
            SEARCH_NAVIGATION_MENU_BOTTOM_MARGIN_DP
        } else {
            SEARCH_NAVIGATION_READER_BOTTOM_MARGIN_DP
        },
    )

internal fun ReaderHook.updateSearchNavigationBar(bar: View) {
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

internal fun ReaderHook.isSearchNavigationCurrent(navigation: SearchNavigationState): Boolean =
    lastSearchState?.bookKey == navigation.bookKey

internal fun ReaderHook.clearStaleSearchNavigation() {
    activeSearchNavigation = null
    clearPersistedSearchOrigin()
    clearSearchResultHighlight()
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
        color = MARK_COLOR_YELLOW,
    )
}

internal fun ReaderHook.applySearchResultHighlight(viewModel: Any?, mark: Any) {
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

internal fun ReaderHook.scheduleSearchResultHighlightRefresh(viewModel: Any?, mark: Any, id: Long, delayMs: Long) {
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

internal fun ReaderHook.injectSearchResultHighlight(viewModel: Any?, mark: Any): Boolean =
    updateSearchMarks(viewModel, "inject") { marks ->
        marks.filterNot(::isSearchResultHighlightMark) + mark
    }

internal fun ReaderHook.clearSearchResultHighlight(viewModel: Any? = currentViewModelRef?.get()): Boolean {
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

internal fun ReaderHook.appendActiveSearchHighlightMark(original: List<*>, label: String? = null): List<Any>? {
    val activeMarks = activeTransientHighlightMarks()
    // 常态阅读（没在搜索、没在朗读、没有划词注入）时既没有要加的标记，原有列表里也不会有
    // 我们注入过的标记，直接返回 null 表示「不用改」。这个函数挂在 resolveMarksForPage /
    // resolveMarksForElement 上，按页、按元素调用，原先无条件走完 4 次链式 filterNot
    // 外加一次 filterNotNull，每次分配五个临时 List。
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

internal fun ReaderHook.scheduleSearchJumpVisibilityCorrection(receiver: Any?, viewModel: Any?, mark: Any) {
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

internal fun ReaderHook.isSearchHighlightOnCurrentVisiblePage(id: Long): Boolean {
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

internal fun ReaderHook.searchHighlightCorrectionDirection(): Boolean? {
    return SearchHighlightPlanner.correctionDirection(
        targetNumber = activeSearchHighlightPageNumber,
        currentNumber = currentVisiblePageNumber,
        targetKey = targetSearchHighlightPageKey(),
        currentKey = currentVisibleSearchPageKey(),
        activeVisibleMatches = activeSearchHighlightVisibleId == idOrNull(activeSearchHighlightMark),
    )
}

internal fun ReaderHook.currentVisibleSearchPageKey(): String? =
    currentVisiblePageSignature?.takeIf { it.isNotBlank() }
        ?: currentVisiblePageNumber?.let { "n=$it" }

internal fun ReaderHook.targetSearchHighlightPageKey(): String? =
    activeSearchHighlightPageSignature?.takeIf { it.isNotBlank() }
        ?: activeSearchHighlightPageNumber?.let { "n=$it" }

internal fun ReaderHook.createResolvedSearchHighlightMark(mark: Any): Any? =
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

internal fun ReaderHook.createSearchHighlightContentOverlay(
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

/**
 * 这条标记是不是模块注入的临时标记（搜索结果 / 朗读 / 划词）。
 *
 * 三种判断都是「取 id 再比一段区间」，分开调用要取 id 三次。这里只取一次，
 * 供 appendActiveSearchHighlightMark 的快速路径用——它按页、按元素被调用。
 */
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

internal fun ReaderHook.applySearchNavigationBarTheme(bar: View, colors: DialogColors) {
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

internal fun ReaderHook.applySearchNavigationButtonTheme(button: TextView, colors: DialogColors) {
    val context = button.context
    button.setTextColor(colors.primaryText)
    button.background = GradientDrawable().apply {
        setColor(colors.cardBackground)
        cornerRadius = dp(context, 15).toFloat()
        setStroke(dp(context, 1), colors.stroke)
    }
}

internal fun ReaderHook.searchNavigationButton(activity: Activity, label: String, colors: DialogColors): TextView =
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

internal fun ReaderHook.removeSearchNavigationBar() {
    val activity = searchNavigationBarActivityRef?.get() ?: activityProvider()
    searchNavigationBarRef = null
    searchNavigationBarActivityRef = null
    postRemoveTaggedViews(activity, SEARCH_NAV_BAR_TAG)
    maybeUnregisterSearchOverlayThemeCallbacks()
}

internal fun ReaderHook.ensureSearchIndexAsync(context: CatalogContext) {
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

internal fun ReaderHook.searchFullTextStreaming(
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

internal fun ReaderHook.appendSearchMatches(
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

internal fun ReaderHook.buildSearchDocuments(context: CatalogContext): List<SearchDocument> {
    val documents = ArrayList<SearchDocument>()
    forEachSearchDocument(context) { document ->
        documents.add(document)
        true
    }
    return documents
}

internal fun ReaderHook.forEachSearchDocument(
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

internal fun ReaderHook.indexedSearchText(raw: String, cfiBase: CfiBase?): IndexedSearchText {
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

internal fun ReaderHook.searchBodyXml(raw: String): String =
    "<body>${sanitizeXmlForSearch(bodyOnlyHtml(raw)).normalizeSearchVoidTags()}</body>"

internal fun ReaderHook.sanitizeXmlForSearch(raw: String): String =
    raw
        .replace(Regex("<!DOCTYPE[\\s\\S]*?>", RegexOption.IGNORE_CASE), "")
        .replace("&nbsp;", " ")
        .replace("&copy;", "(c)")
        .replace("&mdash;", "-")
        .replace("&ndash;", "-")
        .replace(Regex("&(?!amp;|lt;|gt;|quot;|apos;|#\\d+;|#x[0-9a-fA-F]+;)"), "&amp;")

internal fun ReaderHook.normalizeSearchNodeText(value: String): NormalizedNodeText {
    val start = value.indexOfFirst { it != ' ' && it != '\n' && it != '\r' && it != '\t' }
    if (start < 0) return NormalizedNodeText("", 0)
    val end = value.indexOfLast { it != ' ' && it != '\n' && it != '\r' && it != '\t' }
    return NormalizedNodeText(value.substring(start, end + 1), start)
}

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

internal fun ReaderHook.fullTextSearchJobKey(bookKey: String, keyword: String): String =
    "$bookKey\n${keyword.lowercase(Locale.ROOT)}"
