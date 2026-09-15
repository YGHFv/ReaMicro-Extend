package com.reamicro.fix.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.reamicro.fix.cloud.local.DAILY_LORE_DETAIL_KEY
import com.reamicro.fix.notification.cloudTaskQualityColor
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class LocalTaskDetailField(val label: String, val value: String, val quality: String = "")

internal fun localTaskRecordDetailFields(taskType: String, detail: JSONObject): List<LocalTaskDetailField> = buildList {
    val lore = detail.optJSONObject(DAILY_LORE_DETAIL_KEY)?.takeIf { taskType == "yeshe_checkin" }
    if (lore != null) {
        fun field(label: String, key: String, quality: String = "", suffix: String = "") {
            val value = lore.detailValue(key)
            if (value.isNotBlank()) add(LocalTaskDetailField(label, value + suffix, quality))
        }
        field("轶闻", "title", lore.detailValue("quality"))
        field("出处", "category")
        val type = lore.detailValue("type")
        if (type.isNotBlank()) add(LocalTaskDetailField("类型", if (type == "STORY") "故事" else type))
        field("等级", "level")
        field("正文", "content")
        val reward = detail.detailValue("奖励").ifBlank { if (lore.optBoolean("claimed")) "已领取" else "待领取" }
        add(LocalTaskDetailField("奖励", reward))
        field("阅历", "exp", suffix = " 点")
        field("彩筹", "gem", suffix = " 枚")
        field("期物", "propName", lore.detailValue("propQuality"))
        for ((label, key) in listOf("开始时间" to "startTime", "结束时间" to "endTime", "领取时间" to "completeTime")) {
            val timestamp = lore.optLong(key)
            if (timestamp > 0L) {
                val millis = if (timestamp < 100_000_000_000L) timestamp * 1000L else timestamp
                val value = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(millis))
                add(LocalTaskDetailField(label, value))
            }
        }
    }
    detail.keys().forEach { key ->
        if (key != DAILY_LORE_DETAIL_KEY && (lore == null || key !in setOf("轶闻", "奖励"))) {
            val value = detail.detailValue(key)
            if (value.isNotBlank()) add(LocalTaskDetailField(key, value))
        }
    }
    if (taskType == "yeshe_checkin" && lore == null && detail.detailValue("轶闻").isNotBlank()) {
        add(LocalTaskDetailField("提示", "旧记录未保存正文与奖励明细；再次查询今日轶闻后，新记录会显示完整详情。"))
    }
}

internal fun localTaskRecordDetailText(header: String, fields: List<LocalTaskDetailField>): CharSequence =
    SpannableStringBuilder(header).apply {
        if (fields.isNotEmpty()) append("\n\n")
        fields.forEachIndexed { index, field ->
            if (index > 0) append("\n")
            append(field.label).append("：")
            if (field.label == "正文") append("\n")
            val start = length
            append(field.value)
            cloudTaskQualityColor(field.quality)?.let { color ->
                setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

private fun JSONObject.detailValue(key: String): String = if (isNull(key)) "" else optString(key)
