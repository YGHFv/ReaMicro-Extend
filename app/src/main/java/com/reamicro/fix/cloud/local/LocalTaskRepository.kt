package com.reamicro.fix.cloud.local

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

interface LocalTaskRepository {
    fun accountIds(): List<String>
    fun list(accountId: String): List<LocalTask>
    fun get(accountId: String, taskType: String): LocalTask? = list(accountId).firstOrNull { it.taskType == taskType }
    fun token(accountId: String): String
    fun accountGeneration(accountId: String): String = ""
    fun runtimeState(accountId: String, taskType: String): JSONObject
    fun recordState(accountId: String, taskType: String, state: JSONObject)
    fun recordExecution(accountId: String, task: LocalTask, state: JSONObject, record: LocalTaskRecord, notify: Boolean = true)
    fun executionEnabled(): Boolean = true
}

internal fun localTaskFromJson(taskType: String, obj: JSONObject): LocalTask {
    val booksJson = obj.optJSONArray("books") ?: JSONArray()
    val books = (0 until booksJson.length()).mapNotNull { index ->
        val item = booksJson.optJSONObject(index) ?: return@mapNotNull null
        val bookId = item.optLong("bookId", 0L)
        if (bookId <= 0L) null else LocalTaskBook(bookId, item.optString("name"))
    }
    return LocalTask(
        taskType = taskType,
        enabled = obj.optBoolean("enabled", false),
        timeOfDay = obj.optString("timeOfDay", "00:05").ifBlank { "00:05" }.let {
            if (taskType == AutoReadTimeLock.TASK_TYPE) AutoReadTimeLock.safeTime(it, obj.optInt("durationMinutes", 30)) else it
        },
        durationMinutes = obj.optInt("durationMinutes", 30).coerceIn(1, 720),
        dailyDrawLimit = obj.optInt("dailyLimit", 3).coerceIn(0, 20),
        drawAtCustomTime = obj.optBoolean("drawAtCustomTime", false),
        hostReadOncePerDay = obj.optBoolean("hostReadOncePerDay", true),
        books = books,
        merchantAutoComplete = obj.optBoolean("merchantAutoComplete", false),
        merchantCityCode = obj.optString("merchantCityCode"),
        merchantPrincipal = obj.optLong("merchantPrincipal", 0L).coerceAtLeast(0L),
        merchantTransportId = obj.optLong("merchantTransportId", 0L).coerceAtLeast(0L),

        forbiddenPawnPropIds = obj.optJSONArray(LocalTaskStore.KEY_FORBIDDEN_PAWN_PROP_IDS)?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).trim().takeIf { it.isNotEmpty() }
            }.toSet()
        } ?: CloudTaskLocalRunner.PROHIBITED_PAWN_PROP_HINTS.keys,
        blessingType = obj.optString("blessingType").trim().uppercase(),
        nextRunAt = obj.optLong("nextRunAt", 0L),
        lastMessage = obj.optString("lastMessage"),
        lastRunAt = obj.optLong("lastRunAt", 0L),
        configUpdatedAt = obj.optLong("configUpdatedAt", 0L),
    )
}

/** Compose and Root task switches own the full workflow; host preferences stay independent. */
internal fun normalizeModuleMerchantConfig(account: JSONObject): JSONObject = account.apply {
    optJSONObject("tasks")?.optJSONObject("traveling_merchant")?.put("merchantAutoComplete", true)
}

internal fun applyLocalTaskExecution(
    current: JSONObject, task: LocalTask, state: JSONObject, now: Long = System.currentTimeMillis(),
) = LocalTaskRuntimeMerge.complete(current, task.configUpdatedAt, state, now)

internal fun appendLocalTaskRecord(root: JSONObject, record: LocalTaskRecord) {
    val records = root.optJSONArray("records") ?: JSONArray()
    records.put(JSONObject().put("at", record.at).put("taskType", record.taskType)
        .put("result", record.result).put("message", record.message).put("detail", record.detail))
    val trimmed = JSONArray()
    for (index in (records.length() - LocalTaskStore.MAX_RECORDS).coerceAtLeast(0) until records.length()) {
        trimmed.put(records.get(index))
    }
    root.put("records", trimmed)
}

internal fun localTaskRuntimeState(task: JSONObject): JSONObject = JSONObject().apply {
    for (key in LocalTaskStore.RUNTIME_STATE_KEYS) if (task.has(key)) put(key, task.get(key))
}

internal fun pendingRewardDrawState(checkin: JSONObject, draw: JSONObject, now: Long): JSONObject? {
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()
    val loreId = checkin.optLong("claimLoreId")
    if (loreId <= 0L || checkin.optString("claimCompletedDate") != today || draw.optLong("drawRewardLoreId") == loreId) return null
    return JSONObject().put("drawRewardLoreId", loreId).put("drawPending", true).put("nextRunAt", now)
}

internal fun nextLocalTaskAt(task: LocalTask, state: JSONObject, checkin: JSONObject, now: Long): Long? {
    if (!task.enabled) return null
    if (task.taskType == "yeshe_draw_card") {
        if (!task.drawAtCustomTime) {
            if (pendingRewardDrawState(checkin, state, now) != null) return now
            if (!state.optBoolean("drawPending")) return null
        }
        // Wake once to persist the legacy schedule; recomputing a future time on every read skips its first run.
        if (!state.optBoolean("drawPending") && task.nextRunAt <= 0L) return now
    }
    return AutoReadTimeLock.clampNextAt(task, state, task.nextRunAt.coerceAtLeast(1L), now)
}

