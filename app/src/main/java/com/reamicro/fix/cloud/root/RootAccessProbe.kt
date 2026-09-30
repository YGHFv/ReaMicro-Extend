package com.reamicro.fix.cloud.root

import java.io.IOException

internal enum class RootAccessState { GRANTED, UNAVAILABLE, DENIED, NOT_ROOT, TIMEOUT, ERROR }

/** Evidence from this application's own su child, never a manager-package/file heuristic. */
internal data class RootAccessReport(
    val state: RootAccessState,
    val executable: String = "",
    val detail: String = "",
) {
    val granted: Boolean get() = state == RootAccessState.GRANTED
}

internal class RootAccessException(val report: RootAccessReport) :
    IllegalStateException("Root ${report.state}: ${report.detail}")

/** Only this read-only probe may try another executable. Never replay a mutating command. */
internal class RootAccessProbe(
    private val execute: (ProcessBuilder, Long) -> RootCommandResult,
) {
    fun inspect(candidates: List<String>, timeoutSeconds: Long): RootAccessReport {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        val unavailable = mutableListOf<String>()
        for (path in candidates.distinct()) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000_000L
            if (remaining <= 0) return RootAccessReport(RootAccessState.TIMEOUT, path, "授权检测超时；请确认授权弹窗后重试")
            // Do NOT File.exists()/canExecute(): KernelSU's su entry can be virtual.
            val result = try {
                execute(ProcessBuilder(path, "-c", PROBE), remaining.coerceAtLeast(1))
            } catch (_: RootCommandTimeout) {
                return RootAccessReport(RootAccessState.TIMEOUT, path, "等待 SU 返回超时；这不代表设备没有 Root")
            } catch (error: IOException) {
                unavailable += "$path: ${error.message.orEmpty().take(180)}"
                continue
            } catch (error: Exception) {
                return RootAccessReport(RootAccessState.ERROR, path, error.javaClass.simpleName)
            }
            val uid = result.output.lineSequence().map(String::trim)
                .firstOrNull { it.startsWith(MARKER) }?.removePrefix(MARKER)
            if (uid == "0" && result.exitCode == 0 && !result.truncated) return RootAccessReport(RootAccessState.GRANTED, path)
            val message = result.diagnosticOutput.takeLast(400)
            val denied = Regex("permission denied|access denied|not allowed|denied|拒绝", RegexOption.IGNORE_CASE)
                .containsMatchIn(message)
            return RootAccessReport(
                when {
                    uid != null && uid != "0" -> RootAccessState.NOT_ROOT
                    denied -> RootAccessState.DENIED
                    else -> RootAccessState.ERROR
                }, path, "exit=${result.exitCode}; $message",
            )
        }
        return RootAccessReport(RootAccessState.UNAVAILABLE, detail = unavailable.joinToString("\n"))
    }

    companion object {
        const val MARKER = "REAMICRO_UID="
        // Only id is run here; no /data access, module installation or /proc scan.
        val PROBE = """uid=${'$'}(/system/bin/id -u 2>/dev/null) || uid=${'$'}(id -u) || exit 71
printf 'REAMICRO_UID=%s\n' "${'$'}uid"
[ "${'$'}uid" = 0 ]"""
        val DEFAULT_PATHS = listOf("su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")
    }
}
