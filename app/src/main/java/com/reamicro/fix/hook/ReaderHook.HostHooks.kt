package com.reamicro.fix.hook

import com.reamicro.fix.core.hostNameStartsWith

import com.reamicro.fix.core.hostNameMatches
import com.reamicro.fix.core.hostClassOrNull
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.typeMatches

import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.online.OnlineReaderContextBridge
import com.reamicro.fix.settings.ReaderHighlightBookContext
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import java.lang.ref.WeakReference
import com.reamicro.fix.hook.reader.*

internal fun ReaderHook.hookReaderViewModel() {
    HookInstallReport.install(FEATURE_ID, "hookReaderViewModel") {
        val cls = classLoader.hostClass(READER_VIEW_MODEL_CLASS)
        installSelectionWindowHook(cls)
        XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (currentViewModelRef?.get() !== param.thisObject) {
                    invalidateOnDemandRefreshes()
                    cancelSelectionPagerConfirmation()
                }
                currentViewModelRef = WeakReference(param.thisObject)
                param.args?.firstOrNull {
                    it != null && HostSymbols.logicalClass(it.javaClass.name) == SESSION_CLASS
                }
                    ?.let { session ->
                        currentSessionRef = WeakReference(session)
                        initializeScrollCrashRecovery()
                }
                XposedBridge.log("$LOG_PREFIX ReaderViewModel created")
                scheduleRestorePersistedSearchOrigin("viewModel created")
            }
        })
        hookViewModelCleared(cls) { viewModel ->
            if (currentViewModelRef?.get() !== viewModel) return@hookViewModelCleared
            XposedBridge.log("$LOG_PREFIX ReaderViewModel cleared")
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
        }
        val readerUiIntentClass = classLoader.hostClass(READER_UI_INTENT_CLASS)
        val onIntentCallback = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (currentViewModelRef?.get() !== param.thisObject) return
                    val intent = param.args?.getOrNull(0) ?: return
                    if (replayingOnDemandJump.get() != true) {
                        val intercepted = when (HostSymbols.logicalClass(intent.javaClass.name)) {
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
                    if (HostSymbols.logicalClass(intent.javaClass.name) == "$READER_UI_INTENT_CLASS\$Scroll") {
                    currentScrollElement = callNoArg(intent, "getElement")
                        currentSearchPagerMode = 3
                    currentPageRef = null
                    currentPageStrong = null
                    return
                }
                if (HostSymbols.logicalClass(intent.javaClass.name) != "$READER_UI_INTENT_CLASS\$Statistics") return
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
                }
        }
        // R8 在不同版本保留不同的重载：2.3.3-b2 留下 onIntent(ReaderUiIntent)，而 2.3.3-b3 只留下
        // 父接口重载 onIntent(arch.UiIntent)。按「同名 + 单参 + 形参是 ReaderUiIntent 的父类型」
        // 收集，避免精确签名匹配失败导致整条 ReaderHook 链装不上。
        val onIntentMethods = cls.declaredMethods.filter { method ->
            method.hostNameMatches("onIntent") &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0].isAssignableFrom(readerUiIntentClass)
        }
        if (onIntentMethods.isEmpty()) {
            error("ReaderViewModel onIntent(${readerUiIntentClass.name}) not found on ${cls.name}")
        }
        onIntentMethods.forEach { XposedBridge.hookMethod(it, onIntentCallback) }
        XposedBridge.log(
            "$LOG_PREFIX ReaderViewModel onIntent(${onIntentMethods.joinToString { it.name }}) hook installed",
        )
    }
}

