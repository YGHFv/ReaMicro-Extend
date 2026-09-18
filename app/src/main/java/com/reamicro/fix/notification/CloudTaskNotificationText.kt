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
 *
 * 正文（fallback）里已经写了物品名时就地着色：签到这类消息会把奖励明细写进正文，
 * 再另起一段列表会让人以为发了两条结果。正文没提到物品时，才退回“物品列表”。
 */
fun cloudTaskNotificationText(fallback: String, itemsJson: String): CharSequence {
    val items = parseCloudTaskResultItems(itemsJson)
    if (items.isEmpty()) return fallback
    colorCloudTaskItemsInText(fallback, items)?.let { return it }
    return cloudTaskItemsList(items)
}

/** 与通知着色共用同一套聚合、排序和品质别名的纯文本摘要，例如“端砚 x1、花笺 x2”。 */
fun cloudTaskItemsSummary(itemsJson: String): String =
    parseCloudTaskResultItems(itemsJson).joinToString("、") { "${it.name} x${it.count}" }

/**
 * 在正文里就地给物品名着色。一个物品名都没匹配上时返回 null，交给调用方退回列表渲染。
 * 只给能识别品质的物品上色，阅历/彩筹这类没有品质的保持系统默认色。
 */
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

/**
 * 品质文案 → 游戏内部枚举。
 *
 * 游戏里只有这五档：GREY / GREEN / BLUE / RED / LIMIT（宿主 `LoreCardKt.getQualityColor`
 * 与 `DailyLoreSheetKt.toDailyLoreTitleColor` 的 when 分支完全一致），
 * `GOLD` 是每日轶闻/祈愿卡面额外用到的一档（`rememberDailyLoreChipColors`、`wangyanQualityColor`）。
 * 服务端下发的就是这几个英文名，中文别名只是给旧数据兜底，不是另立一套档位。
 */
private fun normalizeQuality(quality: String): String = when (quality.trim().uppercase()) {
    "红", "红色", "绝品", "传说" -> "RED"
    "橙", "橙色" -> "LIMIT"
    "金", "金色" -> "GOLD"
    "蓝", "蓝色", "精品" -> "BLUE"
    "绿", "绿色", "良品" -> "GREEN"
    "灰", "灰色", "普通" -> "GREY"
    else -> quality.trim().uppercase()
}

/** 品质权重：数值越大品质越高，顺序与宿主 `marketQualityRank` 一致（GOLD 插在 LIMIT 与 RED 之间）。 */
private fun qualityPriority(quality: String): Int = when (normalizeQuality(quality)) {
    "LIMIT" -> 60
    "GOLD" -> 50
    "RED" -> 40
    "BLUE" -> 30
    "GREEN" -> 20
    "GREY", "GRAY" -> 10
    else -> 0
}

/**
 * 品质配色：直接取宿主 `LoreCardKt.getQualityColor` 的常量，别再自己调色。
 *
 * 期物列表、通知里的奖励明细都按这套上色，颜色与游戏内看板/当铺/寄售里的一致。
 */
internal fun cloudTaskQualityColor(quality: String): Int? = when (normalizeQuality(quality)) {
    "LIMIT" -> 0xFFFF9800.toInt()
    "GOLD" -> 0xFFE0B84E.toInt()
    "RED" -> 0xFFF44336.toInt()
    "BLUE" -> 0xFF2196F3.toInt()
    "GREEN" -> 0xFF4CAF50.toInt()
    "GREY", "GRAY" -> 0xFF9E9E9E.toInt()
    else -> null
}

/** 品质权重：通知聚合与期物配置页排序共用同一套，数值越大品质越高。 */
internal fun cloudTaskQualityPriority(quality: String): Int = qualityPriority(quality)
