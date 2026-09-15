package com.reamicro.fix.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import com.reamicro.fix.cloud.api.CloudTaskWakeDiagnostics
import com.reamicro.fix.cloud.api.CloudTaskWakeScheduler
import com.reamicro.fix.cloud.api.NextWakeHint
import com.reamicro.fix.cloud.local.CloudTaskLocalRunner
import com.reamicro.fix.cloud.local.LocalTask
import com.reamicro.fix.cloud.local.LocalTaskBook
import com.reamicro.fix.cloud.local.LocalTaskRecord
import com.reamicro.fix.cloud.local.LocalTaskRunner
import com.reamicro.fix.cloud.local.LocalTaskStore
import com.reamicro.fix.hook.CLOUD_AUTOMATION_TASKS
import com.reamicro.fix.hook.CloudAutomationTaskSpec
import com.reamicro.fix.logging.ModuleAndroidLog
import com.reamicro.fix.logging.ModuleLogBuffer
import com.reamicro.fix.notification.CloudTaskNotifications
import com.reamicro.fix.notification.NotificationRecord
import com.reamicro.fix.notification.NotificationRecordStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块主界面：底栏三个页签（记录 / 配置 / 关于）。
 *
 * 为什么要有它：模块此前没有任何界面，任务跑没跑、通知发没发、下次什么时候跑，用户全都没处看。
 * 「记录」页把任务记录按通知的样式列出来、点进去看细节（运签、奖励、行商事件与收益…）；
 * 「配置」页放本地任务配置、权限管理与日志；「关于」页放版本与后台唤醒状态。
 *
 * UI 全部代码构建：模块只有极简资源，复用设置页那套视图辅助函数又必须绑定宿主的 Activity。
 */
class ModuleMainActivity : Activity() {

