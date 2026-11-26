package com.reamicro.fix.cloud.root

import android.os.Process
import android.system.ErrnoException
import com.reamicro.fix.cloud.local.CloudTaskLocalRunner
import java.lang.reflect.InvocationTargetException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.URI
import java.net.UnknownHostException
import kotlin.system.exitProcess

internal object RootDnsProbe {
    fun check(remainingMs: Long): RootDnsProbeResult {
        if (remainingMs < 1_000L) return RootDnsProbeResult(errorType = "DnsProbeBudgetExhausted")
        if (System.getenv("CLASSPATH").isNullOrBlank()) {
            return RootDnsProbeResult(errorType = "DnsProbeMissingClasspath")
        }
        return try {
            // 继承 runner.sh 的 APK CLASSPATH 和 UID；不向业务 JVM 写入 DNS 负缓存。
            val result = RootCommandRunner.executeTransient(
                ProcessBuilder(
                    "/system/bin/app_process", "/system/bin",
                    "com.reamicro.fix.cloud.root.RootDnsProbeMain",
                ),
                timeoutSeconds = (remainingMs / 1_000L).coerceIn(1L, 3L),
            )
            RootDnsProbeResult.parse(result.exitCode, result.output, result.truncated)
        } catch (error: InterruptedException) {
            throw error
        } catch (_: RootCommandTimeout) {
            RootDnsProbeResult(errorType = "DnsProbeTimeout")
        } catch (error: Exception) {
            RootDnsProbeResult(errorType = error.javaClass.simpleName)
        }
    }
}

/** 只解析业务域名，不发送 HTTP，不读写任务状态或凭据。 */
object RootDnsProbeMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (Process.myUid() != 0 || arguments.isNotEmpty()) exitProcess(64)
        println(RootDnsProbeResult.MARKER)
        System.out.flush()
        val result = probe()
        result.fields().forEach { (name, value) -> println("$name=$value") }
        System.out.flush()
        exitProcess(if (result.ready) 0 else 2)
    }

    private fun probe(): RootDnsProbeResult {
        var networkHandle: Long? = null
        var vpnPresent: Boolean? = null
        return try {
            val host = URI(CloudTaskLocalRunner.REAMICRO_BASE_URL).host
            if (host.isNullOrBlank()) throw UnknownHostException()
            val (networks, vpn) = RootDirectNetwork.available()
            vpnPresent = vpn
            if (networks.isEmpty()) return RootDnsProbeResult(
                errorType = "DnsDirectNetworkUnavailable", vpnPresent = vpn,
            )
            var lastFailure: Exception? = null
            for (network in networks) {
                networkHandle = network.networkHandle
                try {
                    val addresses = network.getAllByName(host)
                    val ipv4 = addresses.count { it is Inet4Address }
                    val ipv6 = addresses.count { it is Inet6Address }
                    if (ipv4 + ipv6 == 0) throw UnknownHostException()
                    return RootDnsProbeResult(
                        ready = true, networkHandle = networkHandle, vpnPresent = vpn,
                        ipv4Count = ipv4, ipv6Count = ipv6,
                    )
                } catch (error: UnknownHostException) {
                    lastFailure = error
                }
            }
            throw lastFailure ?: UnknownHostException()
        } catch (error: Exception) {
            val cause = if (error is InvocationTargetException) error.targetException else error
            val causes = generateSequence(cause) { it.cause }.take(8).toList()
            RootDnsProbeResult(
                errorType = cause.javaClass.simpleName.takeIf {
                    it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,95}"))
                } ?: "DnsFailure",
                networkHandle = networkHandle, vpnPresent = vpnPresent,
                gaiError = causes.firstNotNullOfOrNull(::rootGaiError),
                errno = causes.filterIsInstance<ErrnoException>().firstOrNull()?.errno,
            )
        }
    }
}
