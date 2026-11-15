package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread

import com.reamicro.fix.core.HostReaderLifecycleBindings
import com.reamicro.fix.core.HostSelectionBindings
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.online.OnlineReaderContextBridge
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import com.reamicro.fix.hook.reader.*

internal fun ReaderHook.hookReaderViewModel() {
    HookInstallReport.install(FEATURE_ID, "hookReaderViewModel") {
        val host = requireNotNull(HostReaderLifecycleBindings.current) { "Reader lifecycle contract unavailable" }
        val selection = requireNotNull(HostSelectionBindings.current) { "Reader selection contract unavailable" }
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        fun capture(value: Any?, session: Any? = null) {
            if (value == null || !host.type("vm").isInstance(value)) return
            val changed = currentViewModelRef?.get() !== value
            if (changed) {
                cancelHighlightRefresh()
                invalidateOnDemandRefreshes()
                cancelSelectionPagerConfirmation()
                currentViewModelRef = WeakReference(value)
                currentSessionRef = null
            }
            val currentSession = session ?: currentSessionRef?.get() ?: runCatching { AccountHostRuntime.session() }.getOrNull()
            if (currentSession != null && host.type("session").isInstance(currentSession) && currentSessionRef?.get() !== currentSession) {
                currentSessionRef = WeakReference(currentSession)
                initializeScrollCrashRecovery()
            }
            if (changed) scheduleRestorePersistedSearchOrigin("reader captured")
        }
        fun logHighlight(intent: Any, source: String) {
            val payload = host.field("MarkUpsertHighlight.selection").get(intent) ?: return
            val quote = selection.field("payload.quote").get(payload) as? String
            HookRuntime.log("$LOG_PREFIX highlight $source quoteLength=${quote?.length ?: 0} " +
                "style=${host.field("MarkUpsertHighlight.style").getInt(intent)} " +
                "color=${host.field("MarkUpsertHighlight.color").get(intent)}")
        }
        try {
            host.vmConstructors().forEach { constructor ->
                val sessionIndex = constructor.parameterTypes.indexOf(host.type("session"))
                handles += HookRuntime.hookMethod(constructor, object : HookCallback() {
                    override fun after(param: Call) {
                        if (param.throwable == null) capture(param.thisObject, param.args?.getOrNull(sessionIndex))
                    }
                })
            }
            // Existing readers can be recovered on their next state read after a module reload.
            handles += HookRuntime.hookMethod(host.method("current"), object : HookCallback() {
                override fun before(param: Call) { capture(param.thisObject) }
            })
            handles += HookRuntime.hookMethod(host.method("cleared"), object : HookCallback() {
                override fun after(param: Call) {
                    if (currentViewModelRef?.get() !== param.thisObject) return
                    cancelHighlightRefresh()
                    cancelSelectionPagerConfirmation()
                    currentViewModelRef = null
                    currentSessionRef = null
                    currentScrollElement = null
                    clearScrollCrashPending("ReaderViewModel cleared")
                    currentSelectionControllerRef = null
                    currentEpubRef = null
                    currentPageRef = null
                    currentEpubStrong = null
                    currentPageStrong = null
                    OnlineReaderContextBridge.clear()
                    onDemandPrefetchInFlight.clear()
                    onDemandPrefetchRetryCount.clear()
                    onDemandRefreshPending.clear()
                    invalidateOnDemandRefreshes()
                    lastHandledReaderStatisticsKey = ""
                    lastOnDemandPrefetchSpineKey = ""
                    onDemandNormalizedHrefsCache = null
                    resetFullTextSearchState("ReaderViewModel cleared", removeOverlays = true)
                    updateReaderHighlightBookContext("", "", "ReaderViewModel cleared", requestRefresh = false)
                }
            })
            handles += HookRuntime.hookMethod(host.constructor("MarkUpsertHighlight"), object : HookCallback() {
                override fun after(param: Call) {
                    if (param.throwable == null) param.thisObject?.let { logHighlight(it, "created") }
                }
            })
            handles += HookRuntime.hookMethod(host.method("dispatch"), object : HookCallback() {
                override fun before(param: Call) {
                    capture(param.thisObject)
                    if (currentViewModelRef?.get() !== param.thisObject) return
                    val intent = param.args?.firstOrNull() ?: return
                    val name = host.intentName(intent)
                    if (name == "MarkUpsertHighlight") logHighlight(intent, "received")
                    if (replayingOnDemandJump.get() != true) {
                        val intercepted = when (name) {
                            "JumpChapter" -> interceptOnDemandJump(param, intent)
                            "JumpNextChapter" -> interceptOnDemandChapterStep(param, intent, step = 1)
                            "JumpPreviousChapter" -> interceptOnDemandChapterStep(param, intent, step = -1)
                            else -> false
                        }
                        if (intercepted) return
                    }
                    if (name == "Scroll") {
                        currentScrollElement = host.field("Scroll.element").get(intent)
                        currentSearchPagerMode = 3
                        currentPageRef = null
                        currentPageStrong = null
                        return
                    }
                    if (name != "Statistics") return
                    currentScrollElement = null
                    if (selectionEditSaving.get()) { param.result = null; return }
                    val page = host.field("Statistics.page").get(intent) ?: return
                    if (filterSelectionPagerTransition(param.thisObject, page)) { param.result = null; return }
                    currentPageRef = WeakReference(page)
                    currentPageStrong = page
                    scheduleRestorePersistedSearchOrigin("visible page")
                    val pageSignature = epubPageSignature(page)
                    currentVisiblePageSignature = pageSignature
                    currentVisiblePageNumber = epubPageNumber(page)
                    scheduleOnDemandVisiblePageRefresh(param.thisObject, page)
                    val statisticsKey = pageSignature
                        ?: "spine=${epubPageSpine(page) ?: -1}|number=${currentVisiblePageNumber ?: -1}"
                    if (statisticsKey == lastHandledReaderStatisticsKey) return
                    lastHandledReaderStatisticsKey = statisticsKey
                    publishOnlineReaderPage(page)
                    prefetchOnlineOnDemandChapters(page)
                }
            })
            handles += HookRuntime.hookMethod(host.method("saveMark"), object : HookCallback() {
                override fun after(param: Call) {
                    HookRuntime.log("$LOG_PREFIX saveMark result=${param.result?.javaClass?.name ?: "null"} " +
                        "throwable=${param.throwable?.javaClass?.name}")
                }
            })
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() } }
            throw error
        }
        HookRuntime.deoptimize(host.method("dispatch"))
        HookInstallReport.install(FEATURE_ID, "selectionWindow") { installSelectionWindowHook() }
        HookRuntime.log("$LOG_PREFIX reader lifecycle hooks installed")
    }
}

