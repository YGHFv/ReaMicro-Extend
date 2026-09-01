package com.reamicro.fix.notification

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import org.json.JSONArray

private data class CloudTaskResultItem(
    val name: String,
    val quality: String,
    val count: Int,
)

/**
 * 云任务物品通知只给物品名称着色，数量和分隔符沿用系统通知文字颜色。
 * 服务端已经排序并聚合，这里再做一次防御性处理，兼容旧缓存或代理改写后的数据。
 */
fun cloudTaskNotificationText(fallback: String, itemsJson: String): CharSequence {
    val items = parseCloudTaskResultItems(itemsJson)
    if (items.isEmpty()) return fallback
    val text = StringBuilder()
    val spans = mutableListOf<Triple<Int, Int, Int>>()
    items.forEachIndexed { index, item ->
        if (index > 0) text.append("、")
        val start = text.length
        text.append(item.name)
        val end = text.length
        qualityColor(item.quality)?.let { spans += Triple(start, end, it) }
        text.append(" x").append(item.count)
    }
    return SpannableString(text.toString()).apply {
        spans.forEach { (start, end, color) ->
            setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

private fun parseCloudTaskResultItems(itemsJson: String): List<CloudTaskResultItem> {
    if (itemsJson.isBlank()) return emptyList()
    val array = runCatching { JSONArray(itemsJson) }.getOrNull() ?: return emptyList()
    val merged = linkedMapOf<Pair<String, String>, Int>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val name = item.optString("name").trim().take(80)
        if (name.isBlank()) continue
        val quality = normalizeQuality(item.optString("quality"))
        val count = item.optInt("count", 1).coerceAtLeast(1)
        val key = name to quality
        merged[key] = (merged[key] ?: 0) + count
    }
    return merged.map { (key, count) -> CloudTaskResultItem(key.first, key.second, count) }
        .sortedWith(compareByDescending<CloudTaskResultItem> { qualityPriority(it.quality) }.thenBy { it.name })
}

private fun normalizeQuality(quality: String): String = when (quality.trim().uppercase()) {
    "红", "红色", "绝品", "传说" -> "RED"
    "橙", "橙色" -> "ORANGE"
    "金", "金色" -> "GOLD"
    "紫", "紫色", "珍品" -> "PURPLE"
    "蓝", "蓝色", "精品" -> "BLUE"
    "绿", "绿色", "良品" -> "GREEN"
    "灰", "灰色", "普通" -> "GREY"
    else -> quality.trim().uppercase()
}

private fun qualityPriority(quality: String): Int = when (normalizeQuality(quality)) {
    "RED" -> 70
    "ORANGE", "GOLD" -> 65
    "YELLOW" -> 60
    "PURPLE" -> 50
    "BLUE" -> 40
    "GREEN" -> 30
    "GREY", "GRAY" -> 20
    else -> 0
}

private fun qualityColor(quality: String): Int? = when (normalizeQuality(quality)) {
    "RED" -> 0xFFE53935.toInt()
    "ORANGE", "GOLD", "YELLOW" -> 0xFFC77800.toInt()
    "PURPLE" -> 0xFF8E24AA.toInt()
    "BLUE" -> 0xFF1E88E5.toInt()
    "GREEN" -> 0xFF2E7D32.toInt()
    "GREY", "GRAY" -> 0xFF607D8B.toInt()
    else -> null
}

internal fun cloudTaskQualityPriorityForTest(quality: String): Int = qualityPriority(quality)
