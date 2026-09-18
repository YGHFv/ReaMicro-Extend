package com.reamicro.fix.cloud.ksu

import android.content.Context
import com.reamicro.fix.cloud.api.CloudTaskWakeScheduler
import com.reamicro.fix.cloud.local.LocalTaskKey
import com.reamicro.fix.cloud.local.LocalTaskRunner
import com.reamicro.fix.cloud.local.LocalTaskStore
import com.reamicro.fix.logging.ModuleAndroidLog
import com.reamicro.fix.notification.CloudTaskNotifications
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object KsuTaskBridge {
    private const val PREFS = "reamicro_ksu_tasks"
    private const val RUNNER = "${KsuTaskRepository.MODULE_DIRECTORY}/runner.sh"
    /** 内置模块包在 APK assets 里的目录；打包任务会放一个 `ReaMicro-Automation-KSU-*.zip`。 */
    private const val BUNDLED_MODULE_DIRECTORY = "ksu"
    private const val KSUD = "/data/adb/ksu/bin/ksud"
    private const val MODULE_UPDATE_DIRECTORY = "/data/adb/modules_update/reamicro_automation"
    private const val NO_KSUD_MARKER = "REAMICRO_NO_KSUD"
    private const val LOG_TAG = "ReaMicroKsu"
    private val queued = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ReaMicroKsuBridge").apply { isDaemon = true } }

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    fun statusText(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = if (isEnabled(context)) "KSU 独立执行（实验性）" else "Android 模块执行"
        val status = prefs.getString("status", "点「使用 KSU」会用 APK 内置的模块包自动安装（需 root）；普通 Root 看门狗只负责唤醒，不是独立执行。")
        return "$mode\n$status"
    }

    @Synchronized
    fun enable(context: Context): String = LocalTaskRunner.switchExecutionMode {
        require(android.os.Process.myUid() / 100000 == 0) { "KSU 模式目前只支持主用户" }
        // 没刷过就装 APK 里内置的那一份，用户不需要再去 GitHub 下载。
        ensureModuleInstalled(context)?.let { hint -> error(hint) }
        check(moduleReady()) { "配套 KSU 模块未启用，请在 KernelSU 管理器里启用模块并重启" }
        try {
            handoff(context).enable(LocalTaskStore { context }.executionPayload())
            startDaemon()
        } catch (error: Throwable) {
            recordFailure(context, error)
            throw error
        }
        CloudTaskWakeScheduler.schedule(context)
        "已切换到 KSU 独立执行；不再由 Android 模块重复运行本地任务"
    }

    /** 模块脚本存在且没被停用/标记卸载（KernelSU 用 disable/remove 标记停用与待卸载）。 */
    private fun moduleReady(): Boolean = RootCommandRunner.run(
        "test -x $RUNNER && test ! -e ${KsuTaskRepository.MODULE_DIRECTORY}/disable && test ! -e ${KsuTaskRepository.MODULE_DIRECTORY}/remove",
    ).exitCode == 0

    /**
     * 确保配套 KSU 模块可用：没刷过就从 APK 内置的 ZIP 装一遍。
     *
     * 返回 null 表示可以继续（原本就刷过，或这次装好且已生效）；返回非空字符串表示装完了但
     * 还需要用户做点什么（KernelSU 对某些安装方式会先落到 modules_update，重启后才生效），
     * 调用方据此提示并且**不要**切换执行模式。
     */
    private fun ensureModuleInstalled(context: Context): String? {
        if (moduleReady()) return null
        val payload = extractBundledModule(context)
        val quoted = "'" + payload.absolutePath.replace("'", "'\\''") + "'"
        val command = "if [ -x $KSUD ]; then $KSUD module install $quoted; " +
            "elif command -v ksud >/dev/null 2>&1; then ksud module install $quoted; " +
            "else echo $NO_KSUD_MARKER; exit 3; fi"
        val result = runCatching { RootCommandRunner.run(command, timeoutSeconds = 120) }
            .onFailure { payload.delete() }
            .getOrThrow()
        payload.delete()
        if (result.output.contains(NO_KSUD_MARKER)) {
            error("当前 Root 环境不是 KernelSU（找不到 ksud），无法自动安装配套模块；请手动刷入模块 ZIP 后重试")
        }
        val tail = result.output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
        check(result.exitCode == 0) { "内置 KSU 模块安装失败：$tail" }
        if (moduleReady()) return null
        if (File(MODULE_UPDATE_DIRECTORY, "runner.sh").isFile) {
            return "内置 KSU 模块已安装，需要重启设备后才生效；重启后请再次选择「使用 KSU」"
        }
        error("内置 KSU 模块安装后仍未找到 runner.sh：$tail")
    }

    /** 把内置模块包释放到应用私有缓存，交给 ksud 安装。 */
    private fun extractBundledModule(context: Context): File {
        val name = context.assets.list(BUNDLED_MODULE_DIRECTORY)
            ?.firstOrNull { it.endsWith(".zip", ignoreCase = true) }
            ?: error("APK 里没有内置 KSU 模块包，请更新模块应用")
        val directory = File(context.cacheDir, "ksu-module").apply { mkdirs() }
        val payload = File(directory, name)
        context.assets.open("$BUNDLED_MODULE_DIRECTORY/$name").use { input ->
            payload.outputStream().use { output -> input.copyTo(output) }
        }
        return payload
    }

    @Synchronized
    fun disable(context: Context): String = LocalTaskRunner.switchExecutionMode {
        if (!isEnabled(context)) return@switchExecutionMode "当前已使用 Android 模块执行"
        try {
            handoff(context).disable()
        } catch (error: Throwable) {
            recordFailure(context, error)
            throw error
        }
        CloudTaskWakeScheduler.schedule(context)
        "已切回 Android 模块执行"
    }

    @Synchronized
    fun synchronize(context: Context, force: Boolean = false, requested: LocalTaskKey? = null) {
        if (!isEnabled(context)) return
        try {
            val snapshot = command(context, "configure", LocalTaskStore { context }.executionPayload())
            applySnapshot(context, snapshot)
            check(snapshot.optBoolean("moduleEnabled", true)) { "KSU 模块已停用或卸载，请启用模块或切回 Android" }
            startDaemon()
            if (force || requested != null) {
                command(context, "kick", JSONObject().put("force", force).apply {
                    requested?.let { put("accountId", it.accountId); put("taskType", it.taskType) }
                })
            }
        } catch (error: Throwable) {
            recordFailure(context, error)
            throw error
        }
    }

    private fun handoff(context: Context) = KsuExecutionHandoff(
        persistMode = { enabled ->
            check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled)
                .putString("status", if (enabled) "KSU 接管中；若被中断，请同步状态或重试切换，Android 暂不执行"
                    else "已交回 Android 执行，KSU 私有目录中的登录凭据已清除").commit()) { "执行模式保存失败，未继续交接" }
        },
        exchange = { action, payload -> command(context, action, payload, if (action == "disable") 60 else 30) },
        restore = { snapshot -> applySnapshot(context, snapshot) },
    )

    @Synchronized
    fun reschedule(context: Context): Int {
        val snapshot = command(context, "reschedule")
        applySnapshot(context, snapshot)
        return snapshot.optInt("rescheduledCount")
    }

    private fun startDaemon() {
        val started = RootCommandRunner.run("sh ${KsuTaskRepository.MODULE_DIRECTORY}/service.sh </dev/null >/dev/null 2>&1", timeoutSeconds = 10)
        check(started.exitCode == 0) { "状态已交给 KSU，但守护脚本未能启动，请检查 KSU 日志或重启" }
    }

    private fun recordFailure(context: Context, error: Throwable) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("status", "KSU 操作未完成；为避免双跑，不自动回退：${error.message}").commit()
    }

    fun requestSync(context: Context, onComplete: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        if (appContext.packageName != CloudTaskNotifications.MODULE_PACKAGE_NAME || !isEnabled(appContext)) return
        dirty.set(true)
        if (!queued.compareAndSet(false, true)) return
        worker.execute {
            try {
                do {
                    dirty.set(false)
                    synchronize(appContext)
                } while (dirty.get())
                CloudTaskWakeScheduler.schedule(appContext)
            } catch (error: Throwable) {
                ModuleAndroidLog.error(LOG_TAG, "KSU 状态同步失败：${error.message}")
            } finally {
                queued.set(false)
                onComplete?.invoke()
                if (dirty.get()) requestSync(appContext)
            }
        }
    }

    private fun applySnapshot(context: Context, snapshot: JSONObject) {
        LocalTaskStore { context }.applySnapshot(snapshot)
        val heartbeat = snapshot.optLong("heartbeatAt")
        val alive = heartbeat > 0L && System.currentTimeMillis() - heartbeat in 0..120_000L
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("status",
            if (alive) "KSU 守护运行中；任务记录与状态已同步" else "暂未检测到近期守护心跳；请确认模块已启用并完成重启，休眠也会延迟心跳").commit()
        val pending = snapshot.optJSONArray("notifications") ?: return
        val delivered = JSONArray()
        for (index in 0 until pending.length()) {
            val item = pending.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val type = item.optString("taskType")
            val intent = CloudTaskNotifications.intent("ksu_$id", LocalTaskRunner.localTaskTitle(type),
                item.optString("message"), item.optString("result"), item.optString("items"))
            if (CloudTaskNotifications.post(context, intent, source = "ksu-task-runner")) delivered.put(id)
        }
        if (delivered.length() > 0) runCatching { command(context, "ack", JSONObject().put("ids", delivered)) }
            .onFailure { ModuleAndroidLog.error(LOG_TAG, "KSU 通知回执失败，下次同步重试") }
    }

    private fun command(context: Context, action: String, input: JSONObject? = null, timeoutSeconds: Long = 30): JSONObject {
        val apk = "'" + context.applicationInfo.sourceDir.replace("'", "'\\''") + "'"
        val invocation = "CLASSPATH=$apk /system/bin/app_process /system/bin com.reamicro.fix.cloud.ksu.KsuTaskMain $action"
        val result = RootCommandRunner.run(invocation, input?.toString(), timeoutSeconds)
        val line = result.output.lineSequence().lastOrNull { it.startsWith("REAMICRO_KSU:") }
            ?: error("KSU 执行器不可用，请检查刷入状态和 root 授权")
        val response = JSONObject(line.removePrefix("REAMICRO_KSU:"))
        check(result.exitCode == 0 && !response.has("error")) { "KSU 指令失败：${response.optString("error", "退出码 ${result.exitCode}")}" }
        check(response.optInt("schema") == KsuTaskRepository.SCHEMA) { "KSU 协议不兼容，请更新 APK 与 KSU 模块" }
        return response
    }
}