internal fun ReaderHook.hookScrollPagerCrashGuard() {
    HookInstallReport.install(FEATURE_ID, "hookScrollPagerCrashGuard") {
        val host = requireNotNull(HostReaderLifecycleBindings.current)
        val methods = listOf(host.method("scroll"))
        methods.forEach { method ->
            method.isAccessible = true
            HookRuntime.hookMethod(method, object : HookCallback() {
                override fun before(param: Call) {
                    val session = currentSessionRef?.get()

                    if (session != null && restoreTranslateFlipStyleIfScrollCrashed(session, "ScrollPager")) {
                        param.result = null
                    }
                }
                override fun after(param: Call) {
                    val error = param.throwable
                    if (error != null) markScrollRenderFailure(error)
                    else if (scrollCrashMarkerOwnedByThisProcess) clearScrollCrashPending("successful scroll render")
                }
            })
        }
        HookRuntime.log("$LOG_PREFIX scroll crash guard hook installed count=${methods.size}")
    }
}

internal fun ReaderHook.hookContentDomRenderTextWidthFallback() {
    HookInstallReport.install(FEATURE_ID, "hookContentDomRenderTextWidthFallback") {
        val host = requireNotNull(HostReaderLifecycleBindings.current)
        val methods = listOf(host.method("width"))
        methods.forEach { method ->
            method.isAccessible = true
            HookRuntime.hookMethod(method, object : HookCallback() {
                override fun after(param: Call) {
                    val error = param.throwable ?: return
                    if (!isUninitializedContentDomParent(error)) return

                    val fallback = fallbackRenderTextWidthDp(param.thisObject) ?: return
                    param.result = fallback
                    HookRuntime.log("$LOG_PREFIX ContentDom parent fallback render width=$fallback")
                }
            })
        }
        HookRuntime.log("$LOG_PREFIX ContentDom render width fallback hook installed count=${methods.size}")
    }
}

