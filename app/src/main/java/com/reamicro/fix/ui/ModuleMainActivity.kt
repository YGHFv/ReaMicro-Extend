package com.reamicro.fix.ui

import com.reamicro.fix.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
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
import android.content.ComponentName
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults.flingBehavior
import androidx.compose.foundation.pager.PagerDefaults.pageNestedScrollConnection
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.cloud.local.CloudTaskLocalRunner
import com.reamicro.fix.cloud.local.LocalTask
import com.reamicro.fix.cloud.local.AutoReadTimeLock
import com.reamicro.fix.cloud.local.LocalTaskBook
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
import com.reamicro.fix.notification.NotificationRecord
import com.reamicro.fix.notification.NotificationRecordStore
import com.reamicro.fix.notification.cloudTaskQualityColor
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.blendColors
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Alarm
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Update
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

/** 危险操作（清空、失败态标题）的提示色：新旧两套 UI 保持同一语义。 */
private val DangerRed = Color(0xFFD03A2B)

/**
 * 模块主界面（miuix 版）：底栏四个页签（记录 / 任务 / 设置 / 关于）。
 *
 * 为什么换成 miuix：旧版是纯 View 手搓的卡片，观感与系统脱节；miuix 是 Compose
 * Multiplatform 的 HyperOS 风格组件库，与 KernelSU 管理器同一套设计语言——
 * 白底圆角卡片、标题行右侧开关、卡片底部横向操作按钮。
 *
 * 结构约定：
 * - 页面数据在 [refresh] 里同步重算进 mutableStateOf 快照，组合层只读快照，
 *   不在组合期碰 SharedPreferences / 网络——和旧版 refresh() 的时机完全一致。
 * - 弹窗分两类：纯文本弹窗（详情/说明/日志）走 [textDialog]，有结构的
 *   任务编辑器走 [editorDialog] + [multiSelectDialog]（多选从编辑器里二次弹出）。
 * - ModuleUiKit 仍然保留：宿主（阅微）进程里的禁当期物多选弹窗还在用它，
 *   那条注入路径与这里的 miuix 界面互不影响。
 */
class ModuleMainActivity : ComponentActivity() {
    // Read live coordinates on DOWN, so scrolling/settings-page transitions cannot leave a stale hit area.
    private var launcherIconCoordinates: LayoutCoordinates? = null


    // ---- 页面状态 ----

    private val tab = mutableIntStateOf(TAB_RECORDS)

    /** 每次自增触发各快照重算；组合层只读这些快照。 */
    private val recordsState = mutableStateOf<List<Pair<String, LocalTaskRecord>>>(emptyList())
    private val failedCount = mutableIntStateOf(0)
    private val taskRows = mutableStateOf<List<TaskRow>>(emptyList())
    private val accountCount = mutableIntStateOf(0)
    private val overviewText = mutableStateOf("")
    private val hideRecentTask = mutableStateOf(false)

    /** 隐藏桌面图标：禁用全部图标别名。主界面本身不受影响，隐藏后仍能进来把它关掉。 */
    private val hideLauncherIcon = mutableStateOf(false)
    /** 当前选中的桌面图标配色（未隐藏时才实际显示）。 */
    private val launcherIconStyle = mutableStateOf(ModuleIconStyle.DEFAULT)
    /** 上一次桌面图标操作暴露的异常状态（多入口/读失败/恢复失败等），供界面提示。null 表示正常。 */
    private val launcherIconIssue = mutableStateOf<LauncherIconIssue?>(null)

    /**
     * 桌面图标切换/隐藏的状态机。判定（回显、恢复、失败回滚、原子提交）都在 [LauncherIconController]，
     * 这里只提供 PackageManager 适配与偏好读写；懒加载保证在 Context 就绪后再取 packageManager。
     */
    private val launcherIcons by lazy {
        LauncherIconController(
            ModuleIconStyle.aliases(),
            ModuleIconStyle.androidComponents(this),
            launcherIconPreferences(),
        )
    }

    /** 应用语言：0=跟随系统 1=简体中文 2=English。以模块自己的偏好为准（见 [ModuleLanguage]）。 */
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

    // ---- 界面设置（设置页「界面」卡片）----

    /** 顶栏与底栏的毛玻璃背景（miuix-blur）：栏底色转透明，改为对穿过的内容做模糊。 */
    private val aboutEffectResumed = mutableStateOf(false)
    private val blurBars = mutableStateOf(false)

    /** Apple 风格悬浮底栏：底栏收成一枚悬浮胶囊，内容从它底下穿过。 */
    private val floatingNavBar = mutableStateOf(false)

    /** 悬浮底栏的液态玻璃效果（仅悬浮底栏开启时生效/显示）：胶囊对底下内容实时模糊 + 玻璃描边。 */
    private val liquidGlass = mutableStateOf(true)

    /**
     * 深浅色：0 跟随系统、1 日间、2 夜间。
     * 与 KSU 的 themeMode 同一套下标，不带 Monet 的 +3 偏移。
     */
    private val themeMode = mutableIntStateOf(THEME_FOLLOW_SYSTEM)

    /** 页面左右滑被列表惯性抢走时怎么处理：0 默认、1 跨轴拦截、2 iOS 风格。 */
    private val pagerGestureMode = mutableIntStateOf(PagerInterceptionMode.CrossAxisInterceptor.ordinal)

    /** 从屏幕边缘横滑关闭顶层弹窗。根页面没有可返回的上一页，所以不退出应用。 */
    private val swipeBack = mutableStateOf(true)

    /** Android 14+ 的系统预测性返回。改的是隐藏接口，开关后重建界面才生效。 */
    private val predictiveBack = mutableStateOf(false)

    // ---- 弹窗状态 ----

    private val textDialog = mutableStateOf<TextDialogUi?>(null)
    private val editorDialog = mutableStateOf<TaskEditor?>(null)
    // Notification navigation is an event, not just a tab snapshot (Pager owns its own state).
    private val recordsNavigationRequest = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        ModuleSplashScreen.install(this, savedInstanceState != null)
        setTheme(com.reamicro.fix.R.style.ModuleTheme)
        super.onCreate(savedInstanceState)
        ModuleLogBuffer.attach(this)
        tab.intValue = savedInstanceState?.getInt(STATE_TAB, TAB_RECORDS) ?: TAB_RECORDS
        themeMode.intValue = uiPrefInt(KEY_THEME_MODE).coerceIn(THEME_FOLLOW_SYSTEM, THEME_DARK)
        // 窗口底色跟着深浅色走：首帧之前系统栏区域显示的就是它（透明会让部分 ROM 露黑边）。
        applyPredictiveBack(uiPrefBoolean(KEY_PREDICTIVE_BACK))
        // 进界面时对齐一次桌面图标：恢复被进程中断的切换，并把桌面图标被重置回默认（例如某些
        // 环境下组件状态被清成 manifest 默认）的情况纠回用户偏好。restore 内部做失败回滚，
        // 不会因为一次 PackageManager 异常把界面点崩；结果同步回界面状态与提示。
        applyLauncherIconResult(launcherIcons.restore())
        updateLanguageContext()
        window?.setBackgroundDrawable(ColorDrawable(if (resolveDark(themeMode.intValue)) DARK_WINDOW_BG else LIGHT_WINDOW_BG))
        ModuleAndroidLog.legacy(LOG_TAG, "module main ui opened")
        // onResume refreshes the local snapshot before first draw; do not read it twice here.
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

