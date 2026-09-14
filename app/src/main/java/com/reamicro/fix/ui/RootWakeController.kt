package com.reamicro.fix.ui

import android.content.Context
import com.reamicro.fix.cloud.api.CloudTaskWakeScheduler
import com.reamicro.fix.logging.ModuleAndroidLog
import java.util.concurrent.TimeUnit

/**
 * Root 增强：把「定时唤醒」交给 root 侧的看门狗。
 *
 * 为什么需要：实测 HyperOS 会对模块进程执行 `freezeUid`，冻结期间系统闹钟的广播、以及宿主投来的
 * 通知广播都**不会执行**（见 [com.reamicro.fix.notification.CloudTaskNotifications] 的说明）；
 * 模块 App 又没有 launcher activity，装完可能长期处于 stopped，而 stopped 应用的广播同样收不到。
 * root 身份跑一个循环去 `am broadcast` 可以同时绕过这两条限制：root 不受应用冻结影响，
 * 且 `--include-stopped-packages` 能拉起 stopped 应用。
 *
 * 只在用户**显式开启**时写入脚本；关掉时把脚本删掉并结束循环，不留残留。
 */
object RootWakeController {

    /** 看门狗脚本落点。Magisk 与 KernelSU 都会在开机时执行这个目录下的脚本。 */
    private const val WATCHDOG_PATH = "/data/adb/service.d/reamicro-watchdog.sh"
    private const val ADB_DIR = "/data/adb"

    /** 循环间隔，与 [CloudTaskWakeScheduler] 的兜底轮询保持一致。 */
    private const val INTERVAL_SECONDS = 15 * 60

    data class Status(
        val rootAvailable: Boolean,
        val watchdogInstalled: Boolean,
        val watchdogRunning: Boolean,
        val message: String,
    )

    fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 是否真的有可用的 root：`su -c id` 必须回 uid=0。 */
    fun isRootAvailable(): Boolean = runCatching {
        val output = exec("id", timeoutSeconds = 10)
        output.contains("uid=0")
    }.getOrDefault(false)

    /** 读当前状态。会真的去问一次 su，必须在后台线程调用。 */
    fun inspect(context: Context): Status {
        val root = isRootAvailable()
        if (!root) {
            return Status(
                rootAvailable = false,
                watchdogInstalled = false,
                watchdogRunning = false,
                message = "未检测到 root，无法使用 root 唤醒（不影响系统闹钟唤醒）",
            )
        }
        val installed = runCatching { exec("test -f $WATCHDOG_PATH && echo yes", 10).contains("yes") }
            .getOrDefault(false)
        val running = isWatchdogRunning()
        return Status(
            rootAvailable = true,
            watchdogInstalled = installed,
            watchdogRunning = running,
            message = when {
                installed && running -> "看门狗已安装并在运行，每 ${INTERVAL_SECONDS / 60} 分钟唤醒一次"
                installed -> "看门狗已安装（开机后自动生效）"
                else -> "未启用看门狗"
            },
        )
    }

    /**
     * 开启：写入开机脚本并立刻启动一份。
     *
     * 返回可读的结果说明，直接显示给用户（root 操作失败的原因需要让用户看到，而不是静默）。
     */
    fun enable(context: Context): String {
        if (!isRootAvailable()) return "未检测到 root，无法启用"
        val script = watchdogScript()
        return runCatching {
            // 1) 写开机脚本。service.d 不存在时先建出来（Magisk/KernelSU 开机都会扫描）。
            val write = exec(
                "mkdir -p ${ADB_DIR}/service.d && cat > $WATCHDOG_PATH <<'EOF'\n$script\nEOF\nchmod 755 $WATCHDOG_PATH",
                timeoutSeconds = 20,
            )
            // 2) 立刻起一份，不必等重启。
            exec("$WATCHDOG_PATH >/dev/null 2>&1 &", timeoutSeconds = 10)
            prefs(context).edit().putBoolean(KEY_ENABLED, true).commit()
            ModuleAndroidLog.legacy(LOG_TAG, "root watchdog enabled at $WATCHDOG_PATH")
            val installed = exec("test -f $WATCHDOG_PATH && echo yes", 10).contains("yes")
            if (installed) {
                "看门狗已启用，每 ${INTERVAL_SECONDS / 60} 分钟以 root 身份唤醒一次（开机自动生效）"
            } else {
                "脚本写入似乎未成功：${write.take(200).ifBlank { "无输出" }}"
            }
        }.getOrElse {
            ModuleAndroidLog.error(LOG_TAG, "root watchdog enable failed: ${it.message}", it)
            "启用失败：${it.message ?: it.javaClass.simpleName}"
        }
    }

    /** 关闭：删脚本并结束循环。 */
    fun disable(context: Context): String = runCatching {
        exec("rm -f $WATCHDOG_PATH; pkill -f reamicro-watchdog", timeoutSeconds = 15)
        prefs(context).edit().putBoolean(KEY_ENABLED, false).commit()
        ModuleAndroidLog.legacy(LOG_TAG, "root watchdog disabled")
        "看门狗已停用"
    }.getOrElse {
        ModuleAndroidLog.error(LOG_TAG, "root watchdog disable failed: ${it.message}", it)
        "停用失败：${it.message ?: it.javaClass.simpleName}"
    }

    /** 用户上次的选择，用于界面回显（实际是否在位以 [inspect] 为准）。 */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /**
     * 看门狗脚本。
     *
     * 直接广播模块自己的心跳接收器：它跑在模块进程里，收到后会重排闹钟、执行到期的本地任务并
     * 拉一次服务器消息。用 `am broadcast` 而不是 `cmd alarm`，是因为前者语义简单、各版本一致。
     */
    internal fun watchdogScript(): String = """
        #!/system/bin/sh
        # ReaMicro 模块唤醒看门狗（由模块主界面的「Root 增强」写入；删除本文件即停用）
        # 作用：以 root 身份定期唤醒模块，绕开「应用被系统冻结 / 应用处于 stopped」导致
        #       系统闹钟广播收不到的问题。每 ${INTERVAL_SECONDS / 60} 分钟一次。
        while true; do
          am broadcast --user 0 --include-stopped-packages \
            -a ${CloudTaskWakeScheduler.ACTION_WAKE} \
            -n com.reamicro.fix/com.reamicro.fix.cloud.api.CloudTaskHeartbeatReceiver \
            >/dev/null 2>&1
          sleep $INTERVAL_SECONDS
        done
    """.trimIndent()

    /**
     * 看门狗循环是否在跑。
     *
     * `pgrep` 不是所有 ROM 都带（toybox 版本差异），所以失败时退回 `ps -A` 过滤——
     * 这条只在界面展示上用，判断不出来时宁可显示"未在运行"也不要谎报在跑。
     */
    private fun isWatchdogRunning(): Boolean = runCatching {
        exec(
            "pgrep -f reamicro-watchdog || ps -A | grep reamicro-watchdog | grep -v grep",
            timeoutSeconds = 10,
        ).isNotBlank()
    }.getOrDefault(false)

    /**
     * 执行一条 root 命令并返回输出。
     *
     * `su` 的位置各机型不同（/system/bin/su、/system/xbin/su、/sbin/su），所以只依赖 PATH，
     * 交给 `su` 自己解析；失败时抛异常，由调用方转成用户可读的提示。
     */
    private fun exec(command: String, timeoutSeconds: Long): String {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("root 命令超时")
        }
        return output.trim()
    }

    private const val LOG_TAG = "ReaMicroRoot"
    private const val PREFS_NAME = "reamicro_root_wake"
    private const val KEY_ENABLED = "enabled"
}
