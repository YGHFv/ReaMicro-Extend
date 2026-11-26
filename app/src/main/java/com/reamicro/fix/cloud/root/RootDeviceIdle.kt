package com.reamicro.fix.cloud.root

import android.os.IBinder
import android.os.Process
import kotlin.system.exitProcess

/** motion 未退出浅度/快速 Doze 时的有界补救；不改变 idle 开关或白名单。 */
internal object RootDeviceIdle {
    fun exit(remainingMs: Long): RootDeviceIdleResult {
        if (Process.myUid() != 0) return RootDeviceIdleResult(errorType = "DeviceIdleRequiresRoot")
        if (remainingMs < 1_000L) return RootDeviceIdleResult(errorType = "DeviceIdleBudgetExhausted")
        if (System.getenv("CLASSPATH").isNullOrBlank()) {
            return RootDeviceIdleResult(errorType = "DeviceIdleMissingClasspath")
        }
        return attemptRootIdleExit {
            val result = RootCommandRunner.executeTransient(
                ProcessBuilder("/system/bin/app_process", "/system/bin", "com.reamicro.fix.cloud.root.RootDeviceIdleMain"),
                timeoutSeconds = (remainingMs / 1_000L).coerceIn(1L, 3L),
            )
            RootDeviceIdleResult.parse(result.exitCode, result.output, result.truncated)
        }
    }
}

/** Binder 调用放在独立进程，避免异常固件的服务调用卡住整个任务运行器。 */
object RootDeviceIdleMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        if (Process.myUid() != 0 || arguments.isNotEmpty()) exitProcess(64)
        val result = attemptRootIdleExit {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("checkService", String::class.java).invoke(null, "deviceidle") as? IBinder
                ?: return@attemptRootIdleExit RootDeviceIdleResult(errorType = "DeviceIdleServiceUnavailable")
            val api = Class.forName("android.os.IDeviceIdleController")
            val service = Class.forName(api.name + "\$Stub").getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
                ?: return@attemptRootIdleExit RootDeviceIdleResult(errorType = "DeviceIdleServiceUnavailable")
            api.getMethod("exitIdle", String::class.java).invoke(service, "reamicro_tasks")
            RootDeviceIdleResult(accepted = true)
        }
        println(result.protocol())
        System.out.flush()
        exitProcess(if (result.accepted) 0 else 2)
    }
}
