package com.reamicro.fix.cloud.root

/** 新进程 DNS 探测的固定协议，不输出 IP、凭据、URL 或异常 message。 */
internal data class RootDnsProbeResult(
    val ready: Boolean = false,
    val errorType: String = "",
    val gaiError: Int? = null,
    val errno: Int? = null,
) {
    companion object {
        const val MARKER = "REAMICRO_DNS_PROBE_V1"

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
            if (exitCode == 0 && fields == mapOf("dnsReady" to "true")) {
                return RootDnsProbeResult(ready = true)
            }
            if (exitCode != 0 && fields["dnsReady"] == "false") {
                if (listOf("gaiError", "errno").any { it in fields && fields[it]?.toIntOrNull() == null }) {
                    return protocolError()
                }
                return RootDnsProbeResult(
                    errorType = fields["errorType"]?.takeIf {
                        it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,95}"))
                    } ?: "DnsProbeExit$exitCode",
                    gaiError = fields["gaiError"]?.toIntOrNull(),
                    errno = fields["errno"]?.toIntOrNull(),
                )
            }
            return protocolError()
        }

        private val FIELD_NAMES = setOf("dnsReady", "errorType", "gaiError", "errno")
        private fun protocolError() = RootDnsProbeResult(errorType = "DnsProbeProtocolError")
    }
}

/** GaiException 未列入公共 SDK，只读取数值 error。 */
internal fun rootGaiError(error: Throwable): Int? =
    if (error.javaClass.name == "android.system.GaiException") {
        runCatching { error.javaClass.getField("error").getInt(error) }.getOrNull()
    } else null
