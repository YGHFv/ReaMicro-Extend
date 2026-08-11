package com.reamicro.fix.hook

import com.reamicro.fix.online.OnlineReaderContextBridge
import com.reamicro.fix.settings.ReaderHighlightBookContext
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import com.reamicro.fix.hook.reader.*

// ReaderHook 的宿主 hook 安装簇。
//
// 所有 hookXxx()：挂到阅读页的 ViewModel、目录、底栏、选择控制器等宿主方法上。
//
// 从 ReaderHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的结果重新
// 缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun ReaderHook.hookReaderViewModel() {
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
                currentEpubStrong = null
                currentPageStrong = null
                OnlineReaderContextBridge.clear()
                onDemandPrefetchInFlight.clear()
                onDemandPrefetchRetryCount.clear()
                onDemandRefreshPending.clear()
                onDemandRefreshInFlight.clear()
                lastHandledReaderStatisticsKey = ""
                lastOnDemandPrefetchSpineKey = ""
                resetFullTextSearchState("ReaderViewModel cleared", removeOverlays = true)
            }
        })
        val readerUiIntentClass = classLoader.loadClass(READER_UI_INTENT_CLASS)
        XposedHelpers.findAndHookMethod(
            cls,
            "onIntent",
            readerUiIntentClass,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
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
                    if (intent.javaClass.name != "$READER_UI_INTENT_CLASS\$Statistics") return
                val page = callNoArg(intent, "getPage") ?: return
                currentPageRef = WeakReference(page)
                currentPageStrong = page
                val pageSignature = epubPageSignature(page)
                currentVisiblePageSignature = pageSignature
                currentVisiblePageNumber = epubPageNumber(page)
                // getVirtualPage 会批量预布局相邻甚至远端章节，不能据此判断真正可见页。
                // 仅使用 Statistics 上报的当前页触发逐章下载和分页刷新，避免异步纠正跳到其他章节。
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
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX ReaderViewModel hook failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaderHook.hookScrollPagerCrashGuard() {
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

internal fun ReaderHook.hookContentDomRenderTextWidthFallback() {
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
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX reader catalog full-text search hook failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaderHook.hookHomeBookshelfScreen() {
    val targets = listOf(
        HOME_SCREEN_CLASS to "HomeScreen",
        BOOKSHELF_SCREEN_CLASS to "BookshelfScreen",
    )
    targets.forEach { (className, methodName) ->
        runCatching {
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
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX home search cleanup hook failed for $className: ${it.message}")
        }
    }
}

internal fun ReaderHook.hookReaderBottomBar() {
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
                    // 阅微 2.3.0：ReaderBottomBar 参数为 (IntentReceiver, Book, UiStatus, Flow, onNavigateBack, ...)
                    // 旧版为 (?, receiver, book, status)，此处按新签名取值：receiver=0/book=1/status=2。
                    val status = param.args?.getOrNull(2)
                    val statusName = status?.javaClass?.simpleName.orEmpty()
                    val menuVisible = statusName.isNotBlank() && statusName != "Reader"
                    // 自动阅读开关开启时，把底栏最左返回键（p4 onNavigateBack, Function0）替换为切换开始/暂停。
                    // 与全文搜索互斥：搜索只用主题键，返回键固定归自动阅读。
                    if (menuVisible && ReaderAutoPageHook.isAutoPageEnabled()) {
                        // 把 p4 onNavigateBack（第一个 Function0）替换为切换自动阅读开始/暂停。
                        val args = param.args ?: return
                        val backIdx = args.indexOfFirst { it != null &&
                            it.javaClass.interfaces.any { iface -> iface.name == "kotlin.jvm.functions.Function0" } }
                        if (backIdx >= 0) {
                            args[backIdx] = nativeFunction0 { ReaderAutoPageHook.toggleAutoPage() }
                        }
                    }
                    val canShowSearchEntry = canShowReaderSearchEntry()
                    val canShowReadAloudEntry = canShowReaderReadAloudEntry()
                    readerBottomMenuVisible = canShowSearchEntry && menuVisible
                    if (canShowSearchEntry && statusName == "Menu") {
                        param.args?.getOrNull(0)?.let { bottomSearchReceiverRef = WeakReference(it) }
                        param.args?.getOrNull(1)?.let { bottomSearchBookRef = WeakReference(it) }
                    }
                    if (canShowReadAloudEntry && statusName == "Menu") {
                        param.args?.getOrNull(0)?.let { bottomReadAloudReceiverRef = WeakReference(it) }
                        param.args?.getOrNull(1)?.let { bottomReadAloudBookRef = WeakReference(it) }
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
                    val receiver = param.args?.getOrNull(0)
                    val book = param.args?.getOrNull(1)
                    val status = param.args?.getOrNull(2)
                    val statusName = status?.javaClass?.simpleName.orEmpty()
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

internal fun ReaderHook.hookInlineSearchIcon() {
    runCatching {
        // hook getArrowBack：自动阅读开关开启时设置标志位，下一个 Icon 调用替换为播放/暂停图标。
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
        // hook getDarkMode/getLightMode：inlineSearchIconEnabled 模式下设置标志位，下一个 Icon 替换为搜索图标。
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
        // hook Icon-ww6aTOc：替换 imageVector（自动阅读图标优先，其次搜索图标）
        val iconClass = classLoader.loadClass("androidx.compose.material3.IconKt")
        iconClass.declaredMethods.filter {
            it.name == "Icon-ww6aTOc" && it.parameterTypes.size == 7
        }.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 自动阅读图标替换（优先级最高）
                    if (nextIconIsAutoPage) {
                        nextIconIsAutoPage = false
                        val icon = ReaderAutoPageHook.autoPageIcon(ReaderAutoPageHook.isAutoPageRunning()) ?: return
                        param.args?.set(0, icon)
                        return
                    }
                    // 搜索图标替换（仅 inlineSearchIconEnabled 模式，替换主题键图标）
                    if (!settingsProvider().inlineSearchIconEnabled) return
                    if (!canRunFullTextSearch() || !readerBottomMenuVisible) return
                    if (!nextIconIsDarkLightToggle) return
                    nextIconIsDarkLightToggle = false
                    val searchIcon = searchImageVector() ?: return
                    param.args?.set(0, searchIcon)
                }
            })
        }
        // hook clickable-oSLSa3U$default：将主题切换按钮的点击回调替换为打开搜索（仅 inlineSearchIconEnabled 模式）
        // 非 inlineSearchIconEnabled 模式下不替换返回键点击，返回键留给自动阅读使用。
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
                        // 只在 inlineSearchIconEnabled 模式下替换主题键点击为搜索
                        val replace = settingsProvider().inlineSearchIconEnabled &&
                            isReaderBottomBarDarkLightToggleClick(onClick)
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

// 在阅微原生高亮界面（底栏搜索右侧那个"高亮"按钮打开的 ReaderHighlightScreen）的
// 高亮列表 LazyColumn 最前面注入一个"补全计划"入口 item，显示在"预设规则"分组标题上方。
// 关键：高亮界面用 LazyColumn 渲染，其 content lambda 被 R8 内联导致 Xposed 无法直接 hook。
// 因此改为 hook HighlightPageContent 主方法标记渲染区间，借用宿主自身调用 item$default 的
// 时机（见 ReaMicroSettingsHook.hookLazyListItem）在第一个 item 之前插入入口，位于列表最上方。
internal fun ReaderHook.hookReaderHighlightScreenEntry() {
    runCatching {
        val screenClass = classLoader.loadClass(READER_HIGHLIGHT_SCREEN_CLASS)
        val composerClass = classLoader.loadClass(COMPOSER_CLASS)
        // HighlightPageContent(List, String, Modifier, Function1, Function1, Function0, Composer, int, int)：
        // 阅微原生高亮页正文（普通 Column）。每次渲染在 before 重置"入口已注入"标志。
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
        // SectionTitleLikeMore(String title, Modifier, Composer, int, int)：分组标题（"预设规则"/"自定义"）。
        // 在第一个标题前注入"补全计划"入口卡片，点击后通过宿主 NavHost 导航到完整聚合页
        // （复用宿主页面框架与返回），不再就地替换或跳过原生内容。
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
                // 只在第一个标题前注入入口卡片，标题继续正常渲染。
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
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX reader highlight screen entry hook failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaderHook.hookReaderHighlightRuleSheet() {
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
                    val request = pendingReaderHighlightSheet
                    // 原先这里无条件打日志，字符串拼接 + isReaderSheetStatus 反射每次组合都要跑一遍。
                    // 只在真的要接管渲染时才记录。
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
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX reader highlight rule sheet hook failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaderHook.hookReaderFamilySheetHeight() {
    runCatching {
        val heightMethod = composeMethod(SIZE_KT_CLASS, HEIGHT_METHOD, 2)
        XposedBridge.hookMethod(heightMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // Modifier.height() 是 Compose 基础修饰符，全进程每次组合都会被调用几十上百次，
                // 所以判断顺序很关键：先做纯浮点比较，只有高度恰好等于宿主那个特定 sheet 高度时，
                // 才去做昂贵的调用栈检查。原先反过来，每次 height() 调用都要抓一整条 Compose
                // 调用栈并分配 StackTraceElement[]，是主要的 GC 来源之一。
                // 两个 dp 值都走 cachedUdp 缓存——udp() 本身是 loadClass + declaredMethods 全扫，
                // 放在这条热路径上比栈遍历还贵。
                val currentHeight = param.args?.getOrNull(1) as? Float ?: return
                val hostHeight = cachedUdp(HOST_READER_FAMILY_SHEET_HEIGHT_DP) ?: return
                if (currentHeight - hostHeight > 0.01f || hostHeight - currentHeight > 0.01f) return
                if (!isInReaderFamilySheetCompose()) return
                param.args[1] = cachedUdp(READER_RULE_SHEET_HEIGHT_DP) ?: return
            }
        })
        XposedBridge.log("$LOG_PREFIX reader family sheet height hook installed")
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX reader family sheet height hook failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaderHook.hookNativeSelectionController() {
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

internal fun ReaderHook.hookNativeSelectionMenu() {
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

internal fun ReaderHook.hookNativeSelectionMenuContent(bodyClass: Class<*>) {
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

internal fun ReaderHook.hookCurrentEpub() {
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
                            // Epub.read 可能由同一本书的恢复流程创建新的 Epub 实例。
                            // 先释放旧页的 HtmlDocument，再让宿主开始构建新分页窗口，避免旧、新文档重叠。
                            currentPageRef = null
                            currentPageStrong = null
                            currentVisiblePageSignature = null
                            currentVisiblePageNumber = null
                            lastHandledReaderStatisticsKey = ""
                            lastOnDemandPrefetchSpineKey = ""
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
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX current Epub hook failed: ${it.stackTraceToString()}")
    }
}

internal fun ReaderHook.hookCurrentEpubPage() {
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
                        currentPageStrong = page
                        if (page != null) {
                            publishOnlineReaderPage(page)
                            prefetchOnlineOnDemandChapters(page)
                        }
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

internal fun ReaderHook.hookReaderSharedStateMarks() {
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

internal fun ReaderHook.hookReaderCatalogHighlightPrecomputations() {
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

internal fun ReaderHook.hookSearchHighlightRenderInputs() {
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

internal fun ReaderHook.hookSearchHighlightMarksArgument(
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

internal fun ReaderHook.hookSearchHighlightResolvedPage() {
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

internal fun ReaderHook.hookSearchHighlightContentOverlays() {
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