    private lateinit var ui: ModuleUiKit
    private var tab = TAB_RECORDS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModuleLogBuffer.attach(this)
        ui = ModuleUiKit(this)
        window?.setBackgroundDrawable(ColorDrawable(ui.palette.pageBackground))
        applyStatusBarIcons()
        ModuleAndroidLog.legacy(LOG_TAG, "module main ui opened")
        refresh()
    }

    /** 状态栏图标与本页底色相反（edge-to-edge 之后状态栏直接压在页面底色上）。 */
    private fun applyStatusBarIcons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            window.insetsController?.setSystemBarsAppearance(
                if (ui.isDarkPage) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
        }
    }

    private fun refresh() {
        setContentView(
            ui.scaffold(
                content = when (tab) {
                    TAB_CONFIG -> configPage()
                    TAB_ABOUT -> aboutPage()
                    else -> recordsPage()
                },
                tabs = TAB_TITLES,
                selectedIndex = tab,
                onSelect = { index ->
                    if (index != tab) {
                        tab = index
                        refresh()
                    }
                },
            ),
        )
    }

    // ---- 记录页 ----

    private fun recordsPage(): View {
        val store = LocalTaskStore { applicationContext }
        val records = store.accountIds()
            .flatMap { accountId -> store.records(accountId).map { accountId to it } }
            .sortedByDescending { (_, record) -> record.at }
        val failed = records.count { (_, record) -> record.result != "success" }
        val head = if (records.isEmpty()) {
            listOf(
                ui.pageTitle("记录", "任务跑过之后会在这里按时间列出"),
                ui.card(listOf(ui.row("还没有执行记录", "到「配置」页点「立即执行」可以先跑一轮；点任意一条记录能看详情（运签、奖励、行商事件）"))),
            )
        } else {
            listOf(
                ui.pageTitle("记录", "共 ${records.size} 条（失败 $failed 条），最新在前；点任意一条看详情"),
            )
        }
        return ui.page(head + records.map { (accountId, record) -> recordCard(accountId, record) })
    }

    /** 一条记录按通知的样式呈现：任务名 + 时间 / 正文，整块可点。 */
    private fun recordCard(accountId: String, record: LocalTaskRecord): View = ui.listItem(
        title = taskTitle(record.taskType) + if (record.result == "success") "" else "（${resultLabel(record.result)}）",
        body = record.message,
        meta = formatDateTime(record.at),
        accent = record.result == "success",
        onClick = { showRecordDetail(accountId, record) },
    )

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
        ui.contentDialog(
            title = "${taskTitle(record.taskType)} 详情",
            content = localTaskRecordDetailText(
                lines.joinToString("\n"),
                detail?.let { localTaskRecordDetailFields(record.taskType, it) }.orEmpty(),
            ),
            actions = listOf(),
            bodySizeSp = if (record.taskType == "yeshe_checkin") 14f else 12f,
        )
    }

    /** 「立即执行」强制跑一轮（忽略 nextRunAt），顺便把旧代码写下的过时时刻重算掉。 */
    private fun runLocalTasksNow() {
        ui.background(
            work = {
                val count = LocalTaskRunner.runDue(applicationContext, force = true)
                if (count > 0) "已执行 $count 个任务" else "没有已启用的本地任务"
            },
            then = { ui.toast(it); refresh() },
        )
    }

    /** 只按配置的时间点重算下次执行时刻，不执行任务——专治历史遗留的"上次跑完 + 24 小时"。 */
    private fun recomputeSchedule() {
        ui.background(
            work = {
                val updated = LocalTaskStore { applicationContext }.rescheduleEnabledTasks()
                runCatching { CloudTaskWakeScheduler.schedule(applicationContext) }
                if (updated > 0) "已按配置时间重算 $updated 个任务" else "任务时刻已经和配置一致"
            },
            then = { ui.toast(it); refresh() },
        )
    }

    // ---- 配置页 ----

    private fun configPage(): View {
        val store = LocalTaskStore { applicationContext }
        val accounts = store.accountIds()
        val tasks = accounts.flatMap { accountId -> store.list(accountId).map { accountId to it } }
        val enabled = tasks.count { (_, task) -> task.enabled }
        val nextTaskAt = futureNextRunAt(store)
        val children = mutableListOf<View>(
            ui.pageTitle("配置", "本地任务与模块权限都集中在这里"),
            ui.sectionTitle("任务"),
            ui.card(
                listOf(
                    ui.row(
                        "任务概览",
                        buildString {
                            append("${accounts.size} 个账号、$enabled 个任务已启用")
                            val wakeAt = NextWakeHint.read(this@ModuleMainActivity)
                            append("\n唤醒时刻：${wakeAt.takeIf { it > 0L }?.let(::formatTime) ?: "未排程"}")
                            append("（看门狗按这个时刻唤醒；系统闹钟另有 15 分钟兜底）")
                            // 唤醒可以**早于**最近的任务：零点例行唤醒、以及云端任务的完成时刻
                            // 也会排进来。不说明的话，用户会以为"唤起时刻没跟着任务刷新"。
                            if (nextTaskAt != null && wakeAt in 1 until nextTaskAt) {
                                append("（早于任务时刻属正常：零点例行唤醒 / 云任务完成时刻）")
                            }
                            append("\n下次任务时刻：${nextTaskAt?.let(::formatTime) ?: "无"}")
                            if (nextTaskAt != null) append("（任务自己排的时刻）")
                        },
                        actions = listOf(
                            "立即执行" to { runLocalTasksNow() },
                            "重算下次时刻" to { recomputeSchedule() },
                        ),
                    ),
                ),
            ),
        )
        if (tasks.isEmpty()) {
            children += ui.card(listOf(ui.row("还没有本地任务", "在阅微的设置页里启用任务后，这里就能改配置")))
        } else {
            tasks.forEach { (accountId, task) -> children += taskCard(accountId, task) }
        }
        children += ui.info("本地任务由模块进程执行；与阅微设置页同步时保留最新配置，旧镜像不会覆盖刚保存的选择。")
        children += ui.sectionTitle("权限与后台")
        children += wakeCard()
        children += ui.sectionTitle("通知与日志")
        children += notificationCard()
        children += logCard()
        return ui.page(children)
    }

    private fun taskCard(accountId: String, task: LocalTask): View {
        val spec = CLOUD_AUTOMATION_TASKS.firstOrNull { it.taskType == task.taskType }
        val subtitle = buildString {
            append(if (task.enabled) "已启用" else "已关闭")
            if (spec != null && spec.blessingOptions.isNotEmpty()) {
                append(" · ${CloudTaskLocalRunner.blessingLabel(task.blessingType)}")
            }
            append('\n')
            append(nextRunLine(accountId, task, spec))
            if (task.lastMessage.isNotBlank()) {
                append('\n')
                append(task.lastMessage)
            }
        }
        return ui.card(
            listOf(
                ui.row(
                    spec?.title ?: taskTitle(task.taskType),
                    subtitle,
                    actions = listOf(
                        "立即执行" to { runSingleTask(accountId, task) },
                        (if (task.enabled) "停用" else "启用") to { setTaskEnabled(accountId, task, !task.enabled) },
                        "改配置" to { openTaskEditor(accountId, task, spec) },
                    ),
                ),
            ),
        )
    }

    /**
     * 这一行「下次…」怎么写，三种任务语义完全不同：
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

    private fun runSingleTask(accountId: String, task: LocalTask) {
        ui.background(
            work = { LocalTaskRunner.runTaskNow(applicationContext, accountId, task.taskType) },
            then = { ui.toast(it); refresh() },
        )
    }

    private fun setTaskEnabled(accountId: String, task: LocalTask, enabled: Boolean) {
        val store = LocalTaskStore { applicationContext }
        store.setEnabled(accountId, task.taskType, enabled, store.token(accountId))
        // 开关变了要重排闹钟，否则新启用的任务不会被唤醒执行。
        runCatching { CloudTaskWakeScheduler.schedule(applicationContext) }
        ui.toast("${taskTitle(task.taskType)}已${if (enabled) "启用" else "停用"}")
        refresh()
    }

    /** 任务配置编辑：字段随任务类型变化（与阅微设置页同一套语义）。 */
    private fun openTaskEditor(accountId: String, task: LocalTask, spec: CloudAutomationTaskSpec?) {
        val fields = linkedMapOf<String, () -> String>()
        ui.editDialog(
            title = spec?.title ?: taskTitle(task.taskType),
            build = { add, choose ->
                if (spec?.rewardTriggered != true && spec?.merchant != true) add("执行时间", "HH:mm", task.timeOfDay)
                if (spec?.autoRead == true) {
                    add("阅读时长", "分钟", task.durationMinutes.toString())
                    add("图书", "每行 bookId|书名，留空=最近阅读", task.books.joinToString("\n") { "${it.bookId}|${it.name}" })
                }
                if (spec?.rewardTriggered == true) add("每日祈愿上限", "0 表示抽完彩筹", task.dailyDrawLimit.toString())
                if (spec?.merchant == true) {
                    add("城池 cityCode", "留空沿用上次", task.merchantCityCode)
                    add("本金", "留空沿用上次", task.merchantPrincipal.takeIf { it > 0L }?.toString().orEmpty())
                    add("车马 transportId", "留空沿用上次", task.merchantTransportId.takeIf { it > 0L }?.toString().orEmpty())
                }
                if (spec != null && spec.blessingOptions.isNotEmpty()) {
                    // 运签用选择器而不是输入框：用户面对的应该只有「求安签/求财签」，
                    // 不该让他知道也不该让他手打 SAFETY 这种 wire 值。
                    choose(
                        "运签",
                        spec.blessingOptions.map { it to CloudTaskLocalRunner.blessingLabel(it) },
                        spec.resolveBlessingChoice(task.blessingType),
                    )
                }
            },
            register = { label, provider -> fields[label] = provider },
            onSave = {
                val updated = applyTaskEdits(task, spec, fields)
                if (updated == null) {
                    ui.toast("填写有误，请检查时间与数字")
                    false
                } else {
                    val store = LocalTaskStore { applicationContext }
                    store.saveTask(accountId, updated, store.token(accountId))
                    runCatching { CloudTaskWakeScheduler.schedule(applicationContext) }
                    ui.toast("配置已保存")
                    refresh()
                    true
                }
            },
        )
    }

    /** 把对话框里填的值并回任务；时间或数字非法时返回 null。 */
    private fun applyTaskEdits(
        task: LocalTask,
        spec: CloudAutomationTaskSpec?,
        fields: Map<String, () -> String>,
    ): LocalTask? {
        val text = { label: String -> fields[label]?.invoke()?.trim().orEmpty() }
        val timeOfDay = if (spec?.rewardTriggered != true && spec?.merchant != true) {
            val parts = text("执行时间").split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull()
            val minute = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || hour == null || minute == null || hour !in 0..23 || minute !in 0..59) return null
            "%02d:%02d".format(hour, minute)
        } else task.timeOfDay
        val duration = if (spec?.autoRead == true) {
            text("阅读时长").toIntOrNull()?.takeIf { it in 1..720 } ?: return null
        } else task.durationMinutes
        val drawLimit = if (spec?.rewardTriggered == true) {
            text("每日祈愿上限").toIntOrNull()?.takeIf { it in 0..20 } ?: return null
        } else task.dailyDrawLimit
        val books = if (spec?.autoRead == true) {
            val lines = text("图书").lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            val parsed = ArrayList<LocalTaskBook>(lines.size)
            for (line in lines) {
                val id = line.substringBefore('|').trim().toLongOrNull() ?: return null
                if (id <= 0L) return null
                parsed += LocalTaskBook(id, line.substringAfter('|', "").trim())
            }
            parsed
        } else task.books
        val blessing = if (spec != null && spec.blessingOptions.isNotEmpty()) {
            spec.resolveBlessingChoice(text("运签"))
        } else task.blessingType
        return task.copy(
            timeOfDay = timeOfDay,
            durationMinutes = duration,
            dailyDrawLimit = drawLimit,
            books = books,
            blessingType = blessing,
            merchantCityCode = if (spec?.merchant == true) text("城池 cityCode") else task.merchantCityCode,
            merchantPrincipal = if (spec?.merchant == true) text("本金").toLongOrNull()?.coerceAtLeast(0L) ?: 0L else task.merchantPrincipal,
            merchantTransportId = if (spec?.merchant == true) text("车马 transportId").toLongOrNull()?.coerceAtLeast(0L) ?: 0L else task.merchantTransportId,
        )
    }

    // ---- 权限 / 通知 / 日志 ----

    private fun wakeCard(): View {
        val status = CloudTaskWakeDiagnostics.inspect(this)
        return ui.card(
            listOf(
                ui.row(
                    "后台唤醒",
                    status.summary(),
                    titleColor = if (status.healthy) ui.palette.title else ui.palette.destructiveText,
                ),
                ui.info(status.details().joinToString("\n")),
                ui.row(
                    "授权与跳转",
                    "通知权限要在这里授予；精确闹钟与电池优化放行后，通知才不会延迟数小时。",
                    actions = buildList<Pair<String, () -> Unit>> {
                        if (!status.notificationAllowed) add("开启通知" to { requestNotificationPermission() })
                        if (!status.exactAlarmAllowed) add("精确闹钟" to { openSystemSettings(CloudTaskWakeDiagnostics.exactAlarmSettingsIntent()) })
                        if (!status.batteryUnrestricted) add("电池优化" to { openSystemSettings(CloudTaskWakeDiagnostics.batteryOptimizationIntent()) })
                        add("厂商自启动" to { openAutoStartSettings() })
                        add("重排闹钟" to { rescheduleAlarm() })
                    },
                ),
            ),
        )
    }

    private fun notificationCard(): View {
        val store = NotificationRecordStore { applicationContext }
        val records = store.list()
        val delivered = records.count { it.delivered }
        val subtitle = if (records.isEmpty()) {
            "还没有通知记录。任务结果通知会在这里留下投递结果（含失败原因）。"
        } else {
            "共 ${records.size} 条：成功投出 $delivered 条，未投出 ${records.size - delivered} 条。\n" +
                "未投出的说明模块进程当时没能发出通知（被系统冻结或未启动），服务器会保留该消息下次重发。"
        }
        return ui.card(
            listOf(
                ui.row(
                    "通知记录",
                    subtitle,
                    actions = listOf(
                        "查看" to { showNotificationRecords(store) },
                        "清空" to { store.clear(); ui.toast("通知记录已清空"); refresh() },
                    ),
                ),
            ),
        )
    }

    private fun showNotificationRecords(store: NotificationRecordStore) {
        val records = store.list()
        ui.contentDialog(
            title = "通知记录",
            content = if (records.isEmpty()) "还没有通知记录。" else records.joinToString("\n\n") { it.describe() },
            actions = listOf("清空" to { store.clear(); ui.toast("通知记录已清空"); refresh() }),
        )
    }

    private fun NotificationRecord.describe(): String = buildString {
        append(formatDateTime(at))
        append(" · ")
        append(if (delivered) "已发出" else "未发出")
        append('\n')
        append(title)
        append('\n')
        append(text)
        append("\n来源：")
        append(source)
        if (detail.isNotBlank()) {
            append(" · ")
            append(detail)
        }
    }

    private fun logCard(): View {
        val logs = ModuleLogBuffer.snapshot()
        val path = ModuleLogBuffer.filePath()
        return ui.card(
            listOf(
                ui.row(
                    "模块日志",
                    "共 ${logs.size} 条（最新在前）。模块进程的日志默认不出现在 logcat 里，这里能直接看到。" +
                        (path?.let { "\n落盘位置：$it" } ?: "\n尚未落盘（模块还没被唤醒过）"),
                    actions = listOf(
                        "查看" to { showLogs() },
                        "清空" to { ModuleLogBuffer.clear(); ui.toast("日志已清空"); refresh() },
                    ),
                ),
            ),
        )
    }

    private fun showLogs() {
        val logs = ModuleLogBuffer.snapshot()
        ui.contentDialog(
            title = "模块日志",
            content = if (logs.isEmpty()) "暂无日志。" else logs.joinToString("\n") {
                "${formatDateTime(it.at)} ${it.level}/${it.tag}: ${it.message}"
            },
            actions = listOf("清空" to { ModuleLogBuffer.clear(); ui.toast("日志已清空"); refresh() }),
        )
    }

    // ---- 关于页 ----

    private fun aboutPage(): View {
        val placeholder = ui.row("Root 状态", "正在检测…")
        ui.background(
            work = { RootWakeController.inspect(applicationContext) },
            then = { status -> replaceRow(placeholder, buildRootRow(status)) },
        )
        return ui.page(
            listOf(
                ui.pageTitle("关于", "阅微补全计划 · 模块 ${versionLine()}"),
                ui.sectionTitle("Root 增强（实验性）"),
                ui.card(
                    listOf(
                        placeholder,
                        ui.row(
                            "说明",
                            "部分机型（如 HyperOS）会冻结后台应用，冻结期间系统闹钟与通知广播都不会执行。" +
                                "启用后，由 root 侧的看门狗在任务时刻唤醒模块（模块每次排完闹钟会把下次时刻写给它），" +
                                "不受冻结影响。\n开启会在 /data/adb/service.d 写入一个开机脚本；停用会删除它。",
                            actions = listOf(
                                "启用" to { rootAction { RootWakeController.enable(applicationContext) } },
                                "停用" to { rootAction { RootWakeController.disable(applicationContext) } },
                            ),
                        ),
                    ),
                ),
                ui.sectionTitle("状态"),
                ui.card(
                    listOf(
                        ui.row("模块进程", "PID ${android.os.Process.myPid()}"),
                        ui.row(
                            "通知权限",
                            if (CloudTaskNotifications.hasPermission(this)) "已授予" else "未授予，任务结果只能在打开阅微时以提示条显示",
                        ),
                        ui.row("唤醒时刻", formatTime(NextWakeHint.read(this))),
                    ),
                ),
            ),
        )
    }

    private fun buildRootRow(status: RootWakeController.Status): View = ui.row(
        status.displayTitle(),
        status.message,
        titleColor = if (status.rootAvailable) ui.palette.title else ui.palette.body,
    )

    private fun rootAction(block: () -> String) {
        ui.background(
            work = block,
            then = { message -> ui.toast(message); refresh() },
        )
    }

    // ---- 通用 ----

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
        } else {
            ui.toast("当前系统无需申请通知权限")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_POST_NOTIFICATIONS) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ui.toast(if (granted) "通知权限已授予" else "未授予通知权限，任务结果只能在打开阅微时以提示条显示")
        refresh()
    }

    private fun openSystemSettings(intent: Intent?) {
        if (intent == null) {
            ui.toast("当前系统无需该项设置")
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
        if (!opened) ui.toast("无法打开系统设置，请手动到系统设置里授权")
    }

    private fun rescheduleAlarm() {
        ui.background(
            work = {
                CloudTaskWakeScheduler.schedule(applicationContext)
                "已重排闹钟：下次唤醒 ${formatTime(NextWakeHint.read(applicationContext))}"
            },
            then = { ui.toast(it); refresh() },
        )
    }

    private fun replaceRow(old: View, new: View) {
        val parent = old.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(old)
        if (index < 0) return
        parent.removeViewAt(index)
        parent.addView(new, index)
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

    private fun versionLine(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }.getOrDefault("2.3.2")

    private fun formatTime(at: Long): String =
        if (at <= 0L) "未排程" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

    private fun formatDateTime(at: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(at))

    private companion object {
        const val LOG_TAG = "ReaMicroMain"
        const val REQUEST_POST_NOTIFICATIONS = 4501
        const val TAB_RECORDS = 0
        const val TAB_CONFIG = 1
        const val TAB_ABOUT = 2
        val TAB_TITLES = listOf("记录", "配置", "关于")
    }
}
