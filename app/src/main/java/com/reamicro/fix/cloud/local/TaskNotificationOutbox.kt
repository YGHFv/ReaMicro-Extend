package com.reamicro.fix.cloud.local

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun appendTaskNotification(
    root: JSONObject, accountId: String, record: LocalTaskRecord,
    accountLabel: String = localTaskAccount(accountId, root).label,
    accountGeneration: String = root.optString("accountGeneration"),
) {
    val pending = root.optJSONArray("notifications") ?: JSONArray()
    val items = runCatching { JSONObject(record.detail.ifBlank { "{}" }).optJSONArray(CloudTaskLocalRunner.KEY_REWARD_ITEMS)?.toString().orEmpty() }.getOrDefault("")
    pending.put(JSONObject().put("id", UUID.randomUUID().toString()).put("accountId", accountId)
        .put("accountGeneration", accountGeneration)
        .put("accountLabel", accountLabel)
        .put("taskType", record.taskType).put("result", record.result).put("message", record.message).put("items", items))
    root.put("notifications", pending)
}
