package com.reamicro.fix.cloud.local

import android.content.Context
import com.reamicro.fix.cloud.api.CloudTaskWakeScheduler
import com.reamicro.fix.logging.ModuleAndroidLog
import com.reamicro.fix.notification.CloudTaskNotifications
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在模块进程中执行「本地自动任务」。
 *
 * 与 [CloudTaskLocalRunner]（云端 device 租约）不同，本地任务的配置与阅微 token 都保存在本机
 * [LocalTaskStore]，不经过服务器。执行逻辑复用 [CloudTaskLocalRunner.runTask]，结果直接用模块
 * 自己的通知渠道发出。行商任务的暂停/续跑通过写回 nextRunAt 实现。
 */
object LocalTaskRunner {
    private val running = AtomicBoolean(false)

    /**
     * 执行本地任务，写回状态、记执行记录并发通知。返回执行条数。
     *
     * [force] 为真时忽略 nextRunAt 一律执行——「立即执行」按钮要的就是这个：不强制的话
     * 时刻还没到的任务会被跳过，用户看到的"下次执行时间"永远停在上一次的旧值上。
     */
    fun runDue(context: Context, maxTasks: Int = 8, force: Boolean = false): Int {
        if (!running.compareAndSet(false, true)) return 0
        val appContext = context.applicationContext
        val store = LocalTaskStore { appContext }
        val now = System.currentTimeMillis()
        var completed = 0
        try {
            store.recoverPendingAutomationTasks(now)
            for (accountId in store.accountsWithEnabledTasks()) {
                val token = store.token(accountId)
                if (token.isBlank()) continue
                val credential = JSONObject()
                    .put("baseUrl", CloudTaskLocalRunner.REAMICRO_BASE_URL)
                    .put("token", token)
                for (taskType in store.list(accountId).map { it.taskType }) {
                    val task = store.get(accountId, taskType) ?: continue
                    if (completed >= maxTasks) return completed
                    if (!task.enabled) continue
                    if (!force && task.nextRunAt > now) continue
                    val request = buildRequest(task)
                    // 把上次落盘的运行时状态喂回执行器，签到/抽卡/阅读的每日计数与行商已通知 tripId 才能续跑。
                    val stateInput = store.runtimeState(accountId, task.taskType)
                    val outcome = runCatching {
                        CloudTaskLocalRunner.runTask(task.taskType, stateInput, request, credential)
                    }.getOrElse { CloudTaskLocalRunner.Outcome("failed", it.message ?: "本地任务执行失败") }
                    persistOutcome(store, accountId, task, outcome, System.currentTimeMillis())
                    // 不论成败都记一条，方便用户回查「为什么没跑 / 为什么失败」。
                    store.appendRecord(
                        accountId,
                        task.taskType,
                        outcome.result,
                        outcome.message,
                        now,
                        outcome.detail.toString(),
                    )
                    if (outcome.notify) {
                        postNotification(appContext, accountId, task.taskType, outcome)
                    }
                    completed++
                }
            }
        } finally {
            running.set(false)
            // 每次执行完都要把下一次唤醒排上。否则本地任务只会在用户打开阅微时执行，
            // 闹钟一旦没排上就再也不会自启。
            runCatching { CloudTaskWakeScheduler.schedule(appContext) }
        }
        return completed
    }

    /**
     * 立即执行指定的一条本地任务（忽略 nextRunAt）。返回结果消息。
     *
     * 与「全部立即执行」分开：用户往往只想补跑某一条（比如刚改了行商参数），
     * 把其它任务一起跑掉既慢又可能重复消耗。
     */
    fun runTaskNow(context: Context, accountId: String, taskType: String): String {
        if (!running.compareAndSet(false, true)) return "任务正在执行，请稍后重试"
        return try {
            executeTaskNow(context, accountId, taskType)
        } finally {
            running.set(false)
            runCatching { CloudTaskWakeScheduler.schedule(context.applicationContext) }
        }
    }

