package com.reamicro.fix.cloud.local

import org.json.JSONObject

internal const val DAILY_LORE_DETAIL_KEY = "lore"

internal fun dailyLoreSnapshot(data: JSONObject, claimed: Boolean): JSONObject {
    val snapshot = JSONObject()
    for (key in DAILY_LORE_FIELDS) {
        if (data.has(key) && !data.isNull(key)) snapshot.put(key, data.get(key))
    }
    return snapshot.put("claimed", claimed)
}

private val DAILY_LORE_FIELDS = listOf(
    "id", "loreId", "title", "content", "category", "type", "quality", "level",
    "exp", "gem", "propId", "propName", "propQuality",
    "startTime", "endTime", "completeTime", "isFinish",
)
