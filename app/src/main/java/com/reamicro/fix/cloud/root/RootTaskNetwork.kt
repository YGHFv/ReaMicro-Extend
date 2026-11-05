package com.reamicro.fix.cloud.root

import android.os.Build
import android.os.Process
import android.os.SystemClock

/** 由 UID-0 独立运行器调用，网络准备的所有命令均有单独超时。 */
internal object RootTaskNetwork {
    fun preparation(cancelled: () -> Boolean): RootNetworkPreparation {
        check(Process.myUid() == 0) { "网络准备只能由 Root 运行器调用" }
        val vendor = RootVendorNetworkPolicy.forDevice(Build.MANUFACTURER, Build.BRAND)
        val wakeCommand = vendor.wakeCommand()
        return RootNetworkPreparation(
            probe = { remaining -> probe(remaining, vendor) },
            exitIdle = { remaining ->
                remaining >= 1_000L && RootCommandRunner.execute(
                    ProcessBuilder("/system/bin/cmd", "deviceidle", "motion"),
                    timeoutSeconds = commandTimeout(remaining),
                ).exitCode == 0
            },
            wakeVendor = wakeCommand?.let { command ->
                { remaining ->
                    remaining >= 1_000L && RootCommandRunner.execute(
                        ProcessBuilder(command), timeoutSeconds = commandTimeout(remaining),
                    ).let { it.exitCode == 0 && !it.truncated }
                }
            },
            shouldWakeVendor = vendor::shouldWake,
            verifyDns = RootDnsProbe::check,
            clock = SystemClock::elapsedRealtime,
            sleep = Thread::sleep,
            cancelled = cancelled,
            report = { event -> RootNightDiagnostics.networkPreparation(event.copy(vendor = vendor.id)) },
        )
    }

    fun probe(
        remainingMs: Long,
        vendor: RootVendorNetworkPolicy = RootVendorNetworkPolicy.forDevice(Build.MANUFACTURER, Build.BRAND),
    ): RootNetworkState {
        if (remainingMs < 1_000L) return RootNetworkState(probeError = "PreparationBudgetExhausted")
        val result = RootCommandRunner.execute(
            ProcessBuilder("/system/bin/sh", "-c", PROBE_SCRIPT + "\n" + vendor.probeScript),
            timeoutSeconds = commandTimeout(remainingMs),
        )
        return if (result.exitCode == 0 && !result.truncated) RootNetworkState.parse(result.output)
        else RootNetworkState(probeError = if (result.truncated) "ProbeOutputTruncated" else "ProbeExit${result.exitCode}")
    }

    private fun commandTimeout(remainingMs: Long): Long = (remainingMs / 1_000L).coerceIn(1L, 3L)

    // 只读取白名单字段；IMS 网络不能被误认为互联网默认网络。
    private val PROBE_SCRIPT = """
        printf 'deep='; /system/bin/cmd deviceidle get deep
        printf 'light='; /system/bin/cmd deviceidle get light
        printf 'network='; /system/bin/cmd deviceidle get network
        printf 'screen='; /system/bin/cmd deviceidle get screen
        /system/bin/dumpsys -t 2 connectivity | /system/bin/sed -n 's/^[[:space:]]*Active default network: */defaultNetwork=/p' | /system/bin/head -n 1
        printf 'airplaneMode='; /system/bin/settings --user 0 get global airplane_mode_on
    """.trimIndent()
}
