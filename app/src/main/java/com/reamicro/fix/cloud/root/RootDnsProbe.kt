package com.reamicro.fix.cloud.root

import android.os.Process
import android.system.ErrnoException
import com.reamicro.fix.cloud.local.CloudTaskLocalRunner
import java.net.InetAddress
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
            val result = RootCommandRunner.execute(
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
        try {
            val host = URI(CloudTaskLocalRunner.REAMICRO_BASE_URL).host
            if (host.isNullOrBlank() || InetAddress.getAllByName(host).isEmpty()) {
                throw UnknownHostException()
            }
            println("dnsReady=true")
        } catch (error: Exception) {
            val causes = generateSequence(error as Throwable) { it.cause }.take(8).toList()
            println("dnsReady=false")
            println("errorType=" + (error.javaClass.simpleName.takeIf {
                it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,95}"))
            } ?: "DnsFailure"))
            causes.firstNotNullOfOrNull(::rootGaiError)?.let { println("gaiError=$it") }
            causes.filterIsInstance<ErrnoException>().firstOrNull()?.let { println("errno=${it.errno}") }
            System.out.flush()
            exitProcess(2)
        }
    }
}
