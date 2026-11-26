package com.reamicro.fix.cloud.root

import java.io.IOException

internal data class RootNetworkEvent(
    val name: String,
    val state: RootNetworkState,
    val elapsedMs: Long,
    val motionAccepted: Boolean? = null,
    val vendorWakeAccepted: Boolean? = null,
    val vendor: String = "none",
    val dns: RootDnsProbeResult? = null,
    val dnsElapsedMs: Long? = null,
    val uidNetworkRestore: RootUidNetworkRestoreResult? = null,
    val idleExitAccepted: Boolean? = null,
    val idleExitError: String = "",
)

/**
 * 每项任务重新验证网络；一次运行器调用共享恢复预算、动作次数和超时结果。
 * 恢复命令只派发请求；重新探测连接状态和新进程 DNS 成功后才放行业务请求。
 */
internal class RootNetworkPreparation(
    private val probe: (remainingMs: Long) -> RootNetworkState,
    private val exitIdle: (remainingMs: Long) -> Boolean,
    private val wakeVendor: ((remainingMs: Long) -> Boolean)? = null,
    private val clock: () -> Long,
    private val sleep: (Long) -> Unit,
    private val cancelled: () -> Boolean = { false },
    private val report: (RootNetworkEvent) -> Unit = {},
    private val timeoutMs: Long = 30_000L,
    private val pollMs: Long = 1_000L,
    private val verifyDns: ((remainingMs: Long) -> RootDnsProbeResult)? = null,
    private val shouldWakeVendor: (RootNetworkState, RootDnsProbeResult?) -> Boolean = { state, _ -> state.offline },
    private val restoreUidNetwork: ((remainingMs: Long) -> RootUidNetworkRestoreResult)? = null,
    private val shouldRestoreUidNetwork: (RootNetworkState, RootDnsProbeResult?) -> Boolean = { _, _ -> false },
    private val exitDeviceIdle: ((remainingMs: Long) -> RootDeviceIdleResult)? = null,
) {
    private var failure: String? = null
    private var recoveryBudgetMs = timeoutMs
    private var motionAttempted = false
    private var vendorWakeAttempted = false
    private var uidNetworkRestoreAttempted = false
    private var idleExitAttempted = false

    init {
        require(timeoutMs > 0 && pollMs > 0)
    }

    fun ensureReady(): RootDnsProbeResult? {
        checkCancelled()
        failure?.let { throw IOException(it) }

        val started = clock()
        var state = RootNetworkState()
        var recoveryNeeded = false
        var firstProbe = true
        var pollDelay = pollMs
        var dns: RootDnsProbeResult? = null
        var dnsElapsedMs: Long? = null
        var uidNetworkRestore: RootUidNetworkRestoreResult? = null
        // 健康复核不消耗恢复预算；即使此前恢复接近上限，仍可检查已正常联网的下一项任务。
        fun remaining(): Long = ((if (recoveryNeeded) recoveryBudgetMs else
            maxOf(recoveryBudgetMs, RECHECK_BUDGET_MS)) - (clock() - started)).coerceAtLeast(0L)
        fun event(name: String, motionAccepted: Boolean? = null, vendorWakeAccepted: Boolean? = null,
                  idleExit: RootDeviceIdleResult? = null) {
            runCatching {
                report(RootNetworkEvent(
                    name, state, clock() - started, motionAccepted, vendorWakeAccepted,
                    dns = dns, dnsElapsedMs = dnsElapsedMs, uidNetworkRestore = uidNetworkRestore,
                    idleExitAccepted = idleExit?.accepted, idleExitError = idleExit?.errorType.orEmpty(),
                ))
            }
        }
        try {
            while (remaining() >= MIN_PROBE_BUDGET_MS) {
                checkCancelled()
                state = try {
                    probe(remaining())
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: Exception) {
                    RootNetworkState(probeError = error.javaClass.simpleName)
                }
                checkCancelled()
                if (firstProbe) {
                    event("network_prepare")
                    firstProbe = false
                }
                if (remaining() <= 0L) break
                dns = null
                dnsElapsedMs = null
                if (state.ready) {
                    val verify = verifyDns
                    if (verify != null) {
                        if (remaining() < MIN_PROBE_BUDGET_MS) break
                        checkCancelled()
                        val dnsStarted = clock()
                        dns = try {
                            verify(remaining())
                        } catch (error: InterruptedException) {
                            throw error
                        } catch (error: Exception) {
                            RootDnsProbeResult(errorType = error.javaClass.simpleName)
                        }
                        dnsElapsedMs = clock() - dnsStarted
                        checkCancelled()
                        event("network_dns_result")
                        checkCancelled()
                        if (remaining() <= 0L) break
                    }
                    if (verify == null || dns?.ready == true) {
                        event("network_ready")
                        return dns
                    }
                }
                recoveryNeeded = true
                if (remaining() < MIN_PROBE_BUDGET_MS) break
                // 维护窗口和可用的飞行模式 Wi-Fi 都能直接执行。先验证业务域名，
                // 确有阻塞再请求恢复，每个动作在本轮至多派发一次。
                val requestVendor = wakeVendor != null && !vendorWakeAttempted && shouldWakeVendor(state, dns)
                val vendorRestricted = state.oplusDeepSleep == true ||
                    state.oplusNetworkDisabled == true || state.xiaomiSleep == true
                if (state.idle && state.airplaneMode != true && !motionAttempted && !(requestVendor && vendorRestricted)) {
                    motionAttempted = true
                    event("network_wake_requested")
                    checkCancelled()
                    if (remaining() < MIN_PROBE_BUDGET_MS) break
                    val accepted = attemptWake(remaining(), exitIdle)
                    event("network_wake_result", motionAccepted = accepted)
                    continue
                }
                if (requestVendor) {
                    vendorWakeAttempted = true
                    event("network_vendor_wake_requested")
                    checkCancelled()
                    if (remaining() < MIN_PROBE_BUDGET_MS) break
                    val accepted = attemptWake(remaining(), wakeVendor)
                    event("network_vendor_wake_result", vendorWakeAccepted = accepted)
                    checkCancelled()
                } else if (motionAttempted && state.idle && state.probeError.isEmpty() &&
                    state.screen == false && state.airplaneMode == false && !idleExitAttempted && exitDeviceIdle != null
                ) {
                    idleExitAttempted = true
                    event("network_idle_exit_requested")
                    checkCancelled()
                    if (remaining() < MIN_PROBE_BUDGET_MS) break
                    val result = attemptRootIdleExit { exitDeviceIdle(remaining()) }
                    event("network_idle_exit_result", idleExit = result)
                    checkCancelled()
                } else if (vendorWakeAttempted && !uidNetworkRestoreAttempted && restoreUidNetwork != null &&
                    shouldRestoreUidNetwork(state, dns)
                ) {
                    // 厂商请求后先复查；网络存在但 DNS 仍失败，才为 Root UID 补一次恢复。
                    uidNetworkRestoreAttempted = true
                    event("network_uid_restore_requested")
                    checkCancelled()
                    if (remaining() < MIN_PROBE_BUDGET_MS) break
                    uidNetworkRestore = try {
                        restoreUidNetwork.invoke(remaining())
                    } catch (error: InterruptedException) {
                        throw error
                    } catch (error: Exception) {
                        RootUidNetworkRestoreResult(errorType = error.javaClass.simpleName)
                    }
                    event("network_uid_restore_result")
                    checkCancelled()
                }
                val delay = minOf(pollDelay, remaining())
                if (delay > 0L) sleep(delay)
                pollDelay = (pollDelay + pollMs).coerceAtMost(maxOf(pollMs, MAX_POLL_MS))
            }
            checkCancelled()
            event("network_timeout")
            failure = timeoutMessage(state, dns)
            throw IOException(failure)
        } catch (error: InterruptedException) {
            event("network_cancelled")
            throw error
        } finally {
            if (recoveryNeeded) recoveryBudgetMs = (recoveryBudgetMs - (clock() - started)).coerceAtLeast(0L)
        }
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted || cancelled()) {
            throw InterruptedException("Root 任务已停止，取消网络等待")
        }
    }

    private fun attemptWake(remainingMs: Long, action: (Long) -> Boolean): Boolean = try {
        action(remainingMs)
    } catch (error: InterruptedException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private fun timeoutMessage(state: RootNetworkState, dns: RootDnsProbeResult?): String = when {
        state.oplusNetworkRestricted ->
            "Root 已唤醒，但 OPPO 深睡网络限制未解除；保留任务进度，等待重试"
        state.xiaomiSleep == true ->
            "Root 已唤醒，但小米睡眠网络策略尚未退出；保留任务进度，等待重试"
        dns?.errorType == "DnsDirectNetworkUnavailable" ->
            "Root 已唤醒，但直连网络暂不可用；保留任务进度，等待重试"
        state.ready && dns?.ready == false ->
            "Root 已唤醒，但业务域名 DNS 暂不可用；保留任务进度，等待重试"
        else -> "Root 已唤醒，但网络准备超时；保留任务进度，等待重试"
    }

    private companion object {
        const val MIN_PROBE_BUDGET_MS = 1_000L
        const val MAX_POLL_MS = 4_000L
        const val RECHECK_BUDGET_MS = 6_000L
    }
}
