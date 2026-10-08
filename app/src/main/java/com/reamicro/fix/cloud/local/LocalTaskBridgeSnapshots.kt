package com.reamicro.fix.cloud.local

import org.json.JSONObject

internal fun applyLocalTaskSyncReceipt(store: LocalTaskStore, operation: String, raw: String) {
    if (operation != LocalTaskBridgeProtocol.SYNC) return
    val payload = JSONObject(raw)
    val accounts = payload.getJSONObject("accounts")
    accounts.keys().forEach { accounts.getJSONObject(it).remove("records") }
    store.applySnapshot(payload)
}

internal fun moduleTaskRecords(raw: String?, accountId: String): List<LocalTaskRecord> {
    if (raw == null || accountId.isBlank()) return emptyList()
    val records = JSONObject(raw).optJSONObject("accounts")?.optJSONObject(accountId)?.optJSONArray("records")
        ?: return emptyList()
    return (0 until records.length()).mapNotNull { index ->
        val item = records.optJSONObject(index) ?: return@mapNotNull null
        LocalTaskRecord(item.optLong("at"), item.optString("taskType"), item.optString("result"),
            item.optString("message"), item.optString("detail"))
    }
}

internal fun mergeLocalTaskRecordViews(host: List<LocalTaskRecord>, module: List<LocalTaskRecord>, limit: Int): List<LocalTaskRecord> =
    (host + module).sortedByDescending { it.at }.take(limit.coerceAtLeast(0))
