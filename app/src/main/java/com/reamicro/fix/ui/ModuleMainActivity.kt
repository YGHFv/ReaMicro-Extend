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
import com.reamicro.fix.cloud.local.LocalTaskRecord
import com.reamicro.fix.cloud.local.LocalTaskRunner
import com.reamicro.fix.cloud.local.LocalTaskStore
import com.reamicro.fix.logging.ModuleAndroidLog
import com.reamicro.fix.logging.ModuleLogBuffer
import com.reamicro.fix.notification.CloudTaskNotifications
import com.reamicro.fix.notification.NotificationRecord
import com.reamicro.fix.notification.NotificationRecordStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块自己的主界面。
 *
 * 存在的两个理由：
 * 1. **能看见**：任务记录、通知记录、诊断日志此前只存在于两个进程各自的存储里，用户没有任何
 *    入口去回查"任务到底跑没跑、通知到底发没发"。这里把它们汇总到一屏。
 * 2. **能被唤醒**：模块 App 一直没有 launcher activity，装完可能长期处于 stopped 状态，而
 *    stopped 应用的广播收不到（系统闹钟唤醒正是靠广播）。有了主界面，用户装完自然会打开一次，
 *    应用脱离 stopped——这与带 FLAG_INCLUDE_STOPPED_PACKAGES 的广播互为双保险。
 *
 * UI 全部用代码构建：模块只有极简资源，复用设置页那套视图辅助函数又必须绑定宿主的 Activity，
 * 所以这里用 [ModuleUiKit] 自建一套，配色仍取自 [com.reamicro.fix.hook.ModuleDialogTheme]。
 */
class ModuleMainActivity : Activity() {