    /**
     * 系统栏沉浸（含小白条）：逐条对照 KernelSU 的写法来，不再自己猜。
     *
     * 旧版在 `onCreate` 顶部调一次 `enableEdgeToEdge()` 是错的：那时 DecorView 还没建好，
     * 这次调用会被随后的窗口初始化吃掉。于是真机上呈现的是「半沉浸」——
     * 实测 Compose 内容区 = y∈[0, 2663]，状态栏贴到了顶，**导航栏那 49px 却仍然占着布局**。
     * 不弹窗时用导航栏底色凑合看着还行，一旦弹窗压暗，那条没被 dim 覆盖的导航栏就露白，
     * 就是「配置点开压暗时小白条又不沉浸了」。
     *
     * 三条缺一不可（KSU MainActivity 的 setContent 内 DisposableEffect 即此写法）：
     * ① 必须放在 `setContent` 里、随深浅色重跑，才能赶在窗口真正建立之后生效；
     * ② `navigationBarStyle` 必须显式传，否则导航栏不参与 edge-to-edge；
     * ③ `isNavigationBarContrastEnforced = false` —— 系统默认会给导航栏叠一层对比度
     *    scrim，这层 scrim 才是「底栏下面一条更亮的白带」的真身，也是旧版
     *    「setNavigationBarColor 设成透明却没变化」的原因（透明只是让 scrim 露出来）。
     */
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

    /** Explicit notification entry; normal launcher/LSPosed/action entry keeps the current tab. */
    private fun consumeNavigationIntent(incoming: Intent?) {
        if (incoming?.action != CloudTaskNotifications.ACTION_OPEN_RECORDS) return
        textDialog.value = null
        editorDialog.value = null
        tab.intValue = TAB_RECORDS
        recordsNavigationRequest.intValue++
        // Do not navigate again on an unrelated configuration change/recreate.
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
        // Display the last in-process snapshot only. Resume must never run su or queue a sync,
        // including resumes caused by the manager permission window. A cold start stays unchecked.
        rootUi.value = RootTaskBridge.lastInspection
        // Returning from a popup, notification or manager must never undo this preference.
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

    // These stores are written in the module process by the mirror receiver, Android runner
    // and Root snapshot importer. Coalesce bursts instead of refreshing every changed key.
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

    /** 系统栏图标与页面底色相反（沉浸后状态栏、小白条都直接压在页面底色上）。 */
    @Composable
    private fun SystemBarAppearance(dark: Boolean) {
        LaunchedEffect(dark) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    // 状态栏与导航栏一起处理：只改状态栏会让小白条上的图标变白→看不见。
                    val lightMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    window.insetsController?.setSystemBarsAppearance(if (dark) 0 else lightMask, lightMask)
                }
            }
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // ---- 数据快照 ----

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
        // 回显只读：以组件实际状态为准，UNKNOWN/多入口/未完成的切换都如实反映，不被偏好值盖掉。
        applyLauncherIconResult(launcherIcons.inspect(), notify = false)
        blurBars.value = uiPrefBoolean(KEY_BLUR_BARS)
        floatingNavBar.value = uiPrefBoolean(KEY_FLOATING_NAV_BAR)
        // 液态玻璃默认开（KSU 也是这个默认值）：键不存在时取 true。
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

    /** Planned task data only; no system alarm or alternate wake schedule. */
    private fun buildOverviewText(store: LocalTaskStore): String {
        val configured = taskRows.value.count { it.task.enabled }
        val next = futureNextRunAt(store)?.let(::formatTime) ?: uiText(R.string.common_none)
        return uiText(R.string.task_overview_body, accountCount.intValue, configured, next) +
            if (!rootEnhancementEnabled.value) "\n" + uiText(R.string.root_manual_only) else ""
    }
    /**
     * 任务卡片的正文：运签（有才写）+ 下次执行 + 最近一次消息。
     *
     * 刻意**不写「已启用 / 已关闭」**：开关就在标题行右侧，再写一遍就是同一张卡片里
     * 出现两处状态。关掉的任务由 [nextRunLine] 返回的「未启用」表达。
     */
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

    // ---- 卡片骨架（照 KernelSU 的模块卡片 `ModuleItem` 复刻） ----
    //
    // KSU 的模块卡片是一套固定骨架，抽出来给四个页面共用：
    //   Card(外边距 12dp、内边距 16dp)
    //   ├ 标题行  标题 17sp / 字重 550；副信息 12sp / 字重 550 / 次要色，紧贴标题下方
    //   ├ 描述    14sp / 次要色
    //   ├ 分隔线  0.5dp、outline 50% 透明，上下各留 8dp
    //   └ 操作行  35dp 高的胶囊按钮（secondaryContainer 底 + 图标 + 15sp 中等字重）
    //
    // 这些数值不是凑的，是 KSU `ModuleItem` 里的原值：17sp/550、12sp/550、14sp、
    // HorizontalDivider(0.5dp, outline.copy(alpha=0.5f))、heightIn(min = 35.dp)。

