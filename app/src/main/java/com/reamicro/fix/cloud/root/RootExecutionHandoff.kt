package com.reamicro.fix.cloud.root

import org.json.JSONObject

internal class RootExecutionHandoff(
    private val persistMode: (Boolean) -> Unit,
    private val exchange: (String, JSONObject?) -> JSONObject,
    private val restore: (JSONObject) -> Unit,
) {
    fun enable(payload: JSONObject) {
        persistMode(true)
        val snapshot = exchange("enable", payload)
        check(snapshot.optBoolean("enabled")) { "Root 未确认接管，请同步状态或重新切换；应用暂不重复处理以避免双跑" }
        restore(snapshot)
    }

    fun disable() {
        val snapshot = exchange("disable", null)
        check(!snapshot.optBoolean("enabled", true)) { "Root 仍持有执行权，未切换" }
        restore(snapshot)
        persistMode(false)
    }
}
