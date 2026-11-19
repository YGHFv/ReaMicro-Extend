package com.reamicro.fix.cloud.local

import org.json.JSONArray
import org.json.JSONObject
import com.reamicro.fix.reload.TaskCancellation

internal data class LocalTaskKey(val accountId: String, val taskType: String)

internal class LocalTaskEngine(
    private val store: LocalTaskRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val execute: ((String, JSONObject, JSONObject, JSONObject) -> CloudTaskLocalRunner.Outcome)? = null,
    private val onCompleted: (String, LocalTask, CloudTaskLocalRunner.Outcome) -> Unit = { _, _, _ -> },
    private val beforeExecute: (String, LocalTask) -> Unit = { _, _ -> },
    private val onExecuting: (String, LocalTask) -> Unit = { _, _ -> },
    private val onFinished: (String, LocalTask) -> Unit = { _, _ -> },
) {
    fun runDue(
        maxTasks: Int = 8, force: Boolean = false, requested: LocalTaskKey? = null,
        expectedAccountGeneration: String? = null,
    ): List<LocalTaskRecord> {
        val results = mutableListOf<LocalTaskRecord>()
        val processed = mutableSetOf<LocalTaskKey>()
        if (Thread.currentThread().isInterrupted) return results
        if (requested != null && expectedAccountGeneration != null &&
            store.accountGeneration(requested.accountId) != expectedAccountGeneration) return results
        recoverTasks()
        while (results.size < maxTasks && !Thread.currentThread().isInterrupted && store.executionEnabled()) {
            triggerRewardDraws()
            val now = clock()
            val candidates = store.accountIds().flatMap { accountId ->
                val generation = store.accountGeneration(accountId)
                store.list(accountId).map { Triple(LocalTaskKey(accountId, it.taskType), it, generation) }
            }.filter { (key, task) ->
                key !in processed && if (requested != null) key == requested else
                    task.enabled && (force || task.taskType != "yeshe_draw_card" || store.runtimeState(key.accountId, task.taskType).optBoolean("drawPending")) &&
                        (force || AutoReadTimeLock.clampNextAt(
                            task, store.runtimeState(key.accountId, task.taskType), task.nextRunAt, now,
                        ) <= now)
            }
            val (key, task, accountGeneration) = candidates.minByOrNull { it.second.nextRunAt } ?: break
            processed += key
            if (store.accountGeneration(key.accountId) != accountGeneration ||
                (requested != null && expectedAccountGeneration != null && accountGeneration != expectedAccountGeneration)
            ) continue
            val token = store.token(key.accountId)
            val stateInput = store.runtimeState(key.accountId, task.taskType)
            val timeLock = if (task.taskType == AutoReadTimeLock.TASK_TYPE) {
                AutoReadTimeLock.deferredOutcome(task.durationMinutes, stateInput, now)
            } else null
            var stopAfterTask = false
            try {
                var outcome = timeLock ?: if (token.isBlank()) {
                    CloudTaskLocalRunner.Outcome("paused", "登录凭据缺失，请更新该账号凭证", stateInput)
                } else runCatching {
                    val active = TaskCancellation {
                        store.get(key.accountId, task.taskType) != null &&
                            store.accountGeneration(key.accountId) == accountGeneration
                    }
                    try {
                        TaskCancellation.current()?.register(active::cancel).use {
                            active.run {
                                // Only actual business requests prepare the Root network.
                                if (!store.executionEnabled() || Thread.currentThread().isInterrupted) {
                                    throw InterruptedException("任务已停止，保留进度等待下次执行")
                                }
                                beforeExecute(key.accountId, task)
                                active.check()
                                if (!store.executionEnabled() || Thread.currentThread().isInterrupted) {
                                    throw InterruptedException("任务已停止，保留进度等待下次执行")
                                }
                                runCatching { onExecuting(key.accountId, task) }
                                val credential = JSONObject().put("baseUrl", CloudTaskLocalRunner.REAMICRO_BASE_URL).put("token", token)
                                if (execute != null) execute.invoke(task.taskType, stateInput, localTaskRequest(task), credential)
                                else CloudTaskLocalRunner.runTask(task.taskType, stateInput, localTaskRequest(task), credential, clock = clock) { progress ->
                                    active.check()
                                    store.recordState(key.accountId, task.taskType, JSONObject(progress.toString())
                                        .put(LocalTaskRuntimeMerge.CONFIG_CONTEXT, task.configUpdatedAt)
                                        .put(LocalTaskRuntimeMerge.ACCOUNT_CONTEXT, accountGeneration))
                                }
                            }
                        }
                    } finally {
                        active.cancel()
                    }
                }.getOrElse {
                    stopAfterTask = it is InterruptedException || it is java.util.concurrent.CancellationException ||
                        Thread.currentThread().isInterrupted || com.reamicro.fix.reload.TaskCancellation.current()?.isCancelled == true
                    // FileChannel.lock 不接受中断标记；只在提交本次进度期间暂时清除。
                    if (stopAfterTask) Thread.interrupted()
                    CloudTaskLocalRunner.Outcome("failed", it.message ?: "本地任务执行失败",
                        store.runtimeState(key.accountId, task.taskType), notify = !stopAfterTask)
                }
                if (store.get(key.accountId, task.taskType) == null ||
                    store.accountGeneration(key.accountId) != accountGeneration
                ) {
                    stopAfterTask = Thread.currentThread().isInterrupted ||
                        TaskCancellation.current()?.isCancelled == true
                    if (stopAfterTask) break
                    continue
                }
                if (Thread.currentThread().isInterrupted) {
                    stopAfterTask = true
                    Thread.interrupted()
                }
                if (stopAfterTask) outcome = outcome.copy(notify = false)
                val finished = clock()
                val state = JSONObject(outcome.state.toString())
                val override = state.optLong("nextRunAtOverride")
                state.remove("nextRunAtOverride")
                val candidate = if (task.taskType == AutoReadTimeLock.TASK_TYPE && override <= 0L && outcome.result == "success") {
                    AutoReadTimeLock.nextDailyAt(task.timeOfDay, task.durationMinutes, finished)
                } else nextLocalRunAt(task.taskType, task.timeOfDay, outcome.result, override, finished)
                state.put("nextRunAt", AutoReadTimeLock.clampNextAt(task, state, candidate, finished))
                    .put("lastMessage", outcome.message).put("lastRunAt", finished)
                    .put(LocalTaskRuntimeMerge.ACCOUNT_CONTEXT, accountGeneration)
                if (task.taskType == "yeshe_draw_card") state.put("drawPending", outcome.result != "success")
                val record = LocalTaskRecord(finished, task.taskType, outcome.result, outcome.message, outcome.detail.toString())
                store.recordExecution(key.accountId, task, state, record, outcome.notify)
                results += record
                if (!stopAfterTask) {
                    triggerRewardDraws()
                    runCatching { onCompleted(key.accountId, task, outcome) }
                }
                if (requested != null || stopAfterTask) break
            } finally {
                if (store.get(key.accountId, task.taskType) != null &&
                    store.accountGeneration(key.accountId) == accountGeneration) runCatching { onFinished(key.accountId, task) }
                if (stopAfterTask) Thread.currentThread().interrupt()
            }
        }
        return results
    }

    private fun recoverTasks() {
        for (accountId in store.accountIds()) {
            val generation = store.accountGeneration(accountId)
            for (task in store.list(accountId).filter { it.enabled }) {
                val state = recoveredAutomationState(task.taskType, store.runtimeState(accountId, task.taskType), clock()) ?: continue
                store.recordState(accountId, task.taskType, state
                    .put(LocalTaskRuntimeMerge.CONFIG_CONTEXT, task.configUpdatedAt)
                    .put(LocalTaskRuntimeMerge.ACCOUNT_CONTEXT, generation))
            }
        }
    }

    private fun triggerRewardDraws() {
        val now = clock()
        for (accountId in store.accountIds()) {
            val generation = store.accountGeneration(accountId)
            val draw = store.get(accountId, "yeshe_draw_card")?.takeIf { it.enabled } ?: continue
            val checkin = store.runtimeState(accountId, "yeshe_checkin")
            val state = store.runtimeState(accountId, draw.taskType)
            val pending = pendingRewardDrawState(checkin, state, now) ?: continue
            store.recordState(accountId, draw.taskType, pending
                .put(LocalTaskRuntimeMerge.CONFIG_CONTEXT, draw.configUpdatedAt)
                .put(LocalTaskRuntimeMerge.ACCOUNT_CONTEXT, generation))
        }
    }
}

internal fun localTaskRequest(task: LocalTask): JSONObject = JSONObject().apply {
    put("blessingType", task.blessingType)
    when (task.taskType) {
        "cloud_auto_read" -> {
            put("durationMinutes", task.durationMinutes)
            if (task.books.isNotEmpty()) {
                put("books", JSONArray(task.books.map { JSONObject().put("bookId", it.bookId).put("name", it.name) }))
                put("bookLimit", task.books.size)
            }
        }
        "yeshe_draw_card" -> put("dailyLimit", task.dailyDrawLimit)
        "pawn" -> put("forbiddenPawnPropIds", JSONArray(task.forbiddenPawnPropIds.sorted()))
        "traveling_merchant" -> {
            put("merchantAutoComplete", task.merchantAutoComplete)
            if (task.merchantCityCode.isNotBlank()) put("merchantCityCode", task.merchantCityCode)
            if (task.merchantPrincipal > 0L) put("merchantPrincipal", task.merchantPrincipal)
            put("merchantTransportId", task.merchantTransportId)
        }
    }
}