    @Composable
    private fun ModuleApp() {
        val tabTitles = TAB_TITLE_RES.map { stringResource(it) }
        val pagerState = rememberPagerState(initialPage = tab.intValue, pageCount = { TAB_TITLE_RES.size })
        val scope = rememberCoroutineScope()
        val pagerMode = PagerInterceptionMode.entries.getOrElse(pagerGestureMode.intValue) {
            PagerInterceptionMode.Native
        }
        val interceptPager = pagerMode == PagerInterceptionMode.CrossAxisInterceptor
        // 顶层弹窗打开时左右滑只负责关闭弹窗，不再同时翻页。
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
        // 底栏高亮 / 顶栏标题用这个即时状态：点击立刻切高亮，不等 Pager 动画推进（原来直接读
        // pagerState.currentPage，要等动画过半才更新，点击反馈明显滞后，观感就是"卡一下"）。
        // 手势滑动时由下方的同步逻辑跟随真实页。
        var selectedPage by remember { mutableIntStateOf(tab.intValue.coerceIn(0, TAB_TITLE_RES.lastIndex)) }
        var isNavigating by remember { mutableStateOf(false) }
        var navJob by remember { mutableStateOf<Job?>(null) }
        // 首帧只组当前页，组合完成后再预载其余页：避免首次进入/切换时四页内容一起组合造成掉帧。
        var contentReady by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { contentReady = true }
        // 手势滑动改变真实页时让高亮跟随；导航动画进行中不抢，避免与 animateToTab 打架。
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
        // 点底栏切页：先取消上一次未完成的动画再起新的，连续点击不会堆叠多个 springAnimateToPage
        // 相互打架（原来每次点都新起协程、无取消，是切换卡顿的主因）。高亮已在上面即时更新。
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
                        // 只有自己仍是最新的导航协程时才复位：连续快点时旧协程被 cancel，其 finally
                        // 可能在新协程已置 isNavigating=true 之后才跑，若无这道 myJob 守卫就会把状态
                        // 错误复位，导致手势同步逻辑在新动画中途抢改 selectedPage、高亮跳变
                        // （KernelSU MainPagerState 同款守卫）。
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
                // About is a primary tab. Its KSU effect owns the entire top area; no title/back bar.
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
                            // 拦住落在悬浮底栏本体上的点击/手势，避免穿透到底层 Pager 触发翻页或滚动
                            // （KernelSU 的 BottomBarMiuix 同款 detectTapGestures 空实现）。
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
                                    // 每个 tab 最小 76dp（KernelSU 同款）：配合底栏的 IntrinsicSize.Min，
                                    // 让悬浮胶囊按内容收成合理宽度的窄胶囊居中，而非拉满整行。
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
                // 首帧只组当前页（0），组合稳定后再预载其余页（3=全部）：与 KernelSU 同策略，
                // 避免冷启动/首切时四页一起组合掉帧。
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
                            // About has no TopAppBar to initialise the collapse range.
                            // Do not let its unused scroll behaviour consume vertical drags.
                            .then(if (page == TAB_ABOUT) Modifier else Modifier.nestedScroll(pageScroll.nestedScrollConnection))
                            .verticalScroll(rememberScrollState(), overscrollEffect = null)
                            .padding(top = if (page == TAB_ABOUT) {
                                WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                            } else padding.calculateTopPadding())
                            .padding(top = if (page == TAB_ABOUT) 0.dp else 4.dp, bottom = 4.dp),
                    ) {
                        // 懒组合：首帧只组当前页，其余页等 contentReady 再组，避免一次性组四页掉帧。
                        // 与 beyondViewportPageCount 的分级预载配套（KernelSU 同策略）。
                        // 记录页条目上百，已移出 pager 单独走 Lazy 容器（见下方 TAB_RECORDS 分支），
                        // 这里 else 留空即可。
                        if (contentReady || page == currentPage) {
                            when (page) {
                                TAB_TASKS -> TasksPage()
                                TAB_CONFIG -> ConfigPage()
                                TAB_ABOUT -> AboutPage()
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
                    // 记录页条目上百，不能进 verticalScroll 的整体组合 Column，单独走 Lazy 容器。
                    RecordsPage(
                        topPadding = padding.calculateTopPadding(),
                        bottomPadding = padding.calculateBottomPadding(),
                        scrollBehavior = pageScroll,
                    )
                } else if (page == TAB_ABOUT) {
                    // The immediate selected tab is not enough: do not animate while the pager moves.
                    // derivedStateOf observes transition boundaries, not every fractional page offset.
                    val aboutPlaying by remember(pagerState, currentPage, isNavigating, blurSupported) {
                        derivedStateOf {
                            blurSupported && aboutEffectResumed.value &&
                                currentPage == TAB_ABOUT && !isNavigating &&
                                !pagerState.isScrollInProgress && pagerState.settledPage == TAB_ABOUT
                        }
                    }
                    // Avoid first-use shader compilation in the middle of the first incoming swipe.
                    // Once activated, keep the same painter and last frame across subsequent switches.
                    var aboutEffectReady by remember { mutableStateOf(false) }
                    LaunchedEffect(aboutPlaying) {
                        if (aboutPlaying) aboutEffectReady = true
                    }
                    val aboutBackdrop = rememberLayerBackdrop()
                    com.reamicro.fix.ui.effect.BgEffectBackground(
                        dynamicBackground = aboutPlaying,
                        effectBackground = aboutEffectReady,
                        modifier = Modifier.fillMaxSize().graphicsLayer(),
                        bgModifier = Modifier.layerBackdrop(aboutBackdrop),
                        isFullSize = true,
                    ) {
                        CompositionLocalProvider(
                            com.reamicro.fix.ui.effect.LocalAboutBackdrop provides
                                if (blurSupported && aboutEffectReady) aboutBackdrop else null,
                        ) { scrollContent() }
                    }
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

    // ---- 记录页 ----

    /**
     * 记录页：条目上百，必须走 LazyColumn 只组合可见卡片。原来的 verticalScroll Column 会把
     * 全部记录一次性组合进布局树，进页面、下拉刷新、每次状态变更都全量重建整页卡片，是
     * 整体卡顿的大头。LazyColumn 不能嵌在 verticalScroll 里，所以这里自带头部内边距与
     * 大标题折叠的 nestedScroll 接线，不再包进 pager 的通用 scrollContent。
     */
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
                // key = 账号 + 时刻 + 任务类型：记录按时刻倒序插入，稳定 key 让新记录只组合新增项。
                key = { _, (accountId, record) -> "$accountId-${record.at}-${record.taskType}" },
                contentType = { _, _ -> "record" },
            ) { index, (accountId, record) ->
                // 按天分组：同一天只出一行日标题（今天 / 昨天 / MM-dd），行内就只留 HH:mm——
                // 比每条都写满「09-24 08:04:44」清爽，也更容易按天扫读。
                val day = dayLabel(record.at)
                if (index == 0 || dayLabel(list[index - 1].second.at) != day) {
                    GroupTitle(day)
                }
                RecordCard(accountId, record)
            }
        }
    }

    /**
     * 记录条目：套 KSU 卡片骨架——标题（失败转红）左侧、时刻右对齐同一行 + 摘要描述。
     *
     * 时刻**走标题行而不是副行**：一天里同一个任务会有好几条，时刻是分组信息（左边已经有
     * 「今天 / 昨天」标题了），放右端一眼纵向对齐就能扫；挤在标题下方会把标题行撑成两行。
     */
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

    // ---- 任务页 ----

