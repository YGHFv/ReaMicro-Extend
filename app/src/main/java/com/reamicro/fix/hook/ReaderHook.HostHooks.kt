package com.reamicro.fix.hook

import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.online.OnlineReaderContextBridge
import com.reamicro.fix.settings.ReaderHighlightBookContext
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.ref.WeakReference
import com.reamicro.fix.hook.reader.*

internal fun ReaderHook.hookReaderViewModel() {
    HookInstallReport.install(FEATURE_ID, "hookReaderViewModel") {
        val cls = classLoader.loadClass(READER_VIEW_MODEL_CLASS)
        installSelectionWindowHook(cls)
        XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (currentViewModelRef?.get() !== param.thisObject) {
                    invalidateOnDemandRefreshes()
                    cancelSelectionPagerConfirmation()
                }
                currentViewModelRef = WeakReference(param.thisObject)
                ensureReadAloudHighlightReceiver()
                requestReadAloudProgressSync(reason = "viewModel created")
                param.args?.firstOrNull { it?.javaClass?.name == SESSION_CLASS }
                    ?.let { session ->
                        currentSessionRef = WeakReference(session)
                        initializeScrollCrashRecovery()
                }
                XposedBridge.log("$LOG_PREFIX ReaderViewModel created")
                scheduleRestorePersistedSearchOrigin("viewModel created")
                scheduleRestorePersistedReadAloudProgress("viewModel created")
            }
        })
        hookViewModelCleared(cls) { viewModel ->
            if (currentViewModelRef?.get() !== viewModel) return@hookViewModelCleared
            XposedBridge.log("$LOG_PREFIX ReaderViewModel cleared")
            clearReadAloudHighlight(viewModel)
            currentViewModelRef = null
            currentSessionRef = null
            currentScrollElement = null
            clearScrollCrashPending("ReaderViewModel cleared")
            currentSelectionControllerRef = null
            bottomReadAloudReceiverRef = null
            bottomReadAloudBookRef = null
            activityProvider()?.runOnUiThread { removeReadAloudMenuButton() }
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
        }
        val readerUiIntentClass = classLoader.loadClass(READER_UI_INTENT_CLASS)
        XposedHelpers.findAndHookMethod(
            cls,
            "onIntent",
            readerUiIntentClass,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (currentViewModelRef?.get() !== param.thisObject) return
                    val intent = param.args?.getOrNull(0) ?: return
                    if (replayingOnDemandJump.get() != true) {
                        val intercepted = when (intent.javaClass.name) {
                            "$READER_UI_INTENT_CLASS\$JumpChapter" ->
                                interceptOnDemandJump(param, intent)
                            "$READER_UI_INTENT_CLASS\$JumpNextChapter" ->
                                interceptOnDemandChapterStep(param, intent, step = 1)
                            "$READER_UI_INTENT_CLASS\$JumpPreviousChapter" ->
                                interceptOnDemandChapterStep(param, intent, step = -1)
                            else -> false
                        }
                        if (intercepted) return
                    }
                    if (intent.javaClass.name == "$READER_UI_INTENT_CLASS\$Scroll") {
                    currentScrollElement = callNoArg(intent, "getElement")
                    currentPageRef = null
                    currentPageStrong = null
                    return
                }
                if (intent.javaClass.name != "$READER_UI_INTENT_CLASS\$Statistics") return
                currentScrollElement = null

                    if (selectionEditSaving.get()) {
                        param.result = null
                        return
                    }
                val page = callNoArg(intent, "getPage") ?: return
                if (filterSelectionPagerTransition(param.thisObject, page)) {
                    param.result = null
                    return
                }
                currentPageRef = WeakReference(page)
                currentPageStrong = page
                scheduleRestorePersistedSearchOrigin("visible page")
                scheduleRestorePersistedReadAloudProgress("visible page")
                val pageSignature = epubPageSignature(page)
                currentVisiblePageSignature = pageSignature
                currentVisiblePageNumber = epubPageNumber(page)

                scheduleOnDemandVisiblePageRefresh(param.thisObject, page)
                val statisticsKey = pageSignature
                    ?: "spine=${callNoArg(page, "getSpineIndex") ?: -1}|number=${currentVisiblePageNumber ?: -1}"
                if (statisticsKey == lastHandledReaderStatisticsKey) return
                lastHandledReaderStatisticsKey = statisticsKey
                publishOnlineReaderPage(page)
                XposedBridge.log(
                    "$LOG_PREFIX reader visible page " +
                        "number=${currentVisiblePageNumber ?: -1} sig=${pageSignature.orEmpty()}",
                )
                prefetchOnlineOnDemandChapters(page)
                if (!isDispatchingReadAloudStatistics) {
                    scheduleReadAloudRestartFromPage(page)
                }
                }
            },
        )
        XposedBridge.log(
            "$LOG_PREFIX ReaderViewModel onIntent(ReaderUiIntent) hook installed",
        )
    }
}

