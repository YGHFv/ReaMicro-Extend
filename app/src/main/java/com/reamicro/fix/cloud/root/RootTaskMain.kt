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
    @JvmStatic
    fun main(arguments: Array<String>) {
        try {
            val command = arguments.firstOrNull().orEmpty()
            check(Process.myUid() == 0) { "需要 Root" }
            val repository = RootTaskRepository(File(RootTaskRepository.STATE_DIRECTORY)) {
                File(RootTaskRepository.MODULE_DIRECTORY).isDirectory &&
                    !File(RootTaskRepository.MODULE_DIRECTORY, "disable").exists() &&
                    !File(RootTaskRepository.MODULE_DIRECTORY, "remove").exists()
            }
            when (command) {
                "enable" -> {
                    val input = readInput()
                    check(repository.withExecutionLock { repository.configure(input, enable = true); true } == true) { "任务正在执行" }
                }
                "configure" -> readInput().let { input ->
                    repository.configure(input, enable = input.optBoolean("enable", false))
                }
                "snapshot" -> Unit
                "reschedule" -> {
                    check(repository.executionEnabled()) { "Root 后台任务未启用" }
                    val count = repository.withExecutionLock { rescheduleLocalTasks(repository, System.currentTimeMillis()) }
                        ?: error("任务正在执行，请稍后重排")
                    respond(repository.snapshot().put("rescheduledCount", count))
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
                "run" -> repository.withExecutionLock {
                    if (repository.executionEnabled()) {
                        val request = repository.takeRequest()
                        val accountId = request?.optString("accountId").orEmpty()
                        val taskType = request?.optString("taskType").orEmpty()
                        val key = if (accountId.isNotBlank() && taskType.isNotBlank()) LocalTaskKey(accountId, taskType) else null
                        LocalTaskEngine(repository).runDue(force = request?.optBoolean("force") == true, requested = key)
                        repository.snapshot().optJSONArray("notifications")?.takeIf { it.length() > 0 }?.let(::notifyModule)
                    }
                }
                else -> error("不支持的 Root 指令")
            }
            respond(repository.snapshot())
        } catch (error: Throwable) {
            val detail = error.message?.take(500).orEmpty()
            respond(JSONObject().put("schema", RootTaskRepository.SCHEMA)
                .put("error", if (detail.isBlank()) error.javaClass.simpleName else "${error.javaClass.simpleName}: $detail"))
            exitProcess(1)
        }
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

    private fun notifyModule(pending: JSONArray) {
        // 任务跑完立刻直投：广播走快速通道，广播没被接收器确认时改用免界面 Activity 兜底。
        // 两者 notificationId 相同，接收端按投递回执幂等去重，重复投递不会出两条通知。
        for (index in 0 until pending.length()) {
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
            )
            val delivery = runCatching {
                val broadcast = runCatching {
                    RootCommandRunner.execute(
                        ProcessBuilder(rootTaskNotificationBroadcastArgs(message)), timeoutSeconds = 10,
                    )
                }.getOrNull()
                // 广播被系统冻结时 am 依旧返回 0，只有接收器回执 result=-1 才算真的落地。
                if (broadcast != null && broadcast.exitCode == 0 && rootTaskBroadcastConfirmed(broadcast.output)) return@runCatching
                val activity = RootCommandRunner.execute(
                    ProcessBuilder(rootTaskNotificationActivityArgs(message)), timeoutSeconds = 10,
                )
                check(activity.exitCode == 0) {
                    "broadcast=${broadcast?.exitCode ?: -1} activity=${activity.exitCode} " +
                        ((broadcast?.diagnosticOutput.orEmpty() + activity.diagnosticOutput).take(200))
                }
            }
            // 直投失败时保留待发队列，交给后续 Root 同步兜底，并在状态目录留痕便于排查。
            delivery.exceptionOrNull()?.let { failure ->
                logFailure("notify_failed $id ${failure.message?.take(200) ?: failure.javaClass.simpleName}")
            }
        }
        // 直投已把通知落地；这里的同步稍后负责把 Root 待发队列确认掉。
        runCatching {
            RootCommandRunner.execute(ProcessBuilder("/system/bin/am", "broadcast", "--user", "0", "--include-stopped-packages",
                "-f", "0x01000000",
                "-a", RootTaskRepository.SYNC_ACTION, "-n", RootTaskRepository.SYNC_COMPONENT), timeoutSeconds = 10)
        }.onFailure { logFailure("sync_broadcast ${it.message?.take(200) ?: it.javaClass.simpleName}") }
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
}