internal fun ReaderHook.installNativeSelectionHooks() {
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

internal fun ReaderHook.hookReaderCatalog() {
    HookInstallReport.install(FEATURE_ID, "hookReaderCatalog") {
        val symbols = requireNotNull(com.reamicro.fix.core.HostReaderMenuBindings.current) { "Reader catalog contract unavailable" }
        val methods = listOf(symbols.method("catalog"))
        methods.forEach { method ->
            method.isAccessible = true
            HookRuntime.hookMethod(method, object : HookCallback() {
                override fun before(param: Call) {
                    val status = param.args?.getOrNull(1)
                    param.args?.let { args ->
                        val composer = args.getOrNull(args.size - 2)
                        if (composer != null) cacheThemeColors(composer)
                    }
                    if (!canRunFullTextSearch()) {
                        activityProvider()?.runOnModuleUiThread {
                            clearSearchOverlays(clearNavigationState = true)
                        }
                        return
                    }
                    if (status?.toString() != "Catalog") {
                        HookRuntime.log("$LOG_PREFIX catalog skip status='${status}'")
                        return
                    }
                    HookRuntime.log("$LOG_PREFIX catalog captured status='${status}'")
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
                    updateReaderHighlightBookContext(bookKey(context), bookTitle(context), "catalog context")
                    injectSearchHighlightIntoReaderCatalog(param)
                    scheduleRestorePersistedSearchOrigin("catalog context")
                }
            })
        }
        HookRuntime.log("$LOG_PREFIX reader catalog full-text search hook installed: ${methods.size}")
    }
}

internal fun ReaderHook.hookHomeBookshelfScreen() {
    for (role in listOf("home", "bookshelf")) {
        HookInstallReport.install(FEATURE_ID, "homeBookshelf.$role") {
            val host = requireNotNull(HostReaderLifecycleBindings.current)
            HookRuntime.hookMethod(host.method(role), object : HookCallback() {
                override fun before(param: Call) { handleHomeBookshelfRendered(role) }
            })
        }
    }
}

internal fun ReaderHook.hookReaderBottomBar() {
    HookInstallReport.install(FEATURE_ID, "readerMenuSearch") { HostReaderSearchEntryBridge(this).install() }
    HookInstallReport.install(FEATURE_ID, "hookReaderBottomBar") {
        val symbols = requireNotNull(com.reamicro.fix.core.HostReaderMenuBindings.current) { "Reader bottom bar contract unavailable" }
        val methods = listOf(symbols.method("bottomBar"))
        methods.forEach { method ->
            method.isAccessible = true
            val composerIndex = method.parameterTypes.indexOf(symbols.method("icon").parameterTypes[4])
            val function0 = symbols.method("clickable").parameterTypes[4]
            val backIndex = method.parameterTypes.indexOf(function0)
            HookRuntime.hookMethod(method, object : HookCallback() {
                override fun before(param: Call) {
                    beginReaderSearchBar(param.args?.getOrNull(0), param.args?.getOrNull(1))

                    param.args?.getOrNull(composerIndex)?.let {
                        cacheThemeColors(it, captureSearchTheme = true)
                        // The caller's native restart scope must also rebuild the back callback.
                        ReaderAutoPageHook.observeAutoPageState()
                    }

                    val status = param.args?.getOrNull(2)
                    val statusName = readerUiStatusName(status)
                    val menuVisible = statusName.isNotBlank() && statusName != "Reader"

                    if (menuVisible && ReaderAutoPageHook.isAutoPageEnabled()) {

                        val args = param.args ?: return
                        val activity = activityProvider()?.takeUnless { it.isFinishing || it.isDestroyed }
                        val identity = activity?.let { AccountHostRuntime.storageSnapshot(it) }
                        if (backIndex >= 0 && activity != null && identity != null) {
                            val activityRef = WeakReference(activity)
                            val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
                            args[backIndex] = ui.callback(function0) {
                                val ownerActivity = activityRef.get() ?: return@callback
                                if (activityProvider() !== ownerActivity || ownerActivity.isFinishing ||
                                    ownerActivity.isDestroyed || !AccountHostRuntime.isCurrentStorage(identity, ownerActivity) ||
                                    !ReaderAutoPageHook.isAutoPageEnabled()) return@callback
                                runCatching {
                                    AccountHostRuntime.withCurrentStorage(identity, ownerActivity) {
                                        if (activityProvider() === ownerActivity && !ownerActivity.isFinishing &&
                                            !ownerActivity.isDestroyed && ReaderAutoPageHook.isAutoPageEnabled()) {
                                            ReaderAutoPageHook.toggleAutoPage()
                                        }
                                    }
                                }.onFailure {
                                    if (activityProvider() === ownerActivity &&
                                        AccountHostRuntime.isCurrentStorage(identity, ownerActivity)) {
                                        HookRuntime.logError("$LOG_PREFIX auto-page shortcut update failed", it)
                                    }
                                }
                            }
                        }
                    }
                    readerBottomMenuVisible = menuVisible
                }

                override fun after(param: Call) {
                    endReaderSearchBar()
                    if (!canRunFullTextSearch()) {
                        activityProvider()?.runOnModuleUiThread {
                            readerBottomMenuVisible = false
                            bottomSearchReceiverRef = null
                            bottomSearchBookRef = null
                            removeSearchNavigationBar()
                        }
                        return
                    }
                    val status = param.args?.getOrNull(2)
                    val statusName = readerUiStatusName(status)
                    readerBottomMenuVisible = statusName.isNotBlank() && statusName != "Reader"
                    val activity = activityProvider() ?: return
                    activity.runOnModuleUiThread {
                        updateSearchNavigationForBottomState(activity)
                    }
                }
            })
        }
        hookReaderBottomBarContent()
        HookRuntime.log("$LOG_PREFIX reader bottom search hook installed: ${methods.size}")
    }
}

internal fun ReaderHook.hookReaderBottomBarContent() {
    HookInstallReport.install(FEATURE_ID, "hookReaderBottomBarContent") {
        val symbols = requireNotNull(com.reamicro.fix.core.HostReaderMenuBindings.current) { "Reader bottom content contract unavailable" }
        HookRuntime.hookMethod(symbols.method("bottomContent"), object : HookCallback() {
            override fun before(param: Call) {
                val callback = param.thisObject ?: return
                if (!symbols.bottomMatches(callback)) return
                val receiver = symbols.field("bottom.receiver").get(callback) ?: return
                if (receiver !== currentViewModelRef?.get()) return
                val book = symbols.field("bottom.book").get(callback) ?: return
                if (!symbols.type("book").isInstance(book)) return
                beginReaderSearchBar(receiver, book)
                param.setObjectExtra("reamicro.bottom.scope", true)
                readerBottomMenuVisible = readerUiStatusName(symbols.field("bottom.status").get(callback)).let { it.isNotBlank() && it != "Reader" }
                param.args.getOrNull(1)?.let {
                    cacheThemeColors(it, captureSearchTheme = true)
                    // Native lambda Function3 runs after its restart scope has opened.
                    ReaderAutoPageHook.observeAutoPageState()
                }
            }
            override fun after(param: Call) {
                if (param.getObjectExtra("reamicro.bottom.scope") != true) return
                endReaderSearchBar()
                activityProvider()?.let { activity -> activity.runOnModuleUiThread { updateSearchNavigationForBottomState(activity) } }
            }
        })
        HookRuntime.deoptimize(symbols.method("bottomContent"))
        HookRuntime.deoptimize(symbols.method("bottomBar"))
    }
}
internal fun ReaderHook.hookInlineSearchIcon() {
    HookInstallReport.install(FEATURE_ID, "hookInlineSearchIcon") {
        val symbols = requireNotNull(com.reamicro.fix.core.HostReaderMenuBindings.current) { "Reader icon contract unavailable" }
        val vectorNameField = symbols.field("vectorName")
        HookRuntime.hookMethod(symbols.method("icon"), object : HookCallback() {
            override fun before(param: Call) {
                if (!readerBottomBarComposeScope.active) return
                val args = param.args
                val vector = args.getOrNull(0) ?: return
                val vectorName = vectorNameField.get(vector) as? String ?: return
                if (readerBottomMenuVisible && ReaderAutoPageHook.isAutoPageEnabled() && vectorName.endsWith(".ArrowBack")) {
                    args[0] = ReaderAutoPageHook.autoPageIcon(ReaderAutoPageHook.isAutoPageRunning()) ?: return
                    args[5] = searchPaintChangedFlags(args[5] as? Int ?: 0, 0)
                    return
                }
                replaceReaderThemeIcon(args, vectorName)
            }
        })
    }
}
internal fun ReaderHook.hookReaderHighlightScreenEntry() {
    HookInstallReport.install(FEATURE_ID, "hookReaderHighlightScreenEntry") { ReaderHighlightUiBridge(this).installEntry() }
}

internal fun ReaderHook.hookReaderHighlightRuleSheet() {
    HookInstallReport.install(FEATURE_ID, "hookReaderHighlightRuleSheet") { ReaderHighlightUiBridge(this).installRules() }
}

internal fun ReaderHook.hookReaderFamilySheetHeight() {
    HookInstallReport.install(FEATURE_ID, "hookReaderFamilySheetHeight") { ReaderHighlightUiBridge(this).installHeight() }
}

internal fun ReaderHook.hookNativeSelectionController() {
    HookInstallReport.install(FEATURE_ID, "hookNativeSelectionController") {
        val symbols = requireNotNull(com.reamicro.fix.core.HostSelectionBindings.current) { "Selection contract unavailable" }
        val handles = mutableListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            handles += HookRuntime.hookMethod(symbols.constructor("controller"), object : HookCallback() {
                override fun after(param: Call) {
                    if (param.throwable == null) currentSelectionControllerRef = WeakReference(param.thisObject)
                }
            })
            for (key in listOf("begin", "selection", "payload", "text", "clear")) {
                handles += HookRuntime.hookMethod(symbols.method(key), object : HookCallback() {
                    override fun before(param: Call) {
                        currentSelectionControllerRef = WeakReference(param.thisObject)
                    }
                })
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
        HookRuntime.log("$LOG_PREFIX native selection controller installed from current APK")
    }
}

internal fun ReaderHook.hookNativeSelectionMenu() {
    HookInstallReport.install(FEATURE_ID, "hookNativeSelectionMenu") {
        val symbols = requireNotNull(com.reamicro.fix.core.HostSelectionBindings.current) { "Selection menu contract unavailable" }
        val handles = mutableListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            handles += HookRuntime.hookMethod(symbols.method("bubble"), object : HookCallback() {
                override fun before(param: Call) {
                    val original = param.args[0] as? List<*> ?: return
                    if (original.any { !symbols.type("action").isInstance(it) }) return
                    val next = ArrayList(original.filterNotNull())
                    val fallbackIcon = next.firstOrNull()?.let(symbols::icon)
                    if (canShowReaderDictionary() && next.none { symbols.title(it) == "词典" }) {
                        fallbackIcon?.let(::createNativeDictionaryAction)?.let(next::add)
                    }
                    if (canEditReaderSelection() && next.none { symbols.title(it) == "编辑" }) {
                        (editImageVector() ?: fallbackIcon)?.let(::createNativeEditAction)?.let(next::add)
                    }
                    if (canHighlightReaderSelection()) {
                        val index = next.indexOfFirst { symbols.title(it) == "高亮" }
                        val icon = next.getOrNull(index)?.let(symbols::icon) ?: fallbackIcon
                        icon?.let(::createNativeHighlightAction)?.let {
                            if (index >= 0) next[index] = it else next.add(it)
                        }
                    }
                    val actions = if (settingsProvider().canUseCompactReaderSelectionMenu) compactSelectionMenuActions(next) else next
                    if (actions.size != original.size || actions.indices.any { actions[it] !== original[it] }) param.args[0] = actions
                    if (settingsProvider().canUseCompactReaderSelectionMenu || selectionMenuMaxItemsPerRow(actions.size) != null) {
                        param.args[3] = true
                        param.args[6] = (param.args[6] as Int) and 0x1C00.inv()
                    }
                }
            })
            for (role in listOf("floating", "inline")) {
                val method = symbols.method("$role.content")
                handles += HookRuntime.hookMethod(method, object : HookCallback() {
                    override fun before(param: Call) {
                        val closure = param.thisObject ?: return
                        if (!symbols.matches(role, closure)) return
                        val values = symbols.field("$role.actions").get(closure) as? List<*> ?: return
                        if (values.any { !symbols.type("action").isInstance(it) }) return
                        val actions = values.filterNotNull()
                        val maxPerRow = selectionMenuMaxItemsPerRow(actions.size) ?: return
                        val composer = param.args[0] ?: return
                        val tint = symbols.field("$role.tint").getLong(closure)
                        val arrow = if (role == "floating") symbols.field("floating.arrow").getBoolean(closure) else null
                        renderSelectionMenuReplacement(param, actions, tint, composer, maxPerRow, arrow)
                    }
                })
                HookRuntime.deoptimize(method)
            }
            HookRuntime.deoptimize(symbols.method("bubble"))
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
        HookRuntime.log("$LOG_PREFIX native selection menu and both content closures installed")
    }
}

private fun ReaderHook.renderSelectionMenuReplacement(
    param: HookCallback.Call,
    actions: List<Any>,
    tint: Long,
    composer: Any,
    maxPerRow: Int,
    bubbleArrowOnTop: Boolean? = null,
) {
    runCatching {
        renderWrappedSelectionMenu(actions, tint, composer, maxPerRow, bubbleArrowOnTop)
    }.onSuccess {
        param.result = targetUnit()
    }.onFailure {
        HookRuntime.log("$LOG_PREFIX wrap native selection menu failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaderHook.hookCurrentEpub() {
    HookInstallReport.install(FEATURE_ID, "hookCurrentEpub") {
        val host = requireNotNull(HostReaderLifecycleBindings.current)
        listOf(host.method("read"), host.method("readElements")).forEach { method ->
                method.isAccessible = true
                HookRuntime.hookMethod(method, object : HookCallback() {
                    override fun before(param: Call) {
                        val previousEpub = currentEpubRef?.get()
                        if (previousEpub != null && previousEpub !== param.thisObject) {
                            val previousDirectory = epubDirectory(previousEpub)
                            val nextDirectory = epubDirectory(param.thisObject)

                            currentPageRef = null
                            currentPageStrong = null
                            currentVisiblePageSignature = null
                            currentVisiblePageNumber = null
                            lastHandledReaderStatisticsKey = ""
                            lastOnDemandPrefetchSpineKey = ""
                            onDemandNormalizedHrefsCache = null
                            if (
                                previousDirectory.isBlank() ||
                                nextDirectory.isBlank() ||
                                previousDirectory != nextDirectory
                            ) {
                                OnlineReaderContextBridge.clear()
                                resetFullTextSearchState("epub changed", removeOverlays = true)
                            }
                        }
                        currentEpubRef = WeakReference(param.thisObject)
                        currentEpubStrong = param.thisObject
                        OnlineReaderContextBridge.updateBook(currentEpubRoot())
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
        HookRuntime.log("$LOG_PREFIX current Epub hook installed")
    }
}

internal fun ReaderHook.hookCurrentEpubPage() {
    HookInstallReport.install(FEATURE_ID, "hookCurrentEpubPage") {
        val symbols = requireNotNull(com.reamicro.fix.core.HostReaderRenderBindings.current)
        val method = symbols.method("container")
        val marksIndex = symbols.index("container.marks")
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                param.setObjectExtra("reamicro.render.previousPage", renderingEpubPage.get())
                renderingEpubPage.set(param.args[symbols.index("container.page")])
                currentHighlightBookIdentity()?.let { (bookKey, bookTitle) ->
                    updateReaderHighlightBookContext(bookKey, bookTitle, "page rendered")
                }
                val original = param.args[marksIndex] as? List<*> ?: return
                appendActiveSearchHighlightMark(original, "EpubContainer")?.let { param.args[marksIndex] = it }
            }
            override fun after(param: Call) {
                val previous = param.getObjectExtra("reamicro.render.previousPage")
                if (previous == null) renderingEpubPage.remove() else renderingEpubPage.set(previous)
            }
        })
        HookRuntime.log("$LOG_PREFIX current EpubPage hook installed")
    }
}
internal fun ReaderHook.hookReaderSharedStateMarks() {
    HookInstallReport.install(FEATURE_ID, "hookReaderSharedStateMarks") {
        val symbols = requireNotNull(com.reamicro.fix.core.HostReaderRenderBindings.current)
        val index = symbols.index("shared.marks")
        HookRuntime.hookMethod(symbols.constructor("shared"), object : HookCallback() {
            override fun before(param: Call) {
                val original = param.args[index] as? List<*> ?: return
                appendActiveSearchHighlightMark(original, "ReaderSharedState")?.let { param.args[index] = it }
            }
        })
        HookRuntime.log("$LOG_PREFIX full-text search highlight ReaderSharedState constructor hook installed")
    }
}
internal fun ReaderHook.hookReaderCatalogHighlightPrecomputations() {
    HookInstallReport.install(FEATURE_ID, "hookReaderCatalogHighlightPrecomputations") {
        val symbols = requireNotNull(com.reamicro.fix.core.HostReaderRenderBindings.current)
        val index = symbols.index("catalog.marks")
        HookRuntime.hookMethod(symbols.method("catalog"), object : HookCallback() {
            override fun before(param: Call) {
                val original = param.args[index] as? List<*> ?: return
                appendActiveSearchHighlightMark(original, "CatalogPrecompute")?.let { param.args[index] = it }
            }
        })
        HookRuntime.log("$LOG_PREFIX full-text search highlight CatalogPrecompute hook installed")
    }
}
internal fun ReaderHook.hookSearchHighlightRenderInputs() {
    hookSearchHighlightMarksArgument("body", "Body")
    hookSearchHighlightMarksArgument("scroll", "ScrollElement")
    ReaderSearchContentBridge(this).install()
    hookSearchHighlightContentOverlays()
}
private fun ReaderHook.hookSearchHighlightMarksArgument(role: String, label: String) {
    HookInstallReport.install(FEATURE_ID, "hookSearchHighlightMarksArgument.$label") {
        val symbols = requireNotNull(com.reamicro.fix.core.HostReaderRenderBindings.current)
        val marksIndex = symbols.index("$role.marks")
        val composerIndex = symbols.index("$role.composer")
        val method = symbols.method(role)
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                val args = param.args
                val owner = args[composerIndex]?.let { symbols.method("scope").invoke(it) } ?: args.firstOrNull()
                if (owner != null && observeSearchPaint(owner)) {
                    args[composerIndex + 1] = searchPaintChangedFlags(args[composerIndex + 1] as Int, marksIndex)
                }
                val original = args[marksIndex] as? List<*> ?: return
                appendActiveSearchHighlightMark(original, label)?.let { args[marksIndex] = it }
            }
        })
        HookRuntime.log("$LOG_PREFIX full-text search highlight $label hook installed")
    }
}
internal fun ReaderHook.hookSearchHighlightContentOverlays() {
    HookInstallReport.install(FEATURE_ID, "hookSearchHighlightContentOverlays") {
        val symbols = requireNotNull(com.reamicro.fix.core.HostReaderRenderBindings.current)
        val marksIndex = symbols.index("overlay.marks")
        val domIndex = symbols.index("overlay.dom")
        val windowIndex = symbols.index("overlay.window")
        val lengthIndex = symbols.index("overlay.length")
        val method = symbols.method("overlay")
        HookRuntime.hookMethod(method, object : HookCallback() {
            override fun before(param: Call) {
                val args = param.args ?: return
                val original = (args.getOrNull(marksIndex) as? List<*>)?.filterNotNull().orEmpty()
                val session = currentSearchPaintSession()
                param.setObjectExtra("reamicro.search.renderSession", session)

                val next = original.filterNot(::isSearchResultHighlightMark).toMutableList()
                activeTransientHighlightMarks().filterNot(::isSearchResultHighlightMark).forEach { mark ->
                    val resolved = createResolvedSearchHighlightMark(mark) ?: return@forEach
                    val id = transientHighlightResolvedMarkId(resolved) ?: return@forEach
                    if (next.none { transientHighlightResolvedMarkId(it) == id }) next += resolved
                }
                if (next != original) args[marksIndex] = next

            }

            override fun after(param: Call) {
                if (param.throwable != null) return
                val original = (param.result as? List<*>)?.filterNotNull().orEmpty()
                val args = param.args ?: return
                val renderedSession = param.getObjectExtra("reamicro.search.renderSession") as? ReaderSearchHighlightSession
                fun isSearchOverlay(overlay: Any) = searchResultHighlightOverlayMarkId(overlay)?.let {
                    com.reamicro.fix.reader.SearchHighlightPlanner.isHighlightId(it,
                        SEARCH_HIGHLIGHT_MARK_ID_BASE, SEARCH_HIGHLIGHT_MARK_ID_RANGE)
                } == true
                val current = if (original.any(::isSearchOverlay)) original.filterNot(::isSearchOverlay) else original
                val additions = ArrayList<Any>()
                if (com.reamicro.fix.reader.SearchHighlightSet.sameGeneration(renderedSession, currentSearchPaintSession())) {
                    additions.addAll(renderedSession!!.paintOverlays(this@hookSearchHighlightContentOverlays,
                        args.getOrNull(domIndex), args.getOrNull(windowIndex), (args.getOrNull(lengthIndex) as? Number)?.toInt() ?: 0))
                }

                activeTransientHighlightMarks().filterNot(::isSearchResultHighlightMark).forEach { mark ->
                    val id = transientHighlightMarkId(mark) ?: return@forEach
                    if (current.any { searchResultHighlightOverlayMarkId(it) == id } ||
                        additions.any { searchResultHighlightOverlayMarkId(it) == id }) return@forEach
                    val forced = createSearchHighlightContentOverlay(
                        contentDom = args.getOrNull(domIndex), visibleWindow = args.getOrNull(windowIndex),
                        renderedTextLength = (args.getOrNull(lengthIndex) as? Number)?.toInt() ?: 0, mark = mark,
                    ) ?: return@forEach
                    additions += forced
                }
                if (current !== original || additions.isNotEmpty()) {
                    param.result = ArrayList<Any>(current.size + additions.size).apply {
                        addAll(current); addAll(additions)
                    }
                }
            }
        })
        HookRuntime.deoptimize(symbols.method("content"))
        HookRuntime.log("$LOG_PREFIX full-text search projected overlay hook installed")
    }
}
