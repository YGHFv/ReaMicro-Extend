package com.reamicro.fix.cloud.root

import android.os.Process
import com.reamicro.fix.cloud.local.LocalTaskEngine
import com.reamicro.fix.cloud.local.LocalTaskKey
import com.reamicro.fix.cloud.local.LocalTaskRunner
import com.reamicro.fix.cloud.local.rescheduleLocalTasks
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

object RootTaskMain {
    // Private reply to watchdog.sh. Older APKs return 0 and keep the 15-minute recovery probe.
    private const val WATCHDOG_QUEUE_CLEAR = 10

    @JvmStatic
    fun main(arguments: Array<String>) {
        try {
            val command = arguments.firstOrNull().orEmpty()
            check(Process.myUid() == 0) { "需要 Root" }
            val repository = RootTaskRepository(File(RootTaskRepository.STATE_DIRECTORY)) {
                File(RootTaskRepository.MODULE_DIRECTORY, "runner.sh").isFile &&
                    !File(RootTaskRepository.MODULE_DIRECTORY, "disable").exists() &&
                    !File(RootTaskRepository.MODULE_DIRECTORY, "remove").exists()
            }
            when (command) {
                "enable" -> repository.enable(readInput())
                "configure" -> repository.configure(readInput())
                "snapshot" -> Unit
                "snapshot-part" -> {
                    respond(RootTaskSnapshotTransport.part(File(RootTaskRepository.STATE_DIRECTORY), readInput()))
                    return
                }
                "reschedule" -> {
                    check(repository.executionEnabled()) { "Root 后台任务未启用" }
                    val count = repository.withExecutionLock { rescheduleLocalTasks(repository, System.currentTimeMillis()) }
                        ?: error("任务正在执行，请稍后重排")
                    respondSnapshot(repository.snapshot().put("rescheduledCount", count))
                    return
                }
                "kick" -> {
                    check(repository.executionEnabled()) { "Root 唤醒未启用" }
                    repository.requestRun(readInput())
                }
                "ack" -> {
                    val ids = readInput().optJSONArray("ids")
                    repository.acknowledge((0 until (ids?.length() ?: 0)).map { ids!!.getString(it) }.toSet())
                }
                "disable" -> {
                    repository.disable()
                    repository.withExecutionLock(wait = true) { repository.clearCredentials() }
                }
                "run" -> {
                    val queueClear = repository.withExecutionLock { runTasks(repository) }
                    if (arguments.getOrNull(1) == "--watchdog") {
                        if (queueClear == null || Thread.currentThread().isInterrupted) exitProcess(1)
                        if (queueClear) exitProcess(WATCHDOG_QUEUE_CLEAR)
                        return
                    }
                }
                else -> error("不支持的 Root 指令")
            }
            if (Thread.currentThread().isInterrupted) {
                respond(JSONObject().put("schema", RootTaskRepository.SCHEMA).put("cancelled", true))
            } else respondSnapshot(repository.snapshot())
        } catch (error: Throwable) {
            val detail = error.message?.take(500).orEmpty()
            respond(JSONObject().put("schema", RootTaskRepository.SCHEMA)
                .put("error", if (detail.isBlank()) error.javaClass.simpleName else "${error.javaClass.simpleName}: $detail"))
            exitProcess(1)
        }
    }

    private fun runTasks(repository: RootTaskRepository): Boolean {
        if (!repository.executionEnabled()) return false
        val request = repository.takeRequest()
        val accountId = request?.optString("accountId").orEmpty()
        val taskType = request?.optString("taskType").orEmpty()
        val key = if (accountId.isNotBlank() && taskType.isNotBlank()) LocalTaskKey(accountId, taskType) else null
        val network = RootTaskNetwork.preparation { !repository.executionEnabled() }
        RootNightDiagnostics.begin()
        RootDirectNetwork.begin()
        val completed = try {
            LocalTaskEngine(repository,
                beforeExecute = { id, task ->
                    notifyProgress(repository, id, task.taskType, "waking")
                    RootDirectNetwork.use(network.ensureReady())
                },
                onExecuting = { id, task -> notifyProgress(repository, id, task.taskType, "running") },
                onFinished = { id, task -> notifyProgress(repository, id, task.taskType, "finished") },
            )
                .runDue(force = request?.optBoolean("force") == true, requested = key,
                    expectedAccountGeneration = request?.optString("accountGeneration"))
        } catch (error: Throwable) {
            RootNightDiagnostics.abort(error)
            throw error
        } finally {
            RootDirectNetwork.clear()
        }
        RootNightDiagnostics.finish(completed)
        if (Thread.currentThread().isInterrupted) return false
        val pending = repository.pendingNotifications() ?: return false
        if (pending.length() == 0) return true
        notifyModule(repository, pending)
        return repository.pendingNotifications()?.length() == 0
    }

