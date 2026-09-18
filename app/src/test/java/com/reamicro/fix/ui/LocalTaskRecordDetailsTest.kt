package com.reamicro.fix.ui

import com.reamicro.fix.cloud.local.DAILY_LORE_DETAIL_KEY
import com.reamicro.fix.cloud.local.dailyLoreSnapshot
import com.reamicro.fix.notification.cloudTaskQualityColor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTaskRecordDetailsTest {
    private val content = "吾友蒋焘，少负才，以文章知名。\n所著有《东璧遗橐》，其文皆艳语，虽老儒不能及。".repeat(80)

    private fun lore() = JSONObject()
        .put("id", "268156").put("loreId", "5").put("title", "蒋焘灵").put("content", content)
        .put("category", "苹野纂闻").put("type", "STORY").put("quality", "GREEN").put("level", "1")
        .put("exp", "4").put("gem", "3").put("propId", "16").put("propName", "端砚").put("propQuality", "BLUE")
        .put("startTime", "1789401601").put("endTime", "1789430401").put("completeTime", "1789445877")
        .put("claimed", true).put("isFinish", true)

    private fun fields(data: JSONObject = lore()) = localTaskRecordDetailFields(
        "yeshe_checkin", JSONObject().put(DAILY_LORE_DETAIL_KEY, dailyLoreSnapshot(data, true)),
    )

    @Test
    fun `long lore content and all rewards are retained without truncation`() {
        val rendered = fields().associate { it.label to it.value }
        assertEquals(content, rendered["正文"])
        assertEquals("蒋焘灵", rendered["轶闻"])
        assertEquals("苹野纂闻", rendered["出处"])
        assertEquals("故事", rendered["类型"])
        assertEquals("1", rendered["等级"])
        assertEquals("已领取", rendered["奖励"])
        assertEquals("4 点", rendered["阅历"])
        assertEquals("3 枚", rendered["彩筹"])
        assertEquals("端砚", rendered["期物"])
    }

    @Test
    fun `lore and prop use their independent quality colors`() {
        val rendered = fields().associateBy { it.label }
        assertEquals("GREEN", rendered.getValue("轶闻").quality)
        assertEquals("BLUE", rendered.getValue("期物").quality)
        // 取宿主 LoreCardKt.getQualityColor 的真实常量，别再自己调色。
        assertEquals(0xFF4CAF50.toInt(), cloudTaskQualityColor(rendered.getValue("轶闻").quality))
        assertEquals(0xFF2196F3.toInt(), cloudTaskQualityColor(rendered.getValue("期物").quality))
        assertEquals("", rendered.getValue("正文").quality)
        assertNull(cloudTaskQualityColor("UNKNOWN"))
    }

    @Test
    fun `seconds and milliseconds render the same Shanghai times`() {
        val seconds = fields().filter { it.label.endsWith("时间") }
        val millis = lore()
        for (key in listOf("startTime", "endTime", "completeTime")) millis.put(key, millis.getLong(key) * 1000L)
        assertEquals(seconds, fields(millis).filter { it.label.endsWith("时间") })
        assertEquals("2026-09-15 00:00:01", seconds.first { it.label == "开始时间" }.value)
        assertEquals("2026-09-15 08:00:01", seconds.first { it.label == "结束时间" }.value)
    }

    @Test
    fun `optional null fields do not render null or invented rewards`() {
        val rendered = fields(JSONObject().put("title", "测试").put("quality", JSONObject.NULL)
            .put("propName", JSONObject.NULL).put("exp", JSONObject.NULL).put("endTime", 0L))
        assertEquals(listOf("轶闻", "奖励"), rendered.map { it.label })
        assertTrue(rendered.none { it.value == "null" })
    }

    @Test
    fun `legacy records keep their actual fields and explain missing content`() {
        val rendered = localTaskRecordDetailFields("yeshe_checkin", JSONObject().put("轶闻", "蒋焘灵").put("奖励", "已领取"))
        assertEquals("蒋焘灵", rendered.first { it.label == "轶闻" }.value)
        assertTrue(rendered.any { it.label == "提示" && it.value.contains("旧记录") })
        assertFalse(rendered.any { it.label == "正文" })
    }

    @Test
    fun `merchant details remain generic and do not get lore metadata`() {
        val rendered = localTaskRecordDetailFields("traveling_merchant", JSONObject().put("事件", "布帛零售").put("收益", "+28"))
        assertEquals(mapOf("事件" to "布帛零售", "收益" to "+28"), rendered.associate { it.label to it.value })
        assertTrue(rendered.all { it.quality.isEmpty() })
    }
}
