package com.reamicro.fix.cloud.local

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest

private const val RECORD_DELETIONS = "recordDeletions"

/** Transfer only deletion counts; the executor keeps its newer execution history. */
internal fun applyLocalTaskRecordDeletionCounts(current: JSONObject, incoming: JSONObject) {
    val counts = incoming.optJSONObject(RECORD_DELETIONS) ?: return
    applyLocalTaskRecordDeletionsSnapshot(current, JSONObject().put(RECORD_DELETIONS, counts))
}

/** Length-prefixed fields keep separators and identical timestamps unambiguous. */
internal fun localTaskRecordIdentity(record: LocalTaskRecord): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(record.at).array())
    for (field in listOf(record.taskType, record.result, record.message, record.detail)) {
        val bytes = field.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    val hex = "0123456789abcdef"
    return buildString(64) {
        for (byte in digest.digest()) {
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0xf])
        }
    }
}

internal fun localTaskRecordFromJson(item: JSONObject): LocalTaskRecord = LocalTaskRecord(
    at = item.optLong("at", 0L),
    taskType = item.optString("taskType"),
    result = item.optString("result"),
    message = item.optString("message"),
    detail = item.optString("detail"),
)

/** Only actual occurrences removed from this account add to its deletion count. */
internal fun deleteSelectedLocalTaskRecords(account: JSONObject, selected: List<LocalTaskRecord>): Int {
    if (selected.isEmpty()) return 0
    val records = account.optJSONArray("records") ?: return 0
    val requested = selected.groupingBy(::localTaskRecordIdentity).eachCount().toMutableMap()
    val deleted = linkedMapOf<String, Int>()
    val remaining = JSONArray()
    for (index in 0 until records.length()) {
        val item = records.opt(index)
        val identity = (item as? JSONObject)?.let { localTaskRecordIdentity(localTaskRecordFromJson(it)) }
        val count = identity?.let { requested[it] } ?: 0
        if (identity != null && count > 0) {
            requested[identity] = count - 1
            deleted[identity] = deleted.getOrDefault(identity, 0) + 1
        } else {
            remaining.put(item)
        }
    }
    if (deleted.isEmpty()) return 0
    val counts = recordDeletionCounts(account).toMutableMap()
    for ((identity, removed) in deleted) {
        val previous = counts.getOrDefault(identity, 0L)
        counts[identity] = if (previous > Long.MAX_VALUE - removed) Long.MAX_VALUE else previous + removed
    }
    account.put("records", remaining)
    account.put(RECORD_DELETIONS, JSONObject(counts))
    return deleted.values.sum()
}

/**
 * Raw Root/host snapshots have no deletion counts and need all local tombstones.
 * A copied local snapshot already removed its own count, so only the difference
 * is applied. A snapshot without records keeps the current, already filtered array.
 */
internal fun applyLocalTaskRecordDeletionsSnapshot(current: JSONObject, incoming: JSONObject) {
    val currentCounts = recordDeletionCounts(current)
    val incomingCounts = recordDeletionCounts(incoming)
    val merged = currentCounts.toMutableMap()
    for ((identity, count) in incomingCounts) {
        merged[identity] = maxOf(merged.getOrDefault(identity, 0L), count)
    }
    val incomingRecords = incoming.optJSONArray("records")
    val records = incomingRecords ?: current.optJSONArray("records")
    val sourceCounts = if (incomingRecords != null) incomingCounts else currentCounts
    val pending = merged.mapValues { (identity, count) -> count - sourceCounts.getOrDefault(identity, 0L) }
        .filterValues { it > 0L }.toMutableMap()
    if (records != null && (incomingRecords != null || pending.isNotEmpty())) {
        val remaining = JSONArray()
        for (index in 0 until records.length()) {
            val item = records.opt(index)
            val identity = (item as? JSONObject)?.let { localTaskRecordIdentity(localTaskRecordFromJson(it)) }
            val count = identity?.let { pending[it] } ?: 0L
            if (identity != null && count > 0L) pending[identity] = count - 1L
            else remaining.put(item)
        }
        current.put("records", remaining)
    }
    if (merged.isNotEmpty()) current.put(RECORD_DELETIONS, JSONObject(merged))
}

private fun recordDeletionCounts(account: JSONObject): Map<String, Long> {
    val stored = account.optJSONObject(RECORD_DELETIONS) ?: return emptyMap()
    return buildMap {
        stored.keys().forEach { identity ->
            if (identity.length == 64 && identity.all { it in '0'..'9' || it in 'a'..'f' }) {
                val count = stored.optLong(identity)
                if (count > 0L) put(identity, count)
            }
        }
    }
}