internal fun ReaderHook.hookScrollPagerCrashGuard() {
    HookInstallReport.install(FEATURE_ID, "hookScrollPagerCrashGuard") {
        val scrollPagerClass = classLoader.loadClass(SCROLL_PAGER_KT_CLASS)
        val methods = scrollPagerClass.declaredMethods.filter {
            it.name == "ScrollPager" && it.parameterTypes.size >= 9
        }
        check(methods.isNotEmpty()) { "hookScrollPagerCrashGuard: no matching host method" }
        methods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val session = currentSessionRef?.get()

                    if (session != null && restoreTranslateFlipStyleIfScrollCrashed(session, "ScrollPager")) {
                        param.result = null
                    }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val error = param.throwable
                    if (error != null) markScrollRenderFailure(error)
                    else if (scrollCrashMarkerOwnedByThisProcess) clearScrollCrashPending("successful scroll render")
                }
            })
        }
        XposedBridge.log("$LOG_PREFIX scroll crash guard hook installed count=${methods.size}")
    }
}

internal fun ReaderHook.hookContentDomRenderTextWidthFallback() {
    HookInstallReport.install(FEATURE_ID, "hookContentDomRenderTextWidthFallback") {
        val contentDomClass = classLoader.loadClass(CONTENT_DOM_CLASS)
        val methods = contentDomClass.declaredMethods.filter {
            it.name == "getRenderTextWidthDp" && it.parameterTypes.isEmpty()
        }
        check(methods.isNotEmpty()) { "hookContentDomRenderTextWidthFallback: no matching host method" }
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
                    if (status?.toString() != "Catalog") {
                        XposedBridge.log("$LOG_PREFIX catalog skip status='${status}'")
                        return
                    }
                    XposedBridge.log("$LOG_PREFIX catalog captured status='${status}'")
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
    }
}

internal fun ReaderHook.hookHomeBookshelfScreen() {
    val targets = listOf(
        HOME_SCREEN_CLASS to "HomeScreen",
        BOOKSHELF_SCREEN_CLASS to "BookshelfScreen",
    )
    targets.forEach { (className, methodName) ->
        HookInstallReport.install(FEATURE_ID, "homeBookshelf.$methodName") {
            val cls = classLoader.loadClass(className)
            val methods = cls.declaredMethods.filter {
                it.name == methodName || it.name.startsWith("$methodName-")
            }
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
        }
    }
}

