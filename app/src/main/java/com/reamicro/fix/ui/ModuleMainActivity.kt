package com.reamicro.fix.ui

import com.reamicro.fix.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import android.content.Context
import androidx.annotation.StringRes
import androidx.annotation.PluralsRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import android.Manifest
import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Intent
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
import androidx.compose.animation.CapturedAnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.computedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.cloud.local.CloudTaskLocalRunner
import com.reamicro.fix.cloud.local.LocalTask
import com.reamicro.fix.cloud.local.LocalTaskAccount
import com.reamicro.fix.cloud.local.LocalTaskCredentials
import com.reamicro.fix.cloud.local.localTaskAccount
import com.reamicro.fix.cloud.local.AutoReadTimeLock
import com.reamicro.fix.cloud.local.LocalTaskRecord
import com.reamicro.fix.cloud.local.LocalTaskRunner
import com.reamicro.fix.cloud.local.LocalTaskStore
import com.reamicro.fix.cloud.local.localTaskFromJson
import com.reamicro.fix.cloud.local.localTaskRecordFromJson
import com.reamicro.fix.cloud.local.localTaskRuntimeState
import com.reamicro.fix.cloud.local.nextLocalTaskAt
import com.reamicro.fix.cloud.root.RootModuleStatus
import com.reamicro.fix.cloud.root.RootTaskBridge
import com.reamicro.fix.hook.CLOUD_AUTOMATION_TASKS
import com.reamicro.fix.hook.CloudAutomationTaskSpec
import com.reamicro.fix.logging.ModuleAndroidLog
import com.reamicro.fix.logging.ModuleLogBuffer
import com.reamicro.fix.notification.CloudTaskNotifications
import com.reamicro.fix.notification.NotificationRecordStore
import com.reamicro.fix.notification.cloudTaskQualityColor
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.RefreshState
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.integration.captureBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.ClockFill
import top.yukonga.miuix.kmp.icon.glass.SettingsOutline
import top.yukonga.miuix.kmp.icon.glass.Tasks
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.springAnimateToPage
import top.yukonga.miuix.kmp.utils.overScrollHorizontal
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val DangerRed = Color(0xFFD03A2B)

class ModuleMainActivity : ComponentActivity() {

    private var launcherIconCoordinates: LayoutCoordinates? = null

    private val tab = mutableIntStateOf(TAB_RECORDS)