    @Composable
    private fun TasksPage() {
        // 概览只做只读播报：那两枚按钮已按需求移除——单个任务的重跑入口在各自卡片的
        // 「执行」上，「重算下次时刻」由本页下拉刷新触发。
        //
        // 播报正文统一走 description（14sp / 常规字重）：与记录页、设置页的卡片正文
        // 同一档（此前走 subtitle = 12sp/550，实测帧高 49px vs 57px，三页里独此一档）。
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

    // ---- 设置页 ----

    @Composable
    private fun ConfigPage() {
        // Rendering or revisiting settings never requests Root, even when enhancement is enabled.
        // Explicit actions refresh diagnostics; task/configuration events own background sync.
        // 语言板块：应用语言切换（跟随系统 / 简体中文 / English）。
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
        // 外观相关的开关统一收在一张卡片里（KSU 把这一组叫「主题设置」）：
        // 第一行是主题下拉，与「页面切换手势」同款：点右侧展开选择。
        // 「液态玻璃」只对悬浮底栏有意义，所以只在悬浮底栏开启时出现。
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

        // 个性化：切换应用图标（含横向图标选择器）+ 两个隐藏开关。
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

    /**
     * 任务卡片：套 KSU 的卡片骨架。
     *
     * 卡片里**不再单独写一行「已启用」**——开关本身就是状态（关掉时标题转灰），
     * 副信息里也不再重复。操作行换成 KSU 的胶囊按钮：左「执行」（播放图标，
     * 原来叫「立即执行」）、右「配置」（齿轮图标，名字按要求保留）。
     *
     * 副信息走 description（14sp / 常规字重）：三页卡片正文统一同一档，
     * 别再换回 subtitle（12sp/550）。
     */
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
    /** A task exchange, not a second permission inspection. Actual Root commands still validate UID 0. */
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
    /**
     * 图标配色选择器：一行横向滚动的圆形预览，选中项高亮描边并显示两字名称。
     * 点选立即调用 [selectLauncherIcon]（隐藏状态下仅记录，恢复时生效）。
     */
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
                    // 四角圆润 18dp（用 Compose 标准 RoundedCornerShape，普通圆弧）。minSdk 26，
                    // 不用 miuix squircle——它的着色器要 API 33+，低版本会回退，不如直接用圆角矩形统一。
                    // 描边：未选中 1.2dp / 选中 1.5dp，只有选中态用 primary 蓝、未选中用淡分割线色。
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
                    // 预览按 adaptive-icon 的两层自行叠合：底层背景色 + 上层前景位图。
                    // 不直接光栅化整张 adaptive-icon(mipmap XML)——那样在部分 ROM（HyperOS 等）
                    // 画不出前景、预览空白。前景是 PNG，painterResource 可直接加载。
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(iconShape)
                            .background(colorResource(style.background))
                            .border(borderWidth, borderColor, iconShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 前景 PNG 按 adaptive-icon 规范带大片透明安全边距（108dp 画布里图案只占
                        // 中间一小块），直接铺满会显得"空白居多"。真实启动器显示时会把前景裁剪放大
                        // （108dp → 可视约 72dp，≈1.5x）。这里给预览前景做同等缩放，观感与桌面一致。
                        // 仅影响预览渲染，不改任何图标资源、也不影响真实应用图标。
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
        val store = LocalTaskStore { applicationContext }
        store.setEnabled(accountId, task.taskType, enabled, store.token(accountId))
        // 配置修改由存储层同步给已开启的 Root 调度器。
        toast(uiText(if (enabled) R.string.toast_task_enabled else R.string.toast_task_disabled, taskTitle(task.taskType)))
        refresh()
    }

    private fun toggleHideRecentTask() {
        val enabled = !hideRecentTask.value
        writeUiPref(KEY_HIDE_RECENT_TASK, enabled)
        // 立即按目标值应用，而不是恒设 false 等离开界面才隐藏：原来这里无论开还是关都传 false，
        // 于是"开启隐藏最近任务"当下毫无变化，要等下一次 onUserLeaveHint/onStop 才真正排除，
        // 表现就是"开关打开了但最近任务里还在"。开启时立刻把当前任务从最近任务移除；
        // 关闭时立刻恢复可见。
        setTaskExcludedFromRecents(enabled)
        hideRecentTask.value = enabled
        toast(if (enabled) uiText(R.string.toast_recents_hidden) else uiText(R.string.toast_recents_shown))
    }

    /**
     * 「隐藏桌面图标」开关：禁用的只是 launcher 别名，主界面自身不动。
     *
     * 只禁用带 LAUNCHER 的别名；始终启用的 ModuleMainActivity 单独声明 MODULE_SETTINGS。
     * LSPosed 按该 category 打开设置，Root 管理器的 action.sh 显式打开 Activity，
     * 二者均不依赖 getLaunchIntentForPackage，不需要 adb，也不会禁用整个应用。
     *
     * 结果按实际生效情况提示：成功隐藏才写恢复入口，成功恢复才说已恢复；失败则给失败文案，
     * 并让开关回显回退到 [applyLauncherIconResult] 读到的真实状态，不再无条件报成功。
     */
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

    /**
     * 切换桌面图标配色。
     *
     * 隐藏状态下也能选：控制器只更新偏好、不点亮别名，等关掉「隐藏桌面图标」时再套用。
     * 未隐藏时立即生效并核对组件实际状态，失败会自动回滚到上一套配色，界面回显随之纠正。
     */
    private fun selectLauncherIcon(style: ModuleIconStyle) {
        val hiddenBefore = hideLauncherIcon.value
        val result = launcherIcons.selectStyle(style.key)
        applyLauncherIconResult(result, notify = false)
        // 隐藏态下切配色不点亮别名（避免把隐藏的图标重新点出来），因此不打扰用户；
        // 仅在“可见”场景按实际结果提示成功或失败。
        if (!hiddenBefore && !result.view.choice.hidden) {
            toast(
                if (result.success) uiText(R.string.toast_icon_switched, uiText(style.labelRes))
                else uiText(R.string.toast_icon_switch_failed),
            )
        }
    }

    /**
     * 把控制器结果同步到界面：当前配色、隐藏开关、异常状态一次对齐。
     *
     * 只信控制器读到的**实际状态**（[LauncherIconView.choice] 由组件枚举推导），
     * 不再各处单独读偏好、各改各的。[notify] 为 true 时（目前仅进界面恢复用）在检测到
     * 异常状态时补一条提示，让“看起来没变”的失败/多入口也有反馈。
     */
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

    /**
     * 桌面图标控制器的偏好读写：落在同一份 UI prefs 里。
     *
     * confirmed = 上一套稳定选择；pending = 已落盘但未完成的切换（进程中断可续做）；
     * configured = 是否已被用户设置过（区分“全新安装未设置”与“确实选择了默认款”）。
     * 写入用 commit 并返回结果，让控制器据此判断“保存是否真的成功”。
     */
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

    /**
     * 只更新语言配置和可观察的资源上下文，不销毁 Activity / Compose 树。
     * Android 13+ 同步系统应用语言；旧系统持久化选择并使用局部配置，不改全局 Locale。
     */
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

    /**
     * 「模糊」开关：顶栏与底栏改走毛玻璃（内容从栏底下穿过并实时模糊）。
     *
     * 关掉时栏恢复不透明底色，渲染与没做这件事之前完全一致，所以可以随开随关。
     */
    private fun toggleBlurBars(enabled: Boolean) {
        writeUiPref(KEY_BLUR_BARS, enabled)
        blurBars.value = enabled
    }

    /** 「悬浮底栏」开关：底栏在标准整条与悬浮胶囊之间切换（胶囊高出台面，内容从底下穿过）。 */
    private fun toggleFloatingNavBar(enabled: Boolean) {
        writeUiPref(KEY_FLOATING_NAV_BAR, enabled)
        floatingNavBar.value = enabled
    }

    /** 「液态玻璃」开关：只在悬浮底栏开启时有意义——胶囊在「玻璃透视」与「不透明」之间切换。 */
    private fun toggleLiquidGlass(enabled: Boolean) {
        writeUiPref(KEY_LIQUID_GLASS, enabled)
        liquidGlass.value = enabled
    }

    /** 主题分段：跟随系统 / 日间 / 夜间。窗口底色一起改，避免切到夜间时状态栏底下还是白的。 */
    private fun setThemeMode(mode: Int) {
        val value = mode.coerceIn(THEME_FOLLOW_SYSTEM, THEME_DARK)
        writeUiPrefInt(KEY_THEME_MODE, value)
        themeMode.intValue = value
        window?.setBackgroundDrawable(ColorDrawable(if (resolveDark(value)) DARK_WINDOW_BG else LIGHT_WINDOW_BG))
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

    /**
     * 预测性返回走系统隐藏接口，和 KSU 一样：先放行隐藏 API，再改当前 ApplicationInfo。
     * 这个开关要重建界面才作用到已经注册的返回回调。
     */
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

    /** 0 跟随系统，1 强制日间，2 强制夜间。 */
    private fun resolveDark(mode: Int): Boolean = when (mode) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> isNightMode()
    }

    /**
     * 横移返回：从起始边缘滑过屏幕三分之一就关闭顶层弹窗。
     * 方向按布局方向取，RTL 从右缘起滑；根页面没有弹窗时不消费手势，左右滑仍然翻页。
     */
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

    /** 横移返回和系统返回共用：先关多选，再关编辑器，最后关文本弹窗。 */
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
            // appTasks is already scoped to our app. Include older tasks opened through another
            // launcher alias/notification as well, not just the first matching current task.
            val tasks = getSystemService(ActivityManager::class.java)?.appTasks.orEmpty()
            tasks.forEach { appTask ->
                runCatching { appTask.setExcludeFromRecents(excluded) }
                    .onFailure { ModuleAndroidLog.error(LOG_TAG, "更新后台卡片可见性失败", it) }
            }
        }.onFailure { ModuleAndroidLog.error(LOG_TAG, "读取后台任务失败", it) }
    }
    // ---- 任务编辑弹窗 ----

