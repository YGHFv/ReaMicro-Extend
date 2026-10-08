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

fun cloudTaskNotificationText(fallback: String, itemsJson: String): CharSequence {
    val items = parseCloudTaskResultItems(itemsJson)
    if (items.isEmpty()) return fallback
    colorCloudTaskItemsInText(fallback, items)?.let { return it }
    return cloudTaskItemsList(items)
}

fun cloudTaskItemsSummary(itemsJson: String): String =
    parseCloudTaskResultItems(itemsJson).joinToString("、") { "${it.name} x${it.count}" }

private fun colorCloudTaskItemsInText(text: String, items: List<CloudTaskResultItem>): SpannableString? {
    if (text.isBlank()) return null
    val span = SpannableString(text)
    var matched = false
    items.forEach { item ->
        var from = 0
        while (true) {
            val at = text.indexOf(item.name, from)
            if (at < 0) break
            matched = true
            cloudTaskQualityColor(item.quality)?.let { color ->
                span.setSpan(ForegroundColorSpan(color), at, at + item.name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            from = at + item.name.length
        }
    }
    return if (matched) span else null
}

private fun cloudTaskItemsList(items: List<CloudTaskResultItem>): CharSequence {
    val text = StringBuilder()
    val spans = mutableListOf<Triple<Int, Int, Int>>()
    items.forEachIndexed { index, item ->
        if (index > 0) text.append("、")
        val start = text.length
        text.append(item.name)
        val end = text.length
        cloudTaskQualityColor(item.quality)?.let { spans += Triple(start, end, it) }
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
    "橙", "橙色" -> "LIMIT"
    "金", "金色" -> "GOLD"
    "蓝", "蓝色", "精品" -> "BLUE"
    "绿", "绿色", "良品" -> "GREEN"
    "灰", "灰色", "普通" -> "GREY"
    else -> quality.trim().uppercase()
}

private fun qualityPriority(quality: String): Int = when (normalizeQuality(quality)) {
    "LIMIT" -> 60
    "GOLD" -> 50
    "RED" -> 40
    "BLUE" -> 30
    "GREEN" -> 20
    "GREY", "GRAY" -> 10
    else -> 0
}

internal fun cloudTaskQualityColor(quality: String): Int? = when (normalizeQuality(quality)) {
    "LIMIT" -> 0xFFFF9800.toInt()
    "GOLD" -> 0xFFE0B84E.toInt()
    "RED" -> 0xFFF44336.toInt()
    "BLUE" -> 0xFF2196F3.toInt()
    "GREEN" -> 0xFF4CAF50.toInt()
    "GREY", "GRAY" -> 0xFF9E9E9E.toInt()
    else -> null
}

internal fun cloudTaskQualityPriority(quality: String): Int = qualityPriority(quality)
