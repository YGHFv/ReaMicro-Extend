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
    fun runtimeState(accountId: String, taskType: String): JSONObject
    fun recordState(accountId: String, taskType: String, state: JSONObject)
    fun recordExecution(accountId: String, task: LocalTask, state: JSONObject, record: LocalTaskRecord)
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
        timeOfDay = obj.optString("timeOfDay", "00:05").ifBlank { "00:05" },
        durationMinutes = obj.optInt("durationMinutes", 30).coerceIn(1, 720),
        dailyDrawLimit = obj.optInt("dailyLimit", 3).coerceIn(0, 20),
        books = books,
        merchantAutoComplete = obj.optBoolean("merchantAutoComplete", false),
        merchantCityCode = obj.optString("merchantCityCode"),
        merchantPrincipal = obj.optLong("merchantPrincipal", 0L).coerceAtLeast(0L),
        merchantTransportId = obj.optLong("merchantTransportId", 0L).coerceAtLeast(0L),
        blessingType = obj.optString("blessingType").trim().uppercase(),
        nextRunAt = obj.optLong("nextRunAt", 0L),
        lastMessage = obj.optString("lastMessage"),
        lastRunAt = obj.optLong("lastRunAt", 0L),
        configUpdatedAt = obj.optLong("configUpdatedAt", 0L),
    )
}

internal fun applyLocalTaskExecution(current: JSONObject, task: LocalTask, state: JSONObject) {
    for (key in LocalTaskStore.RUNTIME_STATE_KEYS) {
        if (key != LocalTaskStore.KEY_NEXT_RUN_AT && state.has(key)) current.put(key, state.get(key))
    }
    if (!current.optBoolean("enabled")) current.put("nextRunAt", 0L)
    else if (current.optLong("configUpdatedAt") <= task.configUpdatedAt) {
        current.put("nextRunAt", state.optLong("nextRunAt"))
    }
}

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
        if (pendingRewardDrawState(checkin, state, now) != null) return now
        if (!state.optBoolean("drawPending")) return null
    }
    return task.nextRunAt.coerceAtLeast(1L)
}

internal fun mergeLocalTaskSnapshot(current: JSONObject?, incoming: JSONObject): JSONObject {
    if (current == null || current.optLong("configUpdatedAt") <= incoming.optLong("configUpdatedAt")) {
        return JSONObject(incoming.toString())
    }
    return JSONObject(current.toString()).apply {
        applyLocalTaskExecution(this, localTaskFromJson("", incoming), localTaskRuntimeState(incoming))
    }
}

internal fun rescheduleLocalTasks(store: LocalTaskRepository, now: Long): Int {
    var updated = 0
    for (accountId in store.accountIds()) {
        val checkin = store.runtimeState(accountId, "yeshe_checkin")
        for (task in store.list(accountId)) {
            val state = store.runtimeState(accountId, task.taskType)
            if (nextLocalTaskAt(task, state, checkin, now) == null) continue
            val pendingClaim = if (task.taskType == "yeshe_checkin" &&
                state.optString("claimCompletedDate") != state.optString("lastCheckinDate")
            ) state.optLong("claimDueAt") else 0L
            val next = rescheduledNextRunAt(task.taskType, task.timeOfDay, task.nextRunAt.takeIf { it > 0L }, now, pendingClaim)
            if (next == task.nextRunAt) continue
            store.recordState(accountId, task.taskType, JSONObject().put("nextRunAt", next))
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
    val clearedAt = maxOf(current.optLong("recordsClearedAt"), incoming.optLong("recordsClearedAt"))
    incoming.optJSONArray("records")?.let { current.put("records", it) }
    current.remove("recordsClearedAt")
    clearLocalTaskRecordsBefore(current, clearedAt)
}
