package com.reamicro.fix.cloud.local

import android.content.Context
import com.reamicro.fix.cloud.api.CloudTaskWakeScheduler
import com.reamicro.fix.logging.ModuleAndroidLog
import com.reamicro.fix.notification.CloudTaskNotifications
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地自动任务的手动与后台执行入口。
 *
 * 配置与阅微 token 保存在本机 [LocalTaskStore]，不经过云任务服务器。
 * Android 模式调用 [LocalTaskEngine]；KSU 模式只同步配置、状态和执行请求，不在模块内重复运行。
 * 两种本地模式都复用 [CloudTaskLocalRunner.runTask]，结果通过模块的通知渠道发出。
 */
object LocalTaskRunner {
    private val running = AtomicBoolean(false)

    fun runDue(context: Context, maxTasks: Int = 8, force: Boolean = false): Int {
        if (!running.compareAndSet(false, true)) return 0
        val appContext = context.applicationContext
        return try {
            if (com.reamicro.fix.cloud.ksu.KsuTaskBridge.isEnabled(appContext)) {
                com.reamicro.fix.cloud.ksu.KsuTaskBridge.synchronize(appContext, force = force)
                0
            } else engine(appContext).runDue(maxTasks, force).size
        } finally {
            running.set(false)
            runCatching { CloudTaskWakeScheduler.schedule(appContext) }
        }
    }

    fun runTaskNow(context: Context, accountId: String, taskType: String): String {
        if (!running.compareAndSet(false, true)) return "任务正在执行，请稍后重试"
        val appContext = context.applicationContext
        return try {
            if (com.reamicro.fix.cloud.ksu.KsuTaskBridge.isEnabled(appContext)) {
                com.reamicro.fix.cloud.ksu.KsuTaskBridge.synchronize(appContext, requested = LocalTaskKey(accountId, taskType))
                "已提交 KSU 执行，请稍后查看任务记录"
            } else engine(appContext).runDue(maxTasks = 1, requested = LocalTaskKey(accountId, taskType))
                .firstOrNull()?.message ?: "任务不存在"
        } finally {
            running.set(false)
            runCatching { CloudTaskWakeScheduler.schedule(appContext) }
        }
    }

    internal fun switchExecutionMode(action: () -> String): String {
        if (!running.compareAndSet(false, true)) return "任务正在执行，完成后再切换执行模式"
        return try { action() } finally { running.set(false) }
    }

    private fun engine(context: Context): LocalTaskEngine {
        val store = LocalTaskStore { context }
        return LocalTaskEngine(store, onCompleted = { accountId, task, outcome ->
            ModuleAndroidLog.legacy("ReaMicroLocalTask",
                "type=${task.taskType} result=${outcome.result} nextRunAt=${store.get(accountId, task.taskType)?.nextRunAt} message=${outcome.message}")
            if (outcome.notify) postNotification(context, accountId, task.taskType, outcome)
        })
    }

    private fun postNotification(context: Context, accountId: String, taskType: String, outcome: CloudTaskLocalRunner.Outcome) {
        val id = "local_${taskType}_${accountId}_${outcome.message.hashCode()}"
        val title = localTaskTitle(taskType) + if (outcome.result == "success") "" else "异常"
        // 结构化奖励明细交给通知着色；正文里已经写了物品名，通知会就地着色而不是拼接列表。
        val items = outcome.detail.optJSONArray(CloudTaskLocalRunner.KEY_REWARD_ITEMS)?.toString().orEmpty()
        val intent = CloudTaskNotifications.intent(id, title, outcome.message, outcome.result, items)
        if (!CloudTaskNotifications.post(context, intent, source = "local-task-runner")) {
            XposedBridge.log("ReaMicro local task notification failed type=$taskType")
        }
    }

    internal fun localTaskTitle(taskType: String): String = when (taskType) {
        "yeshe_checkin" -> "每日轶闻"
        "yeshe_draw_card" -> "自动祈愿"
        "cloud_auto_read" -> "自动阅读"
        "traveling_merchant" -> "自动行商"
        "pawn" -> "期物典当"
        else -> "自动任务"
    }
}

/**
 * 按任务配置的每天时间点算出下一次执行时刻（东八区）。
 *
 * 配置的是"每天 HH:mm"，所以下一次必须是**那一天的 HH:mm**：今天还没到就用今天，否则明天。
 * 用 now + 24h 会让执行时刻每天往后漂，也和设置页里显示的时间对不上。
 */
internal fun nextDailyRunAt(timeOfDay: String, now: Long): Long {
    val zone = java.time.ZoneId.of("Asia/Shanghai")
    val parts = timeOfDay.trim().split(":")
    val hour = (parts.getOrNull(0)?.toIntOrNull() ?: 0).coerceIn(0, 23)
    val minute = (parts.getOrNull(1)?.toIntOrNull() ?: 5).coerceIn(0, 59)
    val localNow = java.time.Instant.ofEpochMilli(now).atZone(zone)
    var candidate = localNow.toLocalDate().atTime(hour, minute).atZone(zone)
    if (candidate.toInstant().toEpochMilli() <= now) candidate = candidate.plusDays(1)
    return candidate.toInstant().toEpochMilli()
}

/**
 * 「重算下次时刻」算出来的值。
 *
 * 只能提前、不能推后：签到没领到奖励时它的下次执行是"解锁时刻"（比如次日 08:00），
 * 直接按每日时间点重算会把它抹成次日 00:00 —— 用户看到的就是"任务时刻刷新了、奖励却没下文"。
 * 所以取两者中更早的那个：旧值更晚，说明是历史遗留的 `now + 24h`，按配置纠正；旧值更早，
 * 说明是一个仍在等待中的节点，保留它。
 *
 * [scheduled] 传 null 表示没有仍在将来的旧值（已过期或不存在）。
 */
internal fun rescheduledNextRunAt(
    taskType: String, timeOfDay: String, scheduled: Long?, now: Long, pendingAt: Long = 0L,
): Long {
    val candidate = when {
        pendingAt > 0L -> minOf(scheduled?.takeIf { it > 0L } ?: Long.MAX_VALUE, pendingAt)
        taskType == "traveling_merchant" -> scheduled ?: (now + 60_000L)
        scheduled != null -> minOf(scheduled, nextDailyRunAt(timeOfDay, now))
        else -> nextDailyRunAt(timeOfDay, now)
    }
    return if (candidate <= now) now + 60_000L else candidate
}

internal fun nextLocalRunAt(taskType: String, timeOfDay: String, result: String, override: Long, now: Long): Long = when {
    override > 0L -> if (override > now) override else now + 60_000L
    result == "failed" -> now + CloudTaskLocalRunner.TASK_RETRY_INTERVAL_MS
    result == "paused" -> now + 3_600_000L
    taskType == "yeshe_draw_card" -> 0L
    taskType == "traveling_merchant" -> now + 4L * 3_600_000L
    else -> nextDailyRunAt(timeOfDay, now)
}
