package com.reamicro.fix.cloud.root

/** 接口接受请求与业务联网成功分开记录；协议不包含地址、凭据或异常 message。 */
internal data class RootUidNetworkRestoreResult(
    val appNet: String = "unavailable",
    val chain8: String = "unavailable",
    val chain9: String = "unavailable",
    val appNetError: String = "",
    val chain8Error: String = "",
    val chain9Error: String = "",
    val errorType: String = "",
) {
    val accepted: Boolean
        get() = appNet == "accepted" || chain8 == "allowed" || chain9 == "allowed"

    fun fields(): Map<String, String> = linkedMapOf(
        "appNet" to appNet, "chain8" to chain8, "chain9" to chain9,
        "appNetError" to appNetError, "chain8Error" to chain8Error,
        "chain9Error" to chain9Error, "errorType" to errorType,
    )

    companion object {
        const val MARKER = "REAMICRO_UID_NETWORK_RESTORE_V1"

        fun parse(exitCode: Int, output: String, truncated: Boolean = false): RootUidNetworkRestoreResult {
            if (truncated) return RootUidNetworkRestoreResult(errorType = "UidNetworkOutputTruncated")
            val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val fields = linkedMapOf<String, String>()
            if (exitCode != 0 || lines.firstOrNull() != MARKER) return protocolError()
            for (line in lines.drop(1)) {
                val pair = line.split('=', limit = 2)
                if (pair.size != 2 || pair[0] !in FIELD_NAMES || fields.put(pair[0], pair[1]) != null) {
                    return protocolError()
                }
            }
            if (fields.keys != FIELD_NAMES ||
                fields["appNet"] !in setOf("accepted", "unavailable", "failed", "skipped") ||
                listOf("chain8", "chain9").any {
                    fields[it] !in setOf("clear", "allowed", "inactive", "unavailable", "failed", "skipped")
                } ||
                ERROR_FIELDS.any { fields.getValue(it).let { error ->
                    error.isNotEmpty() && !error.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,95}"))
                } }
            ) return protocolError()
            return RootUidNetworkRestoreResult(
                fields.getValue("appNet"), fields.getValue("chain8"), fields.getValue("chain9"),
                fields.getValue("appNetError"), fields.getValue("chain8Error"),
                fields.getValue("chain9Error"), fields.getValue("errorType"),
            )
        }

        private val ERROR_FIELDS = setOf("appNetError", "chain8Error", "chain9Error", "errorType")
        private val FIELD_NAMES = setOf("appNet", "chain8", "chain9") + ERROR_FIELDS
        private fun protocolError() = RootUidNetworkRestoreResult(errorType = "UidNetworkProtocolError")
    }
}

/** 电池 1.2.8 使用 OEM deny 链 9 或 8；只解除已有的 UID 0 deny，不关闭链。 */
internal fun restoreRootUidFirewall(
    chain: Int,
    enabled: () -> Boolean,
    readRule: (uid: Int) -> Int,
    writeRule: (uid: Int, rule: Int) -> Unit,
): String {
    require(chain in 8..9)
    if (!enabled()) return "inactive"
    return when (readRule(0)) {
        1 -> "clear"
        2 -> {
            writeRule(0, 1)
            check(readRule(0) == 1) { "Root UID firewall rule was not restored" }
            "allowed"
        }
        else -> error("Unknown Root UID firewall rule")
    }
}