internal fun ReaderHook.hookReaderBottomBar() {
    HookInstallReport.install(FEATURE_ID, "hookReaderBottomBar") {
        val cls = classLoader.loadClass(READER_BOTTOM_BAR_CLASS)
        val methods = cls.declaredMethods.filter {
            it.name == "ReaderBottomBar" && it.parameterTypes.size >= 7
        }
        if (methods.isEmpty()) error("ReaderBottomBar composable not found")
        methods.forEach { method ->
            method.isAccessible = true
            val composerIndex = method.parameterTypes.indexOfFirst { it.name == "androidx.compose.runtime.Composer" }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    beginReaderSearchBar(param.args?.getOrNull(0), param.args?.getOrNull(1))

                    param.args?.getOrNull(composerIndex)?.let { cacheThemeColors(it, captureSearchTheme = true) }

                    val status = param.args?.getOrNull(2)
                    val statusName = status?.javaClass?.simpleName.orEmpty()
                    val menuVisible = statusName.isNotBlank() && statusName != "Reader"

                    if (menuVisible && ReaderAutoPageHook.isAutoPageEnabled()) {

                        val args = param.args ?: return
                        val backIdx = args.indexOfFirst { it != null &&
                            it.javaClass.interfaces.any { iface -> iface.name == "kotlin.jvm.functions.Function0" } }
                        if (backIdx >= 0) {
                            args[backIdx] = nativeFunction0 { ReaderAutoPageHook.toggleAutoPage() }
                        }
                    }
                    val canShowReadAloudEntry = canShowReaderReadAloudEntry()
                    readerBottomMenuVisible = menuVisible
                    if (canShowReadAloudEntry && statusName == "Menu") {
                        param.args?.getOrNull(0)?.let { bottomReadAloudReceiverRef = WeakReference(it) }
                        param.args?.getOrNull(1)?.let { bottomReadAloudBookRef = WeakReference(it) }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    endReaderSearchBar()
                    if (!canRunFullTextSearch()) {
                        activityProvider()?.runOnUiThread {
                            readerBottomMenuVisible = false
                            bottomSearchReceiverRef = null
                            bottomSearchBookRef = null
                            bottomReadAloudReceiverRef = null
                            bottomReadAloudBookRef = null
                            removeSearchNavigationBar()
                            removeReadAloudMenuButton()
                        }
                        return
                    }
                    val receiver = param.args?.getOrNull(0)
                    val book = param.args?.getOrNull(1)
                    val status = param.args?.getOrNull(2)
                    val statusName = status?.javaClass?.simpleName.orEmpty()
                    val canShowReadAloudEntry = canShowReaderReadAloudEntry()
                    readerBottomMenuVisible = statusName.isNotBlank() && statusName != "Reader"
                    if (canShowReadAloudEntry && statusName == "Menu") {
                        bottomReadAloudReceiverRef = receiver?.let { WeakReference(it) }
                        bottomReadAloudBookRef = book?.let { WeakReference(it) }
                    }
                    val activity = activityProvider() ?: return
                    activity.runOnUiThread {
                        updateSearchNavigationForBottomState(activity)
                        removeReadAloudMenuButton()
                    }
                }
            })
        }
        hookReaderBottomBarContent(cls)
        XposedBridge.log("$LOG_PREFIX reader bottom search hook installed: ${methods.size}")
    }
}

internal fun ReaderHook.hookReaderBottomBarContent(bottomBarClass: Class<*>) {
    HookInstallReport.install(FEATURE_ID, "hookReaderBottomBarContent") {
        val methods = bottomBarClass.declaredMethods.mapNotNull { method ->
            readerBottomBarContentBinding(method.name, method.parameterTypes.map { it.name })
                ?.let { method to it }
        }
        check(methods.isNotEmpty()) { "ReaderBottomBar content callbacks not found" }
        methods.forEach { (method, binding) ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    beginReaderSearchBar(
                        param.args?.getOrNull(binding.receiverIndex),
                        param.args?.getOrNull(binding.bookIndex),
                    )
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    endReaderSearchBar()
                }
            })
        }
        XposedBridge.log("$LOG_PREFIX reader bottom search content hooks installed: ${methods.size}")
    }
}

internal fun ReaderHook.hookInlineSearchIcon() {
    HookInstallReport.install(FEATURE_ID, "hookInlineSearchIcon") {

        runCatching {
            val cls = classLoader.loadClass(ARROW_BACK_ICON_CLASS)
            cls.declaredMethods.filter { it.name == "getArrowBack" && it.parameterTypes.size == 1 }.forEach { m ->
                m.isAccessible = true
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!ReaderAutoPageHook.isAutoPageEnabled()) return
                        if (!readerBottomMenuVisible) return
                        nextIconIsAutoPage = true
                    }
                })
            }
        }

        val iconClass = classLoader.loadClass("androidx.compose.material3.IconKt")
        iconClass.declaredMethods.filter {
            it.name == "Icon-ww6aTOc" && it.parameterTypes.size == 7 &&
                it.parameterTypes[0].name == "androidx.compose.ui.graphics.vector.ImageVector"
        }.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!nextIconIsAutoPage && !readerBottomBarComposeScope.active) return
                    val args = param.args ?: return
                    val vectorName = callString(args.getOrNull(0), "getName")
                    if (nextIconIsAutoPage && vectorName.endsWith(".ArrowBack")) {
                        nextIconIsAutoPage = false
                        val icon = ReaderAutoPageHook.autoPageIcon(ReaderAutoPageHook.isAutoPageRunning()) ?: return
                        param.args?.set(0, icon)
                        return
                    }
                    replaceReaderThemeIcon(args, vectorName)
                }
            })
        }

        runCatching {
            val clickableClass = classLoader.loadClass("androidx.compose.foundation.ClickableKt")
            clickableClass.declaredMethods.filter {
                it.name.startsWith("clickable-") && it.name.endsWith("\$default") && it.parameterTypes.size == 8
            }.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!readerBottomBarComposeScope.active) return
                        val onClick = param.args?.getOrNull(5) ?: return
                        extractNavGraphScopeFromLambda(onClick)?.let { currentReaderNavGraphScopeRef = WeakReference(it) }
                        param.args[5] = bindReaderThemeClick(onClick)
                    }
                })
            }
        }
        XposedBridge.log("$LOG_PREFIX inline search icon replacement hook installed")
    }
}

