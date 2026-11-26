package com.reamicro.fix.cloud.root

import android.os.Build
import android.os.IBinder
import android.os.Process
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/** 在单独的 UID 0 进程内调用厂商接口，共享网络准备预算，避免 Binder 阻塞运行器。 */
internal object RootOplusUidNetwork {
    fun restore(remainingMs: Long): RootUidNetworkRestoreResult {
        check(Process.myUid() == 0) { "UID 网络恢复只能由 Root 运行器调用" }
        if (remainingMs < 1_000L) return RootUidNetworkRestoreResult(errorType = "UidNetworkBudgetExhausted")
        if (System.getenv("CLASSPATH").isNullOrBlank()) {
            return RootUidNetworkRestoreResult(errorType = "UidNetworkMissingClasspath")
        }
        return try {
            val result = RootCommandRunner.executeTransient(
                ProcessBuilder(
                    "/system/bin/app_process", "/system/bin",
                    "com.reamicro.fix.cloud.root.RootOplusUidNetworkMain",
                ),
                timeoutSeconds = (remainingMs / 1_000L).coerceIn(1L, 3L),
            )
            RootUidNetworkRestoreResult.parse(result.exitCode, result.output, result.truncated)
        } catch (error: InterruptedException) {
            throw error
        } catch (_: RootCommandTimeout) {
            RootUidNetworkRestoreResult(errorType = "UidNetworkTimeout")
        } catch (error: Exception) {
            RootUidNetworkRestoreResult(errorType = error.javaClass.simpleName)
        }
    }
}

/** 固定只处理 Root UID，不接受包名、UID 或配置输入，不改无线电开关和全局防火墙。 */
object RootOplusUidNetworkMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (Process.myUid() != 0 || arguments.isNotEmpty()) exitProcess(64)
        println(RootUidNetworkRestoreResult.MARKER)
        System.out.flush()
        val result = if (RootVendorNetworkPolicy.forDevice(Build.MANUFACTURER, Build.BRAND) !=
            RootVendorNetworkPolicy.OPLUS
        ) {
            RootUidNetworkRestoreResult(appNet = "skipped", chain8 = "skipped", chain9 = "skipped")
        } else restore()
        result.fields().forEach { (name, value) -> println("$name=$value") }
        System.out.flush()
        exitProcess(0)
    }

    private fun restore(): RootUidNetworkRestoreResult {
        val appNet = attempt {
            // 直接确认 Binder 存在，避免 Manager 在服务缺失时静默返回被误记为接受请求。
            val type = Class.forName("android.nwpower.IOAppNetControl")
            val service = service("oappnetcontrol", type) ?: return@attempt "unavailable"
            type.getMethod("setFirewall", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(service, 0, true)
            "accepted"
        }
        val connectivity = runCatching {
            val type = Class.forName("android.net.IConnectivityManager")
            service("connectivity", type)?.let { it to type }
        }
        fun chain(number: Int): Pair<String, String> = attempt {
            val (service, type) = connectivity.getOrThrow() ?: return@attempt "unavailable"
            val integer = Int::class.javaPrimitiveType
            restoreRootUidFirewall(
                chain = number,
                enabled = { type.getMethod("getFirewallChainEnabled", integer).invoke(service, number) as Boolean },
                readRule = { uid ->
                    type.getMethod("getUidFirewallRule", integer, integer).invoke(service, number, uid) as Int
                },
                writeRule = { uid, rule ->
                    type.getMethod("setUidFirewallRule", integer, integer, integer).invoke(service, number, uid, rule)
                    Unit
                },
            )
        }
        val chain8 = chain(8)
        val chain9 = chain(9)
        return RootUidNetworkRestoreResult(
            appNet = appNet.first, chain8 = chain8.first, chain9 = chain9.first,
            appNetError = appNet.second, chain8Error = chain8.second, chain9Error = chain9.second,
        )
    }

    private fun service(name: String, type: Class<*>): Any? {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("checkService", String::class.java).invoke(null, name) as? IBinder ?: return null
        return Class.forName(type.name + "\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }

    private fun attempt(action: () -> String): Pair<String, String> = try {
        action() to ""
    } catch (error: Exception) {
        val cause = if (error is InvocationTargetException) error.targetException else error
        val status = if (cause is ClassNotFoundException || cause is NoSuchMethodException) "unavailable" else "failed"
        status to cause.javaClass.simpleName.takeIf {
            it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,95}"))
        }.orEmpty()
    }
}
