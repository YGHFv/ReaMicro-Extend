package com.reamicro.fix.hook

import android.widget.Switch
import com.reamicro.fix.ai.AiApiStore
import com.reamicro.fix.cloud.api.ApiServerClient
import com.reamicro.fix.cloud.api.ApiServerSettingsStore
import com.reamicro.fix.core.AppTopBarArguments
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.ai.AiImagePresetTarget
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.OnlineEpubStyleKind
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import com.reamicro.fix.hook.settings.*

// 设置页界面渲染簇。
//
// 注入到宿主 NavHost 的各个设置页面、顶栏、列表项与卡片。
//
// 从 ReaMicroSettingsHook 机械外移而来，函数体逐字未改：搬迁脚本会把反缩进后的
// 结果重新缩进回去与原文逐字节比对，不一致直接中止（tools/extract-hook-cluster.mjs）。
internal fun ReaMicroSettingsHook.insertModuleSettingsItem(lazyListScope: Any) {
    injectingModuleItem.set(true)
    runCatching {
        addLazyItem(lazyListScope, MODULE_SETTINGS_ITEM_KEY) { composer ->
            renderSettingsEntry(
                title = MODULE_ENTRY_TITLE,
                callbackName = "OpenModuleSettings",
                route = InjectedRoute.ModuleSettings,
                composer = composer,
            )
        }
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to insert module settings item: ${it.stackTraceToString()}")
    }
    injectingModuleItem.set(false)
}

internal fun ReaMicroSettingsHook.insertAccountSettingsItem(lazyListScope: Any) {
    if (!settings.snapshot().moduleEnabled) return
    injectingModuleItem.set(true)
    runCatching {
        addLazyItem(lazyListScope, ACCOUNT_SETTINGS_ITEM_KEY) { composer ->
            renderSettingsEntry(
                title = ACCOUNT_SWITCH_TITLE,
                callbackName = "OpenAccountSwitch",
                route = InjectedRoute.AccountSwitch,
                composer = composer,
            )
        }
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to insert account settings item: ${it.stackTraceToString()}")
    }
    injectingModuleItem.set(false)
}

