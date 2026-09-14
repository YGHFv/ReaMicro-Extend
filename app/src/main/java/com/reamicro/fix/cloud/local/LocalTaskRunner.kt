package com.reamicro.fix.cloud.local

import android.content.Context
import com.reamicro.fix.cloud.api.CloudTaskWakeScheduler
import com.reamicro.fix.notification.CloudTaskNotifications
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * 在模块进程中执行「本地自动任务」。
 *
 * 与 [CloudTaskLocalRunner]（云端 device 租约）不同，本地任务的配置与阅微 token 都保存在本机
 * [LocalTaskStore]，不经过服务器。执行逻辑复用 [CloudTaskLocalRunner.runTask]，结果直接用模块
 * 自己的通知渠道发出。行商任务的暂停/续跑通过写回 nextRunAt 实现。
 */
object LocalTaskRunner {

    /** 执行所有已到期的本地任务，写回状态、记执行记录并发通知。返回执行条数。 */
    fun runDue(context: Context, maxTasks: Int = 8): Int {
        val appContext = context.applicationContext
        val store = LocalTaskStore { appContext }
        val now = System.currentTimeMillis()
        var completed = 0
        try {
            for (accountId in store.accountsWithEnabledTasks()) {
                val token = store.token(accountId)
                if (token.isBlank()) continue
                val credential = JSONObject()
                    .put("baseUrl", CloudTaskLocalRunner.REAMICRO_BASE_URL)
                    .put("token", token)
                for (task in store.list(accountId)) {
                    if (completed >= maxTasks) return completed
                    if (!task.enabled) continue
                    if (task.nextRunAt > now) continue
                    val request = buildRequest(task)
                    // 把上次落盘的运行时状态喂回执行器，签到/抽卡/阅读的每日计数与行商已通知 tripId 才能续跑。
                    val stateInput = store.runtimeState(accountId, task.taskType)
                    val outcome = runCatching {
                        CloudTaskLocalRunner.runTask(task.taskType, stateInput, request, credential)
                    }.getOrElse { CloudTaskLocalRunner.Outcome("failed", it.message ?: "本地任务执行失败") }
                    persistOutcome(store, accountId, task.taskType, outcome, now)
                    // 不论成败都记一条，方便用户回查「为什么没跑 / 为什么失败」。
                    store.appendRecord(accountId, task.taskType, outcome.result, outcome.message, now)
                    if (outcome.notify) {
                        postNotification(appContext, accountId, task.taskType, outcome)
                    }
                    completed++
                }
            }
        } finally {
            // 每次执行完都要把下一次唤醒排上。否则本地任务只会在用户打开阅微时执行，
            // 闹钟一旦没排上就再也不会自启。
            runCatching { CloudTaskWakeScheduler.schedule(appContext) }
        }
        return completed
    }

    private fun buildRequest(task: LocalTask): JSONObject {
        val request = JSONObject()
        // 运签签种对两个任务都有意义：每日轶闻固定求运，自动行商按用户选择求安/求财。
        if (task.blessingType.isNotBlank()) request.put("blessingType", task.blessingType)
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
        taskType: String,
        outcome: CloudTaskLocalRunner.Outcome,
        now: Long,
    ) {
        val state = JSONObject(outcome.state.toString())
        // 统一把 nextRunAtOverride 落成本地存储的 nextRunAt；无 override 时按类型默认间隔。
        val override = state.optLong("nextRunAtOverride", 0L)
        val nextRunAt = when {
            override > 0L -> override
            taskType == "traveling_merchant" -> now + 4L * 3_600_000L
            else -> now + 24L * 3_600_000L
        }
        state.remove("nextRunAtOverride")
        state.put(LocalTaskStore.KEY_NEXT_RUN_AT, nextRunAt)
        state.put(LocalTaskStore.KEY_LAST_MESSAGE, outcome.message)
        state.put(LocalTaskStore.KEY_LAST_RUN_AT, now)
        store.recordState(accountId, taskType, state)
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
