package com.reamicro.fix.cloud.local

import android.content.Context
import android.os.Looper
import android.widget.Toast
import com.reamicro.fix.core.HostPackages
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.TaskCancellation
import com.reamicro.fix.xposed.HookRuntime
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** One batch per host launch; activity recreation and resume never create a second batch. */
internal object HostLocalTaskRunner {
    private val started = AtomicBoolean()
    @Volatile private var cancellation: TaskCancellation? = null

    fun start(context: Context, accountId: String, token: String, isCurrent: () -> Boolean) {
        if (!HostPackages.contains(context.packageName) || accountId.isBlank() || token.isBlank() ||
            !runCatching(isCurrent).getOrDefault(false)) return
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val active = TaskCancellation()
        cancellation = active
        try {
            ModuleResources.maintenanceThread({
                try {
                    TaskCancellation.current()?.register(active::cancel).use {
                        active.run {
                            if (!isCurrent()) return@run
                            // Host hot reload creates a new class loader, but must not run a second batch.
                            val prefs = app.getSharedPreferences("reamicro_host_task_launch", Context.MODE_PRIVATE)
                            val boot = runCatching { android.provider.Settings.Global.getInt(app.contentResolver, "boot_count", -1) }
                                .getOrDefault(-1)
                            val launch = "$boot:${android.os.Process.myPid()}:${android.os.Process.getStartUptimeMillis()}"
                            val gate = HostTaskLaunchGate({ prefs.getString("launch", null) }, { prefs.edit().putString("launch", it).commit() })
                            if (!gate.claim(launch)) return@run
                            val store = LocalTaskStore { app }
                            store.refreshToken(accountId, token)
                            val current = { !active.isCancelled && isCurrent() }
                            val scoped = hostTaskRepository(store, accountId, current)
                            runHostTaskBatch(LocalTaskEngine(scoped), current) {
                                ModuleResources.handler(Looper.getMainLooper()).post {
                                    runCatching {
                                        if (current()) Toast.makeText(app,
                                            "自动任务已完成，详情请查看任务记录。", Toast.LENGTH_LONG).show()
                                    }.onFailure { HookRuntime.logError("ReaMicro host task completion toast failed", it) }
                                }
                            }
                        }
                    }
                } catch (_: java.util.concurrent.CancellationException) {
                    // Account switching and hot reload cancel this batch without starting another.
                } catch (error: Exception) {
                    HookRuntime.logError("ReaMicro host task batch failed", error)
                }
            }, "ReaMicro-HostTasks", onFinished = { cancellation = null }).start()
        } catch (error: Exception) {
            cancellation = null
            started.set(false)
            HookRuntime.logError("ReaMicro host task batch could not start", error)
        }
    }

    fun cancel() { cancellation?.cancel() }
}

internal class HostTaskLaunchGate(private val read: () -> String?, private val write: (String) -> Boolean) {
    @Synchronized fun claim(launch: String): Boolean {
        require(launch.isNotBlank())
        if (read() == launch) return false
        check(write(launch)) { "宿主任务启动状态保存失败" }
        return true
    }
}

internal fun runHostTaskBatch(
    engine: LocalTaskEngine, isCurrent: () -> Boolean, onCompleted: () -> Unit,
): List<LocalTaskRecord> {
    val records = engine.runDue(maxTasks = LocalTaskCredentials.taskTypes.size, force = true, hostBatch = true)
    if (records.isNotEmpty() && !Thread.currentThread().isInterrupted &&
        TaskCancellation.current()?.isCancelled != true && isCurrent()) onCompleted()
    return records
}

/** Host execution cannot enumerate other accounts or publish the module app's notification outbox. */
internal fun hostTaskRepository(
    store: LocalTaskRepository, accountId: String, isCurrent: () -> Boolean,
): LocalTaskRepository = object : LocalTaskRepository by store {
    override fun accountIds(): List<String> = listOf(accountId)
    override fun executionEnabled(): Boolean = isCurrent()
    override fun recordExecution(accountId: String, task: LocalTask, state: JSONObject, record: LocalTaskRecord, notify: Boolean) =
        store.recordExecution(accountId, task, state, record, notify = false)
}
