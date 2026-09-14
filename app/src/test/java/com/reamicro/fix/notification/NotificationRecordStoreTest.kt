package com.reamicro.fix.notification

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知投递记录的裁剪与解析。
 *
 * 背景：模块进程常年没有界面，通知到底有没有发出去，设备侧唯一能回查的就是这份记录。
 * 所以记录既不能悄悄丢（裁剪算错），也不能被一条脏数据毁掉整页（解析没兜住）。
 */
class NotificationRecordStoreTest {

    private fun recordJson(at: Long, title: String = "任务完成"): JSONObject = JSONObject()
        .put("at", at)
        .put("title", title)
        .put("text", "正文")
        .put("result", "success")
        .put("source", "receiver")
        .put("delivered", true)
        .put("detail", "")

    @Test
    fun `未超上限时原样保留`() {
        val array = JSONArray().apply { put(recordJson(1L)); put(recordJson(2L)) }
        val trimmed = trimNotificationRecords(array)
        assertEquals(2, trimmed.length())
    }

    @Test
    fun `正好等于上限时不丢记录`() {
        val max = NotificationRecordStore.MAX_RECORDS
        val array = JSONArray().apply { repeat(max) { put(recordJson(it.toLong())) } }
        assertEquals(max, trimNotificationRecords(array).length())
    }

    @Test
    fun `超出上限时只保留最新的那些`() {
        val max = NotificationRecordStore.MAX_RECORDS
        val extra = 5
        val array = JSONArray().apply { repeat(max + extra) { put(recordJson(it.toLong())) } }
        val trimmed = trimNotificationRecords(array)
        assertEquals(max, trimmed.length())
        // 丢的必须是最旧的：首条应是被裁掉 extra 条之后的那条。
        assertEquals(extra.toLong(), trimmed.optJSONObject(0)?.optLong("at"))
        assertEquals((max + extra - 1).toLong(), trimmed.optJSONObject(max - 1)?.optLong("at"))
    }

    @Test
    fun `空数组裁剪后仍为空`() {
        assertEquals(0, trimNotificationRecords(JSONArray()).length())
    }

    @Test
    fun `解析后按时间倒序`() {
        val raw = JSONArray().apply {
            put(recordJson(100L, "旧"))
            put(recordJson(300L, "新"))
            put(recordJson(200L, "中"))
        }.toString()
        assertEquals(listOf("新", "中", "旧"), parseNotificationRecords(raw).map { it.title })
    }

    @Test
    fun `坏数据降级为空而不是抛异常`() {
        // 记录页是排查问题用的，不能因为一条脏数据就整页打不开。
        assertTrue(parseNotificationRecords("not json at all").isEmpty())
        assertTrue(parseNotificationRecords("").isEmpty())
    }

    @Test
    fun `投递失败的记录保留失败原因`() {
        val raw = JSONArray().apply {
            put(
                JSONObject()
                    .put("at", 1L)
                    .put("title", "野社签到异常")
                    .put("text", "网络不可达")
                    .put("result", "failed")
                    .put("source", "receiver")
                    .put("delivered", false)
                    .put("detail", "未授予通知权限"),
            )
        }.toString()
        val record = parseNotificationRecords(raw).single()
        assertFalse(record.delivered)
        assertEquals("未授予通知权限", record.detail)
        assertEquals("failed", record.result)
    }
}
