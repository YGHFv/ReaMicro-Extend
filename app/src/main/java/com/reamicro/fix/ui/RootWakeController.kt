package com.reamicro.fix.ui

import android.content.Context
import com.reamicro.fix.cloud.api.CloudTaskWakeScheduler
import com.reamicro.fix.cloud.api.NextWakeHint
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

    /** 循环读文件的粒度上限：睡得太久就感知不到"用户刚改了配置"。 */
    private const val MAX_SLEEP_SECONDS = 15 * 60

    /** 广播之后的冷却，等模块写回新的唤醒时刻。 */
    private const val COOLDOWN_SECONDS = 5 * 60

    data class Status(
        val rootAvailable: Boolean,
        val watchdogInstalled: Boolean,
        val watchdogRunning: Boolean,
        val message: String,
    ) {
        /**
         * 状态短标签。**只在这里推导一次**。
         *
         * 之前是界面拿三个布尔值各自判断"标题"和"说明"，两边口径不一致时就会出现
         * 「看门狗运行中」配「未启用看门狗」这种自相矛盾的展示（实机见过）。
         * 现在状态与说明同源，且 [rootAvailable] 为假时无条件显示未授权——没有 root 就谈不上
         * 看门狗在不在跑，探测结果也不可信。
         */
        fun stateLabel(): String = when {
            !rootAvailable -> "未授权"
            watchdogRunning -> "看门狗运行中"
            watchdogInstalled -> "看门狗已安装"
            else -> "已授权，未启用"
        }

        fun displayTitle(): String = "Root 状态：${stateLabel()}"
    }

    fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 是否真的有可用的 root：`su -c id` 必须**退出码为 0 且回显 uid=0**。 */
    fun isRootAvailable(): Boolean {
        val result = runCatching { runRoot("id", timeoutSeconds = 10) }
        val available = result.getOrNull()?.let { it.exitCode == 0 && it.output.contains("uid=0") } ?: false
        ModuleAndroidLog.legacy(
            LOG_TAG,
            "root probe exit=${result.getOrNull()?.exitCode} available=$available " +
                "error=${result.exceptionOrNull()?.message.orEmpty().take(80)}",
        )
        return available
    }

    /** 读当前状态。会真的去问一次 su，必须在后台线程调用。 */
    fun inspect(context: Context): Status {
        if (!isRootAvailable()) {
            return Status(
                rootAvailable = false,
                watchdogInstalled = false,
                watchdogRunning = false,
                message = "未检测到 root（已尝试 su -c id），无法使用 root 唤醒；系统闹钟唤醒不受影响",
            )
        }
        val installed = probeYes("test -f $WATCHDOG_PATH && echo yes")
        val running = isWatchdogRunning()
        return Status(
            rootAvailable = true,
            watchdogInstalled = installed,
            watchdogRunning = running,
            message = when {
                installed && running -> "看门狗已安装并在运行，按任务时刻唤醒（读不到时刻时每 ${MAX_SLEEP_SECONDS / 60} 分钟兜底）"
                installed -> "看门狗已安装（开机后自动生效）"
                else -> "尚未启用看门狗，root 已授权可直接开启"
            },
        )
    }

    /**
     * 执行一条探测命令，只有**退出码为 0 且回显 yes** 才算真。
     *
     * 严格判定的原因：`su` 不存在时某些实现会把错误訊息写到 stdout，早前用"输出非空"判定
     * 会把这类报错当成"成功"，于是没有 root 的机器上也显示"看门狗运行中"。
     * 每条探测都写进模块日志——root 问题只能靠这些原始输出定位。
     */
    private fun probeYes(command: String): Boolean = runCatching {
        val result = runRoot(command, timeoutSeconds = 10)
        val ok = result.exitCode == 0 && result.output.trim() == "yes"
        ModuleAndroidLog.legacy(LOG_TAG, "probe[$command] exit=${result.exitCode} ok=$ok out=${result.output.take(80)}")
        ok
    }.getOrElse {
        ModuleAndroidLog.legacy(LOG_TAG, "probe[$command] could not run: ${it.message}")
        false
    }

    /**
     * 开启：写入开机脚本并立刻启动一份。
     *
     * 返回可读的结果说明，直接显示给用户（root 操作失败的原因需要让用户看到，而不是静默）。
     */
    fun enable(context: Context): String {
        if (!isRootAvailable()) return "未检测到 root，无法启用"
        val appContext = context.applicationContext
        val script = watchdogScript(NextWakeHint.file(appContext).absolutePath)
        return runCatching {
            // 1) 写开机脚本。service.d 不存在时先建出来（Magisk/KernelSU 开机都会扫描）。
            val write = runRoot(
                "mkdir -p ${ADB_DIR}/service.d && cat > $WATCHDOG_PATH <<'EOF'\n$script\nEOF\nchmod 755 $WATCHDOG_PATH",
                timeoutSeconds = 20,
            )
            // 2) 先把当前的下次唤醒时刻写出去，看门狗一起步就能按它睡，不必先空转一轮。
            CloudTaskWakeScheduler.schedule(appContext)
            // 3) 立刻起一份，不必等重启。
            runRoot("$WATCHDOG_PATH >/dev/null 2>&1 &", timeoutSeconds = 10)
            prefs(context).edit().putBoolean(KEY_ENABLED, true).commit()
            ModuleAndroidLog.legacy(LOG_TAG, "root watchdog enabled at $WATCHDOG_PATH")
            if (probeYes("test -f $WATCHDOG_PATH && echo yes")) {
                "看门狗已启用：按任务时刻唤醒（读不到时刻时每 ${MAX_SLEEP_SECONDS / 60} 分钟兜底），开机自动生效"
            } else {
                "脚本写入似乎未成功：${write.output.take(200).ifBlank { "无输出" }}"
            }
        }.getOrElse {
            ModuleAndroidLog.error(LOG_TAG, "root watchdog enable failed: ${it.message}", it)
            "启用失败：${it.message ?: it.javaClass.simpleName}"
        }
    }

    /** 关闭：删脚本并结束循环。 */
    fun disable(context: Context): String = runCatching {
        runRoot("rm -f $WATCHDOG_PATH; pkill -f reamicro-watchdog", timeoutSeconds = 15)
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
     * **按任务时刻唤醒，而不是固定周期**：模块每次排完闹钟都会把触发时刻写进
     * [NextWakeHint] 的文件，脚本睡到那个时刻才广播一次。这样唤醒次数与真实任务时刻对齐，
     * 不会大多数唤醒都无事可做。睡醒的粒度上限 [MAX_SLEEP_SECONDS]（默认 15 分钟）是为了
     * 及时感知"用户刚改了配置"——只读文件、不广播，代价可以忽略。
     *
     * 只有在**读不到时刻**时才退化成定期广播，这样全新安装（还没排过闹钟）也不会失联。
     */
    internal fun watchdogScript(nextWakeFile: String): String = """
        #!/system/bin/sh
        # ReaMicro 模块唤醒看门狗（由模块主界面的「Root 增强」写入；删除本文件即停用）
        # 作用：以 root 身份在任务时刻唤醒模块，绕开「应用被系统冻结 / 应用处于 stopped」
        #       导致系统闹钟广播收不到的问题。
        WAKE_FILE="$nextWakeFile"
        MAX_SLEEP=$MAX_SLEEP_SECONDS
        COOLDOWN=$COOLDOWN_SECONDS
        while true; do
          now=${'$'}(date +%s)
          next=${'$'}(cat "${'$'}WAKE_FILE" 2>/dev/null)
          case "${'$'}next" in
            ''|*[!0-9]*) next=0 ;;
          esac
          if [ "${'$'}next" -gt "${'$'}now" ]; then
            # 还没到点：睡到时刻或睡满一个粒度，醒来再看一眼（用户可能刚改了配置）。
            delay=${'$'}((next - now))
            [ "${'$'}delay" -gt "${'$'}MAX_SLEEP" ] && delay=${'$'}MAX_SLEEP
            sleep "${'$'}delay"
            continue
          fi
          if [ "${'$'}next" -eq 0 ]; then
            # 还没排过闹钟（全新安装/刚启用）：定期唤醒一次，让模块有机会写下时刻。
            sleep "${'$'}MAX_SLEEP"
          fi
          am broadcast --user 0 --include-stopped-packages \
            -a ${CloudTaskWakeScheduler.ACTION_WAKE} \
            -n com.reamicro.fix/com.reamicro.fix.cloud.api.CloudTaskHeartbeatReceiver \
            >/dev/null 2>&1
          # 等模块写回新的唤醒时刻，避免同一个到期时刻被反复广播。
          sleep "${'$'}COOLDOWN"
        done
    """.trimIndent()

    /**
     * 看门狗循环是否在跑。
     *
     * 两个坑都踩过：
     * - **`pgrep -f` 会匹配到自己**：探测命令本身是 `sh -c "pgrep -f reamicro-watchdog ..."`，
     *   它的命令行里就含这个字符串，于是没有看门狗也永远匹配得到（实机表现在无 root 的机器上
     *   也显示"看门狗运行中"）。用 `[r]eamicro` 这种写法让模式匹配不到自己的字面量。
     * - `pgrep` 不是所有 ROM 都带（toybox 版本差异），所以失败时退回 `ps -A` 过滤。
     */
    private fun isWatchdogRunning(): Boolean =
        probeYes("pgrep -f '[r]eamicro-watchdog' >/dev/null && echo yes") ||
            probeYes("ps -A | grep '[r]eamicro-watchdog' | grep -v grep >/dev/null && echo yes")

    /** 一次 root 命令的结果。保留退出码——只看输出会把"找不到 su"的报错当成成功。 */
    private data class RootCommand(val exitCode: Int, val output: String)

    /**
     * 执行一条 root 命令。
     *
     * `su` 的位置各机型不同（Magisk 在 /system/bin、KernelSU 在 /data/adb/ksu/bin 并会加入 PATH），
     * 所以只依赖 PATH，交给 `su` 自己解析；`su` 不存在或未被授权时 ProcessBuilder 会抛异常或
     * 命令非零退出，由调用方转成"无 root"。
     */
    private fun runRoot(command: String, timeoutSeconds: Long): RootCommand {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("root 命令超时")
        }
        return RootCommand(process.exitValue(), output.trim())
    }

    private const val LOG_TAG = "ReaMicroRoot"
    private const val PREFS_NAME = "reamicro_root_wake"
    private const val KEY_ENABLED = "enabled"
}
