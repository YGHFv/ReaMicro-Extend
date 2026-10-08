package com.reamicro.fix.cloud.local

import android.content.Context
import com.reamicro.fix.logging.ModuleAndroidLog
import com.reamicro.fix.notification.CloudTaskNotifications
import com.reamicro.fix.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

object LocalTaskRunner {
    private val running = AtomicBoolean(false)

    fun runTaskNow(context: Context, accountId: String, taskType: String): String {
        if (!running.compareAndSet(false, true)) return "任务正在执行，请稍后重试"
        val appContext = context.applicationContext
        return try {
            if (com.reamicro.fix.cloud.root.RootTaskBridge.isEnabled(appContext)) {
                com.reamicro.fix.cloud.root.RootTaskBridge.synchronize(appContext, requested = LocalTaskKey(accountId, taskType))
                "已提交 Root 后台任务，请稍后查看任务记录"
            } else engine(appContext).runDue(maxTasks = 1, requested = LocalTaskKey(accountId, taskType))
                .firstOrNull()?.message ?: "任务不存在"
        } finally {
            running.set(false)
        }
    }

    internal fun withTaskControl(action: () -> String): String {
        if (!running.compareAndSet(false, true)) return "任务正在执行，完成后再更改 Root增强状态"
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
