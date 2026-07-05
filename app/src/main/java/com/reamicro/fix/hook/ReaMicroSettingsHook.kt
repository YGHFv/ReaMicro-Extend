package com.reamicro.fix.hook

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Dialog
import android.app.PendingIntent
import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.NinePatchDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.reamicro.fix.ai.AiApiConfig
import com.reamicro.fix.ai.AiApiStore
import com.reamicro.fix.ai.AiDictionaryPreset
import com.reamicro.fix.ai.AiImagePreset
import com.reamicro.fix.ai.AiImagePresetTarget
import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.association.provider.AssociationSearchProviderRegistry
import com.reamicro.fix.association.provider.ExternalSourceLoader
import com.reamicro.fix.association.provider.YouShuLoginCookies
import com.reamicro.fix.association.provider.YouShuLoginState
import com.reamicro.fix.online.OnlineSourceAuth
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.settings.ReaderHighlightBookContext
import com.reamicro.fix.settings.ReaderHighlightSettingsSnapshot
import com.reamicro.fix.settings.ReaderHighlightRule
import com.reamicro.fix.settings.ReaderHighlightRuleType
import com.reamicro.fix.settings.ReaderHighlightStyle
import com.reamicro.fix.settings.XposedModuleSettings
import com.reamicro.fix.settings.readerHighlightBookIdentityMatches
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess
import org.json.JSONArray
import org.json.JSONObject

/**
 * Injects the module settings UI into the host About/Settings surface.
 *
 * The host is Compose, but this module cannot compile against host internals, so most UI is
 * created through reflected Compose calls and small value objects kept in this class.
 */
class ReaMicroSettingsHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settings: XposedModuleSettings,
    private val onGlobalFontChanged: () -> Unit = {},
) {
    private val accountController = AccountCompletionController(classLoader, activityProvider)
    private val settingsBuildDepth = ThreadLocal.withInitial { 0 }
    private val itemCount = ThreadLocal.withInitial { 0 }
    private val injectingModuleItem = ThreadLocal.withInitial { false }
    private val settingsEntryTitleOverride = ThreadLocal.withInitial<String?> { null }
    private val navigatingModuleRoute = ThreadLocal.withInitial { false }
    private val poppingInjectedRoute = ThreadLocal.withInitial { false }
    private val methodCache = mutableMapOf<String, Method>()
    private var lazyItemDefaultMethod: Method? = null
    @Volatile private var currentSettingsNavGraphScope: Any? = null
    @Volatile private var currentSettingsNavController: Any? = null
    @Volatile private var injectedRouteStack: List<InjectedRoute> = emptyList()
    @Volatile private var injectedRouteUiState: Any? = null
    @Volatile private var fontLibraryVersionUiState: Any? = null
    @Volatile private var onlineSourceVersionUiState: Any? = null
    @Volatile private var aiApiVersionUiState: Any? = null
    @Volatile private var readerHighlightVersionUiState: Any? = null
    @Volatile private var profileBackgroundVersionUiState: Any? = null
    @Volatile private var pendingDeleteFontUiState: Any? = null
    @Volatile private var lastFontImportToken: String = ""
    @Volatile private var lastFontImportAtMs: Long = 0L
    @Volatile private var lastOnlineSourceImportToken: String = ""
    @Volatile private var lastOnlineSourceImportAtMs: Long = 0L
    @Volatile private var lastExternalImportToken: String = ""
    @Volatile private var lastExternalImportAtMs: Long = 0L
    @Volatile private var pendingDeleteOnlineSourceId: String = ""
    @Volatile private var pendingDeleteOnlineSourceAtMs: Long = 0L
    @Volatile private var pendingHighlightNinePatchInputRef: WeakReference<EditText>? = null
    private val previewFontFamilyCache = HashMap<String, Any>()
    private val failedPreviewFontFamilyLogKeys = HashSet<String>()
    private val fontFilesCacheLock = Any()
    @Volatile private var cachedFontFilesVersion: Int = Int.MIN_VALUE
    @Volatile private var cachedFontFilesAtMs: Long = 0L
    @Volatile private var cachedFontFiles: List<File> = emptyList()
    @Volatile private var suppressRotationSnapshotSyncUntilMs: Long = 0L
    @Volatile private var rotationUiState: RotationUiState? = null
    @Volatile private var accountListVersionUiState: Any? = null
    @Volatile private var associationExpandedUiState: Any? = null
    @Volatile private var readerExpandedUiState: Any? = null
    @Volatile private var fontExpandedUiState: Any? = null
    @Volatile private var accountExpandedUiState: Any? = null
    @Volatile private var cloudExpandedUiState: Any? = null
    @Volatile private var accountSwitchExpandedUiState: Any? = null
    @Volatile private var accountDataExportExpandedUiState: Any? = null
    @Volatile private var rotationExpandedUiState: Any? = null

    fun install() {
        activeInstance = this
        hookStringResource()
        hookNavGraphScope()
        hookAboutScreen()
        hookSettingsListBuilder()
        hookLazyListItem()
        hookFontDocumentPickerResult()
        hookExternalSourceImportIntent()
        hookHostAccountSignOut()
        hookAccountSecurityScreen()
    }

    private fun hookStringResource() {
        runCatching {
            val stringResourcesClass = cls(STRING_RESOURCES_CLASS)
            XposedBridge.hookAllMethods(stringResourcesClass, STRING_RESOURCE_METHOD, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val overrideTitle = settingsEntryTitleOverride.get() ?: return
                    if (param.result == HOST_ABOUT_TITLE) {
                        param.result = overrideTitle
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook stringResource: ${it.stackTraceToString()}")
        }
    }

    private fun hookNavGraphScope() {
        runCatching {
            val navGraphScopeClass = cls(NAV_GRAPH_SCOPE_CLASS)
            XposedBridge.hookAllMethods(navGraphScopeClass, "navigate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val route = param.args?.getOrNull(0) ?: return
                    if (route.javaClass.name == ROUTE_ABOUT_CLASS && navigatingModuleRoute.get() != true) {
                        injectedRouteStack = emptyList()
                        setInjectedRouteState(null)
                        clearPendingDeleteFontSelection()
                    }
                }
            })
            XposedBridge.hookAllMethods(navGraphScopeClass, "popBackStack", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // Injected module pages reuse the host About route, so back navigation must
                    // consume our nested stack before the host NavController pops its own route.
                    if (poppingInjectedRoute.get() == true) return
                    if (handleNestedInjectedBack()) {
                        param.result = true
                        return
                    }
                    val nextStack = injectedRouteStack.dropLast(1)
                    injectedRouteStack = nextStack
                    setInjectedRouteState(nextStack.lastOrNull())
                }
            })
            XposedBridge.hookAllMethods(cls(NAV_CONTROLLER_CLASS), "popBackStack", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (poppingInjectedRoute.get() == true) return
                    if (!isCurrentSettingsNavController(param.thisObject)) return
                    if (handleNestedInjectedBack()) {
                        param.result = true
                    }
                }
            })
            XposedBridge.hookAllMethods(cls(NAV_CONTROLLER_CLASS), "navigateUp", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (poppingInjectedRoute.get() == true) return
                    if (!isCurrentSettingsNavController(param.thisObject)) return
                    if (handleNestedInjectedBack()) {
                        param.result = true
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook NavGraphScope: ${it.stackTraceToString()}")
        }
    }

    private fun hookAboutScreen() {
        runCatching {
            val aboutScreen = method(ABOUT_SCREEN_CLASS, ABOUT_SCREEN_METHOD, 2)
            XposedBridge.hookMethod(aboutScreen, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val route = activeInjectedRoute() ?: return
                    val composer = param.args?.getOrNull(0) ?: return
                    runCatching {
                        renderInjectedSettingsScreen(route, composer)
                        param.result = null
                    }.onFailure {
                        injectedRouteStack = emptyList()
                        XposedBridge.log("$LOG_PREFIX failed to render injected settings screen: ${it.stackTraceToString()}")
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook AboutScreen: ${it.stackTraceToString()}")
        }
    }

    private fun hookSettingsListBuilder() {
        runCatching {
            val buildMethod = resolveSettingsListBuilderMethod()
            XposedBridge.hookMethod(buildMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // Track the host settings LazyColumn build so injected rows appear at a
                    // stable position even when upstream adds or removes nearby settings.
                    currentSettingsNavGraphScope = param.args?.firstOrNull { it?.javaClass?.name == NAV_GRAPH_SCOPE_CLASS }
                        ?: param.args?.getOrNull(0)
                    currentSettingsNavController = runCatching {
                        currentSettingsNavGraphScope?.method0("getNavController")
                    }.getOrNull()
                    settingsBuildDepth.set((settingsBuildDepth.get() ?: 0) + 1)
                    itemCount.set(0)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val depth = ((settingsBuildDepth.get() ?: 0) - 1).coerceAtLeast(0)
                    settingsBuildDepth.set(depth)
                    if (depth == 0) itemCount.set(0)
                }
            })
            XposedBridge.log("$LOG_PREFIX ReaMicro settings list hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook settings list: ${it.stackTraceToString()}")
        }
    }

    // 设置页 LazyColumn 构建函数是 R8 生成的 synthetic lambda，名称会随阅微版本变化。
    // 2.1 是 "SettingsScreen$lambda$0$0$1$0"，2.2 是 "SettingsScreen$lambda$0$1$0$0"。
    // 这里按稳定签名解析：首参是宿主 NavGraphScope，末参是 Compose LazyListScope。
    private fun resolveSettingsListBuilderMethod(): Method {
        val settingsClass = cls(SETTINGS_SCREEN_CLASS)
        val lazyListScopeClass = cls(LAZY_LIST_SCOPE_CLASS)
        val navGraphScopeClass = cls(NAV_GRAPH_SCOPE_CLASS)
        val exact = runCatching {
            method(SETTINGS_SCREEN_CLASS, SETTINGS_LIST_BUILDER_METHOD, 4)
        }.getOrNull()
        if (exact != null) return exact
        val candidates = settingsClass.declaredMethods.filter { candidate ->
            val params = candidate.parameterTypes
            candidate.name.startsWith("SettingsScreen") &&
                params.isNotEmpty() &&
                params.first() == navGraphScopeClass &&
                params.last() == lazyListScopeClass
        }
        val resolved = candidates.firstOrNull { it.parameterTypes.size == 4 }
            ?: candidates.firstOrNull()
            ?: error("settings list builder lambda not found in $SETTINGS_SCREEN_CLASS")
        resolved.isAccessible = true
        XposedBridge.log("$LOG_PREFIX resolved settings list builder: ${resolved.name}")
        return resolved
    }

    private fun hookLazyListItem() {
        runCatching {
            val lazyListScopeClass = cls(LAZY_LIST_SCOPE_CLASS)
            val method = lazyListScopeClass.declaredMethods.firstOrNull {
                it.name == LAZY_ITEM_DEFAULT_METHOD && it.parameterTypes.size == 6
            } ?: error("LazyListScope.item\$default not found")
            method.isAccessible = true
            lazyItemDefaultMethod = method
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if ((settingsBuildDepth.get() ?: 0) <= 0 || injectingModuleItem.get() == true) return
                    val count = (itemCount.get() ?: 0) + 1
                    itemCount.set(count)
                    if (count == INSERT_AFTER_SETTINGS_ITEM_COUNT) {
                        insertModuleSettingsItem(param.args[0] ?: return)
                    } else if (count == INSERT_BEFORE_SIGN_OUT_ITEM_COUNT) {
                        insertAccountSettingsItem(param.args[0] ?: return)
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX LazyList settings item hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook LazyList item: ${it.stackTraceToString()}")
        }
    }

    private fun insertModuleSettingsItem(lazyListScope: Any) {
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

    private fun insertAccountSettingsItem(lazyListScope: Any) {
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

    private fun renderSettingsEntry(
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

    private fun renderNestedSettingsEntry(
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

    private fun openInjectedRouteViaHostNavigation(route: InjectedRoute, navGraphScopeOverride: Any? = null): Boolean {
        val navGraphScope = navGraphScopeOverride ?: currentSettingsNavGraphScope ?: return false
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

    private fun openNestedInjectedRoute(route: InjectedRoute): Boolean {
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

    private fun activeInjectedRoute(): InjectedRoute? =
        currentInjectedRoute()

    private fun currentInjectedRoute(): InjectedRoute? =
        injectedRouteUiState?.let(::routeStateValue) ?: injectedRouteStack.lastOrNull()

    private fun refreshCurrentInjectedRoute() {
        currentInjectedRoute()?.let { route -> setInjectedRouteState(route) }
    }

    private fun navigateBackFromInjectedRoute() {
        val navGraphScope = currentSettingsNavGraphScope
        val stack = injectedRouteStack
        if (handleNestedInjectedBack()) return
        injectedRouteStack = stack.dropLast(1)
        setInjectedRouteState(null)
        clearPendingDeleteFontSelection()
        popHostInjectedRoute(navGraphScope)
    }

    private fun handleNestedInjectedBack(): Boolean {
        val currentRoute = currentInjectedRoute()
        if (currentRoute !is InjectedRoute.FontPicker &&
            currentRoute !is InjectedRoute.ReaderBookHighlightRules &&
            currentRoute !is InjectedRoute.ReaderBookOnlyHighlightRules &&
            currentRoute !is InjectedRoute.ReaderBookGlobalHighlightRules &&
            currentRoute != InjectedRoute.FontLibrary &&
            currentRoute !in MODULE_CHILD_ROUTES &&
            currentRoute !in READER_CHILD_ROUTES &&
            currentRoute !in AI_CHILD_ROUTES
        ) return false
        if (currentRoute == InjectedRoute.FontLibrary) clearPendingDeleteFontSelection()
        val nextStack = injectedRouteStack.dropLast(1)
            .takeIf { it.isNotEmpty() }
            ?: listOf(
                when {
                    currentRoute is InjectedRoute.ReaderBookHighlightRules -> InjectedRoute.ReaderHighlightTextSettings
                    currentRoute is InjectedRoute.ReaderBookOnlyHighlightRules -> InjectedRoute.ReaderHighlightTextSettings
                    currentRoute is InjectedRoute.ReaderBookGlobalHighlightRules -> InjectedRoute.ReaderBookHighlightRules(currentRoute.bookKey, currentRoute.bookTitle)
                    currentRoute in READER_CHILD_ROUTES -> InjectedRoute.ReaderCompletionSettings
                    currentRoute in MODULE_CHILD_ROUTES -> InjectedRoute.ModuleSettings
                    currentRoute in AI_CHILD_ROUTES -> InjectedRoute.AiConfigSettings
                    else -> InjectedRoute.FontSettings
                },
            )
        injectedRouteStack = nextStack
        setInjectedRouteState(nextStack.last())
        XposedBridge.log("$LOG_PREFIX nested settings back: $currentRoute -> ${nextStack.last()}")
        return true
    }

    private fun isCurrentSettingsNavController(target: Any?): Boolean {
        if (target == null) return false
        if (currentSettingsNavController === target) return true
        val resolved = runCatching {
            currentSettingsNavGraphScope?.method0("getNavController")
        }.getOrNull()
        if (resolved != null) currentSettingsNavController = resolved
        return resolved === target
    }

    private fun popHostInjectedRoute(navGraphScope: Any?) {
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

    private fun renderInjectedSettingsScreen(route: InjectedRoute, composer: Any) {
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
                InjectedRoute.ReaderHighlightSettings -> renderReaderHighlightSettingsContent(innerPaddings, innerComposer)
                InjectedRoute.ReaderHighlightConfigSettings -> renderReaderHighlightConfigSettingsContent(innerPaddings, innerComposer)
                InjectedRoute.ReaderHighlightTextSettings -> renderReaderHighlightTextSettingsContent(innerPaddings, innerComposer)
                is InjectedRoute.ReaderBookHighlightRules -> renderReaderBookHighlightRulesContent(currentRoute, innerPaddings, innerComposer)
                is InjectedRoute.ReaderBookOnlyHighlightRules -> renderReaderBookOnlyHighlightRulesContent(currentRoute, innerPaddings, innerComposer)
                is InjectedRoute.ReaderBookGlobalHighlightRules -> renderReaderBookGlobalHighlightRulesContent(currentRoute, innerPaddings, innerComposer)
                InjectedRoute.ReaderHighlightColorPicker -> renderReaderHighlightColorPickerContent(innerPaddings, innerComposer)
                InjectedRoute.ProfileBackgroundSettings -> renderProfileBackgroundSettingsContent(innerPaddings, innerComposer)
                InjectedRoute.CloudCompletionSettings -> renderCloudCompletionSettingsContent(innerPaddings, innerComposer)
                InjectedRoute.RotationCompletionSettings -> renderRotationCompletionSettingsContent(innerPaddings, innerComposer)
                InjectedRoute.AccountSwitch -> renderAccountSwitchContent(innerPaddings, innerComposer)
                InjectedRoute.OnlineCompletionSettings -> renderOnlineCompletionSettingsContent(innerPaddings, innerComposer)
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
    }

    private fun renderInjectedBackHandler(route: InjectedRoute, composer: Any) {
        val enabled = route is InjectedRoute.FontPicker ||
            route is InjectedRoute.ReaderBookHighlightRules ||
            route is InjectedRoute.ReaderBookOnlyHighlightRules ||
            route is InjectedRoute.ReaderBookGlobalHighlightRules ||
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

    private fun renderHostTopBar(title: String, composer: Any) {
        renderHostTopBar(title, composer) {
            navigateBackFromInjectedRoute()
        }
    }

    private fun renderHostTopBar(title: String, composer: Any, onBack: () -> Unit) {
        val back = functionProxy("ModuleSettingsBack", FUNCTION0_CLASS) {
            onBack()
            targetUnit()
        }
        method(APP_TOP_BAR_CLASS, APP_TOP_BAR_METHOD, 8).invoke(
            null,
            title,
            null,
            null,
            back,
            null,
            composer,
            0,
            22,
        )
    }

    private fun renderReaderSheetTopBar(title: String, composer: Any, onClose: () -> Unit) {
        val windowInsets = readerSheetWindowInsets(composer)
        val closeIcon = closeImageVector()
        if (windowInsets == null || closeIcon == null) {
            renderHostTopBar(title, composer, onClose)
            return
        }
        val close = functionProxy("ReaderSheetClose", FUNCTION0_CLASS) {
            onClose()
            targetUnit()
        }
        method(APP_TOP_BAR_CLASS, APP_TOP_BAR_METHOD, 8).invoke(
            null,
            title,
            windowInsets,
            closeIcon,
            close,
            null,
            composer,
            0,
            16,
        )
    }

    private fun readerSheetWindowInsets(composer: Any): Any? =
        runCatching {
            val topAppBarDefaults = staticObject(TOP_APP_BAR_DEFAULTS_CLASS, "INSTANCE")
            val topInsets = method(TOP_APP_BAR_DEFAULTS_CLASS, "getWindowInsets", 2).invoke(
                topAppBarDefaults,
                composer,
                staticInt(TOP_APP_BAR_DEFAULTS_CLASS, "\$stable"),
            )
            val statusInsets = method(WINDOW_INSETS_EXT_ANDROID_KT_CLASS, "getStatusBarsIgnoringVisibilityCompat", 3).invoke(
                null,
                staticObject(WINDOW_INSETS_CLASS, "INSTANCE"),
                composer,
                6,
            )
            method(WINDOW_INSETS_KT_CLASS, "exclude", 2).invoke(null, topInsets, statusInsets)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX reader sheet window insets fallback: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun closeImageVector(): Any? =
        runCatching {
            val evaIcons = staticObject(EVA_ICONS_CLASS, "INSTANCE")
            val outline = cls(EVA_OUTLINE_KT_CLASS).declaredMethods.first {
                it.name == "getOutline" && it.parameterTypes.size == 1
            }.apply { isAccessible = true }.invoke(null, evaIcons)
            cls(EVA_CLOSE_KT_CLASS).declaredMethods.first {
                it.name == "getClose" && it.parameterTypes.size == 1
            }.apply { isAccessible = true }.invoke(null, outline)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX reader sheet close icon fallback: ${it.stackTraceToString()}")
        }.getOrNull()

    private fun renderHostModuleSettingsContent(innerPaddings: Any, composer: Any) {
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

    private fun profileBackgroundColorSummary(value: String): String =
        profileBackgroundArgbHex(profileBackgroundColorValue(value))

    private fun profileBackgroundCropPositionSummary(value: String): String =
        PROFILE_BACKGROUND_CROP_POSITION_OPTIONS.firstOrNull { it.value == value }?.title
            ?: PROFILE_BACKGROUND_CROP_POSITION_OPTIONS.first().title

    private fun profileBackgroundDisplayModeSummary(value: String): String =
        PROFILE_BACKGROUND_DISPLAY_MODE_OPTIONS.firstOrNull { it.value == value }?.title
            ?: PROFILE_BACKGROUND_DISPLAY_MODE_OPTIONS.first().title

    private fun profileBackgroundPercentSummary(value: Int): String =
        "${value.coerceIn(0, 100)}%"

    private fun completionEntryRow(key: String, title: String, enabled: Boolean, route: InjectedRoute): ActionRow =
        ActionRow(
            key = key,
            title = title,
            subtitle = if (enabled) "\u5df2\u542f\u7528" else "\u672a\u542f\u7528",
            trailing = "\u8bbe\u7f6e",
            onClick = { openNestedInjectedRoute(route) },
        )

    private fun renderAssociationCompletionSettingsContent(innerPaddings: Any, composer: Any) {
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

    private fun renderReaderCompletionSettingsContent(innerPaddings: Any, composer: Any) {
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
            )
            val rows = listOf(
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

    private fun renderAboutCompletionContent(innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("AboutCompletionList", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
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
            )
            val actionRows = listOf(
                ActionRow(
                    key = "about_completion_export_log",
                    title = "\u5bfc\u51fa\u65e5\u5fd7",
                    subtitle = "\u5c06\u6a21\u5757\u8fd0\u884c\u65e5\u5fd7\u5bfc\u51fa\u5230\u4e0b\u8f7d\u76ee\u5f55",
                    onClick = { exportModuleLog() },
                ),
            )
            addLazyItem(lazyListScope, ABOUT_COMPLETION_CONTENT_ITEM_KEY) { itemComposer ->
                renderHostSettingsCard(toggleRows, itemComposer)
            }
            addLazyItem(lazyListScope, ABOUT_COMPLETION_ENTRY_ITEM_KEY) { itemComposer ->
                renderHostActionCard(actionRows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun exportModuleLog() {
        val activity = activityProvider() ?: return
        runCatching {
            val fileName = "reamicro_log_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".txt"
            val logText = collectModuleLog()
            writeBytesToDownloads(activity, fileName, "text/plain", logText.toByteArray(Charsets.UTF_8))
            showToast("\u5df2\u5bfc\u51fa\u65e5\u5fd7\uff1a$fileName")
        }.onFailure {
            showToast("\u5bfc\u51fa\u65e5\u5fd7\u5931\u8d25")
            XposedBridge.log("$LOG_PREFIX export module log failed: ${it.stackTraceToString()}")
        }
    }

    private fun collectModuleLog(): String {
        val builder = StringBuilder()
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time"))
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.contains("ReaMicro", ignoreCase = true)) {
                        builder.append(line).append('\n')
                    }
                }
            }
            process.destroy()
        }.onFailure {
            builder.append("collect log failed: ${it.stackTraceToString()}")
        }
        if (builder.isEmpty()) {
            builder.append("\u6682\u65e0\u65e5\u5fd7\uff08logcat \u672a\u6355\u83b7\u5230\u76f8\u5173\u8bb0\u5f55\uff09")
        }
        return builder.toString()
    }


    private fun renderReaderHighlightSettingsContent(innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("ReaderHighlightSettingsList", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            readerHighlightVersionValue()
            val highlight = settings.highlightSettings()
            val rows = listOf(
                ActionRow(
                    key = "reader_highlight_config",
                    title = "\u9ad8\u4eae\u6837\u5f0f",
                    subtitle = "${highlight.styles.size} \u4e2a\u6837\u5f0f",
                    singleLineSubtitle = true,
                    onClick = { openNestedInjectedRoute(InjectedRoute.ReaderHighlightConfigSettings) },
                ),
                ActionRow(
                    key = "reader_highlight_text",
                    title = "\u9ad8\u4eae\u89c4\u5219",
                    subtitle = "${highlight.rules.count { it.enabled }} / ${highlight.rules.size} \u6761\u89c4\u5219",
                    singleLineSubtitle = true,
                    onClick = { openNestedInjectedRoute(InjectedRoute.ReaderHighlightTextSettings) },
                ),
            )
            addLazyItem(lazyListScope, READER_HIGHLIGHT_SETTINGS_ITEM_KEY) { itemComposer ->
                renderHostActionCard(rows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun renderReaderHighlightConfigSettingsContent(innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("ReaderHighlightConfigSettingsList", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            fontLibraryVersionValue()
            readerHighlightVersionValue()
            val highlight = settings.highlightSettings()
            val rows = buildList {
                add(
                    ActionRow(
                        key = "reader_highlight_style_add",
                        title = "\u6dfb\u52a0\u914d\u7f6e",
                        subtitle = "\u65b0\u589e\u4e00\u7ec4\u9ad8\u4eae\u6837\u5f0f",
                        singleLineSubtitle = true,
                        onClick = { openReaderHighlightStyleDialog(newReaderHighlightStyle()) },
                        onLongClick = ::openReaderHighlightStyleImportPicker,
                    ),
                )
                highlight.styles.forEach { style ->
                    add(
                        ActionRow(
                            key = "reader_highlight_style_${style.id}",
                            title = style.name,
                            subtitle = highlightStyleSummary(style),
                            trailing = readerHighlightStyleDefaultTrailing(highlight, style),
                            singleLineSubtitle = true,
                            onClick = { openReaderHighlightStyleDialog(style) },
                            onLongClick = { exportReaderHighlightStyle(style) },
                        ),
                    )
                }
            }
            addLazyItem(lazyListScope, READER_HIGHLIGHT_CONFIG_ITEM_KEY) { itemComposer ->
                renderHostActionCard(rows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun renderReaderHighlightTextSettingsContent(innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("ReaderHighlightTextSettingsList", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            readerHighlightVersionValue()
            val highlight = settings.highlightSettings()
            val globalRows = buildList {
                add(
                    ActionRow(
                        key = "reader_highlight_rule_add",
                        title = "\u6dfb\u52a0\u914d\u7f6e",
                        subtitle = "\u65b0\u589e\u4e00\u6761\u56fa\u5b9a\u6587\u672c\u6216\u6b63\u5219\u9ad8\u4eae\u89c4\u5219",
                        singleLineSubtitle = true,
                        onClick = { openReaderHighlightRuleDialog(newReaderHighlightRule()) },
                    ),
                )
                highlight.rules.filter { it.bookKey.isBlank() }.forEach { rule ->
                    add(
                        ActionRow(
                            key = "reader_highlight_rule_${rule.id}",
                            title = rule.name,
                            subtitle = "${highlightRuleSummary(rule)} / ${highlightRuleStyleSummary(highlight, rule)}",
                            trailingContent = { itemComposer ->
                                renderHostActionSwitch(
                                    key = "reader_highlight_rule_switch_${rule.id}",
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
            }
            val bookRows = buildList {
                val bookGroups = readerHighlightBookGroups(highlight)
                bookGroups.forEach { group ->
                    add(
                        ActionRow(
                            key = "reader_highlight_book_${group.bookKey.hashCode()}",
                            title = group.bookTitle,
                            subtitle = group.subtitle,
                            trailing = "\u5355\u4e66",
                            singleLineSubtitle = true,
                            onClick = {
                                openNestedInjectedRoute(
                                    InjectedRoute.ReaderBookHighlightRules(group.bookKey, group.bookTitle),
                                )
                            },
                        ),
                    )
                }
                if (isEmpty()) {
                    add(
                        ActionRow(
                            key = "reader_highlight_book_empty",
                            title = "\u6682\u65e0\u5355\u4e66\u89c4\u5219",
                            subtitle = "\u5728\u9605\u8bfb\u9875\u9009\u4e2d\u6587\u672c\u540e\u53ef\u6dfb\u52a0\u672c\u4e66\u9ad8\u4eae",
                            singleLineSubtitle = true,
                        ),
                    )
                }
            }
            addLazyItem(lazyListScope, READER_HIGHLIGHT_TEXT_ITEM_KEY) { itemComposer ->
                renderHostActionCard(globalRows, itemComposer)
            }
            addLazyItem(lazyListScope, READER_HIGHLIGHT_BOOK_GROUPS_ITEM_KEY) { itemComposer ->
                renderHostActionCard(bookRows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun renderReaderBookHighlightRulesContent(route: InjectedRoute.ReaderBookHighlightRules, innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("ReaderBookHighlightRulesList", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            readerHighlightVersionValue()
            val highlight = settings.highlightSettings()
            val rows = buildList {
                val globalRules = highlight.globalRules()
                val followsGlobal = highlight.bookFollowsGlobalRules(route.bookKey)
                val enabledGlobalRuleIds = highlight.effectiveGlobalRuleIdsForBook(route.bookKey)
                val enabledGlobalCount = globalRules.count { it.id in enabledGlobalRuleIds }
                add(
                    ActionRow(
                        key = "reader_book_highlight_rule_add_${route.bookKey.hashCode()}",
                        title = "\u6dfb\u52a0\u914d\u7f6e",
                        subtitle = "\u65b0\u589e\u672c\u4e66\u56fa\u5b9a\u6587\u672c\u6216\u6b63\u5219\u9ad8\u4eae\u89c4\u5219",
                        singleLineSubtitle = true,
                        onClick = {
                            openReaderHighlightRuleDialog(
                                newReaderHighlightRule(route.bookKey, route.bookTitle),
                            )
                        },
                    ),
                )
                add(
                    ActionRow(
                        key = "reader_book_global_rules_${route.bookKey.hashCode()}",
                        title = "\u5168\u5c40\u89c4\u5219",
                        subtitle = if (followsGlobal) {
                            "\u8ddf\u968f\u5168\u5c40"
                        } else {
                            "\u5df2\u542f\u7528 $enabledGlobalCount/${globalRules.size} \u6761\u5168\u5c40\u89c4\u5219"
                        },
                        singleLineSubtitle = true,
                        onClick = {
                            openNestedInjectedRoute(
                                InjectedRoute.ReaderBookGlobalHighlightRules(route.bookKey, route.bookTitle),
                            )
                        },
                    ),
                )
                highlight.bookRules(route.bookKey).forEach { rule ->
                    add(
                        ActionRow(
                            key = "reader_book_highlight_rule_${rule.id}",
                            title = rule.name,
                            subtitle = "${highlightRuleSummary(rule)} / ${highlightRuleStyleSummary(highlight, rule)}",
                            trailingContent = { itemComposer ->
                                renderHostActionSwitch(
                                    key = "reader_book_highlight_rule_switch_${rule.id}",
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
            }
            addLazyItem(lazyListScope, READER_HIGHLIGHT_BOOK_RULES_ITEM_KEY) { itemComposer ->
                renderHostActionCard(rows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun renderReaderBookOnlyHighlightRulesContent(route: InjectedRoute.ReaderBookOnlyHighlightRules, innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("ReaderBookOnlyHighlightRulesList", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            readerHighlightVersionValue()
            val highlight = settings.highlightSettings()
            val rows = buildList {
                val bookRules = highlight.bookRules(route.bookKey)
                bookRules.forEach { rule ->
                    add(
                        ActionRow(
                            key = "reader_book_only_highlight_rule_${rule.id}",
                            title = rule.name,
                            subtitle = "${highlightRuleSummary(rule)} / ${highlightRuleStyleSummary(highlight, rule)}",
                            trailingContent = { itemComposer ->
                                renderHostActionSwitch(
                                    key = "reader_book_only_highlight_rule_switch_${rule.id}",
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
                            key = "reader_book_only_highlight_empty_${route.bookKey.hashCode()}",
                            title = "\u6682\u65e0\u5355\u4e66\u89c4\u5219",
                            subtitle = "\u9009\u4e2d\u672c\u4e66\u6587\u672c\u540e\u53ef\u6dfb\u52a0\u9ad8\u4eae\u89c4\u5219",
                            singleLineSubtitle = true,
                        ),
                    )
                }
            }
            addLazyItem(lazyListScope, "reader_book_only_rules_${route.bookKey}".hashCode()) { itemComposer ->
                renderHostActionCard(rows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun renderReaderBookGlobalHighlightRulesContent(route: InjectedRoute.ReaderBookGlobalHighlightRules, innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("ReaderBookGlobalHighlightRulesList", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            readerHighlightVersionValue()
            val highlight = settings.highlightSettings()
            val globalRules = highlight.globalRules()
            val followsGlobal = highlight.bookFollowsGlobalRules(route.bookKey)
            val enabledIds = highlight.effectiveGlobalRuleIdsForBook(route.bookKey)
            val rows = buildList {
                add(
                    ActionRow(
                        key = "reader_book_global_follow_${route.bookKey.hashCode()}",
                        title = "\u8ddf\u968f\u5168\u5c40",
                        subtitle = if (followsGlobal) "\u4fee\u6539\u4e0b\u65b9\u5f00\u5173\u65f6\u540c\u6b65\u4fee\u6539\u5168\u5c40\u89c4\u5219" else "\u4fee\u6539\u4e0b\u65b9\u5f00\u5173\u65f6\u4ec5\u5f71\u54cd\u672c\u4e66",
                        trailingContent = { itemComposer ->
                            renderHostActionSwitch(
                                key = "reader_book_global_follow_switch_${route.bookKey.hashCode()}",
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
                globalRules.forEach { rule ->
                    add(
                        ActionRow(
                            key = "reader_book_global_rule_${route.bookKey.hashCode()}_${rule.id}",
                            title = rule.name,
                            subtitle = "${highlightRuleSummary(rule)} / ${highlightRuleStyleSummary(highlight, rule)}",
                            trailingContent = { itemComposer ->
                                renderHostActionSwitch(
                                    key = "reader_book_global_rule_switch_${route.bookKey.hashCode()}_${rule.id}",
                                    checked = rule.id in enabledIds,
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
                        ),
                    )
                }
            }
            addLazyItem(lazyListScope, "reader_book_global_rules_${route.bookKey}".hashCode()) { itemComposer ->
                renderHostActionCard(rows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun renderReaderHighlightRulesSheetFromReader(
        globalRules: Boolean,
        bookKey: String,
        bookTitle: String,
        composer: Any,
        onClose: () -> Unit,
    ) {
        val title = if (globalRules) "\u5168\u5c40\u9ad8\u4eae\u89c4\u5219" else "\u5355\u4e66\u9ad8\u4eae\u89c4\u5219"
        val content = functionProxy("ReaderHighlightRulesSheetContent", FUNCTION3_CLASS) { args ->
            val innerComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
            renderReaderSheetBackHandler(innerComposer, onClose)
            renderReaderSheetTopBar(title, innerComposer, onClose)
            renderReaderHighlightRulesSheetList(globalRules, bookKey, bookTitle, innerComposer)
            targetUnit()
        }
        method(COLUMN_KT_CLASS, COLUMN_METHOD, 7).invoke(
            null,
            readerHighlightSheetModifier(composer),
            arrangementTop(),
            alignmentStart(),
            content,
            composer,
            0,
            0,
        )
    }

    private fun renderReaderSheetBackHandler(composer: Any, onClose: () -> Unit) {
        val back = functionProxy("ReaderHighlightRulesSheetBack", FUNCTION0_CLASS) {
            onClose()
            targetUnit()
        }
        renderReaderSheetNavigationBackHandler(composer, back)
        method(BACK_HANDLER_KT_CLASS, BACK_HANDLER_METHOD, 5).invoke(null, true, back, composer, 0, 0)
    }

    private fun renderReaderSheetNavigationBackHandler(composer: Any, back: Any) {
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

    private fun renderReaderHighlightRulesSheetList(
        globalRules: Boolean,
        bookKey: String,
        bookTitle: String,
        composer: Any,
    ) {
        val listContent = functionProxy("ReaderHighlightRulesSheetList", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            readerHighlightVersionValue()
            val rows = readerHighlightRulesSheetRows(globalRules, bookKey, bookTitle)
            addLazyItem(lazyListScope, "reader_highlight_sheet_${globalRules}_${bookKey}".hashCode()) { itemComposer ->
                renderHostActionCard(rows, itemComposer)
            }
            targetUnit()
        }
        method(LAZY_DSL_KT_CLASS, LAZY_COLUMN_METHOD, 13).invoke(
            null,
            readerHighlightSheetListModifier(),
            null,
            null,
            false,
            spacedBy(12),
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

    private fun readerHighlightRulesSheetRows(
        globalRules: Boolean,
        bookKey: String,
        bookTitle: String,
    ): List<ActionRow> {
        val highlight = settings.highlightSettings()
        return if (globalRules) {
            val rules = highlight.globalRules()
            val followsGlobal = highlight.bookFollowsGlobalRules(bookKey)
            val enabledIds = highlight.effectiveGlobalRuleIdsForBook(bookKey)
            buildList {
                add(
                    ActionRow(
                        key = "reader_sheet_follow_${bookKey.hashCode()}",
                        title = "\u8ddf\u968f\u5168\u5c40",
                        subtitle = if (followsGlobal) {
                            "\u4fee\u6539\u4e0b\u65b9\u5f00\u5173\u65f6\u540c\u6b65\u4fee\u6539\u5168\u5c40\u89c4\u5219"
                        } else {
                            "\u4fee\u6539\u4e0b\u65b9\u5f00\u5173\u65f6\u4ec5\u5f71\u54cd\u672c\u4e66"
                        },
                        trailingContent = { itemComposer ->
                            renderHostActionSwitch(
                                key = "reader_sheet_follow_switch_${bookKey.hashCode()}",
                                checked = followsGlobal,
                                composer = itemComposer,
                            ) { enabled ->
                                settings.setReaderHighlightBookFollowsGlobalRules(bookKey, enabled)
                                bumpReaderHighlightVersion()
                            }
                        },
                        singleLineSubtitle = true,
                    ),
                )
                if (rules.isEmpty()) {
                    add(
                        ActionRow(
                            key = "reader_sheet_global_empty_${bookKey.hashCode()}",
                            title = "\u6682\u65e0\u5168\u5c40\u89c4\u5219",
                            subtitle = "\u53ef\u5728\u9ad8\u4eae\u7ba1\u7406\u4e2d\u6dfb\u52a0",
                            singleLineSubtitle = true,
                        ),
                    )
                }
                rules.forEach { rule ->
                    add(
                        ActionRow(
                            key = "reader_sheet_global_${bookKey.hashCode()}_${rule.id}",
                            title = rule.name,
                            subtitle = "${highlightRuleSummary(rule)} / ${highlightRuleStyleSummary(highlight, rule)}",
                            trailingContent = { itemComposer ->
                                renderHostActionSwitch(
                                    key = "reader_sheet_global_switch_${bookKey.hashCode()}_${rule.id}",
                                    checked = rule.id in enabledIds,
                                    composer = itemComposer,
                                ) { enabled ->
                                    if (followsGlobal) {
                                        settings.setReaderHighlightRuleEnabled(rule.id, enabled)
                                    } else {
                                        settings.setReaderHighlightBookGlobalRuleEnabled(bookKey, rule.id, enabled)
                                    }
                                    bumpReaderHighlightVersion()
                                }
                            },
                            singleLineSubtitle = true,
                        ),
                    )
                }
            }
        } else {
            val rules = highlight.rules.filter { it.bookKey.isNotBlank() && it.appliesToBook(bookKey, bookTitle) }
            if (rules.isEmpty()) {
                listOf(
                    ActionRow(
                        key = "reader_sheet_book_empty_${bookKey.hashCode()}",
                        title = "\u6682\u65e0\u5355\u4e66\u89c4\u5219",
                        subtitle = "\u9009\u4e2d\u6587\u5b57\u540e\u53ef\u6dfb\u52a0\u672c\u4e66\u9ad8\u4eae",
                        singleLineSubtitle = true,
                    ),
                )
            } else {
                rules.map { rule ->
                    ActionRow(
                        key = "reader_sheet_book_${bookKey.hashCode()}_${rule.id}",
                        title = rule.name,
                        subtitle = "${highlightRuleSummary(rule)} / ${highlightRuleStyleSummary(highlight, rule)}",
                        trailingContent = { itemComposer ->
                            renderHostActionSwitch(
                                key = "reader_sheet_book_switch_${bookKey.hashCode()}_${rule.id}",
                                checked = rule.enabled,
                                composer = itemComposer,
                            ) { enabled ->
                                settings.setReaderHighlightRuleEnabled(rule.id, enabled)
                                bumpReaderHighlightVersion()
                            }
                        },
                        singleLineSubtitle = true,
                    )
                }
            }
        }
    }

    private fun renderReaderHighlightColorPickerContent(innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("ReaderHighlightColorPickerList", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            val current = settings.dialogueHighlightSettings().color
            val rows = READER_HIGHLIGHT_COLOR_OPTIONS.map { option ->
                ActionRow(
                    key = "reader_dialogue_highlight_color_${option.value}",
                    title = option.title,
                    subtitle = option.value,
                    trailing = if (option.value.equals(current, ignoreCase = true)) "\u5f53\u524d" else null,
                    singleLineSubtitle = true,
                    onClick = {
                        settings.setReaderDialogueHighlightColor(option.value)
                        navigateBackFromInjectedRoute()
                    },
                )
            }
            addLazyItem(lazyListScope, READER_HIGHLIGHT_COLOR_PICKER_ITEM_KEY) { itemComposer ->
                renderHostActionCard(rows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun renderProfileBackgroundSettingsContent(innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("ProfileBackgroundSettingsList", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            profileBackgroundVersionValue()
            val snapshot = settings.snapshot()
            val imageSubtitle = if (snapshot.profileBackgroundImage.isBlank()) {
                "\u672a\u9009\u62e9"
            } else {
                snapshot.profileBackgroundImage.substringAfterLast('/')
            }
            val imageRows = listOf(
                ActionRow(
                    key = "profile_background_image_entry",
                    title = "\u80cc\u666f\u56fe\u7247",
                    subtitle = imageSubtitle,
                    singleLineSubtitle = true,
                    onClick = { openProfileBackgroundImagePicker() },
                ),
                ActionRow(
                    key = "profile_background_display_mode_entry",
                    title = "\u663e\u793a\u65b9\u5f0f",
                    subtitle = profileBackgroundDisplayModeSummary(snapshot.profileBackgroundDisplayMode),
                    singleLineSubtitle = true,
                    onClick = { openProfileBackgroundDisplayModeDialog() },
                ),
                ActionRow(
                    key = "profile_background_crop_position_entry",
                    title = "\u88c1\u526a\u4f4d\u7f6e",
                    subtitle = profileBackgroundCropPositionSummary(snapshot.profileBackgroundCropPosition),
                    singleLineSubtitle = true,
                    onClick = { openProfileBackgroundCropPositionDialog() },
                ),
                ActionRow(
                    key = "profile_background_blur_entry",
                    title = "\u6a21\u7cca\u5f3a\u5ea6",
                    subtitle = profileBackgroundPercentSummary(snapshot.profileBackgroundBlur),
                    singleLineSubtitle = true,
                    onClick = {
                        openProfileBackgroundPercentDialog(
                            title = "\u6a21\u7cca\u5f3a\u5ea6",
                            initialValue = settings.snapshot().profileBackgroundBlur,
                            onApply = settings::setProfileBackgroundBlur,
                        )
                    },
                ),
                ActionRow(
                    key = "profile_background_transparency_entry",
                    title = "\u56fe\u7247\u900f\u660e\u5ea6",
                    subtitle = profileBackgroundPercentSummary(snapshot.profileBackgroundTransparency),
                    singleLineSubtitle = true,
                    onClick = {
                        openProfileBackgroundPercentDialog(
                            title = "\u56fe\u7247\u900f\u660e\u5ea6",
                            initialValue = settings.snapshot().profileBackgroundTransparency,
                            onApply = settings::setProfileBackgroundTransparency,
                        )
                    },
                ),
                ActionRow(
                    key = "profile_background_card_transparency_entry",
                    title = "\u5bb9\u5668\u900f\u660e\u5ea6",
                    subtitle = profileBackgroundPercentSummary(snapshot.profileBackgroundCardTransparency),
                    singleLineSubtitle = true,
                    onClick = {
                        openProfileBackgroundPercentDialog(
                            title = "\u5bb9\u5668\u900f\u660e\u5ea6",
                            initialValue = settings.snapshot().profileBackgroundCardTransparency,
                            onApply = settings::setProfileBackgroundCardTransparency,
                        )
                    },
                ),
                ActionRow(
                    key = "profile_background_card_blur_entry",
                    title = "\u5bb9\u5668\u6a21\u7cca\u611f",
                    subtitle = profileBackgroundPercentSummary(snapshot.profileBackgroundCardBlur),
                    singleLineSubtitle = true,
                    onClick = {
                        openProfileBackgroundPercentDialog(
                            title = "\u5bb9\u5668\u6a21\u7cca\u611f",
                            initialValue = settings.snapshot().profileBackgroundCardBlur,
                            onApply = settings::setProfileBackgroundCardBlur,
                        )
                    },
                ),
            )
            addLazyItem(lazyListScope, PROFILE_BACKGROUND_COLOR_PICKER_ITEM_KEY + 1) { itemComposer ->
                renderHostActionCard(imageRows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun openProfileBackgroundColorDialog() {
        val current = profileBackgroundArgbHex(profileBackgroundColorValue(settings.snapshot().profileBackgroundColor))
        openProfileBackgroundCustomColorDialog(current)
    }

    private fun openProfileBackgroundCustomColorDialog(initialHex: String) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            runCatching {
                val colors = SettingsDialogColors(activity)
                val dialog = Dialog(activity)
                val card = settingsDialogCard(activity, colors)
                card.addView(settingsDialogTitle(activity, "\u80cc\u666f\u989c\u8272", colors))
                var pendingHex = profileBackgroundNormalizeArgbHexOrNull(initialHex)
                    ?: ModuleSettings.DEFAULT_PROFILE_BACKGROUND_COLOR
                val previewRow = profileBackgroundColorPreviewRow(activity, pendingHex, colors)
                val input = settingsDialogInput(activity, "#AARRGGBB", singleLine = true, colors = colors).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    setText(pendingHex)
                    selectAll()
                }
                val status = settingsDialogStatus(activity, pendingHex, colors)

                fun updatePreview(raw: String) {
                    val normalized = profileBackgroundNormalizeArgbHexOrNull(raw)
                    if (normalized == null) {
                        status.text = "\u989c\u8272\u503c\u683c\u5f0f\u4e0d\u6b63\u786e"
                        status.setTextColor(colors.destructiveText)
                        return
                    }
                    pendingHex = normalized
                    status.text = normalized
                    status.setTextColor(colors.body)
                    previewRow.background = settingsRoundedRect(
                        profileBackgroundColorValue(normalized),
                        settingsDp(activity, 14),
                        colors.border,
                    )
                }

                input.addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            updatePreview(s?.toString().orEmpty())
                        }

                        override fun afterTextChanged(s: Editable?) = Unit
                    },
                )
                card.addView(previewRow)
                card.addView(input)
                card.addView(status)

                val actions = settingsDialogActions(activity)
                actions.addView(
                    settingsDialogButton(activity, "\u53d6\u6d88", colors, SettingsDialogButtonRole.Neutral).apply {
                        setOnClickListener { dialog.dismiss() }
                    },
                    settingsDialogButtonParams(activity),
                )
                actions.addView(
                    settingsDialogButton(activity, "\u5b8c\u6210", colors).apply {
                        setOnClickListener {
                            val normalized = profileBackgroundNormalizeArgbHexOrNull(input.text?.toString().orEmpty())
                            if (normalized == null) {
                                Toast.makeText(activity, "\u989c\u8272\u503c\u683c\u5f0f\u4e0d\u6b63\u786e", Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }
                            settings.setProfileBackgroundColor(normalized)
                            dialog.dismiss()
                            bumpProfileBackgroundVersion()
                        }
                    },
                    settingsDialogButtonParams(activity),
                )
                card.addView(actions)
                showSettingsDialog(dialog, card, activity)
            }.onFailure {
                XposedBridge.log("ReaMicro LSP profile custom color dialog failed: ${it.stackTraceToString()}")
            }
        }
    }

    private fun openProfileBackgroundDisplayModeDialog() {
        openProfileBackgroundOptionDialog(
            title = "\u663e\u793a\u65b9\u5f0f",
            current = settings.snapshot().profileBackgroundDisplayMode,
            options = PROFILE_BACKGROUND_DISPLAY_MODE_OPTIONS,
            onSelected = settings::setProfileBackgroundDisplayMode,
            logName = "display mode",
        )
    }

    private fun openProfileBackgroundPercentDialog(
        title: String,
        initialValue: Int,
        onApply: (Int) -> Unit,
    ) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            runCatching {
                val colors = SettingsDialogColors(activity)
                val dialog = Dialog(activity)
                val card = settingsDialogCard(activity, colors)
                card.addView(settingsDialogTitle(activity, title, colors))
                val input = settingsDialogInput(activity, "\u8f93\u5165 0-100", singleLine = true, colors = colors).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(initialValue.coerceIn(0, 100).toString())
                    selectAll()
                }
                card.addView(input)
                card.addView(settingsDialogStatus(activity, "\u8303\u56f4 0-100\uff0c\u4fdd\u5b58\u540e\u7acb\u5373\u751f\u6548\u3002", colors))
                val actions = settingsDialogActions(activity)
                actions.addView(
                    settingsDialogButton(activity, "\u53d6\u6d88", colors, SettingsDialogButtonRole.Neutral).apply {
                        setOnClickListener { dialog.dismiss() }
                    },
                    settingsDialogButtonParams(activity),
                )
                actions.addView(
                    settingsDialogButton(activity, "\u5b8c\u6210", colors).apply {
                        setOnClickListener {
                            val value = input.text?.toString()?.trim()?.toIntOrNull()
                            if (value == null || value !in 0..100) {
                                Toast.makeText(activity, "\u8bf7\u8f93\u5165 0-100", Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }
                            onApply(value)
                            dialog.dismiss()
                            bumpProfileBackgroundVersion()
                        }
                    },
                    settingsDialogButtonParams(activity),
                )
                card.addView(actions)
                showSettingsDialog(dialog, card, activity)
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX profile background percent dialog failed: ${it.stackTraceToString()}")
            }
        }
    }

    private fun openProfileBackgroundCropPositionDialog() {
        openProfileBackgroundOptionDialog(
            title = "\u88c1\u526a\u4f4d\u7f6e",
            current = settings.snapshot().profileBackgroundCropPosition,
            options = PROFILE_BACKGROUND_CROP_POSITION_OPTIONS,
            onSelected = settings::setProfileBackgroundCropPosition,
            logName = "crop position",
        )
    }

    private fun openProfileBackgroundOptionDialog(
        title: String,
        current: String,
        options: List<HighlightColorOption>,
        onSelected: (String) -> Unit,
        logName: String,
    ) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            runCatching {
                val colors = SettingsDialogColors(activity)
                val dialog = Dialog(activity)
                val card = settingsDialogCard(activity, colors)
                card.addView(settingsDialogTitle(activity, title, colors))
                options.forEach { option ->
                    val selected = option.value == current
                    card.addView(
                        settingsDialogChoiceRow(
                            activity,
                            if (selected) "${option.title}  \u5f53\u524d" else option.title,
                            colors,
                        ) {
                            onSelected(option.value)
                            dialog.dismiss()
                            bumpProfileBackgroundVersion()
                        },
                    )
                }
                val actions = settingsDialogActions(activity)
                actions.addView(
                    settingsDialogButton(activity, "\u5173\u95ed", colors, SettingsDialogButtonRole.Neutral).apply {
                        setOnClickListener { dialog.dismiss() }
                    },
                    settingsDialogButtonParams(activity),
                )
                card.addView(actions)
                showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX profile background $logName dialog failed: ${it.stackTraceToString()}")
            }
        }
    }

    private fun profileBackgroundColorPreviewRow(
        context: Context,
        hex: String,
        colors: SettingsDialogColors,
    ): View =
        View(context).apply {
            background = settingsRoundedRect(profileBackgroundColorValue(hex), settingsDp(context, 14), colors.border)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                settingsDp(context, 52),
            ).apply { bottomMargin = settingsDp(context, 10) }
        }

    private fun profileBackgroundNormalizeArgbHexOrNull(value: String): String? {
        val normalized = value.trim().removePrefix("#")
        if (!normalized.matches(Regex("^[0-9a-fA-F]{6}$")) &&
            !normalized.matches(Regex("^[0-9a-fA-F]{8}$"))
        ) {
            return null
        }
        val argb = when (normalized.length) {
            6 -> "FF$normalized"
            8 -> normalized
            else -> return null
        }
        return "#${argb.uppercase(Locale.US)}"
    }

    private fun profileBackgroundColorValue(hex: String): Int {
        val normalized = hex.trim().removePrefix("#")
        return runCatching {
            when (normalized.length) {
                8 -> normalized.toLong(16).toInt()
                6 -> (0xFF shl 24) or normalized.toInt(16)
                else -> 0x80000000.toInt()
            }
        }.getOrDefault(0x80000000.toInt())
    }

    private fun profileBackgroundArgbHex(argb: Int): String =
        String.format(Locale.US, "#%08X", argb)

    private fun openReaderHighlightStyleDialog(style: ReaderHighlightStyle) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            runCatching {
                val colors = SettingsDialogColors(activity)
                val dialog = Dialog(activity)
                val card = settingsDialogCard(activity, colors)
                val nameInput = settingsDialogInput(activity, "\u6837\u5f0f\u540d\u79f0", singleLine = true, colors = colors).apply {
                    setText(style.name)
                }
                val colorInput = settingsDialogInput(activity, "\u989c\u8272\uff0c\u4f8b\u5982 #FF9800", singleLine = true, colors = colors).apply {
                    setText(style.color)
                }
                var fontSelection = style.fontFamily
                lateinit var syncFontStatus: () -> Unit
                val fontStatus = settingsDialogFontChoiceRow(activity, "", null, fontSelection, colors) {
                    openSettingsFontSelectionDialog(
                        activity = activity,
                        title = "\u5b57\u4f53",
                        currentSelection = fontSelection,
                        clearTitle = "\u8ddf\u968f\u5168\u5c40\u5b57\u4f53",
                    ) { selection ->
                        fontSelection = selection
                        syncFontStatus.invoke()
                    }
                }
                val cssInput = settingsDialogInput(activity, "CSS\uff08\u652f\u6301 color/background-color/border/padding/margin/background-size\uff09", singleLine = false, colors = colors).apply {
                    setText(sanitizeReaderHighlightCss(style.css))
                    minLines = 2
                }
                val ninePatchInput = settingsDialogInput(activity, "\u56fe\u7247\u8def\u5f84\uff08\u4fdd\u7559\u914d\u7f6e\uff09", singleLine = true, colors = colors).apply {
                    setText(style.ninePatchPath)
                }
                val ninePatchSelectButton = settingsDialogButton(
                    activity,
                    if (style.ninePatchPath.isBlank()) "\u9009\u62e9\u56fe\u7247" else "\u66f4\u6362\u56fe\u7247",
                    colors,
                    SettingsDialogButtonRole.Neutral,
                )
                val setLightDefaultButton = settingsDialogButton(activity, "\u6d45\u8272\u9ed8\u8ba4", colors, SettingsDialogButtonRole.Neutral)
                val setDarkDefaultButton = settingsDialogButton(activity, "\u6df1\u8272\u9ed8\u8ba4", colors, SettingsDialogButtonRole.Neutral)
                val finishButton = settingsDialogButton(activity, "\u5b8c\u6210", colors)
                val deleteButton = settingsDialogButton(activity, "\u5220\u9664", colors, SettingsDialogButtonRole.Destructive)
                val cancelButton = settingsDialogButton(activity, "\u53d6\u6d88", colors, SettingsDialogButtonRole.Neutral)
                val preview = readerHighlightStylePreview(activity, "\u9884\u89c8\uff1a\u201c\u5bf9\u8bdd\u9ad8\u4eae\u9884\u89c8\u201d", colors)
                lateinit var syncPreviews: () -> Unit
                fun syncImageButtons() {
                    ninePatchSelectButton.text = if (ninePatchInput.text?.toString()?.trim().isNullOrBlank()) {
                        "\u9009\u62e9\u56fe\u7247"
                    } else {
                        "\u66f4\u6362\u56fe\u7247"
                    }
                }
                syncFontStatus = {
                    fontStatus.text = "\u5b57\u4f53\uff1a${dialogueHighlightFontSummary(fontSelection)}"
                    fontStatus.typeface = androidTypefaceForFontSelection(fontSelection) ?: Typeface.DEFAULT
                }
                syncPreviews = {
                    syncReaderHighlightStylePreview(
                        preview,
                        colorInput.text?.toString().orEmpty(),
                        cssInput.text?.toString().orEmpty(),
                        ninePatchInput.text?.toString()?.trim().orEmpty(),
                        style.ninePatchSlice,
                        fontSelection,
                        colors,
                    )
                }
                listOf(colorInput, cssInput).forEach { input ->
                    input.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = syncPreviews.invoke()
                        override fun afterTextChanged(s: Editable?) = Unit
                    })
                }
                ninePatchInput.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        syncImageButtons()
                        syncPreviews.invoke()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
                card.addView(settingsDialogTitle(activity, "\u9ad8\u4eae\u6837\u5f0f", colors))
                card.addView(nameInput)
                card.addView(colorInput)
                syncFontStatus.invoke()
                card.addView(fontStatus)
                card.addView(cssInput)
                syncPreviews.invoke()
                card.addView(preview)
                card.addView(ninePatchSelectButton, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = settingsDp(activity, 6) })
                card.addView(settingsDialogButtonRow(activity, listOf(setLightDefaultButton, setDarkDefaultButton)))
                syncImageButtons()
                val buttons = if (style.id in setOf(
                    ModuleSettings.DEFAULT_READER_HIGHLIGHT_DARK_STYLE_ID,
                    ModuleSettings.BUILTIN_READER_HIGHLIGHT_RAINBOW_GLASS_STYLE_ID,
                )) {
                    listOf(finishButton, cancelButton)
                } else {
                    listOf(deleteButton, finishButton, cancelButton)
                }
                card.addView(settingsDialogButtonRow(activity, buttons))
                ninePatchSelectButton.setOnClickListener {
                    openHighlightNinePatchPicker(activity, ninePatchInput)
                }
                setLightDefaultButton.setOnClickListener {
                    settings.setReaderHighlightDefaultLightStyle(style.id)
                    bumpReaderHighlightVersion()
                    showToast("\u5df2\u8bbe\u4e3a\u6d45\u8272\u9ed8\u8ba4")
                }
                setDarkDefaultButton.setOnClickListener {
                    settings.setReaderHighlightDefaultDarkStyle(style.id)
                    bumpReaderHighlightVersion()
                    showToast("\u5df2\u8bbe\u4e3a\u6df1\u8272\u9ed8\u8ba4")
                }
                finishButton.setOnClickListener {
                    settings.setReaderHighlightStyle(
                        style.copy(
                            name = nameInput.text?.toString()?.trim().orEmpty().ifBlank { style.name },
                            color = colorInput.text?.toString()?.trim().orEmpty(),
                            fontFamily = fontSelection,
                            css = sanitizeReaderHighlightCss(cssInput.text?.toString().orEmpty()),
                            ninePatchPath = ninePatchInput.text?.toString()?.trim().orEmpty(),
                        ),
                    )
                    bumpReaderHighlightVersion()
                    dialog.dismiss()
                }
                deleteButton.setOnClickListener {
                    settings.removeReaderHighlightStyle(style.id)
                    bumpReaderHighlightVersion()
                    dialog.dismiss()
                }
                cancelButton.setOnClickListener { dialog.dismiss() }
                showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX open highlight style dialog failed: ${it.stackTraceToString()}")
            }
        }
    }

    private fun exportReaderHighlightStyle(style: ReaderHighlightStyle) {
        val activity = activityProvider() ?: return
        runCatching {
            val fileName = "reamicro_highlight_style_${safeDownloadName(style.name)}_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".json"
            writeTextToDownloads(activity, fileName, readerHighlightStyleExportJson(activity, style).toString(2))
            showToast("\u5df2\u5bfc\u51fa\u9ad8\u4eae\u6837\u5f0f\uff1a$fileName")
        }.onFailure {
            showToast("\u5bfc\u51fa\u9ad8\u4eae\u6837\u5f0f\u5931\u8d25")
            XposedBridge.log("$LOG_PREFIX export highlight style failed: ${it.stackTraceToString()}")
        }
    }

    private fun readerHighlightStylePreview(
        context: Context,
        message: String,
        colors: SettingsDialogColors,
    ): TextView =
        ReaderHighlightPreviewTextView(context).apply {
            text = message
            textSize = 15f
            includeFontPadding = true
            setPadding(
                settingsDp(context, 12),
                settingsDp(context, 10),
                settingsDp(context, 12),
                settingsDp(context, 10),
            )
            background = settingsRoundedRect(colors.field, settingsDp(context, 12), colors.border)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = settingsDp(context, 10) }
        }

    private fun syncReaderHighlightStylePreview(
        preview: TextView,
        colorValue: String,
        css: String,
        ninePatchPath: String,
        ninePatchSlice: String,
        fontSelection: String,
        colors: SettingsDialogColors,
    ) {
        val cssValues = readerHighlightCssProperties(css)
        val textColor = parseSettingsColor(cssValues["color"].orEmpty())
            ?: parseSettingsColor(colorValue)
            ?: colors.title
            val backgroundColor = parseSettingsColor(cssValues["background-color"].orEmpty())
            ?: parseSettingsColor(cssValues["background"].orEmpty())
            ?: colors.field
        preview.setTextColor(textColor)
        preview.background = settingsRoundedRect(colors.field, settingsDp(preview.context, 12), colors.border)
        (preview as? ReaderHighlightPreviewTextView)?.setHighlightPreview(
            css = css,
            path = ninePatchPath,
            slice = ninePatchSlice.ifBlank { reedenNineSlice(css) },
            fallbackColor = backgroundColor,
            hasFallbackFill = cssValues.containsKey("background-color") || cssValues.containsKey("background"),
        )
        preview.textSize = 15f
        val baseTypeface = androidTypefaceForFontSelection(fontSelection) ?: Typeface.DEFAULT
        preview.typeface = Typeface.create(baseTypeface, Typeface.NORMAL)
        preview.paintFlags = preview.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
    }

    private inner class ReaderHighlightPreviewTextView(context: Context) : TextView(context) {
        private var previewCss: String = ""
        private var previewPath: String = ""
        private var previewSlice: String = ""
        private var previewFallbackColor: Int = Color.TRANSPARENT
        private var previewHasFallbackFill: Boolean = false
        private var previewBitmap: Bitmap? = null
        private var previewNinePatchDrawable: NinePatchDrawable? = null
        private var previewBitmapPath: String = ""
        private var previewDrawLogKey: String = ""

        fun setHighlightPreview(
            css: String,
            path: String,
            slice: String,
            fallbackColor: Int,
            hasFallbackFill: Boolean,
        ) {
            previewCss = css
            previewPath = path
            previewSlice = slice
            previewFallbackColor = fallbackColor
            previewHasFallbackFill = hasFallbackFill
            invalidatePreviewBitmap()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            drawPreviewLineBackgrounds(canvas)
            super.onDraw(canvas)
        }

        private fun invalidatePreviewBitmap() {
            if (previewPath == previewBitmapPath && (previewBitmap != null || previewPath.isBlank())) return
            previewBitmapPath = previewPath
            previewBitmap = null
            previewNinePatchDrawable = null
            previewDrawLogKey = ""
            val assetName = previewPath.removePrefix("asset://").takeIf { it != previewPath }
            val bitmap = ReaderHighlightImageAssets.decodeBitmap(previewPath, context, LOG_PREFIX) ?: return
            previewBitmap = bitmap
            XposedBridge.log("$LOG_PREFIX highlight preview bitmap loaded path=$previewPath size=${bitmap.width}x${bitmap.height} alpha=${bitmap.hasAlpha()}")
            if (bitmap.ninePatchChunk != null) {
                previewNinePatchDrawable = NinePatchDrawable(resources, bitmap, bitmap.ninePatchChunk, Rect(), assetName ?: File(previewPath).name)
            }
        }

        private fun drawPreviewLineBackgrounds(canvas: Canvas) {
            val layout = layout ?: run {
                logPreviewDrawOnce("layout-null|$previewPath", "highlight preview draw skipped: layout unavailable path=$previewPath")
                return
            }
            val bitmap = previewBitmap
            val hasImage = bitmap != null
            if (!hasImage && !previewHasFallbackFill) {
                logPreviewDrawOnce("empty|$previewPath", "highlight preview draw skipped: no image/fallback path=$previewPath")
                return
            }
            val box = previewBoxStyle(previewCss, resources.displayMetrics.density)
            val nineSlice = bitmap?.let { parsePreviewNineSlice(previewSlice, it.width, it.height) }
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = previewFallbackColor }
            var drawnRects = 0
            for (line in 0 until layout.lineCount) {
                val lineLeft = layout.getLineLeft(line)
                val lineRight = layout.getLineRight(line)
                if (lineRight <= lineLeft) continue
                val rect = Rect(
                    (totalPaddingLeft + lineLeft - box.paddingLeftPx - box.marginLeftPx).toInt(),
                    (totalPaddingTop + layout.getLineTop(line) - box.paddingTopPx - box.marginTopPx).toInt(),
                    (totalPaddingLeft + lineRight + box.paddingRightPx + box.marginRightPx).toInt(),
                    (totalPaddingTop + layout.getLineBottom(line) + box.paddingBottomPx + box.marginBottomPx).toInt(),
                )
                if (rect.width() <= 0 || rect.height() <= 0) continue
                val saveCount = if (box.radiusPx > 0f) {
                    val path = android.graphics.Path().apply {
                        addRoundRect(RectF(rect), box.radiusPx, box.radiusPx, android.graphics.Path.Direction.CW)
                    }
                    val count = canvas.save()
                    canvas.clipPath(path)
                    count
                } else {
                    -1
                }
                if (bitmap != null) {
                    when {
                        previewNinePatchDrawable != null -> {
                            previewNinePatchDrawable?.bounds = rect
                            previewNinePatchDrawable?.draw(canvas)
                        }
                        nineSlice != null -> drawPreviewNineSlice(canvas, bitmap, nineSlice, rect)
                        else -> drawPreviewBitmapByBackgroundSize(canvas, bitmap, rect, box.backgroundSize)
                    }
                } else {
                    canvas.drawRect(rect, fillPaint)
                }
                if (saveCount >= 0) canvas.restoreToCount(saveCount)
                drawPreviewCssBorder(canvas, rect, box)
                drawnRects += 1
            }
            logPreviewDrawOnce(
                "draw|$previewPath|$drawnRects|${bitmap?.width}x${bitmap?.height}",
                "highlight preview draw path=$previewPath rects=$drawnRects bitmap=${bitmap?.width}x${bitmap?.height} size=${width}x${height}",
            )
        }

        private fun logPreviewDrawOnce(key: String, message: String) {
            if (previewDrawLogKey == key) return
            previewDrawLogKey = key
            XposedBridge.log("$LOG_PREFIX $message")
        }
    }

    private fun parsePreviewNineSlice(value: String, bitmapWidth: Int, bitmapHeight: Int): PreviewNineSlice? {
        if (value.isBlank()) return null
        val numbers = Regex("""-?\d+(?:\.\d+)?""").findAll(value).map { it.value.toFloat() }.toList()
        if (numbers.size < 4) return null
        val scaleX = if (numbers.size >= 6 && numbers[4] > 0f) bitmapWidth / numbers[4] else 1f
        val scaleY = if (numbers.size >= 6 && numbers[5] > 0f) bitmapHeight / numbers[5] else 1f
        val left = (numbers[0] * scaleX).toInt().coerceIn(0, bitmapWidth)
        val top = (numbers[1] * scaleY).toInt().coerceIn(0, bitmapHeight)
        val right = (numbers[2] * scaleX).toInt().coerceIn(left, bitmapWidth)
        val bottom = (numbers[3] * scaleY).toInt().coerceIn(top, bitmapHeight)
        if (right <= left || bottom <= top) return null
        return PreviewNineSlice(left, top, right, bottom)
    }

    private fun drawPreviewNineSlice(canvas: Canvas, bitmap: Bitmap, slice: PreviewNineSlice, dst: Rect) {
        val srcX = intArrayOf(0, slice.left, slice.right, bitmap.width)
        val srcY = intArrayOf(0, slice.top, slice.bottom, bitmap.height)
        val leftWidth = minOf(slice.left, dst.width() / 2)
        val rightWidth = minOf(bitmap.width - slice.right, dst.width() - leftWidth)
        val topHeight = minOf(slice.top, dst.height() / 2)
        val bottomHeight = minOf(bitmap.height - slice.bottom, dst.height() - topHeight)
        val dstX = intArrayOf(dst.left, dst.left + leftWidth, dst.right - rightWidth, dst.right)
        val dstY = intArrayOf(dst.top, dst.top + topHeight, dst.bottom - bottomHeight, dst.bottom)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val source = Rect(srcX[col], srcY[row], srcX[col + 1], srcY[row + 1])
                val target = Rect(dstX[col], dstY[row], dstX[col + 1], dstY[row + 1])
                if (source.width() > 0 && source.height() > 0 && target.width() > 0 && target.height() > 0) {
                    canvas.drawBitmap(bitmap, source, target, null)
                }
            }
        }
    }

    private fun drawPreviewBitmapByBackgroundSize(
        canvas: Canvas,
        bitmap: Bitmap,
        dst: Rect,
        backgroundSize: String,
    ) {
        if (backgroundSize.contains("100% 100%", ignoreCase = true) ||
            backgroundSize.contains("stretch", ignoreCase = true)
        ) {
            canvas.drawBitmap(bitmap, null, dst, null)
            return
        }
        drawPreviewCoverBitmap(canvas, bitmap, dst)
    }

    private fun drawPreviewCoverBitmap(canvas: Canvas, bitmap: Bitmap, dst: Rect) {
        if (dst.width() <= 0 || dst.height() <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return
        val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val dstAspect = dst.width().toFloat() / dst.height().toFloat()
        val src = if (srcAspect > dstAspect) {
            val width = (bitmap.height * dstAspect).toInt().coerceIn(1, bitmap.width)
            val left = (bitmap.width - width) / 2
            Rect(left, 0, left + width, bitmap.height)
        } else {
            val height = (bitmap.width / dstAspect).toInt().coerceIn(1, bitmap.height)
            val top = (bitmap.height - height) / 2
            Rect(0, top, bitmap.width, top + height)
        }
        canvas.drawBitmap(bitmap, src, dst, null)
    }

    private fun drawPreviewCssBorder(canvas: Canvas, rect: Rect, box: PreviewBoxStyle) {
        if (box.borderWidthPx <= 0f || box.borderColor == null || rect.width() <= 0 || rect.height() <= 0) return
        val inset = box.borderWidthPx / 2f
        val borderRect = RectF(rect).apply { inset(inset, inset) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = box.borderWidthPx
            color = box.borderColor
        }
        if (box.radiusPx > 0f) {
            canvas.drawRoundRect(borderRect, box.radiusPx, box.radiusPx, paint)
        } else {
            canvas.drawRect(borderRect, paint)
        }
    }

    private fun previewBoxStyle(css: String, density: Float): PreviewBoxStyle {
        val values = readerHighlightCssProperties(css)
        val padding = previewCssBoxEdges(values, "padding", density).scale(PREVIEW_REEDEN_BOX_EDGE_SCALE)
        val margin = previewCssBoxEdges(values, "margin", density).scale(PREVIEW_REEDEN_BOX_EDGE_SCALE)
        val border = values["border"].orEmpty()
        val borderWidth = values["border-width"]?.let { previewCssSizePx(it, density) }
            ?: Regex("""(\d+(?:\.\d+)?)px""").find(border)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let { it * density }
            ?: 0f
        val borderColor = parseSettingsColor(values["border-color"].orEmpty())
            ?: Regex("""rgba?\([^)]+\)|#[0-9a-fA-F]{6,8}""").find(border)?.value?.let(::parseSettingsColor)
        return PreviewBoxStyle(
            paddingLeftPx = padding.left,
            paddingTopPx = padding.top,
            paddingRightPx = padding.right,
            paddingBottomPx = padding.bottom,
            marginLeftPx = margin.left,
            marginTopPx = margin.top,
            marginRightPx = margin.right,
            marginBottomPx = margin.bottom,
            borderWidthPx = borderWidth,
            borderColor = borderColor,
            radiusPx = values["border-radius"]?.let { previewCssSizePx(it, density) } ?: 0f,
            backgroundSize = values["background-size"].orEmpty(),
        )
    }

    private fun previewCssBoxEdges(values: Map<String, String>, prefix: String, density: Float): PreviewBoxEdges {
        val shorthand = values[prefix].orEmpty()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { previewCssSizePx(it, density) }
        val top = values["$prefix-top"]?.let { previewCssSizePx(it, density) } ?: shorthand.getOrNull(0) ?: 0f
        val right = values["$prefix-right"]?.let { previewCssSizePx(it, density) } ?: shorthand.getOrNull(1) ?: shorthand.getOrNull(0) ?: 0f
        val bottom = values["$prefix-bottom"]?.let { previewCssSizePx(it, density) } ?: shorthand.getOrNull(2) ?: shorthand.getOrNull(0) ?: 0f
        val left = values["$prefix-left"]?.let { previewCssSizePx(it, density) } ?: shorthand.getOrNull(3) ?: shorthand.getOrNull(1) ?: shorthand.getOrNull(0) ?: 0f
        return PreviewBoxEdges(left = left, top = top, right = right, bottom = bottom)
    }

    private fun previewCssSizePx(value: String, density: Float): Float {
        val trimmed = value.trim().lowercase()
        val number = Regex("""-?\d+(?:\.\d+)?""").find(trimmed)?.value?.toFloatOrNull() ?: return 0f
        return when {
            trimmed.endsWith("px") -> number * density
            trimmed.endsWith("dp") -> number * density
            trimmed.endsWith("em") -> number * 16f * density
            trimmed.endsWith("rem") -> number * 16f * density
            else -> number * density
        }.coerceAtLeast(0f)
    }

    private data class PreviewNineSlice(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private data class PreviewBoxEdges(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        fun scale(factor: Float): PreviewBoxEdges =
            PreviewBoxEdges(left * factor, top * factor, right * factor, bottom * factor)
    }

    private data class PreviewBoxStyle(
        val paddingLeftPx: Float,
        val paddingTopPx: Float,
        val paddingRightPx: Float,
        val paddingBottomPx: Float,
        val marginLeftPx: Float,
        val marginTopPx: Float,
        val marginRightPx: Float,
        val marginBottomPx: Float,
        val borderWidthPx: Float,
        val borderColor: Int?,
        val radiusPx: Float,
        val backgroundSize: String,
    )

    private fun readerHighlightCssProperties(css: String): Map<String, String> =
        css.split(';')
            .mapNotNull { part ->
                val index = part.indexOf(':')
                if (index <= 0) {
                    null
                } else {
                    val key = part.substring(0, index).trim().lowercase()
                    val value = part.substring(index + 1).trim()
                    if (isSupportedReaderHighlightCssProperty(key, value)) key to value else null
                }
            }
            .toMap()

    private fun sanitizeReaderHighlightCss(css: String): String =
        css.split(';')
            .mapNotNull { part ->
                val index = part.indexOf(':')
                if (index <= 0) return@mapNotNull null
                val key = part.substring(0, index).trim().lowercase()
                val value = part.substring(index + 1).trim()
                if (key.isBlank() || value.isBlank() || !isSupportedReaderHighlightCssProperty(key, value)) {
                    null
                } else {
                    "$key: $value"
                }
            }
            .joinToString("; ")

    private fun isSupportedReaderHighlightCssProperty(key: String, value: String): Boolean {
        if (value.isBlank()) return false
        if (value.contains("url(", ignoreCase = true)) return false
        return when (key) {
            "color", "background", "background-color", "border-color" ->
                parseSettingsColor(value) != null
            "background-size" ->
                isSupportedReaderHighlightBackgroundSize(value)
            "border" ->
                readerHighlightCssSizeRegex.containsMatchIn(value) || readerHighlightCssColorRegex.containsMatchIn(value)
            "font-size" ->
                isReaderHighlightFontSizeValue(value)
            "border-width", "border-radius",
            "padding-left", "padding-top", "padding-right", "padding-bottom",
            "margin-left", "margin-top", "margin-right", "margin-bottom" ->
                isReaderHighlightCssSizeValue(value)
            "padding", "margin" ->
                isReaderHighlightCssBoxValue(value)
            "reeden-background-nine-slice", "--reeden-background-nine-slice" ->
                Regex("""-?\d+(?:\.\d+)?""").findAll(value).count() >= 4
            else -> false
        }
    }

    private val readerHighlightCssSizeRegex = Regex("""\b\d+(?:\.\d+)?(?:px|dp|em|rem)?\b""", RegexOption.IGNORE_CASE)
    private val readerHighlightCssColorRegex = Regex("""rgba?\([^)]+\)|#[0-9a-fA-F]{6,8}""")

    private fun isReaderHighlightCssSizeValue(value: String): Boolean =
        value.trim().matches(Regex("""\d+(?:\.\d+)?(?:px|dp|em|rem)?""", RegexOption.IGNORE_CASE))

    private fun isReaderHighlightFontSizeValue(value: String): Boolean =
        value.trim().matches(Regex("""\d+(?:\.\d+)?\s*(?:px|sp|em|rem|pt)?""", RegexOption.IGNORE_CASE))

    private fun isReaderHighlightCssBoxValue(value: String): Boolean {
        val parts = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return parts.size in 1..4 && parts.all(::isReaderHighlightCssSizeValue)
    }

    private fun isSupportedReaderHighlightBackgroundSize(value: String): Boolean {
        val normalized = value.trim().lowercase().replace(Regex("\\s+"), " ")
        return normalized == "100% 100%" || normalized == "stretch"
    }

    private fun parseSettingsColor(value: String): Int? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        runCatching { Color.parseColor(trimmed) }.getOrNull()?.let { return it }
        val groups = Regex("""rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?:\s*,\s*([0-9.]+))?\s*\)""")
            .find(trimmed)
            ?.groupValues
            ?: return null
        val alpha = groups.getOrNull(4)
            ?.takeIf { it.isNotBlank() }
            ?.toFloatOrNull()
            ?.let { if (it <= 1f) (it * 255).toInt() else it.toInt() }
            ?: 255
        return Color.argb(
            alpha.coerceIn(0, 255),
            groups[1].toInt().coerceIn(0, 255),
            groups[2].toInt().coerceIn(0, 255),
            groups[3].toInt().coerceIn(0, 255),
        )
    }

    private fun openReaderHighlightStyleImportPicker() {
        val activity = activityProvider() ?: return
        runCatching {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
            }
            activity.startActivityForResult(intent, HIGHLIGHT_STYLE_DOCUMENT_REQUEST_CODE)
        }.onFailure {
            showToast("\u6253\u5f00\u9ad8\u4eae\u6837\u5f0f\u5bfc\u5165\u5931\u8d25")
            XposedBridge.log("$LOG_PREFIX open highlight style import failed: ${it.stackTraceToString()}")
        }
    }

    private fun openHighlightNinePatchPicker(activity: Activity, input: EditText) {
        runCatching {
            pendingHighlightNinePatchInputRef = WeakReference(input)
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/png"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "application/octet-stream", "*/*"))
            }
            activity.startActivityForResult(intent, HIGHLIGHT_NINE_PATCH_DOCUMENT_REQUEST_CODE)
        }.onFailure {
            showToast("\u6253\u5f00\u56fe\u7247\u9009\u62e9\u5931\u8d25")
            XposedBridge.log("$LOG_PREFIX open highlight image picker failed: ${it.stackTraceToString()}")
        }
    }

    private fun importHighlightNinePatchFromUri(activity: Activity, uri: Uri) {
        runCatching {
            val target = copyHighlightNinePatchUri(activity, uri)
            pendingHighlightNinePatchInputRef?.get()?.setText(target.absolutePath)
            showToast("\u5df2\u9009\u62e9\u56fe\u7247\uff1a${target.name}")
        }.onFailure {
            showToast(it.message ?: "\u9009\u62e9\u56fe\u7247\u5931\u8d25")
            XposedBridge.log("$LOG_PREFIX import highlight image failed: ${it.stackTraceToString()}")
        }
    }

    private fun openProfileBackgroundImagePicker() {
        val activity = activityProvider() ?: return
        runCatching {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            val chooser = Intent.createChooser(intent, "\u9009\u62e9\u80cc\u666f\u56fe\u7247").apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(intent))
            }
            activity.startActivityForResult(chooser, PROFILE_BACKGROUND_IMAGE_DOCUMENT_REQUEST_CODE)
        }.onFailure {
            showToast("\u6253\u5f00\u80cc\u666f\u56fe\u7247\u9009\u62e9\u5931\u8d25")
            XposedBridge.log("$LOG_PREFIX open profile background image picker failed: ${it.stackTraceToString()}")
        }
    }

    private fun importProfileBackgroundImageFromUri(activity: Activity, uri: Uri) {
        runCatching {
            val target = copyProfileBackgroundImageUri(activity, uri)
            settings.setProfileBackgroundImage(target.absolutePath)
            settings.setProfileBackgroundUseImage(true)
            showToast("\u5df2\u9009\u62e9\u80cc\u666f\u56fe\u7247\uff1a${target.name}")
            bumpProfileBackgroundVersion()
        }.onFailure {
            showToast(it.message ?: "\u9009\u62e9\u80cc\u666f\u56fe\u7247\u5931\u8d25")
            XposedBridge.log("$LOG_PREFIX import profile background image failed: ${it.stackTraceToString()}")
        }
    }

    private fun copyProfileBackgroundImageUri(activity: Activity, uri: Uri): File {
        val rawName = queryDisplayName(activity, uri) ?: uri.lastPathSegment.orEmpty()
        val extension = rawName.substringAfterLast('.', "png").lowercase()
        val safeExt = if (extension.matches(Regex("^[a-z0-9]{1,8}$"))) extension else "png"
        val displayName = "profile_background_${System.currentTimeMillis()}.$safeExt"
        val targetDir = File(activity.filesDir, "profile_background").apply { mkdirs() }
        targetDir.listFiles()?.forEach { it.delete() }
        val target = File(targetDir, displayName)
        activity.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().buffered().use { output -> input.copyTo(output) }
        } ?: error("\u65e0\u6cd5\u8bfb\u53d6\u80cc\u666f\u56fe\u7247")
        return target
    }

    private fun importReaderHighlightStyleFromUri(activity: Activity, uri: Uri) {
        runCatching {
            val bytes = activity.contentResolver.openInputStream(uri)
                ?.use { it.readBytes() }
                ?: error("empty highlight style file")
            val imported = readerHighlightImportFromBytes(activity, bytes)
            imported.styles.forEach(settings::setReaderHighlightStyle)
            imported.rules.forEach(settings::setReaderHighlightRule)
            bumpReaderHighlightVersion()
            showToast(
                if (imported.reeden) {
                    if (imported.styles.isEmpty() && imported.skippedDuplicates > 0) {
                        "\u5df2\u5b58\u5728 Reeden \u9ad8\u4eae\u6837\u5f0f\uff1a${imported.skippedDuplicates} \u4e2a"
                    } else if (imported.skippedDuplicates > 0) {
                        "\u5df2\u5bfc\u5165 Reeden \u9ad8\u4eae\u6837\u5f0f\uff1a${imported.styles.size} \u4e2a\uff0c\u8df3\u8fc7 ${imported.skippedDuplicates} \u4e2a\u91cd\u590d"
                    } else {
                        "\u5df2\u5bfc\u5165 Reeden \u9ad8\u4eae\u6837\u5f0f\uff1a${imported.styles.size} \u4e2a"
                    }
                } else if (imported.styles.size == 1) {
                    "\u5df2\u5bfc\u5165\u9ad8\u4eae\u6837\u5f0f\uff1a${imported.styles.first().name}"
                } else {
                    "\u5df2\u5bfc\u5165 ${imported.styles.size} \u4e2a\u9ad8\u4eae\u6837\u5f0f"
                },
            )
        }.onFailure {
            showToast("\u5bfc\u5165\u9ad8\u4eae\u6837\u5f0f\u5931\u8d25")
            XposedBridge.log("$LOG_PREFIX import highlight style failed: ${it.stackTraceToString()}")
        }
    }

    private fun readerHighlightImportFromBytes(activity: Activity, bytes: ByteArray): ReaderHighlightImport {
        if (bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 1.toByte()
        ) {
            return readerHighlightImportFromReedenRed(activity, bytes)
        }
        return ReaderHighlightImport(
            styles = listOf(readerHighlightStyleFromJson(activity, JSONObject(bytes.toString(Charsets.UTF_8)))),
            rules = emptyList(),
            reeden = false,
        )
    }

    private fun readerHighlightStyleExportJson(activity: Activity, style: ReaderHighlightStyle): JSONObject =
        JSONObject()
            .put("type", "reader_highlight_style")
            .put("version", 1)
            .put("style", readerHighlightStyleJson(style))

    private fun readerHighlightStyleJson(style: ReaderHighlightStyle): JSONObject =
        JSONObject()
            .put("id", style.id)
            .put("name", style.name)
            .put("color", style.color)
            .put("fontFamily", style.fontFamily)
            .put("css", sanitizeReaderHighlightCss(style.css))
            .put("ninePatchPath", style.ninePatchPath)
            .put("ninePatchSlice", style.ninePatchSlice)
            .apply {
                highlightNinePatchJson(style.ninePatchPath)?.let { put("ninePatchFile", it) }
            }

    private fun readerHighlightStyleFromJson(activity: Activity, root: JSONObject): ReaderHighlightStyle {
        val item = root.optJSONObject("style") ?: root
        val existingIds = settings.highlightSettings().styles.map { it.id }.toSet()
        val rawId = item.optString("id").takeIf { it.isNotBlank() } ?: uniqueReaderHighlightId("style")
        val id = if (rawId in existingIds || rawId == ModuleSettings.DEFAULT_READER_HIGHLIGHT_STYLE_ID) {
            uniqueReaderHighlightId("style")
        } else {
            rawId
        }
        return ReaderHighlightStyle(
            id = id,
            name = item.optString("name").ifBlank { "\u5bfc\u5165\u9ad8\u4eae\u6837\u5f0f" },
            color = item.optString("color").ifBlank { ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR },
            fontFamily = item.optString("fontFamily"),
            css = sanitizeReaderHighlightCss(item.optString("css")),
            ninePatchPath = restoreHighlightNinePatchFromJson(activity, item.optJSONObject("ninePatchFile"))
                ?: item.optString("ninePatchPath"),
            ninePatchSlice = item.optString("ninePatchSlice"),
        )
    }

    private fun readerHighlightImportFromReedenRed(activity: Activity, bytes: ByteArray): ReaderHighlightImport {
        val json = GZIPInputStream(bytes.copyOfRange(4, bytes.size).inputStream())
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val root = JSONObject(json)
        if (root.optString("type") != "highlightRule") error("not a Reeden highlight rule file")
        val data = root.optJSONArray("data") ?: JSONArray()
        val existingStyles = settings.highlightSettings().styles
        val existingIds = existingStyles.map { it.id }.toMutableSet()
        val existingKeys = existingStyles.mapNotNull(::readerHighlightStyleImportKey).toMutableSet()
        val imagePathByHash = HashMap<String, String>()
        val styles = ArrayList<ReaderHighlightStyle>()
        var skippedDuplicates = 0
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val rawCss = item.optString("styleCssText")
            val css = sanitizeReaderHighlightCss(rawCss)
            val imageData = item.optString("backgroundImageData")
            if (css.isBlank() || imageData.isBlank()) continue
            val imageBytes = runCatching {
                GZIPInputStream(Base64.decode(imageData, Base64.DEFAULT).inputStream()).use { it.readBytes() }
            }.getOrNull() ?: continue
            val hash = md5Hex(imageBytes)
            val importKey = readerHighlightStyleImportKey(css, hash)
            if (!existingKeys.add(importKey)) {
                skippedDuplicates++
                continue
            }
            val imagePath = imagePathByHash.getOrPut(hash) {
                writeReaderHighlightImage(activity, imageBytes, "$hash.png").absolutePath
            }
            var id: String
            var suffix = 0
            do {
                id = "style_reeden_${System.currentTimeMillis()}_${index}_$suffix"
                suffix++
            } while (!existingIds.add(id))
            styles.add(
                ReaderHighlightStyle(
                    id = id,
                    name = item.optString("name").ifBlank { "Reeden \u9ad8\u4eae\u6837\u5f0f ${styles.size + 1}" },
                    color = colorFromReedenCss(rawCss),
                    fontFamily = "",
                    css = css,
                    ninePatchPath = imagePath,
                    ninePatchSlice = reedenNineSlice(css),
                ),
            )
        }
        if (styles.isEmpty() && skippedDuplicates == 0) error("no Reeden highlight styles found")
        return ReaderHighlightImport(styles = styles, rules = emptyList(), reeden = true, skippedDuplicates = skippedDuplicates)
    }

    private fun readerHighlightStyleImportKey(style: ReaderHighlightStyle): String? {
        val path = style.ninePatchPath.trim()
        val css = sanitizeReaderHighlightCss(style.css)
        if (css.isBlank() || path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        val hash = runCatching { md5Hex(file.readBytes()) }.getOrNull() ?: return null
        return readerHighlightStyleImportKey(css, hash)
    }

    private fun readerHighlightStyleImportKey(css: String, imageHash: String): String =
        "${sanitizeReaderHighlightCss(css)}|${imageHash.uppercase()}"

    private fun copyHighlightNinePatchUri(activity: Activity, uri: Uri): File {
        val rawName = queryDisplayName(activity, uri) ?: uri.lastPathSegment.orEmpty()
        if (!rawName.endsWith(".png", ignoreCase = true)) {
            error("\u8bf7\u9009\u62e9 PNG \u56fe\u7247")
        }
        val target = if (rawName.endsWith(".9.png", ignoreCase = true)) {
            uniqueHighlightNinePatchFile(activity, sanitizeNinePatchFileName(rawName))
        } else {
            uniqueHighlightImageFile(activity, sanitizeHighlightImageFileName(rawName))
        }
        activity.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().buffered().use { output -> input.copyTo(output) }
        } ?: error("\u65e0\u6cd5\u8bfb\u53d6\u56fe\u7247")
        return target
    }

    private fun highlightNinePatchJson(path: String): JSONObject? {
        val file = File(path)
        if (!file.isFile) return null
        return JSONObject()
            .put("name", file.name)
            .put("mime", "image/png")
            .put("base64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
    }

    private fun restoreHighlightNinePatchFromJson(activity: Activity, item: JSONObject?): String? {
        item ?: return null
        val encoded = item.optString("base64")
        if (encoded.isBlank()) return null
        val name = sanitizeHighlightImageFileName(item.optString("name").ifBlank { "highlight.png" })
        val target = uniqueHighlightImageFile(activity, name)
        target.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
        return target.absolutePath
    }

    private fun uniqueHighlightNinePatchFile(activity: Activity, displayName: String): File {
        val dir = File(activity.filesDir, "reader_highlight_nine_patch").apply { mkdirs() }
        val base = displayName.removeSuffix(".9.png").ifBlank { "highlight" }
        var target = File(dir, "$base.9.png")
        var index = 1
        while (target.exists()) {
            target = File(dir, "${base}_$index.9.png")
            index++
        }
        return target
    }

    private fun writeReaderHighlightImage(activity: Activity, bytes: ByteArray, displayName: String): File =
        uniqueHighlightImageFile(activity, sanitizeHighlightImageFileName(displayName)).apply {
            writeBytes(bytes)
        }

    private fun sanitizeNinePatchFileName(name: String): String {
        val cleaned = safeDownloadName(name)
            .removeSuffix(".png")
            .removeSuffix(".9")
            .trim('.', ' ')
            .ifBlank { "highlight" }
        return "$cleaned.9.png"
    }

    private fun sanitizeHighlightImageFileName(name: String): String {
        val cleaned = safeDownloadName(name)
            .removeSuffix(".png")
            .trim('.', ' ')
            .ifBlank { "highlight" }
        return "$cleaned.png"
    }

    private fun uniqueHighlightImageFile(activity: Activity, displayName: String): File {
        val dir = File(activity.filesDir, "reader_highlight_nine_patch").apply { mkdirs() }
        val base = displayName.removeSuffix(".png").ifBlank { "highlight" }
        var target = File(dir, "$base.png")
        var index = 1
        while (target.exists()) {
            target = File(dir, "${base}_$index.png")
            index++
        }
        return target
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5")
            .digest(bytes)
            .joinToString("") { "%02X".format(it) }

    private fun reedenNineSlice(css: String): String =
        Regex("""(?:--)?reeden-background-nine-slice\s*:\s*([^;]+)""")
            .findAll(css)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()

    private fun colorFromReedenCss(css: String): String {
        val value = Regex("""color\s*:\s*([^;]+)""").find(css)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val rgba = Regex("""rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})""")
            .find(value)
            ?.groupValues
        if (rgba != null && rgba.size >= 4) {
            return "#%02X%02X%02X".format(
                rgba[1].toInt().coerceIn(0, 255),
                rgba[2].toInt().coerceIn(0, 255),
                rgba[3].toInt().coerceIn(0, 255),
            )
        }
        val hex = Regex("""#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})""").find(value)?.value
        return hex?.let {
            if (it.length == 9) "#${it.takeLast(6)}" else it
        }?.uppercase() ?: ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR
    }

    private fun safeDownloadName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_")
            .trim()
            .take(80)
            .ifBlank { "style" }

    private fun openReaderHighlightRuleDialog(rule: ReaderHighlightRule) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            runCatching {
                val colors = SettingsDialogColors(activity)
                val dialog = Dialog(activity)
                val card = settingsDialogCard(activity, colors)
                val highlight = settings.highlightSettings()
                val styles = highlight.styles
                val nameInput = settingsDialogInput(activity, "\u89c4\u5219\u540d\u79f0", singleLine = true, colors = colors).apply {
                    setText(rule.name)
                }
                val builtInRule = isDefaultReaderHighlightRule(rule.id)
                var selectedType = if (builtInRule) {
                    rule.type
                } else {
                    when (rule.type) {
                        ReaderHighlightRuleType.FixedText,
                        ReaderHighlightRuleType.Regex -> rule.type
                        else -> ReaderHighlightRuleType.FixedText
                    }
                }
                var selectedStyleId = rule.styleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID }
                var selectedDarkStyleId = rule.darkStyleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID }
                val typeStatus = settingsDialogStatus(activity, "\u7c7b\u578b\uff1a${highlightRuleTypeTitle(rule)}", colors)
                val patternInput = settingsDialogInput(activity, "\u5339\u914d\u5185\u5bb9", singleLine = false, colors = colors).apply {
                    setText(rule.pattern)
                    minLines = 2
                }
                lateinit var syncStyleSelection: () -> Unit
                val lightStyleStatus = settingsDialogChoiceRow(
                    activity,
                    "",
                    colors,
                ) {
                    openReaderHighlightStyleSelectionDialog(
                        activity = activity,
                        title = "\u9009\u62e9\u6d45\u8272\u6837\u5f0f",
                        styles = styles,
                        currentStyleId = selectedStyleId,
                        colors = colors,
                        defaultReferenceId = ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID,
                        defaultLabel = "\u6d45\u8272\u9ed8\u8ba4",
                    ) { styleId ->
                        selectedStyleId = styleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID }
                        syncStyleSelection.invoke()
                    }
                }
                val darkStyleStatus = settingsDialogChoiceRow(
                    activity,
                    "",
                    colors,
                ) {
                    openReaderHighlightStyleSelectionDialog(
                        activity = activity,
                        title = "\u9009\u62e9\u6df1\u8272\u6837\u5f0f",
                        styles = styles,
                        currentStyleId = selectedDarkStyleId,
                        colors = colors,
                        defaultReferenceId = ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID,
                        defaultLabel = "\u6df1\u8272\u9ed8\u8ba4",
                    ) { styleId ->
                        selectedDarkStyleId = styleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID }
                        syncStyleSelection.invoke()
                    }
                }
                val finishButton = settingsDialogButton(activity, "\u5b8c\u6210", colors)
                val deleteButton = settingsDialogButton(activity, "\u5220\u9664", colors, SettingsDialogButtonRole.Destructive)
                val cancelButton = settingsDialogButton(activity, "\u53d6\u6d88", colors, SettingsDialogButtonRole.Neutral)
                fun syncTypeSelection() {
                    typeStatus.text = "\u7c7b\u578b\uff1a${highlightRuleTypeTitle(rule.copy(type = selectedType))}"
                    val needsPattern = selectedType == ReaderHighlightRuleType.FixedText ||
                        selectedType == ReaderHighlightRuleType.Regex
                    patternInput.visibility = if (needsPattern) View.VISIBLE else View.GONE
                    patternInput.hint = when (selectedType) {
                        ReaderHighlightRuleType.FixedText -> "\u56fa\u5b9a\u6587\u672c\uff0c\u4f8b\u5982\uff1a\u91cd\u8981"
                        ReaderHighlightRuleType.Regex -> "\u6b63\u5219\u8868\u8fbe\u5f0f\uff0c\u4f8b\u5982\uff1a\\d{4}-\\d{2}-\\d{2}"
                        else -> "\u5339\u914d\u5185\u5bb9"
                    }
                }
                syncStyleSelection = {
                    lightStyleStatus.text = "\u6d45\u8272\u6837\u5f0f\uff1a${readerHighlightLightStyleLabel(highlight, selectedStyleId)}"
                    darkStyleStatus.text = "\u6df1\u8272\u6837\u5f0f\uff1a${readerHighlightDarkStyleLabel(highlight, selectedDarkStyleId)}"
                }
                card.addView(settingsDialogTitle(activity, "\u9ad8\u4eae\u89c4\u5219", colors))
                card.addView(settingsDialogHint(activity, "${highlightRuleTypeTitle(rule)}\uff1a${highlightRuleDescription(rule)}", colors))
                if (builtInRule) {
                    card.addView(settingsDialogHint(activity, "\u5185\u7f6e\u89c4\u5219\u4ec5\u652f\u6301\u5207\u6362\u6837\u5f0f\uff0c\u5339\u914d\u6587\u672c\u7531\u6a21\u5757\u5185\u7f6e\u5904\u7406\u3002", colors))
                } else {
                    card.addView(nameInput)
                    card.addView(typeStatus)
                    card.addView(
                        settingsDialogChoiceRow(activity, "\u56fa\u5b9a\u6587\u672c", colors) {
                            selectedType = ReaderHighlightRuleType.FixedText
                            syncTypeSelection()
                        },
                    )
                    card.addView(
                        settingsDialogChoiceRow(activity, "\u6b63\u5219", colors) {
                            selectedType = ReaderHighlightRuleType.Regex
                            syncTypeSelection()
                        },
                    )
                    card.addView(patternInput)
                    syncTypeSelection()
                }
                syncStyleSelection.invoke()
                card.addView(lightStyleStatus)
                card.addView(darkStyleStatus)
                val buttons = if (builtInRule) {
                    listOf(finishButton, cancelButton)
                } else {
                    listOf(deleteButton, finishButton, cancelButton)
                }
                card.addView(settingsDialogButtonRow(activity, buttons))
                finishButton.setOnClickListener {
                    settings.setReaderHighlightRule(
                        rule.copy(
                            name = if (builtInRule) {
                                rule.name
                            } else {
                                nameInput.text?.toString()?.trim().orEmpty().ifBlank { rule.name }
                            },
                            type = selectedType,
                            styleId = selectedStyleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID },
                            darkStyleId = selectedDarkStyleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID },
                            pattern = if (
                                selectedType == ReaderHighlightRuleType.FixedText ||
                                selectedType == ReaderHighlightRuleType.Regex
                            ) {
                                patternInput.text?.toString()?.trim().orEmpty()
                            } else {
                                ""
                            },
                        ),
                    )
                    bumpReaderHighlightVersion()
                    dialog.dismiss()
                }
                deleteButton.setOnClickListener {
                    settings.removeReaderHighlightRule(rule.id)
                    bumpReaderHighlightVersion()
                    dialog.dismiss()
                }
                cancelButton.setOnClickListener { dialog.dismiss() }
                showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX open highlight rule dialog failed: ${it.stackTraceToString()}")
            }
        }
    }

    private fun openReaderHighlightStyleSelectionDialog(
        activity: Activity,
        title: String,
        styles: List<ReaderHighlightStyle>,
        currentStyleId: String,
        colors: SettingsDialogColors,
        defaultReferenceId: String,
        defaultLabel: String,
        onSelected: (String) -> Unit,
    ) {
        val dialog = Dialog(activity)
        val card = settingsDialogCard(activity, colors)
        card.addView(settingsDialogTitle(activity, title, colors))
        val highlight = settings.highlightSettings()
        val defaultStyleName = highlight.styleById(defaultReferenceId).name
        card.addView(
            settingsDialogChoiceRow(
                activity,
                if (currentStyleId == defaultReferenceId) {
                    "$defaultLabel\uff08$defaultStyleName\uff09  \u5f53\u524d"
                } else {
                    "$defaultLabel\uff08$defaultStyleName\uff09"
                },
                colors,
            ) {
                onSelected(defaultReferenceId)
                dialog.dismiss()
            },
        )
        styles.forEach { style ->
            val selected = style.id == currentStyleId
            card.addView(
                settingsDialogChoiceRow(
                    activity,
                    if (selected) "${style.name}  \u5f53\u524d" else style.name,
                    colors,
                ) {
                    onSelected(style.id)
                    dialog.dismiss()
                },
            )
        }
        showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, 0.92f)
    }

    private fun renderCloudCompletionSettingsContent(innerPaddings: Any, composer: Any) {
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
            addLazyItem(lazyListScope, CLOUD_SWITCHES_ITEM_KEY) { itemComposer ->
                renderHostSettingsCard(rows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun renderRotationCompletionSettingsContent(innerPaddings: Any, composer: Any) {
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

    private fun renderAccountSwitchContent(innerPaddings: Any, composer: Any) {
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

    private fun renderAccountSecurityExportContent(state: Any, deleteDialogState: Any, composer: Any) {
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

    private fun renderHostDeleteAccountItem(state: Any, deleteDialogState: Any, composer: Any) {
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

    private fun exportCurrentAccountCredential() {
        val activity = activityProvider() ?: return
        Thread {
            runCatching { accountController.exportCurrentCredential() }
                .onSuccess { exported ->
                    activity.runOnUiThread {
                        runCatching {
                            bumpAccountListVersion()
                            copyTextToClipboard(activity, "\u9605\u5fae\u8d26\u53f7\u51ed\u8bc1", exported.credential)
                            showToast("\u5df2\u590d\u5236\u8d26\u53f7 ${exported.account.accountId} \u7684\u51ed\u8bc1")
                        }.onFailure {
                            XposedBridge.log("$LOG_PREFIX account credential copy failed: ${it.stackTraceToString()}")
                            showToast(it.message ?: "\u590d\u5236\u51ed\u8bc1\u5931\u8d25")
                        }
                    }
                }
                .onFailure {
                    XposedBridge.log("$LOG_PREFIX account export failed: ${it.stackTraceToString()}")
                    showToast(it.message ?: "\u5bfc\u51fa\u51ed\u8bc1\u5931\u8d25")
                }
        }.start()
    }

    private fun exportAccountData(accountId: Long) {
        val activity = activityProvider() ?: return
        val progress = showExportProgress(
            activity = activity,
            title = "\u6b63\u5728\u5bfc\u51fa\u5168\u90e8\u6570\u636e",
            message = "\u6b63\u5728\u6574\u7406\u8d26\u53f7\u6570\u636e\uff0c\u8bf7\u7a0d\u5019",
        )
        Thread {
            var exportedFile: File? = null
            runCatching {
                if (progress.isCancelled) error("export canceled")
                val exported = accountController.exportAccountDataBundleFile(accountId)
                exportedFile = exported.file
                if (progress.isCancelled) error("export canceled")
                progress.update("\u6b63\u5728\u5199\u5165\u4e0b\u8f7d\u76ee\u5f55")
                writeFileToDownloads(activity, exported.fileName, "application/zip", exported.file)
                if (progress.isCancelled) error("export canceled")
                exported
            }.onSuccess { exported ->
                exported.file.delete()
                activity.runOnUiThread {
                    progress.dismiss()
                    bumpAccountListVersion()
                    showToast(
                        "\u5df2\u5bfc\u51fa\u8d26\u53f7 ${exported.account.accountId} \u7684\u5168\u90e8\u6570\u636e\u5230\u4e0b\u8f7d\u76ee\u5f55\uff1a${exported.fileName}",
                    )
                }
            }.onFailure {
                exportedFile?.delete()
                progress.dismiss()
                if (!progress.isCancelled) {
                    XposedBridge.log("$LOG_PREFIX account data export failed: ${it.stackTraceToString()}")
                    showToast(it.message ?: "\u5bfc\u51fa\u8d26\u53f7\u6570\u636e\u5931\u8d25")
                }
            }
        }.start()
    }

    private fun exportAllAccountData() {
        val activity = activityProvider() ?: return
        val progress = showExportProgress(
            activity = activity,
            title = "\u6b63\u5728\u5bfc\u51fa\u6240\u6709\u8d26\u53f7\u6570\u636e",
            message = "\u6b63\u5728\u9010\u4e2a\u6574\u7406\u8d26\u53f7\u6570\u636e\uff0c\u8bf7\u7a0d\u5019",
        )
        Thread {
            val exportedFiles = mutableListOf<File>()
            runCatching {
                if (progress.isCancelled) error("export canceled")
                val exported = accountController.exportAllAccountDataBundleFiles()
                exportedFiles.addAll(exported.map { it.file })
                exported.forEachIndexed { index, bundle ->
                    if (progress.isCancelled) error("export canceled")
                    progress.update("\u6b63\u5728\u5199\u5165\u4e0b\u8f7d\u76ee\u5f55 ${index + 1}/${exported.size}")
                    writeFileToDownloads(activity, bundle.fileName, "application/zip", bundle.file)
                }
                if (progress.isCancelled) error("export canceled")
                exported
            }.onSuccess { exported ->
                exported.forEach { it.file.delete() }
                activity.runOnUiThread {
                    progress.dismiss()
                    bumpAccountListVersion()
                    showToast("\u5df2\u9010\u4e2a\u5bfc\u51fa ${exported.size} \u4e2a\u8d26\u53f7\u7684\u6570\u636e\u5305")
                }
            }.onFailure {
                exportedFiles.forEach { it.delete() }
                progress.dismiss()
                if (!progress.isCancelled) {
                    XposedBridge.log("$LOG_PREFIX all account data export failed: ${it.stackTraceToString()}")
                    showToast(it.message ?: "\u5bfc\u51fa\u5168\u90e8\u8d26\u53f7\u6570\u636e\u5931\u8d25")
                }
            }
        }.start()
    }

    private fun exportAccountBooks(accountId: Long) {
        val activity = activityProvider() ?: return
        val progress = showExportProgress(
            activity = activity,
            title = "\u6b63\u5728\u5bfc\u51fa\u5168\u90e8\u56fe\u4e66",
            message = "\u6b63\u5728\u91cd\u65b0\u6253\u5305 EPUB\uff0c\u8bf7\u7a0d\u5019",
        )
        Thread {
            var exportedFile: File? = null
            runCatching {
                if (progress.isCancelled) error("export canceled")
                val exported = accountController.exportAccountBooksBundleFile(accountId)
                exportedFile = exported.file
                if (progress.isCancelled) error("export canceled")
                progress.update("\u6b63\u5728\u5199\u5165\u4e0b\u8f7d\u76ee\u5f55")
                writeFileToDownloads(activity, exported.fileName, "application/zip", exported.file)
                if (progress.isCancelled) error("export canceled")
                exported
            }.onSuccess { exported ->
                exported.file.delete()
                activity.runOnUiThread {
                    progress.dismiss()
                    showToast(
                        "\u5df2\u5bfc\u51fa\u8d26\u53f7 ${exported.account.accountId} \u7684\u5168\u90e8\u56fe\u4e66\u5230\u4e0b\u8f7d\u76ee\u5f55\uff1a${exported.fileName}",
                    )
                }
            }.onFailure {
                exportedFile?.delete()
                progress.dismiss()
                if (!progress.isCancelled) {
                    XposedBridge.log("$LOG_PREFIX account books export failed: ${it.stackTraceToString()}")
                    showToast(it.message ?: "\u5bfc\u51fa\u8d26\u53f7\u5168\u90e8\u56fe\u4e66\u5931\u8d25")
                }
            }
        }.start()
    }

    private fun openAccountDataDocumentPicker() {
        val activity = activityProvider() ?: return
        runCatching {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "application/zip",
                        "application/octet-stream",
                    ),
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivityForResult(intent, ACCOUNT_DATA_DOCUMENT_REQUEST_CODE)
        }.onFailure {
            Toast.makeText(activity, "\u65e0\u6cd5\u6253\u5f00\u6570\u636e\u9009\u62e9\u5668", Toast.LENGTH_SHORT).show()
            XposedBridge.log("$LOG_PREFIX failed to open account data picker: ${it.stackTraceToString()}")
        }
    }

    private fun importAccountDataFromUri(activity: Activity, uri: Uri) {
        Thread {
            runCatching {
                val bytes = activity.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes() }
                    ?: error("\u65e0\u6cd5\u8bfb\u53d6\u8d26\u53f7\u6570\u636e\u5305")
                val imported = accountController.importAccountDataBundle(bytes)
                queryDisplayName(activity, uri).orEmpty() to imported
            }.onSuccess { (_, imported) ->
                activity.runOnUiThread {
                    bumpAccountListVersion()
                    restartHostAfterAccountSwitch(activity, imported.account.accountId)
                }
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX account data import failed: ${it.stackTraceToString()}")
                showToast(it.message ?: "\u5bfc\u5165\u8d26\u53f7\u6570\u636e\u5931\u8d25")
            }
        }.start()
    }

    private fun exportAllAccountCredentials() {
        val activity = activityProvider() ?: return
        Thread {
            runCatching {
                val exported = accountController.exportAllCredentialsBundle()
                val fileName = buildAccountCredentialBundleFileName()
                writeTextToDownloads(activity, fileName, exported.content)
                fileName to exported.count
            }.onSuccess { (fileName, count) ->
                activity.runOnUiThread {
                    bumpAccountListVersion()
                    showToast("\u5df2\u5bfc\u51fa $count \u4e2a\u8d26\u53f7\u51ed\u8bc1\u5230\u4e0b\u8f7d\u76ee\u5f55\uff1a$fileName")
                }
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX account bundle export failed: ${it.stackTraceToString()}")
                showToast(it.message ?: "\u5bfc\u51fa\u5168\u90e8\u8d26\u53f7\u51ed\u8bc1\u5931\u8d25")
            }
        }.start()
    }

    private fun importCredentialFromClipboard() {
        val activity = activityProvider() ?: return
        val raw = readClipboardText(activity).trim()
        if (raw.isBlank()) {
            showToast("\u526a\u8d34\u677f\u4e2d\u6ca1\u6709\u8d26\u53f7\u51ed\u8bc1")
            return
        }
        Thread {
            runCatching { accountController.importCredentials(raw) }
                .onSuccess { result ->
                    activity.runOnUiThread {
                        bumpAccountListVersion()
                        val firstAccountId = result.accounts.firstOrNull()?.accountId
                        val message = if (result.count <= 1 && firstAccountId != null) {
                            "\u5df2\u5bfc\u5165\u8d26\u53f7 $firstAccountId"
                        } else {
                            "\u5df2\u5bfc\u5165 ${result.count} \u4e2a\u8d26\u53f7"
                        }
                        showToast(message)
                    }
                }
                .onFailure {
                    showToast(it.message ?: "\u5bfc\u5165\u51ed\u8bc1\u5931\u8d25")
                }
        }.start()
    }

    private fun openAccountCredentialDocumentPicker() {
        val activity = activityProvider() ?: return
        runCatching {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "application/json",
                        "text/plain",
                        "application/octet-stream",
                    ),
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivityForResult(intent, ACCOUNT_CREDENTIAL_DOCUMENT_REQUEST_CODE)
        }.onFailure {
            Toast.makeText(activity, "\u65e0\u6cd5\u6253\u5f00\u51ed\u8bc1\u9009\u62e9\u5668", Toast.LENGTH_SHORT).show()
            XposedBridge.log("$LOG_PREFIX failed to open account credential picker: ${it.stackTraceToString()}")
        }
    }

    private fun importCredentialFromUri(activity: Activity, uri: Uri) {
        Thread {
            runCatching {
                val raw = activity.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?.trim()
                    .orEmpty()
                if (raw.isBlank()) {
                    error("\u9009\u62e9\u7684\u6587\u4ef6\u4e2d\u6ca1\u6709\u8d26\u53f7\u51ed\u8bc1")
                }
                val displayName = queryDisplayName(activity, uri)?.takeIf { it.isNotBlank() }
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "\u51ed\u8bc1\u6587\u4ef6"
                val result = accountController.importCredentials(raw)
                displayName to result
            }.onSuccess { (displayName, result) ->
                activity.runOnUiThread {
                    bumpAccountListVersion()
                    val firstAccountId = result.accounts.firstOrNull()?.accountId
                    val message = if (result.count <= 1 && firstAccountId != null) {
                        "\u5df2\u4ece $displayName \u5bfc\u5165\u8d26\u53f7 $firstAccountId"
                    } else {
                        "\u5df2\u4ece $displayName \u5bfc\u5165 ${result.count} \u4e2a\u8d26\u53f7"
                    }
                    showToast(message)
                }
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX account credential file import failed: ${it.stackTraceToString()}")
                showToast(it.message ?: "\u4ece\u6587\u4ef6\u5bfc\u5165\u51ed\u8bc1\u5931\u8d25")
            }
        }.start()
    }

    private fun switchToStoredAccount(accountId: Long) {
        val activity = activityProvider() ?: return
        Thread {
            runCatching { accountController.switchToAccount(accountId) }
                .onSuccess { account ->
                    activity.runOnUiThread {
                        restartHostAfterAccountSwitch(activity, account.accountId)
                    }
                }
                .onFailure {
                    showToast(it.message ?: "\u5207\u6362\u8d26\u53f7\u5931\u8d25")
                }
        }.start()
    }

    private fun copyTextToClipboard(activity: Activity, label: String, text: String) {
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
            ?: error("\u65e0\u6cd5\u83b7\u53d6\u526a\u8d34\u677f")
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun readClipboardText(activity: Activity): String {
        val clipboard = activity.getSystemService(ClipboardManager::class.java) ?: return ""
        val clip = clipboard.primaryClip ?: return ""
        return clip.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
    }

    private fun restartHostAfterAccountSwitch(activity: Activity, accountId: Long) {
        showToast("\u5df2\u5207\u6362\u5230\u8d26\u53f7 $accountId\uff0c\u6b63\u5728\u5237\u65b0")
        val launchIntent = activity.packageManager?.getLaunchIntentForPackage(activity.packageName)
        if (launchIntent == null) {
            runCatching { activity.recreate() }
                .onFailure {
                    XposedBridge.log("$LOG_PREFIX fallback recreate failed: ${it.stackTraceToString()}")
                }
            return
        }
        val restartIntent = launchIntent.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("reamicro_account_switched", accountId)
        }
        runCatching {
            activity.startActivity(restartIntent)
            activity.overridePendingTransition(0, 0)
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX host refresh after account switch failed: ${it.stackTraceToString()}")
            runCatching {
                activity.recreate()
            }.onFailure { fallbackError ->
                XposedBridge.log("$LOG_PREFIX fallback host refresh failed: ${fallbackError.stackTraceToString()}")
                showToast("\u8d26\u53f7\u5df2\u5207\u6362\uff0c\u8bf7\u624b\u52a8\u5237\u65b0\u6216\u91cd\u65b0\u8fdb\u5165\u9605\u5fae")
            }
        }
    }

    private fun scheduleHostRestartCommand(componentName: String): Boolean =
        runCatching {
            val command = buildString {
                append("(sleep ")
                append(ACCOUNT_RESTART_COMMAND_DELAY_SECONDS)
                append("; am start -n ")
                append(shellQuote(componentName))
                append(" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER ")
                append("--activity-clear-task --activity-new-task >/dev/null 2>&1) &")
            }
            Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            true
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX schedule host restart command failed: ${it.stackTraceToString()}")
        }.getOrDefault(false)

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun scheduleHostRestart(alarmManager: AlarmManager?, pendingIntent: PendingIntent) {
        if (alarmManager == null) {
            pendingIntent.send()
            return
        }
        val elapsedTriggerAt = SystemClock.elapsedRealtime() + ACCOUNT_RESTART_DELAY_MS
        val wallTriggerAt = System.currentTimeMillis() + ACCOUNT_RESTART_DELAY_MS
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    elapsedTriggerAt,
                    pendingIntent,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    elapsedTriggerAt,
                    pendingIntent,
                )
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    elapsedTriggerAt,
                    pendingIntent,
                )
            }
        }.onFailure { exactError ->
            XposedBridge.log("$LOG_PREFIX exact host restart alarm failed: ${exactError.stackTraceToString()}")
            runCatching {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(wallTriggerAt, pendingIntent),
                    pendingIntent,
                )
            }.onFailure { alarmClockError ->
                XposedBridge.log("$LOG_PREFIX alarm clock host restart failed: ${alarmClockError.stackTraceToString()}")
                pendingIntent.send()
            }
        }
    }

    private fun buildAccountCredentialBundleFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "\u9605\u5fae\u8865\u5168\u8ba1\u5212_$timestamp.json"
    }

    private fun writeTextToDownloads(activity: Activity, displayName: String, text: String): Uri {
        return writeBytesToDownloads(activity, displayName, "application/json", text.toByteArray(Charsets.UTF_8))
    }

    private fun writeFileToDownloads(activity: Activity, displayName: String, mimeType: String, inputFile: File): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("\u65e0\u6cd5\u521b\u5efa\u4e0b\u8f7d\u6587\u4ef6")
            runCatching {
                resolver.openOutputStream(uri)?.use { output ->
                    inputFile.inputStream().buffered().use { input -> input.copyTo(output) }
                } ?: error("\u65e0\u6cd5\u5199\u5165\u4e0b\u8f7d\u6587\u4ef6")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                uri
            }.getOrElse { error ->
                resolver.delete(uri, null, null)
                throw error
            }
            return uri
        }
        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            error("\u65e0\u6cd5\u521b\u5efa\u4e0b\u8f7d\u76ee\u5f55")
        }
        val outputFile = File(downloadsDir, displayName)
        inputFile.inputStream().buffered().use { input ->
            outputFile.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(outputFile)
    }

    private fun writeBytesToDownloads(activity: Activity, displayName: String, mimeType: String, bytes: ByteArray): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("\u65e0\u6cd5\u521b\u5efa\u4e0b\u8f7d\u6587\u4ef6")
            runCatching {
                resolver.openOutputStream(uri)?.use { output -> output.write(bytes) }
                    ?: error("\u65e0\u6cd5\u5199\u5165\u4e0b\u8f7d\u6587\u4ef6")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                uri
            }.getOrElse { error ->
                resolver.delete(uri, null, null)
                throw error
            }
            return uri
        }
        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            error("\u65e0\u6cd5\u521b\u5efa\u4e0b\u8f7d\u76ee\u5f55")
        }
        val outputFile = File(downloadsDir, displayName)
        outputFile.outputStream().use { output -> output.write(bytes) }
        return Uri.fromFile(outputFile)
    }

    private fun showToast(message: String) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExportProgress(activity: Activity, title: String, message: String): ExportProgress {
        val progress = ExportProgress()
        activity.runOnUiThread {
            runCatching {
                val dark =
                    (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                val dp = activity.resources.displayMetrics.density
                val textColor = if (dark) Color.parseColor("#A3ACBA") else Color.parseColor("#8F96A3")
                val titleColor = if (dark) Color.parseColor("#E5E6EB") else Color.parseColor("#20242D")
                val cardColor = if (dark) Color.parseColor("#1C2128") else Color.parseColor("#F4F6FB")
                val messageView = TextView(activity).apply {
                    text = message
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(textColor)
                    includeFontPadding = false
                }
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding((26 * dp).toInt(), (24 * dp).toInt(), (26 * dp).toInt(), (22 * dp).toInt())
                    background = roundedRect(cardColor, 22 * dp)
                    addView(
                        ProgressBar(activity).apply { isIndeterminate = true },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = (16 * dp).toInt() },
                    )
                    addView(
                        TextView(activity).apply {
                            text = title
                            textSize = 16f
                            gravity = Gravity.CENTER
                            setTextColor(titleColor)
                            includeFontPadding = false
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = (8 * dp).toInt() },
                    )
                    addView(
                        messageView,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }
                val content = FrameLayout(activity).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    addView(
                        card,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER,
                        ),
                    )
                }
                val dialog = Dialog(activity).apply {
                    setCancelable(false)
                    setContentView(content)
                }
                dialog.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        progress.handleBack(activity)
                        true
                    } else {
                        false
                    }
                }
                dialog.window?.let { window ->
                    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    window.setDimAmount(0.18f)
                    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }
                dialog.show()
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                progress.attach(dialog, messageView)
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX failed to show export progress: ${it.stackTraceToString()}")
            }
        }
        return progress
    }

    private fun roundedRect(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }

    private class ExportProgress {
        @Volatile private var dialog: Dialog? = null
        @Volatile private var messageView: TextView? = null
        @Volatile private var pendingMessage: String? = null
        @Volatile private var canceled: Boolean = false
        @Volatile private var backWarnedAtMs: Long = 0L
        private val mainHandler = Handler(Looper.getMainLooper())
        val isCancelled: Boolean
            get() = canceled

        fun attach(dialog: Dialog, messageView: TextView) {
            this.dialog = dialog
            this.messageView = messageView
            pendingMessage?.let {
                messageView.text = it
                pendingMessage = null
            }
        }

        fun handleBack(activity: Activity) {
            val now = System.currentTimeMillis()
            if (now - backWarnedAtMs <= EXPORT_CANCEL_BACK_WINDOW_MS) {
                canceled = true
                Toast.makeText(activity, "\u5df2\u53d6\u6d88\u5bfc\u51fa", Toast.LENGTH_SHORT).show()
                dismiss()
                return
            }
            backWarnedAtMs = now
            Toast.makeText(activity, "\u518d\u6b21\u8fd4\u56de\u53d6\u6d88\u5bfc\u51fa", Toast.LENGTH_SHORT).show()
        }

        fun update(message: String) {
            if (canceled) return
            val view = messageView
            if (view == null) {
                pendingMessage = message
                return
            }
            mainHandler.post {
                view.text = message
            }
        }

        fun dismiss() {
            mainHandler.post {
                runCatching {
                    dialog?.takeIf { it.isShowing }?.dismiss()
                }
                dialog = null
                messageView = null
                pendingMessage = null
            }
        }

        companion object {
            private const val EXPORT_CANCEL_BACK_WINDOW_MS = 2_500L
        }
    }

    private fun renderFontSettingsContent(innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("FontSettingsList", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            fontLibraryVersionValue()
            val config = settings.fontSettings()
            val rows = listOf(
                ActionRow(
                    key = "font_global_family",
                    title = "\u5168\u5c40\u5b57\u4f53",
                    subtitle = fontSelectionSummary(config.globalFamily, "\u8ddf\u968f\u9605\u5fae"),
                    onClick = { openNestedInjectedRoute(InjectedRoute.FontPicker(FontPickerTarget.Global)) },
                ),
                ActionRow(
                    key = "font_song_mapping",
                    title = "\u5b8b\u4f53\u6620\u5c04",
                    subtitle = fontSelectionSummary(config.songMapping, "\u4e0d\u6620\u5c04"),
                    onClick = { openNestedInjectedRoute(InjectedRoute.FontPicker(FontPickerTarget.SongMapping)) },
                ),
                ActionRow(
                    key = "font_kai_mapping",
                    title = "\u6977\u4f53\u6620\u5c04",
                    subtitle = fontSelectionSummary(config.kaiMapping, "\u4e0d\u6620\u5c04"),
                    onClick = { openNestedInjectedRoute(InjectedRoute.FontPicker(FontPickerTarget.KaiMapping)) },
                ),
                ActionRow(
                    key = "font_library",
                    title = "\u5b57\u4f53\u5e93",
                    subtitle = "${listFontFiles().size} \u4e2a\u5b57\u4f53",
                    onClick = { openNestedInjectedRoute(InjectedRoute.FontLibrary) },
                ),
            )
            addLazyItem(lazyListScope, FONT_SETTINGS_CONTENT_ITEM_KEY) { itemComposer ->
                renderHostActionCard(rows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun renderFontPickerContent(target: FontPickerTarget, innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("FontPickerList${target.name}", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            fontLibraryVersionValue()
            val current = currentFontSelection(target)
            val rows = buildList {
                add(
                    ActionRow(
                        key = "font_${target.name}_clear",
                        title = target.clearTitle,
                        subtitle = target.clearSubtitle,
                        trailing = if (current.isBlank()) "\u5f53\u524d" else null,
                        onClick = {
                            setFontSelection(target, "")
                            navigateBackFromInjectedRoute()
                        },
                    ),
                )
                builtinFontSelectionsFor(target).forEach { option ->
                    add(
                        ActionRow(
                            key = "font_${target.name}_${option.value}",
                            title = option.title,
                            subtitle = option.subtitle,
                            trailing = if (sameFontSelection(current, option.value)) "\u5f53\u524d" else null,
                            titleFontSelection = option.value,
                            onClick = {
                                setFontSelection(target, option.value)
                                navigateBackFromInjectedRoute()
                            },
                        ),
                    )
                }
                val files = listFontFiles()
                files.forEach { file ->
                    val selection = file.absolutePath
                    add(
                        ActionRow(
                            key = "font_${target.name}_${selection.hashCode()}",
                            title = displayFontName(selection),
                            subtitle = file.name,
                            trailing = if (sameFontSelection(current, selection)) "\u5f53\u524d" else null,
                            titleFontSelection = selection,
                            onClick = {
                                setFontSelection(target, selection)
                                navigateBackFromInjectedRoute()
                            },
                        ),
                    )
                }
                if (files.isEmpty()) {
                    add(
                        ActionRow(
                            key = "font_${target.name}_empty",
                            title = "\u6682\u65e0\u5b57\u4f53",
                            subtitle = "\u8bf7\u5148\u5728\u5b57\u4f53\u5e93\u6dfb\u52a0\u5b57\u4f53",
                            onClick = { openNestedInjectedRoute(InjectedRoute.FontLibrary) },
                        ),
                    )
                }
            }
            addLazyItem(lazyListScope, FONT_PICKER_CONTENT_ITEM_KEY) { itemComposer ->
                renderHostActionCard(rows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun renderFontLibraryContent(innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("FontLibraryList", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            fontLibraryVersionValue()
            val pendingDelete = pendingDeleteFontSelection()
            val files = listFontFiles()
            val rows = buildList {
                add(
                    ActionRow(
                        key = "font_library_add",
                        title = "\u6dfb\u52a0\u5b57\u4f53",
                        subtitle = "\u9009\u62e9 ttf/otf \u5b57\u4f53\u6587\u4ef6",
                        onClick = { openFontDocumentPicker() },
                    ),
                )
                builtinFontSelections().forEach { option ->
                    add(
                        ActionRow(
                            key = "font_library_builtin_${option.value}",
                            title = option.title,
                            subtitle = option.subtitle,
                            titleFontSelection = option.value,
                        ),
                    )
                }
                if (files.isEmpty()) {
                    add(
                        ActionRow(
                            key = "font_library_empty",
                            title = "\u6682\u65e0\u5b57\u4f53",
                            subtitle = "\u5b57\u4f53\u5e93\u4e0e\u9605\u8bfb\u6392\u7248\u4e92\u901a",
                        ),
                    )
                } else {
                    files.forEach { file ->
                        val selection = file.absolutePath
                        val isPendingDelete = sameFontSelection(pendingDelete, selection)
                        add(
                            ActionRow(
                                key = "font_library_${selection.hashCode()}",
                                title = displayFontName(selection),
                                subtitle = if (isPendingDelete) "\u518d\u6b21\u70b9\u51fb\u786e\u8ba4\u79fb\u9664" else "\u70b9\u51fb\u79fb\u9664",
                                trailing = if (isPendingDelete) "\u79fb\u9664" else null,
                                titleFontSelection = selection,
                                onClick = {
                                    if (isPendingDelete) {
                                        deleteFont(file)
                                    } else {
                                        setPendingDeleteFontSelection(selection)
                                    }
                                },
                            ),
                        )
                    }
                }
            }
            addLazyItem(lazyListScope, FONT_LIBRARY_CONTENT_ITEM_KEY) { itemComposer ->
                renderHostActionCard(rows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun renderOnlineCompletionSettingsContent(innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("OnlineCompletionList", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            onlineSourceVersionValue()
            val sources = listOnlineSources()
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
                                onClick = { confirmOrRemoveOnlineSource(source) },
                                onLongClick = { armOnlineSourceRemoval(source) },
                                trailingContent = { itemComposer ->
                                    renderOnlineSourceSwitch(source, itemComposer)
                                },
                            ),
                        )
                    }
                }
            }
            addLazyItem(lazyListScope, ONLINE_COMPLETION_CONTENT_ITEM_KEY) { itemComposer ->
                renderHostActionCard(rows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun renderAiConfigSettingsContent(innerPaddings: Any, composer: Any) {
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

    private fun renderDictionarySettingsContent(innerPaddings: Any, composer: Any) {
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

    private fun renderDictionaryApiPickerContent(innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("DictionaryApiPickerList", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            aiApiVersionValue()
            val context = activityProvider()?.applicationContext
            val dictionarySettings = AiApiStore.dictionarySettings(context)
            val configs = listAiApiConfigs()
            val rows = buildList {
                add(
                    ActionRow(
                        key = "dictionary_api_follow",
                        title = "\u8ddf\u968f API \u914d\u7f6e",
                        subtitle = "\u4f7f\u7528 API \u914d\u7f6e\u9875\u4e2d\u542f\u7528\u7684 API",
                        trailing = if (dictionarySettings.apiId.isBlank()) "\u5f53\u524d" else null,
                        onClick = {
                            AiApiStore.setDictionaryApiId(context, "")
                            bumpAiApiVersion()
                            navigateBackFromInjectedRoute()
                        },
                    ),
                )
                if (configs.isEmpty()) {
                    add(
                        ActionRow(
                            key = "dictionary_api_empty",
                            title = "\u6682\u65e0 API",
                            subtitle = "\u8bf7\u5148\u5728 API \u914d\u7f6e\u9875\u6dfb\u52a0 API",
                        ),
                    )
                } else {
                    configs.forEach { config ->
                        add(
                            ActionRow(
                                key = "dictionary_api_${config.id}",
                                title = config.displayName.compactOnlineSourceLine(),
                                subtitle = aiApiSubtitle(config),
                                trailing = if (dictionarySettings.apiId == config.id) "\u5f53\u524d" else null,
                                onClick = {
                                    AiApiStore.setDictionaryApiId(context, config.id)
                                    bumpAiApiVersion()
                                    navigateBackFromInjectedRoute()
                                },
                            ),
                        )
                    }
                }
            }
            addLazyItem(lazyListScope, DICTIONARY_API_PICKER_CONTENT_ITEM_KEY) { itemComposer ->
                renderHostActionCard(rows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun renderDictionaryPresetPickerContent(innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("DictionaryPresetPickerList", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            aiApiVersionValue()
            val context = activityProvider()?.applicationContext
            val dictionarySettings = AiApiStore.dictionarySettings(context)
            val rows = buildList {
                add(
                    ActionRow(
                        key = "dictionary_preset_add",
                        title = "\u6dfb\u52a0\u9884\u8bbe",
                        subtitle = "\u586b\u5199\u9884\u8bbe\u540d\u79f0\u548c\u63d0\u793a\u8bcd\u5185\u5bb9",
                        onClick = { openDictionaryPresetDialog() },
                    ),
                )
                AiApiStore.dictionaryPresets(context).forEach { preset ->
                    add(
                        ActionRow(
                            key = "dictionary_preset_${preset.id}",
                            title = preset.name.compactOnlineSourceLine(),
                            subtitle = presetPromptPreview(preset.prompt),
                            singleLineSubtitle = true,
                            trailing = if (dictionarySettings.presetId == preset.id) "\u5f53\u524d" else null,
                            onClick = {
                                AiApiStore.setDictionaryPresetId(context, preset.id)
                                bumpAiApiVersion()
                                navigateBackFromInjectedRoute()
                            },
                            onLongClick = if (preset.builtIn) null else {
                                { openDictionaryPresetDialog(preset) }
                            },
                        ),
                    )
                }
            }
            addLazyItem(lazyListScope, DICTIONARY_PRESET_PICKER_CONTENT_ITEM_KEY) { itemComposer ->
                renderHostActionCard(rows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun renderImageSettingsContent(innerPaddings: Any, composer: Any) {
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

    private fun renderImageApiPickerContent(innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("ImageApiPickerList", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            aiApiVersionValue()
            val context = activityProvider()?.applicationContext
            val imageSettings = AiApiStore.imageSettings(context)
            val configs = listAiApiConfigs()
            val rows = buildList {
                add(
                    ActionRow(
                        key = "image_api_follow",
                        title = "\u8ddf\u968f API \u914d\u7f6e",
                        subtitle = "\u4f7f\u7528 API \u914d\u7f6e\u9875\u4e2d\u542f\u7528\u7684 API",
                        trailing = if (imageSettings.apiId.isBlank()) "\u5f53\u524d" else null,
                        onClick = {
                            AiApiStore.setImageApiId(context, "")
                            bumpAiApiVersion()
                            navigateBackFromInjectedRoute()
                        },
                    ),
                )
                if (configs.isEmpty()) {
                    add(
                        ActionRow(
                            key = "image_api_empty",
                            title = "\u6682\u65e0 API",
                            subtitle = "\u8bf7\u5148\u5728 API \u914d\u7f6e\u9875\u6dfb\u52a0 API",
                        ),
                    )
                } else {
                    configs.forEach { config ->
                        add(
                            ActionRow(
                                key = "image_api_${config.id}",
                                title = config.displayName.compactOnlineSourceLine(),
                                subtitle = aiApiSubtitle(config),
                                trailing = if (imageSettings.apiId == config.id) "\u5f53\u524d" else null,
                                onClick = {
                                    AiApiStore.setImageApiId(context, config.id)
                                    bumpAiApiVersion()
                                    navigateBackFromInjectedRoute()
                                },
                            ),
                        )
                    }
                }
            }
            addLazyItem(lazyListScope, IMAGE_API_PICKER_CONTENT_ITEM_KEY) { itemComposer ->
                renderHostActionCard(rows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun renderImagePresetPickerContent(target: AiImagePresetTarget, innerPaddings: Any, composer: Any) {
        val listContent = functionProxy("ImagePresetPickerList${target.id}", FUNCTION1_CLASS) { args ->
            val lazyListScope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
            aiApiVersionValue()
            val context = activityProvider()?.applicationContext
            val imageSettings = AiApiStore.imageSettings(context)
            val selectedPresetId = imagePresetId(imageSettings, target)
            val rows = buildList {
                add(
                    ActionRow(
                        key = "image_${target.id}_preset_add",
                        title = "\u6dfb\u52a0\u9884\u8bbe",
                        subtitle = "\u586b\u5199\u9884\u8bbe\u540d\u79f0\u548c\u751f\u56fe\u63d0\u793a\u8bcd",
                        onClick = { openImagePresetDialog(target) },
                    ),
                )
                AiApiStore.imagePresets(context, target).forEach { preset ->
                    add(
                        ActionRow(
                            key = "image_${target.id}_preset_${preset.id}",
                            title = preset.name.compactOnlineSourceLine(),
                            subtitle = presetPromptPreview(preset.prompt),
                            singleLineSubtitle = true,
                            trailing = if (selectedPresetId == preset.id) "\u5f53\u524d" else null,
                            onClick = {
                                AiApiStore.setImagePresetId(context, target, preset.id)
                                bumpAiApiVersion()
                                navigateBackFromInjectedRoute()
                            },
                            onLongClick = if (preset.builtIn) null else {
                                { openImagePresetDialog(target, preset) }
                            },
                        ),
                    )
                }
            }
            addLazyItem(lazyListScope, IMAGE_PRESET_PICKER_CONTENT_ITEM_KEY) { itemComposer ->
                renderHostActionCard(rows, itemComposer)
            }
            targetUnit()
        }
        renderHostLazyColumn(innerPaddings, listContent, composer)
    }

    private fun dictionarySettingsSummary(): String {
        val preset = dictionaryPresetSelectionSummary()
        val api = dictionaryApiSelectionSummary()
        return "$preset / $api"
    }

    private fun dictionaryApiSelectionSummary(): String {
        val context = activityProvider()?.applicationContext
        val dictionarySettings = AiApiStore.dictionarySettings(context)
        val config = AiApiStore.dictionaryApi(context)
        return when {
            config == null -> "\u672a\u9009\u62e9 API"
            dictionarySettings.apiId.isBlank() -> "\u8ddf\u968f API \u914d\u7f6e\uff1a${config.displayName}"
            else -> config.displayName
        }
    }

    private fun dictionaryPresetSelectionSummary(): String {
        val context = activityProvider()?.applicationContext
        val dictionarySettings = AiApiStore.dictionarySettings(context)
        return AiApiStore.dictionaryPreset(context, dictionarySettings.presetId).name
    }

    private fun imageSettingsSummary(): String {
        val cover = imagePresetSelectionSummary(AiImagePresetTarget.Cover)
        val banner = imagePresetSelectionSummary(AiImagePresetTarget.Banner)
        val api = imageApiSelectionSummary()
        return "$cover / $banner / $api"
    }

    private fun imageApiSelectionSummary(): String {
        val context = activityProvider()?.applicationContext
        val imageSettings = AiApiStore.imageSettings(context)
        val config = AiApiStore.imageApi(context)
        return when {
            config == null -> "\u672a\u9009\u62e9 API"
            imageSettings.apiId.isBlank() -> "\u8ddf\u968f API \u914d\u7f6e\uff1a${config.displayName}"
            else -> config.displayName
        }
    }

    private fun imagePresetSelectionSummary(target: AiImagePresetTarget): String {
        val context = activityProvider()?.applicationContext
        val imageSettings = AiApiStore.imageSettings(context)
        return AiApiStore.imagePreset(context, target, imagePresetId(imageSettings, target)).name
    }

    private fun imagePresetId(settings: com.reamicro.fix.ai.AiImageSettings, target: AiImagePresetTarget): String =
        when (target) {
            AiImagePresetTarget.Cover -> settings.coverPresetId
            AiImagePresetTarget.Banner -> settings.bannerPresetId
        }

    private enum class SettingsDialogButtonRole {
        Primary,
        Neutral,
        Destructive,
    }

    private inner class SettingsDialogColors(context: Context) {
        private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val card: Int = if (dark) Color.rgb(30, 34, 40) else Color.WHITE
        val border: Int = if (dark) Color.rgb(62, 69, 78) else Color.rgb(226, 230, 236)
        val title: Int = if (dark) Color.WHITE else Color.rgb(29, 33, 39)
        val body: Int = if (dark) Color.rgb(190, 198, 208) else Color.rgb(86, 94, 106)
        val field: Int = if (dark) Color.rgb(39, 44, 51) else Color.rgb(246, 248, 251)
        val primary: Int = settingsThemeColor(context, android.R.attr.colorAccent, Color.rgb(45, 135, 120))
        val primarySoft: Int = if (dark) Color.rgb(36, 70, 64) else Color.rgb(230, 244, 241)
        val primaryText: Int = if (dark) Color.rgb(166, 224, 212) else primary
        val neutralSoft: Int = if (dark) Color.rgb(43, 48, 55) else Color.rgb(242, 244, 247)
        val neutralText: Int = if (dark) Color.rgb(218, 223, 230) else Color.rgb(74, 82, 94)
        val destructiveSoft: Int = if (dark) Color.rgb(82, 39, 42) else Color.rgb(253, 236, 236)
        val destructiveText: Int = if (dark) Color.rgb(255, 172, 172) else Color.rgb(214, 69, 69)
    }

    private fun settingsDialogCard(context: Context, colors: SettingsDialogColors): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                settingsDp(context, 20),
                settingsDp(context, 20),
                settingsDp(context, 20),
                settingsDp(context, 18),
            )
            background = settingsRoundedRect(colors.card, settingsDp(context, 22), colors.border)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    private fun settingsDialogTitle(
        context: Context,
        title: String,
        colors: SettingsDialogColors,
    ): TextView =
        TextView(context).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setTextColor(colors.title)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = settingsDp(context, 14) }
        }

    private fun settingsDialogInput(
        context: Context,
        hintText: String,
        singleLine: Boolean,
        colors: SettingsDialogColors,
    ): EditText =
        EditText(context).apply {
            hint = hintText
            textSize = 14f
            setSingleLine(singleLine)
            minHeight = settingsDp(context, 46)
            setTextColor(colors.title)
            setHintTextColor(colors.body)
            setPadding(
                settingsDp(context, 12),
                settingsDp(context, 8),
                settingsDp(context, 12),
                settingsDp(context, 8),
            )
            background = settingsRoundedRect(colors.field, settingsDp(context, 12), colors.border)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = settingsDp(context, 10) }
        }

    private fun settingsDialogStatus(
        context: Context,
        message: String,
        colors: SettingsDialogColors,
    ): TextView =
        TextView(context).apply {
            text = message
            textSize = 13f
            setTextColor(colors.body)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = settingsDp(context, 10) }
        }

    private fun settingsDialogActions(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = settingsDp(context, 4) }
        }

    private fun settingsDialogButton(
        context: Context,
        title: String,
        colors: SettingsDialogColors,
        role: SettingsDialogButtonRole = SettingsDialogButtonRole.Primary,
    ): TextView =
        TextView(context).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setSingleLine(true)
            minHeight = settingsDp(context, 44)
            setPadding(settingsDp(context, 8), settingsDp(context, 10), settingsDp(context, 8), settingsDp(context, 10))
            when (role) {
                SettingsDialogButtonRole.Primary -> {
                    setTextColor(colors.primaryText)
                    background = settingsRoundedRect(colors.primarySoft, settingsDp(context, 12), colors.border)
                }
                SettingsDialogButtonRole.Neutral -> {
                    setTextColor(colors.neutralText)
                    background = settingsRoundedRect(colors.neutralSoft, settingsDp(context, 12), colors.border)
                }
                SettingsDialogButtonRole.Destructive -> {
                    setTextColor(colors.destructiveText)
                    background = settingsRoundedRect(colors.destructiveSoft, settingsDp(context, 12), colors.border)
                }
            }
        }

    private fun settingsDialogButtonParams(context: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = settingsDp(context, 4)
            rightMargin = settingsDp(context, 4)
        }

    private fun settingsDialogButtonRow(context: Context, buttons: List<TextView>): LinearLayout =
        settingsDialogActions(context).apply {
            buttons.forEach { button ->
                addView(button, settingsDialogButtonParams(context))
            }
        }

    private fun settingsDialogHint(
        context: Context,
        message: String,
        colors: SettingsDialogColors,
    ): TextView =
        settingsDialogStatus(context, message, colors)

    private fun settingsDialogProgressParams(context: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = settingsDp(context, 10)
        }

    private fun settingsDialogScroll(context: Context, card: LinearLayout): ScrollView =
        ScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(card)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    private fun showSettingsDialog(dialog: Dialog, content: View, activity: Activity, widthRatio: Float = 0.9f) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0.46f)
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0.46f)
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setLayout(
                (activity.resources.displayMetrics.widthPixels * widthRatio).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun showAiModelSelectionDialog(
        activity: Activity,
        models: List<String>,
        colors: SettingsDialogColors,
        onSelected: (String) -> Unit,
    ) {
        val dialog = Dialog(activity)
        val card = settingsDialogCard(activity, colors)
        card.addView(settingsDialogTitle(activity, "\u9009\u62e9\u6a21\u578b", colors))
        models.forEach { model ->
            card.addView(
                settingsDialogChoiceRow(activity, model, colors) {
                    onSelected(model)
                    dialog.dismiss()
                },
            )
        }
        showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, 0.92f)
    }

    private fun settingsDialogChoiceRow(
        context: Context,
        title: String,
        colors: SettingsDialogColors,
        onClick: () -> Unit,
    ): TextView =
        TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(colors.title)
            setSingleLine(false)
            setPadding(
                settingsDp(context, 12),
                settingsDp(context, 10),
                settingsDp(context, 12),
                settingsDp(context, 10),
            )
            background = settingsRoundedRect(colors.field, settingsDp(context, 12), colors.border)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = settingsDp(context, 8) }
        }

    private fun addSettingsFontChoiceRows(
        activity: Activity,
        card: LinearLayout,
        colors: SettingsDialogColors,
        currentSelection: String,
        clearTitle: String,
        onSelected: (String) -> Unit,
    ): List<View> {
        val rows = mutableListOf<View>()
        fun addRow(selection: String, title: String, subtitle: String? = null) {
            val current = sameFontSelection(currentSelection, selection)
            val row = settingsDialogFontChoiceRow(
                context = activity,
                title = if (current) "$title  \u5f53\u524d" else title,
                subtitle = subtitle,
                selection = selection,
                colors = colors,
            ) {
                onSelected(selection)
            }
            rows.add(row)
            card.addView(row)
        }
        addRow("", clearTitle)
        builtinFontSelections().forEach { option ->
            addRow(option.value, option.title, option.subtitle)
        }
        val files = listFontFiles()
        files.forEach { file ->
            addRow(file.absolutePath, displayFontName(file.absolutePath), file.name)
        }
        if (files.isEmpty()) {
            val row = settingsDialogStatus(activity, "\u5b57\u4f53\u5e93\u6682\u65e0\u5b57\u4f53\uff0c\u53ef\u5148\u5728\u5b57\u4f53\u7ba1\u7406\u4e2d\u6dfb\u52a0\u3002", colors)
            rows.add(row)
            card.addView(row)
        }
        return rows
    }

    private fun openSettingsFontSelectionDialog(
        activity: Activity,
        title: String,
        currentSelection: String,
        clearTitle: String,
        onSelected: (String) -> Unit,
    ) {
        runCatching {
            val colors = SettingsDialogColors(activity)
            val dialog = Dialog(activity)
            val card = settingsDialogCard(activity, colors)
            fun addRow(selection: String, rowTitle: String, subtitle: String? = null) {
                val current = sameFontSelection(currentSelection, selection)
                card.addView(
                    settingsDialogFontChoiceRow(
                        context = activity,
                        title = if (current) "$rowTitle  \u5f53\u524d" else rowTitle,
                        subtitle = subtitle,
                        selection = selection,
                        colors = colors,
                    ) {
                        onSelected(selection)
                        dialog.dismiss()
                    },
                )
            }
            card.addView(settingsDialogTitle(activity, title, colors))
            addRow("", clearTitle)
            builtinFontSelections().forEach { option ->
                addRow(option.value, option.title, option.subtitle)
            }
            val files = listFontFiles()
            files.forEach { file ->
                addRow(file.absolutePath, displayFontName(file.absolutePath), file.name)
            }
            if (files.isEmpty()) {
                card.addView(settingsDialogHint(activity, "\u5b57\u4f53\u5e93\u6682\u65e0\u5b57\u4f53\uff0c\u53ef\u5148\u5728\u5b57\u4f53\u7ba1\u7406\u4e2d\u6dfb\u52a0\u3002", colors))
            }
            showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, 0.9f)
        }.onFailure {
            showToast("\u6253\u5f00\u5b57\u4f53\u9009\u62e9\u5931\u8d25")
            XposedBridge.log("$LOG_PREFIX open settings font selection failed: ${it.stackTraceToString()}")
        }
    }

    private fun settingsDialogFontChoiceRow(
        context: Context,
        title: String,
        subtitle: String?,
        selection: String,
        colors: SettingsDialogColors,
        onClick: () -> Unit,
    ): TextView =
        TextView(context).apply {
            text = if (subtitle.isNullOrBlank()) title else "$title\n$subtitle"
            textSize = 14f
            setTextColor(colors.title)
            typeface = androidTypefaceForFontSelection(selection) ?: Typeface.DEFAULT
            setSingleLine(false)
            setPadding(
                settingsDp(context, 12),
                settingsDp(context, 10),
                settingsDp(context, 12),
                settingsDp(context, 10),
            )
            background = settingsRoundedRect(colors.field, settingsDp(context, 12), colors.border)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = settingsDp(context, 8) }
        }

    private fun androidTypefaceForFontSelection(selection: String): Typeface? {
        if (selection.isBlank()) return null
        if (selection == FAMILY_SYSTEM) return Typeface.DEFAULT
        val file = File(selection)
        if (!file.isFile || !isFontFileName(file.name)) return null
        return runCatching { Typeface.createFromFile(file) }.getOrNull()
    }

    private fun settingsRoundedRect(fill: Int, radiusPx: Int, stroke: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radiusPx.toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(1, stroke)
        }

    private fun settingsDp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun settingsThemeColor(context: Context, attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) value.data else fallback
    }

    private fun openDictionaryPresetDialog() {
        openDictionaryPresetDialog(existing = null)
    }

    private fun openDictionaryPresetDialog(existing: AiDictionaryPreset?) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            runCatching {
                val colors = SettingsDialogColors(activity)
                val dialog = Dialog(activity)
                val card = settingsDialogCard(activity, colors)
                val nameInput = settingsDialogInput(activity, "\u9884\u8bbe\u540d\u79f0", singleLine = true, colors = colors).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
                    setText(existing?.name.orEmpty())
                }
                val promptInput = settingsDialogInput(
                    activity,
                    "\u63d0\u793a\u8bcd\u5185\u5bb9\uff0c\u53ef\u7528 {{text}} \u4ee3\u8868\u9009\u4e2d\u6587\u672c",
                    singleLine = false,
                    colors = colors,
                ).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    minLines = 5
                    maxLines = 8
                    gravity = Gravity.TOP or Gravity.START
                    setText(existing?.prompt.orEmpty())
                }
                val finishButton = settingsDialogButton(activity, "\u5b8c\u6210", colors)
                val deleteButton = existing?.takeUnless { it.builtIn }?.let {
                    settingsDialogButton(activity, "\u5220\u9664", colors, SettingsDialogButtonRole.Destructive)
                }
                val cancelButton = settingsDialogButton(activity, "\u53d6\u6d88", colors, SettingsDialogButtonRole.Neutral)
                val actionRow = settingsDialogActions(activity).apply {
                    addView(finishButton, settingsDialogButtonParams(activity))
                    if (deleteButton != null) addView(deleteButton, settingsDialogButtonParams(activity))
                    addView(cancelButton, settingsDialogButtonParams(activity))
                }
                card.addView(settingsDialogTitle(activity, "\u8bcd\u5178\u9884\u8bbe", colors))
                card.addView(nameInput)
                card.addView(promptInput)
                card.addView(actionRow)

                fun hasAllValues(): Boolean =
                    nameInput.text?.toString()?.trim()?.isNotBlank() == true &&
                        promptInput.text?.toString()?.trim()?.isNotBlank() == true

                fun refreshButtons() {
                    val canFinish = hasAllValues()
                    finishButton.isEnabled = canFinish
                    finishButton.alpha = if (canFinish) 1f else 0.38f
                }

                val watcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        refreshButtons()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                }
                nameInput.addTextChangedListener(watcher)
                promptInput.addTextChangedListener(watcher)

                deleteButton?.setOnClickListener {
                    val target = existing ?: return@setOnClickListener
                    if (AiApiStore.removeDictionaryPreset(activity.applicationContext, target.id)) {
                        bumpAiApiVersion()
                        showToast("\u5df2\u5220\u9664\u9884\u8bbe\uff1a${target.name}")
                    }
                    dialog.dismiss()
                }
                finishButton.setOnClickListener {
                    val name = nameInput.text?.toString().orEmpty()
                    val prompt = promptInput.text?.toString().orEmpty()
                    runCatching {
                        if (existing == null) {
                            AiApiStore.addDictionaryPreset(activity.applicationContext, name, prompt)
                        } else {
                            AiApiStore.updateDictionaryPreset(activity.applicationContext, existing.id, name, prompt)
                        }
                    }.onSuccess { preset ->
                        bumpAiApiVersion()
                        showToast(
                            if (existing == null) {
                                "\u5df2\u6dfb\u52a0\u9884\u8bbe\uff1a${preset.name}"
                            } else {
                                "\u5df2\u66f4\u65b0\u9884\u8bbe\uff1a${preset.name}"
                            },
                        )
                        dialog.dismiss()
                    }.onFailure { error ->
                        Toast.makeText(activity, error.message ?: "\u65e0\u6cd5\u4fdd\u5b58\u9884\u8bbe", Toast.LENGTH_SHORT).show()
                    }
                }
                cancelButton.setOnClickListener { dialog.dismiss() }
                refreshButtons()
                showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX failed to open dictionary preset dialog: ${it.stackTraceToString()}")
                showToast("\u65e0\u6cd5\u6253\u5f00\u8bcd\u5178\u9884\u8bbe")
            }
        }
    }

    private fun openImagePresetDialog(target: AiImagePresetTarget) {
        openImagePresetDialog(target, existing = null)
    }

    private fun openImagePresetDialog(target: AiImagePresetTarget, existing: AiImagePreset?) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            runCatching {
                val actualTarget = existing?.target ?: target
                val colors = SettingsDialogColors(activity)
                val dialog = Dialog(activity)
                val card = settingsDialogCard(activity, colors)
                val nameInput = settingsDialogInput(activity, "\u9884\u8bbe\u540d\u79f0", singleLine = true, colors = colors).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
                    setText(existing?.name.orEmpty())
                }
                val promptInput = settingsDialogInput(
                    activity,
                    "\u751f\u56fe\u63d0\u793a\u8bcd\uff0c\u53ef\u7528 {{text}} \u4ee3\u8868\u751f\u6210\u4e3b\u9898",
                    singleLine = false,
                    colors = colors,
                ).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    minLines = 5
                    maxLines = 8
                    gravity = Gravity.TOP or Gravity.START
                    setText(existing?.prompt.orEmpty())
                }
                val finishButton = settingsDialogButton(activity, "\u5b8c\u6210", colors)
                val deleteButton = existing?.takeUnless { it.builtIn }?.let {
                    settingsDialogButton(activity, "\u5220\u9664", colors, SettingsDialogButtonRole.Destructive)
                }
                val cancelButton = settingsDialogButton(activity, "\u53d6\u6d88", colors, SettingsDialogButtonRole.Neutral)
                val actionRow = settingsDialogActions(activity).apply {
                    addView(finishButton, settingsDialogButtonParams(activity))
                    if (deleteButton != null) addView(deleteButton, settingsDialogButtonParams(activity))
                    addView(cancelButton, settingsDialogButtonParams(activity))
                }
                card.addView(settingsDialogTitle(activity, actualTarget.title, colors))
                card.addView(nameInput)
                card.addView(promptInput)
                card.addView(actionRow)

                fun hasAllValues(): Boolean =
                    nameInput.text?.toString()?.trim()?.isNotBlank() == true &&
                        promptInput.text?.toString()?.trim()?.isNotBlank() == true

                fun refreshButtons() {
                    val canFinish = hasAllValues()
                    finishButton.isEnabled = canFinish
                    finishButton.alpha = if (canFinish) 1f else 0.38f
                }

                val watcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        refreshButtons()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                }
                nameInput.addTextChangedListener(watcher)
                promptInput.addTextChangedListener(watcher)

                deleteButton?.setOnClickListener {
                    val targetPreset = existing ?: return@setOnClickListener
                    if (AiApiStore.removeImagePreset(activity.applicationContext, targetPreset.id)) {
                        bumpAiApiVersion()
                        showToast("\u5df2\u5220\u9664\u9884\u8bbe\uff1a${targetPreset.name}")
                    }
                    dialog.dismiss()
                }
                finishButton.setOnClickListener {
                    val name = nameInput.text?.toString().orEmpty()
                    val prompt = promptInput.text?.toString().orEmpty()
                    runCatching {
                        if (existing == null) {
                            AiApiStore.addImagePreset(activity.applicationContext, actualTarget, name, prompt)
                        } else {
                            AiApiStore.updateImagePreset(activity.applicationContext, existing.id, name, prompt)
                        }
                    }.onSuccess { preset ->
                        bumpAiApiVersion()
                        showToast(
                            if (existing == null) {
                                "\u5df2\u6dfb\u52a0\u9884\u8bbe\uff1a${preset.name}"
                            } else {
                                "\u5df2\u66f4\u65b0\u9884\u8bbe\uff1a${preset.name}"
                            },
                        )
                        dialog.dismiss()
                    }.onFailure { error ->
                        Toast.makeText(activity, error.message ?: "\u65e0\u6cd5\u4fdd\u5b58\u9884\u8bbe", Toast.LENGTH_SHORT).show()
                    }
                }
                cancelButton.setOnClickListener { dialog.dismiss() }
                refreshButtons()
                showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX failed to open image preset dialog: ${it.stackTraceToString()}")
                showToast("\u65e0\u6cd5\u6253\u5f00\u751f\u56fe\u9884\u8bbe")
            }
        }
    }

    private fun listAiApiConfigs(): List<AiApiConfig> =
        AiApiStore.list(activityProvider()?.applicationContext)

    private fun aiApiSubtitle(config: AiApiConfig): String =
        config.model

    private fun presetPromptPreview(prompt: String): String =
        prompt.compactOnlineSourceLine().let { line ->
            if (line.length > PRESET_PROMPT_PREVIEW_MAX_CHARS) {
                line.take(PRESET_PROMPT_PREVIEW_MAX_CHARS).trimEnd() + "..."
            } else {
                line
            }
        }

    private data class AiApiDialogValues(
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    private fun renderAiApiSwitch(config: AiApiConfig, composer: Any) {
        val latest = listAiApiConfigs().firstOrNull { it.id == config.id } ?: config
        val targetChecked = latest.enabled
        val state = rememberBooleanState(composer, targetChecked)
        fun updateChecked(value: Boolean) {
            state.javaClass.methods.firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
                ?.invoke(state, value)
        }
        val rememberedChecked = state.method0("getValue") as? Boolean ?: targetChecked
        val checked = if (rememberedChecked != targetChecked) {
            updateChecked(targetChecked)
            targetChecked
        } else {
            rememberedChecked
        }
        val onCheckedChange = functionProxy("AiApiSwitch${config.id}", FUNCTION1_CLASS) { args ->
            val enabled = args?.getOrNull(0) as? Boolean ?: return@functionProxy targetUnit()
            if (AiApiStore.setEnabled(activityProvider()?.applicationContext, config.id, enabled)) {
                updateChecked(enabled)
                bumpAiApiVersion()
            } else {
                updateChecked(targetChecked)
            }
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

    private fun openAiApiConfigDialog() {
        openAiApiConfigDialog(existing = null)
    }

    private fun openAiApiConfigDialog(existing: AiApiConfig?) {
        val activity = activityProvider() ?: return
        activity.runOnUiThread {
            runCatching {
                val colors = SettingsDialogColors(activity)
                val dialog = Dialog(activity)
                val card = settingsDialogCard(activity, colors)
                val nameInput = settingsDialogInput(activity, "\u540d\u79f0\uff08\u9ed8\u8ba4\u6a21\u578b\u540d\uff09", singleLine = true, colors = colors).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
                    setText(existing?.name.orEmpty())
                }
                val baseUrlInput = settingsDialogInput(activity, "base_url", singleLine = true, colors = colors).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setText(existing?.baseUrl.orEmpty())
                }
                val apiKeyInput = settingsDialogInput(activity, "api_key", singleLine = true, colors = colors).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    typeface = Typeface.DEFAULT
                    setHintTextColor(colors.body)
                    setText(existing?.apiKey.orEmpty())
                }
                val modelInput = settingsDialogInput(activity, "model", singleLine = true, colors = colors).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
                    setText(existing?.model.orEmpty())
                }
                val status = settingsDialogStatus(
                    activity,
                    if (existing == null) {
                        "\u586b\u5b8c\u540e\u5148\u70b9\u51fb\u6d4b\u8bd5\uff0c\u957f\u6309\u5b8c\u6210\u53ef\u76f4\u63a5\u4fdd\u5b58"
                    } else {
                        "\u5f53\u524d\u914d\u7f6e\u53ef\u76f4\u63a5\u5b8c\u6210\uff0c\u4fee\u6539\u540e\u9700\u8981\u91cd\u65b0\u6d4b\u8bd5\uff1b\u957f\u6309\u5b8c\u6210\u53ef\u76f4\u63a5\u4fdd\u5b58"
                    },
                    colors,
                )
                val progress = ProgressBar(activity).apply {
                    visibility = View.GONE
                    isIndeterminate = true
                }
                val finishButton = settingsDialogButton(activity, "\u5b8c\u6210", colors)
                val deleteButton = existing?.let {
                    settingsDialogButton(activity, "\u5220\u9664", colors, SettingsDialogButtonRole.Destructive)
                }
                val cancelButton = settingsDialogButton(activity, "\u53d6\u6d88", colors, SettingsDialogButtonRole.Neutral)
                val testButton = settingsDialogButton(activity, "\u6d4b\u8bd5", colors)
                val fetchModelsButton = settingsDialogButton(activity, "\u83b7\u53d6\u6a21\u578b", colors, SettingsDialogButtonRole.Neutral)
                val fetchRow = settingsDialogActions(activity).apply {
                    addView(fetchModelsButton, settingsDialogButtonParams(activity))
                }
                val actionRow = settingsDialogActions(activity).apply {
                    addView(finishButton, settingsDialogButtonParams(activity))
                    if (deleteButton != null) addView(deleteButton, settingsDialogButtonParams(activity))
                    addView(cancelButton, settingsDialogButtonParams(activity))
                    addView(testButton, settingsDialogButtonParams(activity))
                }
                card.addView(settingsDialogTitle(activity, "API \u914d\u7f6e", colors))
                card.addView(nameInput)
                card.addView(baseUrlInput)
                card.addView(apiKeyInput)
                card.addView(modelInput)
                card.addView(fetchRow)
                card.addView(status)
                card.addView(progress, settingsDialogProgressParams(activity))
                card.addView(actionRow)

                var testedBaseUrl = existing?.baseUrl.orEmpty()
                var testedApiKey = existing?.apiKey.orEmpty()
                var testedModel = existing?.model.orEmpty()
                var testing = false
                var fetchingModels = false

                deleteButton?.setOnClickListener {
                    val target = existing ?: return@setOnClickListener
                    if (AiApiStore.remove(activity.applicationContext, target.id)) {
                        bumpAiApiVersion()
                        showToast("\u5df2\u5220\u9664 API\uff1a${target.displayName}")
                    }
                    dialog.dismiss()
                }

                fun values(): AiApiDialogValues =
                    AiApiDialogValues(
                        nameInput.text?.toString().orEmpty().trim(),
                        baseUrlInput.text?.toString().orEmpty().trim(),
                        apiKeyInput.text?.toString().orEmpty().trim(),
                        modelInput.text?.toString().orEmpty().trim(),
                    )

                fun hasBaseAndKey(): Boolean {
                    val values = values()
                    return values.baseUrl.isNotBlank() && values.apiKey.isNotBlank()
                }

                fun hasAllValues(): Boolean {
                    val values = values()
                    return values.baseUrl.isNotBlank() && values.apiKey.isNotBlank() && values.model.isNotBlank()
                }

                fun testPassedForCurrentValues(): Boolean {
                    val values = values()
                    return values.baseUrl == testedBaseUrl && values.apiKey == testedApiKey && values.model == testedModel
                }

                fun refreshButtons() {
                    val busy = testing || fetchingModels
                    val canFetchModels = hasBaseAndKey() && !busy
                    val canTest = hasAllValues() && !busy
                    val canFinish = hasAllValues() && !busy
                    fetchModelsButton.isEnabled = canFetchModels
                    fetchModelsButton.alpha = if (canFetchModels) 1f else 0.38f
                    testButton.isEnabled = canTest
                    testButton.alpha = if (canTest) 1f else 0.38f
                    finishButton.isEnabled = canFinish
                    finishButton.alpha = when {
                        !canFinish -> 0.38f
                        testPassedForCurrentValues() -> 1f
                        else -> 0.72f
                    }
                    deleteButton?.isEnabled = !busy
                    deleteButton?.alpha = if (busy) 0.38f else 1f
                    cancelButton.isEnabled = !busy
                    cancelButton.alpha = if (busy) 0.38f else 1f
                }

                val watcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        if (!testPassedForCurrentValues()) status.text = "\u914d\u7f6e\u5df2\u53d8\u66f4\uff0c\u9700\u8981\u91cd\u65b0\u6d4b\u8bd5"
                        refreshButtons()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                }
                baseUrlInput.addTextChangedListener(watcher)
                apiKeyInput.addTextChangedListener(watcher)
                modelInput.addTextChangedListener(watcher)

                fetchModelsButton.setOnClickListener {
                    val values = values()
                    if (values.baseUrl.isBlank() || values.apiKey.isBlank()) {
                        Toast.makeText(activity, "\u8bf7\u5148\u586b\u5199 base_url \u548c api_key", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    fetchingModels = true
                    status.text = "\u6b63\u5728\u83b7\u53d6\u6a21\u578b\u5217\u8868..."
                    progress.visibility = View.VISIBLE
                    refreshButtons()
                    Thread({
                        val result = AiApiStore.fetchModels(values.baseUrl, values.apiKey)
                        activity.runOnUiThread {
                            fetchingModels = false
                            progress.visibility = View.GONE
                            status.text = result.message
                            refreshButtons()
                            if (result.success && result.models.isNotEmpty()) {
                                showAiModelSelectionDialog(activity, result.models, colors) { model ->
                                    modelInput.setText(model)
                                    modelInput.setSelection(modelInput.text?.length ?: 0)
                                    status.text = "\u5df2\u9009\u62e9\u6a21\u578b\uff0c\u8bf7\u6d4b\u8bd5\u8fde\u63a5"
                                    refreshButtons()
                                }
                            }
                        }
                    }, "ReaMicroAiModelFetch").start()
                }

                testButton.setOnClickListener {
                    val values = values()
                    if (values.baseUrl.isBlank() || values.apiKey.isBlank() || values.model.isBlank()) {
                        Toast.makeText(activity, "\u8bf7\u5148\u586b\u5b8c\u5168\u90e8\u5b57\u6bb5", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    testing = true
                    status.text = "\u6b63\u5728\u6d4b\u8bd5\u8fde\u63a5..."
                    progress.visibility = View.VISIBLE
                    refreshButtons()
                    Thread({
                        val result = AiApiStore.test(values.baseUrl, values.apiKey, values.model)
                        activity.runOnUiThread {
                            testing = false
                            progress.visibility = View.GONE
                            status.text = result.message
                            if (result.success) {
                                testedBaseUrl = values.baseUrl
                                testedApiKey = values.apiKey
                                testedModel = values.model
                            } else {
                                testedBaseUrl = ""
                                testedApiKey = ""
                                testedModel = ""
                            }
                            refreshButtons()
                        }
                    }, "ReaMicroAiApiTest").start()
                }

                fun saveApiConfigDirect() {
                    val values = values()
                    if (values.baseUrl.isBlank() || values.apiKey.isBlank() || values.model.isBlank()) {
                        Toast.makeText(activity, "\u8bf7\u5148\u586b\u5b8c\u5168\u90e8\u5b57\u6bb5", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val config = if (existing == null) {
                        AiApiStore.add(activity.applicationContext, values.baseUrl, values.apiKey, values.model, values.name)
                    } else {
                        AiApiStore.update(activity.applicationContext, existing.id, values.baseUrl, values.apiKey, values.model, values.name)
                    }
                    bumpAiApiVersion()
                    showToast(
                        if (existing == null) {
                            "\u5df2\u6dfb\u52a0 API\uff1a${config.displayName}"
                        } else {
                            "\u5df2\u66f4\u65b0 API\uff1a${config.displayName}"
                        },
                    )
                    dialog.dismiss()
                }

                finishButton.setOnClickListener {
                    if (!testPassedForCurrentValues()) {
                        Toast.makeText(activity, "\u8bf7\u5148\u6d4b\u8bd5\u901a\u8fc7\uff0c\u6216\u957f\u6309\u5b8c\u6210\u76f4\u63a5\u4fdd\u5b58", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    saveApiConfigDirect()
                }
                finishButton.setOnLongClickListener {
                    saveApiConfigDirect()
                    true
                }
                cancelButton.setOnClickListener { dialog.dismiss() }
                refreshButtons()
                showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX failed to open AI API config dialog: ${it.stackTraceToString()}")
                showToast("\u65e0\u6cd5\u6253\u5f00 API \u914d\u7f6e")
            }
        }
    }

    private fun onlineSourceSubtitle(source: OnlineSourceEntry): String {
        val tags = mutableListOf<String>()
        when {
            OnlineSourceAuth.hasSavedLogin(activityProvider()?.applicationContext, source) -> tags += "已登录"
            source.webLoginUrl.isNotBlank() || OnlineSourceAuth.supportsCredentialLogin(source) -> {
                tags += "可登录"
                tags += "可跳过"
            }
            source.hasLoginConfig -> {
                tags += "有登录配置"
                tags += "可跳过"
            }
            else -> tags += "免登录"
        }
        val rate = source.concurrentRate
            .compactOnlineSourceLine()
            .takeIf { it.isNotBlank() && it != "0" }
            ?.let { "频控 $it" }
        rate?.let { tags += it }
        return tags.joinToString(" · ")
    }

    private fun String.compactOnlineSourceLine(): String =
        replace(Regex("\\s+"), " ").trim()

    private fun listOnlineSources(): List<OnlineSourceEntry> =
        OnlineSourceStore.list(activityProvider()?.applicationContext)

    private fun armOnlineSourceRemoval(source: OnlineSourceEntry) {
        pendingDeleteOnlineSourceId = source.id
        pendingDeleteOnlineSourceAtMs = SystemClock.elapsedRealtime()
        showToast("再次点击移除此源：${source.name}")
    }

    private fun confirmOrRemoveOnlineSource(source: OnlineSourceEntry) {
        val now = SystemClock.elapsedRealtime()
        if (
            pendingDeleteOnlineSourceId == source.id &&
            now - pendingDeleteOnlineSourceAtMs <= ONLINE_SOURCE_REMOVE_CONFIRM_WINDOW_MS
        ) {
            settings.setOnlineSourceEnabled(source.id, false)
            val removed = OnlineSourceStore.remove(activityProvider()?.applicationContext, source.id)
            pendingDeleteOnlineSourceId = ""
            pendingDeleteOnlineSourceAtMs = 0L
            if (removed) {
                bumpOnlineSourceVersion()
                showToast("已移除在线源：${source.name}")
            } else {
                showToast("在线源移除失败：${source.name}")
            }
        } else {
            armOnlineSourceRemoval(source)
        }
    }

    private fun renderOnlineSourceSwitch(source: OnlineSourceEntry, composer: Any) {
        val targetChecked = settings.isOnlineSourceEnabled(source.id)
        val state = rememberBooleanState(composer, targetChecked)
        fun updateChecked(value: Boolean) {
            state.javaClass.methods.firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
                ?.invoke(state, value)
        }
        val rememberedChecked = state.method0("getValue") as? Boolean ?: targetChecked
        val checked = if (rememberedChecked != targetChecked) {
            updateChecked(targetChecked)
            targetChecked
        } else {
            rememberedChecked
        }
        val onCheckedChange = functionProxy("OnlineSourceSwitch${source.id}", FUNCTION1_CLASS) { args ->
            val enabled = args?.getOrNull(0) as? Boolean ?: return@functionProxy targetUnit()
            val applied = setOnlineSourceEnabled(source, enabled, ::updateChecked)
            updateChecked(applied)
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

    private fun setOnlineSourceEnabled(
        source: OnlineSourceEntry,
        enabled: Boolean,
        updateChecked: (Boolean) -> Unit,
    ): Boolean {
        if (!enabled) {
            settings.setOnlineSourceEnabled(source.id, false)
            return false
        }
        if (!source.hasLoginConfig) {
            settings.setOnlineSourceEnabled(source.id, true)
            if (source.hasLoginConfig) {
                showToast("${source.name} 暂未执行登录，已跳过并启用")
            }
            return true
        }
        settings.setOnlineSourceEnabled(source.id, false)
        openOnlineSourceLoginDialog(source) { confirmed ->
            if (confirmed) {
                settings.setOnlineSourceEnabled(source.id, true)
                updateChecked(true)
                bumpOnlineSourceVersion()
                showToast("已启用在线源：${source.name}")
            } else {
                settings.setOnlineSourceEnabled(source.id, false)
                updateChecked(false)
            }
        }
        return false
    }

    private fun importOnlineSourceFromClipboard() {
        val activity = activityProvider() ?: return
        val url = readClipboardText(activity).trim()
        if (url.isBlank()) {
            showToast("剪贴板中没有在线源链接")
            return
        }
        importOnlineSourceAsync(activity, "clipboard:$url") {
            OnlineSourceStore.importFromUrl(activity.applicationContext, url)
        }
    }

    private fun openOnlineSourceDocumentPicker() {
        val activity = activityProvider() ?: return
        runCatching {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "application/json",
                        "text/plain",
                        "application/octet-stream",
                    ),
                )
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivityForResult(intent, ONLINE_SOURCE_DOCUMENT_REQUEST_CODE)
        }.onFailure {
            Toast.makeText(activity, "无法打开在线源选择器", Toast.LENGTH_SHORT).show()
            XposedBridge.log("$LOG_PREFIX failed to open online source picker: ${it.stackTraceToString()}")
        }
    }

    private fun importOnlineSourceDocumentResult(activity: Activity, intent: Intent) {
        val clipData = intent.clipData
        if (clipData != null && clipData.itemCount != 1) {
            showToast("一次最多导入一个在线源")
            return
        }
        val uri = clipData?.getItemAt(0)?.uri ?: intent.data
        if (uri == null) {
            showToast("未选择在线源文件")
            return
        }
        importOnlineSourceAsync(activity, "uri:$uri") {
            val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取在线源文件")
            val displayName = queryDisplayName(activity, uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "online_source.json"
            OnlineSourceStore.importBytes(activity.applicationContext, bytes, displayName, uri.toString())
        }
    }

    private fun importOnlineSourceAsync(
        activity: Activity,
        token: String,
        importer: () -> OnlineSourceEntry,
    ) {
        val now = System.currentTimeMillis()
        if (token == lastOnlineSourceImportToken && now - lastOnlineSourceImportAtMs < ONLINE_SOURCE_IMPORT_DEDUPE_WINDOW_MS) {
            return
        }
        lastOnlineSourceImportToken = token
        lastOnlineSourceImportAtMs = now
        Thread {
            runCatching { importer() }
                .onSuccess { source ->
                    activity.runOnUiThread {
                        bumpOnlineSourceVersion()
                        showToast("已添加在线源：${source.name}")
                    }
                }
                .onFailure {
                    XposedBridge.log("$LOG_PREFIX online source import failed: ${it.stackTraceToString()}")
                    showToast(it.message ?: "导入在线源失败")
                }
        }.apply {
            name = "ReaMicroOnlineSourceImport"
            isDaemon = true
            start()
        }
    }

    private fun openOnlineSourceLoginDialog(source: OnlineSourceEntry, onResult: (Boolean) -> Unit) {
        val activity = activityProvider()
        if (activity == null) {
            onResult(false)
            return
        }
        val loginUrl = source.webLoginUrl
        if (loginUrl.isBlank()) {
            openOnlineSourceCredentialDialog(activity, source, onResult)
            return
            showToast("${source.name} 没有可打开的登录地址，已跳过登录")
            onResult(true)
            return
        }
        activity.runOnUiThread {
            runCatching {
                CookieManager.getInstance().setAcceptCookie(true)
                var resolved = false
                val webView = WebView(activity).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            CookieManager.getInstance().flush()
                        }
                    }
                    loadUrl(loginUrl)
                }
                fun resolve(value: Boolean) {
                    if (resolved) return
                    resolved = true
                    CookieManager.getInstance().flush()
                    onResult(value)
                }
                val dialog = AlertDialog.Builder(activity)
                    .setTitle("${source.name} 登录")
                    .setView(webView)
                    .setPositiveButton("完成", null)
                    .setNegativeButton("跳过启用", null)
                    .setNeutralButton("取消", null)
                    .create()
                dialog.setOnShowListener {
                    dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    webView.requestFocus()
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        resolve(true)
                        dialog.dismiss()
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        resolve(true)
                        dialog.dismiss()
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        resolve(false)
                        dialog.dismiss()
                    }
                }
                dialog.setOnDismissListener {
                    if (!resolved) resolve(false)
                    runCatching { webView.destroy() }
                }
                dialog.show()
                dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to open online source login: ${it.stackTraceToString()}")
            onResult(false)
        }
    }
    }


    private fun openOnlineSourceCredentialDialog(
        activity: Activity,
        source: OnlineSourceEntry,
        onResult: (Boolean) -> Unit,
    ) {
        activity.runOnUiThread {
            runCatching {
                val container = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 24, 48, 0)
                }
                val userInput = EditText(activity).apply {
                    hint = "账号"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
                    setSingleLine(true)
                }
                val passwordInput = EditText(activity).apply {
                    hint = "密码"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setSingleLine(true)
                }
                container.addView(userInput)
                container.addView(passwordInput)
                var resolved = false
                fun resolve(value: Boolean) {
                    if (resolved) return
                    resolved = true
                    onResult(value)
                }
                val dialog = AlertDialog.Builder(activity)
                    .setTitle("${source.name} 登录")
                    .setMessage("该源没有可打开的登录地址，请输入账号密码，或选择跳过启用。")
                    .setView(container)
                    .setPositiveButton("完成", null)
                    .setNegativeButton("跳过启用", null)
                    .setNeutralButton("取消", null)
                    .create()
                dialog.setOnShowListener {
                    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val username = userInput.text?.toString().orEmpty()
                        val password = passwordInput.text?.toString().orEmpty()
                        if (username.isBlank() || password.isBlank()) {
                            Toast.makeText(activity, "请输入账号和密码", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        positiveButton.isEnabled = false
                        Toast.makeText(activity, "正在登录 ${source.name}", Toast.LENGTH_SHORT).show()
                        Thread({
                            val result = OnlineSourceAuth.login(
                                activity.applicationContext,
                                source,
                                username,
                                password,
                            )
                            activity.runOnUiThread {
                                positiveButton.isEnabled = true
                                Toast.makeText(activity, result.message, Toast.LENGTH_SHORT).show()
                                if (result.success) {
                                    resolve(true)
                                    dialog.dismiss()
                                }
                            }
                        }, "ReaMicroOnlineSourceLogin").start()
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        resolve(true)
                        dialog.dismiss()
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        resolve(false)
                        dialog.dismiss()
                    }
                }
                dialog.setOnCancelListener { resolve(false) }
                dialog.show()
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX failed to open online source credential dialog: ${it.stackTraceToString()}")
                onResult(false)
            }
        }
    }

    private fun setRotationEnabled(
        enabled: Boolean,
        currentState: RotationUiState,
        updateRow: (String, Boolean) -> Unit,
    ): Boolean {
        settings.setRotationEnabled(enabled)
        updateRow(ModuleSettings.KEY_ROTATION_ENABLED, enabled)
        if (enabled) {
            val nextState = if (
                currentState.autoEnabled ||
                currentState.portraitLockEnabled ||
                currentState.landscapeLockEnabled
            ) {
                currentState
            } else {
                currentState.copy(autoEnabled = true)
            }
            rotationUiState = nextState
            if (nextState.autoEnabled && !currentState.autoEnabled) {
                settings.setRotationAutoEnabled(true)
            }
        }
        applyCurrentRotation()
        return enabled
    }

    private fun setRotationBaseEnabled(
        key: String,
        enabled: Boolean,
        updateRow: (String, Boolean) -> Unit,
    ): Boolean {
        suppressRotationSnapshotSync()
        val nextState = currentRotationUiState().withBase(key, enabled)
        rotationUiState = nextState
        when (key) {
            ModuleSettings.KEY_ROTATION_AUTO_ENABLED -> settings.setRotationAutoEnabled(enabled)
            ModuleSettings.KEY_ROTATION_PORTRAIT_LOCK_ENABLED -> settings.setRotationPortraitLockEnabled(enabled)
            ModuleSettings.KEY_ROTATION_LANDSCAPE_LOCK_ENABLED -> settings.setRotationLandscapeLockEnabled(enabled)
            else -> return false
        }
        ModuleSettings.ROTATION_BASE_KEYS.forEach { baseKey ->
            updateRow(baseKey, nextState.isEnabled(baseKey))
        }
        applyCurrentRotation()
        return nextState.isEnabled(key)
    }

    private fun suppressRotationSnapshotSync() {
        suppressRotationSnapshotSyncUntilMs = System.currentTimeMillis() + ROTATION_SNAPSHOT_SYNC_SUPPRESS_MS
    }

    private fun currentRotationUiState(): RotationUiState =
        rotationUiState ?: RotationUiState.from(settings.snapshot())

    private fun currentRotationDisplayState(): RotationUiState {
        val snapshot = settings.snapshot()
        return RotationUiState.fromActivityOrientation(
            activityProvider()?.requestedOrientation,
            snapshot.rotationReverseEnabled,
        ) ?: rotationUiState ?: RotationUiState.from(snapshot)
    }

    private fun applyCurrentRotation() {
        val activity = activityProvider() ?: return
        RotationOrientationController.apply(activity, settings.snapshot())
    }

    private fun setAssociationSearchSourceEnabled(
        groupId: String,
        enabled: Boolean,
        updateChecked: (Boolean) -> Unit,
    ): Boolean {
        if (groupId != BookSource.YouShu.id) {
            settings.setAssociationSearchSourceEnabled(groupId, enabled)
            return enabled
        }
        if (!enabled) {
            settings.setAssociationSearchSourceEnabled(groupId, false)
            return false
        }

        settings.setAssociationSearchSourceEnabled(groupId, false)
        val cookieHeader = YouShuLoginCookies.cookieHeader()
        if (!YouShuLoginCookies.hasLoginCookie(cookieHeader)) {
            openYouShuLoginPage { loggedInAfterDialog ->
                if (loggedInAfterDialog) {
                    applyYouShuSourceEnabled(groupId, updateChecked)
                } else {
                    settings.setAssociationSearchSourceEnabled(groupId, false)
                    updateChecked(false)
                }
            }
            return false
        }

        verifyYouShuLoginAsync(
            attempts = YOUSHU_FAST_LOGIN_VERIFY_ATTEMPTS,
            allowWebView = false,
        ) { loggedIn ->
            if (loggedIn) {
                applyYouShuSourceEnabled(groupId, updateChecked)
            } else {
                openYouShuLoginPage { loggedInAfterDialog ->
                    if (loggedInAfterDialog) {
                        applyYouShuSourceEnabled(groupId, updateChecked)
                    } else {
                        settings.setAssociationSearchSourceEnabled(groupId, false)
                        updateChecked(false)
                    }
                }
            }
        }
        return false
    }

    private fun visibleAssociationSearchSourceGroups() =
        AssociationSearchProviderRegistry.searchSourceGroups(activityProvider()?.applicationContext)

    private fun hasManualEditFeature(): Boolean =
        ExternalSourceLoader.loadFeatures(activityProvider()?.applicationContext)
            .any { feature -> "manual_edit" in feature.capabilities }

    private fun applyYouShuSourceEnabled(groupId: String, updateChecked: (Boolean) -> Unit) {
        settings.setAssociationSearchSourceEnabled(groupId, true)
        updateChecked(true)
        activityProvider()?.let { activity ->
            activity.runOnUiThread {
                Toast.makeText(activity, "优书网已登录并启用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun verifyYouShuLoginAsync(
        attempts: Int = YOUSHU_LOGIN_VERIFY_ATTEMPTS,
        allowWebView: Boolean = true,
        onResult: (Boolean) -> Unit,
    ) {
        val activity = activityProvider()
        Thread {
            var loggedIn = false
            var cookieNames = ""
            runCatching {
                val maxAttempts = attempts.coerceAtLeast(1)
                for (attempt in 0 until maxAttempts) {
                    CookieManager.getInstance().flush()
                    val cookieHeader = YouShuLoginCookies.cookieHeader()
                    cookieNames = YouShuLoginCookies.cookieNames(cookieHeader)
                    loggedIn = YouShuLoginState.canSearchWithCookie(cookieHeader) ||
                        (allowWebView && YouShuLoginState.canSearchWithWebView())
                    if (loggedIn) break
                    if (attempt < maxAttempts - 1) {
                        Thread.sleep(YOUSHU_LOGIN_VERIFY_DELAY_MS)
                    }
                }
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX YouShu login verify failed: ${it.stackTraceToString()}")
                loggedIn = false
            }
            XposedBridge.log("$LOG_PREFIX YouShu login verify result=$loggedIn cookies=[$cookieNames]")
            if (activity != null) {
                activity.runOnUiThread { onResult(loggedIn) }
            } else {
                onResult(loggedIn)
            }
        }.apply {
            name = "ReaMicroYouShuLoginVerify"
            isDaemon = true
            start()
        }
    }

    private fun openYouShuLoginPage(onLoginStateResolved: (Boolean) -> Unit) {
        val activity = activityProvider()
        if (activity == null) {
            onLoginStateResolved(false)
            return
        }
        activity.runOnUiThread {
            runCatching {
                CookieManager.getInstance().setAcceptCookie(true)
                val mainHandler = Handler(Looper.getMainLooper())
                var destroyed = false
                var resolved = false
                val webView = WebView(activity).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    setOnTouchListener { view, _ ->
                        view.requestFocusFromTouch()
                        false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            CookieManager.getInstance().flush()
                        }
                    }
                    loadUrl(YOUSHU_LOGIN_URL)
                }
                fun resolveLoginState(loggedIn: Boolean) {
                    if (resolved) return
                    resolved = true
                    onLoginStateResolved(loggedIn)
                }
                fun destroyWebView() {
                    if (destroyed) return
                    destroyed = true
                    CookieManager.getInstance().flush()
                    runCatching { webView.destroy() }
                }
                fun checkCurrentWebViewLoginState(onResult: (Boolean) -> Unit) {
                    CookieManager.getInstance().flush()
                    if (YouShuLoginCookies.hasLoginCookie()) {
                        onResult(true)
                        return
                    }
                    var callbackReturned = false
                    webView.evaluateJavascript(YOUSHU_LOGIN_STATE_JS) { value ->
                        if (callbackReturned) return@evaluateJavascript
                        callbackReturned = true
                        CookieManager.getInstance().flush()
                        val hasCookie = YouShuLoginCookies.hasLoginCookie()
                        XposedBridge.log("$LOG_PREFIX YouShu visible login state page=${value == "true"} cookies=[${YouShuLoginCookies.cookieNames()}] hasCookie=$hasCookie")
                        onResult(hasCookie)
                    }
                    mainHandler.postDelayed({
                        if (!callbackReturned) {
                            callbackReturned = true
                            onResult(YouShuLoginCookies.hasLoginCookie())
                        }
                    }, YOUSHU_LOGIN_STATE_TIMEOUT_MS)
                }
                fun finishLoginCheck(dialog: AlertDialog) {
                    CookieManager.getInstance().flush()
                    val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    positiveButton?.isEnabled = false
                    checkCurrentWebViewLoginState { loggedIn ->
                        positiveButton?.isEnabled = true
                        if (loggedIn) {
                            resolveLoginState(true)
                            dialog.dismiss()
                        } else if (dialog.isShowing) {
                            Toast.makeText(activity, "未读到优书网登录Cookie，请等待页面刷新后再点完成", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                val dialog = AlertDialog.Builder(activity)
                    .setTitle("优书网登录")
                    .setView(webView)
                    .setPositiveButton("完成", null)
                    .setNegativeButton("取消", null)
                    .create()
                dialog.setOnShowListener {
                    dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    webView.requestFocus()
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        finishLoginCheck(dialog)
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        resolveLoginState(false)
                        dialog.dismiss()
                    }
                }
                dialog.setOnDismissListener {
                    if (!resolved) resolveLoginState(false)
                    destroyWebView()
                }
                dialog.show()
                dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX failed to open YouShu login WebView: ${it.stackTraceToString()}")
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(YOUSHU_LOGIN_URL)))
                }.onFailure { error ->
                    XposedBridge.log("$LOG_PREFIX failed to open YouShu login page: ${error.stackTraceToString()}")
                }
                onLoginStateResolved(false)
            }
        }
    }

    private fun renderHostLazyColumn(innerPaddings: Any, listContent: Any, composer: Any) {
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

    private fun fontSelectionSummary(value: String, fallback: String): String =
        if (value.isBlank()) fallback else displayFontName(value)

    private fun dialogueHighlightFontSummary(value: String): String {
        if (value.isNotBlank()) return displayFontName(value)
        val global = settings.fontSettings().globalFamily
        return if (global.isBlank()) "\u8ddf\u968f\u5168\u5c40\u5b57\u4f53" else "\u8ddf\u968f\u5168\u5c40\u5b57\u4f53\uff1a${displayFontName(global)}"
    }

    private fun dialogueHighlightColorSummary(value: String): String =
        READER_HIGHLIGHT_COLOR_OPTIONS.firstOrNull { it.value.equals(value, ignoreCase = true) }?.title ?: value

    private fun readerHighlightSettingsSummary(): String {
        val highlight = settings.highlightSettings()
        return "${highlight.styles.size} \u4e2a\u6837\u5f0f / ${highlight.rules.count { it.enabled }} \u6761\u89c4\u5219"
    }

    private fun readerHighlightBookGroups(highlight: ReaderHighlightSettingsSnapshot): List<ReaderHighlightBookGroupRow> =
        buildList {
            highlight.bookRuleGroups().forEach { group ->
                add(
                    ReaderHighlightBookGroupRow(
                        bookKey = group.bookKey,
                        bookTitle = displayReaderBookTitle(group.bookKey, group.bookTitle),
                        subtitle = "${group.enabledCount} / ${group.totalCount} \u6761\u672c\u4e66\u89c4\u5219",
                    ),
                )
            }
        }.sortedBy { it.bookTitle }

    private fun displayReaderBookTitle(bookKey: String, storedTitle: String): String {
        val currentBookKey = ReaderHighlightBookContext.bookKey
        val currentBookTitle = ReaderHighlightBookContext.bookTitle
        val currentMatches = readerHighlightBookIdentityMatches(
            storedBookKey = bookKey,
            storedBookTitle = storedTitle,
            currentBookKey = currentBookKey,
            currentBookTitle = currentBookTitle,
        )
        val candidates = buildList {
            if (currentMatches) add(currentBookTitle)
            add(storedTitle)
            addAll(bookKey.split('|').asReversed())
        }
        return candidates
            .asSequence()
            .map(::cleanReaderBookTitle)
            .firstOrNull { it.isNotBlank() && !isGenericReaderBookTitle(it) && !isInternalReaderBookTitle(it) }
            ?: "\u672c\u4e66"
    }

    private fun cleanReaderBookTitle(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return ""
        return trimmed
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .removeSuffix(".epub")
            .removeSuffix(".EPUB")
            .trim()
    }

    private fun isInternalReaderBookTitle(value: String): Boolean =
        value.contains('|') ||
            value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) ||
            value.matches(Regex("^[0-9a-fA-F]{16,}$"))

    private fun isGenericReaderBookTitle(value: String): Boolean =
        value == "\u672c\u4e66" || value == "\u56fe\u4e66"

    private fun highlightStyleSummary(style: ReaderHighlightStyle): String =
        listOfNotNull(
            dialogueHighlightColorSummary(style.color),
            dialogueHighlightFontSummary(style.fontFamily),
            style.css.takeIf { it.isNotBlank() }?.let { "CSS" },
            style.ninePatchPath.takeIf { it.isNotBlank() }?.let {
                if (style.ninePatchSlice.isNotBlank()) "Reeden \u4e5d\u5bab\u683c\u56fe" else "\u56fe\u7247\u80cc\u666f"
            },
        ).joinToString(" / ")

    private fun readerHighlightStyleDefaultTrailing(
        highlight: ReaderHighlightSettingsSnapshot,
        style: ReaderHighlightStyle,
    ): String? =
        buildList {
            if (style.id == highlight.defaultLightStyleId) add("\u6d45\u8272\u9ed8\u8ba4")
            if (style.id == highlight.defaultDarkStyleId) add("\u6df1\u8272\u9ed8\u8ba4")
        }.takeIf { it.isNotEmpty() }?.joinToString(" / ")

    private fun highlightRuleTypeTitle(rule: ReaderHighlightRule): String =
        when (rule.type) {
            ReaderHighlightRuleType.DoubleQuoteDialogue -> "\u53cc\u5f15\u53f7\u5bf9\u8bdd"
            ReaderHighlightRuleType.SingleQuotePhrase -> "\u5355\u5f15\u53f7\u8bcd\u7ec4"
            ReaderHighlightRuleType.FixedText -> "\u56fa\u5b9a\u6587\u672c"
            ReaderHighlightRuleType.Regex -> "\u6b63\u5219"
        }

    private fun highlightRuleDescription(rule: ReaderHighlightRule): String =
        when (rule.type) {
            ReaderHighlightRuleType.DoubleQuoteDialogue -> "\u5339\u914d\u540c\u6bb5\u5185\u7684\u201c\u2026\u201d\u300c\u2026\u300d\u300e\u2026\u300f\u548c \"...\""
            ReaderHighlightRuleType.SingleQuotePhrase -> "\u5339\u914d\u540c\u6bb5\u5185\u7684\u2018\u2026\u2019 \u548c '...'"
            ReaderHighlightRuleType.FixedText -> "\u6309\u5b57\u9762\u91cf\u5339\u914d\u56fa\u5b9a\u6587\u672c"
            ReaderHighlightRuleType.Regex -> "\u6309\u6b63\u5219\u8868\u8fbe\u5f0f\u5339\u914d\u6587\u672c"
        }

    private fun highlightRuleSummary(rule: ReaderHighlightRule): String {
        val base = highlightRuleTypeTitle(rule)
        val pattern = rule.pattern.takeIf {
            rule.type == ReaderHighlightRuleType.FixedText || rule.type == ReaderHighlightRuleType.Regex
        }?.compactOnlineSourceLine()
        return if (pattern.isNullOrBlank()) base else "$base: $pattern"
    }

    private fun highlightRuleStyleSummary(
        highlight: ReaderHighlightSettingsSnapshot,
        rule: ReaderHighlightRule,
    ): String {
        val light = readerHighlightLightStyleLabel(highlight, rule.styleId)
        val dark = readerHighlightDarkStyleLabel(highlight, rule.darkStyleId)
        return if (dark == light) {
            "\u6d45/\u6df1 $light"
        } else {
            "\u6d45 $light / \u6df1 $dark"
        }
    }

    private fun readerHighlightLightStyleLabel(
        highlight: ReaderHighlightSettingsSnapshot,
        styleId: String,
    ): String {
        val id = styleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID }
        return if (id == ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID) {
            "\u6d45\u8272\u9ed8\u8ba4\uff08${highlight.defaultLightStyle().name}\uff09"
        } else {
            highlight.styleById(id).name
        }
    }

    private fun readerHighlightDarkStyleLabel(
        highlight: ReaderHighlightSettingsSnapshot,
        styleId: String,
    ): String {
        val id = styleId.ifBlank { ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID }
        return if (id == ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID) {
            "\u6df1\u8272\u9ed8\u8ba4\uff08${highlight.defaultDarkStyle().name}\uff09"
        } else {
            highlight.styleById(id).name
        }
    }

    private fun newReaderHighlightStyle(): ReaderHighlightStyle {
        val index = settings.highlightSettings().styles.size + 1
        return ReaderHighlightStyle(
            id = uniqueReaderHighlightId("style"),
            name = "\u9ad8\u4eae\u6837\u5f0f $index",
            color = ModuleSettings.DEFAULT_READER_DIALOGUE_HIGHLIGHT_COLOR,
        )
    }

    private fun newReaderHighlightRule(bookKey: String = "", bookTitle: String = ""): ReaderHighlightRule {
        val rules = settings.highlightSettings().rules
        val index = rules.count { it.bookKey == bookKey } + 1
        return ReaderHighlightRule(
            id = uniqueReaderHighlightId("rule"),
            name = if (bookKey.isBlank()) "\u9ad8\u4eae\u89c4\u5219 $index" else "\u672c\u4e66\u89c4\u5219 $index",
            type = ReaderHighlightRuleType.FixedText,
            styleId = ModuleSettings.READER_HIGHLIGHT_LIGHT_DEFAULT_REFERENCE_ID,
            darkStyleId = ModuleSettings.READER_HIGHLIGHT_DARK_DEFAULT_REFERENCE_ID,
            bookKey = bookKey,
            bookTitle = bookTitle,
        )
    }

    private fun uniqueReaderHighlightId(prefix: String): String {
        val highlight = settings.highlightSettings()
        val existing = highlight.styles.map { it.id }.toSet() + highlight.rules.map { it.id }.toSet()
        var id = "${prefix}_${System.currentTimeMillis()}"
        var suffix = 1
        while (id in existing) {
            id = "${prefix}_${System.currentTimeMillis()}_$suffix"
            suffix++
        }
        return id
    }

    private fun isDefaultReaderHighlightRule(ruleId: String): Boolean =
        ruleId == ModuleSettings.DEFAULT_READER_DOUBLE_QUOTE_RULE_ID ||
            ruleId == ModuleSettings.DEFAULT_READER_SINGLE_QUOTE_RULE_ID

    private fun currentFontSelection(target: FontPickerTarget): String {
        val config = settings.fontSettings()
        return when (target) {
            FontPickerTarget.Global -> config.globalFamily
            FontPickerTarget.SongMapping -> config.songMapping
            FontPickerTarget.KaiMapping -> config.kaiMapping
            FontPickerTarget.DialogueHighlight -> settings.dialogueHighlightSettings().fontFamily
        }
    }

    private fun setFontSelection(target: FontPickerTarget, value: String) {
        when (target) {
            FontPickerTarget.Global -> {
                val changed = !sameFontSelection(currentFontSelection(target), value)
                settings.setFontGlobalFamily(value)
                if (changed) onGlobalFontChanged()
            }
            FontPickerTarget.SongMapping -> settings.setFontSongMapping(value)
            FontPickerTarget.KaiMapping -> settings.setFontKaiMapping(value)
            FontPickerTarget.DialogueHighlight -> settings.setReaderDialogueHighlightFontFamily(value)
        }
    }

    private fun sameFontSelection(current: String, selection: String): Boolean =
        current == selection ||
            (!isBuiltinFontSelection(current) && !isBuiltinFontSelection(selection) &&
                current.isNotBlank() && File(current).name == File(selection).name)

    private fun displayFontName(value: String): String {
        builtinFontSelections().firstOrNull { it.value == value }?.let { return it.title }
        val name = File(value).name.ifBlank { value }
        return name.substringBeforeLast('.', name)
    }

    private fun builtinFontSelections(): List<FontSelectionOption> =
        listOf(
            FontSelectionOption(
                value = FAMILY_SOURCE_HAN_SERIF,
                title = "\u601d\u6e90\u5b8b\u4f53",
                subtitle = "\u9605\u5fae\u5185\u7f6e\u5b57\u4f53",
            ),
            FontSelectionOption(
                value = FAMILY_SYSTEM,
                title = "\u7cfb\u7edf\u5b57\u4f53",
                subtitle = "\u8ddf\u968f\u7cfb\u7edf\u9ed8\u8ba4\u5b57\u4f53",
            ),
        )

    private fun builtinFontSelectionsFor(target: FontPickerTarget): List<FontSelectionOption> =
        builtinFontSelections().filterNot { option ->
            option.value == FAMILY_SOURCE_HAN_SERIF &&
                (target == FontPickerTarget.Global || target == FontPickerTarget.SongMapping)
        }

    private fun isBuiltinFontSelection(selection: String): Boolean =
        selection == FAMILY_SOURCE_HAN_SERIF || selection == FAMILY_SYSTEM

    private fun listFontFiles(): List<File> {
        val activity = activityProvider() ?: return emptyList()
        val version = fontLibraryVersionValue()
        val now = System.currentTimeMillis()
        synchronized(fontFilesCacheLock) {
            if (
                cachedFontFilesVersion == version &&
                now - cachedFontFilesAtMs < FONT_FILES_CACHE_WINDOW_MS
            ) {
                return cachedFontFiles
            }
        }
        val files = fontDirectories(activity)
            .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
            .filter { it.isFile && isFontFileName(it.name) }
            .distinctBy { it.absolutePath }
            .sortedBy { displayFontName(it.absolutePath).lowercase() }
        synchronized(fontFilesCacheLock) {
            cachedFontFilesVersion = version
            cachedFontFilesAtMs = now
            cachedFontFiles = files
        }
        return files
    }

    private fun fontDirectories(activity: Activity): List<File> {
        val filesDir = activity.filesDir ?: return emptyList()
        val dirs = filesDir.listFiles()
            ?.filter { it.isDirectory && it.name.toLongOrNull() != null }
            ?.map { File(it, "fonts") }
            ?.toMutableList()
            ?: mutableListOf()
        val defaultDir = File(File(filesDir, "0"), "fonts")
        if (dirs.none { it.absolutePath == defaultDir.absolutePath }) {
            dirs.add(defaultDir)
        }
        return dirs.distinctBy { it.absolutePath }
    }

    private fun writableFontDirectory(activity: Activity): File =
        fontDirectories(activity)
            .filter { it.exists() }
            .maxByOrNull { it.lastModified() }
            ?: File(File(activity.filesDir, "0"), "fonts")

    private fun openFontDocumentPicker() {
        val activity = activityProvider() ?: return
        runCatching {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "font/ttf",
                        "font/otf",
                        "application/x-font-ttf",
                        "application/x-font-otf",
                        "application/font-sfnt",
                        "application/octet-stream",
                    ),
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivityForResult(intent, FONT_DOCUMENT_REQUEST_CODE)
        }.onFailure {
            Toast.makeText(activity, "\u65e0\u6cd5\u6253\u5f00\u5b57\u4f53\u9009\u62e9\u5668", Toast.LENGTH_SHORT).show()
            XposedBridge.log("$LOG_PREFIX failed to open font picker: ${it.stackTraceToString()}")
        }
    }

    private fun hookHostAccountSignOut() {
        runCatching {
            XposedBridge.hookAllMethods(
                cls(USER_REPOSITORY_CLASS),
                USER_REPOSITORY_SIGN_OUT_METHOD,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!settings.snapshot().canRunAccountCompletion) return
                        runCatching { accountController.persistCurrentAccountSnapshot() }
                            .onFailure {
                                XposedBridge.log("$LOG_PREFIX failed to persist account before sign out: ${it.stackTraceToString()}")
                            }
                    }
                },
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook host sign out: ${it.stackTraceToString()}")
        }
    }

    private fun hookAccountSecurityScreen() {
        runCatching {
            val method = method(
                ACCOUNT_SECURITY_SCREEN_CLASS,
                ACCOUNT_SECURITY_DELETE_CONTENT_METHOD,
                5,
            )
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!settings.snapshot().canRunAccountDataExport) return
                    val state = param.args?.getOrNull(0) ?: return
                    val deleteDialogState = param.args?.getOrNull(1) ?: return
                    val composer = param.args?.getOrNull(3) ?: return
                    runCatching {
                        renderAccountSecurityExportContent(state, deleteDialogState, composer)
                        param.result = targetUnit()
                    }.onFailure {
                        XposedBridge.log("$LOG_PREFIX failed to render account security export list: ${it.stackTraceToString()}")
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook account security screen: ${it.stackTraceToString()}")
        }
    }

    private fun hookFontDocumentPickerResult() {
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                    val resultCode = (param.args?.getOrNull(1) as? Number)?.toInt() ?: return
                    if (resultCode != Activity.RESULT_OK) return
                    val activity = param.thisObject as? Activity ?: return
                    val intent = param.args?.getOrNull(2) as? Intent ?: return
                    if (requestCode == ONLINE_SOURCE_DOCUMENT_REQUEST_CODE) {
                        importOnlineSourceDocumentResult(activity, intent)
                        return
                    }
                    val uri = intent.data ?: return
                    when (requestCode) {
                        FONT_DOCUMENT_REQUEST_CODE -> copyFontUriToLibrary(activity, uri)
                        HIGHLIGHT_STYLE_DOCUMENT_REQUEST_CODE -> importReaderHighlightStyleFromUri(activity, uri)
                        HIGHLIGHT_NINE_PATCH_DOCUMENT_REQUEST_CODE -> importHighlightNinePatchFromUri(activity, uri)
                        PROFILE_BACKGROUND_IMAGE_DOCUMENT_REQUEST_CODE -> importProfileBackgroundImageFromUri(activity, uri)
                        ACCOUNT_CREDENTIAL_DOCUMENT_REQUEST_CODE -> importCredentialFromUri(activity, uri)
                        ACCOUNT_DATA_DOCUMENT_REQUEST_CODE -> importAccountDataFromUri(activity, uri)
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook font picker result: ${it.stackTraceToString()}")
        }
    }

    private fun hookExternalSourceImportIntent() {
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.packageName != HOST_PACKAGE_NAME) return
                    consumeExternalSourceImportIntent(activity, activity.intent)
                }
            })
            XposedBridge.hookAllMethods(Activity::class.java, "onNewIntent", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.packageName != HOST_PACKAGE_NAME) return
                    val intent = param.args?.getOrNull(0) as? Intent ?: activity.intent
                    consumeExternalSourceImportIntent(activity, intent)
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook external source import intent: ${it.stackTraceToString()}")
        }
    }

    private fun consumeExternalSourceImportIntent(activity: Activity, intent: Intent?) {
        intent ?: return
        val payload = intent.getStringExtra(EXTRA_IMPORT_PAYLOAD) ?: return
        // 只消费一次，避免旋转/重复 onCreate 反复导入
        intent.removeExtra(EXTRA_IMPORT_PAYLOAD)
        val displayName = intent.getStringExtra(EXTRA_IMPORT_NAME) ?: "imported.json"
        val bytes = runCatching { Base64.decode(payload, Base64.NO_WRAP) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            showToast("导入内容为空")
            return
        }
        val token = "external:$displayName:${bytes.size}"
        if (token == lastExternalImportToken &&
            System.currentTimeMillis() - lastExternalImportAtMs < ONLINE_SOURCE_IMPORT_DEDUPE_WINDOW_MS
        ) {
            return
        }
        lastExternalImportToken = token
        lastExternalImportAtMs = System.currentTimeMillis()
        importExternalJson(activity, bytes, displayName)
    }

    private fun importExternalJson(activity: Activity, bytes: ByteArray, displayName: String) {
        when (detectExternalImportKind(bytes)) {
            ExternalImportKind.HIGHLIGHT -> importExternalHighlight(activity, bytes)
            ExternalImportKind.ONLINE_SOURCE -> importExternalOnlineSource(activity, bytes, displayName)
        }
    }

    private fun detectExternalImportKind(bytes: ByteArray): ExternalImportKind {
        // Reeden 高亮包（RED\1 magic）
        if (bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 1.toByte()
        ) {
            return ExternalImportKind.HIGHLIGHT
        }
        val text = bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")
        val json = runCatching {
            when {
                text.startsWith("[") -> JSONArray(text).optJSONObject(0)
                text.startsWith("{") -> JSONObject(text)
                else -> null
            }
        }.getOrNull() ?: return ExternalImportKind.ONLINE_SOURCE
        val isSource = json.has("bookSourceName") || json.has("bookSourceUrl") ||
            json.has("searchUrl") || json.has("ruleSearch") || json.has("ruleToc")
        if (isSource) return ExternalImportKind.ONLINE_SOURCE
        val isHighlight = json.optString("type") == "reader_highlight_style" ||
            json.has("style") || json.has("styles") || json.has("color")
        return if (isHighlight) ExternalImportKind.HIGHLIGHT else ExternalImportKind.ONLINE_SOURCE
    }

    private fun importExternalHighlight(activity: Activity, bytes: ByteArray) {
        runCatching {
            val imported = readerHighlightImportFromBytes(activity, bytes)
            imported.styles.forEach(settings::setReaderHighlightStyle)
            imported.rules.forEach(settings::setReaderHighlightRule)
            bumpReaderHighlightVersion()
            showToast(
                if (imported.reeden) {
                    "已导入 Reeden 高亮样式：${imported.styles.size} 个"
                } else if (imported.styles.size == 1) {
                    "已导入高亮样式：${imported.styles.first().name}"
                } else {
                    "已导入 ${imported.styles.size} 个高亮样式"
                },
            )
        }.onFailure {
            showToast("导入高亮样式失败")
            XposedBridge.log("$LOG_PREFIX external highlight import failed: ${it.stackTraceToString()}")
        }
    }

    private fun importExternalOnlineSource(activity: Activity, bytes: ByteArray, displayName: String) {
        importOnlineSourceAsync(activity, "external:$displayName:${bytes.size}") {
            OnlineSourceStore.importBytes(activity.applicationContext, bytes, displayName, "import:external")
        }
    }

    private enum class ExternalImportKind { ONLINE_SOURCE, HIGHLIGHT }

    private fun copyFontUriToLibrary(activity: Activity, uri: Uri) {
        runCatching {
            val displayName = sanitizeFontFileName(queryDisplayName(activity, uri) ?: uri.lastPathSegment.orEmpty())
            if (!isFontFileName(displayName)) {
                Toast.makeText(activity, "\u8bf7\u9009\u62e9 ttf/otf \u5b57\u4f53\u6587\u4ef6", Toast.LENGTH_SHORT).show()
                return
            }
            val importToken = "$uri|$displayName"
            val now = System.currentTimeMillis()
            if (importToken == lastFontImportToken && now - lastFontImportAtMs < FONT_IMPORT_DEDUPE_WINDOW_MS) {
                return
            }
            lastFontImportToken = importToken
            lastFontImportAtMs = now
            if (listFontFiles().any { it.name.equals(displayName, ignoreCase = true) }) {
                Toast.makeText(activity, "\u5b57\u4f53\u5df2\u5b58\u5728\uff1a${displayFontName(displayName)}", Toast.LENGTH_SHORT).show()
                bumpFontLibraryVersion()
                return
            }
            val targetDir = writableFontDirectory(activity).apply { mkdirs() }
            val targetFile = File(targetDir, displayName)
            val input = activity.contentResolver.openInputStream(uri) ?: error("font input stream is null")
            input.use { source ->
                FileOutputStream(targetFile).use { output ->
                    source.copyTo(output)
                }
            }
            bumpFontLibraryVersion()
            Toast.makeText(activity, "\u5df2\u6dfb\u52a0\u5b57\u4f53\uff1a${displayFontName(targetFile.name)}", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(activity, "\u6dfb\u52a0\u5b57\u4f53\u5931\u8d25", Toast.LENGTH_SHORT).show()
            XposedBridge.log("$LOG_PREFIX failed to copy font: ${it.stackTraceToString()}")
        }
    }

    private fun queryDisplayName(activity: Activity, uri: Uri): String? =
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

    private fun sanitizeFontFileName(name: String): String {
        val cleaned = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
        return cleaned.ifBlank { "font_${System.currentTimeMillis()}.ttf" }
    }

    private fun isFontFileName(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension == "ttf" || extension == "otf"
    }

    private fun uniqueFontFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var index = 1
        while (file.exists()) {
            file = File(dir, "$base-$index.$extension")
            index += 1
        }
        return file
    }

    private fun deleteFont(file: File) {
        val activity = activityProvider() ?: return
        runCatching {
            val target = file.canonicalFile
            if (!isKnownFontFile(activity, target)) {
                error("font file is outside user font directories: ${target.absolutePath}")
            }
            if (!target.exists()) {
                clearDeletedFontSelection(target)
                bumpFontLibraryVersion()
                Toast.makeText(activity, "\u5b57\u4f53\u5df2\u4e0d\u5b58\u5728", Toast.LENGTH_SHORT).show()
                return
            }
            if (!target.delete()) error("delete returned false")
            clearDeletedFontSelection(target)
            setPendingDeleteFontSelection("")
            bumpFontLibraryVersion()
            Toast.makeText(activity, "\u5df2\u79fb\u9664\u5b57\u4f53\uff1a${displayFontName(target.absolutePath)}", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(activity, "\u79fb\u9664\u5b57\u4f53\u5931\u8d25", Toast.LENGTH_SHORT).show()
            XposedBridge.log("$LOG_PREFIX failed to delete font: ${it.stackTraceToString()}")
        }
    }

    private fun isKnownFontFile(activity: Activity, file: File): Boolean {
        if (!file.isFile && file.exists()) return false
        if (!isFontFileName(file.name)) return false
        val targetPath = file.canonicalFile.absolutePath
        return fontDirectories(activity).any { dir ->
            val dirPath = dir.canonicalFile.absolutePath
            targetPath.startsWith(dirPath + File.separator)
        }
    }

    private fun clearDeletedFontSelection(file: File) {
        val selection = file.absolutePath
        val config = settings.fontSettings()
        if (sameFontSelection(config.globalFamily, selection)) settings.setFontGlobalFamily("")
        if (sameFontSelection(config.songMapping, selection)) settings.setFontSongMapping("")
        if (sameFontSelection(config.kaiMapping, selection)) settings.setFontKaiMapping("")
    }

    private fun renderHostSettingsCard(rows: List<ToggleRow>, composer: Any) {
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

    private fun renderHostActionCard(rows: List<ActionRow>, composer: Any) {
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

    private fun renderHostActionRow(row: ActionRow, composer: Any) {
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

    private fun renderHostSwitchRow(
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

    private fun renderHostText(text: String, composer: Any, fontFamily: Any? = null) {
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

    private fun renderHostSupportingText(text: String, composer: Any, singleLine: Boolean = false) {
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

    private fun renderHostTrailingText(text: String, composer: Any) {
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

    private fun renderHostSwitch(
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

    private fun renderHostActionSwitch(
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

    private fun renderHostDivider(composer: Any) {
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

    private fun addLazyItem(lazyListScope: Any, key: Int, block: (Any) -> Unit) {
        val content = composableLambda(key, FUNCTION3_CLASS) { args ->
            val composer = args?.getOrNull(1) ?: return@composableLambda targetUnit()
            block(composer)
            targetUnit()
        }
        val itemMethod = lazyItemDefaultMethod ?: method(LAZY_LIST_SCOPE_CLASS, LAZY_ITEM_DEFAULT_METHOD, 6)
        itemMethod.invoke(null, lazyListScope, null, null, content, 3, null)
    }

    private fun pageModifier(innerPaddings: Any): Any {
        val filled = method(SIZE_KT_CLASS, FILL_MAX_SIZE_DEFAULT_METHOD, 4).invoke(
            null,
            modifierInstance(),
            0f,
            1,
            null,
        )
        val padded = method(PADDING_KT_CLASS, PADDING_VALUES_METHOD, 2).invoke(null, filled, innerPaddings)
        return method(PADDING_KT_CLASS, PADDING_HORIZONTAL_DEFAULT_METHOD, 5).invoke(
            null,
            padded,
            udp(16),
            0f,
            2,
            null,
        )
    }

    private fun settingsCardModifier(composer: Any): Any {
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

    private fun readerHighlightSheetModifier(composer: Any): Any {
        val height = method(SIZE_KT_CLASS, HEIGHT_METHOD, 2).invoke(
            null,
            modifierInstance(),
            udp(READER_RULE_SHEET_HEIGHT_DP),
        )
        return method(BACKGROUND_KT_CLASS, BACKGROUND_DEFAULT_METHOD, 5).invoke(
            null,
            height,
            colorScheme(composer).longMethod("getSurface"),
            null,
            2,
            null,
        )
    }

    private fun readerHighlightSheetListModifier(): Any {
        val filled = method(SIZE_KT_CLASS, FILL_MAX_SIZE_DEFAULT_METHOD, 4).invoke(
            null,
            modifierInstance(),
            0f,
            1,
            null,
        )
        return method(PADDING_KT_CLASS, PADDING_HORIZONTAL_DEFAULT_METHOD, 5).invoke(
            null,
            filled,
            udp(18),
            0f,
            2,
            null,
        )
    }

    private fun rowModifier(visible: Boolean): Any =
        method(SIZE_KT_CLASS, HEIGHT_METHOD, 2).invoke(null, modifierInstance(), udp(if (visible) 56 else 0))

    private fun rowModifier(visible: Boolean, height: Int): Any =
        method(SIZE_KT_CLASS, HEIGHT_METHOD, 2).invoke(null, modifierInstance(), udp(if (visible) height else 0))

    private fun clickableModifier(baseModifier: Any, name: String, onClick: () -> Unit): Any =
        method(CLICKABLE_KT_CLASS, CLICKABLE_DEFAULT_METHOD, 9).invoke(
            null,
            baseModifier,
            null,
            null,
            false,
            null,
            null,
            functionProxy(name, FUNCTION0_CLASS) {
                onClick()
                targetUnit()
            },
            28,
            null,
        )

    private fun combinedClickableModifier(
        baseModifier: Any,
        name: String,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ): Any =
        runCatching {
            method(CLICKABLE_KT_CLASS, COMBINED_CLICKABLE_DEFAULT_METHOD, 12).invoke(
                null,
                baseModifier,
                false,
                null,
                null,
                null,
                functionProxy("${name}LongClick", FUNCTION0_CLASS) {
                    onLongClick()
                    targetUnit()
                },
                null,
                false,
                null,
                functionProxy("${name}Click", FUNCTION0_CLASS) {
                    onClick()
                    targetUnit()
                },
                239,
                null,
            )
        }.getOrElse {
            XposedBridge.log("$LOG_PREFIX combined clickable fallback for $name: ${it.stackTraceToString()}")
            clickableModifier(baseModifier, name, onClick)
        }

    private fun listItemDefaultMask(hasSupporting: Boolean, hasTrailing: Boolean): Int {
        var mask = 4 or 16 or 128 or 256
        if (!hasSupporting) mask = mask or 8
        if (!hasTrailing) mask = mask or 32
        return mask
    }

    private fun switchModifier(): Any {
        val height = method(SIZE_KT_CLASS, HEIGHT_METHOD, 2).invoke(null, modifierInstance(), udp(44))
        val scaled = method(SCALE_KT_CLASS, SCALE_METHOD, 2).invoke(null, height, 0.62f)
        return method(ALPHA_KT_CLASS, ALPHA_METHOD, 2).invoke(null, scaled, 1.0f)
    }

    private fun dividerModifier(): Any =
        method(PADDING_KT_CLASS, PADDING_ABSOLUTE_DEFAULT_METHOD, 7).invoke(
            null,
            modifierInstance(),
            udp(16),
            0f,
            0f,
            0f,
            14,
            null,
        )

    private fun transparentListItemColors(composer: Any): Any {
        val colors = staticObject(LIST_ITEM_DEFAULTS_CLASS, "INSTANCE")
        val transparent = staticObject(COLOR_CLASS, "INSTANCE").longMethod(COLOR_TRANSPARENT_METHOD)
        val stable = staticInt(LIST_ITEM_DEFAULTS_CLASS, "\$stable")
        return method(LIST_ITEM_DEFAULTS_CLASS, LIST_ITEM_COLORS_METHOD, 12).invoke(
            colors,
            transparent,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            composer,
            (stable shl 27) or 6,
            510,
        )
    }

    private fun switchColors(composer: Any): Any {
        val switchDefaults = staticObject(SWITCH_DEFAULTS_CLASS, "INSTANCE")
        val scheme = colorScheme(composer)
        val surfaceContainerHighest = scheme.longMethod("getSurfaceContainerHighest")
        val backgroundDim = method(THEME_KT_CLASS, BACKGROUND_DIM_METHOD, 1).invoke(null, scheme) as Long
        val stable = staticInt(SWITCH_DEFAULTS_CLASS, "\$stable")
        return method(SWITCH_DEFAULTS_CLASS, SWITCH_COLORS_METHOD, 20).invoke(
            switchDefaults,
            0L,
            0L,
            0L,
            0L,
            surfaceContainerHighest,
            backgroundDim,
            surfaceContainerHighest,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            composer,
            0,
            stable shl 18,
            65423,
        )
    }

    private fun rememberBooleanState(composer: Any, initialValue: Boolean): Any {
        val remembered = composer.method0("rememberedValue")
        if (remembered !== composerEmpty()) return remembered
        val state = mutableBooleanState(initialValue)
        composer.javaClass.methods.firstOrNull { it.name == "updateRememberedValue" && it.parameterTypes.size == 1 }
            ?.invoke(composer, state)
        return state
    }

    private fun associationExpandedState(initialValue: Boolean): Any {
        associationExpandedUiState?.let { return it }
        return mutableBooleanState(initialValue).also { associationExpandedUiState = it }
    }

    private fun readerExpandedState(initialValue: Boolean): Any {
        readerExpandedUiState?.let { return it }
        return mutableBooleanState(initialValue).also { readerExpandedUiState = it }
    }

    private fun fontExpandedState(initialValue: Boolean): Any {
        fontExpandedUiState?.let { return it }
        return mutableBooleanState(initialValue).also { fontExpandedUiState = it }
    }

    private fun accountExpandedState(initialValue: Boolean): Any {
        accountExpandedUiState?.let { return it }
        return mutableBooleanState(initialValue).also { accountExpandedUiState = it }
    }

    private fun cloudExpandedState(initialValue: Boolean): Any {
        cloudExpandedUiState?.let { return it }
        return mutableBooleanState(initialValue).also { cloudExpandedUiState = it }
    }

    private fun accountSwitchExpandedState(initialValue: Boolean): Any {
        accountSwitchExpandedUiState?.let { return it }
        return mutableBooleanState(initialValue).also { accountSwitchExpandedUiState = it }
    }

    private fun accountDataExportExpandedState(initialValue: Boolean): Any {
        accountDataExportExpandedUiState?.let { return it }
        return mutableBooleanState(initialValue).also { accountDataExportExpandedUiState = it }
    }

    private fun rotationExpandedState(initialValue: Boolean): Any {
        rotationExpandedUiState?.let { return it }
        return mutableBooleanState(initialValue).also { rotationExpandedUiState = it }
    }

    @Volatile private var injectedRouteGetter: java.lang.reflect.Method? = null
    @Volatile private var injectedRouteSetter: java.lang.reflect.Method? = null

    private fun injectedRouteState(initialValue: InjectedRoute): Any {
        injectedRouteUiState?.let { return it }
        val state = mutableState(initialValue).also { injectedRouteUiState = it }
        resolveRouteStateAccessors(state)
        return state
    }

    private fun resolveRouteStateAccessors(state: Any) {
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

    private fun routeStateValue(state: Any): InjectedRoute? {
        if (injectedRouteGetter == null) resolveRouteStateAccessors(state)
        return runCatching { injectedRouteGetter?.invoke(state) as? InjectedRoute }.getOrNull()
    }

    private fun setInjectedRouteState(route: InjectedRoute?) {
        val state = injectedRouteUiState ?: return
        if (injectedRouteSetter == null) resolveRouteStateAccessors(state)
        runCatching { injectedRouteSetter?.invoke(state, route) }
    }

    private fun fontLibraryVersionState(): Any {
        fontLibraryVersionUiState?.let { return it }
        return mutableState(0).also { fontLibraryVersionUiState = it }
    }

    private fun fontLibraryVersionValue(): Int =
        (fontLibraryVersionState().method0("getValue") as? Number)?.toInt() ?: 0

    private fun bumpFontLibraryVersion() {
        val state = fontLibraryVersionState()
        val value = (state.method0("getValue") as? Number)?.toInt() ?: 0
        state.javaClass.methods
            .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
            ?.invoke(state, value + 1)
        synchronized(fontFilesCacheLock) {
            cachedFontFilesVersion = Int.MIN_VALUE
            cachedFontFilesAtMs = 0L
            cachedFontFiles = emptyList()
        }
    }

    private fun onlineSourceVersionState(): Any {
        onlineSourceVersionUiState?.let { return it }
        return mutableState(0).also { onlineSourceVersionUiState = it }
    }

    private fun onlineSourceVersionValue(): Int =
        (onlineSourceVersionState().method0("getValue") as? Number)?.toInt() ?: 0

    private fun bumpOnlineSourceVersion() {
        val state = onlineSourceVersionState()
        val value = (state.method0("getValue") as? Number)?.toInt() ?: 0
        state.javaClass.methods
            .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
            ?.invoke(state, value + 1)
    }

    private fun aiApiVersionState(): Any {
        aiApiVersionUiState?.let { return it }
        return mutableState(0).also { aiApiVersionUiState = it }
    }

    private fun aiApiVersionValue(): Int =
        (aiApiVersionState().method0("getValue") as? Number)?.toInt() ?: 0

    private fun bumpAiApiVersion() {
        val state = aiApiVersionState()
        val value = (state.method0("getValue") as? Number)?.toInt() ?: 0
        state.javaClass.methods
            .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
            ?.invoke(state, value + 1)
    }

    private fun readerHighlightVersionState(): Any {
        readerHighlightVersionUiState?.let { return it }
        return mutableState(0).also { readerHighlightVersionUiState = it }
    }

    private fun readerHighlightVersionValue(): Int =
        (readerHighlightVersionState().method0("getValue") as? Number)?.toInt() ?: 0

    private fun bumpReaderHighlightVersion() {
        val state = readerHighlightVersionState()
        val value = (state.method0("getValue") as? Number)?.toInt() ?: 0
        state.javaClass.methods
            .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
            ?.invoke(state, value + 1)
    }

    private fun profileBackgroundVersionState(): Any {
        profileBackgroundVersionUiState?.let { return it }
        return mutableState(0).also { profileBackgroundVersionUiState = it }
    }

    private fun profileBackgroundVersionValue(): Int =
        (profileBackgroundVersionState().method0("getValue") as? Number)?.toInt() ?: 0

    private fun bumpProfileBackgroundVersion() {
        val state = profileBackgroundVersionState()
        val value = (state.method0("getValue") as? Number)?.toInt() ?: 0
        state.javaClass.methods
            .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
            ?.invoke(state, value + 1)
    }

    private fun accountListVersionState(): Any {
        accountListVersionUiState?.let { return it }
        return mutableState(0).also { accountListVersionUiState = it }
    }

    private fun accountListVersionValue(): Int =
        (accountListVersionState().method0("getValue") as? Number)?.toInt() ?: 0

    private fun bumpAccountListVersion() {
        val state = accountListVersionState()
        val value = (state.method0("getValue") as? Number)?.toInt() ?: 0
        state.javaClass.methods
            .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
            ?.invoke(state, value + 1)
    }

    private fun pendingDeleteFontState(): Any {
        pendingDeleteFontUiState?.let { return it }
        return mutableState("").also { pendingDeleteFontUiState = it }
    }

    private fun pendingDeleteFontSelection(): String =
        pendingDeleteFontState().method0("getValue") as? String ?: ""

    private fun setPendingDeleteFontSelection(selection: String) {
        val state = pendingDeleteFontState()
        state.javaClass.methods
            .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
            ?.invoke(state, selection)
    }

    private fun clearPendingDeleteFontSelection() {
        val state = pendingDeleteFontUiState ?: return
        state.javaClass.methods
            .firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
            ?.invoke(state, "")
    }

    private fun resolvePreviewFontFamily(selection: String): Any? {
        if (selection.isBlank()) return null
        synchronized(previewFontFamilyCache) {
            previewFontFamilyCache[selection]?.let { return it }
        }
        resolveBuiltinFontFamily(selection)?.let { family ->
            synchronized(previewFontFamilyCache) {
                previewFontFamilyCache[selection] = family
            }
            return family
        }
        val file = File(selection)
        if (!file.isFile || !isFontFileName(file.name)) return null
        return runCatching {
            val provider = staticObject(FONT_PROVIDER_CLASS, "INSTANCE")
            val normal = fontWeight("getNormal")
            val bold = fontWeight("getBold")
            val fromPath = method(FONT_PROVIDER_CLASS, "fromPath", 2)
            val fonts = listOfNotNull(
                fromPath.invoke(provider, file.absolutePath, normal),
                fromPath.invoke(provider, file.absolutePath, bold),
            )
            if (fonts.isEmpty()) return null
            val family = cls(FONT_FAMILY_KT_CLASS).declaredMethods.first {
                it.name == "FontFamily" &&
                    it.parameterTypes.size == 1 &&
                    List::class.java.isAssignableFrom(it.parameterTypes[0])
            }.invoke(null, fonts)
            synchronized(previewFontFamilyCache) {
                previewFontFamilyCache[selection] = family
            }
            family
        }.onFailure { logResolvePreviewFontFailure(selection, it) }.getOrNull()
    }

    private fun resolveBuiltinFontFamily(selection: String): Any? =
        runCatching {
            when (selection) {
                FAMILY_SYSTEM -> resolveDefaultFontFamily()
                FAMILY_SOURCE_HAN_SERIF -> staticObject(FONT_PROVIDER_CLASS, "INSTANCE").method0("builtInSong")
                else -> null
            }
        }.onFailure { logResolvePreviewFontFailure(selection, it) }.getOrNull()

    private fun resolveDefaultFontFamily(): Any? {
        fieldObjectOrNull(FONT_FAMILY_CLASS, "Default")?.let { return it }
        for (fieldName in listOf("Companion", "INSTANCE")) {
            val companion = fieldObjectOrNull(FONT_FAMILY_CLASS, fieldName) ?: continue
            callMethod(companion, "getDefault")?.let { return it }
        }
        return staticMethod(FONT_FAMILY_CLASS, "getDefault")?.invoke(null)
    }

    private fun fontWeight(methodName: String): Any {
        val clazz = cls(FONT_WEIGHT_CLASS)
        companionFontWeight(clazz, methodName)?.let { return it }
        val (fieldName, weight) = when (methodName) {
            "getBold" -> "Bold" to 700
            else -> "Normal" to 400
        }
        staticFontWeight(clazz, fieldName)?.let { return it }
        return clazz.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(weight)
    }

    private fun companionFontWeight(clazz: Class<*>, methodName: String): Any? {
        for (fieldName in listOf("INSTANCE", "Companion")) {
            val companion = runCatching {
                clazz.getDeclaredField(fieldName).apply { isAccessible = true }.get(null)
            }.recoverCatching {
                clazz.getField(fieldName).apply { isAccessible = true }.get(null)
            }.getOrNull() ?: continue
            val method = companion.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.isEmpty()
            } ?: continue
            return method.invoke(companion)
        }
        return null
    }

    private fun staticFontWeight(clazz: Class<*>, fieldName: String): Any? =
        runCatching {
            clazz.getDeclaredField(fieldName).apply { isAccessible = true }.get(null)
        }.recoverCatching {
            clazz.getField(fieldName).apply { isAccessible = true }.get(null)
        }.getOrNull()

    private fun logResolvePreviewFontFailure(selection: String, error: Throwable) {
        val key = "$selection|${error.javaClass.name}|${error.message}"
        synchronized(failedPreviewFontFamilyLogKeys) {
            if (!failedPreviewFontFamilyLogKeys.add(key)) return
        }
        XposedBridge.log(
            "$LOG_PREFIX failed to resolve preview font: " +
                "${displayFontName(selection)}: ${error.javaClass.simpleName}: ${error.message}",
        )
    }

    private fun mutableBooleanState(initialValue: Boolean): Any =
        method(SNAPSHOT_STATE_KT_CLASS, MUTABLE_STATE_OF_DEFAULT_METHOD, 4).invoke(
            null,
            initialValue,
            null,
            2,
            null,
        )

    private fun mutableState(initialValue: Any?): Any =
        method(SNAPSHOT_STATE_KT_CLASS, MUTABLE_STATE_OF_DEFAULT_METHOD, 4).invoke(
            null,
            initialValue,
            null,
            2,
            null,
        )

    private fun booleanStateValue(state: Any): Boolean =
        state.method0("getValue") as? Boolean ?: false

    private fun setBooleanState(state: Any, value: Boolean) {
        state.javaClass.methods.firstOrNull { it.name == "setValue" && it.parameterTypes.size == 1 }
            ?.invoke(state, value)
    }

    private fun colorScheme(composer: Any): Any {
        val materialTheme = staticObject(MATERIAL_THEME_CLASS, "INSTANCE")
        val stable = staticInt(MATERIAL_THEME_CLASS, "\$stable")
        return method(MATERIAL_THEME_CLASS, "getColorScheme", 2).invoke(materialTheme, composer, stable)
    }

    private fun typography(composer: Any): Any {
        val materialTheme = staticObject(MATERIAL_THEME_CLASS, "INSTANCE")
        val stable = staticInt(MATERIAL_THEME_CLASS, "\$stable")
        return method(MATERIAL_THEME_CLASS, "getTypography", 2).invoke(materialTheme, composer, stable)
    }

    private fun backgroundDim(composer: Any): Long =
        method(THEME_KT_CLASS, BACKGROUND_DIM_METHOD, 1).invoke(null, colorScheme(composer)) as Long

    private fun spacedBy(value: Int): Any {
        val arrangement = staticObject(ARRANGEMENT_CLASS, "INSTANCE")
        return arrangement.javaClass.methods.first {
            it.name == SPACED_BY_METHOD && it.parameterTypes.size == 1
        }.invoke(arrangement, udp(value))
    }

    private fun arrangementTop(): Any =
        staticObject(ARRANGEMENT_CLASS, "INSTANCE").method0("getTop")

    private fun alignmentStart(): Any =
        staticObject(ALIGNMENT_CLASS, "INSTANCE").method0("getStart")

    private fun modifierInstance(): Any =
        staticObject(MODIFIER_CLASS, "INSTANCE")

    private fun composerEmpty(): Any =
        staticObject(COMPOSER_CLASS, "INSTANCE").method0("getEmpty")

    private fun udp(value: Int): Float =
        cls(UNIT_EXT_KT_CLASS).declaredMethods.first {
            it.name == UDP_METHOD && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }.invoke(null, value) as Float

    private fun udp(value: Double): Float =
        cls(UNIT_EXT_KT_CLASS).declaredMethods.first {
            it.name == UDP_METHOD && it.parameterTypes.contentEquals(arrayOf(Double::class.javaPrimitiveType))
        }.invoke(null, value) as Float

    private fun composableLambda(key: Int, functionClassName: String, block: (Array<Any?>?) -> Any?): Any =
        method(COMPOSABLE_LAMBDA_KT_CLASS, COMPOSABLE_LAMBDA_METHOD, 3).invoke(
            null,
            key,
            true,
            functionProxy("Composable$key", functionClassName, block),
        )

    private fun functionProxy(name: String, functionClassName: String, block: (Array<Any?>?) -> Any?): Any {
        val functionClass = cls(functionClassName)
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

    private fun cls(className: String): Class<*> =
        XposedHelpers.findClass(className, classLoader)

    private fun method(className: String, methodName: String, parameterCount: Int): Method {
        val cacheKey = "$className#$methodName/$parameterCount"
        return synchronized(methodCache) {
            methodCache.getOrPut(cacheKey) {
                cls(className).declaredMethods.firstOrNull {
                    it.name == methodName && it.parameterTypes.size == parameterCount
                }?.apply { isAccessible = true }
                    ?: error("$className.$methodName/$parameterCount not found")
            }
        }
    }

    private fun staticObject(className: String, fieldName: String): Any {
        val clazz = cls(className)
        val field = runCatching { clazz.getDeclaredField(fieldName) }
            .recoverCatching { clazz.getField(fieldName) }
            .recoverCatching { clazz.getDeclaredField("Companion") }
            .recoverCatching {
                clazz.declaredClasses.firstNotNullOfOrNull { inner ->
                    inner.declaredFields.firstOrNull { it.name == "\$\$INSTANCE" }?.apply { isAccessible = true }?.get(null)
                }?.let { companion ->
                    return companion
                }
                throw it
            }
            .getOrElse {
                val fields = clazz.declaredFields.joinToString { field -> "${field.name}:${field.type.name}" }
                error("$className.$fieldName not found; fields=[$fields]")
            }
        field.isAccessible = true
        return field.get(null)
    }

    private fun fieldObjectOrNull(className: String, fieldName: String): Any? =
        runCatching {
            val clazz = cls(className)
            val field = runCatching { clazz.getDeclaredField(fieldName) }
                .recoverCatching { clazz.getField(fieldName) }
                .getOrThrow()
            field.isAccessible = true
            field.get(null)
        }.getOrNull()

    private fun callMethod(target: Any?, name: String): Any? {
        if (target == null) return null
        return target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.isEmpty()
        }?.invoke(target)
    }

    private fun staticMethod(className: String, name: String): Method? =
        cls(className).methods.firstOrNull {
            it.name == name && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }

    private fun staticInt(className: String, fieldName: String): Int =
        cls(className).getDeclaredField(fieldName).apply { isAccessible = true }.getInt(null)

    private fun Any.method0(name: String): Any =
        javaClass.methods.first {
            it.parameterTypes.isEmpty() && (it.name == name || it.name.startsWith("$name-"))
        }.invoke(this)

    private fun Any.longMethod(name: String): Long =
        method0(name) as Long

    private fun targetUnit(): Any? = runCatching {
        staticObject("kotlin.Unit", "INSTANCE")
    }.getOrNull()

    private data class ToggleRow(
        val key: String,
        val title: String,
        val checked: Boolean,
        val checkedProvider: () -> Boolean = { checked },
        val visibleProvider: () -> Boolean = { true },
        val syncWithSnapshot: Boolean = false,
        val onChanged: (Boolean, (String, Boolean) -> Unit) -> Boolean,
    )

    private data class ActionRow(
        val key: String,
        val title: String,
        val subtitle: String? = null,
        val trailing: String? = null,
        val trailingContent: ((Any) -> Unit)? = null,
        val titleFontSelection: String? = null,
        val singleLineSubtitle: Boolean = false,
        val onClick: (() -> Unit)? = null,
        val onLongClick: (() -> Unit)? = null,
    )

    private data class FontSelectionOption(
        val value: String,
        val title: String,
        val subtitle: String,
    )

    private data class HighlightColorOption(
        val value: String,
        val title: String,
    )

    private data class ReaderHighlightBookGroupRow(
        val bookKey: String,
        val bookTitle: String,
        val subtitle: String,
    )

    private data class ReaderHighlightImport(
        val styles: List<ReaderHighlightStyle>,
        val rules: List<ReaderHighlightRule>,
        val reeden: Boolean,
        val skippedDuplicates: Int = 0,
    )

    private enum class FontPickerTarget(
        val title: String,
        val clearTitle: String,
        val clearSubtitle: String,
    ) {
        Global(
            title = "\u5168\u5c40\u5b57\u4f53",
            clearTitle = "\u8ddf\u968f\u9605\u5fae",
            clearSubtitle = "\u4e0d\u4f7f\u7528\u5168\u5c40\u5b57\u4f53",
        ),
        SongMapping(
            title = "\u5b8b\u4f53\u6620\u5c04",
            clearTitle = "\u4e0d\u6620\u5c04\u5b8b\u4f53",
            clearSubtitle = "\u4f7f\u7528\u9605\u5fae\u5185\u7f6e\u5b8b\u4f53",
        ),
        KaiMapping(
            title = "\u6977\u4f53\u6620\u5c04",
            clearTitle = "\u4e0d\u6620\u5c04\u6977\u4f53",
            clearSubtitle = "\u4f7f\u7528\u9605\u5fae\u5185\u7f6e\u6977\u4f53",
        ),
        DialogueHighlight(
            title = "\u5bf9\u8bdd\u5b57\u4f53",
            clearTitle = "\u8ddf\u968f\u5168\u5c40\u5b57\u4f53",
            clearSubtitle = "\u4f7f\u7528\u5b57\u4f53\u8865\u5168\u91cc\u7684\u5168\u5c40\u5b57\u4f53",
        ),
    }

    private sealed class InjectedRoute(val title: String) {
        object ModuleSettings : InjectedRoute(MODULE_ENTRY_TITLE)
        object AssociationCompletionSettings : InjectedRoute("\u5173\u8054\u8865\u5168")
        object ReaderCompletionSettings : InjectedRoute("\u9605\u8bfb\u8865\u5168")
        object ReaderHighlightSettings : InjectedRoute(READER_HIGHLIGHT_SETTINGS_TITLE)
        object ReaderHighlightConfigSettings : InjectedRoute("\u9ad8\u4eae\u6837\u5f0f")
        object ReaderHighlightTextSettings : InjectedRoute("\u9ad8\u4eae\u89c4\u5219")
        data class ReaderBookHighlightRules(val bookKey: String, val bookTitle: String) : InjectedRoute(bookTitle)
        data class ReaderBookOnlyHighlightRules(val bookKey: String, val bookTitle: String) : InjectedRoute("\u5355\u4e66\u9ad8\u4eae\u89c4\u5219")
        data class ReaderBookGlobalHighlightRules(val bookKey: String, val bookTitle: String) : InjectedRoute("\u8ddf\u968f\u5168\u5c40")
        object ReaderHighlightColorPicker : InjectedRoute("\u5bf9\u8bdd\u989c\u8272")
        object ProfileBackgroundSettings : InjectedRoute("\u4e3b\u9875\u8865\u5168")
        object CloudCompletionSettings : InjectedRoute("\u4e91\u76d8\u8865\u5168")
        object RotationCompletionSettings : InjectedRoute("\u65cb\u8f6c\u8865\u5168")
        object AccountSwitch : InjectedRoute(ACCOUNT_SWITCH_TITLE)
        object OnlineCompletionSettings : InjectedRoute(ONLINE_COMPLETION_TITLE)
        object AiConfigSettings : InjectedRoute(AI_CONFIG_TITLE)
        object DictionarySettings : InjectedRoute(DICTIONARY_SETTINGS_TITLE)
        object DictionaryApiPicker : InjectedRoute("\u0041\u0050\u0049 \u914d\u7f6e")
        object DictionaryPresetPicker : InjectedRoute("\u8bcd\u5178\u9884\u8bbe")
        object ImageSettings : InjectedRoute(IMAGE_SETTINGS_TITLE)
        object ImageApiPicker : InjectedRoute("\u0041\u0050\u0049 \u914d\u7f6e")
        data class ImagePresetPicker(val target: AiImagePresetTarget) : InjectedRoute(target.title)
        object FontSettings : InjectedRoute(FONT_SETTINGS_TITLE)
        data class FontPicker(val target: FontPickerTarget) : InjectedRoute(target.title)
        object FontLibrary : InjectedRoute(FONT_LIBRARY_TITLE)
        object AboutCompletion : InjectedRoute(ABOUT_COMPLETION_TITLE)
    }

    private val MODULE_CHILD_ROUTES = setOf(
        InjectedRoute.AssociationCompletionSettings,
        InjectedRoute.ReaderCompletionSettings,
        InjectedRoute.CloudCompletionSettings,
        InjectedRoute.RotationCompletionSettings,
        InjectedRoute.OnlineCompletionSettings,
        InjectedRoute.AiConfigSettings,
        InjectedRoute.FontSettings,
        InjectedRoute.AboutCompletion,
        InjectedRoute.ProfileBackgroundSettings,
    )

    private val READER_CHILD_ROUTES = setOf(
        InjectedRoute.ReaderHighlightSettings,
        InjectedRoute.ReaderHighlightConfigSettings,
        InjectedRoute.ReaderHighlightTextSettings,
        InjectedRoute.ReaderHighlightColorPicker,
        InjectedRoute.FontPicker(FontPickerTarget.DialogueHighlight),
    )

    private val AI_CHILD_ROUTES = setOf(
        InjectedRoute.DictionarySettings,
        InjectedRoute.DictionaryApiPicker,
        InjectedRoute.DictionaryPresetPicker,
        InjectedRoute.ImageSettings,
        InjectedRoute.ImageApiPicker,
        InjectedRoute.ImagePresetPicker(AiImagePresetTarget.Cover),
        InjectedRoute.ImagePresetPicker(AiImagePresetTarget.Banner),
    )

    private data class RotationUiState(
        val autoEnabled: Boolean,
        val portraitLockEnabled: Boolean,
        val landscapeLockEnabled: Boolean,
        val reverseEnabled: Boolean,
    ) {
        fun isEnabled(key: String): Boolean =
            when (key) {
                ModuleSettings.KEY_ROTATION_AUTO_ENABLED -> autoEnabled
                ModuleSettings.KEY_ROTATION_PORTRAIT_LOCK_ENABLED -> portraitLockEnabled
                ModuleSettings.KEY_ROTATION_LANDSCAPE_LOCK_ENABLED -> landscapeLockEnabled
                ModuleSettings.KEY_ROTATION_REVERSE_ENABLED -> reverseEnabled
                else -> false
            }

        fun withBase(key: String, enabled: Boolean): RotationUiState =
            copy(
                autoEnabled = enabled && key == ModuleSettings.KEY_ROTATION_AUTO_ENABLED,
                portraitLockEnabled = enabled && key == ModuleSettings.KEY_ROTATION_PORTRAIT_LOCK_ENABLED,
                landscapeLockEnabled = enabled && key == ModuleSettings.KEY_ROTATION_LANDSCAPE_LOCK_ENABLED,
            )

        companion object {
            fun from(snapshot: com.reamicro.fix.settings.ModuleSettingsSnapshot): RotationUiState =
                RotationUiState(
                    autoEnabled = snapshot.rotation.autoEnabled,
                    portraitLockEnabled = snapshot.rotation.portraitLockEnabled,
                    landscapeLockEnabled = snapshot.rotation.landscapeLockEnabled,
                    reverseEnabled = snapshot.rotationReverseEnabled,
                )

            fun fromActivityOrientation(
                requestedOrientation: Int?,
                defaultReverseEnabled: Boolean,
            ): RotationUiState? =
                when (requestedOrientation) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR -> RotationUiState(
                        autoEnabled = true,
                        portraitLockEnabled = false,
                        landscapeLockEnabled = false,
                        reverseEnabled = false,
                    )
                    ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR -> RotationUiState(
                        autoEnabled = true,
                        portraitLockEnabled = false,
                        landscapeLockEnabled = false,
                        reverseEnabled = true,
                    )
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> RotationUiState(
                        autoEnabled = false,
                        portraitLockEnabled = true,
                        landscapeLockEnabled = false,
                        reverseEnabled = false,
                    )
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> RotationUiState(
                        autoEnabled = false,
                        portraitLockEnabled = true,
                        landscapeLockEnabled = false,
                        reverseEnabled = true,
                    )
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> RotationUiState(
                        autoEnabled = false,
                        portraitLockEnabled = false,
                        landscapeLockEnabled = true,
                        reverseEnabled = false,
                    )
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> RotationUiState(
                        autoEnabled = false,
                        portraitLockEnabled = false,
                        landscapeLockEnabled = true,
                        reverseEnabled = true,
                    )
                    else -> if (defaultReverseEnabled) {
                        RotationUiState(
                            autoEnabled = false,
                            portraitLockEnabled = false,
                            landscapeLockEnabled = false,
                            reverseEnabled = true,
                        )
                    } else {
                        null
                    }
                }
        }
    }

    companion object {
        @Volatile private var activeInstance: ReaMicroSettingsHook? = null

        fun openReaderBookGlobalHighlightRulesFromReader(
            bookKey: String,
            bookTitle: String,
            navGraphScope: Any?,
        ): Boolean =
            activeInstance?.openInjectedRouteViaHostNavigation(
                InjectedRoute.ReaderBookGlobalHighlightRules(bookKey, bookTitle),
                navGraphScope,
            ) ?: false

        fun openReaderBookOnlyHighlightRulesFromReader(
            bookKey: String,
            bookTitle: String,
            navGraphScope: Any?,
        ): Boolean =
            activeInstance?.openInjectedRouteViaHostNavigation(
                InjectedRoute.ReaderBookOnlyHighlightRules(bookKey, bookTitle),
                navGraphScope,
            ) ?: false

        fun renderReaderHighlightRulesSheetFromReader(
            globalRules: Boolean,
            bookKey: String,
            bookTitle: String,
            composer: Any,
            onClose: () -> Unit,
        ): Boolean =
            activeInstance?.runCatching {
                renderReaderHighlightRulesSheetFromReader(
                    globalRules = globalRules,
                    bookKey = bookKey,
                    bookTitle = bookTitle,
                    composer = composer,
                    onClose = onClose,
                )
                true
            }?.onFailure {
                XposedBridge.log("$LOG_PREFIX reader highlight rules sheet render failed: ${it.stackTraceToString()}")
            }?.getOrDefault(false) ?: false

        const val LOG_PREFIX = "ReaMicro LSP"

        const val SETTINGS_SCREEN_CLASS = "app.zhendong.reamicro.ui.setting.SettingsScreenKt"
        // 2.2 更名后的设置列表构建 lambda；旧版名称不存在时会回退到签名匹配。
        const val SETTINGS_LIST_BUILDER_METHOD = "SettingsScreen\$lambda\$0\$1\$0\$0"
        const val ACCOUNT_SECURITY_SCREEN_CLASS = "app.zhendong.reamicro.ui.setting.AccountSecurityScreenKt"
        const val ACCOUNT_SECURITY_DELETE_CONTENT_METHOD = "AccountSecurityScreen\$lambda\$0\$0\$4\$0\$2"
        const val ACCOUNT_SECURITY_DELETE_ITEM_METHOD = "DeleteAccountItem"
        const val NAV_GRAPH_SCOPE_CLASS = "app.zhendong.reamicro.NavGraphScope"
        const val NAV_CONTROLLER_CLASS = "androidx.navigation.NavController"
        const val ROUTE_ABOUT_CLASS = "app.zhendong.reamicro.Route\$About"
        const val BACK_HANDLER_KT_CLASS = "androidx.activity.compose.BackHandlerKt"
        const val BACK_HANDLER_METHOD = "BackHandler"
        const val NAVIGATION_EVENT_INFO_NONE_CLASS = "androidx.navigationevent.NavigationEventInfo\$None"
        const val REMEMBER_NAVIGATION_EVENT_STATE_KT_CLASS = "androidx.navigationevent.compose.RememberNavigationEventStateKt"
        const val REMEMBER_NAVIGATION_EVENT_STATE_METHOD = "rememberNavigationEventState"
        const val NAVIGATION_EVENT_HANDLER_KT_CLASS = "androidx.navigationevent.compose.NavigationEventHandlerKt"
        const val NAVIGATION_BACK_HANDLER_METHOD = "NavigationBackHandler"
        const val ABOUT_SCREEN_CLASS = "app.zhendong.reamicro.ui.setting.AboutScreenKt"
        const val ABOUT_SCREEN_METHOD = "AboutScreen"
        const val APP_ABOUT_CLASS = "app.zhendong.reamicro.ui.setting.components.AppAboutKt"
        const val APP_ABOUT_METHOD = "AppAbout"
        const val APP_TOP_BAR_CLASS = "app.zhendong.reamicro.arch.components.AppTopBarKt"
        const val APP_TOP_BAR_METHOD = "AppTopBar"
        const val TOP_APP_BAR_DEFAULTS_CLASS = "androidx.compose.material3.TopAppBarDefaults"
        const val WINDOW_INSETS_CLASS = "androidx.compose.foundation.layout.WindowInsets"
        const val WINDOW_INSETS_KT_CLASS = "androidx.compose.foundation.layout.WindowInsetsKt"
        const val WINDOW_INSETS_EXT_ANDROID_KT_CLASS = "app.zhendong.reamicro.ui.insets.WindowInsetsExt_androidKt"
        const val EVA_ICONS_CLASS = "compose.icons.EvaIcons"
        const val EVA_OUTLINE_KT_CLASS = "compose.icons.evaicons.__OutlineKt"
        const val EVA_CLOSE_KT_CLASS = "compose.icons.evaicons.outline.CloseKt"

        const val SCAFFOLD_KT_CLASS = "androidx.compose.material3.ScaffoldKt"
        const val SCAFFOLD_METHOD = "Scaffold-TvnljyQ"
        const val LAZY_DSL_KT_CLASS = "androidx.compose.foundation.lazy.LazyDslKt"
        const val LAZY_COLUMN_METHOD = "LazyColumn"
        const val LAZY_LIST_SCOPE_CLASS = "androidx.compose.foundation.lazy.LazyListScope"
        const val LAZY_ITEM_DEFAULT_METHOD = "item\$default"
        const val COLUMN_KT_CLASS = "androidx.compose.foundation.layout.ColumnKt"
        const val COLUMN_METHOD = "Column"
        const val LIST_ITEM_KT_CLASS = "androidx.compose.material3.ListItemKt"
        const val LIST_ITEM_METHOD = "ListItem-HXNGIdc"
        const val LIST_ITEM_DEFAULTS_CLASS = "androidx.compose.material3.ListItemDefaults"
        const val LIST_ITEM_COLORS_METHOD = "colors-J08w3-E"
        const val SWITCH_KT_CLASS = "androidx.compose.material3.SwitchKt"
        const val SWITCH_METHOD = "Switch"
        const val SWITCH_DEFAULTS_CLASS = "androidx.compose.material3.SwitchDefaults"
        const val SWITCH_COLORS_METHOD = "colors-V1nXRL4"
        const val TEXT_KT_CLASS = "androidx.compose.material3.TextKt"
        const val TEXT_METHOD = "Text-Nvy7gAk"
        const val DIVIDER_KT_CLASS = "app.zhendong.reamicro.arch.components.DividerKt"
        const val DASHED_DIVIDER_METHOD = "DashedHorizontalDivider-aM-cp0Q"

        const val SIZE_KT_CLASS = "androidx.compose.foundation.layout.SizeKt"
        const val FILL_MAX_SIZE_DEFAULT_METHOD = "fillMaxSize\$default"
        const val FILL_MAX_WIDTH_DEFAULT_METHOD = "fillMaxWidth\$default"
        const val HEIGHT_METHOD = "height-3ABfNKs"
        const val PADDING_KT_CLASS = "androidx.compose.foundation.layout.PaddingKt"
        const val PADDING_VALUES_METHOD = "padding"
        const val PADDING_HORIZONTAL_DEFAULT_METHOD = "padding-VpY3zN4\$default"
        const val PADDING_ABSOLUTE_DEFAULT_METHOD = "padding-qDBjuR0\$default"
        const val BACKGROUND_KT_CLASS = "androidx.compose.foundation.BackgroundKt"
        const val BACKGROUND_DEFAULT_METHOD = "background-bw27NRU\$default"
        const val BORDER_KT_CLASS = "androidx.compose.foundation.BorderKt"
        const val BORDER_METHOD = "border-xT4_qwU"
        const val CLIP_KT_CLASS = "androidx.compose.ui.draw.ClipKt"
        const val CLIP_METHOD = "clip"
        const val SCALE_KT_CLASS = "androidx.compose.ui.draw.ScaleKt"
        const val SCALE_METHOD = "scale"
        const val ALPHA_KT_CLASS = "androidx.compose.ui.draw.AlphaKt"
        const val ALPHA_METHOD = "alpha"
        const val CLICKABLE_KT_CLASS = "androidx.compose.foundation.ClickableKt"
        const val CLICKABLE_DEFAULT_METHOD = "clickable-O2vRcR0\$default"
        const val COMBINED_CLICKABLE_DEFAULT_METHOD = "combinedClickable-hoGz1lA\$default"
        const val SHAPE_KT_CLASS = "app.zhendong.reamicro.arch.components.ShapeKt"
        const val ROUNDED_SHAPE_METHOD = "getRoundedShape"
        const val THEME_KT_CLASS = "app.zhendong.reamicro.arch.theme.ThemeKt"
        const val BACKGROUND_AUTO_METHOD = "getBackgroundAuto"
        const val BACKGROUND_DIM_METHOD = "getBackgroundDim"
        const val BORDER_VARIANT_METHOD = "getBorderVariant"
        const val MATERIAL_THEME_CLASS = "androidx.compose.material3.MaterialTheme"
        const val COLOR_CLASS = "androidx.compose.ui.graphics.Color"
        const val COLOR_TRANSPARENT_METHOD = "getTransparent-0d7_KjU"
        const val FONT_PROVIDER_CLASS = "org.epub.FontProvider"
        const val FONT_FAMILY_CLASS = "androidx.compose.ui.text.font.FontFamily"
        const val FONT_FAMILY_KT_CLASS = "androidx.compose.ui.text.font.FontFamilyKt"
        const val FONT_WEIGHT_CLASS = "androidx.compose.ui.text.font.FontWeight"
        const val MODIFIER_CLASS = "androidx.compose.ui.Modifier"
        const val READER_RULE_SHEET_HEIGHT_DP = 355
        const val ARRANGEMENT_CLASS = "androidx.compose.foundation.layout.Arrangement"
        const val SPACED_BY_METHOD = "spacedBy-0680j_4"
        const val ALIGNMENT_CLASS = "androidx.compose.ui.Alignment"
        const val UNIT_EXT_KT_CLASS = "app.zhendong.reamicro.arch.extensions.UnitExtKt"
        const val UDP_METHOD = "getUdp"
        const val COMPOSER_CLASS = "androidx.compose.runtime.Composer"
        const val SNAPSHOT_STATE_KT_CLASS = "androidx.compose.runtime.SnapshotStateKt__SnapshotStateKt"
        const val MUTABLE_STATE_OF_DEFAULT_METHOD = "mutableStateOf\$default"
        const val COMPOSABLE_LAMBDA_KT_CLASS = "androidx.compose.runtime.internal.ComposableLambdaKt"
        const val COMPOSABLE_LAMBDA_METHOD = "composableLambdaInstance"
        const val STRING_RESOURCES_CLASS = "org.jetbrains.compose.resources.StringResourcesKt"
        const val STRING_RESOURCE_METHOD = "stringResource"
        const val FUNCTION0_CLASS = "kotlin.jvm.functions.Function0"
        const val FUNCTION1_CLASS = "kotlin.jvm.functions.Function1"
        const val FUNCTION2_CLASS = "kotlin.jvm.functions.Function2"
        const val FUNCTION3_CLASS = "kotlin.jvm.functions.Function3"
        const val USER_REPOSITORY_CLASS = "app.zhendong.reamicro.repository.UserRepository"
        const val USER_REPOSITORY_SIGN_OUT_METHOD = "signOut"

        const val INSERT_AFTER_SETTINGS_ITEM_COUNT = 2
        const val INSERT_BEFORE_SIGN_OUT_ITEM_COUNT = 3
        const val ACCOUNT_SETTINGS_ITEM_KEY = 0x524D4657
        const val MODULE_SETTINGS_ITEM_KEY = 0x524D4658
        const val MODULE_TOP_BAR_KEY = 0x524D4659
        const val MODULE_CONTENT_KEY = 0x524D465A
        const val MODULE_SWITCH_ITEM_KEY = 0x524D465B
        const val ASSOCIATION_SWITCHES_ITEM_KEY = 0x524D465C
        const val READER_SWITCHES_ITEM_KEY = 0x524D465D
        const val ROTATION_SWITCHES_ITEM_KEY = 0x524D465E
        const val CLOUD_SWITCHES_ITEM_KEY = 0x524D465F
        const val FONT_SETTINGS_ITEM_KEY = 0x524D4660
        const val FONT_SWITCHES_ITEM_KEY = 0x524D4661
        const val FONT_SETTINGS_CONTENT_ITEM_KEY = 0x524D4662
        const val FONT_PICKER_CONTENT_ITEM_KEY = 0x524D4663
        const val FONT_LIBRARY_CONTENT_ITEM_KEY = 0x524D4664
        const val ONLINE_COMPLETION_SETTINGS_ITEM_KEY = 0x524D4665
        const val ACCOUNT_COMPLETION_SWITCHES_ITEM_KEY = 0x524D4666
        const val ACCOUNT_EXPORT_ACTION_ITEM_KEY = 0x524D4667
        const val ACCOUNT_IMPORT_ACTION_ITEM_KEY = 0x524D4668
        const val ACCOUNT_SWITCH_ACTION_ITEM_KEY = 0x524D4669
        const val ONLINE_COMPLETION_CONTENT_ITEM_KEY = 0x524D466A
        const val AI_CONFIG_SETTINGS_ITEM_KEY = 0x524D466B
        const val AI_CONFIG_CONTENT_ITEM_KEY = 0x524D466C
        const val DICTIONARY_SETTINGS_CONTENT_ITEM_KEY = 0x524D466D
        const val DICTIONARY_THINKING_SWITCH_ITEM_KEY = 0x524D466E
        const val DICTIONARY_API_PICKER_CONTENT_ITEM_KEY = 0x524D466F
        const val DICTIONARY_PRESET_PICKER_CONTENT_ITEM_KEY = 0x524D4670
        const val IMAGE_SETTINGS_CONTENT_ITEM_KEY = 0x524D4671
        const val IMAGE_API_PICKER_CONTENT_ITEM_KEY = 0x524D4672
        const val IMAGE_PRESET_PICKER_CONTENT_ITEM_KEY = 0x524D4673
        const val READER_HIGHLIGHT_MANAGEMENT_ITEM_KEY = 0x524D4674
        const val READER_HIGHLIGHT_SETTINGS_ITEM_KEY = 0x524D4675
        const val READER_HIGHLIGHT_COLOR_PICKER_ITEM_KEY = 0x524D4676
        const val READER_HIGHLIGHT_CONFIG_ITEM_KEY = 0x524D4677
        const val READER_HIGHLIGHT_TEXT_ITEM_KEY = 0x524D4678
        const val READER_HIGHLIGHT_BOOK_RULES_ITEM_KEY = 0x524D4679
        const val READER_HIGHLIGHT_BOOK_GROUPS_ITEM_KEY = 0x524D467A
        const val ABOUT_COMPLETION_ENTRY_ITEM_KEY = 0x524D467B
        const val ABOUT_COMPLETION_CONTENT_ITEM_KEY = 0x524D467C
        const val PROFILE_BACKGROUND_SWITCHES_ITEM_KEY = 0x524D467D
        const val PROFILE_BACKGROUND_COLOR_PICKER_ITEM_KEY = 0x524D467E
        const val ACCOUNT_CREDENTIAL_DOCUMENT_REQUEST_CODE = 0x524D47
        const val ACCOUNT_DATA_DOCUMENT_REQUEST_CODE = 0x524D48
        const val ONLINE_SOURCE_DOCUMENT_REQUEST_CODE = 0x524D49
        const val HIGHLIGHT_STYLE_DOCUMENT_REQUEST_CODE = 0x524D4A
        const val HIGHLIGHT_NINE_PATCH_DOCUMENT_REQUEST_CODE = 0x524D4B
        const val PROFILE_BACKGROUND_IMAGE_DOCUMENT_REQUEST_CODE = 0x524D4C
        const val ACCOUNT_RESTART_DELAY_MS = 1_400L
        const val ACCOUNT_RESTART_KILL_DELAY_MS = 250L
        const val ACCOUNT_RESTART_COMMAND_DELAY_SECONDS = "0.8"
        const val SWITCH_TRAILING_KEY_MASK = 0x13579BDF
        const val ACTION_SUPPORTING_KEY_MASK = 0x2468ACE0
        const val ACTION_TRAILING_KEY_MASK = 0x0F0F0F0F
        const val TEXT_DEFAULT_MASK = 131066
        const val TEXT_WITH_FONT_FAMILY_MASK = 130938
        const val TEXT_SINGLE_LINE_MASK = 73722
        const val PRESET_PROMPT_PREVIEW_MAX_CHARS = 32
        const val PREVIEW_REEDEN_BOX_EDGE_SCALE = 0.78f
        const val FAMILY_SYSTEM = "system"
        const val FAMILY_SOURCE_HAN_SERIF = "serif"
        const val ONLINE_COMPLETION_TITLE = "在线补全"
        const val AI_CONFIG_TITLE = "API \u914d\u7f6e"
        const val DICTIONARY_SETTINGS_TITLE = "\u8bcd\u5178\u7ba1\u7406"
        const val IMAGE_SETTINGS_TITLE = "\u751f\u56fe\u7ba1\u7406"
        const val READER_HIGHLIGHT_SETTINGS_TITLE = "\u9ad8\u4eae\u7ba1\u7406"
        const val HOST_ABOUT_TITLE = "关于阅微"
        const val MODULE_ENTRY_TITLE = "补全计划"
        const val ABOUT_COMPLETION_TITLE = "关于补全"
        const val FONT_SETTINGS_TITLE = "字体设置"
        const val FONT_LIBRARY_TITLE = "字体库"
        const val FONT_DOCUMENT_REQUEST_CODE = 0x524D46
        const val FONT_IMPORT_DEDUPE_WINDOW_MS = 2_500L
        const val ONLINE_SOURCE_IMPORT_DEDUPE_WINDOW_MS = 2_500L
        const val HOST_PACKAGE_NAME = "app.zhendong.reamicro"
        const val EXTRA_IMPORT_PAYLOAD = "com.reamicro.fix.import.PAYLOAD"
        const val EXTRA_IMPORT_NAME = "com.reamicro.fix.import.NAME"
        const val ONLINE_SOURCE_REMOVE_CONFIRM_WINDOW_MS = 3_000L
        const val FONT_FILES_CACHE_WINDOW_MS = 500L
        private val READER_HIGHLIGHT_COLOR_OPTIONS = listOf(
            HighlightColorOption("#FF9800", "\u6a59\u8272"),
            HighlightColorOption("#F59E0B", "\u6696\u6a59"),
            HighlightColorOption("#D97706", "\u6df1\u6a59"),
            HighlightColorOption("#16A34A", "\u7eff\u8272"),
            HighlightColorOption("#2563EB", "\u84dd\u8272"),
            HighlightColorOption("#9333EA", "\u7d2b\u8272"),
            HighlightColorOption("#DC2626", "\u7ea2\u8272"),
        )
        private val PROFILE_BACKGROUND_CROP_POSITION_OPTIONS = listOf(
            HighlightColorOption(ModuleSettings.PROFILE_BACKGROUND_CROP_TOP, "\u9760\u4e0a"),
            HighlightColorOption(ModuleSettings.PROFILE_BACKGROUND_CROP_CENTER, "\u5c45\u4e2d"),
            HighlightColorOption(ModuleSettings.PROFILE_BACKGROUND_CROP_BOTTOM, "\u9760\u4e0b"),
        )
        private val PROFILE_BACKGROUND_DISPLAY_MODE_OPTIONS = listOf(
            HighlightColorOption(ModuleSettings.PROFILE_BACKGROUND_DISPLAY_COVER, "\u586b\u5145\u5168\u5c4f"),
            HighlightColorOption(ModuleSettings.PROFILE_BACKGROUND_DISPLAY_FIT_WIDTH, "\u9002\u5408\u5bbd\u5ea6"),
            HighlightColorOption(ModuleSettings.PROFILE_BACKGROUND_DISPLAY_FIT_HEIGHT, "\u9002\u5408\u9ad8\u5ea6"),
        )
        const val YOUSHU_LOGIN_URL = "https://m.youshu.me/login.php"
        const val YOUSHU_FAST_LOGIN_VERIFY_ATTEMPTS = 1
        const val YOUSHU_LOGIN_VERIFY_ATTEMPTS = 4
        const val YOUSHU_LOGIN_VERIFY_DELAY_MS = 800L
        const val YOUSHU_LOGIN_STATE_TIMEOUT_MS = 2_000L
        const val ROTATION_SNAPSHOT_SYNC_SUPPRESS_MS = 1_000L
        const val ACCOUNT_SWITCH_TITLE = "切换账号"
        const val YOUSHU_LOGIN_STATE_JS = """
            (function(){
                var cookie = document.cookie || '';
                if (/(^|;\s*)jieqiUserInfo=/.test(cookie)) return true;
                var body = document.body;
                var text = body ? (body.innerText || body.textContent || '') : '';
                var hasPassword = !!document.querySelector('input[type="password"],input[name="password"]');
                var hasLogoutLink = !!document.querySelector('a[href*="logout"],a[href*="login.php?action=logout"],a[href*="login.php?act=logout"]');
                var hasAccountLink = !!document.querySelector('a[href*="userdetail.php"],a[href*="useredit.php"],a[href*="setavatar.php"],a[href*="message.php?box="]');
                var hasLogoutText = /退出|退出登录|登出|注销|logout/i.test(text);
                return (hasLogoutLink || hasLogoutText || hasAccountLink) && !hasPassword;
            })();
        """
    }
}