internal fun ReaderHook.hookReaderHighlightScreenEntry() {
    HookInstallReport.install(FEATURE_ID, "hookReaderHighlightScreenEntry") {
        val screenClass = classLoader.loadClass(READER_HIGHLIGHT_SCREEN_CLASS)
        val composerClass = classLoader.loadClass(COMPOSER_CLASS)

        val pageContentMethod = screenClass.declaredMethods.firstOrNull { method ->
            method.name == "HighlightPageContent" &&
                method.parameterTypes.firstOrNull()?.name == "java.util.List" &&
                method.parameterTypes.any { it == composerClass }
        } ?: error("HighlightPageContent main method not found")
        pageContentMethod.isAccessible = true
        XposedBridge.hookMethod(pageContentMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                highlightScreenEntryInjected.set(false)
            }
        })

        val sectionTitleMethod = screenClass.declaredMethods.firstOrNull { method ->
            method.name == "SectionTitleLikeMore" &&
                method.parameterTypes.firstOrNull() == String::class.java &&
                method.parameterTypes.any { it == composerClass }
        } ?: error("SectionTitleLikeMore method not found")
        sectionTitleMethod.isAccessible = true
        val composerIndex = sectionTitleMethod.parameterTypes.indexOfFirst { it == composerClass }
        XposedBridge.hookMethod(sectionTitleMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val composer = param.args?.getOrNull(composerIndex) ?: return

                if (highlightScreenEntryInjected.get() == true) return
                highlightScreenEntryInjected.set(true)
                val bookKey = ReaderHighlightBookContext.bookKey
                val bookTitle = ReaderHighlightBookContext.bookTitle
                ReaMicroSettingsHook.renderReaderHighlightScreenEntryCard(
                    bookKey = bookKey,
                    bookTitle = bookTitle,
                    composer = composer,
                ) {
                    XposedBridge.log("$LOG_PREFIX highlight entry card clicked")
                    openReaderCompletionPlanPage()
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX reader highlight screen entry hook installed")
    }
}

internal fun ReaderHook.hookReaderHighlightRuleSheet() {
    HookInstallReport.install(FEATURE_ID, "hookReaderHighlightRuleSheet") {
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
                    val request = pendingReaderHighlightSheet

                    if (request == null) return
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
    }
}

internal fun ReaderHook.hookReaderFamilySheetHeight() {
    HookInstallReport.install(FEATURE_ID, "hookReaderFamilySheetHeight") {
        val heightMethod = composeMethod(SIZE_KT_CLASS, HEIGHT_METHOD, 2)
        XposedBridge.hookMethod(heightMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {

                val currentHeight = param.args?.getOrNull(1) as? Float ?: return
                val hostHeight = cachedUdp(HOST_READER_FAMILY_SHEET_HEIGHT_DP) ?: return
                if (currentHeight - hostHeight > 0.01f || hostHeight - currentHeight > 0.01f) return
                if (!isInReaderFamilySheetCompose()) return
                param.args[1] = cachedUdp(READER_RULE_SHEET_HEIGHT_DP) ?: return
            }
        })
        XposedBridge.log("$LOG_PREFIX reader family sheet height hook installed")
    }
}

internal fun ReaderHook.hookNativeSelectionController() {
    HookInstallReport.install(FEATURE_ID, "hookNativeSelectionController") {
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
    }
}

