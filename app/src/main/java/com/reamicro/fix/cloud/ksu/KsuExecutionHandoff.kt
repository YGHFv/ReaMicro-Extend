package com.reamicro.fix.cloud.ksu

import org.json.JSONObject

internal class KsuExecutionHandoff(
    private val persistMode: (Boolean) -> Unit,
    private val exchange: (String, JSONObject?) -> JSONObject,
    private val restore: (JSONObject) -> Unit,
) {
    fun enable(payload: JSONObject) {
        persistMode(true)
        val snapshot = exchange("enable", payload)
        check(snapshot.optBoolean("enabled")) { "KSU 未确认接管，请同步状态或重新切换；Android 暂不执行以避免双跑" }
        restore(snapshot)
    }

    fun disable() {
        val snapshot = exchange("disable", null)
        check(!snapshot.optBoolean("enabled", true)) { "KSU 仍持有执行权，未切换" }
        restore(snapshot)
        persistMode(false)
    }
}
