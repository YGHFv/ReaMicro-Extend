package com.reamicro.fix.cloud.root

import java.lang.reflect.InvocationTargetException

/** Binder 接受请求不等于联网成功；回执只携带结果和异常类型，不携带异常正文。 */
internal data class RootDeviceIdleResult(val accepted: Boolean = false, val errorType: String = "") {
    fun protocol(): String = "$MARKER\naccepted=$accepted\nerrorType=$errorType"

    companion object {
        const val MARKER = "REAMICRO_DEVICE_IDLE_EXIT_V1"

        fun parse(exitCode: Int, output: String, truncated: Boolean = false): RootDeviceIdleResult {
            if (truncated) return RootDeviceIdleResult(errorType = "DeviceIdleOutputTruncated")
            val lines = output.trim().lines()
            if (lines.size != 3 || lines[0] != MARKER || !lines[2].startsWith("errorType=")) return invalid()
            val accepted = when (lines[1]) {
                "accepted=true" -> true
                "accepted=false" -> false
                else -> return invalid()
            }
            val error = lines[2].removePrefix("errorType=")
            if (exitCode != (if (accepted) 0 else 2) ||
                (accepted && error.isNotEmpty()) ||
                (!accepted && !error.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,95}")))
            ) return invalid()
            return RootDeviceIdleResult(accepted, error)
        }

        private fun invalid() = RootDeviceIdleResult(errorType = "DeviceIdleProtocolError")
    }
}

internal fun attemptRootIdleExit(action: () -> RootDeviceIdleResult): RootDeviceIdleResult = try {
    action()
} catch (error: Exception) {
    val cause = if (error is InvocationTargetException) error.targetException else error
    if (cause is InterruptedException) throw cause
    val type = if (cause is RootCommandTimeout) "DeviceIdleTimeout" else cause.javaClass.simpleName
    RootDeviceIdleResult(errorType = type.takeIf {
        it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,95}"))
    } ?: "DeviceIdleFailure")
}