internal fun mergeLocalTaskSnapshot(current: JSONObject?, incoming: JSONObject): JSONObject =
    LocalTaskRuntimeMerge.snapshot(current, incoming)

/** Root snapshots can update an imported account, but cannot recreate it after deletion. */
internal fun applyExistingLocalTaskAccountSnapshot(current: JSONObject?, incoming: JSONObject) {
    if (current == null || current.optString("accountGeneration") != incoming.optString("accountGeneration")) return
    val tasks = incoming.optJSONObject("tasks") ?: return
    val existing = current.optJSONObject("tasks") ?: JSONObject().also { current.put("tasks", it) }
    tasks.keys().forEach { type ->
        val task = tasks.optJSONObject(type) ?: return@forEach
        existing.put(type, mergeLocalTaskSnapshot(existing.optJSONObject(type), task))
    }
    applyLocalTaskRecordsSnapshot(current, incoming)
}

internal fun localTaskAccountStateCurrent(account: JSONObject, state: JSONObject): Boolean =
    !state.has(LocalTaskRuntimeMerge.ACCOUNT_CONTEXT) ||
        state.optString(LocalTaskRuntimeMerge.ACCOUNT_CONTEXT) == account.optString("accountGeneration")

internal fun rescheduleLocalTasks(store: LocalTaskRepository, now: Long): Int {
    var updated = 0
    for (accountId in store.accountIds()) {
        val generation = store.accountGeneration(accountId)
        val checkin = store.runtimeState(accountId, "yeshe_checkin")
        for (task in store.list(accountId)) {
            val state = store.runtimeState(accountId, task.taskType)
            val effectiveNext = nextLocalTaskAt(task, state, checkin, now) ?: continue
            val pendingClaim = if (task.taskType == "yeshe_checkin" &&
                state.optString("claimCompletedDate") != state.optString("lastCheckinDate")
            ) state.optLong("claimDueAt") else 0L
            val candidate = if (task.taskType == "yeshe_draw_card" &&
                (state.optBoolean("drawPending") || !task.drawAtCustomTime)) {
                effectiveNext.coerceAtLeast(now)
            } else if (task.taskType == AutoReadTimeLock.TASK_TYPE) {
                val daily = AutoReadTimeLock.nextDailyAt(task.timeOfDay, task.durationMinutes, now)
                minOf(task.nextRunAt.takeIf { it > 0L } ?: daily, daily).coerceAtLeast(now)
            } else rescheduledNextRunAt(task.taskType, task.timeOfDay, task.nextRunAt.takeIf { it > 0L }, now, pendingClaim)
            val next = AutoReadTimeLock.clampNextAt(task, state, candidate, now)
            if (next == task.nextRunAt) continue
            store.recordState(accountId, task.taskType, JSONObject().put("nextRunAt", next)
                .put(LocalTaskRuntimeMerge.CONFIG_CONTEXT, task.configUpdatedAt)
                .put(LocalTaskRuntimeMerge.ACCOUNT_CONTEXT, generation))
            updated++
        }
    }
    return updated
}

internal fun clearLocalTaskRecordsBefore(account: JSONObject, clearedAt: Long) {
    if (clearedAt <= account.optLong("recordsClearedAt")) return
    account.put("recordsClearedAt", clearedAt)
    val records = account.optJSONArray("records") ?: JSONArray()
    val remaining = JSONArray()
    for (index in 0 until records.length()) {
        val record = records.optJSONObject(index) ?: continue
        if (record.optLong("at") > clearedAt) remaining.put(record)
    }
    account.put("records", remaining)
}

internal fun applyLocalTaskRecordsSnapshot(current: JSONObject, incoming: JSONObject) {
    val restoredAt = current.optLong(LocalTaskAccountBundle.RECORDS_IMPORTED_AT)
    val restoredHistory = if ((restoredAt > 0L || incoming.optBoolean("recordsMerge")) && incoming.optJSONArray("records") != null) {
        // Apply new tombstones to the already restored baseline independently of raw module history.
        JSONObject(current.toString()).also { baseline ->
            val deletionOnly = JSONObject(incoming.toString()).apply { remove("records") }
            applyLocalTaskRecordDeletionsSnapshot(baseline, deletionOnly)
        }.optJSONArray("records")
    } else null
    val incomingClear = incoming.optLong("recordsClearedAt").takeIf { restoredAt <= 0L || it >= restoredAt } ?: 0L
    val clearedAt = maxOf(current.optLong("recordsClearedAt"), incomingClear)
    applyLocalTaskRecordDeletionsSnapshot(current, incoming)
    if (restoredHistory != null) current.put("records", mergeRestoredLocalTaskRecords(
        restoredHistory, current.optJSONArray("records") ?: JSONArray(), restoredAt,
    ))
    current.remove("recordsClearedAt")
    clearLocalTaskRecordsBefore(current, clearedAt)
}