internal fun ReaderHook.hookNativeSelectionMenu() {
    HookInstallReport.install(FEATURE_ID, "hookNativeSelectionMenu") {
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
    }
}

internal fun ReaderHook.hookNativeSelectionMenuContent(bodyClass: Class<*>) {
    val contentMethods = bodyClass.declaredMethods.filter { method ->
        method.name.contains("SelectionBubbleMenu") &&
            method.name.contains("lambda") &&
            method.parameterTypes.size == 4 &&
            List::class.java.isAssignableFrom(method.parameterTypes[0]) &&
            method.parameterTypes[1] == Long::class.javaPrimitiveType
    }
    check(contentMethods.isNotEmpty()) { "SelectionBubbleMenu content lambda not found" }
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

internal fun ReaderHook.hookCurrentEpub() {
    HookInstallReport.install(FEATURE_ID, "hookCurrentEpub") {
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
        XposedBridge.log("$LOG_PREFIX current Epub hook installed")
    }
}

internal fun ReaderHook.hookCurrentEpubPage() {
    HookInstallReport.install(FEATURE_ID, "hookCurrentEpubPage") {
        val containerClass = classLoader.loadClass("app.zhendong.reamicro.ui.reader.components.EpubContainerKt")
        val methods = containerClass.declaredMethods.filter {
            it.name == "EpubContainer" && it.parameterTypes.size >= 2
        }
        check(methods.isNotEmpty()) { "EpubContainer not found" }
        methods.forEach { method ->
            val marksIndex = epubContainerMarksIndex(method.parameterTypes)
            check(marksIndex >= 0) { "EpubContainer marks parameter not found: $method" }
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val page = param.args?.getOrNull(1)
                    renderingEpubPage.set(page)
                    currentHighlightBookIdentity()?.let { (bookKey, bookTitle) ->
                        updateReaderHighlightBookContext(bookKey, bookTitle, "page rendered")
                    }
                    val args = param.args ?: return
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
    }
}

internal fun ReaderHook.hookReaderSharedStateMarks() {
    HookInstallReport.install(FEATURE_ID, "hookReaderSharedStateMarks") {
        val sharedStateClass = classLoader.loadClass(READER_SHARED_STATE_CLASS)
        XposedBridge.hookAllMethods(sharedStateClass, "getMarks", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {

                val original = (param.result as? List<*>) ?: return
                val nextMarks = appendActiveSearchHighlightMark(original, "ReaderSharedState") ?: return
                param.result = nextMarks
            }
        })
        XposedBridge.log("$LOG_PREFIX full-text search highlight ReaderSharedState hook installed")
    }
}

internal fun ReaderHook.hookReaderCatalogHighlightPrecomputations() {
    HookInstallReport.install(FEATURE_ID, "hookReaderCatalogHighlightPrecomputations") {
        val viewModelClass = classLoader.loadClass(READER_VIEW_MODEL_CLASS)
        val methods = viewModelClass.declaredMethods.filter { method ->
            method.name == "computeCatalogPrecomputations" &&
                method.parameterTypes.size >= 3 &&
                List::class.java.isAssignableFrom(method.parameterTypes[2])
        }
        check(methods.isNotEmpty()) { "hookReaderCatalogHighlightPrecomputations: no matching host method" }
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
    }
}

internal fun ReaderHook.hookSearchHighlightRenderInputs() {
    hookSearchHighlightMarksArgument(
        className = "org.epub.ui.BodyKt",
        methodName = "Body",
        marksIndex = 4,
        label = "Body",
    )
    hookSearchHighlightMarksArgument(
        className = "app.zhendong.reamicro.ui.reader.components.ScrollPagerKt",
        methodName = "ScrollPagerElement", marksIndex = 1, label = "ScrollElement",
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

internal fun ReaderHook.hookSearchHighlightMarksArgument(
    className: String,
    methodName: String,
    marksIndex: Int,
    label: String,
) {
    HookInstallReport.install(FEATURE_ID, "hookSearchHighlightMarksArgument.$label") {
        val targetClass = classLoader.loadClass(className)
        var count = 0
        targetClass.declaredMethods
            .filter { it.name == methodName && it.parameterTypes.size > marksIndex }
            .forEach { method ->
                method.isAccessible = true
                val composerIndex = method.parameterTypes.indexOfFirst { it.name == "androidx.compose.runtime.Composer" }
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args ?: return
                        if (label == "Body" || label == "ScrollElement") {

                            val owner = args.getOrNull(composerIndex)?.let { callNoArg(it, "getRecomposeScope") }
                                ?: (if (label == "Body") args.getOrNull(2) else args.firstOrNull()) ?: return
                            if (observeSearchPaint(owner) && composerIndex >= 0) {

                                val flags = args.getOrNull(composerIndex + 1) as? Int ?: 0
                                args[composerIndex + 1] = searchPaintChangedFlags(flags, marksIndex)
                            }
                        }
                        val originalMarks = args.getOrNull(marksIndex) as? List<*> ?: return
                        if (label == "ScrollResolve" && currentSearchPaintSession() != null &&
                            originalMarks.all { it == null || isSearchResultHighlightMark(it) }) {

                            param.result = appendSearchResolvedMarker(emptyList<Any>())
                            return
                        }
                        val nextMarks = appendActiveSearchHighlightMark(originalMarks, label) ?: return
                        args[marksIndex] = nextMarks
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (label == "ScrollResolve" && param.throwable == null) {
                            param.result = appendSearchResolvedMarker((param.result as? List<*>).orEmpty())
                        }
                    }
                })
                count++
            }
        XposedBridge.log("$LOG_PREFIX full-text search highlight $label hook installed count=$count")
    }
}

