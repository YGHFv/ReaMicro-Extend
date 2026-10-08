package com.reamicro.fix.cloud.root

import android.content.Context
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.R
import com.reamicro.fix.i18n.moduleString
import com.reamicro.fix.cloud.local.LocalTaskKey
import com.reamicro.fix.cloud.local.LocalTaskRunner
import com.reamicro.fix.cloud.local.LocalTaskStore
import com.reamicro.fix.logging.ModuleAndroidLog
import com.reamicro.fix.notification.CloudTaskNotifications
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

internal object RootTaskBridge {

    @Volatile var lastInspection: RootModuleStatus? = null
        private set
    fun wasAccessRequested(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("accessRequested", false)
    fun customSuPath(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("suPath", "").orEmpty()

    @Synchronized
    fun setCustomSuPath(context: Context, value: String) {
        RootCommandRunner.setCustomSuPath(value)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("suPath", value.trim()).commit()) { "无法保存 SU 路径" }
        lastInspection = null
    }

    private fun prepareTransport(context: Context) = RootCommandRunner.setCustomSuPath(customSuPath(context))
    private const val PREFS = "reamicro_ksu_tasks"

    private const val BUNDLED_MODULE_DIRECTORY = "module"
    private const val LOG_TAG = "ReaMicroRoot"
    private val syncLock = Any()
    private var syncQueued = false
    private var syncDirty = false
    private val syncCallbacks = mutableListOf<() -> Unit>()
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ReaMicroRootBridge").apply { isDaemon = true } }

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

    private const val STATUS_SWITCHING = "handoff_to_ksu"
    private const val STATUS_DISABLED = "root_disabled"
    private const val STATUS_RUNNING = "daemon_running"
    private const val STATUS_NO_HEARTBEAT = "daemon_no_heartbeat"
    private const val STATUS_ERROR = "operation_error"

    fun lastOperationError(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("statusError", "").orEmpty()
    }

    @Synchronized
    fun inspect(context: Context, authorizationTimeoutSeconds: Long = 60): RootModuleStatus {
        prepareTransport(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("accessRequested", true).apply()
        val access = RootCommandRunner.inspectAccess(authorizationTimeoutSeconds)
        return RootModuleInspector.inspect(access) { script ->
            RootCommandRunner.runAuthorized(access.executable, script, timeoutSeconds = 10)
        }.copy(checkedAt = System.currentTimeMillis()).also { lastInspection = it }
    }

    @Synchronized
    fun enable(context: Context): String = LocalTaskRunner.withTaskControl {
        require(android.os.Process.myUid() / 100000 == 0) { context.moduleString(R.string.error_ksu_primary_user) }
        try {
            val access = inspect(context)
            if (!access.rootAvailable) throw RootAccessException(access.access)
            ensureModuleInstalled(context)?.let { hint ->
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("status", "module_pending").remove("statusError").commit()
                return@withTaskControl hint
            }
            cleanupLegacyWake(context)
            handoff(context).enable(LocalTaskStore { context }.executionPayload())
            startDaemon(context)
        } catch (error: Throwable) {
            recordFailure(context, error)
            throw error
        }
        context.moduleString(R.string.toast_ksu_enabled)
    }

    private fun manager(context: Context): RootModuleManager =
        RootModuleManager.parse(RootCommandRunner.run(RootModuleManager.detectionScript(RootCommandRunner.selectedExecutable()), timeoutSeconds = 15))
            ?: error(context.moduleString(R.string.error_ksu_missing_ksud))

    private fun lifecycle(context: Context) = RootModuleLifecycle(
        inspect = { inspect(context, authorizationTimeoutSeconds = 15) },
        manager = { manager(context) },
        execute = { script, timeout -> RootCommandRunner.run(script, timeoutSeconds = timeout) },
        install = { installer ->
            val payload = extractBundledModule(context)
            val result = try {
                RootCommandRunner.run(installer.install(payload.absolutePath), timeoutSeconds = 120)
            } finally {
                payload.delete()
            }
            check(result.exitCode == 0) {
                context.moduleString(R.string.error_ksu_install, result.diagnosticOutput.takeLast(700))
            }
        },
    )

    private fun ensureModuleInstalled(context: Context): String? =
        when (lifecycle(context).prepare(BuildConfig.ROOT_MODULE_VERSION_CODE)) {
            RootModuleInstallDecision.READY -> null
            RootModuleInstallDecision.REBOOT -> context.moduleString(R.string.ksu_reboot_required)
            RootModuleInstallDecision.INSTALL -> error("模块准备状态错误")
        }

    @Synchronized
    fun installOrUpdate(context: Context): String {
        require(android.os.Process.myUid() / 100000 == 0) { context.moduleString(R.string.error_ksu_primary_user) }
        val status = inspect(context)
        if (!status.rootAvailable) throw RootAccessException(status.access)
        val hint = ensureModuleInstalled(context)
        return hint ?: context.moduleString(R.string.execution_module_up_to_date)
    }

    private fun extractBundledModule(context: Context): File {
        val names = context.assets.list(BUNDLED_MODULE_DIRECTORY).orEmpty()
            .filter { it.startsWith("ReaMicro-Automation-Root-") && it.endsWith(".zip", ignoreCase = true) }
        check(names.size == 1) { context.moduleString(R.string.error_ksu_missing_bundle) }
        val directory = File(context.cacheDir, "root-module")
        check(directory.isDirectory || directory.mkdirs()) { context.moduleString(R.string.error_ksu_missing_bundle) }
        val payload = File.createTempFile("reamicro-root-", ".zip", directory)
        try {
            context.assets.open("$BUNDLED_MODULE_DIRECTORY/${names.single()}").use { input ->
                payload.outputStream().use { output -> input.copyTo(output) }
            }
            RootModuleBundle.verify(payload, BuildConfig.ROOT_MODULE_VERSION_CODE)
            return payload
        } catch (error: Throwable) {
            payload.delete()
            throw error
        }
    }

    @Synchronized
    fun disable(context: Context, remove: Boolean = true): String = LocalTaskRunner.withTaskControl {
        val wasRoot = isEnabled(context)
        val status = inspect(context)
        if (!status.rootAvailable) throw RootAccessException(status.access)
        try {
            check(status.rootAvailable) { status.error.ifBlank { context.moduleString(R.string.execution_root_permission_hint) } }
            check(status.inspectionComplete) { status.error }
            if (wasRoot) {

                handoff(context).disable()
            } else if (status.statePresent) {

                command(context, "disable", timeoutSeconds = 60)
            }
            cleanupLegacyWake(context)
            stopDaemon(context)
            val pendingReboot = if (remove) lifecycle(context).remove() else {
                lifecycle(context).pause()
                false
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("status", STATUS_DISABLED).remove("statusError").commit()
            if (!remove) return@withTaskControl context.moduleString(R.string.execution_module_paused)
            context.moduleString(if (pendingReboot) R.string.root_removal_pending else R.string.root_disabled_done)
        } catch (error: Throwable) {
            recordFailure(context, error)
            throw error
        }
    }

    private fun cleanupLegacyWake(context: Context) {
        val result = RootCommandRunner.run(RootProcessCleanup.legacy(), timeoutSeconds = 20)
        check(result.exitCode == 0) { context.moduleString(R.string.error_root_legacy_cleanup) }
        check(context.getSharedPreferences("reamicro_root_wake", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", false).commit()) { context.moduleString(R.string.error_execution_mode_save) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("legacyWakeMigrated", true).commit()
    }

    private fun stopDaemon(context: Context) {
        val module = RootTaskRepository.MODULE_DIRECTORY
        val state = RootTaskRepository.STATE_DIRECTORY
        val stopped = RootCommandRunner.run(
            "if [ -f '$module/stop.sh' ]; then sh '$module/stop.sh'; " +
                "else if [ -d '$state' ]; then umask 077; touch '$state/stop' || exit 1; fi; " +
                RootProcessCleanup.oldSupervisor() + "; fi",
            timeoutSeconds = 25,
        )
        check(stopped.exitCode == 0) { context.moduleString(R.string.error_root_stop) }
    }

    @Synchronized
    fun synchronize(context: Context, force: Boolean = false, requested: LocalTaskKey? = null) {
        if (!isEnabled(context)) return
        prepareTransport(context)
        try {
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("legacyWakeMigrated", false)) {
                cleanupLegacyWake(context)
            }
            val snapshot = command(context, "configure", LocalTaskStore { context }.executionPayload())
            applySnapshot(context, snapshot)
            check(snapshot.optBoolean("moduleEnabled", true)) { context.moduleString(R.string.error_ksu_disabled) }
            startDaemon(context)
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

    private fun handoff(context: Context) = RootExecutionHandoff(
        persistMode = { enabled ->
            check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled)
                .putString("status", if (enabled) STATUS_SWITCHING else STATUS_DISABLED).remove("statusError").commit()) { context.moduleString(R.string.error_execution_mode_save) }
        },
        exchange = { action, payload -> command(context, action, payload, if (action == "disable") 60 else 30) },
        restore = { snapshot -> applySnapshot(context, snapshot) },
    )

    @Synchronized
    fun reschedule(context: Context): Int {
        prepareTransport(context)
        val snapshot = command(context, "reschedule")
        applySnapshot(context, snapshot)
        return snapshot.optInt("rescheduledCount")
    }

    private fun startDaemon(context: Context) {
        val started = RootCommandRunner.run("sh ${RootTaskRepository.MODULE_DIRECTORY}/service.sh start </dev/null", timeoutSeconds = 20)
        check(started.exitCode == 0) {
            context.moduleString(R.string.error_ksu_daemon) + ": " + started.diagnosticOutput.takeLast(400)
        }
    }

    private fun recordFailure(context: Context, error: Throwable) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("status", STATUS_ERROR).putString("statusError", error.message.orEmpty()).commit()
    }

    fun requestSync(context: Context, onComplete: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        if (appContext.packageName != CloudTaskNotifications.MODULE_PACKAGE_NAME || !isEnabled(appContext)) {
            onComplete?.invoke()
            return
        }
        synchronized(syncLock) {
            onComplete?.let(syncCallbacks::add)
            syncDirty = true
            if (syncQueued) return
            syncQueued = true
        }
        worker.execute {
            while (true) {

                val callbacks = synchronized(syncLock) {
                    syncDirty = false
                    syncCallbacks.toList().also { syncCallbacks.clear() }
                }
                try {
                    synchronize(appContext)
                } catch (error: Throwable) {
                    ModuleAndroidLog.error(LOG_TAG, "Root 状态同步失败：${error.message}")
                }
                callbacks.forEach { callback ->
                    runCatching(callback).onFailure {
                        ModuleAndroidLog.error(LOG_TAG, "Root 同步回调失败：${it.message}")
                    }
                }
                val again = synchronized(syncLock) {
                    if (syncDirty) true else { syncQueued = false; false }
                }
                if (!again) break
            }
        }
    }

    private fun applySnapshot(context: Context, snapshot: JSONObject) {
        LocalTaskStore { context }.applySnapshot(snapshot)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("statusError").commit()
        val heartbeat = snapshot.optLong("heartbeatAt")
        val alive = heartbeat > 0L && System.currentTimeMillis() - heartbeat in 0..120_000L
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("status",
            if (alive) STATUS_RUNNING else STATUS_NO_HEARTBEAT).commit()
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
        val invocation = "CLASSPATH=$apk /system/bin/app_process /system/bin com.reamicro.fix.cloud.root.RootTaskMain $action"
        val result = RootCommandRunner.run(invocation, input?.toString(), timeoutSeconds)
        check(!result.truncated) { "Root 返回数据超出上限，拒绝应用不完整快照" }
        val line = result.output.lineSequence().lastOrNull { it.startsWith("REAMICRO_KSU:") }
            ?: error(context.moduleString(R.string.error_ksu_unavailable))
        val response = JSONObject(line.removePrefix("REAMICRO_KSU:"))
        check(result.exitCode == 0 && !response.has("error")) { context.moduleString(R.string.error_ksu_command, response.optString("error", context.moduleString(R.string.error_exit_code, result.exitCode))) }
        check(response.optInt("schema") == RootTaskRepository.SCHEMA) { context.moduleString(R.string.error_ksu_protocol) }
        return response
    }
}
