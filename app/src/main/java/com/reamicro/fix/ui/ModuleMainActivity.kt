package com.reamicro.fix.ui

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.cloud.api.CloudTaskWakeDiagnostics
import com.reamicro.fix.cloud.api.CloudTaskWakeScheduler
import com.reamicro.fix.cloud.api.NextWakeHint
import com.reamicro.fix.cloud.local.CloudTaskLocalRunner
import com.reamicro.fix.cloud.local.LocalTask
import com.reamicro.fix.cloud.local.LocalTaskBook
import com.reamicro.fix.cloud.local.LocalTaskRecord
import com.reamicro.fix.cloud.local.LocalTaskRunner
import com.reamicro.fix.cloud.local.LocalTaskStore
import com.reamicro.fix.cloud.ksu.KsuTaskBridge
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
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
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
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
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
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

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

    // ---- 页面状态 ----

    private val tab = mutableIntStateOf(TAB_RECORDS)

    /** 每次自增触发各快照重算；组合层只读这些快照。 */
    private val recordsState = mutableStateOf<List<Pair<String, LocalTaskRecord>>>(emptyList())
    private val failedCount = mutableIntStateOf(0)
    private val taskRows = mutableStateOf<List<TaskRow>>(emptyList())
    private val accountCount = mutableIntStateOf(0)
    private val overviewText = mutableStateOf("")
    private val wakeUi = mutableStateOf<WakeUi?>(null)
    private val hideRecentTask = mutableStateOf(false)
    private val notificationSummary = mutableStateOf("")
    private val logSummary = mutableStateOf("")
    private val rootUi = mutableStateOf<RootUi?>(null)

    // ---- 主题设置（设置页「主题」卡片）----

    /** 顶栏与底栏的毛玻璃背景（miuix-blur）：栏底色转透明，改为对穿过的内容做模糊。 */
    private val blurBars = mutableStateOf(false)

    /** Apple 风格悬浮底栏：底栏收成一枚悬浮胶囊，内容从它底下穿过。 */
    private val floatingNavBar = mutableStateOf(false)

    /** 悬浮底栏的液态玻璃效果（仅悬浮底栏开启时生效/显示）：胶囊对底下内容实时模糊 + 玻璃描边。 */
    private val liquidGlass = mutableStateOf(true)

    // ---- 弹窗状态 ----

    private val textDialog = mutableStateOf<TextDialogUi?>(null)
    private val editorDialog = mutableStateOf<TaskEditor?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        ModuleLogBuffer.attach(this)
        super.onCreate(savedInstanceState)
        // 窗口底色跟着深浅色走：首帧之前系统栏区域显示的就是它（透明会让部分 ROM 露黑边）。
        window?.setBackgroundDrawable(ColorDrawable(if (isNightMode()) DARK_WINDOW_BG else LIGHT_WINDOW_BG))
        ModuleAndroidLog.legacy(LOG_TAG, "module main ui opened")
        refresh()
        setContent {
            val dark = isSystemInDarkTheme()
            ImmersiveSystemBars(dark)
            SystemBarAppearance(dark)
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                ModuleApp()
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

    override fun onResume() {
        super.onResume()
        setTaskExcludedFromRecents(false)
        KsuTaskBridge.requestSync(applicationContext) { runOnUiThread { if (!isFinishing && !isDestroyed) refresh() } }
    }

    override fun onUserLeaveHint() {
        if (hideRecentTask.value) setTaskExcludedFromRecents(true)
        super.onUserLeaveHint()
    }

    override fun onStop() {
        if (!isChangingConfigurations && hideRecentTask.value) setTaskExcludedFromRecents(true)
        super.onStop()
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

        val status = CloudTaskWakeDiagnostics.inspect(this)
        wakeUi.value = WakeUi(
            healthy = status.healthy,
            summary = status.summary(),
            details = status.details().joinToString("\n"),
            notificationAllowed = status.notificationAllowed,
            exactAlarmAllowed = status.exactAlarmAllowed,
            batteryUnrestricted = status.batteryUnrestricted,
        )
        hideRecentTask.value = hideRecentTaskEnabled()
        blurBars.value = uiPrefBoolean(KEY_BLUR_BARS)
        floatingNavBar.value = uiPrefBoolean(KEY_FLOATING_NAV_BAR)
        // 液态玻璃默认开（KSU 也是这个默认值）：键不存在时取 true。
        liquidGlass.value = uiPrefBoolean(KEY_LIQUID_GLASS, true)

        val notifications = NotificationRecordStore { context }.list()
        notificationSummary.value = if (notifications.isEmpty()) {
            "还没有通知记录。任务结果通知会在这里留下投递结果（含失败原因）。"
        } else {
            val delivered = notifications.count { it.delivered }
            "共 ${notifications.size} 条：成功投出 $delivered 条，未投出 ${notifications.size - delivered} 条。\n" +
                "未投出的说明模块进程当时没能发出通知（被系统冻结或未启动），服务器会保留该消息下次重发。"
        }
        val logs = ModuleLogBuffer.snapshot()
        val path = ModuleLogBuffer.filePath()
        logSummary.value = "共 ${logs.size} 条（最新在前）。模块进程的日志默认不出现在 logcat 里，这里能直接看到。" +
            (path?.let { "\n落盘位置：$it" } ?: "\n尚未落盘（模块还没被唤醒过）")
    }

    /** 「任务概览」正文，与旧版逐字对齐（唤醒早于任务时刻的说明必须保留）。 */
    /**
     * 任务概览的两行播报。
     *
     * 刻意**不带任何括号注解**（原来那几处「看门狗按这个时刻唤醒…」「早于任务时刻属正常…」
     * 已按需求删掉）：这些属于实现细节，概览只播报此刻的事实——唤醒时刻、下次任务时刻。
     * 唤醒可能早于任务本身是正常的（零点例行唤醒、云端任务完成时刻），但那不是用户需要
     * 在概览里读的信息。
     */
    private fun buildOverviewText(store: LocalTaskStore): String = buildString {
        val enabled = taskRows.value.count { it.task.enabled }
        append("${accountCount.value} 个账号、$enabled 个任务已启用")
        val wakeAt = NextWakeHint.read(this@ModuleMainActivity)
        append("\n唤醒时刻：${wakeAt.takeIf { it > 0L }?.let(::formatTime) ?: "未排程"}")
        val nextTaskAt = futureNextRunAt(store)
        append("\n下次任务时刻：${nextTaskAt?.let(::formatTime) ?: "无"}")
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
                append("运签 ")
                append(CloudTaskLocalRunner.blessingLabel(task.blessingType))
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

    /**
     * 分组小标题。
     *
     * SmallTitle 默认的内边距是水平 28dp，跟卡片自己的 12dp 凑在一起会明显错位；这里统一
     * 收成「卡片 12dp + 缩进 12dp」。分组标题比卡片内文字更靠外，是 miuix 分组标题的惯例。
     */
    @Composable
    private fun GroupTitle(text: String) {
        SmallTitle(
            text = text,
            insideMargin = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        )
    }

    /** KSU 卡片里的分隔线：0.5dp、outline 50% 透明（ModuleItem 的原值）。 */
    @Composable
    private fun CardDivider() {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            thickness = 0.5.dp,
            color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
        )
    }

    /** KSU 模块卡片骨架：标题行 + 可选副信息 / 描述 + 可选操作行。 */
    @Composable
    private fun SectionCard(
        title: String,
        titleColor: Color = MiuixTheme.colorScheme.onSurface,
        subtitle: String? = null,
        description: String? = null,
        trailing: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        actions: (@Composable RowScope.() -> Unit)? = null,
    ) {
        Card(
            // KSU 的卡片自带 12dp 水平边距，页面不用再给——所以列表页的水平 padding 让给了它。
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            insideMargin = PaddingValues(16.dp),
            onClick = onClick,
            showIndication = onClick != null,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 17.sp, fontWeight = FontWeight(550), color = titleColor)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            modifier = Modifier.padding(top = 2.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight(550),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (trailing != null) {
                    Spacer(Modifier.width(8.dp))
                    trailing()
                }
            }
            if (description != null) {
                Text(
                    description,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (actions != null) {
                CardDivider()
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
    }

    /**
     * KSU 卡片底部的胶囊按钮：浅色底 + 图标 + 文字。
     *
     * KSU 的「执行」按钮是一枚 `clip(CircleShape).background(secondaryContainer)` 的手搓
     * 胶囊，右侧「配置 / 卸载」则用 `IconButton(backgroundColor = ...)`。这里统一走 miuix
     * 的 [IconButton]（底色 `secondaryContainer`、最小 35dp、默认 40dp 圆角）——两者观感
     * 一致，也不会绕开库自己的按压反馈。
     */
    @Composable
    private fun CapsuleButton(
        icon: ImageVector,
        label: String,
        onClick: () -> Unit,
    ) {
        // KSU 的图标色：深色 0.7、浅色 0.9 的 onSurface。
        val tint = MiuixTheme.colorScheme.onSurface.copy(alpha = if (isSystemInDarkTheme()) 0.7f else 0.9f)
        IconButton(
            onClick = onClick,
            backgroundColor = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
            minHeight = 35.dp,
            minWidth = 35.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = tint,
                )
                Spacer(Modifier.width(4.dp))
                Text(label, color = tint, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    /** 不带图标的胶囊按钮（权限跳转这一类小动作，配图标反而挤）。 */
    @Composable
    private fun CapsuleTextButton(label: String, onClick: () -> Unit) {
        val tint = MiuixTheme.colorScheme.onSurface.copy(alpha = if (isSystemInDarkTheme()) 0.7f else 0.9f)
        IconButton(
            onClick = onClick,
            backgroundColor = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
            minHeight = 35.dp,
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 14.dp),
                color = tint,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }

    @Composable
    private fun ModuleApp() {
        // key(tab)：每个页签整套重来。滚到大标题收起状态后切页，若共用同一个 ScrollBehavior，
        // 新页面会「标题已经是收起态、内容却在顶部」；顺带每页也各自一份滚动位置。
        key(tab.intValue) {
            // 大标题随滚动收起：miuix / HyperOS 应用（KernelSU 管理器也是这套）的标准做法。
            // 标题给的是**当前页签**，不是模块名——模块名归「关于」页。
            val scrollBehavior = MiuixScrollBehavior()
            // 下拉刷新：顶栏不再放刷新按钮，任务页直接下拉触发（原「重算下次时刻」）。
            var pullRefreshing by remember { mutableStateOf(false) }
            // 主题设置的三个开关（设置页「主题」卡片）。全关时整套渲染与改动前逐像素一致。
            // RuntimeShader 要 Android 13+：不支持的设备自动退回不透明栏，不出现透明花屏。
            val blurSupported = remember { isRuntimeShaderSupported() }
            val blurred = blurBars.value && blurSupported
            val floating = floatingNavBar.value
            val liquid = floating && liquidGlass.value && blurSupported
            val surface = MiuixTheme.colorScheme.surface
            // 毛玻璃取样源（KernelSU 管理器同款写法）：整页内容录进一层，栏对这层做模糊。
            // 录之前先铺一层 surface——必须与 Scaffold 的 containerColor（也是 surface）同值：
            // 铺错颜色（比如硬编码白）会把白卡片盖在同色页面上，卡片背景直接「消失」。
            val backdrop = rememberLayerBackdrop {
                drawRect(surface)
                drawContent()
            }
            // 栏的玻璃罩层：模糊后的内容上压一层半透明 surface，保证文字可读（KSU 0.87）。
            val barBlurColors = BlurColors(
                blendColors = listOf(BlendColorEntry(surface.copy(alpha = BAR_TINT_ALPHA))),
            )
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = TAB_TITLES[tab.intValue],
                        largeTitle = TAB_TITLES[tab.intValue],
                        scrollBehavior = scrollBehavior,
                        // 模糊开启时底色交给玻璃层：TopAppBar 自带底色，不改透明会把模糊整个盖住。
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
                        // KSU 风格悬浮胶囊：图标在上、页签名在下，选中项主色 + 圆角高亮块。
                        // 三档外观——液态玻璃开：vibrancy + 小半径模糊 + 边缘折射（LiquidGlass.kt，
                        // 与 KernelSU 底栏同配方）；只开模糊：与顶栏同款的大半径毛玻璃；
                        // 都不开：不透明 surfaceContainer。
                        val capsuleShape = RoundedCornerShape(FLOAT_BAR_CORNER)
                        val capsuleBackground = when {
                            liquid -> Modifier
                                // 折射需要向外多采 40dp（liquidCapsuleEffects 里的 padding），
                                // 但那圈「效果区」不该被看见——尤其下拉越界时它录的是未拉伸的
                                // 旧内容，会在胶囊外圈露出一圈灰色残影。裁到胶囊形内：取样照旧
                                // （读的是背景层纹理，不受画布裁剪影响），光晕消失。
                                .clip(capsuleShape)
                                .drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { capsuleShape },
                                    effects = { liquidCapsuleEffects() },
                                    highlight = { LiquidCapsuleHighlight },
                                    onDrawSurface = { drawRect(surface.copy(alpha = LIQUID_SURFACE_ALPHA)) },
                                )
                            blurred -> Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { capsuleShape },
                                effects = {
                                    blur(BAR_BLUR_RADIUS)
                                    blendColors(barBlurColors)
                                },
                            )
                            else -> Modifier.background(MiuixTheme.colorScheme.surfaceContainer, capsuleShape)
                        }
                        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 28.dp)
                                // KSU 的底部留白：有手势条时贴条上 8dp，三大金刚键时抬 28dp。
                                .padding(bottom = if (navInset > 0.dp) 8.dp + navInset else 28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .dropShadow(
                                        shape = capsuleShape,
                                        shadow = Shadow(
                                            radius = 10.dp,
                                            color = Color.Black,
                                            alpha = if (isSystemInDarkTheme()) 0.2f else 0.1f,
                                        ),
                                    )
                                    .then(capsuleBackground)
                                    .height(64.dp)
                                    .selectableGroup()
                                    .padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TAB_ICONS.forEachIndexed { index, icon ->
                                    val selected = tab.intValue == index
                                    // 选中态：主色内容 + 一层主色淡底圆块（Material 导航指示器的写法）。
                                    val itemColor = if (selected) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantActions
                                    }
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .background(
                                                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                else Color.Transparent,
                                                CircleShape,
                                            )
                                            .selectable(
                                                selected = selected,
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                role = Role.Tab,
                                                onClick = { tab.intValue = index },
                                            ),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = TAB_TITLES[index],
                                            tint = itemColor,
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Text(
                                            text = TAB_TITLES[index],
                                            color = itemColor,
                                            fontSize = 11.sp,
                                            lineHeight = 14.sp,
                                            maxLines = 1,
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
                                    selected = tab.intValue == index,
                                    onClick = { tab.intValue = index },
                                    icon = icon,
                                    label = TAB_TITLES[index],
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                // 只有任务页有下拉刷新（重算下次时刻），其余页签下拉没有任何意义，
                // 不包 PullToRefresh——多一层只会徒增下拉时差（玻璃底栏透灰影）的暴露面。
                val scrollContent: @Composable () -> Unit = {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            // 越界回弹必须在绑定滚动行为之前加。
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                            // 内容即玻璃的取样源；没有任何玻璃消费者时不登记，省掉一次全屏图层录制。
                            .then(
                                if (blurred || liquid) {
                                    Modifier.layerBackdrop(backdrop)
                                } else {
                                    Modifier
                                },
                            )
                            .verticalScroll(rememberScrollState())
                            // 顶栏/底栏的让位改成「滚动容器内」的上内边距：静止位置与滚动范围
                            // 和原来完全一致，区别只是滚动途中内容会从栏底下穿过——栏不透明时
                            // 看不出来，开了玻璃才显现（「模糊」要的就是这个）。
                            .padding(top = padding.calculateTopPadding())
                            // 卡片的水平边距交给卡片自己（KSU 的 12dp），页面只留纵向节奏。
                            .padding(vertical = 4.dp),
                    ) {
                        when (tab.intValue) {
                            TAB_TASKS -> TasksPage()
                            TAB_CONFIG -> ConfigPage()
                            TAB_ABOUT -> AboutPage()
                            else -> RecordsPage()
                        }
                        // 栏高留在滚动末尾。原来这份让位在容器外，内容永远到不了栏底下；
                        // 挪进来之后必须补这个占位，否则滚到底时最后一张卡片会压在栏底下。
                        Spacer(Modifier.height(padding.calculateBottomPadding()))
                        Spacer(Modifier.height(4.dp))
                    }
                }
                if (tab.intValue == TAB_TASKS) {
                    PullToRefresh(
                        isRefreshing = pullRefreshing,
                        onRefresh = {
                            pullRefreshing = true
                            recomputeSchedule { pullRefreshing = false }
                        },
                        // miuix 默认文案是英文（"Release to refresh"），换成中文。
                        refreshTexts = listOf("下拉刷新", "松手刷新", "正在刷新…", "刷新成功"),
                        contentPadding = PaddingValues(top = padding.calculateTopPadding()),
                        topAppBarScrollBehavior = scrollBehavior,
                    ) {
                        scrollContent()
                    }
                } else {
                    scrollContent()
                }
                Dialogs()
            }
        }
    }

    // ---- 记录页 ----

    @Composable
    private fun RecordsPage() {
        val list = recordsState.value
        if (list.isEmpty()) {
            SectionCard(
                title = "还没有执行记录",
                description = "到「任务」页点任务卡片上的「执行」可以先跑一轮；点任意一条记录能看详情（运签、奖励、行商事件）",
            )
            return
        }
        Text(
            "共 ${list.size} 条 · 失败 ${failedCount.intValue} 条",
            modifier = Modifier.padding(horizontal = 28.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(2.dp))
        // 按天分组：同一天只出一行日标题（今天 / 昨天 / MM-dd），行内就只留 HH:mm——
        // 比每条都写满「09-24 08:04:44」清爽，也更容易按天扫读。
        list.forEachIndexed { index, (accountId, record) ->
            val day = dayLabel(record.at)
            if (index == 0 || dayLabel(list[index - 1].second.at) != day) {
                GroupTitle(day)
            }
            RecordCard(accountId, record)
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
            title = taskTitle(record.taskType) + if (failed) "（${resultLabel(record.result)}）" else "",
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
            title = "任务概览",
            description = overviewText.value,
        )
        if (taskRows.value.isEmpty()) {
            SectionCard(
                title = "还没有本地任务",
                description = "在阅微的设置页里启用任务后，这里就能配置",
            )
        } else {
            taskRows.value.forEach { row -> TaskCard(row) }
        }
        Text(
            "本地任务由模块进程执行；与阅微设置页同步时保留最新配置，旧镜像不会覆盖刚保存的选择。",
            modifier = Modifier.padding(horizontal = 28.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }

    // ---- 设置页 ----

    @Composable
    private fun ConfigPage() {
        // Root 增强原来挂在「关于」页，按需求搬到设置页：它本质是后台唤醒的一种实现方式，
        // 和下面那组「权限与后台」本就是同一件事。
        // 探测要起 su 进程，进页一次即可；结果落在 rootUi。
        LaunchedEffect(Unit) {
            if (rootUi.value == null) {
                runBg(
                    work = { RootWakeController.inspect(applicationContext) },
                    then = { status ->
                        rootUi.value = RootUi(status.rootAvailable, status.displayTitle(), status.message)
                    },
                )
            }
        }
        GroupTitle("主题")
        // 外观相关的开关统一收在一张卡片里（KSU 的「主题设置」也是这个分组法）：
        // 几行都是 SwitchPreference，标题 + 说明 + 右侧开关，行间不加分隔线（miuix 标准样式）。
        // 「液态玻璃」只对悬浮底栏有意义，所以只在悬浮底栏开启时出现。
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            SwitchPreference(
                title = "模糊",
                summary = "启用顶栏和底栏的模糊效果",
                checked = blurBars.value,
                onCheckedChange = ::toggleBlurBars,
            )
            SwitchPreference(
                title = "悬浮底栏",
                summary = "使用 Apple 风格的悬浮底栏",
                checked = floatingNavBar.value,
                onCheckedChange = ::toggleFloatingNavBar,
            )
            if (floatingNavBar.value) {
                SwitchPreference(
                    title = "液态玻璃",
                    summary = "启用悬浮底栏的液态玻璃效果",
                    checked = liquidGlass.value,
                    onCheckedChange = ::toggleLiquidGlass,
                )
            }
        }

        GroupTitle("权限与后台")
        WakeCards()

        GroupTitle("Root 增强（实验性）")
        RootCard()

        GroupTitle("通知与日志")
        SectionCard(
            title = "通知记录",
            description = notificationSummary.value,
        ) {
            CapsuleButton(MiuixIcons.Info, "查看") { showNotificationRecords() }
            Spacer(Modifier.weight(1f))
            CapsuleButton(MiuixIcons.Delete, "清空") {
                NotificationRecordStore { applicationContext }.clear()
                toast("通知记录已清空")
                refresh()
            }
        }
        SectionCard(
            title = "模块日志",
            description = logSummary.value,
        ) {
            CapsuleButton(MiuixIcons.Info, "查看") { showLogs() }
            Spacer(Modifier.weight(1f))
            CapsuleButton(MiuixIcons.Delete, "清空") {
                ModuleLogBuffer.clear()
                toast("日志已清空")
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
            title = row.spec?.title ?: taskTitle(row.task.taskType),
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
            CapsuleButton(MiuixIcons.Play, "执行") { runSingleTask(row.accountId, row.task) }
            Spacer(Modifier.weight(1f))
            CapsuleButton(MiuixIcons.Settings, "配置") { openTaskEditor(row.accountId, row.task, row.spec) }
        }
    }

    /** Root 增强（实验性）：状态 + 说明 + 启用/停用。原先在「关于」页，现归设置页。 */
    @Composable
    private fun RootCard() {
        val root = rootUi.value
        SectionCard(
            title = root?.title ?: "Root 状态（正在检测…）",
            titleColor = if (root?.rootAvailable == true) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            description = buildString {
                if (root != null) {
                    append(root.message)
                    append('\n')
                }
                append(
                    "部分机型（如 HyperOS）会冻结后台应用，冻结期间系统闹钟与通知广播都不会执行。" +
                        "启用后，由 root 侧的看门狗在任务时刻唤醒模块（模块每次排完闹钟会把下次时刻写给它），" +
                        "不受冻结影响。\n开启会在 /data/adb/service.d 写入一个开机脚本；停用会删除它。",
                )
            },
        ) {
            CapsuleButton(MiuixIcons.Play, "启用") { rootAction { RootWakeController.enable(applicationContext) } }
            Spacer(Modifier.weight(1f))
            CapsuleButton(MiuixIcons.Delete, "停用") { rootAction { RootWakeController.disable(applicationContext) } }
        }
    }

    @Composable
    private fun WakeCards() {
        val wake = wakeUi.value
        SectionCard(
            title = "后台唤醒",
            titleColor = if (wake == null || wake.healthy) MiuixTheme.colorScheme.onSurface else DangerRed,
            description = wake?.let { w ->
                if (w.details.isNotBlank()) "${w.summary}\n${w.details}" else w.summary
            },
        )
        SectionCard(
            title = "本地任务执行模式",
            description = KsuTaskBridge.statusText(applicationContext),
        ) {
            CapsuleButton(MiuixIcons.Settings, "说明与切换") { showExecutionModeDialog() }
            Spacer(Modifier.weight(1f))
            CapsuleButton(MiuixIcons.Update, "同步状态") {
                rootAction { KsuTaskBridge.synchronize(applicationContext); "状态已同步" }
            }
        }

        // 「隐藏后台卡片」开关：打开 = 返回桌面时把模块从最近任务卡片里藏起来
        // （onUserLeaveHint/onStop 里的 setExcludeFromRecents），不影响自动任务。
        // checked 直接跟 hideRecentTask 状态走，不再放说明行。
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            SwitchPreference(
                title = "隐藏后台卡片",
                checked = hideRecentTask.value,
                onCheckedChange = { toggleHideRecentTask() },
            )
        }

        SectionCard(
            title = "授权与跳转",
            description = "通知权限要在这里授予；精确闹钟与电池优化放行后，通知才不会延迟数小时。",
        ) {
            PermissionActions()
        }
    }

    /**
     * 授权与跳转的动作按钮：**一行两个、左右各一**，和其它卡片操作行同一个节奏。
     *
     * 顺序上，缺权限才出现的三个入口排在前面，两个固定动作（厂商自启动 / 重排闹钟）殿后；
     * 权限都放行过（多数用户）就只剩后面两个，正好左一个右一个。真到了五个都齐的情况，
     * 就按两两一行铺开，末行左对齐即可——不会像原来 FlowRow 那样把标题和按钮挤成一团。
     *
     * 两个固定动作带图标（后台 / 闹钟，和 KSU 卡片的「执行」同一套胶囊观感）；
     * 三个权限入口是系统设置跳转，无图标胶囊即可。
     */
    @Composable
    private fun PermissionActions() {
        val wake = wakeUi.value
        // (图标, 文案, 动作)：图标为空 = 纯文字胶囊。
        val entries = buildList {
            if (wake != null && !wake.notificationAllowed) {
                add(Triple<ImageVector?, String, () -> Unit>(null, "开启通知") { requestNotificationPermission() })
            }
            if (wake != null && !wake.exactAlarmAllowed) {
                add(Triple<ImageVector?, String, () -> Unit>(null, "精确闹钟") { openSystemSettings(CloudTaskWakeDiagnostics.exactAlarmSettingsIntent()) })
            }
            if (wake != null && !wake.batteryUnrestricted) {
                add(Triple<ImageVector?, String, () -> Unit>(null, "电池优化") { openSystemSettings(CloudTaskWakeDiagnostics.batteryOptimizationIntent()) })
            }
            add(Triple<ImageVector?, String, () -> Unit>(MiuixIcons.Tune, "厂商自启动") { openAutoStartSettings() })
            add(Triple<ImageVector?, String, () -> Unit>(MiuixIcons.Alarm, "重排闹钟") { rescheduleAlarm() })
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pair.forEachIndexed { index, (icon, text, action) ->
                        if (index > 0) Spacer(Modifier.weight(1f))
                        if (icon != null) {
                            CapsuleButton(icon, text, action)
                        } else {
                            CapsuleTextButton(text, action)
                        }
                    }
                }
            }
        }
    }

    /**
     * 任务页右上角那个按钮做的事：只按配置的时间点重算下次执行时刻，**不执行任务**。
     *
     * 专治历史遗留的「上次跑完 + 24 小时」——老版本写进 prefs 的下次时刻不会自己跟着配置
     * 走，重算一次即可对齐；顺带把系统闹钟按新时刻重排。
     */
    private fun recomputeSchedule(onFinish: () -> Unit = {}) {
        runBg(
            work = {
                val updated = LocalTaskStore { applicationContext }.rescheduleEnabledTasks()
                runCatching { CloudTaskWakeScheduler.schedule(applicationContext) }
                if (updated > 0) "已按配置时间重算 $updated 个任务" else "任务时刻已经和配置一致"
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
        // 开关变了要重排闹钟，否则新启用的任务不会被唤醒执行。
        runCatching { CloudTaskWakeScheduler.schedule(applicationContext) }
        toast("${taskTitle(task.taskType)}已${if (enabled) "启用" else "停用"}")
        refresh()
    }

    private fun toggleHideRecentTask() {
        val enabled = !hideRecentTask.value
        writeUiPref(KEY_HIDE_RECENT_TASK, enabled)
        setTaskExcludedFromRecents(false)
        hideRecentTask.value = enabled
        toast(if (enabled) "返回桌面后自动隐藏模块后台卡片" else "模块后台卡片恢复显示")
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

    private fun setTaskExcludedFromRecents(excluded: Boolean) {
        runCatching {
            getSystemService(ActivityManager::class.java)?.appTasks
                ?.firstOrNull { appTask ->
                    val info = appTask.taskInfo
                    val recentTaskId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info?.taskId else info?.persistentId
                    recentTaskId == taskId
                }?.setExcludeFromRecents(excluded)
        }.onFailure { ModuleAndroidLog.error(LOG_TAG, "更新后台卡片可见性失败", it) }
    }

    // ---- 任务编辑弹窗 ----

    /**
     * 任务配置编辑：字段随任务类型变化（与阅微设置页同一套语义）。
     *
     * 所有字段值都落在 [TaskEditor.values]（按标签取值），保存时直接交给
     * [applyTaskEdits]——与旧版 editDialog 的 register 通道等价，标签是唯一键。
     */
    private fun openTaskEditor(accountId: String, task: LocalTask, spec: CloudAutomationTaskSpec?) {
        val editor = TaskEditor(accountId, task, spec)
        fun put(label: String, value: String) {
            editor.values[label] = value
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
                    toast(result.exceptionOrNull()?.message ?: "读取期物清单失败")
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
                    toast(result.exceptionOrNull()?.message ?: "读取城池/车马失败")
                } else {
                    editor.merchantCities = fetched.cities
                    editor.merchantTransports = fetched.transports
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
    private fun pawnOptionsFromState(accountId: String): List<MultiSelectOption> {
        val state = LocalTaskStore { applicationContext }.runtimeState(accountId, "pawn")
        return CloudTaskLocalRunner.pawnPropChoices(state).map {
            MultiSelectOption(it.propId, it.label, cloudTaskQualityColor(it.quality))
        }
    }

    /**
     * 现拉一次期物全量（当日期物 + 背包）：期物的名字与品质只在服务端。
     *
     * 现在编辑器每次打开都会自动走这一趟，所以**并进**已存图鉴而不是整表替换——
     * 替换会把上周见过、今天已不在背包里的期物从图鉴里抹掉，已锁它的配置就没了名字。
     */
    private fun fetchPawnOptions(accountId: String): List<MultiSelectOption> {
        val pawnStore = LocalTaskStore { applicationContext }
        val token = pawnStore.token(accountId)
        if (token.isBlank()) {
            throw IllegalStateException("阅微登录凭据无效，请重新登录后再刷新")
        }
        val fetched = CloudTaskLocalRunner.fetchPawnPropCatalog(token)
        fetched.error?.let { reason ->
            runOnUiThread { toast("部分期物没读到：$reason") }
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
            MultiSelectOption(it.propId, it.label, cloudTaskQualityColor(it.quality))
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
            "%02d:%02d".format(hour, minute)
        } else task.timeOfDay
        val duration = if (spec?.autoRead == true) {
            text(FIELD_DURATION).toIntOrNull()?.takeIf { it in 1..720 } ?: return null
        } else task.durationMinutes
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
                toast("本金不能超过当前车马的负重（$capacity）")
                return
            }
        }
        val fields = editor.values.mapValues { (_, value) -> { value } }
        val updated = applyTaskEdits(editor.task, editor.spec, fields)
        if (updated == null) {
            toast("填写有误，请检查时间与数字")
            return
        }
        val store = LocalTaskStore { applicationContext }
        store.saveTask(editor.accountId, updated, store.token(editor.accountId))
        runCatching { CloudTaskWakeScheduler.schedule(applicationContext) }
        toast("配置已保存")
        editorDialog.value = null
        refresh()
    }

    // ---- 文本弹窗 ----

    private fun showRecordDetail(accountId: String, record: LocalTaskRecord) {
        val detail = runCatching { JSONObject(record.detail) }.getOrNull()
        val lines = buildList {
            add("时间：${formatDateTime(record.at)}")
            add("任务：${taskTitle(record.taskType)}")
            add("账号：$accountId")
            add("结果：${resultLabel(record.result)}")
            add("")
            add(record.message)
        }
        textDialog.value = TextDialogUi(
            title = "${taskTitle(record.taskType)} 详情",
            content = localTaskRecordDetailText(
                lines.joinToString("\n"),
                detail?.let { localTaskRecordDetailFields(record.taskType, it) }.orEmpty(),
            ).toString(),
        )
    }

    private fun showExecutionModeDialog() {
        textDialog.value = TextDialogUi(
            title = "本地任务执行模式",
            content = "Android 模式由系统闹钟与后台任务执行。KSU 模式由刷入模块的独立进程运行同一套任务逻辑，APK 被关闭也能继续。\n\n" +
                "两种模式共用原来的本地任务设置，不需要重新配置。切换只等待本机正在执行的这一轮完成并保存进度，不会等待行商旅程结束或轶闻解锁；共用配置也不能跳过这个防重复执行的交接。\n\n" +
                "KSU 模式为实验功能：需刷入配套 ZIP、授权本应用 root，只支持主用户。登录凭据会复制到 /data/adb/reamicro-automation 的 root 私有文件（目录 700、文件 600），普通应用不可读。切回 Android 会同步最终记录并清除该凭据副本。\n\n" +
                "配套 KSU 模块已内置在 APK 里：点「使用 KSU」会先检查模块是否已刷入，没有就用 ksud 自动安装，装好后自动切换到 KSU 模式。如果安装后提示需要重启，重启设备再点一次即可。\n\n" +
                "KSU 仍可能受设备休眠、断网、模块停用、token 失效或接口风控影响，并非绝对准时。检测失败时不自动切回，避免重复消费。卸载 KSU 模块前必须先切回 Android。",
            actions = listOf(
                "使用 KSU" to { rootAction { KsuTaskBridge.enable(applicationContext) } },
                "使用 Android" to { rootAction { KsuTaskBridge.disable(applicationContext) } },
            ),
        )
    }

    private fun showNotificationRecords() {
        val records = NotificationRecordStore { applicationContext }.list()
        textDialog.value = TextDialogUi(
            title = "通知记录",
            content = if (records.isEmpty()) {
                "还没有通知记录。"
            } else {
                records.joinToString("\n\n") { record ->
                    buildString {
                        append(formatDateTime(record.at))
                        append(" · ")
                        append(if (record.delivered) "已发出" else "未发出")
                        append('\n')
                        append(record.title)
                        append('\n')
                        append(record.text)
                        append("\n来源：")
                        append(record.source)
                        if (record.detail.isNotBlank()) {
                            append(" · ")
                            append(record.detail)
                        }
                    }
                }
            },
            actions = listOf(
                "清空" to {
                    NotificationRecordStore { applicationContext }.clear()
                    toast("通知记录已清空")
                    textDialog.value = null
                    refresh()
                },
            ),
        )
    }

    private fun showLogs() {
        val logs = ModuleLogBuffer.snapshot()
        textDialog.value = TextDialogUi(
            title = "模块日志",
            content = if (logs.isEmpty()) {
                "暂无日志。"
            } else {
                logs.joinToString("\n") { "${formatDateTime(it.at)} ${it.level}/${it.tag}: ${it.message}" }
            },
            actions = listOf(
                "清空" to {
                    ModuleLogBuffer.clear()
                    toast("日志已清空")
                    textDialog.value = null
                    refresh()
                },
            ),
        )
    }

    // ---- 关于页 ----

    /**
     * 「阅微补全计划」卡片：副标题是模块版本 + 构建时间，整张卡片点击打开 GitHub 仓库。
     *
     * 构建时间取自 BuildConfig.BUILD_TIME（`build.gradle.kts` 里按构建时刻写入的毫秒时间戳），
     * 不是 APK 的安装/更新时间——那两者会被「重新安装」带偏，看不出这一版是什么时候编出来的。
     */
    @Composable
    private fun AboutPage() {
        SectionCard(
            title = "阅微补全计划",
            subtitle = "模块 ${versionLine()} · 构建 ${buildTimeText()}",
            onClick = { openUrl(GITHUB_REPO_URL) },
        )
        GroupTitle("状态")
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            insideMargin = PaddingValues(16.dp),
        ) {
            StatusLine("模块进程", "PID ${android.os.Process.myPid()}")
            CardDivider()
            StatusLine(
                "通知权限",
                if (CloudTaskNotifications.hasPermission(this@ModuleMainActivity)) "已授予"
                else "未授予（结果只能在打开阅微时以提示条显示）",
            )
            CardDivider()
            StatusLine("唤醒时刻", formatTime(NextWakeHint.read(this@ModuleMainActivity)))
        }
    }

    /** 状态行：左标题、右值；两侧都给权重，长值会在自己那一半里换行而不是把标题挤没。 */
    @Composable
    private fun StatusLine(title: String, value: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MiuixTheme.textStyles.main, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(
                value,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                modifier = Modifier.weight(1.4f),
            )
        }
    }

    private fun rootAction(block: () -> String) {
        runBg(
            work = block,
            then = { message ->
                toast(message)
                refresh()
            },
        )
    }

    // ---- 弹窗渲染 ----

    @Composable
    private fun Dialogs() {
        textDialog.value?.let { dialog ->
            OverlayDialog(
                title = dialog.title,
                show = true,
                onDismissRequest = { textDialog.value = null },
            ) {
                // 不再套一层 Card：弹窗本身就是一张卡片，再套一层会多出一圈 16dp 内边距，
                // 正文相对标题看起来是「缩进」的。正文直接交给弹窗自己的 insideMargin 更整齐。
                Text(
                    dialog.content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                )
                DialogActions(dialog) { textDialog.value = null }
            }
        }
        editorDialog.value?.let { editor -> TaskEditorDialog(editor) }
        editorDialog.value?.let { editor ->
            val label = editor.multiOpenLabel ?: return@let
            MultiSelectOverlay(editor, label)
        }
    }

    @Composable
    private fun DialogActions(dialog: TextDialogUi, dismiss: () -> Unit) {
        if (dialog.actions.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = "关闭", onClick = dismiss)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                TextButton(text = "取消", onClick = dismiss)
                Spacer(Modifier.weight(1f))
                dialog.actions.forEach { (label, action) ->
                    TextButton(text = label, onClick = action)
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }

    @Composable
    private fun TaskEditorDialog(editor: TaskEditor) {
        // 聚焦输入框在窗口坐标里的底边（EditorField 聚焦时上报），驱动 editorImeLift 做最小位移。
        val focusedFieldBottom = remember { mutableStateOf(0f) }
        OverlayDialog(
            title = editor.spec?.title ?: taskTitle(editor.task.taskType),
            show = true,
            onDismissRequest = { editorDialog.value = null },
            // miuix 自带的 imePadding 会把底部对齐的弹窗整个顶到键盘上沿（跳一整个键盘的高度）。
            // 关掉它，换成 editorImeLift 的「最小位移」：只抬到刚好露出聚焦的输入框。
            defaultWindowInsetsPadding = false,
            modifier = Modifier.editorImeLift(fieldBottom = { focusedFieldBottom.value }),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val spec = editor.spec
                if (spec?.rewardTriggered != true && spec?.merchant != true) {
                    EditorField(
                        label = "执行时间",
                        hint = "HH:mm",
                        value = editor.values[FIELD_TIME].orEmpty(),
                        onValue = { editor.values[FIELD_TIME] = it },
                        onFocusBottom = { focusedFieldBottom.value = it },
                    )
                }
                if (spec?.autoRead == true) {
                    EditorField(
                        label = "阅读时长",
                        hint = "分钟",
                        value = editor.values[FIELD_DURATION].orEmpty(),
                        onValue = { editor.values[FIELD_DURATION] = it },
                        onFocusBottom = { focusedFieldBottom.value = it },
                    )
                    EditorField(
                        label = "图书",
                        hint = "每行 bookId|书名，留空=最近阅读",
                        value = editor.values[FIELD_BOOKS].orEmpty(),
                        onValue = { editor.values[FIELD_BOOKS] = it },
                        maxLines = 6,
                        onFocusBottom = { focusedFieldBottom.value = it },
                    )
                }
                if (spec?.rewardTriggered == true) {
                    EditorField(
                        label = "每日祈愿上限",
                        hint = "0 表示抽完彩筹",
                        value = editor.values[FIELD_DRAW_LIMIT].orEmpty(),
                        onValue = { editor.values[FIELD_DRAW_LIMIT] = it },
                        onFocusBottom = { focusedFieldBottom.value = it },
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
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                // 按钮对齐主题设置弹窗那套：取消浅灰胶囊、保存主色胶囊，等宽并排。
                Button(
                    onClick = { editorDialog.value = null },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text("取消")
                }
                Spacer(Modifier.width(16.dp))
                Button(
                    onClick = { saveTaskEdits(editor) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("保存")
                }
            }
        }
    }

    /**
     * 带悬浮标签的输入框。
     *
     * 字段名（如「执行时间」）留在框外当标题；原来的小字提示（如「HH:mm」）搬进
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
        onFocusBottom: (Float) -> Unit = {},
    ) {
        Text(label, style = MiuixTheme.textStyles.main)
        Spacer(Modifier.height(6.dp))
        // 框内正文位置 = 输入框的上下内边距：悬浮标签未上浮时与输入文字同一排，上浮后收到框顶。
        val textTop = 16.dp
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        // 上浮条件取「聚焦 || 有内容」：只看有内容的话，点进空框后要等敲下第一个字标签才弹
        // 上去，很跳；只看聚焦的话，填好的字段一失焦标签就掉回来压住正文。
        val floated = focused || value.isNotEmpty()
        val labelTop by animateDpAsState(if (floated) 4.dp else textTop)
        val labelSize by animateDpAsState(if (floated) 10.dp else 17.dp)
        // 标签取 onSecondaryContainerVariant：这一档正是「secondaryContainer 底上的文字」，
        // 与输入框自身 #F0F0F0 的底色配套（深色下自动变 #4F4F4F 底 + 亮灰字），不写死颜色。
        val labelColor = MiuixTheme.colorScheme.onSecondaryContainerVariant
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    // 聚焦期间持续上报底边（窗口坐标）。弹窗被 editorImeLift 抬起时坐标跟着
                    // 变小，抬升量在 lift 侧补偿（还原「原始底边」），不会来回振荡。
                    if (focused) onFocusBottom(coords.boundsInWindow().bottom)
                },
        ) {
            TextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier.fillMaxWidth(),
                insideMargin = DpSize(16.dp, textTop),
                maxLines = maxLines,
                interactionSource = interaction,
            )
            // 标签不吃点击：它只是一层画在输入框上的文字，点到哪儿都是下面那层接管焦点。
            if (hint.isNotBlank()) {
                Text(
                    hint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = labelTop),
                    fontSize = labelSize.value.sp,
                    fontWeight = FontWeight.Medium,
                    color = labelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    /**
     * 编辑弹窗的键盘「最小位移」：只把弹窗抬到刚好露出聚焦的输入框，而不是像 miuix 自带的
     * imePadding 那样抬一整个键盘的高度——底部对齐的弹窗会被整个顶到屏幕顶端。
     *
     * [fieldBottom] 是聚焦输入框当前在窗口坐标里的底边（EditorField 聚焦期间持续上报）。
     * 键盘收起时回落到导航栏 inset；抬起量走弹簧动画，键盘弹出时是滑上去而不是瞬移。
     */
    @Composable
    private fun Modifier.editorImeLift(fieldBottom: () -> Float): Modifier {
        val density = LocalDensity.current
        val imeBottom = WindowInsets.ime.getBottom(density)
        val navBottom = WindowInsets.navigationBars.getBottom(density)
        var applied by remember { mutableIntStateOf(0) }
        val target = if (imeBottom == 0 || fieldBottom() <= 0f) {
            0
        } else {
            val imeTop = LocalConfiguration.current.screenHeightDp * density.density - imeBottom
            // fieldBottom 是弹窗抬升后的坐标；加回当前抬升量还原「原始底边」，否则抬起后
            // 坐标变小 → 判定不用抬 → 落下 → 又要抬，来回振荡。
            val originalBottom = fieldBottom() + applied
            (originalBottom + with(density) { 16.dp.toPx() } - imeTop).toInt().coerceIn(0, imeBottom)
        }
        val lifted by animateIntAsState(target, spring(dampingRatio = 1f, stiffness = 300f))
        SideEffect { applied = lifted }
        return Modifier.padding(bottom = with(density) { maxOf(lifted, navBottom).toDp() })
    }

    /** 「禁当期物」入口：一行摘要按钮，点开多选弹窗。 */
    @Composable
    private fun PawnMultiSelectEntry(editor: TaskEditor) {
        val options = editor.multiOptions[FIELD_PAWN].orEmpty()
        val picked = CloudTaskLocalRunner.parseForbiddenPawnPropIds(editor.values[FIELD_PAWN].orEmpty())
        Text("禁当期物", style = MiuixTheme.textStyles.main)
        Spacer(Modifier.height(2.dp))
        Text(
            "点一下锁定期物禁止典当；清单打开时从阅微现拉（当日期物 + 背包）",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(6.dp))
        TextButton(
            text = multiSelectSummary(options, picked),
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
        val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
        var expanded by remember { mutableStateOf(false) }
        val currentLabel = choices.firstOrNull { it.value == value }?.label
            ?: value.ifBlank { emptyLabel }
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MiuixTheme.textStyles.main)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        summary,
                        style = MiuixTheme.textStyles.footnote1,
                        color = muted,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    currentLabel,
                    style = MiuixTheme.textStyles.main,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                DropdownArrowEndAction(actionColor = muted)
            }
            OverlayListPopup(
                show = expanded,
                alignment = PopupPositionProvider.Align.End,
                // 弹层自带压暗层：弹窗底和弹层底都是白，不压一层的话边界分不清。
                enableWindowDim = true,
                onDismissRequest = { expanded = false },
            ) {
                ListPopupColumn {
                    choices.forEachIndexed { index, choice ->
                        DropdownImpl(
                            text = choice.label,
                            optionSize = choices.size,
                            isSelected = choice.value == value,
                            index = index,
                            onSelectedIndexChange = {
                                onSelect(choices[it].value)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    /**
     * 城池对车马的通行要求：`requiredTransportType` 为空表示任何车马都能去（GUSU/LANGYA），
     * 非空时必须与车马的 `transportType` 一致（楼兰=HORSE，江陵/蓬莱=SHIP——只有船去蓬莱）。
     * 规则照宿主行商准备页，字段实际取值已探针取证。
     */
    private fun cityAccepts(
        city: CloudTaskLocalRunner.MerchantCityOption,
        transport: CloudTaskLocalRunner.MerchantTransportOption?,
    ): Boolean = city.requiredTransportType.isBlank() || transport?.transportType == city.requiredTransportType

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
        if (minutes < 60L) return "$minutes 分钟"
        val hours = String.format(Locale.US, "%.1f", minutes / 60.0).removeSuffix(".0")
        return "$hours 小时"
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
        val selectedTransport = editor.merchantTransports.firstOrNull {
            it.id == editor.values[FIELD_TRANSPORT].orEmpty()
        }
        val compatible = editor.merchantCities.filter { cityAccepts(it, selectedTransport) }
        val selectedCity = compatible.firstOrNull { it.code == editor.values[FIELD_CITY].orEmpty() }
        ChoiceDropdownField(
            title = "城池",
            summary = when {
                loading -> "正在读取可选城池…"
                selectedCity != null -> "预计 ${formatMerchantDuration(selectedCity, selectedTransport)}"
                selectedTransport != null -> "共 ${compatible.size} 座适配当前车马"
                else -> "共 ${compatible.size} 座"
            },
            value = editor.values[FIELD_CITY].orEmpty(),
            emptyLabel = "未选择",
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
        val selected = compatible.firstOrNull { it.id == editor.values[FIELD_TRANSPORT].orEmpty() }
        ChoiceDropdownField(
            title = "车马",
            summary = when {
                loading -> "正在读取可用车马…"
                selected != null -> "速度 +${selected.speedPercent}% · 负重 ${selected.carryingCapacity}"
                selectedCity != null -> "当前城池没有适配的已拥有车马"
                else -> "共 ${compatible.size} 项已拥有"
            },
            value = editor.values[FIELD_TRANSPORT].orEmpty(),
            emptyLabel = "未选择",
            choices = compatible.map { DropdownChoice(it.id, it.label) },
            onSelect = { id ->
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
     * 行商「本金」滑动条：上限就是当前车马的负重，拖动按百分比换算金额。
     *
     * 滑到最右（100%）金额恒等于负重本身（不丢取整误差）；0 表示不设本金、
     * 沿用上次。没选车马时没有负重上限，滑动条禁用。金额存进
     * [FIELD_PRINCIPAL]，与旧输入框同一字段，执行侧无需感知界面形态。
     */
    @Composable
    private fun PrincipalSlider(editor: TaskEditor) {
        val capacity = editor.merchantTransports
            .firstOrNull { it.id == editor.values[FIELD_TRANSPORT].orEmpty() }
            ?.carryingCapacity ?: 0L
        val saved = editor.values[FIELD_PRINCIPAL]?.trim()?.toLongOrNull() ?: 0L
        // 金额以本地 state 为准，拖动时同步写回 values；容量变化（换车马或清单
        // 异步到达）时用已存值重新对位，超出新车马负重的部分截掉。
        var amount by remember(capacity) { mutableLongStateOf(saved.coerceAtMost(capacity)) }
        val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("本金", style = MiuixTheme.textStyles.main)
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        capacity <= 0L -> "先选车马"
                        amount <= 0L -> "沿用上次"
                        else -> amount.toString()
                    },
                    style = MiuixTheme.textStyles.main,
                    color = muted,
                )
            }
            Slider(
                value = if (capacity > 0L) (amount.toFloat() / capacity).coerceIn(0f, 1f) else 0f,
                onValueChange = { fraction ->
                    if (capacity <= 0L) return@Slider
                    // 满格恒等于负重本身；其余按拖动百分比四舍五入。
                    val next = if (fraction >= 1f) capacity else (fraction * capacity).roundToLong()
                    amount = next
                    editor.values[FIELD_PRINCIPAL] = next.toString()
                },
                valueRange = 0f..1f,
                enabled = capacity > 0L,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (capacity > 0L) "上限 $capacity（当前车马负重），0 = 沿用上次"
                else "选择车马后按负重设上限",
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
            title = "运签",
            summary = options.joinToString("/") { CloudTaskLocalRunner.blessingLabel(it) },
            value = editor.values[FIELD_BLESSING].orEmpty(),
            emptyLabel = CloudTaskLocalRunner.blessingLabel(""),
            choices = options.map { DropdownChoice(it, CloudTaskLocalRunner.blessingLabel(it)) },
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
                        toast(result.exceptionOrNull()?.message ?: "读取清单失败")
                    } else if (updated != null) {
                        editor.multiOptions[label] = updated
                    }
                },
            )
        }
        OverlayDialog(
            title = label,
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
                    "勾选 = 禁止典当；每次打开自动读取最新清单，红色品质一律不自动典当。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                if (options.isEmpty() && refreshing) {
                    Text(
                        "正在读取期物清单…",
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
                                option.label,
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
                    Text("取消")
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
                    Text("完成")
                }
            }
        }
    }

    // ---- 通用 ----

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
        } else {
            toast("当前系统无需申请通知权限")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_POST_NOTIFICATIONS) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        toast(if (granted) "通知权限已授予" else "未授予通知权限，任务结果只能在打开阅微时以提示条显示")
        refresh()
    }

    private fun openSystemSettings(intent: Intent?) {
        if (intent == null) {
            toast("当前系统无需该项设置")
            return
        }
        openFirstAvailable(intent)
    }

    private fun openAutoStartSettings() {
        val candidates = listOf(
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ),
            Intent().setComponent(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ),
            Intent().setComponent(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ),
            CloudTaskWakeDiagnostics.moduleDetailsIntent(),
        )
        openFirstAvailable(*candidates.toTypedArray())
    }

    private fun openFirstAvailable(vararg intents: Intent) {
        val opened = intents.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            .any { runCatching { startActivity(it); true }.getOrDefault(false) }
        if (!opened) toast("无法打开系统设置，请手动到系统设置里授权")
    }

    private fun rescheduleAlarm() {
        runBg(
            work = {
                CloudTaskWakeScheduler.schedule(applicationContext)
                "已重排闹钟：下次唤醒 ${formatTime(NextWakeHint.read(applicationContext))}"
            },
            then = { message ->
                toast(message)
                refresh()
            },
        )
    }

    private fun hideRecentTaskEnabled(): Boolean = uiPrefBoolean(KEY_HIDE_RECENT_TASK)

    // ---- 模块界面自己的偏好 ----

    /** 界面偏好（隐藏后台卡片 / 主题设置）读：都落在同一份 prefs 里。 */
    private fun uiPrefBoolean(key: String, defValue: Boolean = false): Boolean =
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).getBoolean(key, defValue)

    /** 界面偏好写：同步落盘（commit），避免用户刚切完开关就被系统回收进程而丢设置。 */
    private fun writeUiPref(key: String, value: Boolean) {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putBoolean(key, value).commit()
    }

    /**
     * 构建时间文案。
     *
     * 取 BuildConfig.BUILD_TIME（`build.gradle.kts` 按构建时刻写进去的毫秒时间戳），不是
     * APK 的安装/更新时间——那两者会被「重新安装」带偏，看不出这一版是什么时候编出来的。
     */
    private fun buildTimeText(): String =
        runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(BuildConfig.BUILD_TIME))
        }.getOrDefault("未知")

    /** 打开外部链接（GitHub 仓库）。没有可用浏览器时给一句人话，别把异常抛到界面上。 */
    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { toast("没有可以打开这个链接的应用") }
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
        if (!task.enabled) return "未启用"
        if (spec?.rewardTriggered == true) return "每日轶闻完成后自动祈愿"
        if (spec?.merchant == true) {
            val poll = if (task.nextRunAt > 0L) "下次检查 ${formatDateTime(task.nextRunAt)}" else "下次检查 待排程"
            val tripEnd = lastMerchantTripEnd(accountId, task.taskType)
            return if (tripEnd != null) "$poll\n行商预计 $tripEnd 完成" else poll
        }
        return if (task.nextRunAt > 0L) "下次 ${formatDateTime(task.nextRunAt)}" else "下次 待排程"
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
        "success" -> "成功"
        "failed" -> "失败"
        "paused" -> "已暂停"
        "skipped" -> "已跳过"
        "" -> "未知"
        else -> result
    }

    private fun taskTitle(taskType: String): String =
        CLOUD_AUTOMATION_TASKS.firstOrNull { it.taskType == taskType }?.title ?: when (taskType) {
            "yeshe_checkin" -> "每日轶闻"
            "yeshe_draw_card" -> "自动祈愿"
            "cloud_auto_read" -> "自动阅读"
            "traveling_merchant" -> "自动行商"
            "pawn" -> "期物典当"
            else -> taskType
        }

    private fun specOf(taskType: String): CloudAutomationTaskSpec? =
        CLOUD_AUTOMATION_TASKS.firstOrNull { it.taskType == taskType }

    private fun versionLine(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }.getOrDefault("2.3.6")

    private fun formatTime(at: Long): String =
        if (at <= 0L) "未排程" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

    private fun formatDateTime(at: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(at))

    /** 记录列表的日分组标题：今天 / 昨天 / MM-dd（跨年才补年份）。 */
    private fun dayLabel(at: Long): String {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = at }
        if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR)) {
            when (now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR)) {
                0 -> return "今天"
                1 -> return "昨天"
            }
            return SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(at))
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(at))
    }

    /** 已经按天分组之后，行内只需要时刻。 */
    private fun formatClock(at: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))

    // ---- 展示用快照结构 ----

    /** 任务卡片行：accountId + 任务 + 规格 + 预生成的正文。 */
    private data class TaskRow(
        val accountId: String,
        val task: LocalTask,
        val spec: CloudAutomationTaskSpec?,
        val subtitle: String,
    )

    /** 后台唤醒自检结果的展示投影。 */
    private data class WakeUi(
        val healthy: Boolean,
        val summary: String,
        val details: String,
        val notificationAllowed: Boolean,
        val exactAlarmAllowed: Boolean,
        val batteryUnrestricted: Boolean,
    )

    /** Root 探测结果的展示投影（后台线程完成后落到 rootUi）。 */
    private data class RootUi(val rootAvailable: Boolean, val title: String, val message: String)

    /** 纯文本弹窗：详情/说明/记录/日志共用，actions 非空时额外带「取消」。 */
    private data class TextDialogUi(
        val title: String,
        val content: String,
        val actions: List<Pair<String, () -> Unit>> = emptyList(),
    )

    /** 任务编辑器的存活状态：values 按标签存当前值，multiOptions / 行商选项可被刷新替换。 */
    private class TaskEditor(
        val accountId: String,
        val task: LocalTask,
        val spec: CloudAutomationTaskSpec?,
    ) {
        val values = mutableStateMapOf<String, String>()
        val multiOptions = mutableStateMapOf<String, List<MultiSelectOption>>()

        /** 行商城池/车马全量选项，打开编辑器时后台从阅微拉；城池↔车马的互斥在 UI 侧现筛。 */
        var merchantCities: List<CloudTaskLocalRunner.MerchantCityOption> by mutableStateOf(emptyList())
        var merchantTransports: List<CloudTaskLocalRunner.MerchantTransportOption> by mutableStateOf(emptyList())

        /** 行商可选项是否还在拉取中。 */
        var merchantLoading: Boolean by mutableStateOf(false)

        /** 非空时在该编辑器之上再弹多选弹窗。 */
        var multiOpenLabel: String? by mutableStateOf(null)
    }

    /** 下拉选择器的一个选项：value 是落库值，label 是展示文案。 */
    private data class DropdownChoice(val value: String, val label: String)

    private companion object {
        const val LOG_TAG = "ReaMicroMain"
        const val REQUEST_POST_NOTIFICATIONS = 4501

        /** 模块界面自己的偏好文件与键名（隐藏后台卡片、主题设置）。 */
        const val UI_PREFS = "reamicro_module_ui"
        const val KEY_HIDE_RECENT_TASK = "hideRecentTask"
        const val KEY_BLUR_BARS = "themeBlurBars"
        const val KEY_FLOATING_NAV_BAR = "themeFloatingNavBar"
        const val KEY_LIQUID_GLASS = "themeLiquidGlass"

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
        val TAB_TITLES = listOf("记录", "任务", "设置", "关于")
        val TAB_ICONS = listOf(MiuixIcons.Recent, MiuixIcons.Tasks, MiuixIcons.Settings, MiuixIcons.Info)

        /** 窗口底色：首帧之前系统栏区域显示的颜色，跟着深浅色走（透明会让部分 ROM 露黑边）。 */
        val LIGHT_WINDOW_BG = android.graphics.Color.WHITE
        val DARK_WINDOW_BG = android.graphics.Color.BLACK

        // 编辑弹窗字段标签：渲染与 applyTaskEdits 必须用同一套（标签即取值键）。
        const val FIELD_TIME = "执行时间"
        const val FIELD_DURATION = "阅读时长"
        const val FIELD_BOOKS = "图书"
        const val FIELD_DRAW_LIMIT = "每日祈愿上限"
        const val FIELD_PAWN = "禁当期物"
        const val FIELD_CITY = "城池 cityCode"
        const val FIELD_PRINCIPAL = "本金"
        const val FIELD_TRANSPORT = "车马 transportId"
        const val FIELD_BLESSING = "运签"
    }
}
