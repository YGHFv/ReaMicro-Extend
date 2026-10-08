package com.reamicro.fix.notification

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class NotificationRecord(
    val at: Long,
    val title: String,
    val text: String,
    val result: String,
    val source: String,
    val delivered: Boolean,
    val detail: String = "",
)

class NotificationRecordStore(private val contextProvider: () -> Context?) {

    fun append(record: NotificationRecord) {
        val prefs = prefs() ?: return
        runCatching {
            val array = JSONArray(prefs.getString(KEY_RECORDS, null) ?: "[]")
            array.put(record.toJson())
            prefs.edit().putString(KEY_RECORDS, trimNotificationRecords(array).toString()).commit()
        }
    }

    fun list(): List<NotificationRecord> {
        val raw = prefs()?.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching { parseNotificationRecords(raw) }.getOrDefault(emptyList())
    }

    fun clear() {
        prefs()?.edit()?.remove(KEY_RECORDS)?.commit()
    }

    fun size(): Int = list().size

    private fun prefs(): SharedPreferences? =
        contextProvider()?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "reamicro_notification_records"
        private const val KEY_RECORDS = "records"

        internal const val MAX_RECORDS = 100
    }
}

internal fun trimNotificationRecords(array: JSONArray): JSONArray {
    val trimmed = JSONArray()
    val start = (array.length() - NotificationRecordStore.MAX_RECORDS).coerceAtLeast(0)
    for (index in start until array.length()) {
        array.opt(index)?.let(trimmed::put)
    }
    return trimmed
}

internal fun parseNotificationRecords(raw: String): List<NotificationRecord> =
    runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::parseNotificationRecord)
        }.sortedByDescending { it.at }
    }.getOrDefault(emptyList())

private fun parseNotificationRecord(json: JSONObject): NotificationRecord = NotificationRecord(
    at = json.optLong("at", 0L),
    title = json.optString("title"),
    text = json.optString("text"),
    result = json.optString("result"),
    source = json.optString("source"),
    delivered = json.optBoolean("delivered", false),
    detail = json.optString("detail"),
)

private fun NotificationRecord.toJson(): JSONObject = JSONObject()
    .put("at", at)
    .put("title", title)
    .put("text", text)
    .put("result", result)
    .put("source", source)
    .put("delivered", delivered)
    .put("detail", detail)