internal fun ReaMicroSettingsHook.renderSettingsEntry(
    title: String,
    callbackName: String,
    route: InjectedRoute,
    composer: Any,
) {
    runCatching {
        val openSettings = functionProxy(callbackName, FUNCTION0_CLASS) {
            if (!openInjectedRouteViaHostNavigation(route)) {
                XposedBridge.log("$LOG_PREFIX host navigation unavailable for $callbackName")
            }
            targetUnit()
        }
        settingsEntryTitleOverride.set(title)
        try {
            method(APP_ABOUT_CLASS, APP_ABOUT_METHOD, 3).invoke(null, openSettings, composer, 0)
        } finally {
            settingsEntryTitleOverride.set(null)
        }
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to render settings entry: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.renderNestedSettingsEntry(
    title: String,
    callbackName: String,
    route: InjectedRoute,
    composer: Any,
) {
    runCatching {
        val openSettings = functionProxy(callbackName, FUNCTION0_CLASS) {
            openNestedInjectedRoute(route)
            targetUnit()
        }
        settingsEntryTitleOverride.set(title)
        try {
            method(APP_ABOUT_CLASS, APP_ABOUT_METHOD, 3).invoke(null, openSettings, composer, 0)
        } finally {
            settingsEntryTitleOverride.set(null)
        }
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to render nested settings entry: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.openInjectedRouteViaHostNavigation(route: InjectedRoute, navGraphScopeOverride: Any? = null): Boolean {
    val navGraphScope = navGraphScopeOverride ?: currentSettingsNavGraphScope ?: lastKnownNavGraphScope ?: return false
    val previousStack = injectedRouteStack
    return runCatching {
        val aboutRoute = staticObject(ROUTE_ABOUT_CLASS, "INSTANCE")
        currentSettingsNavGraphScope = navGraphScope
        currentSettingsNavController = runCatching { navGraphScope.method0("getNavController") }.getOrNull()
        val navigate = navGraphScope.javaClass.methods.firstOrNull {
            it.name == "navigate" && it.parameterTypes.size == 3
        } ?: error("NavGraphScope.navigate not found")
        injectedRouteStack = if (previousStack.isEmpty()) {
            listOf(route)
        } else {
            previousStack + route
        }
        setInjectedRouteState(route)
        navigatingModuleRoute.set(true)
        try {
            navigate.invoke(navGraphScope, aboutRoute, null, null)
        } finally {
            navigatingModuleRoute.set(false)
        }
        true
    }.onFailure {
        injectedRouteStack = previousStack
        setInjectedRouteState(previousStack.lastOrNull())
        navigatingModuleRoute.set(false)
        XposedBridge.log("$LOG_PREFIX failed to open injected settings route: ${it.stackTraceToString()}")
    }.getOrDefault(false)
}

internal fun ReaMicroSettingsHook.openNestedInjectedRoute(route: InjectedRoute): Boolean {
    val currentStack = injectedRouteStack
    if (currentStack.isEmpty()) {
        return openInjectedRouteViaHostNavigation(route)
    }
    if (currentInjectedRoute() == InjectedRoute.FontLibrary || route == InjectedRoute.FontLibrary) {
        clearPendingDeleteFontSelection()
    }
    injectedRouteStack = currentStack + route
    setInjectedRouteState(route)
    return true
}

internal fun ReaMicroSettingsHook.activeInjectedRoute(): InjectedRoute? =
    currentInjectedRoute()

internal fun ReaMicroSettingsHook.currentInjectedRoute(): InjectedRoute? =
    injectedRouteUiState?.let(::routeStateValue) ?: injectedRouteStack.lastOrNull()

internal fun ReaMicroSettingsHook.refreshCurrentInjectedRoute() {
    currentInjectedRoute()?.let { route -> setInjectedRouteState(route) }
}

internal fun ReaMicroSettingsHook.navigateBackFromInjectedRoute() {
    val navGraphScope = currentSettingsNavGraphScope
    val stack = injectedRouteStack
    if (handleNestedInjectedBack()) return
    injectedRouteStack = stack.dropLast(1)
    setInjectedRouteState(null)
    clearPendingDeleteFontSelection()
    popHostInjectedRoute(navGraphScope)
}

internal fun ReaMicroSettingsHook.isCurrentSettingsNavController(target: Any?): Boolean {
    if (target == null) return false
    if (currentSettingsNavController === target) return true
    val resolved = runCatching {
        currentSettingsNavGraphScope?.method0("getNavController")
    }.getOrNull()
    if (resolved != null) currentSettingsNavController = resolved
    return resolved === target
}

internal fun ReaMicroSettingsHook.popHostInjectedRoute(navGraphScope: Any?) {
    if (navGraphScope == null) return
    poppingInjectedRoute.set(true)
    runCatching {
        navGraphScope.javaClass.methods
            .firstOrNull { it.name == "popBackStack" && it.parameterTypes.isEmpty() }
            ?.invoke(navGraphScope)
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX failed to pop injected route: ${it.stackTraceToString()}")
    }
    poppingInjectedRoute.set(false)
}

internal fun ReaMicroSettingsHook.renderInjectedSettingsScreen(route: InjectedRoute, composer: Any) {
    val routeState = injectedRouteState(route)
    val currentRoute = routeStateValue(routeState) ?: route
    renderInjectedBackHandler(currentRoute, composer)
    val topBar = composableLambda(MODULE_TOP_BAR_KEY, FUNCTION2_CLASS) { args ->
        val innerComposer = args?.getOrNull(0) ?: return@composableLambda targetUnit()
        val currentRoute = routeStateValue(routeState) ?: route
        renderHostTopBar(currentRoute.title, innerComposer)
        targetUnit()
    }
    val content = composableLambda(MODULE_CONTENT_KEY, FUNCTION3_CLASS) { args ->
        val innerPaddings = args?.getOrNull(0) ?: return@composableLambda targetUnit()
        val innerComposer = args.getOrNull(1) ?: return@composableLambda targetUnit()
        when (val currentRoute = routeStateValue(routeState) ?: route) {
            InjectedRoute.ModuleSettings -> renderHostModuleSettingsContent(innerPaddings, innerComposer)
            InjectedRoute.AssociationCompletionSettings -> renderAssociationCompletionSettingsContent(innerPaddings, innerComposer)
            InjectedRoute.ReaderCompletionSettings -> renderReaderCompletionSettingsContent(innerPaddings, innerComposer)
            InjectedRoute.ReaderReadAloudSettings -> renderReaderReadAloudSettingsContent(innerPaddings, innerComposer)
            InjectedRoute.ReaderSelectionMenuSettings -> renderReaderSelectionMenuSettingsContent(innerPaddings, innerComposer)
            InjectedRoute.ReaderHighlightSettings -> renderReaderHighlightSettingsContent(innerPaddings, innerComposer)
            InjectedRoute.ReaderHighlightConfigSettings -> renderReaderHighlightConfigSettingsContent(innerPaddings, innerComposer)
            InjectedRoute.ReaderHighlightTextSettings -> renderReaderHighlightTextSettingsContent(innerPaddings, innerComposer)
            is InjectedRoute.ReaderCompletionPlan -> renderReaderCompletionPlanContent(currentRoute, innerPaddings, innerComposer)
            is InjectedRoute.ReaderBookHighlightRules -> renderReaderBookHighlightRulesContent(currentRoute, innerPaddings, innerComposer)
            is InjectedRoute.ReaderBookOnlyHighlightRules -> renderReaderBookOnlyHighlightRulesContent(currentRoute, innerPaddings, innerComposer)
            is InjectedRoute.ReaderBookGlobalHighlightRules -> renderReaderBookGlobalHighlightRulesContent(currentRoute, innerPaddings, innerComposer)
            InjectedRoute.ReaderHighlightColorPicker -> renderReaderHighlightColorPickerContent(innerPaddings, innerComposer)
            InjectedRoute.ProfileBackgroundSettings -> renderProfileBackgroundSettingsContent(innerPaddings, innerComposer)
            InjectedRoute.CloudCompletionSettings -> renderCloudCompletionSettingsContent(innerPaddings, innerComposer)
            InjectedRoute.ApiServerSettings -> renderApiServerSettingsContent(innerPaddings, innerComposer)
            InjectedRoute.RotationCompletionSettings -> renderRotationCompletionSettingsContent(innerPaddings, innerComposer)
            InjectedRoute.AccountSwitch -> renderAccountSwitchContent(innerPaddings, innerComposer)
            InjectedRoute.OnlineCompletionSettings -> renderOnlineCompletionSettingsContent(innerPaddings, innerComposer)
            InjectedRoute.OnlineDownloadStyleSettings -> renderOnlineDownloadStyleSettingsContent(innerPaddings, innerComposer)
            is InjectedRoute.OnlineEpubStyleList -> renderOnlineEpubStyleListContent(currentRoute.kind, innerPaddings, innerComposer)
            InjectedRoute.AiConfigSettings -> renderAiConfigSettingsContent(innerPaddings, innerComposer)
            InjectedRoute.DictionarySettings -> renderDictionarySettingsContent(innerPaddings, innerComposer)
            InjectedRoute.DictionaryApiPicker -> renderDictionaryApiPickerContent(innerPaddings, innerComposer)
            InjectedRoute.DictionaryPresetPicker -> renderDictionaryPresetPickerContent(innerPaddings, innerComposer)
            InjectedRoute.ImageSettings -> renderImageSettingsContent(innerPaddings, innerComposer)
            InjectedRoute.ImageApiPicker -> renderImageApiPickerContent(innerPaddings, innerComposer)
            is InjectedRoute.ImagePresetPicker -> renderImagePresetPickerContent(currentRoute.target, innerPaddings, innerComposer)
            InjectedRoute.FontSettings -> renderFontSettingsContent(innerPaddings, innerComposer)
            is InjectedRoute.FontPicker -> renderFontPickerContent(currentRoute.target, innerPaddings, innerComposer)
            InjectedRoute.FontLibrary -> renderFontLibraryContent(innerPaddings, innerComposer)
            InjectedRoute.AboutCompletion -> renderAboutCompletionContent(innerPaddings, innerComposer)
        }
        targetUnit()
    }
    method(SCAFFOLD_KT_CLASS, SCAFFOLD_METHOD, 13).invoke(
        null,
        null,
        topBar,
        null,
        null,
        null,
        0,
        backgroundDim(composer),
        0L,
        null,
        content,
        composer,
        805306416,
        445,
    )
    updateModuleDialogTheme(composer)
}

internal fun ReaMicroSettingsHook.renderInjectedBackHandler(route: InjectedRoute, composer: Any) {
    val enabled = route is InjectedRoute.FontPicker ||
        route is InjectedRoute.ReaderBookHighlightRules ||
        route is InjectedRoute.ReaderBookOnlyHighlightRules ||
        route is InjectedRoute.ReaderBookGlobalHighlightRules ||
        route is InjectedRoute.OnlineEpubStyleList ||
        route == InjectedRoute.FontLibrary ||
        route in MODULE_CHILD_ROUTES ||
        route in READER_CHILD_ROUTES ||
        route in AI_CHILD_ROUTES
    val onBack = functionProxy("InjectedNestedBackHandler", FUNCTION0_CLASS) {
        handleNestedInjectedBack()
        targetUnit()
    }
    method(BACK_HANDLER_KT_CLASS, BACK_HANDLER_METHOD, 5).invoke(null, enabled, onBack, composer, 0, 0)
}

internal fun ReaMicroSettingsHook.renderHostTopBar(title: String, composer: Any) {
    renderHostTopBar(title, composer) {
        navigateBackFromInjectedRoute()
    }
}

internal fun ReaMicroSettingsHook.renderHostTopBar(title: String, composer: Any, onBack: () -> Unit) {
    invokeAppTopBar(title = title, composer = composer, onBack = onBack)
}

internal fun ReaMicroSettingsHook.renderReaderSheetTopBar(title: String, composer: Any, onClose: () -> Unit) {
    val windowInsets = readerSheetWindowInsets(composer)
    val closeIcon = closeImageVector()
    if (windowInsets == null || closeIcon == null) {
        renderHostTopBar(title, composer, onClose)
        return
    }
    invokeAppTopBar(
        title = title,
        composer = composer,
        onBack = onClose,
        navIcon = closeIcon,
        windowInsets = windowInsets,
    )
}

/**
 * 调用宿主 AppTopBar 渲染顶栏标题。
 *
 * 签名随宿主版本变化（2.2.0 的 8 参 → 2.3.0 beta 的 9 参 → 2.3.1 beta 的 10 参 + 方法名
 * mangling），所以定位与实参铺设都不写死：方法由 [appTopBarMethod] 按 Composable 尾参形状找，
 * 实参由 [AppTopBarArguments.plan] 按参数类型铺。两处的版本沿革与失效方式见各自注释。
 */
internal fun ReaMicroSettingsHook.invokeAppTopBar(
    title: String,
    composer: Any,
    onBack: (() -> Unit)?,
    navIcon: Any? = null,
    windowInsets: Any? = null,
) {
    val m = appTopBarMethod()
    val backProxy = onBack?.let { cb ->
        functionProxy("ModuleSettingsBack", FUNCTION0_CLASS) {
            cb()
            targetUnit()
        }
    }
    val args = AppTopBarArguments.plan(
        parameterTypes = m.parameterTypes,
        composer = composer,
        title = title,
        onBack = backProxy,
        navIcon = navIcon,
        windowInsets = windowInsets,
        function0ClassName = FUNCTION0_CLASS,
        imageVectorClassName = IMAGE_VECTOR_CLASS,
        windowInsetsClassName = WINDOW_INSETS_CLASS,
    )
    m.invoke(null, *args)
}

/**
 * 定位宿主 `AppTopBar`：按基础名（容忍 inline class mangling 后缀）+ 首参 String +
 * 末三参 `(Composer,int,int)`，取参数最多者，兼容参数个数与方法名的版本变化。
 *
 * 2.3.1 beta 新增 `contentColor: Color` 参数后 JVM 方法名变成 `AppTopBar-cd68TDI`，
 * 原先的 `name == "AppTopBar"` 精确匹配落空 → 顶栏整条静默消失（异常被 Compose 代理吞掉）。
 */
internal fun ReaMicroSettingsHook.appTopBarMethod(): Method =
    synchronized(methodCache) {
        methodCache.getOrPut("$APP_TOP_BAR_CLASS#$APP_TOP_BAR_METHOD/*") {
            val candidates = cls(APP_TOP_BAR_CLASS).declaredMethods
            composeInterop.findComposableMethod(
                candidates = candidates,
                baseName = APP_TOP_BAR_METHOD,
                composerClassName = COMPOSER_CLASS,
                firstParameterType = String::class.java,
            ) ?: error(
                "$APP_TOP_BAR_CLASS.$APP_TOP_BAR_METHOD not found; candidates=" +
                    candidates.joinToString { "${it.name}/${it.parameterTypes.size}" },
            )
        }
    }

internal fun ReaMicroSettingsHook.renderHostModuleSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("ModuleSettingsList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        val snapshot = settings.snapshot()
        addLazyItem(lazyListScope, ASSOCIATION_SWITCHES_ITEM_KEY) { itemComposer ->
            renderNestedSettingsEntry(
                title = "\u5173\u8054\u8865\u5168",
                callbackName = "OpenAssociationCompletionSettings",
                route = InjectedRoute.AssociationCompletionSettings,
                composer = itemComposer,
            )
        }
        addLazyItem(lazyListScope, ONLINE_COMPLETION_SETTINGS_ITEM_KEY) { itemComposer ->
            renderNestedSettingsEntry(
                title = ONLINE_COMPLETION_TITLE,
                callbackName = "OpenOnlineCompletionSettings",
                route = InjectedRoute.OnlineCompletionSettings,
                composer = itemComposer,
            )
        }
        addLazyItem(lazyListScope, CLOUD_SWITCHES_ITEM_KEY) { itemComposer ->
            renderNestedSettingsEntry(
                title = "\u4e91\u76d8\u8865\u5168",
                callbackName = "OpenCloudCompletionSettings",
                route = InjectedRoute.CloudCompletionSettings,
                composer = itemComposer,
            )
        }
        addLazyItem(lazyListScope, FONT_SETTINGS_ITEM_KEY) { itemComposer ->
            renderNestedSettingsEntry(
                title = "\u5b57\u4f53\u8865\u5168",
                callbackName = "OpenFontSettings",
                route = InjectedRoute.FontSettings,
                composer = itemComposer,
            )
        }
        addLazyItem(lazyListScope, READER_SWITCHES_ITEM_KEY) { itemComposer ->
            renderNestedSettingsEntry(
                title = "\u9605\u8bfb\u8865\u5168",
                callbackName = "OpenReaderCompletionSettings",
                route = InjectedRoute.ReaderCompletionSettings,
                composer = itemComposer,
            )
        }
        addLazyItem(lazyListScope, PROFILE_BACKGROUND_SWITCHES_ITEM_KEY) { itemComposer ->
            renderNestedSettingsEntry(
                title = "\u4e3b\u9875\u8865\u5168",
                callbackName = "OpenProfileBackgroundSettings",
                route = InjectedRoute.ProfileBackgroundSettings,
                composer = itemComposer,
            )
        }
        addLazyItem(lazyListScope, ROTATION_SWITCHES_ITEM_KEY) { itemComposer ->
            renderNestedSettingsEntry(
                title = "\u65cb\u8f6c\u8865\u5168",
                callbackName = "OpenRotationCompletionSettings",
                route = InjectedRoute.RotationCompletionSettings,
                composer = itemComposer,
            )
        }
        addLazyItem(lazyListScope, AI_CONFIG_SETTINGS_ITEM_KEY) { itemComposer ->
            renderNestedSettingsEntry(
                title = AI_CONFIG_TITLE,
                callbackName = "OpenAiConfigSettings",
                route = InjectedRoute.AiConfigSettings,
                composer = itemComposer,
            )
        }
        addLazyItem(lazyListScope, ABOUT_COMPLETION_ENTRY_ITEM_KEY) { itemComposer ->
            renderNestedSettingsEntry(
                title = ABOUT_COMPLETION_TITLE,
                callbackName = "OpenAboutCompletion",
                route = InjectedRoute.AboutCompletion,
                composer = itemComposer,
            )
        }
        return@functionProxy targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.completionEntryRow(key: String, title: String, enabled: Boolean, route: InjectedRoute): ActionRow =
    ActionRow(
        key = key,
        title = title,
        subtitle = if (enabled) "\u5df2\u542f\u7528" else "\u672a\u542f\u7528",
        trailing = "\u8bbe\u7f6e",
        onClick = { openNestedInjectedRoute(route) },
    )

internal fun ReaMicroSettingsHook.renderAssociationCompletionSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("AssociationCompletionList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        val snapshot = settings.snapshot()
        val rows = buildList {
            add(
                ToggleRow(
                    key = ModuleSettings.KEY_ASSOCIATION_MANUAL_EDIT_ENABLED,
                    title = "\u624b\u52a8\u7f16\u8f91",
                    checked = snapshot.associationManualEditEnabled,
                    visibleProvider = { hasManualEditFeature() },
                    onChanged = { checked, _ ->
                        settings.setAssociationManualEditEnabled(checked)
                        checked
                    },
                ),
            )
            visibleAssociationSearchSourceGroups().forEach { group ->
                add(
                    ToggleRow(
                        key = ModuleSettings.searchSourceKey(group.id),
                        title = group.title,
                        checked = snapshot.isSearchSourceGroupEnabled(group.id),
                        onChanged = { checked, updateRow ->
                            setAssociationSearchSourceEnabled(group.id, checked) { value ->
                                updateRow(ModuleSettings.searchSourceKey(group.id), value)
                            }
                        },
                    ),
                )
            }
        }
        addLazyItem(lazyListScope, ASSOCIATION_SWITCHES_ITEM_KEY) { itemComposer ->
            renderHostSettingsCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderReaderCompletionSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("ReaderCompletionList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        val snapshot = settings.snapshot()
        val managementRows = listOf(
            ActionRow(
                key = "reader_highlight_settings",
                title = READER_HIGHLIGHT_SETTINGS_TITLE,
                subtitle = readerHighlightSettingsSummary(),
                onClick = { openNestedInjectedRoute(InjectedRoute.ReaderHighlightSettings) },
            ),
            ActionRow(
                key = "reader_read_aloud_settings",
                title = "\u542c\u4e66\u7ba1\u7406",
                subtitle = readerReadAloudSettingsSummary(snapshot),
                onClick = { openNestedInjectedRoute(InjectedRoute.ReaderReadAloudSettings) },
            ),
            ActionRow(
                key = "reader_selection_menu_settings",
                title = "\u9009\u4e2d\u83dc\u5355",
                subtitle = readerSelectionMenuSettingsSummary(snapshot),
                onClick = { openNestedInjectedRoute(InjectedRoute.ReaderSelectionMenuSettings) },
            ),
        )
        val rows = listOf(
            ToggleRow(
                key = ModuleSettings.KEY_READER_BACKGROUND_ENABLED,
                title = "\u80cc\u666f\u6269\u5c55",
                checked = snapshot.readerBackgroundEnabled,
                onChanged = { checked, _ ->
                    settings.setReaderBackgroundEnabled(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_READER_AUTO_PAGE_ENABLED,
                title = "\u81ea\u52a8\u9605\u8bfb",
                checked = snapshot.readerAutoPageEnabled,
                onChanged = { checked, _ ->
                    settings.setReaderAutoPageEnabled(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_READER_OVERWRITE_CHECK_ENABLED,
                title = "\u8986\u76d6\u68c0\u67e5",
                checked = snapshot.readerOverwriteCheckEnabled,
                onChanged = { checked, _ ->
                    settings.setReaderOverwriteCheckEnabled(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_EDIT_FILE_ENABLED,
                title = "\u6587\u4ef6\u7f16\u8f91",
                checked = snapshot.editFileEnabled,
                onChanged = { checked, _ ->
                    settings.setEditFileEnabled(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_READER_DIALOGUE_HIGHLIGHT_ENABLED,
                title = "\u9ad8\u4eae\u5bf9\u8bdd",
                checked = snapshot.readerDialogueHighlightEnabled,
                onChanged = { checked, _ ->
                    settings.setReaderDialogueHighlightEnabled(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_INLINE_SEARCH_ICON_ENABLED,
                title = "\u5185\u5d4c\u641c\u7d22",
                checked = snapshot.inlineSearchIconEnabled,
                onChanged = { checked, _ ->
                    settings.setInlineSearchIconEnabled(checked)
                    checked
                },
            ),
        )
        addLazyItem(lazyListScope, READER_HIGHLIGHT_MANAGEMENT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(managementRows, itemComposer)
        }
        addLazyItem(lazyListScope, READER_SWITCHES_ITEM_KEY) { itemComposer ->
            renderHostSettingsCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderReaderSelectionMenuSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("ReaderSelectionMenuSettingsList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        val snapshot = settings.snapshot()
        val rows = listOf(
            ToggleRow(
                key = ModuleSettings.KEY_READER_COMPACT_SELECTION_MENU_ENABLED,
                title = "\u7b80\u6d01\u83dc\u5355",
                checked = snapshot.readerCompactSelectionMenuEnabled,
                onChanged = { checked, _ ->
                    settings.setReaderCompactSelectionMenuEnabled(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_READER_EDIT_OVERWRITE_ENABLED,
                title = "\u7f16\u8f91\u8986\u5199",
                checked = snapshot.readerEditOverwriteEnabled,
                onChanged = { checked, _ ->
                    settings.setReaderEditOverwriteEnabled(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_READER_DICTIONARY_ENABLED,
                title = "\u8bcd\u5178\u91ca\u4e49",
                checked = snapshot.readerDictionaryEnabled,
                onChanged = { checked, _ ->
                    settings.setReaderDictionaryEnabled(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_READER_SELECTION_HIGHLIGHT_ENABLED,
                title = "\u9009\u4e2d\u9ad8\u4eae",
                checked = snapshot.readerSelectionHighlightEnabled,
                onChanged = { checked, _ ->
                    settings.setReaderSelectionHighlightEnabled(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_READER_READ_ALOUD_SELECTION_ENABLED,
                title = "\u6bb5\u843d\u968f\u542c",
                checked = snapshot.readerReadAloudSelectionEnabled,
                onChanged = { checked, _ ->
                    settings.setReaderReadAloudSelectionEnabled(checked)
                    checked
                },
            ),
        )
        addLazyItem(lazyListScope, READER_SELECTION_MENU_SETTINGS_ITEM_KEY) { itemComposer ->
            renderHostSettingsCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderAboutCompletionContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("AboutCompletionList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        aboutVersionValue()
        val snapshot = settings.snapshot()
        val toggleRows = listOf(
            ToggleRow(
                key = ModuleSettings.KEY_CONCISE_LOG_ENABLED,
                title = "\u7b80\u6d01\u65e5\u5fd7",
                checked = snapshot.conciseLogEnabled,
                onChanged = { checked, _ ->
                    settings.setConciseLogEnabled(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_READER_HIGHLIGHT_PERFORMANCE_LOG_ENABLED,
                title = "\u9ad8\u4eae\u65e5\u5fd7",
                checked = snapshot.readerHighlightPerformanceLogEnabled,
                onChanged = { checked, _ ->
                    settings.setReaderHighlightPerformanceLogEnabled(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_ACCOUNT_CACHE_CLEANUP_ENABLED,
                title = "\u7f13\u5b58\u6e05\u7406",
                checked = snapshot.accountCacheCleanupEnabled,
                onChanged = { checked, _ ->
                    settings.setAccountCacheCleanupEnabled(checked)
                    checked
                },
            ),
        )
        // API 服务器设置入口只在调试模式解锁后显示，位置在"模块自检"上方。
        val debugRows = if (snapshot.apiDebugUnlocked) {
            listOf(
                ActionRow(
                    key = "about_completion_api_server_settings",
                    title = "API 服务器设置",
                    subtitle = "服务器地址、API Key、内容库更新、云端签到抽卡等",
                    onClick = { openNestedInjectedRoute(InjectedRoute.ApiServerSettings) },
                ),
            )
        } else {
            emptyList()
        }
        val actionRows = debugRows + listOf(
            ActionRow(
                key = "about_completion_hook_report",
                title = "\u6a21\u5757\u81ea\u68c0",
                subtitle = HookInstallReport.summaryLine(),
                onClick = { openHookInstallReportDialog() },
            ),
            ActionRow(
                key = "about_completion_export_log",
                title = "\u5bfc\u51fa\u65e5\u5fd7",
                subtitle = "\u5c06\u6a21\u5757\u8fd0\u884c\u65e5\u5fd7\u5bfc\u51fa\u5230\u4e0b\u8f7d\u76ee\u5f55",
                onClick = { exportModuleLog() },
            ),
        )
        // 项目地址单独一张卡片放在最下面，副标题直接是链接本身，点击跳浏览器。
        val versionLine = moduleBuildVersionLine()
        val projectRows = listOf(
            ActionRow(
                key = "about_completion_build_version",
                title = "\u6784\u5efa\u7248\u672c",
                subtitle = versionLine,
                singleLineSubtitle = true,
                onClick = { registerAboutVersionTap() },
            ),
            ActionRow(
                key = "about_completion_project_url",
                title = "\u9879\u76ee\u5730\u5740",
                subtitle = PROJECT_URL,
                singleLineSubtitle = true,
                onClick = { openProjectUrl() },
            ),
        )
        addLazyItem(lazyListScope, ABOUT_COMPLETION_CONTENT_ITEM_KEY) { itemComposer ->
            renderHostSettingsCard(toggleRows, itemComposer)
        }
        addLazyItem(lazyListScope, ABOUT_COMPLETION_ENTRY_ITEM_KEY) { itemComposer ->
            renderHostActionCard(actionRows, itemComposer)
        }
        addLazyItem(lazyListScope, ABOUT_COMPLETION_PROJECT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(projectRows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

/**
 * 展示 hook 安装自检结果。
 *
 * 宿主升级后，先看这里的「已安装 N/M」与失败列表，能直接定位掉线的 hook，
 * 不必再靠功能表现反推。
 */

// 从阅读页原生高亮界面点击"补全计划"进入的完整聚合页。三段式：
//   段1 高亮样式入口（点进去是完整页面，非弹窗）
//   段2 全局规则卡（添加全局规则 + 跟随全局开关 + 各全局规则）
//   段3 单书规则卡（仅本书规则，平铺，含添加本书规则）
// 全部复用宿主 ActionCard/Switch 组件与宿主 LazyColumn，样式与设置页一致。
internal fun ReaMicroSettingsHook.renderReaderCompletionPlanContent(
    route: InjectedRoute.ReaderCompletionPlan,
    innerPaddings: Any,
    composer: Any,
) {
    val listContent = functionProxy("ReaderCompletionPlanList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        readerHighlightVersionValue()
        fontLibraryVersionValue()
        val highlight = settings.highlightSettings()
        // 段1：高亮样式入口
        val styleRows = listOf(
            ActionRow(
                key = "reader_plan_style_entry_${route.bookKey.hashCode()}",
                title = "\u9ad8\u4eae\u6837\u5f0f",
                subtitle = "${highlight.styles.size} \u4e2a\u6837\u5f0f",
                singleLineSubtitle = true,
                onClick = { openNestedInjectedRoute(InjectedRoute.ReaderHighlightConfigSettings) },
            ),
        )
        // 段2：全局规则（添加全局规则 + 跟随全局 + 各全局规则）
        val followsGlobal = highlight.bookFollowsGlobalRules(route.bookKey)
        val enabledGlobalIds = highlight.effectiveGlobalRuleIdsForBook(route.bookKey)
        val globalRows = buildList {
            add(
                ActionRow(
                    key = "reader_plan_global_add_${route.bookKey.hashCode()}",
                    title = "\u6dfb\u52a0\u5168\u5c40\u89c4\u5219",
                    subtitle = "\u65b0\u589e\u4e00\u6761\u56fa\u5b9a\u6587\u672c\u6216\u6b63\u5219\u9ad8\u4eae\u89c4\u5219",
                    singleLineSubtitle = true,
                    onClick = { openReaderHighlightRuleDialog(newReaderHighlightRule()) },
                ),
            )
            add(
                ActionRow(
                    key = "reader_plan_global_follow_${route.bookKey.hashCode()}",
                    title = "\u8ddf\u968f\u5168\u5c40",
                    subtitle = if (followsGlobal) "\u4fee\u6539\u4e0b\u65b9\u5f00\u5173\u65f6\u540c\u6b65\u4fee\u6539\u5168\u5c40\u89c4\u5219" else "\u4fee\u6539\u4e0b\u65b9\u5f00\u5173\u65f6\u4ec5\u5f71\u54cd\u672c\u4e66",
                    trailingContent = { itemComposer ->
                        renderHostActionSwitch(
                            key = "reader_plan_global_follow_switch_${route.bookKey.hashCode()}",
                            checked = followsGlobal,
                            composer = itemComposer,
                        ) { enabled ->
                            settings.setReaderHighlightBookFollowsGlobalRules(route.bookKey, enabled)
                            bumpReaderHighlightVersion()
                        }
                    },
                    singleLineSubtitle = true,
                ),
            )
            highlight.globalRules().forEach { rule ->
                add(
                    ActionRow(
                        key = "reader_plan_global_rule_${route.bookKey.hashCode()}_${rule.id}",
                        title = rule.name,
                        subtitle = "${highlightRuleSummary(rule)} / ${highlightRuleStyleSummary(highlight, rule)}",
                        trailingContent = { itemComposer ->
                            renderHostActionSwitch(
                                key = "reader_plan_global_rule_switch_${route.bookKey.hashCode()}_${rule.id}",
                                checked = rule.id in enabledGlobalIds,
                                composer = itemComposer,
                            ) { enabled ->
                                if (followsGlobal) {
                                    settings.setReaderHighlightRuleEnabled(rule.id, enabled)
                                } else {
                                    settings.setReaderHighlightBookGlobalRuleEnabled(route.bookKey, rule.id, enabled)
                                }
                                bumpReaderHighlightVersion()
                            }
                        },
                        singleLineSubtitle = true,
                        onClick = { openReaderHighlightRuleDialog(rule) },
                    ),
                )
            }
        }
        // 段3：单书规则（仅本书，平铺）
        val bookRules = highlight.bookRules(route.bookKey)
        val bookRows = buildList {
            add(
                ActionRow(
                    key = "reader_plan_book_add_${route.bookKey.hashCode()}",
                    title = "\u6dfb\u52a0\u672c\u4e66\u89c4\u5219",
                    subtitle = "\u65b0\u589e\u672c\u4e66\u56fa\u5b9a\u6587\u672c\u6216\u6b63\u5219\u9ad8\u4eae\u89c4\u5219",
                    singleLineSubtitle = true,
                    onClick = {
                        openReaderHighlightRuleDialog(
                            newReaderHighlightRule(route.bookKey, route.bookTitle),
                        )
                    },
                ),
            )
            bookRules.forEach { rule ->
                add(
                    ActionRow(
                        key = "reader_plan_book_rule_${rule.id}",
                        title = rule.name,
                        subtitle = "${highlightRuleSummary(rule)} / ${highlightRuleStyleSummary(highlight, rule)}",
                        trailingContent = { itemComposer ->
                            renderHostActionSwitch(
                                key = "reader_plan_book_rule_switch_${rule.id}",
                                checked = rule.enabled,
                                composer = itemComposer,
                            ) { enabled ->
                                settings.setReaderHighlightRuleEnabled(rule.id, enabled)
                                bumpReaderHighlightVersion()
                            }
                        },
                        singleLineSubtitle = true,
                        onClick = { openReaderHighlightRuleDialog(rule) },
                    ),
                )
            }
            if (bookRules.isEmpty()) {
                add(
                    ActionRow(
                        key = "reader_plan_book_empty_${route.bookKey.hashCode()}",
                        title = "\u6682\u65e0\u5355\u4e66\u89c4\u5219",
                        subtitle = "\u9009\u4e2d\u672c\u4e66\u6587\u672c\u540e\u53ef\u6dfb\u52a0\u9ad8\u4eae\u89c4\u5219",
                        singleLineSubtitle = true,
                    ),
                )
            }
        }
        addLazyItem(lazyListScope, "reader_plan_style_${route.bookKey}".hashCode()) { itemComposer ->
            renderHostActionCard(styleRows, itemComposer)
        }
        addLazyItem(lazyListScope, "reader_plan_global_${route.bookKey}".hashCode()) { itemComposer ->
            renderHostActionCard(globalRows, itemComposer)
        }
        addLazyItem(lazyListScope, "reader_plan_book_${route.bookKey}".hashCode()) { itemComposer ->
            renderHostActionCard(bookRows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderReaderSheetBackHandler(composer: Any, onClose: () -> Unit) {
    val back = functionProxy("ReaderHighlightRulesSheetBack", FUNCTION0_CLASS) {
        onClose()
        targetUnit()
    }
    renderReaderSheetNavigationBackHandler(composer, back)
    method(BACK_HANDLER_KT_CLASS, BACK_HANDLER_METHOD, 5).invoke(null, true, back, composer, 0, 0)
}

internal fun ReaMicroSettingsHook.renderReaderSheetNavigationBackHandler(composer: Any, back: Any) {
    runCatching {
        val none = staticObject(NAVIGATION_EVENT_INFO_NONE_CLASS, "INSTANCE")
        val state = method(
            REMEMBER_NAVIGATION_EVENT_STATE_KT_CLASS,
            REMEMBER_NAVIGATION_EVENT_STATE_METHOD,
            6,
        ).invoke(null, none, null, null, composer, 6, 6)
        method(
            NAVIGATION_EVENT_HANDLER_KT_CLASS,
            NAVIGATION_BACK_HANDLER_METHOD,
            7,
        ).invoke(null, state, true, null, back, composer, 0, 4)
    }.onFailure {
        XposedBridge.log("$LOG_PREFIX reader sheet navigation back fallback: ${it.stackTraceToString()}")
    }
}

internal fun ReaMicroSettingsHook.renderCloudCompletionSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("CloudCompletionList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        val snapshot = settings.snapshot()
        val rows = listOf(
            ToggleRow(
                key = ModuleSettings.KEY_CLOUD_WEBDAV_ENABLED,
                title = "WebDAV",
                checked = snapshot.cloudWebDavEnabled,
                onChanged = { checked, _ ->
                    settings.setCloudWebDavEnabled(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_CLOUD_LOCAL_LIBRARY_ENABLED,
                title = "\u672c\u5730\u4e66\u5e93",
                checked = snapshot.cloudLocalLibraryEnabled,
                onChanged = { checked, _ ->
                    settings.setCloudLocalLibraryEnabled(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_CLOUD_EXTENDED_DISPLAY_ENABLED,
                title = "\u6269\u5c55\u663e\u793a",
                checked = snapshot.cloudExtendedDisplayEnabled,
                onChanged = { checked, _ ->
                    settings.setCloudExtendedDisplayEnabled(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_CLOUD_DOWNLOAD_CANCEL_ENABLED,
                title = "\u5141\u8bb8\u53d6\u6d88",
                checked = snapshot.cloudDownloadCancelEnabled,
                onChanged = { checked, _ ->
                    settings.setCloudDownloadCancelEnabled(checked)
                    checked
                },
            ),
            ToggleRow(
                key = ModuleSettings.KEY_ACCOUNT_EXPORT_ENABLED,
                title = "\u5bfc\u51fa\u8865\u5168",
                checked = snapshot.accountExportEnabled,
                onChanged = { checked, _ ->
                    settings.setAccountExportEnabled(checked)
                    checked
                },
            ),
        )
        addLazyItem(lazyListScope, CLOUD_SWITCHES_ITEM_KEY) { itemComposer ->
            renderHostSettingsCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderApiServerSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("ApiServerSettingsList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        val apiRows = listOf(
            ActionRow(
                key = "api_server_settings",
                title = "API 服务器配置",
                subtitle = apiServerSettingsSubtitle(),
                onClick = { openApiServerSettingsDialog() },
            ),
            ActionRow(
                key = "api_package_updates",
                title = "检查内容库更新",
                subtitle = "书源、关联源、主题、成书样式和高亮样式",
                onClick = { checkApiPackageUpdates() },
            ),
            ActionRow(
                key = "api_library_upload",
                title = "上传内容库",
                subtitle = "把本机书源、关联源上传到服务器，需后台开放上传并配置阅微 ID 白名单",
                onClick = { openApiLibraryUploadDialog() },
            ),
            ActionRow(
                key = "api_library_link",
                title = "关联内容库",
                subtitle = "本机书源、关联源与服务器比对，名称和域名一致即可由服务器更新",
                onClick = { openApiLibraryLinkDialog() },
            ),
            ActionRow(
                key = "api_package_management",
                title = "已安装内容包",
                subtitle = "查看版本、启用主题、回滚或卸载",
                onClick = { openApiPackageManagementDialog() },
            ),
            ActionRow(
                key = "api_module_update",
                title = "检查模块更新",
                subtitle = "从服务器获取 GitHub 最新 Release",
                onClick = { checkApiModuleUpdate() },
            ),
            ActionRow(
                key = "api_module_download",
                title = "下载并安装模块更新",
                subtitle = "下载后交由 Android 安装器确认",
                onClick = { downloadApiModuleUpdate() },
            ),
            ActionRow(
                key = "api_backup_upload",
                title = "备份模块设置到服务器",
                subtitle = "不包含 API Key、账号密码等敏感凭据",
                onClick = { backupApiModuleSettings() },
            ),
            ActionRow(
                key = "api_backup_restore",
                title = "从服务器恢复模块设置",
                subtitle = "恢复前请确认会覆盖本机模块设置",
                onClick = { restoreApiModuleSettings() },
            ),
            ActionRow(
                key = "api_credentials_backup",
                title = "账号密钥加密备份",
                subtitle = "客户端口令加密，服务器只保存密文",
                onClick = { openCredentialBackupDialog() },
            ),
            ActionRow(
                key = "api_cloud_automation",
                title = "云端签到、抽卡与自动阅读",
                subtitle = "上传阅微登录密钥后由服务器执行，可选择最近阅读或自定义图书",
                onClick = { openCloudAutomationDialog() },
            ),
        )
        addLazyItem(lazyListScope, "api_server_settings_card".hashCode()) { itemComposer ->
            renderHostActionCard(apiRows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderRotationCompletionSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("RotationCompletionList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        val snapshot = settings.snapshot()
        val rotationState = RotationUiState.fromActivityOrientation(
            activityProvider()?.requestedOrientation,
            snapshot.rotationReverseEnabled,
        ) ?: rotationUiState ?: RotationUiState.from(snapshot)
        val rows = buildList {
            add(
                ToggleRow(
                    key = ModuleSettings.KEY_ROTATION_AUTO_ENABLED,
                    title = "\u81ea\u52a8\u65cb\u8f6c",
                    checked = rotationState.autoEnabled,
                    checkedProvider = { currentRotationDisplayState().autoEnabled },
                    syncWithSnapshot = true,
                    onChanged = { checked, updateRow ->
                        setRotationBaseEnabled(ModuleSettings.KEY_ROTATION_AUTO_ENABLED, checked, updateRow)
                    },
                ),
            )
            add(
                ToggleRow(
                    key = ModuleSettings.KEY_ROTATION_PORTRAIT_LOCK_ENABLED,
                    title = "\u7ad6\u5411\u9501\u5b9a",
                    checked = rotationState.portraitLockEnabled,
                    checkedProvider = { currentRotationDisplayState().portraitLockEnabled },
                    syncWithSnapshot = true,
                    onChanged = { checked, updateRow ->
                        setRotationBaseEnabled(ModuleSettings.KEY_ROTATION_PORTRAIT_LOCK_ENABLED, checked, updateRow)
                    },
                ),
            )
            add(
                ToggleRow(
                    key = ModuleSettings.KEY_ROTATION_LANDSCAPE_LOCK_ENABLED,
                    title = "\u6a2a\u5411\u9501\u5b9a",
                    checked = rotationState.landscapeLockEnabled,
                    checkedProvider = { currentRotationDisplayState().landscapeLockEnabled },
                    syncWithSnapshot = true,
                    onChanged = { checked, updateRow ->
                        setRotationBaseEnabled(ModuleSettings.KEY_ROTATION_LANDSCAPE_LOCK_ENABLED, checked, updateRow)
                    },
                ),
            )
            add(
                ToggleRow(
                    key = ModuleSettings.KEY_ROTATION_REVERSE_ENABLED,
                    title = "\u53cd\u5411\u65cb\u8f6c",
                    checked = rotationState.reverseEnabled,
                    checkedProvider = { currentRotationDisplayState().reverseEnabled },
                    syncWithSnapshot = true,
                    onChanged = { checked, updateRow ->
                        suppressRotationSnapshotSync()
                        val nextState = currentRotationUiState().copy(reverseEnabled = checked)
                        rotationUiState = nextState
                        updateRow(ModuleSettings.KEY_ROTATION_REVERSE_ENABLED, checked)
                        settings.setRotationReverseEnabled(checked)
                        applyCurrentRotation()
                        checked
                    },
                ),
            )
        }
        addLazyItem(lazyListScope, ROTATION_SWITCHES_ITEM_KEY) { itemComposer ->
            renderHostSettingsCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderAccountSwitchContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("AccountSwitchList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        accountListVersionValue()
        val accounts = accountController.displayAccounts()
        val expandedState = accountSwitchExpandedState(accounts.isNotEmpty())
        addLazyItem(lazyListScope, ACCOUNT_EXPORT_ACTION_ITEM_KEY) { itemComposer ->
            renderHostActionCard(
                listOf(
                    ActionRow(
                        key = "account_export",
                        title = "\u5bfc\u51fa\u51ed\u8bc1",
                        subtitle = "\u5bfc\u51fa\u5f53\u524d\u8d26\u53f7\u7684\u767b\u5f55\u51ed\u8bc1",
                        onClick = ::exportCurrentAccountCredential,
                        onLongClick = ::exportAllAccountCredentials,
                    ),
                    ActionRow(
                        key = "account_import",
                        title = "\u5bfc\u5165\u51ed\u8bc1",
                        subtitle = "\u8bfb\u53d6\u526a\u8d34\u677f\u6216\u6587\u4ef6\u4e2d\u7684\u8d26\u53f7\u51ed\u8bc1",
                        onClick = ::importCredentialFromClipboard,
                        onLongClick = ::openAccountCredentialDocumentPicker,
                    ),
                ),
                itemComposer,
            )
        }
        addLazyItem(lazyListScope, ACCOUNT_SWITCH_ACTION_ITEM_KEY) { itemComposer ->
            val expanded = booleanStateValue(expandedState)
            val switchRows = buildList {
                add(
                    ActionRow(
                        key = "account_switch",
                        title = "\u5207\u6362\u8d26\u53f7",
                        subtitle = if (accounts.isEmpty()) "\u6682\u65e0\u53ef\u5207\u6362\u8d26\u53f7" else "\u5df2\u4fdd\u5b58 ${accounts.size} \u4e2a\u8d26\u53f7",
                        trailing = if (expanded) "\u6536\u8d77" else "\u5c55\u5f00",
                        onClick = {
                            setBooleanState(expandedState, !booleanStateValue(expandedState))
                        },
                    ),
                )
                if (expanded) {
                    accounts.forEach { account ->
                        add(
                            ActionRow(
                                key = "account_${account.accountId}",
                                title = account.accountId.toString(),
                                subtitle = account.nickname.ifBlank { null },
                                trailing = if (account.isCurrent) "\u5df2\u767b\u5f55" else account.identity,
                                onClick = {
                                    if (account.isCurrent) {
                                        showToast("\u5f53\u524d\u8d26\u53f7\u5df2\u767b\u5f55")
                                    } else {
                                        switchToStoredAccount(account.accountId)
                                    }
                                },
                            ),
                        )
                    }
                }
            }
            renderHostActionCard(switchRows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderAccountSecurityExportContent(state: Any, deleteDialogState: Any, composer: Any) {
    accountListVersionValue()
    val accounts = accountController.displayAccounts()
    val expandedState = accountDataExportExpandedState(accounts.isNotEmpty())
    val expanded = booleanStateValue(expandedState)
    val rows = buildList {
        add(
            ActionRow(
                key = "account_data_import",
                title = "\u5bfc\u5165\u6570\u636e",
                subtitle = "\u9009\u62e9\u5df2\u5bfc\u51fa\u7684\u8d26\u53f7\u6570\u636e\u5305\u5e76\u6062\u590d",
                onClick = ::openAccountDataDocumentPicker,
            ),
        )
        add(
            ActionRow(
                key = "account_data_export",
                title = "\u5bfc\u51fa\u6570\u636e",
                subtitle = if (accounts.isEmpty()) {
                    "\u6682\u65e0\u53ef\u5bfc\u51fa\u8d26\u53f7"
                } else {
                    "\u5df2\u4fdd\u5b58 ${accounts.size} \u4e2a\u8d26\u53f7"
                },
                trailing = if (expanded) "\u6536\u8d77" else "\u5c55\u5f00",
                onClick = {
                    setBooleanState(expandedState, !booleanStateValue(expandedState))
                },
                onLongClick = ::exportAllAccountData,
            ),
        )
        if (expanded) {
            accounts.forEach { account ->
                add(
                    ActionRow(
                        key = "account_data_export_${account.accountId}",
                        title = account.accountId.toString(),
                        subtitle = account.nickname.ifBlank { null },
                        trailing = if (account.isCurrent) "\u5df2\u767b\u5f55" else account.identity,
                        onClick = {
                            exportAccountData(account.accountId)
                        },
                        onLongClick = {
                            exportAccountBooks(account.accountId)
                        },
                    ),
                )
            }
        }
    }
    val content = functionProxy("AccountSecurityExportContent", FUNCTION3_CLASS) { args ->
        val innerComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
        renderHostActionCard(rows, innerComposer)
        renderHostDeleteAccountItem(state, deleteDialogState, innerComposer)
        targetUnit()
    }
    method(COLUMN_KT_CLASS, COLUMN_METHOD, 7).invoke(
        null,
        method(SIZE_KT_CLASS, FILL_MAX_WIDTH_DEFAULT_METHOD, 4).invoke(
            null,
            modifierInstance(),
            0f,
            1,
            null,
        ),
        spacedBy(16),
        alignmentStart(),
        content,
        composer,
        0,
        0,
    )
}

internal fun ReaMicroSettingsHook.renderHostDeleteAccountItem(state: Any, deleteDialogState: Any, composer: Any) {
    val deletingAccount = runCatching {
        val uiState = state.method0("getValue")
        uiState.method0("getDeletingAccount") as? Boolean ?: false
    }.getOrDefault(false)
    val onClick = functionProxy("OpenDeleteAccountDialog", FUNCTION0_CLASS) {
        setBooleanState(deleteDialogState, true)
        targetUnit()
    }
    method(ACCOUNT_SECURITY_SCREEN_CLASS, ACCOUNT_SECURITY_DELETE_ITEM_METHOD, 4).invoke(
        null,
        deletingAccount,
        onClick,
        composer,
        48,
    )
}

internal fun ReaMicroSettingsHook.renderOnlineCompletionSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("OnlineCompletionList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        onlineSourceVersionValue()
        onlineEpubStyleVersionValue()
        val sources = listOnlineSources()
        val styleRows = listOf(
            ActionRow(
                key = "online_download_style",
                title = ONLINE_DOWNLOAD_STYLE_TITLE,
                subtitle = onlineDownloadStyleSummary(),
                singleLineSubtitle = true,
                onClick = { openNestedInjectedRoute(InjectedRoute.OnlineDownloadStyleSettings) },
            ),
        )
        val rows = buildList {
            add(
                ActionRow(
                    key = "online_source_add",
                    title = "添加在线源",
                    subtitle = "点击读取剪贴板链接，长按选择源文件",
                    onClick = ::importOnlineSourceFromClipboard,
                    onLongClick = ::openOnlineSourceDocumentPicker,
                ),
            )
            if (sources.isEmpty()) {
                add(
                    ActionRow(
                        key = "online_source_empty",
                        title = "暂无在线源",
                        subtitle = "在线源与关联搜索源分开管理",
                    ),
                )
            } else {
                sources.forEach { source ->
                    add(
                        ActionRow(
                            key = "online_source_${source.id}",
                            title = source.name.compactOnlineSourceLine(),
                            subtitle = onlineSourceSubtitle(source),
                            onClick = { openOnlineSourceManagementDialog(source) },
                            onLongClick = { openOnlineSourceManagementDialog(source) },
                            trailingContent = { itemComposer ->
                                renderOnlineSourceSwitch(source, itemComposer)
                            },
                        ),
                    )
                }
            }
        }
        addLazyItem(lazyListScope, ONLINE_DOWNLOAD_STYLE_ENTRY_ITEM_KEY) { itemComposer ->
            renderHostActionCard(styleRows, itemComposer)
        }
        addLazyItem(lazyListScope, ONLINE_COMPLETION_CONTENT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

/** 「下载配置」页：五类成书样式各一行，点进去是与高亮样式一致的样式列表。 */
internal fun ReaMicroSettingsHook.renderOnlineDownloadStyleSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("OnlineDownloadStyleList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        onlineEpubStyleVersionValue()
        fontLibraryVersionValue()
        val styleSettings = onlineEpubStyleSettings()
        val rows = OnlineEpubStyleKind.entries.map { kind ->
            ActionRow(
                key = "online_epub_style_${kind.id}",
                title = kind.title,
                subtitle = onlineEpubStyleRowSubtitle(styleSettings, kind),
                singleLineSubtitle = true,
                onClick = { openNestedInjectedRoute(InjectedRoute.OnlineEpubStyleList(kind)) },
            )
        }
        addLazyItem(lazyListScope, ONLINE_DOWNLOAD_STYLE_CONTENT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderAiConfigSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("AiConfigList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        aiApiVersionValue()
        val configs = listAiApiConfigs()
        val managementRows = listOf(
            ActionRow(
                key = "dictionary_settings",
                title = DICTIONARY_SETTINGS_TITLE,
                subtitle = dictionarySettingsSummary(),
                onClick = { openNestedInjectedRoute(InjectedRoute.DictionarySettings) },
            ),
            ActionRow(
                key = "image_settings",
                title = IMAGE_SETTINGS_TITLE,
                subtitle = imageSettingsSummary(),
                onClick = { openNestedInjectedRoute(InjectedRoute.ImageSettings) },
            ),
        )
        val rows = buildList {
            add(
                ActionRow(
                    key = "ai_api_add",
                    title = "\u6dfb\u52a0 API",
                    subtitle = "OpenAI \u517c\u5bb9\u63a5\u53e3\uff1abase_url\u3001api_key\u3001model",
                    onClick = { openAiApiConfigDialog() },
                ),
            )
            if (configs.isEmpty()) {
                add(
                    ActionRow(
                        key = "ai_api_empty",
                        title = "\u6682\u65e0 API",
                        subtitle = "\u6dfb\u52a0\u5e76\u6d4b\u8bd5\u901a\u8fc7\u540e\u4f1a\u81ea\u52a8\u51fa\u73b0\u5728\u5217\u8868\u4e2d",
                    ),
                )
            } else {
                configs.forEach { config ->
                    add(
                        ActionRow(
                            key = "ai_api_${config.id}",
                            title = config.displayName.compactOnlineSourceLine(),
                            subtitle = aiApiSubtitle(config),
                            onClick = { showToast(config.baseUrl) },
                            onLongClick = { openAiApiConfigDialog(config) },
                            trailingContent = { itemComposer ->
                                renderAiApiSwitch(config, itemComposer)
                            },
                        ),
                    )
                }
            }
        }
        addLazyItem(lazyListScope, DICTIONARY_SETTINGS_CONTENT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(managementRows, itemComposer)
        }
        addLazyItem(lazyListScope, AI_CONFIG_CONTENT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderDictionarySettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("DictionarySettingsList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        aiApiVersionValue()
        val dictionarySettings = AiApiStore.dictionarySettings(activityProvider()?.applicationContext)
        val rows = listOf(
            ActionRow(
                key = "dictionary_api_picker",
                title = "\u0041\u0050\u0049 \u914d\u7f6e",
                subtitle = dictionaryApiSelectionSummary(),
                onClick = { openNestedInjectedRoute(InjectedRoute.DictionaryApiPicker) },
            ),
            ActionRow(
                key = "dictionary_preset_picker",
                title = "\u8bcd\u5178\u9884\u8bbe",
                subtitle = dictionaryPresetSelectionSummary(),
                onClick = { openNestedInjectedRoute(InjectedRoute.DictionaryPresetPicker) },
            ),
        )
        addLazyItem(lazyListScope, DICTIONARY_SETTINGS_CONTENT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        addLazyItem(lazyListScope, DICTIONARY_THINKING_SWITCH_ITEM_KEY) { itemComposer ->
            renderHostSettingsCard(
                listOf(
                    ToggleRow(
                        key = "dictionary_disable_thinking",
                        title = "\u7981\u7528\u601d\u8003",
                        checked = dictionarySettings.disableThinking,
                        onChanged = { checked, _ ->
                            AiApiStore.setDictionaryDisableThinking(activityProvider()?.applicationContext, checked)
                            bumpAiApiVersion()
                            checked
                        },
                    ),
                    ToggleRow(
                        key = "dictionary_single_use_preset",
                        title = "\u5355\u6b21\u5207\u6362",
                        checked = dictionarySettings.singleUsePreset,
                        onChanged = { checked, _ ->
                            AiApiStore.setDictionarySingleUsePreset(activityProvider()?.applicationContext, checked)
                            bumpAiApiVersion()
                            checked
                        },
                    ),
                ),
                itemComposer,
            )
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderImageSettingsContent(innerPaddings: Any, composer: Any) {
    val listContent = functionProxy("ImageSettingsList", FUNCTION1_CLASS) { args ->
        val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        aiApiVersionValue()
        val rows = listOf(
            ActionRow(
                key = "image_api_picker",
                title = "\u0041\u0050\u0049 \u914d\u7f6e",
                subtitle = imageApiSelectionSummary(),
                onClick = { openNestedInjectedRoute(InjectedRoute.ImageApiPicker) },
            ),
            ActionRow(
                key = "image_cover_preset_picker",
                title = AiImagePresetTarget.Cover.title,
                subtitle = imagePresetSelectionSummary(AiImagePresetTarget.Cover),
                onClick = { openNestedInjectedRoute(InjectedRoute.ImagePresetPicker(AiImagePresetTarget.Cover)) },
            ),
            ActionRow(
                key = "image_banner_preset_picker",
                title = AiImagePresetTarget.Banner.title,
                subtitle = imagePresetSelectionSummary(AiImagePresetTarget.Banner),
                onClick = { openNestedInjectedRoute(InjectedRoute.ImagePresetPicker(AiImagePresetTarget.Banner)) },
            ),
        )
        addLazyItem(lazyListScope, IMAGE_SETTINGS_CONTENT_ITEM_KEY) { itemComposer ->
            renderHostActionCard(rows, itemComposer)
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

internal fun ReaMicroSettingsHook.renderHostLazyColumn(innerPaddings: Any, listContent: Any, composer: Any) {
    method(LAZY_DSL_KT_CLASS, LAZY_COLUMN_METHOD, 13).invoke(
        null,
        pageModifier(innerPaddings),
        null,
        null,
        false,
        spacedBy(16),
        null,
        null,
        false,
        null,
        listContent,
        composer,
        0,
        494,
    )
}

internal fun ReaMicroSettingsHook.renderHostSettingsCard(rows: List<ToggleRow>, composer: Any) {
    val content = functionProxy("ModuleSettingsCardContent", FUNCTION3_CLASS) { args ->
        val innerComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
        val stateUpdaters = mutableMapOf<String, (Boolean) -> Unit>()
        val pendingStateUpdates = mutableMapOf<String, Boolean>()
        var hasVisibleRow = false
        rows.forEach { row ->
            val visible = row.visibleProvider()
            if (hasVisibleRow && visible) renderHostDivider(innerComposer)
            renderHostSwitchRow(row, innerComposer, stateUpdaters, pendingStateUpdates)
            if (visible) hasVisibleRow = true
        }
        targetUnit()
    }
    method(COLUMN_KT_CLASS, COLUMN_METHOD, 7).invoke(
        null,
        settingsCardModifier(composer),
        arrangementTop(),
        alignmentStart(),
        content,
        composer,
        0,
        0,
    )
}

internal fun ReaMicroSettingsHook.renderHostActionCard(rows: List<ActionRow>, composer: Any) {
    val content = functionProxy("ModuleActionCardContent", FUNCTION3_CLASS) { args ->
        val innerComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
        rows.forEachIndexed { index, row ->
            if (index > 0) renderHostDivider(innerComposer)
            renderHostActionRow(row, innerComposer)
        }
        targetUnit()
    }
    method(COLUMN_KT_CLASS, COLUMN_METHOD, 7).invoke(
        null,
        settingsCardModifier(composer),
        arrangementTop(),
        alignmentStart(),
        content,
        composer,
        0,
        0,
    )
}

internal fun ReaMicroSettingsHook.renderHostActionRow(row: ActionRow, composer: Any) {
    val headline = composableLambda(row.key.hashCode(), FUNCTION2_CLASS) { args ->
        val innerComposer = args?.getOrNull(0) ?: return@composableLambda targetUnit()
        renderHostText(row.title, innerComposer, resolvePreviewFontFamily(row.titleFontSelection.orEmpty()))
        targetUnit()
    }
    val supporting = row.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
        composableLambda(row.key.hashCode() xor ACTION_SUPPORTING_KEY_MASK, FUNCTION2_CLASS) { args ->
            val innerComposer = args?.getOrNull(0) ?: return@composableLambda targetUnit()
            // Preset prompt previews must be single-line; ordinary explanatory rows keep
            // host defaults so settings copy can still wrap naturally.
            renderHostSupportingText(subtitle, innerComposer, row.singleLineSubtitle)
            targetUnit()
        }
    }
    val trailingText = row.trailing?.takeIf { it.isNotBlank() }
    val trailing = when {
        row.trailingContent != null -> composableLambda(row.key.hashCode() xor ACTION_TRAILING_KEY_MASK, FUNCTION2_CLASS) { args ->
            val innerComposer = args?.getOrNull(0) ?: return@composableLambda targetUnit()
            row.trailingContent.invoke(innerComposer)
            targetUnit()
        }
        trailingText != null -> composableLambda(row.key.hashCode() xor ACTION_TRAILING_KEY_MASK, FUNCTION2_CLASS) { args ->
            val innerComposer = args?.getOrNull(0) ?: return@composableLambda targetUnit()
            renderHostTrailingText(trailingText, innerComposer)
            targetUnit()
        }
        else -> null
    }
    val baseModifier = rowModifier(true, if (supporting == null) 56 else 68)
    val modifier = when {
        row.onClick != null && row.onLongClick != null ->
            combinedClickableModifier(
                baseModifier = baseModifier,
                name = "ActionRow${row.key}",
                onClick = row.onClick,
                onLongClick = row.onLongClick,
            )
        row.onClick != null ->
            clickableModifier(baseModifier, "ActionRow${row.key}") { row.onClick.invoke() }
        else -> baseModifier
    }
    method(LIST_ITEM_KT_CLASS, LIST_ITEM_METHOD, 12).invoke(
        null,
        headline,
        modifier,
        null,
        supporting,
        null,
        trailing,
        transparentListItemColors(composer),
        0f,
        0f,
        composer,
        196614,
        listItemDefaultMask(
            hasSupporting = supporting != null,
            hasTrailing = trailing != null,
        ),
    )
}

internal fun ReaMicroSettingsHook.renderHostSwitchRow(
    row: ToggleRow,
    composer: Any,
    stateUpdaters: MutableMap<String, (Boolean) -> Unit>,
    pendingStateUpdates: MutableMap<String, Boolean>,
) {
    val headline = composableLambda(row.key.hashCode(), FUNCTION2_CLASS) { args ->
        val innerComposer = args?.getOrNull(0) ?: return@composableLambda targetUnit()
        renderHostText(row.title, innerComposer)
        targetUnit()
    }
    val trailing = composableLambda(row.key.hashCode() xor SWITCH_TRAILING_KEY_MASK, FUNCTION2_CLASS) { args ->
        val innerComposer = args?.getOrNull(0) ?: return@composableLambda targetUnit()
        renderHostSwitch(row, innerComposer, stateUpdaters, pendingStateUpdates)
        targetUnit()
    }
    method(LIST_ITEM_KT_CLASS, LIST_ITEM_METHOD, 12).invoke(
        null,
        headline,
        rowModifier(row.visibleProvider()),
        null,
        null,
        null,
        trailing,
        transparentListItemColors(composer),
        0f,
        0f,
        composer,
        196614,
        412,
    )
}

internal fun ReaMicroSettingsHook.renderHostText(text: String, composer: Any, fontFamily: Any? = null) {
    method(TEXT_KT_CLASS, TEXT_METHOD, 22).invoke(
        null,
        text,
        null,
        colorScheme(composer).longMethod("getOnBackground"),
        null,
        0L,
        null,
        null,
        fontFamily,
        0L,
        null,
        null,
        0L,
        0,
        false,
        0,
        0,
        null,
        typography(composer).method0("getLabelLarge"),
        composer,
        0,
        0,
        if (fontFamily == null) TEXT_DEFAULT_MASK else TEXT_WITH_FONT_FAMILY_MASK,
    )
}

internal fun ReaMicroSettingsHook.renderHostSupportingText(text: String, composer: Any, singleLine: Boolean = false) {
    method(TEXT_KT_CLASS, TEXT_METHOD, 22).invoke(
        null,
        text,
        null,
        runCatching { colorScheme(composer).longMethod("getOnSurfaceVariant") }
            .getOrElse { colorScheme(composer).longMethod("getOnBackground") },
        null,
        0L,
        null,
        null,
        null,
        0L,
        null,
        null,
        0L,
        0,
        false,
        if (singleLine) 1 else 0,
        if (singleLine) 1 else 0,
        null,
        typography(composer).method0("getBodyMedium"),
        composer,
        0,
        0,
        if (singleLine) TEXT_SINGLE_LINE_MASK else TEXT_DEFAULT_MASK,
    )
}

internal fun ReaMicroSettingsHook.renderHostTrailingText(text: String, composer: Any) {
    method(TEXT_KT_CLASS, TEXT_METHOD, 22).invoke(
        null,
        text,
        null,
        runCatching { colorScheme(composer).longMethod("getOnSurfaceVariant") }
            .getOrElse { colorScheme(composer).longMethod("getOnBackground") },
        null,
        0L,
        null,
        null,
        null,
        0L,
        null,
        null,
        0L,
        0,
        false,
        0,
        0,
        null,
        typography(composer).method0("getLabelLarge"),
        composer,
        0,
        0,
        TEXT_DEFAULT_MASK,
    )
}

internal fun ReaMicroSettingsHook.renderHostSwitch(
    row: ToggleRow,
    composer: Any,
    stateUpdaters: MutableMap<String, (Boolean) -> Unit>,
    pendingStateUpdates: MutableMap<String, Boolean>,
) {
    val targetChecked = row.checkedProvider()
    val state = rememberBooleanState(composer, targetChecked)
    fun updateChecked(value: Boolean) {
        state.javaClass.methods.firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
            ?.invoke(state, value)
    }
    val rememberedChecked = state.method0("getValue") as? Boolean ?: targetChecked
    val canSyncWithSnapshot = !row.syncWithSnapshot ||
        System.currentTimeMillis() >= suppressRotationSnapshotSyncUntilMs
    val checked = if (row.syncWithSnapshot && canSyncWithSnapshot && rememberedChecked != targetChecked) {
        updateChecked(targetChecked)
        targetChecked
    } else {
        rememberedChecked
    }
    fun updateRow(key: String, value: Boolean) {
        if (key == row.key) {
            updateChecked(value)
        } else {
            val updater = stateUpdaters[key]
            if (updater != null) {
                updater(value)
            } else {
                pendingStateUpdates[key] = value
            }
        }
    }
    stateUpdaters[row.key] = ::updateChecked
    pendingStateUpdates.remove(row.key)?.let(::updateChecked)
    val onCheckedChange = functionProxy("ModuleSwitch${row.key}", FUNCTION1_CLASS) { args ->
        val newValue = args?.getOrNull(0) as? Boolean ?: return@functionProxy targetUnit()
        updateRow(row.key, row.onChanged(newValue, ::updateRow))
        targetUnit()
    }
    method(SWITCH_KT_CLASS, SWITCH_METHOD, 10).invoke(
        null,
        checked,
        onCheckedChange,
        switchModifier(),
        null,
        false,
        switchColors(composer),
        null,
        composer,
        0,
        88,
    )
}

internal fun ReaMicroSettingsHook.renderHostActionSwitch(
    key: String,
    checked: Boolean,
    composer: Any,
    onChanged: (Boolean) -> Unit,
) {
    val state = rememberBooleanState(composer, checked)
    fun updateChecked(value: Boolean) {
        state.javaClass.methods.firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
            ?.invoke(state, value)
    }
    val rememberedChecked = state.method0("getValue") as? Boolean ?: checked
    if (rememberedChecked != checked) {
        updateChecked(checked)
    }
    val onCheckedChange = functionProxy("ActionSwitch$key", FUNCTION1_CLASS) { args ->
        val newValue = args?.getOrNull(0) as? Boolean ?: return@functionProxy targetUnit()
        updateChecked(newValue)
        onChanged(newValue)
        targetUnit()
    }
    method(SWITCH_KT_CLASS, SWITCH_METHOD, 10).invoke(
        null,
        checked,
        onCheckedChange,
        switchModifier(),
        null,
        false,
        switchColors(composer),
        null,
        composer,
        0,
        88,
    )
}

internal fun ReaMicroSettingsHook.renderHostDivider(composer: Any) {
    method(DIVIDER_KT_CLASS, DASHED_DIVIDER_METHOD, 6).invoke(
        null,
        dividerModifier(),
        0L,
        0f,
        composer,
        0,
        6,
    )
}

internal fun ReaMicroSettingsHook.addLazyItem(lazyListScope: Any, key: Int, block: (Any) -> Unit) {
    val content = composableLambda(key, FUNCTION3_CLASS) { args ->
        val composer = args?.getOrNull(1) ?: return@composableLambda targetUnit()
        block(composer)
        targetUnit()
    }
    val itemMethod = lazyItemDefaultMethod ?: method(LAZY_LIST_SCOPE_CLASS, LAZY_ITEM_DEFAULT_METHOD, 6)
    itemMethod.invoke(null, lazyListScope, null, null, content, 3, null)
}

internal fun ReaMicroSettingsHook.settingsCardModifier(composer: Any): Any {
    updateModuleDialogTheme(composer)
    val shape = method(SHAPE_KT_CLASS, ROUNDED_SHAPE_METHOD, 0).invoke(null)
    val scheme = colorScheme(composer)
    val clipped = method(CLIP_KT_CLASS, CLIP_METHOD, 2).invoke(null, modifierInstance(), shape)
    val bordered = method(BORDER_KT_CLASS, BORDER_METHOD, 4).invoke(
        null,
        clipped,
        udp(0.8),
        method(THEME_KT_CLASS, BORDER_VARIANT_METHOD, 1).invoke(null, scheme),
        shape,
    )
    val background = method(BACKGROUND_KT_CLASS, BACKGROUND_DEFAULT_METHOD, 5).invoke(
        null,
        bordered,
        method(THEME_KT_CLASS, BACKGROUND_AUTO_METHOD, 1).invoke(null, scheme),
        null,
        2,
        null,
    )
    return method(SIZE_KT_CLASS, FILL_MAX_WIDTH_DEFAULT_METHOD, 4).invoke(null, background, 0f, 1, null)
}

internal fun ReaMicroSettingsHook.rowModifier(visible: Boolean): Any =
    method(SIZE_KT_CLASS, HEIGHT_METHOD, 2).invoke(null, modifierInstance(), udp(if (visible) 56 else 0))

internal fun ReaMicroSettingsHook.rowModifier(visible: Boolean, height: Int): Any =
    method(SIZE_KT_CLASS, HEIGHT_METHOD, 2).invoke(null, modifierInstance(), udp(if (visible) height else 0))

internal fun ReaMicroSettingsHook.injectedRouteState(initialValue: InjectedRoute): Any {
    injectedRouteUiState?.let { return it }
    val state = mutableState(initialValue).also { injectedRouteUiState = it }
    resolveRouteStateAccessors(state)
    return state
}

internal fun ReaMicroSettingsHook.resolveRouteStateAccessors(state: Any) {
    if (injectedRouteGetter != null && injectedRouteSetter != null) return
    runCatching {
        val clazz = state.javaClass
        val getter = clazz.methods.firstOrNull {
            it.parameterTypes.isEmpty() &&
                (it.name == "getValue" || it.name.startsWith("getValue-")) &&
                it.returnType != java.lang.Void.TYPE && it.returnType != Int::class.javaPrimitiveType &&
                it.returnType != java.lang.Boolean.TYPE
        }?.apply { isAccessible = true }
        val setter = clazz.methods.firstOrNull {
            it.name == "setValue" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] != Int::class.javaPrimitiveType &&
                it.parameterTypes[0] != java.lang.Boolean.TYPE
        }?.apply { isAccessible = true }
        if (getter != null) injectedRouteGetter = getter
        if (setter != null) injectedRouteSetter = setter
    }
}

internal fun ReaMicroSettingsHook.routeStateValue(state: Any): InjectedRoute? {
    if (injectedRouteGetter == null) resolveRouteStateAccessors(state)
    return runCatching { injectedRouteGetter?.invoke(state) as? InjectedRoute }.getOrNull()
}

internal fun ReaMicroSettingsHook.setInjectedRouteState(route: InjectedRoute?) {
    val state = injectedRouteUiState ?: return
    if (injectedRouteSetter == null) resolveRouteStateAccessors(state)
    runCatching { injectedRouteSetter?.invoke(state, route) }
}