internal fun ReaderHook.hookScrollPagerCrashGuard() {
    HookInstallReport.install(FEATURE_ID, "hookScrollPagerCrashGuard") {
        val scrollPagerClass = classLoader.hostClass(SCROLL_PAGER_KT_CLASS)
        val methodName = HostSymbols.resolveMethodName(SCROLL_PAGER_KT_CLASS, "ScrollPager")
        val methods = scrollPagerClass.declaredMethods.filter {
            it.hostNameMatches(methodName) && it.parameterTypes.size >= 9
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
        val contentDomClass = classLoader.hostClass(CONTENT_DOM_CLASS)
        val methodName = HostSymbols.resolveMethodName(CONTENT_DOM_CLASS, "getRenderTextWidthDp")
        val methods = contentDomClass.declaredMethods.filter {
            it.hostNameMatches(methodName) && it.parameterTypes.isEmpty()
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
        val catalogClass = classLoader.hostClass(READER_CATALOG_CLASS)
        val methodName = HostSymbols.resolveMethodName(READER_CATALOG_CLASS, "ReaderCatalog")
        val methods = catalogClass.declaredMethods.filter {
            it.hostNameMatches(methodName) &&
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
        XposedBridge.log("$LOG_PREFIX reader catalog full-text search hook installed: ${methods.size}")
        if (HostSymbols.profileId == "2.3.3-b4") B4ReaderSearchEntryBridge(this).install()
    }
}

internal fun ReaderHook.hookHomeBookshelfScreen() {
    val targets = listOf(
        HOME_SCREEN_CLASS to "HomeScreen",
        BOOKSHELF_SCREEN_CLASS to "BookshelfScreen",
    )
    targets.forEach { (className, methodName) ->
        HookInstallReport.install(FEATURE_ID, "homeBookshelf.$methodName") {
            val cls = classLoader.hostClass(className)
            val resolvedName = HostSymbols.resolveMethodName(className, methodName)
            val methods = cls.declaredMethods.filter {
                it.hostNameStartsWith(methodName)
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
        val cls = classLoader.hostClass(READER_BOTTOM_BAR_CLASS)
        val methodName = HostSymbols.resolveMethodName(READER_BOTTOM_BAR_CLASS, "ReaderBottomBar")
        val methods = cls.declaredMethods.filter {
            it.hostNameMatches(methodName) && it.parameterTypes.size >= 7
        }
        if (methods.isEmpty()) error("ReaderBottomBar composable not found")
        methods.forEach { method ->
            method.isAccessible = true
            val composerIndex = method.parameterTypes.indexOfFirst {
                HostSymbols.logicalClass(it.name) == "androidx.compose.runtime.Composer"
            }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    beginReaderSearchBar(param.args?.getOrNull(0), param.args?.getOrNull(1))

                    param.args?.getOrNull(composerIndex)?.let { cacheThemeColors(it, captureSearchTheme = true) }

                    val status = param.args?.getOrNull(2)
                    val statusName = readerUiStatusName(status)
                    val menuVisible = statusName.isNotBlank() && statusName != "Reader"

                    if (menuVisible && ReaderAutoPageHook.isAutoPageEnabled()) {

                        val args = param.args ?: return
                        val backIdx = args.indexOfFirst { it != null &&
                            it.javaClass.interfaces.any { iface ->
                                HostSymbols.logicalClass(iface.name) == "kotlin.jvm.functions.Function0"
                            } }
                        if (backIdx >= 0) {
                            args[backIdx] = nativeFunction0 { ReaderAutoPageHook.toggleAutoPage() }
                        }
                    }
                    readerBottomMenuVisible = menuVisible
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    endReaderSearchBar()
                    if (!canRunFullTextSearch()) {
                        activityProvider()?.runOnUiThread {
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
                    activity.runOnUiThread {
                        updateSearchNavigationForBottomState(activity)
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
            readerBottomBarContentBinding(
                method.name,
                method.parameterTypes.map { HostSymbols.logicalClass(it.name) },
            )
                ?.let { method to it }
        }
        if (methods.isEmpty() && !HostSymbols.isIdentity) {
            // R8 删除静态 lambda 桥接后，作用域转到真实重组闭包，按捕获对象过滤合并分支。
            val content = classLoader.hostClass(
                "$READER_BOTTOM_BAR_CLASS\$\$ExternalSyntheticLambda19",
            )
            val receiverField = content.hostField("f\$0")
            val bookField = content.hostField("f\$1")
            val bookClass = classLoader.hostClass("app.zhendong.reamicro.data.db.entity.Book")
            val callback = content.hostMethod("invoke", Any::class.java, Any::class.java)
            XposedBridge.hookMethod(callback, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val closure = param.thisObject ?: return
                    val book = bookField.get(closure)
                    val receiver = receiverField.get(closure)
                    if (!bookClass.isInstance(book) || receiver !== currentViewModelRef?.get()) return
                    beginReaderSearchBar(receiver, book)
                    param.setObjectExtra("reamicro.bottom.scope", true)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.getObjectExtra("reamicro.bottom.scope") == true) endReaderSearchBar()
                }
            })
            XposedBridge.log("$LOG_PREFIX reader bottom search content closure hook installed")
            return@install
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
            val cls = classLoader.hostClass(ARROW_BACK_ICON_CLASS)
            val getArrowBack = HostSymbols.resolveMethodName(ARROW_BACK_ICON_CLASS, "getArrowBack")
            cls.declaredMethods.filter { it.name == getArrowBack && it.parameterTypes.size == 1 }.forEach { m ->
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

        val iconClass = classLoader.hostClass("androidx.compose.material3.IconKt")
        val iconMethod = HostSymbols.resolveMethodName("androidx.compose.material3.IconKt", "Icon-ww6aTOc")
        iconClass.declaredMethods.filter {
            it.name == iconMethod && it.parameterTypes.size == 7 &&
                HostSymbols.logicalClass(it.parameterTypes[0].name) ==
                "androidx.compose.ui.graphics.vector.ImageVector"
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

        if (!HostSymbols.isIdentity) {
            val clickable = classLoader.hostClass("androidx.compose.foundation.ClickableElement")
            XposedBridge.hookAllConstructors(clickable, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!readerBottomBarComposeScope.active) return
                    val constructor = param.method as java.lang.reflect.Constructor<*>
                    val index = constructor.parameterTypes.indexOfFirst {
                        typeMatches(it, "kotlin.jvm.functions.Function0")
                    }
                    val original = param.args?.getOrNull(index) ?: return
                    param.args[index] = bindReaderThemeClick(original)
                }
            })
        }
        runCatching {
            val clickableClass = classLoader.hostClass("androidx.compose.foundation.ClickableKt")
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
        val screenClass = classLoader.hostClass(READER_HIGHLIGHT_SCREEN_CLASS)
        val composerClass = classLoader.hostClass(COMPOSER_CLASS)

        val pageContentMethod = screenClass.declaredMethods.firstOrNull { method ->
            method.hostNameMatches("HighlightPageContent") &&
                typeMatches(method.parameterTypes.firstOrNull(), "java.util.List") &&
                method.parameterTypes.any { it == composerClass }
        } ?: error("HighlightPageContent main method not found")
        pageContentMethod.isAccessible = true
        XposedBridge.hookMethod(pageContentMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                highlightScreenEntryInjected.set(false)
            }
        })

        // SectionTitleLikeMore 是旧 UI API，b2/b3 的 ReaderHighlightScreenKt 已没有同名成员。
        // 缺省回退到同一屏的 HighlightPageContent：它每次组屏都会执行，且以 Composer 为参数，
        // 卡片会落在分页标签栏上方，与旧版插入位置一致。
        val sectionTitleMethod = screenClass.declaredMethods.firstOrNull { method ->
            method.hostNameMatches("SectionTitleLikeMore") &&
                method.parameterTypes.firstOrNull() == String::class.java &&
                method.parameterTypes.any { it == composerClass }
        }
        val entryMethod = sectionTitleMethod ?: pageContentMethod
        if (sectionTitleMethod == null) {
            XposedBridge.log("$LOG_PREFIX SectionTitleLikeMore 缺失，高亮页入口卡片迁移到 HighlightPageContent")
        }
        entryMethod.isAccessible = true
        val composerIndex = entryMethod.parameterTypes.indexOfFirst { it == composerClass }
        XposedBridge.hookMethod(entryMethod, object : XC_MethodHook() {
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
        val cls = classLoader.hostClass(READER_FAMILY_EPUB_CLASS)
        val clearMethods = cls.declaredMethods.filter {
            it.hostNameMatches("ReaderFamilyEpub") && it.parameterTypes.size == 5
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
            it.hostNameMatches("ReaderFamilyEpub\$lambda\$2") && it.parameterTypes.size == 6
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
        val controllerClass = classLoader.hostClass("org.epub.ui.EpubSelectionController")
        XposedBridge.hookAllConstructors(controllerClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                currentSelectionControllerRef = WeakReference(param.thisObject)
            }
        })
        controllerClass.declaredMethods
            .filter { method ->
                method.name.contains("Selection", ignoreCase = true) ||
                    method.hostNameMatches("selectedPayload") ||
                    method.hostNameMatches("selectedText")
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
        val bodyClass = classLoader.hostClass("org.epub.ui.BodyKt")
        val menuMethods = bodyClass.declaredMethods.filter {
            it.hostNameMatches("SelectionBubbleMenu") &&
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
                    val compact = if (settingsProvider().canUseCompactReaderSelectionMenu) {
                        compactSelectionMenuActions(next)
                    } else {
                        next
                    }
                    if (next.size != original.size || compact !== next) param.args[0] = compact
                    if (!HostSymbols.isIdentity && method.parameterTypes.size == 7 &&
                        method.parameterTypes[3] == Boolean::class.javaPrimitiveType &&
                        settingsProvider().canUseCompactReaderSelectionMenu
                    ) {
                        param.args[3] = true
                        param.args[6] = ((param.args[6] as? Int) ?: 0) and 0x1C00.inv()
                    }
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
    if (contentMethods.isNotEmpty()) {
        contentMethods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val actions = (param.args?.getOrNull(0) as? List<*>)?.filterNotNull().orEmpty()
                    val maxPerRow = selectionMenuMaxItemsPerRow(actions.size) ?: return
                    val tint = (param.args?.getOrNull(1) as? Number)?.toLong() ?: return
                    val composer = param.args?.getOrNull(2) ?: return
                    renderSelectionMenuReplacement(param, actions, tint, composer, maxPerRow)
                }
            })
        }
        XposedBridge.log("$LOG_PREFIX native SelectionBubbleMenu content hook installed: ${contentMethods.size}")
        return
    }

    // B4 删除了旧版 SelectionBubbleMenu$lambda 桥接，布局入口直接变成
    // SelectionBubbleMenu(List, boolean, int, boolean, Modifier, Composer, int)。
    // 仍在这里接管内容布局，否则原生 8 等分分支会把双字菜单标题裁成首字。
    val fallbackMethods = bodyClass.declaredMethods.filter { method ->
        method.hostNameMatches("SelectionBubbleMenu") &&
            method.parameterTypes.size == 7 &&
            List::class.java.isAssignableFrom(method.parameterTypes[0]) &&
            method.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
            method.parameterTypes[3] == Boolean::class.javaPrimitiveType
    }
    if (fallbackMethods.isEmpty()) {
        XposedBridge.log("$LOG_PREFIX SelectionBubbleMenu content lambda not found; native layout kept")
        return
    }
    fallbackMethods.forEach { method ->
        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val actions = (param.args?.getOrNull(0) as? List<*>)?.filterNotNull().orEmpty()
                val maxPerRow = selectionMenuMaxItemsPerRow(actions.size) ?: return
                val composer = param.args?.getOrNull(5) ?: return
                val tint = selectionMenuTint(composer)
                    ?: (latestThemeColors?.primaryText?.let { composeColorFromArgb(it) })
                    ?: return
                renderSelectionMenuReplacement(param, actions, tint, composer, maxPerRow)
            }
        })
    }
    XposedBridge.log("$LOG_PREFIX native SelectionBubbleMenu layout hook installed: ${fallbackMethods.size}")
}

private fun ReaderHook.renderSelectionMenuReplacement(
    param: XC_MethodHook.MethodHookParam,
    actions: List<Any>,
    tint: Long,
    composer: Any,
    maxPerRow: Int,
) {
    runCatching {
        renderWrappedSelectionMenu(actions, tint, composer, maxPerRow)
    }.onSuccess {
        param.result = targetUnit()
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX wrap native selection menu failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaderHook.hookCurrentEpub() {
    HookInstallReport.install(FEATURE_ID, "hookCurrentEpub") {
        val epubClass = classLoader.hostClass("app.zhendong.reamicro.data.epub.Epub")
        val readMethod = HostSymbols.resolveMethodName("app.zhendong.reamicro.data.epub.Epub", "read")
        epubClass.declaredMethods
            .filter { it.hostNameMatches(readMethod) }
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
        val containerName = "app.zhendong.reamicro.ui.reader.components.EpubContainerKt"
        val containerClass = classLoader.hostClass(containerName)
        val epubContainerMethod = HostSymbols.resolveMethodName(containerName, "EpubContainer")
        val methods = containerClass.declaredMethods.filter {
            it.hostNameMatches(epubContainerMethod) && it.parameterTypes.size >= 2
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
        val sharedStateClass = classLoader.hostClass(READER_SHARED_STATE_CLASS)
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
        val viewModelClass = classLoader.hostClass(READER_VIEW_MODEL_CLASS)
        val methodName = HostSymbols.resolveMethodName(
            READER_VIEW_MODEL_CLASS,
            "computeCatalogPrecomputations",
        )
        val methods = viewModelClass.declaredMethods.filter { method ->
            method.hostNameMatches(methodName) &&
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
    ReaderSearchContentBridge(this).install()
    hookSearchHighlightContentOverlays()
    if (HostSymbols.isIdentity) {
        hookSearchHighlightMarksArgument(
            className = "app.zhendong.reamicro.ui.reader.components.ScrollPagerKt",
            methodName = "resolveMarksForElement",
            marksIndex = 1,
            label = "ScrollResolve",
        )
    }
}

internal fun ReaderHook.hookSearchHighlightMarksArgument(
    className: String,
    methodName: String,
    marksIndex: Int,
    label: String,
) {
    HookInstallReport.install(FEATURE_ID, "hookSearchHighlightMarksArgument.$label") {
        val targetClass = classLoader.hostClass(className)
        val resolvedMethodName = HostSymbols.resolveMethodName(className, methodName)
        var count = 0
        targetClass.declaredMethods
            .filter { it.hostNameMatches(resolvedMethodName) && it.parameterTypes.size > marksIndex }
            .forEach { method ->
                method.isAccessible = true
                val composerIndex = method.parameterTypes.indexOfFirst {
                    HostSymbols.logicalClass(it.name) == "androidx.compose.runtime.Composer"
                }
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
        check(count > 0) { "$label marks input was not hooked" }
        XposedBridge.log("$LOG_PREFIX full-text search highlight $label hook installed count=$count")
    }
}

internal fun ReaderHook.hookSearchHighlightResolvedPage() {
    HookInstallReport.install(FEATURE_ID, "hookSearchHighlightResolvedPage") {
        val bodyClass = classLoader.hostClass("org.epub.ui.BodyKt")
        val methodName = HostSymbols.resolveMethodName("org.epub.ui.BodyKt", "resolveMarksForPage")
        val methods = bodyClass.declaredMethods.filter {
            it.hostNameMatches(methodName) && it.parameterTypes.size >= 2
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
        val contentClass = classLoader.hostClass("org.epub.ui.ContentKt")
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
