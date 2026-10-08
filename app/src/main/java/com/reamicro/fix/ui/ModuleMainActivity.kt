package com.reamicro.fix.ui

import com.reamicro.fix.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import android.Manifest
import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults.flingBehavior
import androidx.compose.foundation.pager.PagerDefaults.pageNestedScrollConnection
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.cloud.local.CloudTaskLocalRunner
import com.reamicro.fix.cloud.local.LocalTask
import com.reamicro.fix.cloud.local.AutoReadTimeLock
import com.reamicro.fix.cloud.local.LocalTaskRecord
import com.reamicro.fix.cloud.local.LocalTaskRunner
import com.reamicro.fix.cloud.local.LocalTaskStore
import com.reamicro.fix.cloud.root.RootModuleStatus
import com.reamicro.fix.cloud.root.RootTaskBridge
import com.reamicro.fix.hook.CLOUD_AUTOMATION_TASKS
import com.reamicro.fix.hook.CloudAutomationTaskSpec
import com.reamicro.fix.logging.ModuleAndroidLog
import com.reamicro.fix.logging.ModuleLogBuffer
import com.reamicro.fix.notification.CloudTaskNotifications
import com.reamicro.fix.notification.NotificationRecordStore
import com.reamicro.fix.notification.cloudTaskQualityColor
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.blendColors
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.pagerGestureOverride
import top.yukonga.miuix.kmp.utils.springAnimateToPage
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val DangerRed = Color(0xFFD03A2B)

class ModuleMainActivity : ComponentActivity() {

    private var launcherIconCoordinates: LayoutCoordinates? = null

    private val tab = mutableIntStateOf(TAB_RECORDS)

    private val recordsState = mutableStateOf<List<Pair<String, LocalTaskRecord>>>(emptyList())
    private val failedCount = mutableIntStateOf(0)
    private val taskRows = mutableStateOf<List<TaskRow>>(emptyList())
    private val accountCount = mutableIntStateOf(0)
    private val overviewText = mutableStateOf("")
    private val hideRecentTask = mutableStateOf(false)

    private val hideLauncherIcon = mutableStateOf(false)

    private val launcherIconStyle = mutableStateOf(ModuleIconStyle.DEFAULT)

    private val launcherIconIssue = mutableStateOf<LauncherIconIssue?>(null)

    private val launcherIcons by lazy {
        LauncherIconController(
            ModuleIconStyle.aliases(),
            ModuleIconStyle.androidComponents(this),
            launcherIconPreferences(),
        )
    }

    private val appLanguage = mutableIntStateOf(0)
    private val languageContext = mutableStateOf<Context?>(null)
    private val uiContext: Context get() = languageContext.value ?: this

