package com.reamicro.fix.cloud.local

import android.content.Context
import com.reamicro.fix.R
import com.reamicro.fix.i18n.moduleString
import com.reamicro.fix.logging.ModuleAndroidLog
import com.reamicro.fix.notification.CloudTaskNotifications
import java.util.concurrent.atomic.AtomicBoolean
import com.reamicro.fix.reload.TaskCancellation

object LocalTaskRunner {
    private val running = AtomicBoolean(false)
    private val activeLock = Any()
    private var activeAccount: String? = null
    private var activeCancellation: TaskCancellation? = null

    fun runTaskNow(context: Context, accountId: String, taskType: String): String {
        if (!running.compareAndSet(false, true)) return "任务正在执行，请稍后重试"
        val appContext = context.applicationContext
        val cancellation = TaskCancellation.current()?.child() ?: TaskCancellation()
        return try {
            synchronized(activeLock) {
                if (LocalTaskStore { appContext }.get(accountId, taskType) == null) return "任务不存在"
                activeAccount = accountId
                activeCancellation = cancellation
            }
            cancellation.run {
                if (com.reamicro.fix.cloud.root.RootTaskBridge.isEnabled(appContext)) {
                    if (com.reamicro.fix.cloud.root.RootTaskBridge.synchronize(appContext, requested = LocalTaskKey(accountId, taskType))) {
                        "已提交 Root 后台任务，请稍后查看任务记录"
                    } else appContext.moduleString(R.string.root_sync_stopped)
                } else {
                    retryNotifications(appContext)
                    val result = engine(appContext).runDue(maxTasks = 1, requested = LocalTaskKey(accountId, taskType))
                        .firstOrNull()?.message ?: "任务不存在"
                    retryNotifications(appContext)
                    result
                }
            }
        } finally {
            synchronized(activeLock) {
                if (activeCancellation === cancellation) {
                    activeAccount = null
                    activeCancellation = null
                }
            }
            running.set(false)
        }
    }

    /** Only the module's imported accounts reach this entry; host execution has its own runner. */
    internal fun deleteAccount(context: Context, accountId: String): String? {
        val app = context.applicationContext
        check(app.packageName == CloudTaskNotifications.MODULE_PACKAGE_NAME)
        synchronized(activeLock) {
            LocalTaskStore { app }.deleteAccount(accountId)
            if (activeAccount == accountId) activeCancellation?.cancel()
        }
        CloudTaskNotifications.cancelAccount(app, accountId)
        return runCatching {
            if (com.reamicro.fix.cloud.root.RootTaskBridge.isEnabled(app)) {
                com.reamicro.fix.cloud.root.RootTaskBridge.synchronize(app)
            }
        }.exceptionOrNull()?.let {
            ModuleAndroidLog.error("ReaMicroLocalTask", "Root account cleanup failed account=$accountId", it)
            it.message ?: it.javaClass.simpleName
        }
    }

    internal fun withTaskControl(action: () -> String): String {
        if (!running.compareAndSet(false, true)) return "任务正在执行，完成后再更改 Root增强状态"
        return try { action() } finally { running.set(false) }
    }

    private fun engine(context: Context): LocalTaskEngine {
        val store = LocalTaskStore { context }
        return LocalTaskEngine(store,
            onExecuting = { id, task -> CloudTaskNotifications.progress(context, id, store.accountLabel(id), task.taskType, "running") },
            onFinished = { id, task -> CloudTaskNotifications.progress(context, id, store.accountLabel(id), task.taskType, "finished") },
            onCompleted = { accountId, task, outcome ->
            ModuleAndroidLog.legacy("ReaMicroLocalTask",
                "account=$accountId type=${task.taskType} result=${outcome.result} nextRunAt=${store.get(accountId, task.taskType)?.nextRunAt} message=${outcome.message}")
            retryNotifications(context)
        })
    }

    @Synchronized
    internal fun retryNotifications(context: Context) {
        val store = LocalTaskStore { context }
        val delivered = mutableMapOf<String, MutableSet<String>>()
        for (item in store.pendingNotifications()) {
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val taskType = item.optString("taskType")
            val result = item.optString("result")
            val title = localTaskTitle(taskType) + if (result == "success") "" else "异常"
            val intent = CloudTaskNotifications.intent("local_$id", title, item.optString("message"), result, item.optString("items"),
                item.optString("accountId"), item.optString("accountLabel"), taskType, item.optString("accountGeneration"))
            if (CloudTaskNotifications.post(context, intent, source = "local-task-runner")) {
                delivered.getOrPut(item.optString("accountId")) { mutableSetOf() }.add(id)
            }
        }
        for ((accountId, ids) in delivered) store.acknowledgeNotifications(accountId, ids)
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