internal fun ReaderHook.hookSearchHighlightResolvedPage() {
    HookInstallReport.install(FEATURE_ID, "hookSearchHighlightResolvedPage") {
        val bodyClass = classLoader.loadClass("org.epub.ui.BodyKt")
        val methods = bodyClass.declaredMethods.filter {
            it.name == "resolveMarksForPage" && it.parameterTypes.size >= 2
        }
        check(methods.isNotEmpty()) { "hookSearchHighlightResolvedPage: no matching host method" }
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
    }
}

internal fun ReaderHook.hookSearchHighlightContentOverlays() {
    HookInstallReport.install(FEATURE_ID, "hookSearchHighlightContentOverlays") {
        val contentClass = classLoader.loadClass("org.epub.ui.ContentKt")
        val candidates = contentClass.declaredMethods.filter { method ->
            List::class.java.isAssignableFrom(method.returnType) &&
                method.parameterTypes.any { List::class.java.isAssignableFrom(it) } &&
                method.parameterTypes.any { it == Int::class.javaPrimitiveType }
        }
        val methods = candidates.filter(::isSearchHighlightContentOverlayMethod)
        check(methods.isNotEmpty()) { "hookSearchHighlightContentOverlays: no matching host method" }
        methods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args ?: return
                    val original = (args.getOrNull(1) as? List<*>)?.filterNotNull().orEmpty()
                    val session = currentSearchPaintSession()
                    param.setObjectExtra("reamicro.search.renderSession", session)

                    val next = original.filterNot(::isSearchResultHighlightMark).toMutableList()
                    activeTransientHighlightMarks().filterNot(::isSearchResultHighlightMark).forEach { mark ->
                        val resolved = createResolvedSearchHighlightMark(mark) ?: return@forEach
                        val id = transientHighlightResolvedMarkId(resolved) ?: return@forEach
                        if (next.none { transientHighlightResolvedMarkId(it) == id }) next += resolved
                    }
                    if (next != original) args[1] = next

                }

                override fun afterHookedMethod(param: MethodHookParam) {
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
                            args.getOrNull(0), args.getOrNull(2), (args.getOrNull(3) as? Number)?.toInt() ?: 0))
                    }

                    activeTransientHighlightMarks().filterNot(::isSearchResultHighlightMark).forEach { mark ->
                        val id = transientHighlightMarkId(mark) ?: return@forEach
                        if (current.any { searchResultHighlightOverlayMarkId(it) == id } ||
                            additions.any { searchResultHighlightOverlayMarkId(it) == id }) return@forEach
                        val forced = createSearchHighlightContentOverlay(
                            contentDom = args.getOrNull(0), visibleWindow = args.getOrNull(2),
                            renderedTextLength = (args.getOrNull(3) as? Number)?.toInt() ?: 0, mark = mark,
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
        }
        XposedBridge.log("$LOG_PREFIX full-text search projected overlay hook installed count=${methods.size}")
    }
}
