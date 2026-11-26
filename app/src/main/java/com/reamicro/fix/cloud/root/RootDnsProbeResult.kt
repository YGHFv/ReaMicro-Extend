package com.reamicro.fix.cloud.root

/** 新进程 DNS 探测的固定协议，不输出 IP、凭据、URL 或异常 message。 */
internal data class RootDnsProbeResult(
    val ready: Boolean = false,
    val errorType: String = "",
    val gaiError: Int? = null,
    val errno: Int? = null,
    val networkHandle: Long? = null,
    val vpnPresent: Boolean? = null,
    val ipv4Count: Int? = null,
    val ipv6Count: Int? = null,
) {
    fun fields(): Map<String, String> = linkedMapOf("dnsReady" to ready.toString()).apply {
        if (!ready) put("errorType", errorType)
        networkHandle?.let { put("networkHandle", it.toString()) }
        vpnPresent?.let { put("vpnPresent", it.toString()) }
        ipv4Count?.let { put("ipv4Count", it.toString()) }
        ipv6Count?.let { put("ipv6Count", it.toString()) }
        gaiError?.let { put("gaiError", it.toString()) }
        errno?.let { put("errno", it.toString()) }
    }

    companion object {
        const val MARKER = "REAMICRO_DNS_PROBE_V2"

        fun parse(exitCode: Int, output: String, truncated: Boolean = false): RootDnsProbeResult {
            if (truncated) return RootDnsProbeResult(errorType = "DnsProbeOutputTruncated")
            val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            if (lines.firstOrNull() != MARKER) return protocolError()
            val fields = linkedMapOf<String, String>()
            for (line in lines.drop(1)) {
                val pair = line.split('=', limit = 2)
                if (pair.size != 2 || pair[0] !in FIELD_NAMES || fields.put(pair[0], pair[1]) != null) {
                    return protocolError()
                }
            }
            return parseFields(exitCode, fields)
        }

        private fun parseFields(exitCode: Int, fields: Map<String, String>): RootDnsProbeResult {
            fun count(name: String): Int? = fields[name]?.toIntOrNull()?.takeIf { it in 0..MAX_ADDRESSES }
            val handle = fields["networkHandle"]?.toLongOrNull()?.takeIf { it > 0 }
            val vpn = when (fields["vpnPresent"]) {
                "true" -> true
                "false" -> false
                else -> null
            }
            if (("networkHandle" in fields && handle == null) || ("vpnPresent" in fields && vpn == null) ||
                listOf("ipv4Count", "ipv6Count").any { it in fields && count(it) == null }
            ) return protocolError()
            if (exitCode == 0 && fields["dnsReady"] == "true") {
                val ipv4 = count("ipv4Count") ?: return protocolError()
                val ipv6 = count("ipv6Count") ?: return protocolError()
                if (handle == null || vpn == null || ipv4 + ipv6 !in 1..MAX_ADDRESSES ||
                    fields.keys != SUCCESS_FIELDS
                ) return protocolError()
                return RootDnsProbeResult(
                    ready = true, networkHandle = handle, vpnPresent = vpn, ipv4Count = ipv4, ipv6Count = ipv6,
                )
            }
            if (exitCode == 2 && fields["dnsReady"] == "false") {
                if ("ipv4Count" in fields || "ipv6Count" in fields) return protocolError()
                if (listOf("gaiError", "errno").any { it in fields && fields[it]?.toIntOrNull() == null }) {
                    return protocolError()
                }
                val error = fields["errorType"]?.takeIf {
                    it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,95}"))
                } ?: return protocolError()
                return RootDnsProbeResult(
                    errorType = error,
                    gaiError = fields["gaiError"]?.toIntOrNull(),
                    errno = fields["errno"]?.toIntOrNull(),
                    networkHandle = handle, vpnPresent = vpn,
                )
            }
            return protocolError()
        }

        private const val MAX_ADDRESSES = 256
        private val SUCCESS_FIELDS = setOf("dnsReady", "networkHandle", "vpnPresent", "ipv4Count", "ipv6Count")
        private val FIELD_NAMES = SUCCESS_FIELDS + setOf("errorType", "gaiError", "errno")
        private fun protocolError() = RootDnsProbeResult(errorType = "DnsProbeProtocolError")
    }
}

/** GaiException 未列入公共 SDK，只读取数值 error。 */
internal fun rootGaiError(error: Throwable): Int? =
    if (error.javaClass.name == "android.system.GaiException") {
        runCatching { error.javaClass.getField("error").getInt(error) }.getOrNull()
    } else null
