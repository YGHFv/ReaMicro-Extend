package com.reamicro.fix.cloud.root

import android.os.Process
import com.reamicro.fix.cloud.local.LocalTaskEngine
import com.reamicro.fix.cloud.local.LocalTaskKey
import com.reamicro.fix.cloud.local.CloudTaskLocalRunner
import com.reamicro.fix.cloud.local.rescheduleLocalTasks
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
                "configure" -> repository.configure(readInput())
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
                        val engine = LocalTaskEngine(repository, onCompleted = { account, task, outcome ->
                            if (outcome.notify) {
                                val items = outcome.detail.optJSONArray(CloudTaskLocalRunner.KEY_REWARD_ITEMS)?.toString().orEmpty()
                                repository.addNotification(account, task.taskType, outcome.result, outcome.message, items)
                            }
                        })
                        val completed = engine.runDue(force = request?.optBoolean("force") == true, requested = key)
                        if (completed.isNotEmpty()) notifyModule()
                    }
                }
                else -> error("不支持的 Root 指令")
            }
            respond(repository.snapshot())
        } catch (error: Throwable) {
            respond(JSONObject().put("schema", RootTaskRepository.SCHEMA).put("error", error.javaClass.simpleName))
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

    private fun notifyModule() {
        runCatching {
            RootCommandRunner.execute(ProcessBuilder("/system/bin/am", "broadcast", "--user", "0", "--include-stopped-packages",
                "-a", RootTaskRepository.SYNC_ACTION, "-n", RootTaskRepository.SYNC_COMPONENT), timeoutSeconds = 10)
        }
    }

    private fun respond(value: JSONObject) { println("REAMICRO_KSU:$value") }
}