    private lateinit var ui: ModuleUiKit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModuleLogBuffer.attach(this)
        ui = ModuleUiKit(this)
        window?.setBackgroundDrawable(ColorDrawable(ui.palette.pageBackground))
        applyStatusBarIcons()
        ModuleAndroidLog.legacy(LOG_TAG, "module main ui opened")
        refresh()
    }

    /**
     * 状态栏图标与本页底色相反。
     *
     * edge-to-edge 之后状态栏直接压在页面底色上：浅色底要深色图标（LIGHT_STATUS_BARS），
     * 深色底反之。不设的话深色模式下图标是黑的，等于看不见。
     */
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
            ui.page(
                listOf(
                    ui.pageTitle("阅微补全计划", versionLine()),
                    ui.sectionTitle("后台唤醒自检"),
                    wakeCard(),
                    ui.sectionTitle("本地自动任务"),
                    localTaskCard(),
                    ui.sectionTitle("通知"),
                    notificationCard(),
                    ui.sectionTitle("Root 增强（实验性）"),
                    rootCard(),
                    ui.sectionTitle("诊断"),
                    diagnosticsCard(),
                ),
            ),
        )
    }

    private fun versionLine(): String =
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "模块 ${info.versionName}（${info.versionCode}）· 阅微补全计划"
        }.getOrDefault("阅微补全计划")

    // ---- 自检与授权 ----

    private fun wakeCard(): View {
        val status = CloudTaskWakeDiagnostics.inspect(this)
        return ui.card(
            listOf(
                ui.row("后台唤醒", status.summary(), titleColor = if (status.healthy) ui.palette.title else ui.palette.destructiveText),
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

    /**
     * 厂商自启动白名单页。
     *
     * 各家 ROM 的组件名各不相同，且都可能在升级后改名，所以按"最可能 → 兜底"依次尝试，
     * 全都拉不起来时退回应用详情页，让用户自己在设置里找。
     */
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
                val next = LocalTaskStore { applicationContext }.earliestNextRunAt()
                if (next > 0L) "已重排闹钟，下一次本地任务：${formatTime(next)}" else "已重排闹钟（当前没有已启用的本地任务）"
            },
            then = { ui.toast(it) },
        )
    }

    // ---- 本地任务 ----

    private fun localTaskCard(): View {
        val store = LocalTaskStore { applicationContext }
        val accounts = store.accountIds()
        val enabled = accounts.sumOf { accountId -> store.list(accountId).count { it.enabled } }
        val nextRunAt = store.earliestNextRunAt()
        val subtitle = buildString {
            append("本机存有 ${accounts.size} 个账号的配置，已启用 $enabled 个任务")
            if (nextRunAt > 0L) append("；下次执行 ${formatTime(nextRunAt)}")
            append("。\n任务由模块进程在后台执行，与阅微是否打开无关。")
        }
        return ui.card(
            listOf(
                ui.row("本地任务状态", subtitle),
                ui.row(
                    "手动执行",
                    "立即跑一次所有已到期的本地任务，执行结果会记到下面的任务记录里。",
                    actions = listOf("立即执行" to { runLocalTasksNow() }),
                ),
                ui.row(
                    "任务记录",
                    recordSummary(store.accountIds().flatMap { store.records(it) }),
                    actions = listOf(
                        "查看" to { showLocalRecords(store) },
                        "清空" to { clearLocalRecords(store) },
                    ),
                ),
            ),
        )
    }

    private fun runLocalTasksNow() {
        ui.background(
            work = {
                val count = LocalTaskRunner.runDue(applicationContext)
                if (count > 0) "已执行 $count 个到期任务" else "没有到期的本地任务（或本机尚未配置本地任务）"
            },
            then = { ui.toast(it); refresh() },
        )
    }

    private fun showLocalRecords(store: LocalTaskStore) {
        val accountIds = store.accountIds()
        val merged = accountIds.flatMap { accountId ->
            store.records(accountId).map { accountId to it }
        }.sortedByDescending { (_, record) -> record.at }
        ui.contentDialog(
            title = "任务记录",
            content = if (merged.isEmpty()) {
                "本机还没有任务执行记录。\n\n如果刚配好任务，可以点「立即执行」跑一次；后台执行的结果也会记在这里。"
            } else {
                merged.joinToString("\n\n") { (accountId, record) ->
                    "${formatDateTime(record.at)} · ${taskTitle(record.taskType)} · " +
                        "${if (record.result == "success") "成功" else record.result}\n" +
                        "账号 $accountId\n${record.message}"
                }
            },
            actions = listOf("清空" to {
                clearLocalRecords(store)
            }),
        )
    }

    private fun clearLocalRecords(store: LocalTaskStore) {
        store.accountIds().forEach { store.clearRecords(it) }
        ui.toast("本机任务记录已清空")
        refresh()
    }

    private fun recordSummary(records: List<LocalTaskRecord>): String =
        if (records.isEmpty()) "暂无执行记录 · 点击查看" else "本机已记录 ${records.size} 条执行结果，最新在前"

    // ---- 通知 ----

    private fun notificationCard(): View {
        val store = NotificationRecordStore { applicationContext }
        val records = store.list()
        val delivered = records.count { it.delivered }
        val failed = records.size - delivered
        val subtitle = if (records.isEmpty()) {
            "还没有通知记录。任务结果通知会在这里留下投递结果（含失败原因）。"
        } else {
            "共 ${records.size} 条：成功投出 $delivered 条，未投出 $failed 条。\n" +
                "未投出的说明模块进程当时没能发出通知（被系统冻结或未启动），服务器会保留该消息下次重发。"
        }
        return ui.card(
            listOf(
                ui.row("通知记录", subtitle),
                ui.row(
                    "通知权限",
                    if (CloudTaskNotifications.hasPermission(this)) "已授予，任务结果会以系统通知的形式送达" else "未授予，任务结果只能在打开阅微时以提示条显示",
                    actions = listOf(
                        "查看记录" to { showNotificationRecords(store) },
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
            content = if (records.isEmpty()) {
                "还没有通知记录。"
            } else {
                records.joinToString("\n\n") { it.describe() }
            },
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

    // ---- Root ----

    private fun rootCard(): View {
        val placeholder = ui.row("Root 状态", "正在检测…")
        ui.background(
            work = { RootWakeController.inspect(applicationContext) },
            then = { status -> replaceRow(placeholder, buildRootRow(status)) },
        )
        return ui.card(
            listOf(
                placeholder,
                ui.row(
                    "说明",
                    "部分机型（如 HyperOS）会冻结后台应用，冻结期间系统闹钟与通知广播都不会执行。" +
                        "启用后，由 root 侧的看门狗在任务时刻唤醒模块（模块每次排完闹钟会把下次时刻写给它；" +
                        "读不到时刻时每 15 分钟兜底一次），不受冻结影响。\n" +
                        "开启会在 /data/adb/service.d/ 写入一个开机脚本；停用会删除它。",
                    actions = listOf(
                        "启用" to { rootAction { RootWakeController.enable(applicationContext) } },
                        "停用" to { rootAction { RootWakeController.disable(applicationContext) } },
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

    // ---- 诊断 ----

    private fun diagnosticsCard(): View {
        val logs = ModuleLogBuffer.snapshot()
        val path = ModuleLogBuffer.filePath()
        return ui.card(
            listOf(
                ui.row(
                    "模块日志",
                    "共 ${logs.size} 条（最新在前）。模块进程的日志默认不会出现在 logcat 里，这里能直接看到。" +
                        (path?.let { "\n落盘位置：$it" } ?: "\n尚未落盘（模块还没被唤醒过）"),
                    actions = listOf(
                        "查看" to { showLogs() },
                        "清空" to { ModuleLogBuffer.clear(); ui.toast("日志已清空"); refresh() },
                    ),
                ),
                ui.row(
                    "模块进程状态",
                    "进程 PID：${android.os.Process.myPid()} · 通知权限：" +
                        (if (CloudTaskNotifications.hasPermission(this)) "已授予" else "未授予"),
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

    /** 行内容会变（root 检测要在后台跑），所以就地替换整行而不是重建整页。 */
    private fun replaceRow(old: View, new: View) {
        val parent = old.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(old)
        if (index < 0) return
        parent.removeViewAt(index)
        parent.addView(new, index)
    }

    private fun taskTitle(taskType: String): String = when (taskType) {
        "yeshe_checkin" -> "野社签到"
        "yeshe_draw_card" -> "野社抽卡"
        "cloud_auto_read" -> "自动阅读"
        "traveling_merchant" -> "行商通知"
        else -> taskType
    }

    private fun formatTime(at: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

    private fun formatDateTime(at: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(at))

    private companion object {
        const val LOG_TAG = "ReaMicroMain"
        const val REQUEST_POST_NOTIFICATIONS = 4501
    }
}
