package com.reamicro.fix.cloud.local

import org.json.JSONArray
import org.json.JSONObject

/** Account backup data never contains the device's credential ciphertext or notification outbox. */
internal object LocalTaskAccountBundle {
    private const val TYPE = "local_task_account"
    private const val FORMAT = 1
    const val RECORDS_IMPORTED_AT = "recordsImportedAt"
    private val taskName = Regex("[a-z0-9_]{1,64}")
    private val recordKeys = setOf("at", "taskType", "result", "message", "detail")

    fun export(accountId: String, stored: JSONObject?): JSONObject {
        requireAccountId(accountId)
        val account = portableAccount(stored ?: JSONObject().put("tasks", JSONObject()).put("records", JSONArray()))
        return JSONObject().put("type", TYPE).put("format", FORMAT).put("accountId", accountId)
            .put("account", account)
    }

    fun read(payload: JSONObject, accountId: String): JSONObject {
        requireAccountId(accountId)
        require(payload.optString("type") == TYPE && payload.optInt("format", -1) == FORMAT) {
            "数据包本地任务格式不支持"
        }
        require(payload.opt("accountId")?.toString() == accountId) { "数据包本地任务与账号不一致" }
        val account = payload.getJSONObject("account")
        require(account.opt("tasks") is JSONObject && account.opt("records") is JSONArray) {
            "数据包本地任务内容不完整"
        }
        return portableAccount(account)
    }

    /** Restore is an explicit configuration change, with revisions newer than either saved copy. */
    fun restore(previous: JSONObject?, imported: JSONObject, now: Long): JSONObject {
        require(now > 0L) { "本地任务恢复时间无效" }
        val restored = previous?.let { JSONObject(it.toString()) } ?: JSONObject()
        val existingTasks = previous?.optJSONObject("tasks") ?: JSONObject()
        val incomingTasks = imported.getJSONObject("tasks")
        val tasks = JSONObject()
        val types = (incomingTasks.keys().asSequence() + existingTasks.keys().asSequence()).toSet()
        for (type in types) {
            val existing = existingTasks.optJSONObject(type)
            val incoming = incomingTasks.optJSONObject(type)
            val task = portableTask(type, incoming ?: existing ?: continue)
            if (incoming == null) task.put("enabled", false)
            task.put(LocalTaskStore.KEY_CONFIG_UPDATED_AT, nextRevision(now,
                existing?.optLong(LocalTaskStore.KEY_CONFIG_UPDATED_AT) ?: 0L,
                incoming?.optLong(LocalTaskStore.KEY_CONFIG_UPDATED_AT) ?: 0L))
            if (!task.optBoolean("enabled")) task.put(LocalTaskStore.KEY_NEXT_RUN_AT, 0L)
            else if (!task.has(LocalTaskStore.KEY_NEXT_RUN_AT)) LocalTaskRuntimeMerge.schedule(task, type, now)
            task.put(LocalTaskRuntimeMerge.UPDATED_AT, nextRevision(now,
                existing?.optLong(LocalTaskRuntimeMerge.UPDATED_AT) ?: 0L,
                existing?.optLong(LocalTaskStore.KEY_LAST_RUN_AT) ?: 0L,
                task.optLong(LocalTaskRuntimeMerge.UPDATED_AT), task.optLong(LocalTaskStore.KEY_LAST_RUN_AT)))
            tasks.put(type, task)
        }
        restored.put("tasks", tasks)
        restored.put("records", JSONArray(imported.getJSONArray("records").toString()))
        restored.put("recordsClearedAt", imported.optLong("recordsClearedAt"))
        restored.remove("recordDeletions")
        imported.optJSONObject("recordDeletions")?.let { restored.put("recordDeletions", JSONObject(it.toString())) }
        // Older module history and clears cannot undo the user's explicit backup restoration.
        restored.put(RECORDS_IMPORTED_AT, now)
        return restored
    }

