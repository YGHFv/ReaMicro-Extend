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
)

/**
 * 一次运行器调用共享一份有界准备结果，多个任务不会各自等待或重复请求厂商恢复。
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
    private val shouldWakeVendor: (RootNetworkState) -> Boolean = { it.offline },
) {
    private var prepared = false
    private var failure: String? = null

    init {
        require(timeoutMs > 0 && pollMs > 0)
    }

    fun ensureReady() {
        checkCancelled()
        if (prepared) return
        failure?.let { throw IOException(it) }

        val started = clock()
        var state = RootNetworkState()
        var motionAttempted = false
        var vendorWakeAttempted = false
        var firstProbe = true
        var dns: RootDnsProbeResult? = null
        var dnsElapsedMs: Long? = null
        fun remaining(): Long = (timeoutMs - (clock() - started)).coerceAtLeast(0L)
        fun event(name: String, motionAccepted: Boolean? = null, vendorWakeAccepted: Boolean? = null) {
            runCatching {
                report(RootNetworkEvent(
                    name, state, clock() - started, motionAccepted, vendorWakeAccepted,
                    dns = dns, dnsElapsedMs = dnsElapsedMs,
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
                if (state.idle && !motionAttempted) {
                    motionAttempted = true
                    event("network_wake_requested")
                    checkCancelled()
                    if (remaining() < MIN_PROBE_BUDGET_MS) break
                    val accepted = try {
                        exitIdle(remaining())
                    } catch (error: InterruptedException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                    event("network_wake_result", motionAccepted = accepted)
                    continue
                }
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
                        prepared = true
                        event("network_ready")
                        return
                    }
                }
                if (!vendorWakeAttempted && shouldWakeVendor(state)) {
                    val wake = wakeVendor
                    if (wake != null) {
                        vendorWakeAttempted = true
                        event("network_vendor_wake_requested")
                        checkCancelled()
                        if (remaining() < MIN_PROBE_BUDGET_MS) break
                        val accepted = try {
                            wake(remaining())
                        } catch (error: InterruptedException) {
                            throw error
                        } catch (_: Exception) {
                            false
                        }
                        event("network_vendor_wake_result", vendorWakeAccepted = accepted)
                        continue
                    }
                }
                val delay = minOf(pollMs, remaining())
                if (delay > 0L) sleep(delay)
            }
            checkCancelled()
            event("network_timeout")
            val message = when {
                state.oplusNetworkRestricted ->
                    "Root 已唤醒，但 OPPO 深睡网络限制未解除；保留任务进度，等待重试"
                state.xiaomiSleep == true ->
                    "Root 已唤醒，但小米睡眠网络策略尚未退出；保留任务进度，等待重试"
                state.ready && dns?.ready == false ->
                    "Root 已唤醒，但业务域名 DNS 暂不可用；保留任务进度，等待重试"
                else -> "Root 已唤醒，但网络准备超时；保留任务进度，等待重试"
            }
            failure = message
            throw IOException(message)
        } catch (error: InterruptedException) {
            event("network_cancelled")
            throw error
        }
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted || cancelled()) {
            throw InterruptedException("Root 任务已停止，取消网络等待")
        }
    }

    private companion object {
        const val MIN_PROBE_BUDGET_MS = 1_000L
    }
}
