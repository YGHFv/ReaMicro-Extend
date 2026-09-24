package com.reamicro.fix.ui

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
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

/** 危险操作（清空、失败态标题）的提示色：新旧两套 UI 保持同一语义。 */
private val DangerRed = Color(0xFFD03A2B)

/**
 * 模块主界面（miuix 版）：底栏三个页签（记录 / 配置 / 关于）。
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

    /** 顶栏用的图标按钮：无底色，只有一枚图标（KSU 顶栏的刷新/排序位即此）。 */
    @Composable
    private fun ToolbarIconButton(
        icon: ImageVector,
        contentDescription: String,
        onClick: () -> Unit,
    ) {
        IconButton(
            onClick = onClick,
            minHeight = 38.dp,
            minWidth = 38.dp,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(22.dp),
                tint = MiuixTheme.colorScheme.onSurface,
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
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = TAB_TITLES[tab.intValue],
                        largeTitle = TAB_TITLES[tab.intValue],
                        scrollBehavior = scrollBehavior,
                        // 只有**任务页**右上角带按钮（位置照 KSU 模块页右上角的重启按钮）。
                        // 它做的事就是被移除的那个「重算下次时刻」：按配置的时间点重算下次执行
                        // 时刻、不执行任务，专治历史遗留的「上次跑完 + 24 小时」。
                        // 其余三页没有需要原地重算的东西，不放按钮。
                        actions = {
                            if (tab.intValue == TAB_TASKS) {
                                ToolbarIconButton(
                                    icon = MiuixIcons.Refresh,
                                    contentDescription = "重算下次时刻",
                                    onClick = { recomputeSchedule() },
                                )
                            }
                        },
                    )
                },
                bottomBar = {
                    NavigationBar {
                        TAB_ICONS.forEachIndexed { index, icon ->
                            NavigationBarItem(
                                selected = tab.intValue == index,
                                onClick = { tab.intValue = index },
                                icon = icon,
                                label = TAB_TITLES[index],
                            )
                        }
                    }
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // 越界回弹必须在绑定滚动行为之前加
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
                        .verticalScroll(rememberScrollState())
                        // 卡片的水平边距交给卡片自己（KSU 的 12dp），页面只留纵向节奏。
                        .padding(vertical = 4.dp),
                ) {
                    when (tab.intValue) {
                        TAB_TASKS -> TasksPage()
                        TAB_CONFIG -> ConfigPage()
                        TAB_ABOUT -> AboutPage()
                        else -> RecordsPage()
                    }
                    Spacer(Modifier.height(4.dp))
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
        // 「执行」上，「重算下次时刻」移到顶栏右上角那个刷新按钮。
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

    // ---- 配置页 ----

    @Composable
    private fun ConfigPage() {
        // Root 增强原来挂在「关于」页，按需求搬到配置页：它本质是后台唤醒的一种实现方式，
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
     */
    @Composable
    private fun TaskCard(row: TaskRow) {
        SectionCard(
            title = row.spec?.title ?: taskTitle(row.task.taskType),
            titleColor = if (row.task.enabled) MiuixTheme.colorScheme.onSurface
            else MiuixTheme.colorScheme.onSurfaceVariantActions,
            subtitle = row.subtitle,
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

    /** Root 增强（实验性）：状态 + 说明 + 启用/停用。原先在「关于」页，现归配置页。 */
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

        // 开关语义对用户友好：checked = 显示后台卡片（ prefs 里存的是"隐藏"）。
        // 这一行直接用 miuix 自带的 SwitchPreference，外层 Card 只负责给它和别的卡片
        // 一样的 12dp 外边距——不必为了统一而把它拆成手搓的行。
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            SwitchPreference(
                title = "后台卡片",
                summary = if (hideRecentTask.value) {
                    "已开启：返回桌面后隐藏最近任务卡片，不停止自动任务，也不等于后台保活。"
                } else {
                    "可在返回桌面时隐藏模块的最近任务卡片，不影响自动任务执行。"
                },
                checked = !hideRecentTask.value,
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
     */
    @Composable
    private fun PermissionActions() {
        val wake = wakeUi.value
        val entries = buildList {
            if (wake != null && !wake.notificationAllowed) {
                add("开启通知" to { requestNotificationPermission() })
            }
            if (wake != null && !wake.exactAlarmAllowed) {
                add("精确闹钟" to { openSystemSettings(CloudTaskWakeDiagnostics.exactAlarmSettingsIntent()) })
            }
            if (wake != null && !wake.batteryUnrestricted) {
                add("电池优化" to { openSystemSettings(CloudTaskWakeDiagnostics.batteryOptimizationIntent()) })
            }
            add("厂商自启动" to { openAutoStartSettings() })
            add("重排闹钟" to { rescheduleAlarm() })
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CapsuleTextButton(pair[0].first, pair[0].second)
                    if (pair.size > 1) {
                        Spacer(Modifier.weight(1f))
                        CapsuleTextButton(pair[1].first, pair[1].second)
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
    private fun recomputeSchedule() {
        runBg(
            work = {
                val updated = LocalTaskStore { applicationContext }.rescheduleEnabledTasks()
                runCatching { CloudTaskWakeScheduler.schedule(applicationContext) }
                if (updated > 0) "已按配置时间重算 $updated 个任务" else "任务时刻已经和配置一致"
            },
            then = { message ->
                toast(message)
                refresh()
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
        getSharedPreferences("reamicro_module_ui", MODE_PRIVATE).edit().putBoolean("hideRecentTask", enabled).commit()
        setTaskExcludedFromRecents(false)
        hideRecentTask.value = enabled
        toast(if (enabled) "返回桌面后自动隐藏模块后台卡片" else "模块后台卡片恢复显示")
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
            put(FIELD_PAWN, task.forbiddenPawnPropIds.sorted().joinToString(","))
            editor.multiOptions[FIELD_PAWN] = pawnOptionsFromState(accountId)
        }
        if (spec?.merchant == true) {
            put(FIELD_CITY, task.merchantCityCode)
            put(FIELD_PRINCIPAL, task.merchantPrincipal.takeIf { it > 0L }?.toString().orEmpty())
            put(FIELD_TRANSPORT, task.merchantTransportId.takeIf { it > 0L }?.toString().orEmpty())
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

    /** 点「刷新期物清单」时现拉一次：期物的名字与品质只在服务端。 */
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
        val state = JSONObject().put(CloudTaskLocalRunner.KEY_PAWN_PROP_CATALOG, fetched.catalog)
        pawnStore.recordState(accountId, "pawn", state)
        return CloudTaskLocalRunner.pawnPropChoices(state).map {
            MultiSelectOption(it.propId, it.label, cloudTaskQualityColor(it.quality))
        }
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

    @Composable
    private fun AboutPage() {
        SectionCard(
            title = "阅微补全计划",
            subtitle = "模块 ${versionLine()}",
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
        OverlayDialog(
            title = editor.spec?.title ?: taskTitle(editor.task.taskType),
            show = true,
            onDismissRequest = { editorDialog.value = null },
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
                    )
                }
                if (spec?.autoRead == true) {
                    EditorField(
                        label = "阅读时长",
                        hint = "分钟",
                        value = editor.values[FIELD_DURATION].orEmpty(),
                        onValue = { editor.values[FIELD_DURATION] = it },
                    )
                    EditorField(
                        label = "图书",
                        hint = "每行 bookId|书名，留空=最近阅读",
                        value = editor.values[FIELD_BOOKS].orEmpty(),
                        onValue = { editor.values[FIELD_BOOKS] = it },
                        maxLines = 6,
                    )
                }
                if (spec?.rewardTriggered == true) {
                    EditorField(
                        label = "每日祈愿上限",
                        hint = "0 表示抽完彩筹",
                        value = editor.values[FIELD_DRAW_LIMIT].orEmpty(),
                        onValue = { editor.values[FIELD_DRAW_LIMIT] = it },
                    )
                }
                if (spec?.taskType == "pawn") {
                    PawnMultiSelectEntry(editor)
                }
                if (spec?.merchant == true) {
                    EditorField(
                        label = "城池 cityCode",
                        hint = "留空沿用上次",
                        value = editor.values[FIELD_CITY].orEmpty(),
                        onValue = { editor.values[FIELD_CITY] = it },
                    )
                    EditorField(
                        label = "本金",
                        hint = "留空沿用上次",
                        value = editor.values[FIELD_PRINCIPAL].orEmpty(),
                        onValue = { editor.values[FIELD_PRINCIPAL] = it },
                    )
                    EditorField(
                        label = "车马 transportId",
                        hint = "留空沿用上次",
                        value = editor.values[FIELD_TRANSPORT].orEmpty(),
                        onValue = { editor.values[FIELD_TRANSPORT] = it },
                    )
                }
                if (spec != null && spec.blessingOptions.isNotEmpty()) {
                    // 运签用选择器而不是输入框：用户面对的应该只有「求安签/求财签」，
                    // 不该让他知道也不该让他手打 SAFETY 这种 wire 值。
                    BlessingChooser(editor, spec)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                TextButton(text = "取消", onClick = { editorDialog.value = null }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                TextButton(text = "保存", onClick = { saveTaskEdits(editor) }, modifier = Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun EditorField(label: String, hint: String, value: String, onValue: (String) -> Unit, maxLines: Int = 1) {
        Text(label, style = MiuixTheme.textStyles.main)
        if (hint.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                hint,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(6.dp))
        TextField(
            value = value,
            onValueChange = onValue,
            maxLines = maxLines,
        )
        Spacer(Modifier.height(12.dp))
    }

    /** 「禁当期物」入口：一行摘要按钮，点开多选弹窗。 */
    @Composable
    private fun PawnMultiSelectEntry(editor: TaskEditor) {
        val options = editor.multiOptions[FIELD_PAWN].orEmpty()
        val picked = CloudTaskLocalRunner.parseForbiddenPawnPropIds(editor.values[FIELD_PAWN].orEmpty())
        Text("禁当期物", style = MiuixTheme.textStyles.main)
        Spacer(Modifier.height(2.dp))
        Text(
            "点一下锁定期物禁止典当；清单来自当日期物与背包里见过的期物",
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

    @Composable
    private fun BlessingChooser(editor: TaskEditor, spec: CloudAutomationTaskSpec) {
        val options = spec.blessingOptions
        Text("运签", style = MiuixTheme.textStyles.main)
        Spacer(Modifier.height(2.dp))
        Text(
            options.joinToString("/") { CloudTaskLocalRunner.blessingLabel(it) },
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(6.dp))
        val selected = editor.values[FIELD_BLESSING].orEmpty()
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editor.values[FIELD_BLESSING] = option }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = { editor.values[FIELD_BLESSING] = option })
                Spacer(Modifier.width(8.dp))
                Text(CloudTaskLocalRunner.blessingLabel(option), style = MiuixTheme.textStyles.main)
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    /**
     * 多选弹窗：一行一个选项，点一下就切换锁定状态。
     *
     * 「禁当期物」这种配置天然是一组开关——让用户手打 propId 既记不住也看不见。
     * 清单可以被「刷新期物清单」换掉（名字与品质只在服务端）；刻意不清掉
     * "刷新后不在清单里"的锁定项：期物可能只是今天已经典当光，用户锁它的意思还在。
     */
    @Composable
    private fun MultiSelectOverlay(editor: TaskEditor, label: String) {
        val options = editor.multiOptions[label].orEmpty()
        val picked = remember(label) {
            mutableStateListOf<String>().apply {
                addAll(CloudTaskLocalRunner.parseForbiddenPawnPropIds(editor.values[label].orEmpty()))
            }
        }
        var refreshing by mutableStateOf(false)
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
                    "勾选 = 禁止典当；清单来自当日期物与背包里见过的期物。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                if (options.isEmpty()) {
                    Text(
                        "暂无可选期物，任务跑过一轮或点下面刷新后再来",
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
            TextButton(
                text = if (refreshing) "正在读取…" else "刷新期物清单",
                onClick = {
                    if (!refreshing) {
                        refreshing = true
                        runBg(
                            work = { runCatching { fetchPawnOptions(editor.accountId) } },
                            then = { result ->
                                refreshing = false
                                val updated = result.getOrNull()
                                if (result.isFailure) {
                                    toast(result.exceptionOrNull()?.message ?: "刷新清单失败")
                                } else if (updated == null) {
                                    toast("没有读到清单，稍后再试")
                                } else {
                                    editor.multiOptions[label] = updated
                                    toast("清单已更新，共 ${updated.size} 项")
                                }
                            },
                        )
                    }
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                TextButton(text = "取消", onClick = { editor.multiOpenLabel = null }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = "完成",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // 落盘还是同一份 ID 集合，沿用「按标签取字符串」通道回传。
                        editor.values[label] = picked
                            .sortedWith(compareBy({ it.toLongOrNull() ?: Long.MAX_VALUE }, { it }))
                            .joinToString(",")
                        editor.multiOpenLabel = null
                    },
                )
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

    private fun hideRecentTaskEnabled(): Boolean =
        getSharedPreferences("reamicro_module_ui", MODE_PRIVATE).getBoolean("hideRecentTask", false)

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

    /** 任务编辑器的存活状态：values 按标签存当前值，multiOptions 可被刷新替换。 */
    private class TaskEditor(
        val accountId: String,
        val task: LocalTask,
        val spec: CloudAutomationTaskSpec?,
    ) {
        val values = mutableStateMapOf<String, String>()
        val multiOptions = mutableStateMapOf<String, List<MultiSelectOption>>()

        /** 非空时在该编辑器之上再弹多选弹窗。 */
        var multiOpenLabel: String? by mutableStateOf(null)
    }

    private companion object {
        const val LOG_TAG = "ReaMicroMain"
        const val REQUEST_POST_NOTIFICATIONS = 4501

        // 底栏四页签：记录（历史）/ 任务（每任务开关与参数）/ 配置（系统级设置）/ 关于。
        const val TAB_RECORDS = 0
        const val TAB_TASKS = 1
        const val TAB_CONFIG = 2
        const val TAB_ABOUT = 3
        val TAB_TITLES = listOf("记录", "任务", "配置", "关于")
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