    private val recordRows = mutableStateOf<List<ModuleRecordRow>>(emptyList())
    private var recordsSelecting by mutableStateOf(false)
    private var selectedRecordKeys by mutableStateOf<Set<String>>(emptySet())
    private var deletingRecordKeys by mutableStateOf<Set<String>>(emptySet())
    private var exitingRecordKeys by mutableStateOf<Set<String>>(emptySet())
    private var recordsDeleteConfirmation by mutableStateOf(false)
    private var recordsDeleting by mutableStateOf(false)
    private var taskDragging by mutableStateOf(false)
    private var taskSortPopupOpen by mutableStateOf(false)
    private var taskOrder by mutableStateOf(ModuleTaskOrder())
    private val taskOrderPreferences by lazy { ModuleTaskOrderPreferences(getSharedPreferences(UI_PREFS, MODE_PRIVATE)) }
    private var recordDeletionError by mutableStateOf(false)
    private val taskRows = mutableStateOf<List<TaskRow>>(emptyList())
    private var accounts by mutableStateOf<List<LocalTaskAccount>>(emptyList())
    private var recordsAccount by mutableStateOf<String?>(null)
    private var tasksAccount by mutableStateOf<String?>(null)
    private val accountsUi = ModuleAccountsUi()
    private val nicknameRefreshAttempts = mutableSetOf<Pair<String, String>>()
    private var nicknameRefreshJob: Job? = null
    private fun accountLabel(id: String): String = accounts.firstOrNull { it.id == id }?.label ?: id
    private fun visibleRecords(): List<ModuleRecordRow> = recordRows.value.filter { recordsAccount == null || it.accountId == recordsAccount }
    private val importCredentialFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAccounts {
            contentResolver.openInputStream(uri)?.use { input ->
                LocalTaskCredentials.read(input)
            } ?: error(uiText(R.string.accounts_file_unavailable))
        }
    }
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
    private fun uiQuantity(@PluralsRes id: Int, count: Int): String =
        uiContext.resources.getQuantityString(id, count, count)
    private val notificationSummary = mutableStateOf("")
    private val logSummary = mutableStateOf("")
    private val rootUi = mutableStateOf<RootModuleStatus?>(null)
    private val suPathDialog = mutableStateOf<TextFieldState?>(null)
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
    }

    private val aboutEffectResumed = mutableStateOf(false)
    private var themeConfig by mutableStateOf(ModuleThemeConfig())
    private val themePreferences by lazy { ModuleThemePreferences(getSharedPreferences(UI_PREFS, MODE_PRIVATE)) }
    private var appliedThemeSurface: Int? = null

    private val swipeBack = mutableStateOf(true)

    private var predictiveBackMode by mutableStateOf(ModulePredictiveBackMode.Off)

    private val textDialog = mutableStateOf<TextDialogUi?>(null)
    private val editorDialog = mutableStateOf<TaskEditor?>(null)

    private val recordsNavigationRequest = mutableIntStateOf(0)

    private val splashExit = ModuleSplashExit(this)
    private val uiRefreshLifetime = SupervisorJob()
    private val uiRefreshScope = CoroutineScope(uiRefreshLifetime + Dispatchers.Main.immediate)
    private val uiRefresh by lazy {
        ModuleUiRefresh(
            scope = uiRefreshScope,
            read = {
                val context = uiContext
                withContext(Dispatchers.IO) { readUiData(context) }
            },
            apply = ::applyUiData,
            onError = { ModuleAndroidLog.error(LOG_TAG, "Failed to refresh local task UI", it) },
        )
    }

    override fun onDestroy() {
        uiRefreshLifetime.cancel()
        splashExit.dispose()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        if (savedInstanceState == null) splashExit.install()
        setTheme(com.reamicro.fix.R.style.ModuleTheme)
        super.onCreate(savedInstanceState)
        ModuleLogBuffer.attach(this)
        tab.intValue = savedInstanceState?.getInt(STATE_TAB, TAB_RECORDS) ?: TAB_RECORDS
        recordsAccount = savedInstanceState?.getString(STATE_RECORD_ACCOUNT)
        tasksAccount = savedInstanceState?.getString(STATE_TASK_ACCOUNT)
        themeConfig = themePreferences.read()
        taskOrder = taskOrderPreferences.read()
        recordsSelecting = savedInstanceState?.getBoolean(STATE_RECORD_SELECTION, false) ?: false
        selectedRecordKeys = savedInstanceState?.getStringArrayList(STATE_SELECTED_RECORDS)?.toSet().orEmpty()
        syncApplicationNightMode(themeConfig.nightMode)

        predictiveBackMode = ModulePredictiveBackMode.read(getSharedPreferences(UI_PREFS, MODE_PRIVATE).all)
        hideRecentTask.value = hideRecentTaskEnabled()
        swipeBack.value = uiPrefBoolean(KEY_SWIPE_BACK, true)

        applyLauncherIconResult(launcherIcons.restore())
        updateLanguageContext()
        window?.setBackgroundDrawable(ColorDrawable(windowThemeSurface()))
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
                LocalModuleWindowGestures provides ModuleWindowGestureSettings(predictiveBackMode, swipeBack.value),
            ) {
                val dark = themeConfig.isDark(isNightMode())
                ImmersiveSystemBars(dark)
                SystemBarAppearance(dark)
                ModuleAppTheme(config = themeConfig, dark = dark) {
                    ThemeWindowSurface()
                    ModulePredictiveBackScope(animated = predictiveBackMode.enabled) {
                        ModuleApp()
                    }
                }
            }
        }
    }

    @Composable
    private fun ThemeWindowSurface() {
        val color = MiuixTheme.colorScheme.surface.toArgb()
        SideEffect {
            if (appliedThemeSurface != color) {
                appliedThemeSurface = color
                window.setBackgroundDrawable(ColorDrawable(color))
            }
        }
    }

    private fun windowThemeSurface(): Int = appliedThemeSurface
        ?: if (themeConfig.isDark(isNightMode())) DARK_WINDOW_BG else LIGHT_WINDOW_BG

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
        accountsUi.close()
        exitRecordSelection()
        recordsAccount = incoming.getStringExtra(CloudTaskNotifications.EXTRA_ACCOUNT_ID)?.takeIf { it.isNotBlank() }
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
        nicknameRefreshAttempts.clear()
        aboutEffectResumed.value = true

        rootUi.value = RootTaskBridge.lastInspection

        setTaskExcludedFromRecents(hideRecentTaskEnabled())
        registerRecordObservers()
        showPendingNotificationPermission()
        runBg(work = { LocalTaskRunner.retryNotifications(applicationContext) }, then = { refresh() })
        RootTaskBridge.requestSync(applicationContext) { recordRefreshHandler.post { refresh() } }
        refresh()
    }

    override fun onPause() {
        aboutEffectResumed.value = false
        unregisterRecordObservers()
        uiRefresh.cancelPending()
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
        if (observingRecords && !isFinishing && !isDestroyed) {
            showPendingNotificationPermission()
            refresh()
        }
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
        getSharedPreferences(com.reamicro.fix.notification.ModuleNotificationPermission.PREFS_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(recordPrefsListener)
    }

    private fun unregisterRecordObservers() {
        observingRecords = false
        getSharedPreferences(LocalTaskStore.PREFS_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(recordPrefsListener)
        getSharedPreferences(NotificationRecordStore.PREFS_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(recordPrefsListener)
        getSharedPreferences(com.reamicro.fix.notification.ModuleNotificationPermission.PREFS_NAME, MODE_PRIVATE)
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
        if (observingRecords && !isFinishing && !isDestroyed) uiRefresh.request()
    }

    private fun readUiData(context: Context): UiData {
        val store = LocalTaskStore { applicationContext }
        // Read each account once. Its task state and records then come from the same
        // preference snapshot instead of reparsing its full history for every task.
        val accounts = store.snapshotPayload(recordLimit = LocalTaskStore.MAX_RECORDS).getJSONObject("accounts")
        val accountIds = accounts.keys().asSequence().toList()
        val accountRecords = accountIds.associateWith { accountId ->
            val records = accounts.getJSONObject(accountId).optJSONArray("records") ?: JSONArray()
            (0 until records.length()).mapNotNull { index ->
                records.optJSONObject(index)?.let(::localTaskRecordFromJson)
            }.sortedByDescending { it.at }
        }
        val recordsList = accountIds
            .flatMap { accountId -> accountRecords.getValue(accountId).map { accountId to it } }
            .sortedByDescending { (_, record) -> record.at }
        val now = System.currentTimeMillis()
        val rows = accountIds.flatMap { accountId ->
            val tasks = accounts.getJSONObject(accountId).getJSONObject("tasks")
            val checkin = tasks.optJSONObject("yeshe_checkin") ?: JSONObject()
            tasks.keys().asSequence().mapNotNull { type ->
                val rawTask = tasks.optJSONObject(type) ?: return@mapNotNull null
                val task = localTaskFromJson(type, rawTask).copy(
                    accountGeneration = accounts.getJSONObject(accountId).optString("accountGeneration"))
                val state = localTaskRuntimeState(rawTask)
                val spec = specOf(task.taskType)
                val effectiveNext = nextLocalTaskAt(task, state, checkin, now) ?: 0L
                TaskRow(
                    accountId, task, spec,
                    moduleTaskSubtitle(context, task, spec, state, accountRecords.getValue(accountId), effectiveNext),
                    effectiveNext,
                )
            }.toList()
        }
        val rootEnabled = RootTaskBridge.isEnabled(applicationContext)
        val next = rows.asSequence()
            .filter { it.task.enabled && it.effectiveNextRunAt > now }.minOfOrNull { it.effectiveNextRunAt }
            ?.let { SimpleDateFormat("MM-dd HH:mm", context.resources.configuration.locales[0]).format(Date(it)) }
            ?: context.getString(R.string.common_none)
        val overview = context.getString(R.string.task_overview_body, accountIds.size, rows.count { it.task.enabled }, next) +
            if (!rootEnabled) "\n" + context.getString(R.string.root_manual_only) else ""
        val notifications = NotificationRecordStore { context }.list()
        val notificationText = if (notifications.isEmpty()) {
            context.getString(R.string.notification_empty_summary)
        } else {
            val delivered = notifications.count { it.delivered }
            context.getString(R.string.notification_summary, notifications.size, delivered, notifications.size - delivered)
        }
        val logs = ModuleLogBuffer.snapshot()
        val path = ModuleLogBuffer.filePath()
        val logText = context.getString(R.string.log_summary, logs.size,
            path?.let { context.getString(R.string.log_file_path, it) } ?: context.getString(R.string.log_file_unavailable))
        return UiData(
            recordsList,
            rows, accountIds.map { localTaskAccount(it, accounts.getJSONObject(it)) }.sortedBy { it.id.toLongOrNull() },
            rootEnabled, overview, notificationText, logText,
        )
    }

    private fun readTaskRow(store: LocalTaskStore, accountId: String, taskType: String): TaskRow {
        val task = checkNotNull(store.get(accountId, taskType)) { "任务不存在，请刷新后重试" }
        val spec = specOf(taskType)
        val state = store.runtimeState(accountId, taskType)
        val effectiveNext = nextLocalTaskAt(
            task, state,
            store.runtimeState(accountId, "yeshe_checkin"), System.currentTimeMillis(),
        ) ?: 0L
        return TaskRow(
            accountId, task, spec,
            moduleTaskSubtitle(uiContext, task, spec, state, store.records(accountId), effectiveNext),
            effectiveNext,
        )
    }

    private fun applySavedTask(row: TaskRow) {
        taskRows.value = taskRows.value.map {
            if (it.accountId == row.accountId && it.task.taskType == row.task.taskType) row else it
        }
        refresh()
        // LocalTaskStore already queues the configuration change. Its confirmed
        // Root snapshot will notify the preference observer when state changes.
    }

    private fun applyUiData(data: UiData) {
        // Keep the displayed multiset frozen through persistence and exit, including identical
        // records whose occurrence keys will be renumbered after one duplicate is removed.
        if (!recordsDeleting) {
            recordRows.value = moduleRecordRows(data.records)
            selectedRecordKeys = selectedRecordKeys.intersect(recordRows.value.mapTo(mutableSetOf()) { it.key })
        }
        taskRows.value = data.tasks
        accounts = data.accounts
        val ids = accounts.mapTo(mutableSetOf()) { it.id }
        if (recordsAccount != null && recordsAccount !in ids) {
            exitRecordSelection()
            recordsAccount = null
        }
        if (tasksAccount != null && tasksAccount !in ids) tasksAccount = null
        rootEnhancementEnabled.value = data.rootEnabled
        overviewText.value = data.overview
        notificationSummary.value = data.notifications
        logSummary.value = data.logs
        refreshMissingAccountNicknames(data.tasks)
    }

    private fun refreshMissingAccountNicknames(rows: List<TaskRow>) {
        if (nicknameRefreshJob?.isActive == true) return
        val missing = accounts.filter { it.nickname.isBlank() }.mapNotNull { account ->
            val generation = rows.firstOrNull { it.accountId == account.id }?.task?.accountGeneration ?: return@mapNotNull null
            (account.id to generation).takeIf { it !in nicknameRefreshAttempts }
        }
        if (missing.isEmpty()) return
        nicknameRefreshJob = uiRefreshScope.launch {
            try {
                for ((accountId, generation) in missing) {
                    nicknameRefreshAttempts += accountId to generation
                    val result = withContext(Dispatchers.IO) {
                        runCatching { LocalTaskStore { applicationContext }.refreshAccountNickname(accountId, generation) }
                    }
                    if (result.getOrDefault(false)) refresh()
                }
            } finally {
                nicknameRefreshJob = null
                refresh()
            }
        }
    }

    @Composable
    private fun ModuleApp() {
        val tabTitles = TAB_TITLE_RES.map { stringResource(it) }
        val pagerState = rememberPagerState(initialPage = tab.intValue, pageCount = { TAB_TITLE_RES.size })
        val scope = rememberCoroutineScope()
        val settings = themeConfig.active
        val pagerMode = settings.pagerMode
        val interceptPager = pagerMode == PagerInterceptionMode.CrossAxis

        val overlayOpen = hasOpenOverlay()
        LaunchedEffect(overlayOpen, aboutEffectResumed.value) {
            if (!overlayOpen && aboutEffectResumed.value) showPendingNotificationPermission()
        }
        val userScrollEnabled = !overlayOpen && !taskDragging && !taskSortPopupOpen && !recordsDeleting
        BackHandler(enabled = recordsSelecting && !overlayOpen && !recordsDeleting) { exitRecordSelection() }
        LaunchedEffect(pagerState.settledPage) {
            tab.intValue = pagerState.settledPage
        }
        BackHandler(enabled = overlayOpen) { dismissTopOverlay() }
        LaunchedEffect(pagerState.settledPage) {
            if (pagerState.settledPage != TAB_RECORDS && !recordsDeleting) exitRecordSelection()
        }

        val scrollBehaviors = List(TAB_TITLE_RES.size) { MiuixScrollBehavior() }
        var pullRefreshing by remember { mutableStateOf(false) }
        val pullState = rememberPullToRefreshState()
        LaunchedEffect(deletingRecordKeys) {
            val deleted = deletingRecordKeys
            if (deleted.isNotEmpty()) {
                delay(140)
                exitingRecordKeys = deleted
                delay(280)
                val remaining = recordRows.value.filterNot { it.key in deleted }
                recordRows.value = remaining
                selectedRecordKeys -= deleted
                deletingRecordKeys = emptySet()
                exitingRecordKeys = emptySet()
                recordsDeleting = false
                if (recordDeletionError) {
                    recordDeletionError = false
                    recordsSelecting = true
                } else exitRecordSelection()
                refresh()
            }
        }
        val blurSupported = remember { isRuntimeShaderSupported() }
        val recordBackdrop = themeConfig.needsPageBackdrop(blurSupported)
        val surface = MiuixTheme.colorScheme.surface
        // Bodies and chrome have different recording owners so a bar cannot sample itself.
        val pageBackdrops = List(TAB_TITLE_RES.size) { rememberLayerBackdrop() }
        val chromeBackdrops = List(TAB_TITLE_RES.size) { rememberLayerBackdrop() }
        val stablePageBackdrops = remember { pageBackdrops }
        val stableChromeBackdrops = remember { chromeBackdrops }
        val aboutState = rememberLazyListState()
        val aboutMotion = com.reamicro.fix.ui.effect.rememberAboutBrandMotion(aboutState)
        val aboutEffectState = remember { com.reamicro.fix.ui.effect.BgEffectState() }
        val recordsState = rememberLazyListState()
        val tasksListState = rememberLazyListState()
        val configListState = rememberLazyListState()
        val launcherListState = rememberLazyListState()
        val bottomBackdrop = rememberLayerBackdrop()
        val recordScene = themeConfig.needsSceneBackdrop(blurSupported)
        val visiblePageIndices = remember(pagerState) {
            computedStateOf { pagerState.layoutInfo.visiblePagesInfo.map { it.index }.sorted() }
        }
        val visiblePages = remember(pagerState, visiblePageIndices) {
            {
                // Sampling must follow every movement frame even while the same two pages stay
                // visible. The index list itself stays cached and is shared by all consumers.
                pagerState.currentPageOffsetFraction
                visiblePageIndices.value
            }
        }
        val backdrop = rememberModulePageBackdrops(stablePageBackdrops, visiblePages)
        val topBackdrop = rememberModulePageBackdrops(stableChromeBackdrops, visiblePages)

        var selectedPage by remember { mutableIntStateOf(tab.intValue.coerceIn(0, TAB_TITLE_RES.lastIndex)) }
        var isNavigating by remember { mutableStateOf(false) }
        var navJob by remember { mutableStateOf<Job?>(null) }

        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }.collect { page ->
                if (!isNavigating && selectedPage != page) selectedPage = page
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

        val animateToTab: (Int) -> Unit = { index ->
            if (index != selectedPage && !taskDragging && !recordsDeleting && !overlayOpen) {
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
        val pageAndTop = com.reamicro.fix.ui.liquid.rememberCombinedBackdrop(backdrop, topBackdrop)
        val scene = if (settings.showNavigation) {
            com.reamicro.fix.ui.liquid.rememberCombinedBackdrop(pageAndTop, bottomBackdrop)
        } else pageAndTop
        CompositionLocalProvider(LocalModuleSceneBackdrop provides if (recordScene) scene else null) {
            Scaffold(
                // The pager pages own their top bars and insets; only the bottom bar is global.
                contentWindowInsets = WindowInsets.navigationBars.union(WindowInsets.captionBar)
                    .only(WindowInsetsSides.Bottom),
                bottomBar = {
                    Box(Modifier.then(if (recordScene) Modifier.captureBackdrop(bottomBackdrop) else Modifier)) {
                        ModuleBottomBar(
                            titles = tabTitles,
                            os3Icons = TAB_ICONS,
                            os4Icons = TAB_GLASS_ICONS,
                            selectedIndex = currentPage,
                            onSelect = animateToTab,
                            config = themeConfig,
                            backdrop = backdrop,
                        )
                    }
                },
            ) { padding ->
                val pagerFling = flingBehavior(
                    state = pagerState,
                    snapAnimationSpec = PagerNavigationSpringSpec,
                )
                val pagerModifier = Modifier.modulePagerGestureOverride(
                    pagerState = pagerState,
                    flingBehavior = pagerFling,
                    mode = pagerMode,
                    enabled = userScrollEnabled,
                    isExcluded = { position ->
                        interceptPager && pagerState.currentPage == TAB_CONFIG &&
                            launcherIconCoordinates?.takeIf { it.isAttached }
                                ?.boundsInWindow()?.contains(position) == true
                    },
                )
                    .fillMaxSize()
                    .background(surface)
                    .padding(rememberModuleHorizontalPadding(padding))
                HorizontalPager(
                    modifier = pagerModifier,
                    state = pagerState,

                    beyondViewportPageCount = 1,
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
                    val pageBackdrop = pageBackdrops[page]
                    val pageVisible = remember(pagerState, page) {
                        computedStateOf { pagerState.layoutInfo.visiblePagesInfo.any { it.index == page } }
                    }
                    // A local Scaffold owns only page chrome. All popups stay in the global host.
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Horizontal),
                        popupHost = {},
                        topBar = {
                            if (page != TAB_ABOUT) {
                                Box(Modifier.then(
                                    if (recordScene) Modifier.captureBackdrop(chromeBackdrops[page]) {
                                        pageVisible.value
                                    } else Modifier,
                                )) {
                                    ModuleTopBar(
                                        title = if (page == TAB_RECORDS && recordsSelecting) {
                                            uiQuantity(R.plurals.records_selected_count, selectedRecordKeys.size)
                                        } else tabTitles[page],
                                        scrollBehavior = pageScroll,
                                        isContentScrolled = when (page) {
                                            TAB_TASKS -> tasksListState.canScrollBackward
                                            TAB_RECORDS -> recordsState.canScrollBackward
                                            else -> configListState.canScrollBackward
                                        },
                                        config = themeConfig,
                                        backdrop = pageBackdrop,
                                        navigationIcon = {
                                            if (page == TAB_RECORDS && recordsSelecting) {
                                                ModuleToolbarAction(
                                                    icon = ModuleActionIcon.Close,
                                                    description = uiText(R.string.records_selection_exit),
                                                    onClick = ::exitRecordSelection,
                                                    enabled = !recordsDeleting,
                                                )
                                            }
                                        },
                                        actions = {
                                            if (page == TAB_RECORDS && recordsSelecting) {
                                                val visible = visibleRecords()
                                                val allSelected = visible.isNotEmpty() && visible.all { it.key in selectedRecordKeys }
                                                ModuleToolbarAction(
                                                    icon = ModuleActionIcon.SelectAll,
                                                    description = uiText(if (allSelected) R.string.records_deselect_all else R.string.records_select_all),
                                                    onClick = {
                                                        selectedRecordKeys = if (allSelected) emptySet()
                                                        else visible.mapTo(linkedSetOf()) { it.key }
                                                    },
                                                    enabled = !recordsDeleting,
                                                )
                                                ModuleToolbarAction(
                                                    icon = ModuleActionIcon.Delete,
                                                    description = uiText(R.string.records_delete_action),
                                                    onClick = { recordsDeleteConfirmation = true },
                                                    enabled = selectedRecordKeys.isNotEmpty() && !recordsDeleting,
                                                    tint = MiuixTheme.colorScheme.error,
                                                )
                                            } else if (page == TAB_RECORDS || page == TAB_TASKS) {
                                                ModuleToolbarAction(
                                                    icon = ModuleActionIcon.Accounts,
                                                    description = uiText(R.string.accounts_filter),
                                                    onClick = { accountsUi.show(if (page == TAB_RECORDS) AccountDialog.Records else AccountDialog.Tasks) },
                                                    enabled = !overlayOpen && !taskDragging && !pullRefreshing,
                                                    canActivate = page == currentPage && !isNavigating && !pagerState.isScrollInProgress,
                                                )
                                                if (page == TAB_TASKS) ModuleTaskSortAction(
                                                    mode = taskOrder.mode,
                                                    onSelect = ::setTaskSortMode,
                                                    enabled = taskRows.value.isNotEmpty() &&
                                                        !overlayOpen && !taskDragging && !pullRefreshing,
                                                    canActivate = page == currentPage && pagerState.settledPage == TAB_TASKS &&
                                                        !isNavigating && !pagerState.isScrollInProgress &&
                                                        !overlayOpen && !taskDragging && !pullRefreshing,
                                                    onExpandedChange = { taskSortPopupOpen = it },
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        },
                    ) { pagePadding ->
                        val listPadding = rememberModulePagePadding(pagePadding, padding)
                        Box(
                            Modifier.fillMaxSize()
                                .then(if (recordBackdrop) Modifier.captureBackdrop(pageBackdrop) {
                                    pageVisible.value
                                } else Modifier)
                                .background(surface)
                                .padding(rememberModuleHorizontalPadding(pagePadding)),
                        ) {
                            if (page == TAB_TASKS) {
                                PullToRefresh(
                                    isRefreshing = pullRefreshing,
                                    pullToRefreshState = pullState,
                                    onRefresh = {
                                        if (!taskDragging) {
                                            pullRefreshing = true
                                            recomputeSchedule { pullRefreshing = false }
                                        }
                                    },
                                    refreshTexts = listOf(uiText(R.string.refresh_pull), uiText(R.string.refresh_release), uiText(R.string.refresh_running), uiText(R.string.refresh_success)),
                                    contentPadding = pagePadding,
                                    topAppBarScrollBehavior = pageScroll,
                                ) {
                                    TasksPage(
                                        contentPadding = listPadding,
                                        listState = tasksListState,
                                        scrollBehavior = pageScroll,
                                        canStartDrag = {
                                            currentPage == TAB_TASKS && pagerState.settledPage == TAB_TASKS &&
                                                !pagerState.isScrollInProgress && !isNavigating && !overlayOpen &&
                                                !pullRefreshing && !taskSortPopupOpen && !recordsDeleting &&
                                                pullState.refreshState == RefreshState.Idle &&
                                                kotlin.math.abs(pullState.dragOffset) < 0.5f
                                        },
                                    )
                                }
                            } else if (page == TAB_RECORDS) {

                                RecordsPage(
                                    contentPadding = listPadding,
                                    scrollBehavior = pageScroll,
                                    listState = recordsState,
                                )
                            } else if (page == TAB_ABOUT) {

                                val aboutVisible by remember(pagerState) {
                                    computedStateOf { pagerState.layoutInfo.visiblePagesInfo.any { it.index == TAB_ABOUT } }
                                }
                                val aboutPlaying by remember(pagerState, isNavigating, blurSupported) {
                                    computedStateOf {
                                        blurSupported && aboutEffectResumed.value &&
                                            pagerState.currentPage == TAB_ABOUT && !isNavigating &&
                                            !pagerState.isScrollInProgress && pagerState.settledPage == TAB_ABOUT
                                    }
                                }
                                AboutPage(
                                    playing = aboutPlaying,
                                    bottomPadding = padding.calculateBottomPadding(),
                                    listState = aboutState,
                                    prepareEffect = aboutVisible,
                                    motion = aboutMotion,
                                    effectState = aboutEffectState,
                                )
                            } else {
                                ConfigPage(
                                    contentPadding = listPadding,
                                    scrollBehavior = pageScroll,
                                    listState = configListState,
                                    launcherListState = launcherListState,
                                )
                            }
                        }
                    }
                }
                Dialogs()
                if (swipeBack.value && (textDialog.value != null || editorDialog.value != null)) {
                    Box(Modifier.fillMaxSize().swipeBack { dismissTopOverlay() })
                }
            }
        }
    }

    @Composable
    private fun RecordsPage(contentPadding: PaddingValues, scrollBehavior: ScrollBehavior, listState: LazyListState) {
        val list = visibleRecords()
        LaunchedEffect(recordsAccount) {
            listState.scrollToItem(0)
            scrollBehavior.state.heightOffset = 0f
            scrollBehavior.state.contentOffset = 0f
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .modulePageScroll(scrollBehavior),
            overscrollEffect = null,
            contentPadding = contentPadding,
        ) {
            item(key = "account-scope", contentType = "summary") {
                Text(recordsAccount?.let(::accountLabel) ?: uiText(R.string.accounts_all),
                    modifier = Modifier.padding(horizontal = 28.dp), style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
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
                    uiText(R.string.record_summary, list.size, list.count { it.record.result != "success" }),
                    modifier = Modifier.padding(horizontal = 28.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(2.dp))
            }
            itemsIndexed(
                items = list,

                key = { _, row -> row.key },
                contentType = { _, _ -> "record" },
            ) { index, row ->

                CapturedAnimatedVisibility(
                    visible = row.key !in exitingRecordKeys,
                    modifier = if (recordsDeleting) Modifier else Modifier.animateItem(),
                    exit = fadeOut(tween(220)) + shrinkVertically(tween(260)),
                ) {
                    Column {
                        val day = dayLabel(row.record.at)
                        if (index == 0 || dayLabel(list[index - 1].record.at) != day) {
                            GroupTitle(day)
                        }
                        RecordCard(row)
                    }
                }
            }
        }
    }

    @Composable
    private fun RecordCard(row: ModuleRecordRow) {
        val record = row.record
        val failed = record.result != "success"
        val selected = row.key in selectedRecordKeys
        val deleting = row.key in deletingRecordKeys
        val colors = MiuixTheme.colorScheme
        val background by animateColorAsState(
            targetValue = when {
                deleting -> colors.errorContainer
                selected -> colors.tertiaryContainer
                else -> colors.surfaceContainer
            },
            animationSpec = tween(140),
            label = "recordSelectionSurface",
        )
        val haptic = LocalHapticFeedback.current
        val selectRecord = {
            if (!recordsDeleting) {
                recordsSelecting = true
                selectedRecordKeys = selectedRecordKeys + row.key
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
        ModuleRecordCard(
            title = if (failed) uiText(R.string.record_failed_title, taskTitle(record.taskType), resultLabel(record.result))
            else taskTitle(record.taskType),
            titleColor = when {
                deleting -> colors.onErrorContainer
                failed -> DangerRed
                else -> colors.onSurface
            },
            containerColor = background,
            enabled = !recordsDeleting,
            modifier = Modifier.semantics {
                this.selected = selected
                if (!recordsSelecting) {
                    customActions = listOf(CustomAccessibilityAction(uiText(R.string.records_selection_enter)) {
                        selectRecord()
                        true
                    })
                }
            },
            trailing = {
                if (recordsSelecting) {
                    Checkbox(
                        state = if (selected) ToggleableState.On else ToggleableState.Off,
                        onClick = { toggleRecordSelection(row.key) },
                        enabled = !recordsDeleting,
                        modifier = Modifier.semantics {
                            contentDescription = "${accountLabel(row.accountId)} ${taskTitle(record.taskType)} ${formatDateTime(record.at)}"
                        },
                    )
                } else {
                    Text(
                        formatClock(record.at),
                        fontSize = 12.sp,
                        fontWeight = FontWeight(550),
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            },
            description = accountLabel(row.accountId) + record.message.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty(),
            onClick = {
                if (!recordsDeleting) {
                    if (recordsSelecting) toggleRecordSelection(row.key)
                    else showRecordDetail(row.accountId, record)
                }
            },
            onLongPress = selectRecord,
        )
    }

    @Composable
    private fun TasksPage(
        contentPadding: PaddingValues, listState: LazyListState,
        scrollBehavior: ScrollBehavior,
        canStartDrag: () -> Boolean,
    ) {
        val sourceRows = taskRows.value.filter { tasksAccount == null || it.accountId == tasksAccount }
        LaunchedEffect(tasksAccount) {
            listState.scrollToItem(0)
            scrollBehavior.state.heightOffset = 0f
            scrollBehavior.state.contentOffset = 0f
        }
        val scopedOverview = if (tasksAccount == null) overviewText.value else {
            val now = System.currentTimeMillis()
            val next = sourceRows.filter { it.task.enabled && it.effectiveNextRunAt > now }
                .minOfOrNull { it.effectiveNextRunAt }?.let(::formatTime) ?: uiText(R.string.common_none)
            uiText(R.string.task_overview_body, accounts.count { it.id == tasksAccount }, sourceRows.count { it.task.enabled }, next) +
                if (rootEnhancementEnabled.value) "" else "\n" + uiText(R.string.root_manual_only)
        }
        val rows = remember(sourceRows, taskOrder) {
            val keyed = sourceRows.associateBy { moduleTaskOrderKey(it.accountId, it.task.taskType) }
            orderedModuleTaskKeys(taskOrder, sourceRows.map {
                ModuleTaskOrderEntry(moduleTaskOrderKey(it.accountId, it.task.taskType), it.task.enabled)
            }).map(keyed::getValue)
        }
        val orderedKeys = remember(rows) { rows.map { moduleTaskOrderKey(it.accountId, it.task.taskType) } }
        val haptic = LocalHapticFeedback.current
        val reorderState = rememberModuleTaskReorderState(
            listState = listState,
            orderedKeys = orderedKeys,
            onMove = { from, target -> taskOrder = taskOrder.move(from, target) },
            onDragStateChange = { active ->
                taskDragging = active
                if (active) {
                    taskSortPopupOpen = false
                    taskOrder = taskOrder.startManual(orderedKeys)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
            onDragFinished = ::persistTaskOrder,
            enabled = canStartDrag,
        )
        DisposableEffect(Unit) { onDispose { taskDragging = false; taskSortPopupOpen = false } }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .taskReorderContainer(reorderState)
                // PullToRefresh already forwards this page's top-bar scroll connection.
                .modulePageScroll(scrollBehavior, connectNestedScroll = false),
            overscrollEffect = null,
            contentPadding = contentPadding,
        ) {
            item(key = "overview", contentType = "overview") {
                SectionCard(
                    title = uiText(R.string.task_overview_title),
                    description = (tasksAccount?.let(::accountLabel) ?: uiText(R.string.accounts_all)) + "\n" + scopedOverview,
                ) {
                    CapsuleButton(moduleActionIcon(ModuleActionIcon.Accounts), uiText(R.string.accounts_manage)) {
                        accountsUi.show(AccountDialog.Manage)
                    }
                }
            }
            if (rows.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    SectionCard(
                        title = uiText(R.string.tasks_empty_title),
                        description = uiText(R.string.tasks_empty_description),
                    )
                }
            } else {
                itemsIndexed(rows, key = { _, row -> moduleTaskOrderKey(row.accountId, row.task.taskType) }, contentType = { _, _ -> "task" }) { index, row ->
                    val orderKey = moduleTaskOrderKey(row.accountId, row.task.taskType)
                    val moveUp = uiText(R.string.task_move_up)
                    val moveDown = uiText(R.string.task_move_down)
                    Box(
                        Modifier
                            .taskReorderItem(reorderState, orderKey)
                            .animateItem(
                                placementSpec = if (reorderState.isLifted(orderKey)) null
                                else androidx.compose.animation.core.spring(
                                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                                ),
                            )
                            .semantics {
                                customActions = buildList {
                                    if (index > 0) add(CustomAccessibilityAction(moveUp) {
                                        moveTaskForAccessibility(orderedKeys, orderKey, orderedKeys[index - 1])
                                        true
                                    })
                                    if (index < orderedKeys.lastIndex) add(CustomAccessibilityAction(moveDown) {
                                        moveTaskForAccessibility(orderedKeys, orderKey, orderedKeys[index + 1])
                                        true
                                    })
                                }
                            },
                    ) {
                        TaskCard(row, Modifier.moduleTaskShadow { reorderState.isLifted(orderKey) })
                    }
                }
            }
            item(key = "sync-note", contentType = "note") {
                Text(
                    uiText(R.string.tasks_sync_note),
                    modifier = Modifier.padding(horizontal = 28.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    @Composable
    private fun ConfigPage(
        contentPadding: PaddingValues,
        scrollBehavior: ScrollBehavior,
        listState: LazyListState,
        launcherListState: LazyListState,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().modulePageScroll(scrollBehavior),
            overscrollEffect = null,
            contentPadding = contentPadding,
        ) {
            item(key = "language.title", contentType = "group-title") {
                GroupTitle(stringResource(R.string.section_language))
            }
            modulePreferenceGroup("language", listOf("language"), { it }) {
                ModuleDropdownPreference(
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
            moduleThemeSettings(config = themeConfig, onChange = ::updateThemeConfig)
            item(key = "personalization.title", contentType = "group-title") {
                GroupTitle(stringResource(R.string.section_personalization))
            }
            modulePreferenceGroup(
                "personalization",
                listOf("icon", "recent", "launcher"),
                { it },
            ) { row ->
                when (row) {
                    "icon" -> Column {
                        Text(
                            text = stringResource(R.string.pref_app_icon_title),
                            fontSize = MiuixTheme.textStyles.headline1.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 10.dp),
                        )
                        LauncherIconPicker(launcherListState)
                        Spacer(Modifier.height(12.dp))
                    }
                    "recent" -> SwitchPreference(
                        title = stringResource(R.string.pref_hide_recent_title),
                        summary = stringResource(R.string.pref_hide_recent_summary),
                        checked = hideRecentTask.value,
                        onCheckedChange = { toggleHideRecentTask() },
                    )
                    "launcher" -> SwitchPreference(
                        title = stringResource(R.string.pref_hide_launcher_title),
                        summary = stringResource(R.string.pref_hide_launcher_summary),
                        checked = hideLauncherIcon.value,
                        onCheckedChange = ::toggleHideLauncherIcon,
                    )
                }
            }
            item(key = "gestures.title", contentType = "group-title") {
                GroupTitle(stringResource(R.string.section_gesture))
            }
            modulePreferenceGroup(
                "gestures",
                listOf("predictive", "swipe", "pager"),
                { it },
            ) { row ->
                when (row) {
                    "predictive" -> ModuleDropdownPreference(
                        title = stringResource(R.string.pref_predictive_back_title),
                        summary = stringResource(R.string.pref_predictive_back_summary),
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                        items = listOf(
                            stringResource(R.string.predictive_back_off),
                            stringResource(R.string.predictive_back_miuix),
                            stringResource(R.string.predictive_back_aosp),
                        ),
                        selectedIndex = predictiveBackMode.ordinal,
                        onSelectedIndexChange = { changePredictiveBackMode(ModulePredictiveBackMode.entries[it]) },
                    )
                    "swipe" -> SwitchPreference(
                        title = stringResource(R.string.pref_swipe_back_title),
                        summary = stringResource(R.string.pref_swipe_back_summary),
                        checked = swipeBack.value,
                        onCheckedChange = ::toggleSwipeBack,
                    )
                    "pager" -> ModuleDropdownPreference(
                        title = stringResource(R.string.pref_pager_gesture_title),
                        summary = stringResource(R.string.pref_pager_gesture_summary),
                        items = listOf(
                            stringResource(R.string.pager_gesture_default),
                            stringResource(R.string.pager_gesture_cross),
                        ),
                        selectedIndex = themeConfig.active.pagerMode.ordinal,
                        onSelectedIndexChange = {
                            updateThemeConfig(themeConfig.withActive(themeConfig.active.copy(pagerMode = PagerInterceptionMode.entries[it])))
                        },
                    )
                }
            }
            item(key = "root.title", contentType = "group-title") {
                GroupTitle(uiText(R.string.root_enhancement_title))
            }
            modulePreferenceGroup("root", ModuleRootPreferenceRow.entries, { it.name }) { row ->
                RootEnhancementPreference(
                    row = row,
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

            item(key = "notification-permission", contentType = "preferences") {
                GroupTitle(uiText(R.string.section_notifications_logs))
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), insideMargin = PaddingValues(0.dp)) {
                    ArrowPreference(title = uiText(R.string.permission_notification_settings),
                        summary = uiText(R.string.root_notification_hint), onClick = { requestNotificationPermission() })
                }
            }
            item(key = "notification-records", contentType = "actions") {
                SectionCard(
                    title = uiText(R.string.notification_records_title),
                    description = notificationSummary.value,
                ) {
                    CapsuleButton(moduleActionIcon(ModuleActionIcon.Info), uiText(R.string.action_view)) { showNotificationRecords() }
                    Spacer(Modifier.weight(1f))
                    CapsuleButton(moduleActionIcon(ModuleActionIcon.Delete), uiText(R.string.action_clear)) {
                        NotificationRecordStore { applicationContext }.clear()
                        toast(uiText(R.string.toast_notifications_cleared))
                        refresh()
                    }
                }
            }
            item(key = "logs", contentType = "actions") {
                SectionCard(
                    title = uiText(R.string.module_logs_title),
                    description = logSummary.value,
                ) {
                    CapsuleButton(moduleActionIcon(ModuleActionIcon.Info), uiText(R.string.action_view)) { showLogs() }
                    Spacer(Modifier.weight(1f))
                    CapsuleButton(moduleActionIcon(ModuleActionIcon.Delete), uiText(R.string.action_clear)) {
                        ModuleLogBuffer.clear()
                        toast(uiText(R.string.toast_logs_cleared))
                        refresh()
                    }
                }
            }
        }
    }

    @Composable
    private fun TaskCard(row: TaskRow, modifier: Modifier = Modifier) {
        SectionCard(
            title = taskTitle(row.task.taskType),
            bodyModifier = modifier,
            showActionDivider = true,
            titleColor = if (row.task.enabled) MiuixTheme.colorScheme.onSurface
            else MiuixTheme.colorScheme.onSurfaceVariantActions,
            description = accountLabel(row.accountId) + "\n" + row.subtitle,
            trailing = {
                Switch(
                    checked = row.task.enabled,
                    enabled = (row.accountId to row.task.taskType) !in taskMutations.value,
                    onCheckedChange = { enabled -> setTaskEnabled(row.accountId, row.task, enabled) },
                )
            },
        ) {
            CapsuleButton(moduleActionIcon(ModuleActionIcon.Play), uiText(R.string.action_run)) { runSingleTask(row.accountId, row.task) }
            Spacer(Modifier.weight(1f))
            CapsuleButton(moduleActionIcon(ModuleActionIcon.Settings), uiText(R.string.action_configure)) { openTaskEditor(row.accountId, row.task, row.spec) }
        }
    }

    private fun importAccounts(read: () -> String) {
        if (accountsUi.busy) return
        accountsUi.show(AccountDialog.Import)
        accountsUi.busy = true
        runBg(work = {
            runCatching {
                val imported = LocalTaskCredentials.parse(read())
                LocalTaskStore { applicationContext }.importCredentials(imported)
                imported.size
            }
        }, then = { result ->
            accountsUi.busy = false
            result.onSuccess { count ->
                nicknameRefreshAttempts.clear()
                accountsUi.credential = TextFieldState()
                accountsUi.show(AccountDialog.Manage)
                toast(uiText(R.string.accounts_imported, count))
                refresh()
            }.onFailure { accountsUi.error = it.message ?: uiText(R.string.accounts_import_failed) }
        })
    }

    private fun saveAccountRemark(account: LocalTaskAccount, remark: String) {
        if (accountsUi.busy) return
        accountsUi.busy = true
        runBg(work = { runCatching { LocalTaskStore { applicationContext }.setAccountRemark(account.id, remark) } }, then = { result ->
            accountsUi.busy = false
            result.onSuccess { accountsUi.show(AccountDialog.Manage); refresh() }
                .onFailure { accountsUi.error = it.message.orEmpty() }
        })
    }

    private fun deleteAccount(account: LocalTaskAccount) {
        if (accountsUi.busy) return
        accountsUi.busy = true
        runBg(work = { runCatching { LocalTaskRunner.deleteAccount(applicationContext, account.id) } }, then = { result ->
            accountsUi.busy = false
            result.onSuccess { syncError ->
                accountsUi.editing = null
                accountsUi.show(AccountDialog.Manage)
                if (recordsAccount == account.id) { exitRecordSelection(); recordsAccount = null }
                if (tasksAccount == account.id) tasksAccount = null
                accounts = accounts.filterNot { it.id == account.id }
                taskRows.value = taskRows.value.filterNot { it.accountId == account.id }
                recordRows.value = recordRows.value.filterNot { it.accountId == account.id }
                if (editorDialog.value?.accountId == account.id) editorDialog.value = null
                toast(uiText(R.string.accounts_deleted, account.label))
                if (syncError != null) accountsUi.error = uiText(R.string.accounts_delete_sync_failed, syncError)
                refresh()
            }.onFailure { accountsUi.error = it.message ?: uiText(R.string.accounts_delete_failed) }
        })
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
                    RootTaskBridge.synchronize(applicationContext) to System.currentTimeMillis()
                }
            },
            then = { result ->
                rootSyncing.value = false
                rootBusy.value = false
                result.onSuccess { (active, at) ->
                    rootSyncedAt.value = at
                    toast(uiText(if (active) R.string.root_sync_success else R.string.root_sync_stopped))
                }.onFailure {
                    rootSyncError.value = it.message ?: it.javaClass.simpleName
                    toast(uiText(R.string.root_sync_failed, rootSyncError.value))
                }
                refresh()
            },
        )
    }

    @Composable
    private fun LauncherIconPicker(listState: LazyListState) {
        val current = launcherIconStyle.value
        DisposableEffect(Unit) {
            onDispose { launcherIconCoordinates = null }
        }
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .overScrollHorizontal()
                .onGloballyPositioned { launcherIconCoordinates = it },
            contentPadding = PaddingValues(horizontal = 12.dp),
            overscrollEffect = null,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(ModuleIconStyle.entries, key = { it.key }, contentType = { "launcher-icon" }) { style ->
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
            work = { LocalTaskRunner.runTaskNow(applicationContext, accountId, task.taskType, task.accountGeneration) },
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
                check(store.get(accountId, task.taskType) != null) { "任务不存在，请刷新后重试" }
                store.setEnabled(accountId, task.taskType, enabled, expected = task)
                readTaskRow(store, accountId, task.taskType)
            }
        }, then = { result ->
            taskMutations.value = taskMutations.value - key
            result.onSuccess { saved ->
                applySavedTask(saved)
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
        window.setBackgroundDrawable(ColorDrawable(windowThemeSurface()))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, tab.intValue)
        outState.putString(STATE_RECORD_ACCOUNT, recordsAccount)
        outState.putString(STATE_TASK_ACCOUNT, tasksAccount)
        outState.putBoolean(STATE_RECORD_SELECTION, recordsSelecting && !recordsDeleting)
        outState.putStringArrayList(STATE_SELECTED_RECORDS, ArrayList(if (recordsDeleting) emptySet() else selectedRecordKeys))
        super.onSaveInstanceState(outState)
    }

    private fun updateThemeConfig(config: ModuleThemeConfig) {
        val value = config
        val modeChanged = themeConfig.nightMode != value.nightMode
        themePreferences.write(value)
        themeConfig = value
        if (modeChanged) syncApplicationNightMode(value.nightMode)
    }

    private fun syncApplicationNightMode(mode: ModuleNightMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val nightMode = when (mode) {
            ModuleNightMode.Light -> UiModeManager.MODE_NIGHT_NO
            ModuleNightMode.Dark -> UiModeManager.MODE_NIGHT_YES
            else -> UiModeManager.MODE_NIGHT_AUTO
        }
        runCatching {
            getSystemService(UiModeManager::class.java)?.setApplicationNightMode(nightMode)
        }.onFailure { error ->

            ModuleAndroidLog.legacy(LOG_TAG, "splash night mode sync failed: ${error.javaClass.simpleName}")
        }
    }

    private fun toggleSwipeBack(enabled: Boolean) {
        writeUiPref(KEY_SWIPE_BACK, enabled)
        swipeBack.value = enabled
    }

    private fun changePredictiveBackMode(mode: ModulePredictiveBackMode) {
        if (mode == predictiveBackMode) return
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
            .putString(ModulePredictiveBackMode.KEY, mode.name)
            .putBoolean(KEY_PREDICTIVE_BACK, mode.enabled)
            .apply()
        predictiveBackMode = mode
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
            accountsUi.open -> accountsUi.close()
            recordsDeleteConfirmation -> if (!recordsDeleting) recordsDeleteConfirmation = false
            rootConfirmation.value != null -> rootConfirmation.value = null
            rootModuleDialog.value -> rootModuleDialog.value = false
            suPathDialog.value != null -> suPathDialog.value = null
            editor?.multiOpenLabel != null -> editor.multiOpenLabel = null
            editor != null -> editorDialog.value = null
            textDialog.value != null -> textDialog.value = null
        }
    }

    private fun toggleRecordSelection(key: String) {
        if (recordsDeleting) return
        selectedRecordKeys = if (key in selectedRecordKeys) selectedRecordKeys - key else selectedRecordKeys + key
    }

    private fun exitRecordSelection() {
        if (recordsDeleting) return
        recordsSelecting = false
        selectedRecordKeys = emptySet()
        recordsDeleteConfirmation = false
    }

    private fun deleteSelectedRecords() {
        if (recordsDeleting) return
        val chosen = recordRows.value.filter { it.key in selectedRecordKeys }
        if (chosen.isEmpty()) {
            recordsDeleteConfirmation = false
            return
        }
        recordsDeleting = true
        runBg(
            work = {
                val deleted = mutableSetOf<String>()
                var count = 0
                val result = runCatching {
                    val store = LocalTaskStore { applicationContext }
                    chosen.groupBy { it.accountId }.forEach { (account, rows) ->
                        count += store.deleteRecords(account, rows.map { it.record })
                        deleted += rows.map { it.key }
                    }
                }
                RecordDeletionResult(deleted, count, result.exceptionOrNull())
            },
            then = { result ->
                recordsDeleteConfirmation = false
                if (result.deletedKeys.isNotEmpty()) {
                    deletingRecordKeys = result.deletedKeys
                    if (result.count > 0) toast(uiQuantity(R.plurals.records_deleted, result.count))
                } else {
                    recordsDeleting = false
                }
                result.error?.let {
                    recordDeletionError = result.deletedKeys.isNotEmpty()
                    toast(uiText(R.string.records_delete_failed))
                }
                refresh()
            },
        )
    }

    private fun setTaskSortMode(mode: ModuleTaskSort) {
        if (taskDragging || taskOrder.mode == mode) return
        taskOrder = taskOrder.copy(mode = mode)
        persistTaskOrder()
    }

    private fun moveTaskForAccessibility(keys: List<String>, key: String, target: String) {
        if (taskDragging) return
        taskOrder = taskOrder.startManual(keys).move(key, target)
        persistTaskOrder()
    }

    private fun persistTaskOrder() {
        taskOrderPreferences.writeAsync(taskOrder) {
            runOnUiThread {
                if (!isDestroyed && !isFinishing) toast(uiText(R.string.task_order_save_failed))
            }
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
        if (spec?.merchant != true) put(FIELD_TIME, task.timeOfDay)
        if (spec?.autoRead == true) {
            put(FIELD_DURATION, task.durationMinutes.toString())
            put(FIELD_BOOKS, task.books.joinToString("\n") { "${it.bookId}|${it.name}" })
        }
        if (spec?.rewardTriggered == true) {
            put(FIELD_DRAW_LIMIT, task.dailyDrawLimit.toString())
            put(FIELD_DRAW_CUSTOM_TIME, task.drawAtCustomTime.toString())
        }
        if (spec?.taskType == "pawn") {

            put(
                FIELD_PAWN,
                (task.forbiddenPawnPropIds + CloudTaskLocalRunner.PROHIBITED_PAWN_PROP_HINTS.keys.filter { it.startsWith("name:") })
                    .sorted()
                    .joinToString(","),
            )
            editor.multiOptions[FIELD_PAWN] = pawnOptionsFromState(accountId)

            runBg(work = { runCatching { fetchPawnOptions(accountId, task.accountGeneration) } }, then = { result ->
                if (editorDialog.value !== editor) return@runBg
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
            put(FIELD_TRANSPORT, task.merchantTransportId.toString())

            editor.merchantLoading = true
            runBg(work = { runCatching { fetchMerchantChoices(accountId, task.accountGeneration) } }, then = { result ->
                if (editorDialog.value !== editor) return@runBg
                editor.merchantLoading = false
                val fetched = result.getOrNull()
                if (fetched == null) {
                    toast(result.exceptionOrNull()?.message ?: uiText(R.string.error_merchant_load))
                } else {
                    editor.merchantCities = fetched.cities
                    editor.merchantTransports = fetched.transports
                    editor.merchantNoTransportCapacity = fetched.noTransportCapacity
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

    private fun fetchPawnOptions(accountId: String, generation: String): List<ModulePawnOption> {
        val pawnStore = LocalTaskStore { applicationContext }
        check(pawnStore.accountGeneration(accountId) == generation) { "账号已变更，请重新打开配置" }
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
        val patch = JSONObject().put(CloudTaskLocalRunner.KEY_PAWN_PROP_CATALOG, merged)
            .put(com.reamicro.fix.cloud.local.LocalTaskRuntimeMerge.ACCOUNT_CONTEXT, generation)
        pawnStore.recordState(accountId, "pawn", patch)
        return CloudTaskLocalRunner.pawnPropChoices(patch).map {
            ModulePawnOption(it.propId, it.name, cloudTaskQualityColor(it.quality), it.hint)
        }
    }

    private fun fetchMerchantChoices(accountId: String, generation: String): CloudTaskLocalRunner.MerchantOptionsFetch {
        val store = LocalTaskStore { applicationContext }
        check(store.accountGeneration(accountId) == generation) { "账号已变更，请重新打开配置" }
        val token = store.token(accountId)
        return CloudTaskLocalRunner.fetchMerchantOptions(token).also {
            check(store.accountGeneration(accountId) == generation) { "账号已变更，请重新打开配置" }
        }
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
                store.saveEditedTask(editor.accountId, updated, "", editor.task)
                readTaskRow(store, editor.accountId, updated.taskType)
            }
        }, then = { result ->
            editor.saving = false
            result.onSuccess { saved ->
                applySavedTask(saved)
                toast(uiText(R.string.toast_config_saved))
                if (editorDialog.value === editor) editorDialog.value = null
            }.onFailure { toast(it.message ?: "任务保存失败") }
            refresh()
        })
    }

    private fun showRecordDetail(accountId: String, record: LocalTaskRecord) {
        textDialog.value = TextDialogUi(
            title = { uiText(R.string.record_detail_title, taskTitle(record.taskType)) },
            content = { accountLabel(accountId) + "\n\n" + moduleRecordDetailText(uiContext, accountId, record, taskTitle(record.taskType), resultLabel(record.result)) },
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
    private fun AboutPage(
        playing: Boolean,
        bottomPadding: Dp,
        listState: LazyListState,
        prepareEffect: Boolean,
        motion: com.reamicro.fix.ui.effect.AboutBrandMotion,
        effectState: com.reamicro.fix.ui.effect.BgEffectState,
    ) {
        val iconStyle = launcherIconStyle.value
        com.reamicro.fix.ui.effect.AboutScrollLayout(
            playing = playing,
            bottomPadding = bottomPadding,
            listState = listState,
            prepareEffect = prepareEffect,
            motion = motion,
            effectState = effectState,
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
                        onClick = { openUrl("https://github.com/compose-miuix-ui/miuix") },
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
                val status = if (failure is com.reamicro.fix.cloud.root.RootAccessException) {
                    com.reamicro.fix.cloud.root.RootModuleStatus(access = failure.report, error = failure.report.detail)
                } else {
                    runCatching { RootTaskBridge.inspect(applicationContext) }.getOrElse { error ->
                        (RootTaskBridge.lastInspection ?: com.reamicro.fix.cloud.root.RootModuleStatus()).copy(
                            inspectionComplete = false,
                            error = error.message ?: error.javaClass.simpleName,
                        )
                    }
                }
                result to status
            },
            then = { (result, status) ->
                rootBusy.value = false
                toast(result.getOrElse { it.message ?: it.javaClass.simpleName })
                rootUi.value = status
                if (result.isSuccess && !status.inspectionComplete) toast(status.error)
                refresh()
            },
        )
    }

    @Composable
    private fun Dialogs() {
        ModuleTaskAccountDialogs(
            ui = accountsUi, accounts = accounts,
            selected = if (accountsUi.dialog == AccountDialog.Records) recordsAccount else tasksAccount,
            onSelect = { id ->
                if (accountsUi.dialog == AccountDialog.Records) {
                    exitRecordSelection(); recordsAccount = id
                } else tasksAccount = id
            },
            onImport = { raw -> importAccounts { raw } },
            onChooseFile = { importCredentialFile.launch(arrayOf("*/*")) },
            onSaveRemark = ::saveAccountRemark,
            onDelete = ::deleteAccount,
        )
        AppWindowDialog(
            show = recordsDeleteConfirmation,
            title = uiText(R.string.records_delete_title),
            onClose = { if (!recordsDeleting) recordsDeleteConfirmation = false },
            primaryLabel = uiText(R.string.records_delete_action),
            onPrimary = ::deleteSelectedRecords,
            primaryEnabled = selectedRecordKeys.isNotEmpty() && !recordsDeleting,
            dangerous = true,
            dismissEnabled = !recordsDeleting,
        ) {
            Text(uiQuantity(R.plurals.records_delete_confirm, selectedRecordKeys.size))
        }
        RootModuleDialog(
            show = rootModuleDialog.value, busy = rootBusy.value, status = rootUi.value,
            lastError = RootTaskBridge.lastOperationError(applicationContext),
            onClose = { rootModuleDialog.value = false },
            onInstall = { rootModuleDialog.value = false; rootAction { RootTaskBridge.installOrUpdate(uiContext) } },
            onSuPath = { rootModuleDialog.value = false; suPathDialog.value = TextFieldState(RootTaskBridge.customSuPath(applicationContext)) },
            onUninstall = { rootModuleDialog.value = false; rootConfirmation.value = RootEnhancementAction.UNINSTALL },
        )
        RetainedWindowContent(rootConfirmation.value) { action, show, onDismissFinished ->
            RootEnhancementDialog(
                show = show, title = uiText(R.string.root_enhancement_title),
                onDismissFinished = onDismissFinished,
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
                Column {
                    BasicComponent(title = uiText(R.string.root_confirmation_title),
                        summary = uiText(when (action) {
                            RootEnhancementAction.ENABLE -> R.string.root_confirm_enable
                            RootEnhancementAction.DISABLE -> R.string.root_confirm_disable
                            RootEnhancementAction.UNINSTALL -> R.string.root_confirm_uninstall
                        }))
                }
            }
        }
        RetainedWindowContent(suPathDialog.value) { path, show, onDismissFinished ->
            RootEnhancementDialog(
                show = show, title = uiText(R.string.execution_su_path),
                onDismissFinished = onDismissFinished,
                onClose = { suPathDialog.value = null },
                primaryLabel = uiText(R.string.action_save), primaryEnabled = !rootBusy.value,
                onPrimary = {
                    if (!rootBusy.value) {
                        val requestedPath = path.text.toString()
                        rootBusy.value = true
                        runBg(
                            work = { runCatching { RootTaskBridge.setCustomSuPath(applicationContext, requestedPath) } },
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
                    state = path)
                Text(uiText(R.string.execution_su_path_description),
                    style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
        RetainedWindowContent(textDialog.value) { dialog, show, onDismissFinished ->
            val primary = dialog.actions.singleOrNull()
            AppWindowDialog(
                show = show, title = dialog.title(), onClose = { textDialog.value = null },
                onDismissFinished = onDismissFinished,
                primaryLabel = primary?.let { uiText(it.first) }, onPrimary = primary?.second,
            ) {
                Text(dialog.content(), style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainer)
                if (dialog.actions.size > 1) {
                    ModuleDialogCard {
                        dialog.actions.forEach { (label, action) ->
                            ArrowPreference(title = uiText(label), onClick = action)
                        }
                    }
                }
            }
        }
        RetainedWindowContent(editorDialog.value) { editor, show, onDismissFinished ->
            key(editor) { TaskEditorDialog(editor, show, onDismissFinished) }
        }
    }

    @Composable
    private fun TaskEditorDialog(editor: TaskEditor, show: Boolean, onDismissFinished: () -> Unit) {
        val taskTextStyles = MiuixTheme.textStyles.copy(
            body2 = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 20.sp),
        )
        MiuixTheme(colors = MiuixTheme.colorScheme, textStyles = taskTextStyles) {
            AppWindowDialog(
                show = show, title = taskTitle(editor.task.taskType),
                onDismissFinished = onDismissFinished,
                onClose = { if (!editor.saving) editorDialog.value = null },
                dismissEnabled = !editor.saving,
                primaryLabel = uiText(R.string.action_save),
                primaryEnabled = !editor.saving,
                onPrimary = { saveTaskEdits(editor) },
            ) {
                Text(accountLabel(editor.accountId), style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(bottom = 12.dp))
                val spec = editor.spec
                val customDrawTime = editor.values[FIELD_DRAW_CUSTOM_TIME] == "true"
                if (spec?.rewardTriggered == true) {
                    SwitchPreference(
                        title = uiText(R.string.task_wish_custom_time),
                        summary = if (customDrawTime) uiText(R.string.task_wish_custom_time_hint)
                            else uiText(R.string.task_wish_trigger),
                        checked = customDrawTime,
                        enabled = !editor.saving,
                        onCheckedChange = { editor.values[FIELD_DRAW_CUSTOM_TIME] = it.toString() },
                        insideMargin = PaddingValues(vertical = 12.dp),
                    )
                }
                if (spec?.merchant != true && (spec?.rewardTriggered != true || customDrawTime)) {
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
                    Text(
                        text = uiText(R.string.task_wish_schedule_hint),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
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
                RetainedWindowContent(editor.multiOpenLabel.takeIf { show }) { label, pickerShown, pickerDismissed ->
                    key(label) { MultiSelectOverlay(editor, label, pickerShown, pickerDismissed) }
                }
            }
        }
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
        ModuleDialogTextField(
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
        CapsuleTextButton(label = modulePawnSelectionSummary(uiContext, options, picked)) {
            editor.multiOpenLabel = FIELD_PAWN
        }
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
            if (useModuleGlass()) {
                top.yukonga.miuix.kmp.integration.EntryGlassDropdownPreference(
                    entry = entry,
                    title = title,
                    summary = summary,
                    backdrop = LocalModuleSceneBackdrop.current,
                    enabled = choices.isNotEmpty(),
                    insideMargin = PaddingValues(vertical = 8.dp),
                    sizing = top.yukonga.miuix.kmp.glass.GlassPopupSizing(maxHeight = 320.dp),
                    onExpandedChange = { expanded ->
                        if (expanded) { focusManager.clearFocus(); keyboard?.hide() }
                    },
                )
            } else {
                OverlayDropdownPreference(
                    entry = entry,
                    title = title,
                    summary = summary,
                    enabled = choices.isNotEmpty(),
                    insideMargin = PaddingValues(vertical = 8.dp),
                    maxHeight = 320.dp,
                    onExpandedChange = { expanded ->
                        if (expanded) { focusManager.clearFocus(); keyboard?.hide() }
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    private fun cityAccepts(
        city: CloudTaskLocalRunner.MerchantCityOption,
        transport: CloudTaskLocalRunner.MerchantTransportOption?,
    ): Boolean = CloudTaskLocalRunner.merchantCityAccepts(city, transport)

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
        // 车马列表始终展示全部已拥有项，否则陆路配置会隐藏海船，玩家无法切换到海路。
        // 选择与当前城池不兼容的车马后，下面的 onSelect 会清空城池并重新展示兼容城池。
        val compatible = CloudTaskLocalRunner.merchantTransportChoices(editor.merchantTransports, selectedCity)

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
    private fun MultiSelectOverlay(editor: TaskEditor, label: String, show: Boolean, onDismissFinished: () -> Unit) {
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
                work = { runCatching { fetchPawnOptions(editor.accountId, editor.task.accountGeneration) } },
                then = { result ->
                    if (editorDialog.value !== editor) return@runBg
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

        ModuleWindowDialog(
            title = uiText(R.string.field_pawn),
            show = show,
            onDismissFinished = onDismissFinished,
            onClose = { editor.multiOpenLabel = null },
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
                                .heightIn(min = 48.dp)
                                .padding(vertical = 4.dp),
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
                ModuleDialogActionButton(
                    text = uiText(R.string.action_cancel),
                    onClick = LocalModuleWindowCloseRequest.current ?: { editor.multiOpenLabel = null },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                ModuleDialogActionButton(
                    text = uiText(R.string.action_done),
                    modifier = Modifier.weight(1f),
                    primary = true,
                    onClick = {

                        editor.values[label] = picked
                            .sortedWith(compareBy({ it.toLongOrNull() ?: Long.MAX_VALUE }, { it }))
                            .joinToString(",")
                        editor.multiOpenLabel = null
                    },
                )
            }
        }
    }

    private fun showPendingNotificationPermission() {
        if (hasOpenOverlay()) return
        val channelId = com.reamicro.fix.notification.ModuleNotificationPermission.takePendingChannel(this) ?: return
        textDialog.value = TextDialogUi(
            title = { uiText(R.string.notification_permission_title) },
            content = { uiText(R.string.notification_permission_explanation) },
            actions = listOf(R.string.notification_permission_enable to {
                textDialog.value = null
                requestNotificationPermission(channelId)
            }),
        )
    }

    private fun hasOpenOverlay(): Boolean =
        textDialog.value != null || editorDialog.value != null || suPathDialog.value != null ||
            rootModuleDialog.value || rootConfirmation.value != null || recordsDeleteConfirmation || accountsUi.open

    private fun requestNotificationPermission(channelId: String? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!uiPrefBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED) ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            writeUiPref(KEY_NOTIFICATION_PERMISSION_REQUESTED, true)
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching {
                val channelSettings = channelId != null && CloudTaskNotifications.hasPermission(this) &&
                    getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() == true
                startActivity(Intent(if (channelSettings) android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                    else android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                    .apply { if (channelSettings) putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, channelId) })
            }.onFailure { toast(uiText(R.string.error_open_settings)) }
        }
    }

    private fun hideRecentTaskEnabled(): Boolean = uiPrefBoolean(KEY_HIDE_RECENT_TASK)

    private fun uiPrefBoolean(key: String, defValue: Boolean = false): Boolean =
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).getBoolean(key, defValue)

    private fun writeUiPref(key: String, value: Boolean) {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putBoolean(key, value).apply()
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
        val effectiveNextRunAt: Long,
    )

    private data class RecordDeletionResult(val deletedKeys: Set<String>, val count: Int, val error: Throwable?)

    private data class UiData(
        val records: List<Pair<String, LocalTaskRecord>>,
        val tasks: List<TaskRow>,
        val accounts: List<LocalTaskAccount>,
        val rootEnabled: Boolean,
        val overview: String,
        val notifications: String,
        val logs: String,
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
        const val STATE_RECORD_ACCOUNT = "recordsAccount"
        const val STATE_TASK_ACCOUNT = "tasksAccount"
        const val STATE_RECORD_SELECTION = "recordSelectionActive"
        const val STATE_SELECTED_RECORDS = "selectedRecordKeys"
        const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"

        const val UI_PREFS = "reamicro_module_ui"
        const val KEY_HIDE_RECENT_TASK = "hideRecentTask"
        const val KEY_HIDE_LAUNCHER_ICON = "hideLauncherIcon"
        const val KEY_LAUNCHER_ICON_STYLE = "launcherIconStyle"

        const val KEY_LAUNCHER_ICON_PENDING = "launcherIconPending"
        const val KEY_LAUNCHER_ICON_PENDING_HIDDEN = "launcherIconPendingHidden"
        const val KEY_SWIPE_BACK = "swipeBack"
        const val KEY_PREDICTIVE_BACK = "predictiveBack"

        const val MODULE_PACKAGE = "com.reamicro.fix"
        const val MAIN_ACTIVITY_CLASS = "com.reamicro.fix.ui.ModuleMainActivity"

        const val GITHUB_REPO_URL = "https://github.com/YGHFv/ReaMicro-Extend"

        const val TAB_RECORDS = 0
        const val TAB_TASKS = 1
        const val TAB_CONFIG = 2
        const val TAB_ABOUT = 3
        val TAB_TITLE_RES = listOf(R.string.tab_records, R.string.tab_tasks, R.string.tab_settings, R.string.tab_about)
        val TAB_ICONS = listOf(MiuixIcons.Recent, MiuixIcons.Tasks, MiuixIcons.Settings, MiuixIcons.Info)
        val TAB_GLASS_ICONS = listOf(
            MiuixIcons.Glass.ClockFill, MiuixIcons.Glass.Tasks, MiuixIcons.Glass.SettingsOutline, MiuixIcons.Glass.Info,
        )

        val LIGHT_WINDOW_BG = lightColorScheme().surface.toArgb()
        val DARK_WINDOW_BG = darkColorScheme().surface.toArgb()

        const val FIELD_TIME = "timeOfDay"
        const val FIELD_DURATION = "durationMinutes"
        const val FIELD_BOOKS = "books"
        const val FIELD_DRAW_LIMIT = "dailyDrawLimit"
        const val FIELD_DRAW_CUSTOM_TIME = "drawAtCustomTime"
        const val FIELD_PAWN = "forbiddenPawnPropIds"
        const val FIELD_CITY = "merchantCityCode"
        const val FIELD_PRINCIPAL = "merchantPrincipal"
        const val FIELD_TRANSPORT = "merchantTransportId"

        const val MERCHANT_NO_TRANSPORT = "0"

        const val MERCHANT_MIN_PRINCIPAL = 10L
        const val FIELD_BLESSING = "blessingType"
    }
}