    private fun executeTaskNow(context: Context, accountId: String, taskType: String): String {
        val appContext = context.applicationContext
        val store = LocalTaskStore { appContext }
        store.recoverPendingAutomationTasks()
        val task = store.get(accountId, taskType) ?: return "任务不存在"
        val token = store.token(accountId)
        if (token.isBlank()) return "阅微登录凭据缺失，请重新登录"
        val credential = JSONObject()
            .put("baseUrl", CloudTaskLocalRunner.REAMICRO_BASE_URL)
            .put("token", token)
        val now = System.currentTimeMillis()
        val outcome = runCatching {
            CloudTaskLocalRunner.runTask(
                taskType,
                store.runtimeState(accountId, taskType),
                buildRequest(task),
                credential,
            )
        }.getOrElse { CloudTaskLocalRunner.Outcome("failed", it.message ?: "执行失败") }
        persistOutcome(store, accountId, task, outcome, System.currentTimeMillis())
        store.appendRecord(accountId, taskType, outcome.result, outcome.message, now, outcome.detail.toString())
        if (outcome.notify) postNotification(appContext, accountId, taskType, outcome)
        return outcome.message
    }

    private fun buildRequest(task: LocalTask): JSONObject {
        val request = JSONObject()
        request.put("blessingType", task.blessingType)
        when (task.taskType) {
            "cloud_auto_read" -> {
                request.put("durationMinutes", task.durationMinutes)
                if (task.books.isNotEmpty()) {
                    request.put("books", JSONArray(task.books.map {
                        JSONObject().put("bookId", it.bookId).put("name", it.name)
                    }))
                    request.put("bookLimit", task.books.size)
                }
            }
            "yeshe_draw_card" -> request.put("dailyLimit", task.dailyDrawLimit)
            "traveling_merchant" -> {
                request.put("merchantAutoComplete", task.merchantAutoComplete)
                if (task.merchantCityCode.isNotBlank()) request.put("merchantCityCode", task.merchantCityCode)
                if (task.merchantPrincipal > 0L) request.put("merchantPrincipal", task.merchantPrincipal)
                if (task.merchantTransportId > 0L) request.put("merchantTransportId", task.merchantTransportId)
            }
        }
        return request
    }

    private fun persistOutcome(
        store: LocalTaskStore,
        accountId: String,
        task: LocalTask,
        outcome: CloudTaskLocalRunner.Outcome,
        now: Long,
    ) {
        val state = JSONObject(outcome.state.toString())
        // 统一把 nextRunAtOverride 落成本地存储的 nextRunAt；无 override 时按任务自己配置的时间点算。
        val override = state.optLong("nextRunAtOverride", 0L)
        val nextRunAt = nextLocalRunAt(task.taskType, task.timeOfDay, outcome.result, override, now)
        state.remove("nextRunAtOverride")
        // 记录下一次的任务时刻，主界面与设置页看到的是同一个来源。
        state.put(LocalTaskStore.KEY_NEXT_RUN_AT, nextRunAt)
        state.put(LocalTaskStore.KEY_LAST_MESSAGE, outcome.message)
        state.put(LocalTaskStore.KEY_LAST_RUN_AT, now)
        store.recordState(accountId, task.taskType, state)
        ModuleAndroidLog.legacy("ReaMicroLocalTask",
            "type=${task.taskType} result=${outcome.result} nextRunAt=$nextRunAt message=${outcome.message}")
    }

    private fun postNotification(
        context: Context,
        accountId: String,
        taskType: String,
        outcome: CloudTaskLocalRunner.Outcome,
    ) {
        val id = "local_${taskType}_${accountId}_${outcome.message.hashCode()}"
        val title = localTaskTitle(taskType) + if (outcome.result == "success") "" else "异常"
        val intent = CloudTaskNotifications.intent(id, title, outcome.message, outcome.result, "")
        if (!CloudTaskNotifications.post(context, intent, source = "local-task-runner")) {
            XposedBridge.log("ReaMicro local task notification failed type=$taskType")
        }
    }

    private fun localTaskTitle(taskType: String): String = when (taskType) {
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
    taskType == "traveling_merchant" -> now + 4L * 3_600_000L
    else -> nextDailyRunAt(timeOfDay, now)
}
