package com.reamicro.fix.cloud.local

import org.json.JSONArray
import org.json.JSONObject

internal const val DAILY_LORE_DETAIL_KEY = "lore"

internal fun dailyLoreSnapshot(data: JSONObject, claimed: Boolean): JSONObject {
    val snapshot = JSONObject()
    for (key in DAILY_LORE_FIELDS) {
        if (data.has(key) && !data.isNull(key)) snapshot.put(key, data.get(key))
    }
    return snapshot.put("claimed", claimed)
}

/**
 * 每日轶闻的结构化奖励明细。
 *
 * 形状与服务端 `daily_lore_reward_items` 一致（name/quality/count），这样通知着色、通知摘要
 * 和记录详情页可以共用同一套渲染：阅历、彩筹没有品质，期物按 propQuality 上色。
 */
internal fun dailyLoreRewardItems(data: JSONObject): JSONArray {
    val rewards = JSONArray()
    val exp = data.optInt("exp", 0)
    if (exp > 0) rewards.put(rewardItem("阅历", "", exp))
    val gem = data.optInt("gem", 0)
    if (gem > 0) rewards.put(rewardItem("彩筹", "", gem))
    val propName = data.optString("propName").trim()
    if (propName.isNotEmpty()) rewards.put(rewardItem(propName, data.optString("propQuality").trim(), 1))
    return rewards
}

private fun rewardItem(name: String, quality: String, count: Int): JSONObject =
    JSONObject().put("name", name).put("quality", quality).put("count", count)

private val DAILY_LORE_FIELDS = listOf(
    "id", "loreId", "title", "content", "category", "type", "quality", "level",
    "exp", "gem", "propId", "propName", "propQuality",
    "startTime", "endTime", "completeTime", "isFinish",
)