    private fun uiText(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) uiContext.getString(id) else uiContext.getString(id, *args)
    private val notificationSummary = mutableStateOf("")
    private val logSummary = mutableStateOf("")
    private val rootUi = mutableStateOf<RootModuleStatus?>(null)
    private val suPathDialog = mutableStateOf<String?>(null)
    private val rootConfirmation = mutableStateOf<RootEnhancementAction?>(null)
    private val rootModuleDialog = mutableStateOf(false)
    private val rootBusy = mutableStateOf(false)
    private val taskMutations = mutableStateOf<Set<Pair<String, String>>>(emptySet())
    private val rootChecking = mutableStateOf(false)
    private val rootSyncing = mutableStateOf(false)
    private val rootSyncedAt = mutableStateOf(0L)
    private val rootSyncError = mutableStateOf("")
    private val rootEnhancementEnabled = mutableStateOf(false)
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        toast(uiText(if (granted) R.string.toast_notification_granted else R.string.permission_notification_denied))
        refresh()
        refresh()
    }

    private val aboutEffectResumed = mutableStateOf(false)
    private val blurBars = mutableStateOf(false)

    private val floatingNavBar = mutableStateOf(false)

    private val liquidGlass = mutableStateOf(true)

    private val themeMode = mutableIntStateOf(THEME_FOLLOW_SYSTEM)

    private val pagerGestureMode = mutableIntStateOf(PagerInterceptionMode.CrossAxisInterceptor.ordinal)

    private val swipeBack = mutableStateOf(true)

    private val predictiveBack = mutableStateOf(false)

    private val textDialog = mutableStateOf<TextDialogUi?>(null)
    private val editorDialog = mutableStateOf<TaskEditor?>(null)

    private val recordsNavigationRequest = mutableIntStateOf(0)

    private val splashExit = ModuleSplashExit(this)

    override fun onDestroy() {
        splashExit.dispose()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        if (savedInstanceState == null) splashExit.install()
        setTheme(com.reamicro.fix.R.style.ModuleTheme)
        super.onCreate(savedInstanceState)
        ModuleLogBuffer.attach(this)
        tab.intValue = savedInstanceState?.getInt(STATE_TAB, TAB_RECORDS) ?: TAB_RECORDS
        themeMode.intValue = uiPrefInt(KEY_THEME_MODE).coerceIn(THEME_FOLLOW_SYSTEM, THEME_DARK)
        syncApplicationNightMode(themeMode.intValue)

        applyPredictiveBack(uiPrefBoolean(KEY_PREDICTIVE_BACK))

        applyLauncherIconResult(launcherIcons.restore())
        updateLanguageContext()
        window?.setBackgroundDrawable(ColorDrawable(if (resolveDark(themeMode.intValue)) DARK_WINDOW_BG else LIGHT_WINDOW_BG))
        ModuleAndroidLog.legacy(LOG_TAG, "module main ui opened")

        consumeNavigationIntent(intent)
        setContent {
            val localizedContext = uiContext
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalResources provides localizedContext.resources,
                LocalLayoutDirection provides if (localizedContext.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL)
                    LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalConfiguration provides Configuration(localizedContext.resources.configuration),
            ) {
                val dark = resolveDark(themeMode.intValue)
                ImmersiveSystemBars(dark)
                SystemBarAppearance(dark)
                MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                    ModuleApp()
                }
            }
        }
    }

    @Composable
    private fun ImmersiveSystemBars(dark: Boolean) {
        DisposableEffect(dark) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ) { dark },
                navigationBarStyle = SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ) { dark },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            onDispose { }
        }
    }

    private fun consumeNavigationIntent(incoming: Intent?) {
        if (incoming?.action != CloudTaskNotifications.ACTION_OPEN_RECORDS) return
        textDialog.value = null
        editorDialog.value = null
        tab.intValue = TAB_RECORDS
        recordsNavigationRequest.intValue++

        incoming.action = Intent.ACTION_MAIN
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNavigationIntent(intent)
        setTaskExcludedFromRecents(hideRecentTaskEnabled())
        if (!isFinishing && !isDestroyed) {
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        aboutEffectResumed.value = true

        rootUi.value = RootTaskBridge.lastInspection

        setTaskExcludedFromRecents(hideRecentTaskEnabled())
        registerRecordObservers()
        refresh()
    }

    override fun onPause() {
        aboutEffectResumed.value = false
        unregisterRecordObservers()
        super.onPause()
    }
    override fun onUserLeaveHint() {
        if (hideRecentTask.value) setTaskExcludedFromRecents(true)
        super.onUserLeaveHint()
    }

    override fun onStop() {
        if (!isChangingConfigurations && hideRecentTask.value) setTaskExcludedFromRecents(true)
        super.onStop()
    }

    private val recordRefreshHandler = Handler(Looper.getMainLooper())
    private var observingRecords = false
    private val refreshRecords = Runnable {
        if (observingRecords && !isFinishing && !isDestroyed) refresh()
    }
    private val recordPrefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (observingRecords) {
                recordRefreshHandler.removeCallbacks(refreshRecords)
                recordRefreshHandler.post(refreshRecords)
            }
        }

    private fun registerRecordObservers() {
        if (observingRecords) return
        observingRecords = true
        getSharedPreferences(LocalTaskStore.PREFS_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(recordPrefsListener)
        getSharedPreferences(NotificationRecordStore.PREFS_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(recordPrefsListener)
    }

    private fun unregisterRecordObservers() {
        observingRecords = false
        getSharedPreferences(LocalTaskStore.PREFS_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(recordPrefsListener)
        getSharedPreferences(NotificationRecordStore.PREFS_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(recordPrefsListener)
        recordRefreshHandler.removeCallbacks(refreshRecords)
    }

    @Composable
    private fun SystemBarAppearance(dark: Boolean) {
        LaunchedEffect(dark) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {

                    val lightMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    window.insetsController?.setSystemBarsAppearance(if (dark) 0 else lightMask, lightMask)
                }
            }
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun refresh() {
        val context = applicationContext
        rootEnhancementEnabled.value = RootTaskBridge.isEnabled(context)
        val store = LocalTaskStore { context }
        val recordsList = store.accountIds()
            .flatMap { accountId -> store.records(accountId).map { accountId to it } }
            .sortedByDescending { (_, record) -> record.at }
        recordsState.value = recordsList
        failedCount.intValue = recordsList.count { (_, record) -> record.result != "success" }

        val tasks = store.accountIds().flatMap { accountId -> store.list(accountId).map { accountId to it } }
        accountCount.intValue = store.accountIds().size
        taskRows.value = tasks.map { (accountId, task) ->
            TaskRow(accountId, task, specOf(task.taskType), taskSubtitle(accountId, task))
        }
        overviewText.value = buildOverviewText(store)

        hideRecentTask.value = hideRecentTaskEnabled()

        applyLauncherIconResult(launcherIcons.inspect(), notify = false)
        blurBars.value = uiPrefBoolean(KEY_BLUR_BARS)
        floatingNavBar.value = uiPrefBoolean(KEY_FLOATING_NAV_BAR)

        liquidGlass.value = uiPrefBoolean(KEY_LIQUID_GLASS, true)
        themeMode.intValue = uiPrefInt(KEY_THEME_MODE).coerceIn(THEME_FOLLOW_SYSTEM, THEME_DARK)
        pagerGestureMode.intValue = uiPrefInt(KEY_PAGER_GESTURE, PagerInterceptionMode.CrossAxisInterceptor.ordinal)
            .coerceIn(0, PagerInterceptionMode.entries.lastIndex)
        swipeBack.value = uiPrefBoolean(KEY_SWIPE_BACK, true)
        predictiveBack.value = uiPrefBoolean(KEY_PREDICTIVE_BACK)

        val notifications = NotificationRecordStore { context }.list()
        notificationSummary.value = if (notifications.isEmpty()) {
            uiText(R.string.notification_empty_summary)
        } else {
            val delivered = notifications.count { it.delivered }
            uiText(R.string.notification_summary, notifications.size, delivered, notifications.size - delivered)
        }
        val logs = ModuleLogBuffer.snapshot()
        val path = ModuleLogBuffer.filePath()
        logSummary.value = uiText(R.string.log_summary, logs.size,
            path?.let { uiText(R.string.log_file_path, it) } ?: uiText(R.string.log_file_unavailable))
    }

    private fun buildOverviewText(store: LocalTaskStore): String {
        val configured = taskRows.value.count { it.task.enabled }
        val next = futureNextRunAt(store)?.let(::formatTime) ?: uiText(R.string.common_none)
        return uiText(R.string.task_overview_body, accountCount.intValue, configured, next) +
            if (!rootEnhancementEnabled.value) "\n" + uiText(R.string.root_manual_only) else ""
    }

    private fun taskSubtitle(accountId: String, task: LocalTask): String {
        val spec = specOf(task.taskType)
        return buildString {
            if (spec != null && spec.blessingOptions.isNotEmpty()) {
                append(uiText(R.string.task_blessing_summary, blessingLabel(task.blessingType)))
                append('\n')
            }
            append(nextRunLine(accountId, task, spec))
            if (task.lastMessage.isNotBlank()) {
                append('\n')
                append(task.lastMessage)
            }
        }
    }

    @Composable
    private fun ModuleApp() {
        val tabTitles = TAB_TITLE_RES.map { stringResource(it) }
        val pagerState = rememberPagerState(initialPage = tab.intValue, pageCount = { TAB_TITLE_RES.size })
        val scope = rememberCoroutineScope()
        val pagerMode = PagerInterceptionMode.entries.getOrElse(pagerGestureMode.intValue) {
            PagerInterceptionMode.Native
        }
        val interceptPager = pagerMode == PagerInterceptionMode.CrossAxisInterceptor

        val overlayOpen = textDialog.value != null || editorDialog.value != null || suPathDialog.value != null ||
            rootModuleDialog.value || rootConfirmation.value != null
        val userScrollEnabled = !overlayOpen
        LaunchedEffect(pagerState.settledPage) {
            tab.intValue = pagerState.settledPage
        }
        BackHandler(enabled = overlayOpen) { dismissTopOverlay() }

        val scrollBehaviors = List(TAB_TITLE_RES.size) { MiuixScrollBehavior() }
        var pullRefreshing by remember { mutableStateOf(false) }
        val blurSupported = remember { isRuntimeShaderSupported() }
        val blurred = blurBars.value && blurSupported
        val floating = floatingNavBar.value
        val liquid = floating && liquidGlass.value && blurSupported
        val surface = MiuixTheme.colorScheme.surface
        val backdrop = rememberLayerBackdrop {
            drawRect(surface)
            drawContent()
        }
        val barBlurColors = BlurColors(
            blendColors = listOf(BlendColorEntry(surface.copy(alpha = BAR_TINT_ALPHA))),
        )

        var selectedPage by remember { mutableIntStateOf(tab.intValue.coerceIn(0, TAB_TITLE_RES.lastIndex)) }
        var isNavigating by remember { mutableStateOf(false) }
        var navJob by remember { mutableStateOf<Job?>(null) }

        var contentReady by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { contentReady = true }

        LaunchedEffect(pagerState.currentPage) {
            if (!isNavigating && selectedPage != pagerState.currentPage) {
                selectedPage = pagerState.currentPage
            }
        }
        LaunchedEffect(recordsNavigationRequest.intValue) {
            if (recordsNavigationRequest.intValue > 0) {
                navJob?.cancel()
                navJob = null
                isNavigating = false
                selectedPage = TAB_RECORDS
                pagerState.scrollToPage(TAB_RECORDS)
            }
        }
        val currentPage = selectedPage.coerceIn(0, TAB_TITLE_RES.lastIndex)
        val scrollBehavior = scrollBehaviors[currentPage]

        val animateToTab: (Int) -> Unit = { index ->
            if (index != selectedPage) {
                navJob?.cancel()
                selectedPage = index
                isNavigating = true
                navJob = scope.launch {
                    val myJob = coroutineContext.job
                    try {
                        pagerState.springAnimateToPage(index)
                    } finally {

                        if (navJob == myJob) {
                            isNavigating = false
                            if (pagerState.currentPage != index) selectedPage = pagerState.currentPage
                        }
                    }
                }
            }
        }
        Scaffold(
            topBar = {

                if (currentPage != TAB_ABOUT) TopAppBar(
                    title = tabTitles[currentPage],
                    largeTitle = tabTitles[currentPage],
                    scrollBehavior = scrollBehavior,
                    color = if (blurred) Color.Transparent else MiuixTheme.colorScheme.surface,
                    modifier = if (blurred) {
                        Modifier.textureBlur(
                            backdrop = backdrop,
                            shape = RectangleShape,
                            blurRadius = BAR_BLUR_RADIUS,
                            colors = barBlurColors,
                        )
                    } else {
                        Modifier
                    },
                )
            },
            bottomBar = {
                if (floating) {
                    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 28.dp)
                            .padding(bottom = if (navInset > 0.dp) 8.dp + navInset else 28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        FloatingBottomBar(

                            modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
                            selectedIndex = currentPage,
                            onSelected = animateToTab,
                            backdrop = backdrop,
                            tabsCount = TAB_ICONS.size,
                            isBlurEnabled = liquid,
                        ) { activateTab ->
                            TAB_ICONS.forEachIndexed { index, icon ->
                                FloatingBottomBarItem(
                                    selected = currentPage == index,
                                    onClick = { activateTab(index) },

                                    modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = tabTitles[index],
                                        tint = top.yukonga.miuix.kmp.theme.LocalContentColor.current,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Text(
                                        text = tabTitles[index],
                                        color = top.yukonga.miuix.kmp.theme.LocalContentColor.current,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Visible,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    NavigationBar(
                        color = if (blurred) Color.Transparent else MiuixTheme.colorScheme.surface,
                        modifier = if (blurred) {
                            Modifier.textureBlur(
                                backdrop = backdrop,
                                shape = RectangleShape,
                                blurRadius = BAR_BLUR_RADIUS,
                                colors = barBlurColors,
                            )
                        } else {
                            Modifier
                        },
                    ) {
                        TAB_ICONS.forEachIndexed { index, icon ->
                            NavigationBarItem(
                                selected = currentPage == index,
                                onClick = { animateToTab(index) },
                                icon = icon,
                                label = tabTitles[index],
                            )
                        }
                    }
                }
            },
        ) { padding ->
            val pagerFling = flingBehavior(
                state = pagerState,
                snapAnimationSpec = PagerNavigationSpringSpec,
            )
            val pagerModifier = (if (interceptPager) {
                Modifier.crossAxisPagerWithExclusion(
                    pagerState = pagerState,
                    enabled = userScrollEnabled,
                    isExcluded = { position ->
                        pagerState.currentPage == TAB_CONFIG &&
                            launcherIconCoordinates?.takeIf { it.isAttached }
                                ?.boundsInWindow()?.contains(position) == true
                    },
                )
            } else {
                Modifier.pagerGestureOverride(
                    pagerState = pagerState,
                    mode = pagerMode,
                    enabled = userScrollEnabled,
                )
            }).then(if (blurred || floating) Modifier.layerBackdrop(backdrop) else Modifier)
            HorizontalPager(
                modifier = pagerModifier,
                state = pagerState,

                beyondViewportPageCount = if (contentReady) 3 else 0,
                userScrollEnabled = userScrollEnabled && !interceptPager,
                overscrollEffect = null,
                pageNestedScrollConnection = if (interceptPager) {
                    PagerGestureNestedScrollConnection
                } else {
                    pageNestedScrollConnection(pagerState, Orientation.Horizontal)
                },
                flingBehavior = pagerFling,
            ) { page ->
                val pageScroll = scrollBehaviors[page]
                val scrollContent: @Composable () -> Unit = {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .overScrollVertical()

                            .then(if (page == TAB_ABOUT) Modifier else Modifier.nestedScroll(pageScroll.nestedScrollConnection))
                            .verticalScroll(rememberScrollState(), overscrollEffect = null)
                            .padding(top = if (page == TAB_ABOUT) {
                                WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                            } else padding.calculateTopPadding())
                            .padding(top = if (page == TAB_ABOUT) 0.dp else 4.dp, bottom = 4.dp),
                    ) {

                        if (contentReady || page == currentPage) {
                            when (page) {
                                TAB_TASKS -> TasksPage()
                                TAB_CONFIG -> ConfigPage()
                                else -> {}
                            }
                        }
                        Spacer(Modifier.height(padding.calculateBottomPadding()))
                        Spacer(Modifier.height(4.dp))
                    }
                }
                if (page == TAB_TASKS) {
                    PullToRefresh(
                        isRefreshing = pullRefreshing,
                        onRefresh = {
                            pullRefreshing = true
                            recomputeSchedule { pullRefreshing = false }
                        },
                        refreshTexts = listOf(uiText(R.string.refresh_pull), uiText(R.string.refresh_release), uiText(R.string.refresh_running), uiText(R.string.refresh_success)),
                        contentPadding = PaddingValues(top = padding.calculateTopPadding()),
                        topAppBarScrollBehavior = pageScroll,
                    ) {
                        scrollContent()
                    }
                } else if (page == TAB_RECORDS) {

                    RecordsPage(
                        topPadding = padding.calculateTopPadding(),
                        bottomPadding = padding.calculateBottomPadding(),
                        scrollBehavior = pageScroll,
                    )
                } else if (page == TAB_ABOUT) {

                    val aboutPlaying by remember(pagerState, currentPage, isNavigating, blurSupported) {
                        derivedStateOf {
                            blurSupported && aboutEffectResumed.value &&
                                currentPage == TAB_ABOUT && !isNavigating &&
                                !pagerState.isScrollInProgress && pagerState.settledPage == TAB_ABOUT
                        }
                    }
                    AboutPage(
                        playing = aboutPlaying,
                        bottomPadding = padding.calculateBottomPadding(),
                    )
                } else {
                    scrollContent()
                }
            }
            Dialogs()
            if (swipeBack.value && (textDialog.value != null || editorDialog.value != null)) {
                Box(Modifier.fillMaxSize().swipeBack { dismissTopOverlay() })
            }
        }
    }

    @Composable
    private fun RecordsPage(topPadding: Dp, bottomPadding: Dp, scrollBehavior: ScrollBehavior) {
        val list = recordsState.value
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = topPadding + 4.dp,
                bottom = bottomPadding + 8.dp,
            ),
        ) {
            if (list.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    SectionCard(
                        title = uiText(R.string.records_empty_title),
                        description = uiText(R.string.records_empty_description),
                    )
                }
                return@LazyColumn
            }
            item(key = "summary", contentType = "summary") {
                Text(
                    uiText(R.string.record_summary, list.size, failedCount.intValue),
                    modifier = Modifier.padding(horizontal = 28.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(2.dp))
            }
            itemsIndexed(
                items = list,

                key = { _, (accountId, record) -> "$accountId-${record.at}-${record.taskType}" },
                contentType = { _, _ -> "record" },
            ) { index, (accountId, record) ->

                val day = dayLabel(record.at)
                if (index == 0 || dayLabel(list[index - 1].second.at) != day) {
                    GroupTitle(day)
                }
                RecordCard(accountId, record)
            }
        }
    }

    @Composable
    private fun RecordCard(accountId: String, record: LocalTaskRecord) {
        val failed = record.result != "success"
        SectionCard(
            title = if (failed) uiText(R.string.record_failed_title, taskTitle(record.taskType), resultLabel(record.result))
            else taskTitle(record.taskType),
            titleColor = if (failed) DangerRed else MiuixTheme.colorScheme.onSurface,
            trailing = {
                Text(
                    formatClock(record.at),
                    fontSize = 12.sp,
                    fontWeight = FontWeight(550),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            },
            description = record.message.takeIf { it.isNotBlank() },
            onClick = { showRecordDetail(accountId, record) },
        )
    }

    @Composable
    private fun TasksPage() {

        SectionCard(
            title = uiText(R.string.task_overview_title),
            description = overviewText.value,
        )
        if (taskRows.value.isEmpty()) {
            SectionCard(
                title = uiText(R.string.tasks_empty_title),
                description = uiText(R.string.tasks_empty_description),
            )
        } else {
            taskRows.value.forEach { row -> TaskCard(row) }
        }
        Text(
            uiText(R.string.tasks_sync_note),
            modifier = Modifier.padding(horizontal = 28.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }

    @Composable
    private fun ConfigPage() {

        GroupTitle(stringResource(R.string.section_language))
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            OverlayDropdownPreference(
                title = stringResource(R.string.pref_app_language_title),
                summary = stringResource(R.string.pref_app_language_summary),
                items = listOf(
                    stringResource(R.string.language_system),
                    stringResource(R.string.language_zh),
                    stringResource(R.string.language_en),
                ),
                selectedIndex = appLanguage.intValue.coerceIn(0, 2),
                onSelectedIndexChange = ::setAppLanguage,
            )
        }

        GroupTitle(stringResource(R.string.section_interface))

        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            OverlayDropdownPreference(
                title = stringResource(R.string.pref_theme_title),
                summary = stringResource(R.string.pref_theme_summary),
                items = listOf(
                    stringResource(R.string.theme_system),
                    stringResource(R.string.theme_light),
                    stringResource(R.string.theme_dark),
                ),
                selectedIndex = themeMode.intValue.coerceIn(THEME_FOLLOW_SYSTEM, THEME_DARK),
                onSelectedIndexChange = ::setThemeMode,
            )
            SwitchPreference(
                title = stringResource(R.string.pref_blur_title),
                summary = stringResource(R.string.pref_blur_summary),
                checked = blurBars.value,
                onCheckedChange = ::toggleBlurBars,
            )
            SwitchPreference(
                title = stringResource(R.string.pref_floating_bar_title),
                summary = stringResource(R.string.pref_floating_bar_summary),
                checked = floatingNavBar.value,
                onCheckedChange = ::toggleFloatingNavBar,
            )
            if (floatingNavBar.value) {
                SwitchPreference(
                    title = stringResource(R.string.pref_liquid_glass_title),
                    summary = stringResource(R.string.pref_liquid_glass_summary),
                    checked = liquidGlass.value,
                    onCheckedChange = ::toggleLiquidGlass,
                )
            }
        }

        GroupTitle(stringResource(R.string.section_personalization))
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            Column {
                Text(
                    text = stringResource(R.string.pref_app_icon_title),
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 10.dp),
                )
                LauncherIconPicker()
                Spacer(Modifier.height(12.dp))
            }
            SwitchPreference(
                title = stringResource(R.string.pref_hide_recent_title),
                summary = stringResource(R.string.pref_hide_recent_summary),
                checked = hideRecentTask.value,
                onCheckedChange = { toggleHideRecentTask() },
            )
            SwitchPreference(
                title = stringResource(R.string.pref_hide_launcher_title),
                summary = stringResource(R.string.pref_hide_launcher_summary),
                checked = hideLauncherIcon.value,
                onCheckedChange = ::toggleHideLauncherIcon,
            )
        }

        GroupTitle(stringResource(R.string.section_gesture))
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                SwitchPreference(
                    title = stringResource(R.string.pref_predictive_back_title),
                    summary = stringResource(R.string.pref_predictive_back_summary),
                    checked = predictiveBack.value,
                    onCheckedChange = ::togglePredictiveBack,
                )
            }
            SwitchPreference(
                title = stringResource(R.string.pref_swipe_back_title),
                summary = stringResource(R.string.pref_swipe_back_summary),
                checked = swipeBack.value,
                onCheckedChange = ::toggleSwipeBack,
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.pref_pager_gesture_title),
                summary = stringResource(R.string.pref_pager_gesture_summary),
                items = listOf(
                    stringResource(R.string.pager_gesture_default),
                    stringResource(R.string.pager_gesture_cross),
                    stringResource(R.string.pager_gesture_ios),
                ),
                selectedIndex = pagerGestureMode.intValue.coerceIn(0, 2),
                onSelectedIndexChange = ::setPagerGestureMode,
            )
        }

        GroupTitle(uiText(R.string.root_enhancement_title))
        RootEnhancementSection()

        GroupTitle(uiText(R.string.section_notifications_logs))
        Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), insideMargin = PaddingValues(0.dp)) {
            ArrowPreference(title = uiText(R.string.permission_notification_settings),
                summary = uiText(R.string.root_notification_hint), onClick = ::requestNotificationPermission)
        }
        SectionCard(
            title = uiText(R.string.notification_records_title),
            description = notificationSummary.value,
        ) {
            CapsuleButton(MiuixIcons.Info, uiText(R.string.action_view)) { showNotificationRecords() }
            Spacer(Modifier.weight(1f))
            CapsuleButton(MiuixIcons.Delete, uiText(R.string.action_clear)) {
                NotificationRecordStore { applicationContext }.clear()
                toast(uiText(R.string.toast_notifications_cleared))
                refresh()
            }
        }
        SectionCard(
            title = uiText(R.string.module_logs_title),
            description = logSummary.value,
        ) {
            CapsuleButton(MiuixIcons.Info, uiText(R.string.action_view)) { showLogs() }
            Spacer(Modifier.weight(1f))
            CapsuleButton(MiuixIcons.Delete, uiText(R.string.action_clear)) {
                ModuleLogBuffer.clear()
                toast(uiText(R.string.toast_logs_cleared))
                refresh()
            }
        }
    }

    @Composable
    private fun TaskCard(row: TaskRow) {
        SectionCard(
            title = taskTitle(row.task.taskType),
            showActionDivider = true,
            titleColor = if (row.task.enabled) MiuixTheme.colorScheme.onSurface
            else MiuixTheme.colorScheme.onSurfaceVariantActions,
            description = row.subtitle,
            trailing = {
                Switch(
                    checked = row.task.enabled,
                    enabled = (row.accountId to row.task.taskType) !in taskMutations.value,
                    onCheckedChange = { enabled -> setTaskEnabled(row.accountId, row.task, enabled) },
                )
            },
        ) {
            CapsuleButton(MiuixIcons.Play, uiText(R.string.action_run)) { runSingleTask(row.accountId, row.task) }
            Spacer(Modifier.weight(1f))
            CapsuleButton(MiuixIcons.Settings, uiText(R.string.action_configure)) { openTaskEditor(row.accountId, row.task, row.spec) }
        }
    }

    @Composable
    private fun RootEnhancementSection() {
        RootEnhancementCard(
            enabled = rootEnhancementEnabled.value, busy = rootBusy.value, status = rootUi.value,
            checking = rootChecking.value, syncing = rootSyncing.value,
            syncedAt = rootSyncedAt.value, syncError = rootSyncError.value,
            onToggle = { enable -> rootConfirmation.value =
                if (enable) RootEnhancementAction.ENABLE else RootEnhancementAction.DISABLE },
            onInspect = ::inspectRoot,
            onManage = { rootModuleDialog.value = true },
            onSync = ::syncRootTasks,
        )
    }

    private fun inspectRoot() {
        if (rootBusy.value) return
        rootBusy.value = true
        rootChecking.value = true
        runBg(
            work = { runCatching { RootTaskBridge.inspect(applicationContext) } },
            then = { result ->
                rootChecking.value = false
                rootBusy.value = false
                result.onSuccess { rootUi.value = it }
                    .onFailure { toast(it.message ?: it.javaClass.simpleName) }
                refresh()
            },
        )
    }

    private fun syncRootTasks() {
        if (rootBusy.value) return
        rootBusy.value = true
        rootSyncing.value = true
        rootSyncError.value = ""
        runBg(
            work = {
                runCatching {
                    check(RootTaskBridge.isEnabled(applicationContext)) {
                        applicationContext.getString(R.string.root_sync_disabled)
                    }
                    RootTaskBridge.synchronize(applicationContext)
                    System.currentTimeMillis()
                }
            },
            then = { result ->
                rootSyncing.value = false
                rootBusy.value = false
                result.onSuccess {
                    rootSyncedAt.value = it
                    toast(uiText(R.string.root_sync_success))
                }.onFailure {
                    rootSyncError.value = it.message ?: it.javaClass.simpleName
                    toast(uiText(R.string.root_sync_failed, rootSyncError.value))
                }
                refresh()
            },
        )
    }

    @Composable
    private fun LauncherIconPicker() {
        val current = launcherIconStyle.value
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { launcherIconCoordinates = it }
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ModuleIconStyle.entries.forEach { style ->
                val selected = style == current
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { selectLauncherIcon(style) }
                        .padding(4.dp),
                ) {

                    val iconShape = RoundedCornerShape(18.dp)
                    val borderColor by animateColorAsState(
                        targetValue = if (selected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.dividerLine
                        },
                        label = "iconBorder",
                    )
                    val borderWidth by animateDpAsState(
                        targetValue = if (selected) 1.5.dp else 1.2.dp,
                        label = "iconBorderWidth",
                    )

                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(iconShape)
                            .background(colorResource(style.background))
                            .border(borderWidth, borderColor, iconShape),
                        contentAlignment = Alignment.Center,
                    ) {

                        Image(
                            painter = painterResource(style.foreground),
                            contentDescription = uiText(style.labelRes),
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(iconShape)
                                .graphicsLayer {
                                    scaleX = 1.5f
                                    scaleY = 1.5f
                                },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = uiText(style.labelRes),
                        fontSize = 12.sp,
                        color = if (selected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
            }
        }
    }

    private fun recomputeSchedule(onFinish: () -> Unit = {}) {
        runBg(
            work = {
                val updated = LocalTaskStore { applicationContext }.rescheduleEnabledTasks()
                if (updated > 0) uiText(R.string.toast_schedule_recomputed, updated) else uiText(R.string.toast_schedule_unchanged)
            },
            then = { message ->
                toast(message)
                refresh()
                onFinish()
            },
        )
    }

    private fun runSingleTask(accountId: String, task: LocalTask) {
        runBg(
            work = { LocalTaskRunner.runTaskNow(applicationContext, accountId, task.taskType) },
            then = { message ->
                toast(message)
                refresh()
            },
        )
    }

    private fun setTaskEnabled(accountId: String, task: LocalTask, enabled: Boolean) {
        val key = accountId to task.taskType
        if (key in taskMutations.value) return
        taskMutations.value = taskMutations.value + key
        runBg(work = {
            runCatching {
                val store = LocalTaskStore { applicationContext }
                val token = if (enabled) store.token(accountId).also {
                    check(it.isNotBlank()) { "登录凭据不可用，请在阅微重新登录并同步" }
                } else ""
                check(store.get(accountId, task.taskType) != null) { "任务不存在，请刷新后重试" }
                store.setEnabled(accountId, task.taskType, enabled, token)
            }
        }, then = { result ->
            taskMutations.value = taskMutations.value - key
            result.onSuccess {
                toast(uiText(if (enabled) R.string.toast_task_enabled else R.string.toast_task_disabled, taskTitle(task.taskType)))
            }.onFailure { toast(it.message ?: "任务保存失败") }
            refresh()
        })
    }

    private fun toggleHideRecentTask() {
        val enabled = !hideRecentTask.value
        writeUiPref(KEY_HIDE_RECENT_TASK, enabled)

        setTaskExcludedFromRecents(enabled)
        hideRecentTask.value = enabled
        toast(if (enabled) uiText(R.string.toast_recents_hidden) else uiText(R.string.toast_recents_shown))
    }

    private fun toggleHideLauncherIcon(enabled: Boolean) {
        val result = launcherIcons.setHidden(enabled)
        applyLauncherIconResult(result, notify = false)
        val hidden = result.view.choice.hidden
        toast(
            when {
                !result.success -> uiText(R.string.toast_icon_switch_failed)
                hidden -> uiText(R.string.toast_launcher_hidden)
                else -> uiText(R.string.toast_launcher_shown)
            },
        )
    }

    private fun selectLauncherIcon(style: ModuleIconStyle) {
        val hiddenBefore = hideLauncherIcon.value
        val result = launcherIcons.selectStyle(style.key)
        applyLauncherIconResult(result, notify = false)

        if (!hiddenBefore && !result.view.choice.hidden) {
            toast(
                if (result.success) uiText(R.string.toast_icon_switched, uiText(style.labelRes))
                else uiText(R.string.toast_icon_switch_failed),
            )
        }
    }

    private fun applyLauncherIconResult(result: LauncherIconResult, notify: Boolean = true) {
        val view = result.view
        launcherIconStyle.value = ModuleIconStyle.fromKey(view.choice.key)
        hideLauncherIcon.value = view.choice.hidden
        launcherIconIssue.value = view.issue
        view.issue?.let { ModuleAndroidLog.error(LOG_TAG, "桌面图标状态异常：$it", result.error) }
        if (notify) {
            when (view.issue) {
                LauncherIconIssue.MULTIPLE ->
                    toast(uiText(R.string.toast_icon_multiple, MODULE_PACKAGE, MAIN_ACTIVITY_CLASS))
                LauncherIconIssue.RECOVERY_FAILED, LauncherIconIssue.SAVE_FAILED, LauncherIconIssue.APPLY_FAILED ->
                    toast(uiText(R.string.toast_icon_switch_failed))
                else -> Unit
            }
        }
    }

    private fun launcherIconPreferences(): LauncherIconPreferences = object : LauncherIconPreferences {
        override fun read(): LauncherIconRecord {
            val configured = uiPrefString(KEY_LAUNCHER_ICON_STYLE) != null || uiPrefBoolean(KEY_HIDE_LAUNCHER_ICON)
            val confirmed = LauncherIconChoice(
                ModuleIconStyle.fromKey(uiPrefString(KEY_LAUNCHER_ICON_STYLE)).key,
                hidden = uiPrefBoolean(KEY_HIDE_LAUNCHER_ICON),
            )
            val pending = uiPrefString(KEY_LAUNCHER_ICON_PENDING)?.let { key ->
                LauncherIconChoice(ModuleIconStyle.fromKey(key).key, hidden = uiPrefBoolean(KEY_LAUNCHER_ICON_PENDING_HIDDEN))
            }
            return LauncherIconRecord(confirmed, pending, configured)
        }

        override fun write(record: LauncherIconRecord): Boolean =
            getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                .putString(KEY_LAUNCHER_ICON_STYLE, record.confirmed.key)
                .putBoolean(KEY_HIDE_LAUNCHER_ICON, record.confirmed.hidden)
                .apply {
                    if (record.pending == null) {
                        remove(KEY_LAUNCHER_ICON_PENDING)
                        remove(KEY_LAUNCHER_ICON_PENDING_HIDDEN)
                    } else {
                        putString(KEY_LAUNCHER_ICON_PENDING, record.pending.key)
                        putBoolean(KEY_LAUNCHER_ICON_PENDING_HIDDEN, record.pending.hidden)
                    }
                }
                .commit()
    }

    private fun setAppLanguage(index: Int) {
        if (index !in 0..2 || index == currentAppLanguageIndex()) return
        runCatching { ModuleLanguage.setSelection(this, index) }
            .onSuccess {
                updateLanguageContext()
                refresh()
            }
            .onFailure {
                ModuleAndroidLog.error(LOG_TAG, "Failed to change app language", it)
                toast(uiText(R.string.error_language_change))
            }
    }

    private fun currentAppLanguageIndex(): Int = ModuleLanguage.selection(this)

    private fun updateLanguageContext() {
        appLanguage.intValue = currentAppLanguageIndex()
        languageContext.value = ModuleLanguage.localizedContext(this, appLanguage.intValue)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLanguageContext()
        refresh()
        window.setBackgroundDrawable(ColorDrawable(if (resolveDark(themeMode.intValue)) DARK_WINDOW_BG else LIGHT_WINDOW_BG))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, tab.intValue)
        super.onSaveInstanceState(outState)
    }

    private fun toggleBlurBars(enabled: Boolean) {
        writeUiPref(KEY_BLUR_BARS, enabled)
        blurBars.value = enabled
    }

    private fun toggleFloatingNavBar(enabled: Boolean) {
        writeUiPref(KEY_FLOATING_NAV_BAR, enabled)
        floatingNavBar.value = enabled
    }

    private fun toggleLiquidGlass(enabled: Boolean) {
        writeUiPref(KEY_LIQUID_GLASS, enabled)
        liquidGlass.value = enabled
    }

    private fun setThemeMode(mode: Int) {
        val value = mode.coerceIn(THEME_FOLLOW_SYSTEM, THEME_DARK)
        writeUiPrefInt(KEY_THEME_MODE, value)
        themeMode.intValue = value
        syncApplicationNightMode(value)
        window?.setBackgroundDrawable(ColorDrawable(if (resolveDark(value)) DARK_WINDOW_BG else LIGHT_WINDOW_BG))
    }

    private fun syncApplicationNightMode(mode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val nightMode = when (mode) {
            THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO
            THEME_DARK -> UiModeManager.MODE_NIGHT_YES
            else -> UiModeManager.MODE_NIGHT_AUTO
        }
        runCatching {
            getSystemService(UiModeManager::class.java)?.setApplicationNightMode(nightMode)
        }.onFailure { error ->

            ModuleAndroidLog.legacy(LOG_TAG, "splash night mode sync failed: ${error.javaClass.simpleName}")
        }
    }

    private fun setPagerGestureMode(mode: Int) {
        val value = mode.coerceIn(0, PagerInterceptionMode.entries.lastIndex)
        writeUiPrefInt(KEY_PAGER_GESTURE, value)
        pagerGestureMode.intValue = value
    }

    private fun toggleSwipeBack(enabled: Boolean) {
        writeUiPref(KEY_SWIPE_BACK, enabled)
        swipeBack.value = enabled
    }

    private fun togglePredictiveBack(enabled: Boolean) {
        writeUiPref(KEY_PREDICTIVE_BACK, enabled)
        predictiveBack.value = enabled
        applyPredictiveBack(enabled)
        recreate()
    }

    private fun applyPredictiveBack(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback",
            )
            val method = ApplicationInfo::class.java.getDeclaredMethod(
                "setEnableOnBackInvokedCallback",
                Boolean::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(applicationInfo, enabled)
        }.onFailure { ModuleAndroidLog.error(LOG_TAG, "更新预测性返回失败", it) }
    }

    private fun resolveDark(mode: Int): Boolean = when (mode) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> isNightMode()
    }

    @Composable
    private fun Modifier.swipeBack(onDismiss: () -> Unit): Modifier {
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val callback by rememberUpdatedState(onDismiss)
        return pointerInput(rtl) {
            val edge = 28.dp.toPx()
            val threshold = 96.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val atEdge = if (rtl) down.position.x >= size.width - edge else down.position.x <= edge
                if (!atEdge) return@awaitEachGesture
                down.consume()
                var total = 0f
                horizontalDrag(down.id) { change ->
                    val delta = change.positionChange().x
                    total += if (rtl) -delta else delta
                    change.consume()
                }
                if (total >= threshold) callback()
            }
        }
    }

    private fun dismissTopOverlay() {
        val editor = editorDialog.value
        when {
            rootConfirmation.value != null -> rootConfirmation.value = null
            rootModuleDialog.value -> rootModuleDialog.value = false
            suPathDialog.value != null -> suPathDialog.value = null
            editor?.multiOpenLabel != null -> editor.multiOpenLabel = null
            editor != null -> editorDialog.value = null
            textDialog.value != null -> textDialog.value = null
        }
    }

    private fun setTaskExcludedFromRecents(excluded: Boolean) {
        runCatching {

            val tasks = getSystemService(ActivityManager::class.java)?.appTasks.orEmpty()
            tasks.forEach { appTask ->
                runCatching { appTask.setExcludeFromRecents(excluded) }
                    .onFailure { ModuleAndroidLog.error(LOG_TAG, "更新后台卡片可见性失败", it) }
            }
        }.onFailure { ModuleAndroidLog.error(LOG_TAG, "读取后台任务失败", it) }
    }

    private fun openTaskEditor(accountId: String, task: LocalTask, spec: CloudAutomationTaskSpec?) {
        val editor = TaskEditor(accountId, task, spec)
        fun put(label: String, value: String) {
            editor.values[label] = value
            if (label in setOf(FIELD_DURATION, FIELD_BOOKS, FIELD_DRAW_LIMIT)) {
                editor.textFields[label] = TextFieldState(initialText = value)
            }
        }
        if (spec?.rewardTriggered != true && spec?.merchant != true) put(FIELD_TIME, task.timeOfDay)
        if (spec?.autoRead == true) {
            put(FIELD_DURATION, task.durationMinutes.toString())
            put(FIELD_BOOKS, task.books.joinToString("\n") { "${it.bookId}|${it.name}" })
        }
        if (spec?.rewardTriggered == true) put(FIELD_DRAW_LIMIT, task.dailyDrawLimit.toString())
        if (spec?.taskType == "pawn") {

            put(
                FIELD_PAWN,
                (task.forbiddenPawnPropIds + CloudTaskLocalRunner.PROHIBITED_PAWN_PROP_HINTS.keys.filter { it.startsWith("name:") })
                    .sorted()
                    .joinToString(","),
            )
            editor.multiOptions[FIELD_PAWN] = pawnOptionsFromState(accountId)

            runBg(work = { runCatching { fetchPawnOptions(accountId) } }, then = { result ->
                val updated = result.getOrNull()
                if (updated != null) {
                    editor.multiOptions[FIELD_PAWN] = updated
                } else {
                    toast(result.exceptionOrNull()?.message ?: uiText(R.string.error_pawn_load))
                }
            })
        }
        if (spec?.merchant == true) {
            put(FIELD_CITY, task.merchantCityCode)
            put(FIELD_PRINCIPAL, task.merchantPrincipal.takeIf { it > 0L }?.toString().orEmpty())
            put(FIELD_TRANSPORT, task.merchantTransportId.takeIf { it > 0L }?.toString().orEmpty())

            editor.merchantLoading = true
            runBg(work = { runCatching { fetchMerchantChoices(accountId) } }, then = { result ->
                editor.merchantLoading = false
                val fetched = result.getOrNull()
                if (fetched == null) {
                    toast(result.exceptionOrNull()?.message ?: uiText(R.string.error_merchant_load))
                } else {
                    editor.merchantCities = fetched.cities
                    editor.merchantTransports = fetched.transports
                    editor.merchantNoTransportCapacity = fetched.noTransportCapacity
                    val savedCity = task.merchantCityCode
                    val savedTransport = task.merchantTransportId
                    if (!editor.merchantSelectionEdited && fetched.activeCityCode.isNotBlank() && savedCity.isBlank()) {
                        put(FIELD_CITY, fetched.activeCityCode)
                    }
                    if (!editor.merchantSelectionEdited && fetched.activeTransportId > 0L && savedTransport <= 0L) {
                        put(FIELD_TRANSPORT, fetched.activeTransportId.toString())
                    }
                }
            })
        }
        if (spec != null && spec.blessingOptions.isNotEmpty()) {
            put(FIELD_BLESSING, spec.resolveBlessingChoice(task.blessingType))
        }
        editorDialog.value = editor
    }

    private fun pawnOptionsFromState(accountId: String): List<ModulePawnOption> {
        val state = LocalTaskStore { applicationContext }.runtimeState(accountId, "pawn")
        return CloudTaskLocalRunner.pawnPropChoices(state).map {
            ModulePawnOption(it.propId, it.name, cloudTaskQualityColor(it.quality), it.hint)
        }
    }

    private fun fetchPawnOptions(accountId: String): List<ModulePawnOption> {
        val pawnStore = LocalTaskStore { applicationContext }
        val token = pawnStore.token(accountId)
        if (token.isBlank()) {
            throw IllegalStateException(uiText(R.string.error_credential_invalid))
        }
        val fetched = CloudTaskLocalRunner.fetchPawnPropCatalog(token)
        fetched.error?.let { reason ->
            runOnUiThread { toast(uiText(R.string.toast_pawn_partial, reason)) }
        }

        val fresh = fetched.catalog.keys().asSequence().mapNotNull { propId ->
            fetched.catalog.optJSONObject(propId)?.let { entry ->
                CloudTaskLocalRunner.PawnPropChoice(
                    propId,
                    entry.optString("name"),
                    entry.optString("quality"),
                )
            }
        }.toList()
        val state = pawnStore.runtimeState(accountId, "pawn")
        val merged = CloudTaskLocalRunner.mergePawnPropCatalog(
            state.optJSONObject(CloudTaskLocalRunner.KEY_PAWN_PROP_CATALOG) ?: JSONObject(),
            fresh,
        )
        state.put(CloudTaskLocalRunner.KEY_PAWN_PROP_CATALOG, merged)
        pawnStore.recordState(accountId, "pawn", state)
        return CloudTaskLocalRunner.pawnPropChoices(state).map {
            ModulePawnOption(it.propId, it.name, cloudTaskQualityColor(it.quality), it.hint)
        }
    }

    private fun fetchMerchantChoices(accountId: String): CloudTaskLocalRunner.MerchantOptionsFetch {
        val token = LocalTaskStore { applicationContext }.token(accountId)
        return CloudTaskLocalRunner.fetchMerchantOptions(token)
    }

    private fun saveTaskEdits(editor: TaskEditor) {
        if (editor.saving || editorDialog.value !== editor) return
        val values = editor.values.mapValues { (key, value) -> editor.textFields[key]?.text?.toString() ?: value }

        if (editor.spec?.merchant == true) {
            val principal = values[FIELD_PRINCIPAL]?.trim()?.toLongOrNull() ?: 0L
            val capacity = editor.merchantTransports
                .firstOrNull { it.id == values[FIELD_TRANSPORT].orEmpty() }
                ?.carryingCapacity ?: editor.merchantNoTransportCapacity
            if (principal > capacity && capacity > 0L) {
                toast(uiText(R.string.error_principal_capacity, capacity))
                return
            }
        }

        val fields = values.mapValues { (_, value) -> { value } }
        val updated = applyModuleTaskEdits(editor.task, editor.spec, fields)
        if (updated == null) {
            toast(uiText(R.string.error_invalid_fields))
            return
        }
        editor.saving = true
        runBg(work = {
            runCatching {
                val store = LocalTaskStore { applicationContext }
                val token = if (updated.enabled) store.token(editor.accountId).also {
                    check(it.isNotBlank()) { "登录凭据不可用，请在阅微重新登录并同步" }
                } else ""
                store.saveEditedTask(editor.accountId, updated, token, editor.task)
            }
        }, then = { result ->
            editor.saving = false
            result.onSuccess {
                toast(uiText(R.string.toast_config_saved))
                if (editorDialog.value === editor) editorDialog.value = null
            }.onFailure { toast(it.message ?: "任务保存失败") }
            refresh()
        })
    }

    private fun showRecordDetail(accountId: String, record: LocalTaskRecord) {
        textDialog.value = TextDialogUi(
            title = { uiText(R.string.record_detail_title, taskTitle(record.taskType)) },
            content = { moduleRecordDetailText(uiContext, accountId, record, taskTitle(record.taskType), resultLabel(record.result)) },
        )
    }

    private fun showNotificationRecords() {
        val records = NotificationRecordStore { applicationContext }.list()
        textDialog.value = TextDialogUi(
            title = { uiText(R.string.notification_records_title) },
            content = { if (records.isEmpty()) {
                uiText(R.string.notification_empty)
            } else {
                records.joinToString("\n\n") { record ->
                    buildString {
                        append(formatDateTime(record.at))
                        append(" · ")
                        append(if (record.delivered) uiText(R.string.notification_sent) else uiText(R.string.notification_not_sent))
                        append('\n')
                        append(record.title)
                        append('\n')
                        append(record.text)
                        append(uiText(R.string.notification_source_prefix))
                        append(record.source)
                        if (record.detail.isNotBlank()) {
                            append(" · ")
                            append(record.detail)
                        }
                    }
                }
            } },
            actions = listOf(
                R.string.action_clear to {
                    NotificationRecordStore { applicationContext }.clear()
                    toast(uiText(R.string.toast_notifications_cleared))
                    textDialog.value = null
                    refresh()
                },
            ),
        )
    }

    private fun showLogs() {
        val logs = ModuleLogBuffer.snapshot()
        textDialog.value = TextDialogUi(
            title = { uiText(R.string.module_logs_title) },
            content = { if (logs.isEmpty()) {
                uiText(R.string.logs_empty)
            } else {
                logs.joinToString("\n") { "${formatDateTime(it.at)} ${it.level}/${it.tag}: ${it.message}" }
            } },
            actions = listOf(
                R.string.action_clear to {
                    ModuleLogBuffer.clear()
                    toast(uiText(R.string.toast_logs_cleared))
                    textDialog.value = null
                    refresh()
                },
            ),
        )
    }

    @Composable
    private fun AboutPage(playing: Boolean, bottomPadding: Dp) {
        val iconStyle = launcherIconStyle.value
        com.reamicro.fix.ui.effect.AboutScrollLayout(
            playing = playing,
            bottomPadding = bottomPadding,
            header = { motion ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    com.reamicro.fix.ui.effect.AboutEffectLogo(
                        foreground = iconStyle.foreground,
                        background = iconStyle.background,
                        modifier = motion.logoModifier(),
                    )
                    Text(
                        text = uiText(R.string.module_name),
                        modifier = Modifier.padding(top = 12.dp, bottom = 5.dp)
                            .then(motion.nameModifier())
                            .then(com.reamicro.fix.ui.effect.aboutBrandTexture()),
                        fontSize = 35.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = versionLine(),
                        modifier = Modifier.fillMaxWidth().then(motion.versionModifier()),
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            },
            content = {
                com.reamicro.fix.ui.effect.AboutEffectCard(
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    ArrowPreference(
                        title = uiText(R.string.about_source),
                        summary = "GitHub · YGHFv/ReaMicro-Extend",
                        onClick = { openUrl(GITHUB_REPO_URL) },
                    )
                    ArrowPreference(
                        title = uiText(R.string.about_releases),
                        onClick = { openUrl("$GITHUB_REPO_URL/releases") },
                    )
                    ArrowPreference(
                        title = uiText(R.string.about_feedback),
                        onClick = { openUrl("$GITHUB_REPO_URL/issues") },
                    )
                }
                GroupTitle(uiText(R.string.about_acknowledgements))
                com.reamicro.fix.ui.effect.AboutEffectCard(
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    ArrowPreference(
                        title = "KernelSU",
                        summary = uiText(R.string.about_ksu_credit),
                        onClick = { openUrl("https://github.com/tiann/KernelSU") },
                    )
                    ArrowPreference(
                        title = "Miuix",
                        summary = uiText(R.string.about_miuix_credit),
                        onClick = { openUrl("https://github.com/miuix-kotlin-multiplatform/miuix") },
                    )
                    ArrowPreference(
                        title = "Scripta",
                        summary = uiText(R.string.about_scripta_credit),
                        onClick = { openUrl("https://github.com/YuKongA/scripta") },
                    )
                    ArrowPreference(
                        title = "齊伋體 qiji-font",
                        summary = uiText(R.string.about_qiji_font_credit),
                        onClick = { openUrl("https://github.com/LingDong-/qiji-font") },
                    )
                }

                Text(
                    text = uiText(R.string.about_build_time, buildTimeText()),
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 4.dp, bottom = 12.dp),
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            },
        )
    }
    private fun rootAction(block: () -> String) {
        if (rootBusy.value) {
            toast(uiText(R.string.execution_operation_pending))
            return
        }
        rootBusy.value = true
        runBg(
            work = {
                val result = runCatching(block)
                val failure = result.exceptionOrNull()
                val status = when {
                    failure is com.reamicro.fix.cloud.root.RootAccessException ->
                        com.reamicro.fix.cloud.root.RootModuleStatus(access = failure.report, error = failure.report.detail)
                    result.isFailure && RootTaskBridge.lastInspection != null -> RootTaskBridge.lastInspection!!
                    else -> RootTaskBridge.inspect(applicationContext)
                }
                result to status
            },
            then = { (result, status) ->
                rootBusy.value = false
                toast(result.getOrElse { it.message ?: it.javaClass.simpleName })
                rootUi.value = status
                refresh()
            },
        )
    }

    @Composable
    private fun Dialogs() {
        RootModuleDialog(
            show = rootModuleDialog.value, busy = rootBusy.value, status = rootUi.value,
            lastError = RootTaskBridge.lastOperationError(applicationContext),
            onClose = { rootModuleDialog.value = false },
            onInstall = { rootModuleDialog.value = false; rootAction { RootTaskBridge.installOrUpdate(uiContext) } },
            onSuPath = { rootModuleDialog.value = false; suPathDialog.value = RootTaskBridge.customSuPath(applicationContext) },
            onUninstall = { rootModuleDialog.value = false; rootConfirmation.value = RootEnhancementAction.UNINSTALL },
        )
        rootConfirmation.value?.let { action ->
            RootEnhancementDialog(
                show = true, title = uiText(R.string.root_enhancement_title),
                onClose = { rootConfirmation.value = null },
                primaryLabel = uiText(when (action) {
                    RootEnhancementAction.ENABLE -> R.string.root_enable_action
                    RootEnhancementAction.DISABLE -> R.string.root_disable_action
                    RootEnhancementAction.UNINSTALL -> R.string.root_uninstall
                }),
                primaryEnabled = !rootBusy.value, dangerous = action == RootEnhancementAction.UNINSTALL,
                onPrimary = {
                    rootConfirmation.value = null
                    rootAction {
                        when (action) {
                            RootEnhancementAction.ENABLE -> RootTaskBridge.enable(uiContext)
                            RootEnhancementAction.DISABLE -> RootTaskBridge.disable(uiContext, remove = false)
                            RootEnhancementAction.UNINSTALL -> RootTaskBridge.disable(uiContext, remove = true)
                        }
                    }
                },
            ) {
                Card(insideMargin = PaddingValues(0.dp)) {
                    BasicComponent(title = uiText(R.string.root_confirmation_title),
                        summary = uiText(when (action) {
                            RootEnhancementAction.ENABLE -> R.string.root_confirm_enable
                            RootEnhancementAction.DISABLE -> R.string.root_confirm_disable
                            RootEnhancementAction.UNINSTALL -> R.string.root_confirm_uninstall
                        }))
                }
            }
        }
        suPathDialog.value?.let { path ->
            RootEnhancementDialog(
                show = true, title = uiText(R.string.execution_su_path),
                onClose = { suPathDialog.value = null },
                primaryLabel = uiText(R.string.action_save), primaryEnabled = !rootBusy.value,
                onPrimary = {
                    if (!rootBusy.value) {
                        rootBusy.value = true
                        runBg(
                            work = { runCatching { RootTaskBridge.setCustomSuPath(applicationContext, path) } },
                            then = { result ->
                                rootBusy.value = false
                                result.onSuccess {
                                    suPathDialog.value = null
                                    rootUi.value = null
                                }.onFailure { toast(it.message.orEmpty()) }
                            },
                        )
                    }
                },
            ) {
                EditorField(label = uiText(R.string.execution_su_path), hint = uiText(R.string.execution_su_path_hint),
                    value = path, onValue = { suPathDialog.value = it })
                Text(uiText(R.string.execution_su_path_description),
                    style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
        textDialog.value?.let { dialog ->
            val primary = dialog.actions.singleOrNull()
            AppWindowDialog(
                show = true, title = dialog.title(), onClose = { textDialog.value = null },
                primaryLabel = primary?.let { uiText(it.first) }, onPrimary = primary?.second,
            ) {
                Text(dialog.content(), style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainer)
                if (dialog.actions.size > 1) {
                    Card(insideMargin = PaddingValues(0.dp)) {
                        dialog.actions.forEach { (label, action) ->
                            ArrowPreference(title = uiText(label), onClick = action)
                        }
                    }
                }
            }
        }
        editorDialog.value?.let { editor ->
            key(editor) { TaskEditorDialog(editor) }
        }
    }

    @Composable
    private fun TaskEditorDialog(editor: TaskEditor) {

        val taskTextStyles = MiuixTheme.textStyles.copy(
            body2 = MiuixTheme.textStyles.body2.copy(fontSize = 15.sp, lineHeight = 20.sp),
        )
        MiuixTheme(colors = MiuixTheme.colorScheme, textStyles = taskTextStyles) {
        AppWindowDialog(
            show = true, title = taskTitle(editor.task.taskType),
            onClose = { if (!editor.saving) editorDialog.value = null },
            primaryLabel = uiText(R.string.action_save),
            primaryEnabled = !editor.saving,
            onPrimary = { saveTaskEdits(editor) },
        ) {
            val spec = editor.spec
            if (spec?.rewardTriggered != true && spec?.merchant != true) {
                TaskTimePicker(
                    label = uiText(R.string.field_time),
                    value = editor.values[FIELD_TIME].orEmpty(),
                    onValueChange = { editor.values[FIELD_TIME] = it },
                    minMinutes = if (spec?.autoRead == true) {
                        (editor.textFields[FIELD_DURATION]?.text?.toString()?.toIntOrNull() ?: editor.task.durationMinutes).coerceIn(1, 720)
                    } else 0,
                )
            }
            if (spec?.autoRead == true) {
                val duration = (editor.textFields[FIELD_DURATION]?.text?.toString()?.toIntOrNull()
                    ?: editor.task.durationMinutes).coerceIn(1, 720)
                Text(
                    text = uiText(R.string.auto_read_time_lock_hint, duration, AutoReadTimeLock.formatTime(duration)),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                EditorField(
                    label = uiText(R.string.field_duration),
                    hint = uiText(R.string.field_duration_hint),
                    state = editor.textFields.getValue(FIELD_DURATION),
                )
                EditorField(
                    label = uiText(R.string.field_books),
                    hint = uiText(R.string.field_books_hint),
                    state = editor.textFields.getValue(FIELD_BOOKS),
                    maxLines = 6,
                )
            }
            if (spec?.rewardTriggered == true) {
                EditorField(
                    label = uiText(R.string.field_draw_limit),
                    hint = uiText(R.string.field_draw_limit_hint),
                    state = editor.textFields.getValue(FIELD_DRAW_LIMIT),
                )
            }
            if (spec?.taskType == "pawn") {
                PawnMultiSelectEntry(editor)
            }
            if (spec?.merchant == true) {
                CityDropdown(editor, editor.merchantLoading)
                TransportDropdown(editor, editor.merchantLoading)
            }
            if (spec != null && spec.blessingOptions.isNotEmpty()) {

                BlessingChooser(editor, spec)
            }
            if (spec?.merchant == true) {
                PrincipalSlider(editor)
            }
            editor.multiOpenLabel?.let { label ->
                key(label) { MultiSelectOverlay(editor, label) }
            }
        }
        }
    }

    @Composable
    private fun EditorField(
        label: String,
        hint: String,
        value: String,
        onValue: (String) -> Unit,
        maxLines: Int = 1,
    ) {
        Text(label, style = MiuixTheme.textStyles.main)
        Spacer(Modifier.height(6.dp))

        TextField(
            value = value,
            onValueChange = onValue,
            label = hint,
            modifier = Modifier.fillMaxWidth(),
            maxLines = maxLines,
            singleLine = maxLines == 1,
        )
        Spacer(Modifier.height(12.dp))
    }

    @Composable
    private fun EditorField(
        label: String,
        hint: String,
        state: TextFieldState,
        maxLines: Int = 1,
    ) {
        Text(label, style = MiuixTheme.textStyles.main)
        Spacer(Modifier.height(6.dp))
        TextField(
            state = state,
            label = hint,
            modifier = Modifier.fillMaxWidth(),
            lineLimits = if (maxLines == 1) TextFieldLineLimits.SingleLine
                else TextFieldLineLimits.MultiLine(maxHeightInLines = maxLines),
        )
        Spacer(Modifier.height(12.dp))
    }

    @Composable
    private fun PawnMultiSelectEntry(editor: TaskEditor) {
        val options = editor.multiOptions[FIELD_PAWN].orEmpty()
        val picked = CloudTaskLocalRunner.parseForbiddenPawnPropIds(editor.values[FIELD_PAWN].orEmpty())
        Text(uiText(R.string.field_pawn), style = MiuixTheme.textStyles.main)
        Spacer(Modifier.height(2.dp))
        Text(
            uiText(R.string.field_pawn_hint),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(6.dp))
        TextButton(
            text = modulePawnSelectionSummary(uiContext, options, picked),
            onClick = { editor.multiOpenLabel = FIELD_PAWN },
        )
        Spacer(Modifier.height(12.dp))
    }

    @Composable
    private fun ChoiceDropdownField(
        title: String,
        summary: String,
        value: String,

        emptyLabel: String,
        choices: List<DropdownChoice>,
        onSelect: (String) -> Unit,
    ) {
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val known = choices.any { it.value == value }
        val entry = DropdownEntry(buildList {
            if (!known) add(DropdownItem(text = value.ifBlank { emptyLabel }, enabled = false, selected = true))
            choices.forEach { choice ->
                add(DropdownItem(text = choice.label, selected = choice.value == value, onClick = { onSelect(choice.value) }))
            }
        })
        MiuixTheme(
            colors = MiuixTheme.colorScheme.copy(
                onSurfaceVariantActions = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            ),
            textStyles = MiuixTheme.textStyles,
        ) {
            WindowDropdownPreference(
                entry = entry, title = title, summary = summary, enabled = choices.isNotEmpty(),
                insideMargin = PaddingValues(vertical = 8.dp), maxHeight = 320.dp,
                onExpandedChange = { expanded ->
                    if (expanded) { focusManager.clearFocus(); keyboard?.hide() }
                },
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    private fun cityAccepts(
        city: CloudTaskLocalRunner.MerchantCityOption,
        transport: CloudTaskLocalRunner.MerchantTransportOption?,
    ): Boolean {
        val requiresTransport = city.requiredTransportType.isNotBlank()
        if (transport == null) return !requiresTransport
        return transport.transportType == CloudTaskLocalRunner.hostExpectedTransportType(city.routeType)
    }

    private fun merchantDurationMinutes(
        city: CloudTaskLocalRunner.MerchantCityOption,
        transport: CloudTaskLocalRunner.MerchantTransportOption?,
    ): Long {
        val speed = transport?.speedPercent ?: 0L
        return kotlin.math.ceil(city.baseDurationMinutes * (100.0 - speed) / 100.0).toLong()
    }

    private fun formatMerchantDuration(
        city: CloudTaskLocalRunner.MerchantCityOption,
        transport: CloudTaskLocalRunner.MerchantTransportOption?,
    ): String {
        val minutes = merchantDurationMinutes(city, transport)
        if (minutes < 60L) return uiText(R.string.duration_minutes, minutes)
        val hours = String.format(Locale.US, "%.1f", minutes / 60.0).removeSuffix(".0")
        return uiText(R.string.duration_hours, hours)
    }

    @Composable
    private fun CityDropdown(editor: TaskEditor, loading: Boolean) {

        val selectedTransport = editor.values[FIELD_TRANSPORT].orEmpty()
            .takeIf { it.isNotBlank() && it != MERCHANT_NO_TRANSPORT }
            ?.let { id -> editor.merchantTransports.firstOrNull { it.id == id } }
        val compatible = editor.merchantCities.filter { cityAccepts(it, selectedTransport) }
        val selectedCity = compatible.firstOrNull { it.code == editor.values[FIELD_CITY].orEmpty() }
        ChoiceDropdownField(
            title = uiText(R.string.field_city),
            summary = when {
                loading -> uiText(R.string.merchant_cities_loading)
                selectedCity != null -> uiText(R.string.merchant_estimate, formatMerchantDuration(selectedCity, selectedTransport))
                else -> uiText(R.string.merchant_cities_count, compatible.size)
            },
            value = editor.values[FIELD_CITY].orEmpty(),
            emptyLabel = uiText(R.string.selection_none),
            choices = compatible.map { DropdownChoice(it.code, it.label) },
            onSelect = { code ->
                editor.merchantSelectionEdited = true
                editor.values[FIELD_CITY] = code
                val city = compatible.firstOrNull { it.code == code } ?: return@ChoiceDropdownField
                val current = editor.merchantTransports.firstOrNull {
                    it.id == editor.values[FIELD_TRANSPORT].orEmpty()
                }

                if (current != null && !cityAccepts(city, current)) {
                    editor.values[FIELD_TRANSPORT] = ""
                }
            },
        )
    }

    @Composable
    private fun TransportDropdown(editor: TaskEditor, loading: Boolean) {
        val selectedCity = editor.merchantCities.firstOrNull {
            it.code == editor.values[FIELD_CITY].orEmpty()
        }
        val compatible = editor.merchantTransports.filter { transport ->
            transport.owned && (selectedCity == null || cityAccepts(selectedCity, transport))
        }

        val selected = compatible.firstOrNull { it.id == editor.values[FIELD_TRANSPORT].orEmpty() }
        val isNoTransport = editor.values[FIELD_TRANSPORT].orEmpty().let { it.isBlank() || it == MERCHANT_NO_TRANSPORT }
        ChoiceDropdownField(
            title = uiText(R.string.field_transport),
            summary = when {
                loading -> uiText(R.string.merchant_transports_loading)
                selected != null -> uiText(R.string.merchant_transport_stats, selected.speedPercent, selected.carryingCapacity)

                isNoTransport -> uiText(R.string.merchant_transport_capacity_only, editor.merchantNoTransportCapacity)
                else -> uiText(R.string.merchant_transports_count, compatible.size)
            },
            value = editor.values[FIELD_TRANSPORT].orEmpty().ifBlank { MERCHANT_NO_TRANSPORT },
            emptyLabel = uiText(R.string.merchant_transport_none),
            choices = listOf(DropdownChoice(MERCHANT_NO_TRANSPORT, uiText(R.string.merchant_transport_none))) +
                compatible.map { DropdownChoice(it.id, it.label) },
            onSelect = { id ->
                editor.merchantSelectionEdited = true
                if (id == MERCHANT_NO_TRANSPORT) {
                    editor.values[FIELD_TRANSPORT] = MERCHANT_NO_TRANSPORT

                    val current = editor.merchantCities.firstOrNull { it.code == editor.values[FIELD_CITY].orEmpty() }
                    if (current != null && !cityAccepts(current, null)) {
                        editor.values[FIELD_CITY] = ""
                    }
                    return@ChoiceDropdownField
                }
                editor.values[FIELD_TRANSPORT] = id
                val transport = compatible.firstOrNull { it.id == id } ?: return@ChoiceDropdownField
                val current = editor.merchantCities.firstOrNull {
                    it.code == editor.values[FIELD_CITY].orEmpty()
                }

                if (current != null && !cityAccepts(current, transport)) {
                    editor.values[FIELD_CITY] = ""
                }
            },
        )
    }

    @Composable
    private fun PrincipalSlider(editor: TaskEditor) {
        val transportId = editor.values[FIELD_TRANSPORT].orEmpty()
        val selected = transportId.takeIf { it.isNotBlank() && it != MERCHANT_NO_TRANSPORT }
            ?.let { id -> editor.merchantTransports.firstOrNull { it.id == id } }
        val capacity = selected?.carryingCapacity ?: editor.merchantNoTransportCapacity
        val scale = remember(capacity) { MerchantPrincipalScale(MERCHANT_MIN_PRINCIPAL, capacity) }
        var amount by remember(editor) {
            mutableLongStateOf(editor.values[FIELD_PRINCIPAL]?.trim()?.toLongOrNull() ?: 0L)
        }

        LaunchedEffect(scale) {
            val clamped = scale.clamp(amount)
            if (clamped != amount) {
                amount = clamped
                editor.values[FIELD_PRINCIPAL] = clamped.toString()
            }
        }
        val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(uiText(R.string.field_principal), style = MiuixTheme.textStyles.main)
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        !scale.enabled -> uiText(R.string.merchant_capacity_unknown)
                        amount <= 0L -> uiText(R.string.merchant_reuse_previous)
                        else -> scale.clamp(amount).toString()
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = muted,
                )
            }
            Slider(
                value = scale.fractionFor(amount),
                onValueChange = { fraction ->
                    if (!scale.enabled) return@Slider
                    val next = scale.amountFor(fraction)
                    amount = next

                    editor.values[FIELD_PRINCIPAL] = next.toString()
                },
                valueRange = 0f..1f,
                enabled = scale.enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (scale.enabled) uiText(R.string.merchant_principal_limit, scale.upper, scale.lower)
                else uiText(R.string.merchant_capacity_hint),
                style = MiuixTheme.textStyles.footnote1,
                color = muted,
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    @Composable
    private fun BlessingChooser(editor: TaskEditor, spec: CloudAutomationTaskSpec) {
        val options = spec.blessingOptions
        ChoiceDropdownField(
            title = uiText(R.string.field_blessing),
            summary = options.joinToString("/") { blessingLabel(it) },
            value = editor.values[FIELD_BLESSING].orEmpty(),
            emptyLabel = blessingLabel(""),
            choices = options.map { DropdownChoice(it, blessingLabel(it)) },
            onSelect = { editor.values[FIELD_BLESSING] = it },
        )
    }

    @Composable
    private fun MultiSelectOverlay(editor: TaskEditor, label: String) {
        val options = editor.multiOptions[label].orEmpty()
        val picked = remember(label) {
            mutableStateListOf<String>().apply {
                addAll(CloudTaskLocalRunner.parseForbiddenPawnPropIds(editor.values[label].orEmpty()))
            }
        }
        var refreshing by remember { mutableStateOf(false) }

        LaunchedEffect(label) {
            refreshing = true
            runBg(
                work = { runCatching { fetchPawnOptions(editor.accountId) } },
                then = { result ->
                    refreshing = false
                    val updated = result.getOrNull()
                    if (result.isFailure) {
                        toast(result.exceptionOrNull()?.message ?: uiText(R.string.error_list_load))
                    } else if (updated != null) {
                        editor.multiOptions[label] = updated
                    }
                },
            )
        }

        WindowDialog(
            title = uiText(R.string.field_pawn),
            show = true,
            onDismissRequest = { editor.multiOpenLabel = null },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    uiText(R.string.pawn_protection_hint),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                if (options.isEmpty() && refreshing) {
                    Text(
                        uiText(R.string.pawn_loading),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                    options.forEach { option ->
                        val locked = option.value in picked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (locked) picked.remove(option.value) else picked.add(option.value)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {

                            Text(
                                modulePawnOptionLabel(uiContext, option),
                                style = MiuixTheme.textStyles.main,
                                color = option.color?.let { Color(it) } ?: MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Checkbox(
                                state = if (locked) ToggleableState.On else ToggleableState.Off,
                                onClick = {
                                    if (locked) picked.remove(option.value) else picked.add(option.value)
                                },
                            )
                        }
                    }
                }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Button(
                    onClick = { editor.multiOpenLabel = null },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text(uiText(R.string.action_cancel))
                }
                Spacer(Modifier.width(16.dp))
                Button(
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    onClick = {

                        editor.values[label] = picked
                            .sortedWith(compareBy({ it.toLongOrNull() ?: Long.MAX_VALUE }, { it }))
                            .joinToString(",")
                        editor.multiOpenLabel = null
                    },
                ) {
                    Text(uiText(R.string.action_done))
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!uiPrefBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED) ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            writeUiPref(KEY_NOTIFICATION_PERMISSION_REQUESTED, true)
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            }.onFailure { toast(uiText(R.string.error_open_settings)) }
        }
    }

    private fun hideRecentTaskEnabled(): Boolean = uiPrefBoolean(KEY_HIDE_RECENT_TASK)

    private fun uiPrefBoolean(key: String, defValue: Boolean = false): Boolean =
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).getBoolean(key, defValue)

    private fun writeUiPref(key: String, value: Boolean) {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putBoolean(key, value).commit()
    }

    private fun uiPrefInt(key: String, defValue: Int = 0): Int =
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).getInt(key, defValue)

    private fun writeUiPrefInt(key: String, value: Int) {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putInt(key, value).commit()
    }

    private fun uiPrefString(key: String, defValue: String? = null): String? =
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).getString(key, defValue)

    private fun buildTimeText(): String =
        runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", uiContext.resources.configuration.locales[0]).format(Date(BuildConfig.BUILD_TIME))
        }.getOrDefault(uiText(R.string.status_unknown))

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { toast(uiText(R.string.error_open_link)) }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun <T> runBg(work: () -> T, then: (T) -> Unit) {
        Thread {
            runCatching(work)
                .onSuccess { value -> runOnUiThread { if (!isFinishing && !isDestroyed) then(value) } }
                .onFailure { error -> runOnUiThread { toast(error.message ?: error.javaClass.simpleName) } }
        }.apply { isDaemon = true }.start()
    }

    private fun futureNextRunAt(store: LocalTaskStore): Long? {
        val now = System.currentTimeMillis()
        return store.accountIds()
            .flatMap { accountId -> store.list(accountId) }
            .filter { it.enabled && it.nextRunAt > now }
            .minOfOrNull { it.nextRunAt }
    }

    private fun nextRunLine(accountId: String, task: LocalTask, spec: CloudAutomationTaskSpec?): String {
        if (!task.enabled) return uiText(R.string.status_not_enabled)
        if (spec?.rewardTriggered == true) return uiText(R.string.task_wish_trigger)
        val next = if (task.nextRunAt > 0L) formatDateTime(task.nextRunAt) else uiText(R.string.status_pending_schedule)
        if (spec?.merchant == true) {
            val poll = uiText(R.string.task_next_check, next)
            val tripEnd = lastMerchantTripEnd(accountId, task.taskType)
            return if (tripEnd != null) uiText(R.string.task_merchant_next, poll, tripEnd) else poll
        }
        return uiText(R.string.task_next_run, next)
    }

    private fun lastMerchantTripEnd(accountId: String, taskType: String): String? {
        val store = LocalTaskStore { applicationContext }
        val state = store.runtimeState(accountId, taskType)
        if (state.has(LocalTaskStore.KEY_MERCHANT_END_TIME)) {
            return state.optLong(LocalTaskStore.KEY_MERCHANT_END_TIME).takeIf { it > 0L }?.let(::formatDateTime)
        }
        return store.records(accountId)
            .firstOrNull { it.taskType == taskType }
            ?.detail
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optString("行程")
            ?.substringAfter(" → ", "")
            ?.takeIf { it.isNotBlank() }
    }

    private fun resultLabel(result: String): String = when (result) {
        "success" -> uiText(R.string.result_success)
        "failed" -> uiText(R.string.result_failed)
        "paused" -> uiText(R.string.result_paused)
        "skipped" -> uiText(R.string.result_skipped)
        "" -> uiText(R.string.status_unknown)
        else -> result
    }

    private fun taskTitle(taskType: String): String = when (taskType) {
            "yeshe_checkin" -> uiText(R.string.task_daily_lore)
            "yeshe_draw_card" -> uiText(R.string.task_auto_wish)
            "cloud_auto_read" -> uiText(R.string.task_auto_read)
            "traveling_merchant" -> uiText(R.string.task_merchant)
            "pawn" -> uiText(R.string.task_pawn)
            else -> taskType
        }

    private fun blessingLabel(type: String): String = when (type.trim().uppercase(Locale.ROOT)) {
        "LUCK" -> uiText(R.string.blessing_luck)
        "SAFETY" -> uiText(R.string.blessing_safety)
        "WEALTH" -> uiText(R.string.blessing_wealth)
        "" -> uiText(R.string.blessing_none)
        else -> type
    }

    private fun specOf(taskType: String): CloudAutomationTaskSpec? =
        CLOUD_AUTOMATION_TASKS.firstOrNull { it.taskType == taskType }

    private fun versionLine(): String =
        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    private fun formatTime(at: Long): String =
        if (at <= 0L) uiText(R.string.status_unscheduled) else SimpleDateFormat("MM-dd HH:mm", uiContext.resources.configuration.locales[0]).format(Date(at))

    private fun formatDateTime(at: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", uiContext.resources.configuration.locales[0]).format(Date(at))

    private fun dayLabel(at: Long): String {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = at }
        if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR)) {
            when (now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR)) {
                0 -> return uiText(R.string.day_today)
                1 -> return uiText(R.string.day_yesterday)
            }
            return SimpleDateFormat("MM-dd", uiContext.resources.configuration.locales[0]).format(Date(at))
        }
        return SimpleDateFormat("yyyy-MM-dd", uiContext.resources.configuration.locales[0]).format(Date(at))
    }

    private fun formatClock(at: Long): String =
        SimpleDateFormat("HH:mm", uiContext.resources.configuration.locales[0]).format(Date(at))

    private data class TaskRow(
        val accountId: String,
        val task: LocalTask,
        val spec: CloudAutomationTaskSpec?,
        val subtitle: String,
    )

    private data class TextDialogUi(
        val title: () -> String,
        val content: () -> String,
        val actions: List<Pair<Int, () -> Unit>> = emptyList(),
    )

    private class TaskEditor(
        val accountId: String,
        val task: LocalTask,
        val spec: CloudAutomationTaskSpec?,
    ) {
        var saving by mutableStateOf(false)
        var merchantSelectionEdited = false
        val values = mutableStateMapOf<String, String>()

        val textFields = mutableStateMapOf<String, TextFieldState>()
        val multiOptions = mutableStateMapOf<String, List<ModulePawnOption>>()

        var merchantCities: List<CloudTaskLocalRunner.MerchantCityOption> by mutableStateOf(emptyList())
        var merchantTransports: List<CloudTaskLocalRunner.MerchantTransportOption> by mutableStateOf(emptyList())

        var merchantNoTransportCapacity: Long by mutableLongStateOf(0L)

        var merchantLoading: Boolean by mutableStateOf(false)

        var multiOpenLabel: String? by mutableStateOf(null)
    }

    private data class DropdownChoice(val value: String, val label: String)

    private companion object {
        const val LOG_TAG = "ReaMicroMain"
        const val STATE_TAB = "selectedTab"
        const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"

        const val UI_PREFS = "reamicro_module_ui"
        const val KEY_HIDE_RECENT_TASK = "hideRecentTask"
        const val KEY_HIDE_LAUNCHER_ICON = "hideLauncherIcon"
        const val KEY_LAUNCHER_ICON_STYLE = "launcherIconStyle"

        const val KEY_LAUNCHER_ICON_PENDING = "launcherIconPending"
        const val KEY_LAUNCHER_ICON_PENDING_HIDDEN = "launcherIconPendingHidden"
        const val KEY_BLUR_BARS = "themeBlurBars"
        const val KEY_FLOATING_NAV_BAR = "themeFloatingNavBar"
        const val KEY_LIQUID_GLASS = "themeLiquidGlass"
        const val KEY_THEME_MODE = "themeMode"
        const val KEY_PAGER_GESTURE = "pagerGestureMode"
        const val KEY_SWIPE_BACK = "swipeBack"
        const val KEY_PREDICTIVE_BACK = "predictiveBack"

        const val MODULE_PACKAGE = "com.reamicro.fix"
        const val MAIN_ACTIVITY_CLASS = "com.reamicro.fix.ui.ModuleMainActivity"

        const val THEME_FOLLOW_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        val FLOAT_BAR_CORNER = 28.dp

        const val BAR_BLUR_RADIUS = 25f
        const val BAR_TINT_ALPHA = 0.87f

        const val LIQUID_SURFACE_ALPHA = 0.4f

        const val GITHUB_REPO_URL = "https://github.com/YGHFv/ReaMicro-Extend"

        const val TAB_RECORDS = 0
        const val TAB_TASKS = 1
        const val TAB_CONFIG = 2
        const val TAB_ABOUT = 3
        val TAB_TITLE_RES = listOf(R.string.tab_records, R.string.tab_tasks, R.string.tab_settings, R.string.tab_about)
        val TAB_ICONS = listOf(MiuixIcons.Recent, MiuixIcons.Tasks, MiuixIcons.Settings, MiuixIcons.Info)

        val LIGHT_WINDOW_BG = lightColorScheme().surface.toArgb()
        val DARK_WINDOW_BG = darkColorScheme().surface.toArgb()

        const val FIELD_TIME = "timeOfDay"
        const val FIELD_DURATION = "durationMinutes"
        const val FIELD_BOOKS = "books"
        const val FIELD_DRAW_LIMIT = "dailyDrawLimit"
        const val FIELD_PAWN = "forbiddenPawnPropIds"
        const val FIELD_CITY = "merchantCityCode"
        const val FIELD_PRINCIPAL = "merchantPrincipal"
        const val FIELD_TRANSPORT = "merchantTransportId"

        const val MERCHANT_NO_TRANSPORT = "0"

        const val MERCHANT_MIN_PRINCIPAL = 10L
        const val FIELD_BLESSING = "blessingType"
    }
}