    private fun readInput(): JSONObject {

        val text = System.`in`.bufferedReader(Charsets.UTF_8).use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(result.length + count <= 4 * 1024 * 1024) { "配置过大" }
                result.append(buffer, 0, count)
            }
            result.toString()
        }
        return JSONObject(text.ifBlank { "{}" })
    }

    private fun notifyModule(repository: RootTaskRepository, pending: JSONArray) {
        val delivery = RootTaskNotificationDelivery(30_000L, 10L, cancelled = { !repository.executionEnabled() })
        // 任务跑完立刻直投：广播走快速通道，广播没被接收器确认时改用免界面 Activity 兜底。
        // 两者 notificationId 相同，接收端按投递回执幂等去重，重复投递不会出两条通知。
        for (index in 0 until pending.length()) {
            if (!delivery.hasBudget) break
            val item = pending.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            val taskType = item.optString("taskType")
            val result = item.optString("result")
            val title = LocalTaskRunner.localTaskTitle(taskType) + if (result == "success") "" else "异常"
            val message = RootTaskNotificationBroadcast(
                id = id,
                title = title,
                text = item.optString("message"),
                result = result,
                items = item.optString("items"),
                accountId = item.optString("accountId"),
                accountLabel = item.optString("accountLabel"),
                taskType = taskType,
                accountGeneration = item.optString("accountGeneration"),
            )
            try {
                if (delivery.deliver(message)) repository.acknowledge(setOf(id))
            } catch (error: InterruptedException) {
                throw error
            } catch (failure: Exception) {
                logFailure("notify_failed $id ${failure.javaClass.simpleName}")
            }
        }
        if (Thread.currentThread().isInterrupted || !repository.executionEnabled()) return
        // 同步执行记录，并重试未确认的 Activity 投递；已确认项不再依赖模块重新申请 SU。
        try {
            RootCommandRunner.executeTransient(ProcessBuilder("/system/bin/am", "broadcast", "--user", "0", "--include-stopped-packages",
                "-f", "0x01000000",
                "-a", RootTaskRepository.SYNC_ACTION, "-n", RootTaskRepository.SYNC_COMPONENT), timeoutSeconds = 10)
        } catch (error: InterruptedException) {
            throw error
        } catch (failure: Exception) {
            logFailure("sync_broadcast ${failure.javaClass.simpleName}")
        }
    }

    private fun notifyProgress(repository: RootTaskRepository, accountId: String, type: String, phase: String) {
        try {
            RootTaskNotificationDelivery(6_000L, 3L, cancelled = { !repository.executionEnabled() })
                .deliver(RootTaskNotificationBroadcast(
                id = "progress:$accountId:$type", title = "", text = "", result = "", items = "",
                accountId = accountId, accountLabel = repository.accountLabel(accountId), taskType = type,
                phase = phase, eventAt = System.currentTimeMillis(),
                accountGeneration = repository.accountGeneration(accountId),
            ))
        } catch (error: InterruptedException) {
            throw error
        } catch (_: Exception) {
            logFailure("progress_failed account=$accountId type=$type phase=$phase")
        }
    }

    /** 把 Root 侧异常写进状态目录日志，避免静默丢通知后无从排查。 */
    private fun logFailure(detail: String) {
        runCatching {
            val log = File(RootTaskRepository.STATE_DIRECTORY, "daemon.log")
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(System.currentTimeMillis()))
            log.appendText("$stamp ${detail.replace('\n', ' ')}\n", Charsets.UTF_8)
        }
    }

    private fun respond(value: JSONObject) { println("REAMICRO_KSU:$value") }
    private fun respondSnapshot(value: JSONObject) {
        respond(RootTaskSnapshotTransport.prepare(File(RootTaskRepository.STATE_DIRECTORY), value))
    }
}