    /**
     * 任务配置编辑：字段随任务类型变化（与阅微设置页同一套语义）。
     *
     * 所有字段值都落在 [TaskEditor.values]（按稳定字段键取值），保存时直接交给
     * [applyTaskEdits]——与旧版 editDialog 的 register 通道等价，字段键与翻译后的标签无关。
     */
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
            // 预填 = 已存清单 ∪ 名字键默认项（青圭）。老任务存的是旧默认（只有 11-18），
            // 不并入的话新默认项在弹窗里永远不勾；名字键默认项执行侧还有红色硬规则兜底，
            // 这里并进去是让它默认可见、默认锁定。
            put(
                FIELD_PAWN,
                (task.forbiddenPawnPropIds + CloudTaskLocalRunner.PROHIBITED_PAWN_PROP_HINTS.keys.filter { it.startsWith("name:") })
                    .sorted()
                    .joinToString(","),
            )
            editor.multiOptions[FIELD_PAWN] = pawnOptionsFromState(accountId)
            // 打开编辑器就顺手拉一次全量期物（当日期物 + 背包），让入口行的计数先用上
            // 最新清单；多选弹窗打开时还会再拉一次（见 MultiSelectOverlay）。
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
            // 城池/车马的下拉选项来自阅微的行商接口，打开编辑器时现拉。
            // 行商当前行程（activeTrip）只做**首次**默认值：任务里已经存过配置就
            // 原样保留，否则每开一次编辑器都会被当前行程覆盖掉已保存的配置。
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
                    if (fetched.activeCityCode.isNotBlank() && savedCity.isBlank()) {
                        put(FIELD_CITY, fetched.activeCityCode)
                    }
                    if (fetched.activeTransportId > 0L && savedTransport <= 0L) {
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

    /** 从执行时攒下的图鉴里读禁当期物清单（不含网络请求，刷新才走网络）。 */
    private fun pawnOptionsFromState(accountId: String): List<ModulePawnOption> {
        val state = LocalTaskStore { applicationContext }.runtimeState(accountId, "pawn")
        return CloudTaskLocalRunner.pawnPropChoices(state).map {
            ModulePawnOption(it.propId, it.name, cloudTaskQualityColor(it.quality), it.hint)
        }
    }

    /**
     * 现拉一次期物全量（当日期物 + 背包）：期物的名字与品质只在服务端。
     *
     * 现在编辑器每次打开都会自动走这一趟，所以**并进**已存图鉴而不是整表替换——
     * 替换会把上周见过、今天已不在背包里的期物从图鉴里抹掉，已锁它的配置就没了名字。
     */
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
        // fetched.catalog 里只有这次拉到的；图鉴里已有的保留，这次学到的覆盖同名条目。
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

    /** 拉城池/车马全量选项与当前行商状态；互斥筛选（车马类型↔城池要求）在 UI 侧现做。 */
    private fun fetchMerchantChoices(accountId: String): CloudTaskLocalRunner.MerchantOptionsFetch {
        val token = LocalTaskStore { applicationContext }.token(accountId)
        return CloudTaskLocalRunner.fetchMerchantOptions(token)
    }

    /** 把对话框里填的值并回任务；时间或数字非法时返回 null。 */
    private fun applyTaskEdits(
        task: LocalTask,
        spec: CloudAutomationTaskSpec?,
        fields: Map<String, () -> String>,
    ): LocalTask? {
        val text = { label: String -> fields[label]?.invoke()?.trim().orEmpty() }
        val timeOfDay = if (spec?.rewardTriggered != true && spec?.merchant != true) {
            val parts = text(FIELD_TIME).split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull()
            val minute = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || hour == null || minute == null || hour !in 0..23 || minute !in 0..59) return null
            String.format(Locale.ROOT, "%02d:%02d", hour, minute)
        } else task.timeOfDay
        val duration = if (spec?.autoRead == true) {
            text(FIELD_DURATION).toIntOrNull()?.takeIf { it in 1..720 } ?: return null
        } else task.durationMinutes
        if (spec?.autoRead == true && AutoReadTimeLock.minutesOfDay(timeOfDay) < duration) return null
        val drawLimit = if (spec?.rewardTriggered == true) {
            text(FIELD_DRAW_LIMIT).toIntOrNull()?.takeIf { it in 0..20 } ?: return null
        } else task.dailyDrawLimit
        val books = if (spec?.autoRead == true) {
            val lines = text(FIELD_BOOKS).lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            val parsed = ArrayList<LocalTaskBook>(lines.size)
            for (line in lines) {
                val id = line.substringBefore('|').trim().toLongOrNull() ?: return null
                if (id <= 0L) return null
                parsed += LocalTaskBook(id, line.substringAfter('|', "").trim())
            }
            parsed
        } else task.books
        val blessing = if (spec != null && spec.blessingOptions.isNotEmpty()) {
            spec.resolveBlessingChoice(text(FIELD_BLESSING))
        } else task.blessingType
        val forbiddenPawnPropIds = if (spec?.taskType == "pawn") {
            CloudTaskLocalRunner.parseForbiddenPawnPropIds(text(FIELD_PAWN))
        } else task.forbiddenPawnPropIds
        return task.copy(
            timeOfDay = timeOfDay,
            durationMinutes = duration,
            dailyDrawLimit = drawLimit,
            books = books,
            forbiddenPawnPropIds = forbiddenPawnPropIds,
            blessingType = blessing,
            merchantCityCode = if (spec?.merchant == true) text(FIELD_CITY) else task.merchantCityCode,
            merchantPrincipal = if (spec?.merchant == true) text(FIELD_PRINCIPAL).toLongOrNull()?.coerceAtLeast(0L) ?: 0L else task.merchantPrincipal,
            merchantTransportId = if (spec?.merchant == true) text(FIELD_TRANSPORT).toLongOrNull()?.coerceAtLeast(0L) ?: 0L else task.merchantTransportId,
        )
    }

    private fun saveTaskEdits(editor: TaskEditor) {
        // 本金不能超过当前车马的负重（负车上路，宿主会拒）：上限跟车马走，没选车马不设限。
        if (editor.spec?.merchant == true) {
            val principal = editor.values[FIELD_PRINCIPAL]?.trim()?.toLongOrNull() ?: 0L
            val capacity = editor.merchantTransports
                .firstOrNull { it.id == editor.values[FIELD_TRANSPORT].orEmpty() }
                ?.carryingCapacity ?: 0L
            if (principal > capacity && capacity > 0L) {
                toast(uiText(R.string.error_principal_capacity, capacity))
                return
            }
        }
        // Read the current buffer synchronously: saving must not race a text-change coroutine.
        val fields = editor.values.mapValues { (key, value) ->
            { editor.textFields[key]?.text?.toString() ?: value }
        }
        val updated = applyTaskEdits(editor.task, editor.spec, fields)
        if (updated == null) {
            toast(uiText(R.string.error_invalid_fields))
            return
        }
        val store = LocalTaskStore { applicationContext }
        store.saveTask(editor.accountId, updated, store.token(editor.accountId))
        toast(uiText(R.string.toast_config_saved))
        editorDialog.value = null
        refresh()
    }

    // ---- 文本弹窗 ----

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