    private fun portableAccount(source: JSONObject): JSONObject {
        val sourceTasks = if (source.has("tasks")) source.getJSONObject("tasks") else JSONObject()
        require(sourceTasks.length() <= 128) { "数据包本地任务过多" }
        val tasks = JSONObject()
        sourceTasks.keys().forEach { type -> tasks.put(type, portableTask(type, sourceTasks.getJSONObject(type))) }
        val sourceRecords = if (source.has("records")) source.getJSONArray("records") else JSONArray()
        require(sourceRecords.length() <= LocalTaskStore.MAX_RECORDS) { "数据包本地任务记录过多" }
        val records = JSONArray()
        for (index in 0 until sourceRecords.length()) {
            val record = sourceRecords.getJSONObject(index)
            require(record.opt("at") is Number && record.optLong("at") >= 0L) { "数据包本地任务记录无效" }
            val copied = JSONObject().put("at", record.getLong("at"))
            for (key in recordKeys - "at") {
                require(!record.has(key) || record.opt(key) is String) { "数据包本地任务记录无效" }
                copied.put(key, record.optString(key))
            }
            records.put(copied)
        }
        val clearedAt = source.optLong("recordsClearedAt")
        require(clearedAt >= 0L) { "数据包本地任务记录状态无效" }
        return JSONObject().put("tasks", tasks).put("records", records).put("recordsClearedAt", clearedAt).apply {
            source.optJSONObject("recordDeletions")?.let { deletions ->
                val copied = JSONObject()
                deletions.keys().forEach { identity ->
                    require(identity.matches(Regex("[0-9a-f]{64}")) && deletions.optLong(identity) > 0L) {
                        "数据包本地任务记录删除状态无效"
                    }
                    copied.put(identity, deletions.getLong(identity))
                }
                put("recordDeletions", copied)
            }
        }
    }

    private fun portableTask(type: String, source: JSONObject): JSONObject {
        require(taskName.matches(type) && (!source.has("taskType") || source.optString("taskType") == type)) {
            "数据包本地任务类型无效"
        }
        require(!source.has("enabled") || source.opt("enabled") is Boolean) { "数据包本地任务启用状态无效" }
        val keys = LocalTaskStore.CONFIG_KEYS + LocalTaskStore.RUNTIME_STATE_KEYS + LocalTaskStore.KEY_CONFIG_UPDATED_AT
        return JSONObject().put("taskType", type).apply {
            for (key in keys) if (source.has(key)) put(key, portableValue(source.get(key)))
        }
    }

    private fun portableValue(value: Any): Any = when (value) {
        is JSONObject -> JSONObject().apply {
            value.keys().asSequence().filterNot { it in setOf("token", "notifications", "outbox") }.forEach { key ->
                put(key, portableValue(value.get(key)))
            }
        }
        is JSONArray -> JSONArray().apply {
            for (index in 0 until value.length()) put(portableValue(value.get(index)))
        }
        else -> value
    }

    private fun nextRevision(now: Long, vararg previous: Long): Long {
        val latest = maxOf(now, previous.maxOrNull() ?: 0L)
        require(latest < Long.MAX_VALUE) { "本地任务状态版本无效" }
        return latest + 1L
    }

    private fun requireAccountId(accountId: String) {
        require(accountId.toLongOrNull()?.let { it > 0L && it.toString() == accountId } == true) {
            "本地任务账号无效"
        }
    }
}

/** A failed SharedPreferences commit can already have changed its in-memory value. */
internal fun commitLocalTaskAccountRestore(
    previous: String?, replacement: String, read: () -> String?, write: (String?) -> Boolean,
) {
    try {
        check(write(replacement)) { "本地任务恢复保存失败" }
    } catch (error: Throwable) {
        runCatching {
            if (read() == replacement) check(write(previous)) { "本地任务恢复回滚保存失败" }
        }.onFailure(error::addSuppressed)
        throw error
    }
}

/** Keep restored history and accept only module records created after that restoration. */
internal fun mergeRestoredLocalTaskRecords(
    baseline: JSONArray, incoming: JSONArray, restoredAt: Long,
): JSONArray {
    val kept = mutableListOf<JSONObject>()
    val currentCounts = linkedMapOf<String, Int>()
    for (index in 0 until baseline.length()) {
        val record = baseline.optJSONObject(index) ?: continue
        val identity = localTaskRecordIdentity(localTaskRecordFromJson(record))
        currentCounts[identity] = currentCounts.getOrDefault(identity, 0) + 1
        kept += record
    }
    val incomingCounts = linkedMapOf<String, Int>()
    for (index in 0 until incoming.length()) {
        val record = incoming.optJSONObject(index)?.takeIf { it.optLong("at") >= restoredAt } ?: continue
        val identity = localTaskRecordIdentity(localTaskRecordFromJson(record))
        val count = incomingCounts.getOrDefault(identity, 0) + 1
        incomingCounts[identity] = count
        if (count > currentCounts.getOrDefault(identity, 0)) kept += record
    }
    return JSONArray().apply {
        kept.sortedBy { it.optLong("at") }.takeLast(LocalTaskStore.MAX_RECORDS).forEach { put(it) }
    }
}
