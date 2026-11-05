package com.reamicro.fix.cloud.root

/** CPU 唤醒、默认网络存在和业务进程能够联网是三个不同的条件。 */
internal data class RootNetworkState(
    val deep: String = "unknown",
    val light: String = "unknown",
    val network: Boolean? = null,
    val defaultNetwork: String = "unknown",
    val probeError: String = "",
    val screen: Boolean? = null,
    val airplaneMode: Boolean? = null,
    // Settings.System 的公开 0/1 标记，不能按 ControllerCenter 内部枚举 4 解析。
    val oplusDeepSleep: Boolean? = null,
    val oplusNetworkDisabled: Boolean? = null,
    val oplusNetworkType: Int? = null,
    val xiaomiSleep: Boolean? = null,
) {
    val ready: Boolean
        get() = probeError.isEmpty() && !oplusNetworkRestricted && xiaomiSleep != true &&
            network != false && when (defaultNetwork) {
            "none" -> false
            "unknown" -> network == true
            else -> defaultNetwork.toIntOrNull()?.let { it > 0 } == true
        }

    // 临时恢复会清 network_type，但可能保留 deep/disabled 两位。
    // 禁网期间即使 Wi-Fi、VALIDATED 和 DNS 缓存仍正常，也不能放行业务请求。
    val oplusNetworkRestricted: Boolean
        get() = (oplusDeepSleep == true || oplusNetworkDisabled == true) && oplusNetworkType in 1..2

    val idle: Boolean
        get() = deep in setOf("IDLE", "IDLE_MAINTENANCE") ||
            light in setOf("IDLE", "WAITING_FOR_NETWORK", "IDLE_MAINTENANCE")

    val offline: Boolean
        get() = defaultNetwork == "none" || (network == false && defaultNetwork == "unknown")

    companion object {
        fun parse(output: String): RootNetworkState {
            val fields = linkedMapOf<String, String>()
            for (line in output.lineSequence()) {
                val separator = line.indexOf('=')
                if (separator < 0) continue
                val name = line.substring(0, separator).trim()
                if (name !in FIELD_NAMES) continue
                if (fields.put(name, line.substring(separator + 1).trim()) != null) {
                    return RootNetworkState(probeError = "DuplicateProbeField")
                }
            }
            fun idleState(name: String): String =
                fields[name]?.takeIf { it.matches(Regex("[A-Z_]+")) } ?: "unknown"
            fun boolean(name: String): Boolean? = when (fields[name]) {
                "true" -> true
                "false" -> false
                else -> null
            }
            fun bit(name: String): Boolean? = when (fields[name]) {
                "1" -> true
                "0" -> false
                else -> null
            }
            val default = fields["defaultNetwork"]
            val defaultValid = default == "none" || default?.toIntOrNull()?.let { it > 0 } == true
            return RootNetworkState(
                deep = idleState("deep"),
                light = idleState("light"),
                network = boolean("network"),
                defaultNetwork = default?.takeIf { defaultValid } ?: "unknown",
                probeError = if (default != null && !defaultValid) "InvalidDefaultNetwork" else "",
                screen = boolean("screen"),
                airplaneMode = bit("airplaneMode"),
                oplusDeepSleep = bit("oplusDeepSleep"),
                oplusNetworkDisabled = bit("oplusNetworkDisabled"),
                oplusNetworkType = fields["oplusNetworkType"]?.toIntOrNull()?.takeIf { it in 0..2 },
                xiaomiSleep = when (fields["xiaomiSleep"]) {
                    "sleep" -> true
                    "unknown", "", null -> null
                    // Provider 缺少该字段时保持 unknown；其他明确配置表示当前非 sleep。
                    else -> fields["xiaomiSleep"]?.takeIf { it.matches(Regex("[A-Za-z_]+")) }?.let { false }
                },
            )
        }

        private val FIELD_NAMES = setOf(
            "deep", "light", "network", "defaultNetwork", "screen", "airplaneMode",
            "oplusDeepSleep", "oplusNetworkDisabled", "oplusNetworkType",
            "xiaomiSleep",
        )
    }
}
