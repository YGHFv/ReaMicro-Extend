package com.reamicro.fix.notification

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 一条通知投递记录。成功与失败都记，方便在模块主界面回查「为什么没收到通知」。 */
data class NotificationRecord(
    val at: Long,
    val title: String,
    val text: String,
    val result: String,
    val source: String,
    val delivered: Boolean,
    val detail: String = "",
)

/**
 * 通知投递记录的持久化。
 *
 * 模块进程常年没有界面，通知有没有发出去在设备上完全看不出来；而服务器只知道自己有没有收到
 * 回执。把每次投递（含失败原因）落在模块自己的 prefs 里，主界面就能直接回答
 * 「这条消息到底发出去没有」——排查「服务器显示已发送、设备没有」时这是第一手证据。
 *
 * 明文存储即可：这里只有标题/正文/结果，不含凭据。
 */
class NotificationRecordStore(private val contextProvider: () -> Context?) {

    fun append(record: NotificationRecord) {
        val prefs = prefs() ?: return
        runCatching {
            val array = JSONArray(prefs.getString(KEY_RECORDS, null) ?: "[]")
            array.put(record.toJson())
            prefs.edit().putString(KEY_RECORDS, trimNotificationRecords(array).toString()).commit()
        }
    }

    /** 读取全部记录，最新在前。 */
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

        /** 只保留最近若干条，避免 SharedPreferences 无限增长。 */
        internal const val MAX_RECORDS = 100
    }
}

/**
 * 裁剪到最近 [NotificationRecordStore.MAX_RECORDS] 条并转成新数组。
 *
 * 抽成纯函数是为了能单测：数组裁剪的边界（正好等于上限、超出多条、空数组）最容易写错，
 * 而这段逻辑一旦写错就是记录悄悄丢失或无限增长。
 */
internal fun trimNotificationRecords(array: JSONArray): JSONArray {
    val trimmed = JSONArray()
    val start = (array.length() - NotificationRecordStore.MAX_RECORDS).coerceAtLeast(0)
    for (index in start until array.length()) {
        array.opt(index)?.let(trimmed::put)
    }
    return trimmed
}

/** 解析记录并按时间倒序（最新在前）。坏数据整体降级为空，不让一条脏记录毁掉整页。 */
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