    // ---- 关于页 ----
    /** KSU miuix 风格的品牌区与链接卡片；不混入运行状态或不存在的社群。 */
    @Composable
    private fun AboutPage() {
        val iconStyle = launcherIconStyle.value
        // miuix 0.9.4 example/AboutPage: SmallTopAppBar(52) + extraTop(40) + logo offset(52).
        // Reserve the bar's space even without a back arrow; the parent adds status-bar insets once.
        val brandTopPadding = 52.dp + 40.dp + 52.dp
        // The example's logoSpacer leaves 126dp after its branding; ours also includes build time.
        val brandBottomPadding = 126.dp
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                .padding(top = brandTopPadding, bottom = brandBottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(88.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colorResource(iconStyle.background)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(iconStyle.foreground),
                    contentDescription = null,
                    // Match the launcher preview: scale the full 88dp foreground by 108 / 72.
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = 1.5f
                        scaleY = 1.5f
                    },
                )
            }
            Text(
                text = uiText(R.string.module_name),
                modifier = Modifier.padding(top = 16.dp, bottom = 5.dp),
                fontSize = 35.sp,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Text(
                text = versionLine(),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = uiText(R.string.about_build_time, buildTimeText()),
                modifier = Modifier.padding(top = 4.dp),
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
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
        }
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

    // ---- 弹窗渲染 ----

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
        // Both BasicComponent.summary and dropdown values consume body2.
        val taskTextStyles = MiuixTheme.textStyles.copy(
            body2 = MiuixTheme.textStyles.body2.copy(fontSize = 15.sp, lineHeight = 20.sp),
        )
        MiuixTheme(colors = MiuixTheme.colorScheme, textStyles = taskTextStyles) {
        AppWindowDialog(
            show = true, title = taskTitle(editor.task.taskType),
            onClose = { editorDialog.value = null },
            primaryLabel = uiText(R.string.action_save),
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
                // 运签用选择器而不是输入框：用户面对的应该只有「求安签/求财签」，
                // 不该让他知道也不该让他手打 SAFETY 这种 wire 值。
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
    /**
     * 带悬浮标签的输入框。
     *
     * 字段名（如「阅读时长」）留在框外当标题；原来的小字提示搬进
     * 框里当悬浮标签——平时占在正文那一行，聚焦（点进输入框）或有内容时缩小上浮到框顶。
     *
     * 这样提示不再单独占一行，字段少一行高度；而且「能不能留空 / 该填什么」正好在要动手打字
     * 的时候贴着输入位置出现。原先标签、提示两行都在框外，一个字段占 118dp（24+2+18+6+56+12），
     * 三个字段就把弹窗顶到 420dp 的滚动上限上去。
     */
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
        // 用 miuix TextField 自带的浮动 label（hint）：控件内部按 insideMargin 垂直居中正文，
        // 有内容/聚焦时把 hint 上浮到框顶。此前是自己在输入框上盖一层 hint Text，正文与浮层
        // 各按各的基线摆放，于是「输入的内容相对输入框不居中」。交回原生控件后由它统一对齐。
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

    /**
     * Keep state-based text input alive for the lifetime of the editor.
     * Unlike the legacy value/onValueChange path, moving between these fields does not
     * tear down a legacy TextInputService session and hide/show the IME in between.
     */
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
    /** 「禁当期物」入口：一行摘要按钮，点开多选弹窗。 */
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

    /**
     * KSU 设置页那种下拉行：左「标题 + 说明」、右「当前值 + ⇅」，点整行在旁边弹出选项列表
     * （当前项主色高亮 + 勾选）。
     *
     * 选项少的选择器都走它，不再各自平铺 RadioButton——平铺在弹窗里和输入框抢高度。
     * 行与弹层必须是同一个 Box 的直接子节点：miuix 的列表弹层取「直接父布局」的窗口矩形
     * 当锚点，塞进 Row 里的话锚点会退化成一个 0 宽的占位。
     */
    @Composable
    private fun ChoiceDropdownField(
        title: String,
        summary: String,
        value: String,
        /** value 为空（或不在选项里）时右侧展示的文案，如「未选择」。 */
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
        WindowDropdownPreference(
            entry = entry, title = title, summary = summary, enabled = choices.isNotEmpty(),
            insideMargin = PaddingValues(vertical = 8.dp), maxHeight = 320.dp,
            onExpandedChange = { expanded ->
                if (expanded) { focusManager.clearFocus(); keyboard?.hide() }
            },
        )
        Spacer(Modifier.height(12.dp))
    }

    /**
     * 城池能否用这套（可为空=不带车马）车马通行。逐行照宿主 TravelingMerchantSheet 的
     * MerchantLoadout 有效性判定（smali 取证 2.3.2，两处校验点一致）：
     *
     * ```
     * if (requiredTransportType.isBlank() || transport != null) {
     *     if (transport != null) valid = transport.transportType == expected(routeType)
     *     else valid = true            // 仅当 requiredTransportType 为空才走到这里
     * } else valid = false             // requiredTransportType 非空且没带车马 → 去不了
     * ```
     *
     * 归纳：
     * - requiredTransportType 为空 → 带任何车马、或不带车马（「无」）都能去；
     * - requiredTransportType 非空（需舆蓁）→ 必须带车马，且 transportType 等于按 routeType
     *   推导的期望类型（LAND→HORSE，其余水路→SHIP）；不带车马**去不了**。
     *
     * 这就是「无车马能去的城池不多」的原因——只有不要求舆蓁的城池才对无车马开放。
     * expected 用 routeType 而非 requiredTransportType：后者只判「要不要车马」，routeType
     * 决定「要哪种」（宿主两个字段各司其职）。
     */
    private fun cityAccepts(
        city: CloudTaskLocalRunner.MerchantCityOption,
        transport: CloudTaskLocalRunner.MerchantTransportOption?,
    ): Boolean {
        val requiresTransport = city.requiredTransportType.isNotBlank()
        if (transport == null) return !requiresTransport
        return transport.transportType == CloudTaskLocalRunner.hostExpectedTransportType(city.routeType)
    }

    /**
     * 城池行程的预计耗时（分钟）：宿主公式
     * `ceil(baseDurationMinutes × (100 − speedPercent) / 100)`——速度加成是**减**耗时，
     * 没选车马按 0%（smali 取证自 `TravelingMerchantSheetKt.MerchantLoadout`）。
     */
    private fun merchantDurationMinutes(
        city: CloudTaskLocalRunner.MerchantCityOption,
        transport: CloudTaskLocalRunner.MerchantTransportOption?,
    ): Long {
        val speed = transport?.speedPercent ?: 0L
        return kotlin.math.ceil(city.baseDurationMinutes * (100.0 - speed) / 100.0).toLong()
    }

    /** 预计耗时的展示格式：不足一小时显示分钟，否则显示小时（整数省小数，如 1.5 小时）。 */
    private fun formatMerchantDuration(
        city: CloudTaskLocalRunner.MerchantCityOption,
        transport: CloudTaskLocalRunner.MerchantTransportOption?,
    ): String {
        val minutes = merchantDurationMinutes(city, transport)
        if (minutes < 60L) return uiText(R.string.duration_minutes, minutes)
        val hours = String.format(Locale.US, "%.1f", minutes / 60.0).removeSuffix(".0")
        return uiText(R.string.duration_hours, hours)
    }

    /**
     * 行商「城池」下拉：选项来自阅微 `get-traveling-merchant`。
     *
     * 与车马互斥——已选车马时只列它去得了的城池；换成去不了的城池时清掉原车马。
     * 小字是**已选城池**的预计耗时：按宿主行商准备页的公式
     * `ceil(baseDurationMinutes × (100 − speedPercent) / 100)` 打上车马速度加成
     * （smali 取证自 `TravelingMerchantSheetKt.MerchantLoadout`，没选车马按 0% 算）。
     */
    @Composable
    private fun CityDropdown(editor: TaskEditor, loading: Boolean) {
        // 值为空或 "0" 都表示不带车马（null），仅列出不要求车马的城池。
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
                // 原车马去不了新城池就清掉，让用户在筛过的清单里重挑。
                if (current != null && !cityAccepts(city, current)) {
                    editor.values[FIELD_TRANSPORT] = ""
                }
            },
        )
    }

    /**
     * 行商「车马」下拉：只列已拥有的；已选城池时只列去得了的车马（互斥同上）。
     * 选中后小字换成这匹/艘的实际效果——速度加成与负重（负重也是本金的上限）。
     */
    @Composable
    private fun TransportDropdown(editor: TaskEditor, loading: Boolean) {
        val selectedCity = editor.merchantCities.firstOrNull {
            it.code == editor.values[FIELD_CITY].orEmpty()
        }
        val compatible = editor.merchantTransports.filter { transport ->
            transport.owned && (selectedCity == null || cityAccepts(selectedCity, transport))
        }
        // 「无」车马：宿主允许不带舆蓁出行（transportId=0），耗时不打速度加成、本金上限走
        // noTransportCapacity。放在最前面，值用 MERCHANT_NO_TRANSPORT（"0"）与执行侧对齐。
        val selected = compatible.firstOrNull { it.id == editor.values[FIELD_TRANSPORT].orEmpty() }
        val isNoTransport = editor.values[FIELD_TRANSPORT].orEmpty().let { it.isBlank() || it == MERCHANT_NO_TRANSPORT }
        ChoiceDropdownField(
            title = uiText(R.string.field_transport),
            summary = when {
                loading -> uiText(R.string.merchant_transports_loading)
                selected != null -> uiText(R.string.merchant_transport_stats, selected.speedPercent, selected.carryingCapacity)
                // 选「无」和选具体车马一样显示负重，只是不带速度加成（无车马没有速度项）。
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
                    // 无车马只能去不需舆蓁的城池：若当前城池要求车马，清掉让用户重挑。
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
                // 原城池不收这匹车马就清掉，让用户在筛过的清单里重挑。
                if (current != null && !cityAccepts(current, transport)) {
                    editor.values[FIELD_CITY] = ""
                }
            },
        )
    }

    /**
     * 0 独立保留为「沿用上次」；轻滑到下限后，按当前车马的本金范围均匀分配轨道。
     * 上限 = 车马负重，不带车马时使用宿主 noTransportCapacity。
     */
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
        // 异步加载 / 更换车马只夹紧正本金；0 始终保持 0，并与保存字段同步。
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
                    style = MiuixTheme.textStyles.main,
                    color = muted,
                )
            }
            Slider(
                value = scale.fractionFor(amount),
                onValueChange = { fraction ->
                    if (!scale.enabled) return@Slider
                    val next = scale.amountFor(fraction)
                    amount = next
                    // 包括 0：回到左端后保存，执行侧才能真正沿用上次本金。
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
    /** 运签选择：空串是合法选项（不祈禳），wire 值（SAFETY/WEALTH）不进界面层。 */
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

    /**
     * 多选弹窗：一行一个选项，点一下就切换锁定状态。
     *
     * 「禁当期物」这种配置天然是一组开关——让用户手打 propId 既记不住也看不见。
     * 期物的名字与品质只在服务端，所以**每次打开弹窗都现拉一轮完整清单**（当日期物 +
     * 背包全量）并进图鉴。刻意不清掉"拉完不在清单里"的锁定项：
     * 期物可能只是今天已经典当光，用户锁它的意思还在。
     */
    @Composable
    private fun MultiSelectOverlay(editor: TaskEditor, label: String) {
        val options = editor.multiOptions[label].orEmpty()
        val picked = remember(label) {
            mutableStateListOf<String>().apply {
                addAll(CloudTaskLocalRunner.parseForbiddenPawnPropIds(editor.values[label].orEmpty()))
            }
        }
        var refreshing by remember { mutableStateOf(false) }
        // 打开即拉：老图鉴缺今天新见的期物，进来先补一轮。
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
        // 多选弹窗是从任务编辑器（WindowDialog，独立窗口）里二次弹出的，必须同样用 WindowDialog：
        // 页面级 OverlayDialog 渲染在 Scaffold 层，会被上层的编辑器窗口盖住——和城池/车马下拉
        // 同一个「弹出页面在配置页外面、点不到」的根因。
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
                            // 行内不再写「已锁定 / 可典当」：勾选框本身就是那个状态，
                            // 语义写在弹窗顶部那一行说明里就够了。
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
                        // 落盘还是同一份 ID 集合，沿用「按标签取字符串」通道回传。
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

    // ---- 通用 ----

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

    // ---- 模块界面自己的偏好 ----

    /** 界面偏好（隐藏后台卡片 / 隐藏桌面图标 / 界面设置）读：都落在同一份 prefs 里。 */
    private fun uiPrefBoolean(key: String, defValue: Boolean = false): Boolean =
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).getBoolean(key, defValue)

    /** 界面偏好写：同步落盘（commit），避免用户刚切完开关就被系统回收进程而丢设置。 */
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

    private fun writeUiPrefString(key: String, value: String) {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putString(key, value).commit()
    }

    /**
     * 构建时间文案。
     *
     * 取 BuildConfig.BUILD_TIME（`build.gradle.kts` 按构建时刻写进去的毫秒时间戳），不是
     * APK 的安装/更新时间——那两者会被「重新安装」带偏，看不出这一版是什么时候编出来的。
     */
    private fun buildTimeText(): String =
        runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", uiContext.resources.configuration.locales[0]).format(Date(BuildConfig.BUILD_TIME))
        }.getOrDefault(uiText(R.string.status_unknown))

    /** 打开外部链接（GitHub 仓库）。没有可用浏览器时给一句人话，别把异常抛到界面上。 */
    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { toast(uiText(R.string.error_open_link)) }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 在后台线程跑一段事，回到主线程更新。
     *
     * root 探测（要起 su 进程）与任务执行（要走网络）都不能卡 UI。结果直接回调对象，
     * 不要为了传值把多个字段拼成字符串再拆开——两端的字段顺序/数量一旦不一致就会渲染出
     * 互相矛盾的内容。
     */
    private fun <T> runBg(work: () -> T, then: (T) -> Unit) {
        Thread {
            runCatching(work)
                .onSuccess { value -> runOnUiThread { if (!isFinishing && !isDestroyed) then(value) } }
                .onFailure { error -> runOnUiThread { toast(error.message ?: error.javaClass.simpleName) } }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 下一个**未来**的任务时刻。
     *
     * 过期的 nextRunAt 不能当成"下次执行"显示——它其实已经到期、会在下一次唤醒时立刻跑；
     * 直接显示会得到"下次执行 09-14 20:02"这种早于当前时间的荒谬结果（用户报的就是这个）。
     */
    private fun futureNextRunAt(store: LocalTaskStore): Long? {
        val now = System.currentTimeMillis()
        return store.accountIds()
            .flatMap { accountId -> store.list(accountId) }
            .filter { it.enabled && it.nextRunAt > now }
            .minOfOrNull { it.nextRunAt }
    }

    /**
     * 任务卡片的「下次…」行，三种任务语义完全不同：
     * - 抽卡（自动祈愿）不是定时任务，它是签到奖励到账后触发的，写时间只会误导；
     * - 行商的下次执行是"轮询检查"，而且真正的节点是**当前这趟行商的结束时间**——那个时间只有
     *   调接口才知道，所以从最近一次执行记录里读出来一起显示，避免和游戏里看到的时间对不上；
     * - 其余任务是每天固定时间，直接显示下次时刻。
     */
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

    /** 任务结果的展示文案。内部值（success/failed/paused…）不该直接出现在界面上。 */
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
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }.getOrDefault("2.3.6")

    private fun formatTime(at: Long): String =
        if (at <= 0L) uiText(R.string.status_unscheduled) else SimpleDateFormat("MM-dd HH:mm", uiContext.resources.configuration.locales[0]).format(Date(at))

    private fun formatDateTime(at: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", uiContext.resources.configuration.locales[0]).format(Date(at))

    /** 记录列表的日分组标题：今天 / 昨天 / MM-dd（跨年才补年份）。 */
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

    /** 已经按天分组之后，行内只需要时刻。 */
    private fun formatClock(at: Long): String =
        SimpleDateFormat("HH:mm", uiContext.resources.configuration.locales[0]).format(Date(at))

    // ---- 展示用快照结构 ----

    /** 任务卡片行：accountId + 任务 + 规格 + 预生成的正文。 */
    private data class TaskRow(
        val accountId: String,
        val task: LocalTask,
        val spec: CloudAutomationTaskSpec?,
        val subtitle: String,
    )

    /** 纯文本弹窗：详情/说明/记录/日志共用，actions 非空时额外带「取消」。 */
    private data class TextDialogUi(
        val title: () -> String,
        val content: () -> String,
        val actions: List<Pair<Int, () -> Unit>> = emptyList(),
    )

    /** 任务编辑器的存活状态：values 按稳定字段键存当前值，multiOptions / 行商选项可被刷新替换。 */
    private class TaskEditor(
        val accountId: String,
        val task: LocalTask,
        val spec: CloudAutomationTaskSpec?,
    ) {
        val values = mutableStateMapOf<String, String>()
        // One stable buffer per editable text field, shared by rendering and Save.
        val textFields = mutableStateMapOf<String, TextFieldState>()
        val multiOptions = mutableStateMapOf<String, List<ModulePawnOption>>()

        /** 行商城池/车马全量选项，打开编辑器时后台从阅微拉；城池↔车马的互斥在 UI 侧现筛。 */
        var merchantCities: List<CloudTaskLocalRunner.MerchantCityOption> by mutableStateOf(emptyList())
        var merchantTransports: List<CloudTaskLocalRunner.MerchantTransportOption> by mutableStateOf(emptyList())

        /** 不带车马（“无”）时的本金上限——宿主 noTransportCapacity；未拉到按 0 处理。 */
        var merchantNoTransportCapacity: Long by mutableLongStateOf(0L)

        /** 行商可选项是否还在拉取中。 */
        var merchantLoading: Boolean by mutableStateOf(false)

        /** 非空时在该编辑器之上再弹多选弹窗。 */
        var multiOpenLabel: String? by mutableStateOf(null)
    }

    /** 下拉选择器的一个选项：value 是落库值，label 是展示文案。 */
    private data class DropdownChoice(val value: String, val label: String)

    private companion object {
        const val LOG_TAG = "ReaMicroMain"
        const val STATE_TAB = "selectedTab"
        const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"

        /** 模块界面自己的偏好文件与键名（隐藏后台卡片、隐藏桌面图标、界面设置）。 */
        const val UI_PREFS = "reamicro_module_ui"
        const val KEY_HIDE_RECENT_TASK = "hideRecentTask"
        const val KEY_HIDE_LAUNCHER_ICON = "hideLauncherIcon"
        const val KEY_LAUNCHER_ICON_STYLE = "launcherIconStyle"
        /** 未完成切换的日志：进程被杀后 [LauncherIconController.restore] 据此续做或回滚。 */
        const val KEY_LAUNCHER_ICON_PENDING = "launcherIconPending"
        const val KEY_LAUNCHER_ICON_PENDING_HIDDEN = "launcherIconPendingHidden"
        const val KEY_BLUR_BARS = "themeBlurBars"
        const val KEY_FLOATING_NAV_BAR = "themeFloatingNavBar"
        const val KEY_LIQUID_GLASS = "themeLiquidGlass"
        const val KEY_THEME_MODE = "themeMode"
        const val KEY_PAGER_GESTURE = "pagerGestureMode"
        const val KEY_SWIPE_BACK = "swipeBack"
        const val KEY_PREDICTIVE_BACK = "predictiveBack"

        /**
         * 模块包名、桌面图标别名与主界面类名。
         *
         * launcher 的 intent-filter 挂在 `ModuleLauncherAlias` 上（见 AndroidManifest.xml），
         * 「隐藏桌面图标」禁用的就是它——主界面 `ModuleMainActivity` 始终保持 enabled，
         * 所以恢复时要拉的是主界面：`am start -n com.reamicro.fix/.ui.ModuleMainActivity`
         * （**不是别名**，别名此刻正被禁用着，拉不起来）。
         */
        const val MODULE_PACKAGE = "com.reamicro.fix"
        const val MAIN_ACTIVITY_CLASS = "com.reamicro.fix.ui.ModuleMainActivity"

        const val THEME_FOLLOW_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        /** 悬浮底栏的圆角：与 miuix FloatingToolbarDefaults.CornerRadius 同为 28dp。 */
        val FLOAT_BAR_CORNER = 28.dp

        /** 顶栏/标准底栏的模糊半径与罩层透明度（对齐 KSU 的 BlurredBar：25f + surface 0.87）。 */
        const val BAR_BLUR_RADIUS = 25f
        const val BAR_TINT_ALPHA = 0.87f

        /** 液态玻璃胶囊表面的奶白罩层透明度：压一点底色保证图标可读，又不能厚到看不出玻璃。 */
        const val LIQUID_SURFACE_ALPHA = 0.4f

        /** 项目主页：「阅微补全计划」卡片点进去的地方。 */
        const val GITHUB_REPO_URL = "https://github.com/YGHFv/ReaMicro-Extend"

        // 底栏四页签：记录（历史）/ 任务（每任务开关与参数）/ 设置（系统级设置）/ 关于。
        const val TAB_RECORDS = 0
        const val TAB_TASKS = 1
        const val TAB_CONFIG = 2
        const val TAB_ABOUT = 3
        val TAB_TITLE_RES = listOf(R.string.tab_records, R.string.tab_tasks, R.string.tab_settings, R.string.tab_about)
        val TAB_ICONS = listOf(MiuixIcons.Recent, MiuixIcons.Tasks, MiuixIcons.Settings, MiuixIcons.Info)

        /** 窗口底色：首帧之前系统栏区域显示的颜色，跟着深浅色走（透明会让部分 ROM 露黑边）。 */
        val LIGHT_WINDOW_BG = android.graphics.Color.WHITE
        val DARK_WINDOW_BG = android.graphics.Color.BLACK

        // 编辑器内部键只用于临时状态；显示标签走资源，切换语言不会改变键。
        const val FIELD_TIME = "timeOfDay"
        const val FIELD_DURATION = "durationMinutes"
        const val FIELD_BOOKS = "books"
        const val FIELD_DRAW_LIMIT = "dailyDrawLimit"
        const val FIELD_PAWN = "forbiddenPawnPropIds"
        const val FIELD_CITY = "merchantCityCode"
        const val FIELD_PRINCIPAL = "merchantPrincipal"
        const val FIELD_TRANSPORT = "merchantTransportId"
        /** 车马下拉里「无」选项的值：与执行侧 transportId=0（不带舆蓁）对齐。 */
        const val MERCHANT_NO_TRANSPORT = "0"
        /** 宿主行商本金下限（smali 取证 2.3.2：principal 需 >= 10）。 */
        const val MERCHANT_MIN_PRINCIPAL = 10L
        const val FIELD_BLESSING = "blessingType"
    }
}