package com.reamicro.fix.cloud.ksu

import android.os.Process
import com.reamicro.fix.cloud.local.LocalTaskEngine
import com.reamicro.fix.cloud.local.LocalTaskKey
import com.reamicro.fix.cloud.local.CloudTaskLocalRunner
import com.reamicro.fix.cloud.local.nextDailyRunAt
import com.reamicro.fix.cloud.local.rescheduleLocalTasks
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess

object KsuTaskMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        try {
            val command = arguments.firstOrNull().orEmpty()
            if (command == "self-test") {
                selfTest()
                return
            }
            check(Process.myUid() == 0) { "需要 root" }
            val repository = KsuTaskRepository(File(KsuTaskRepository.STATE_DIRECTORY)) {
                File(KsuTaskRepository.MODULE_DIRECTORY).isDirectory &&
                    !File(KsuTaskRepository.MODULE_DIRECTORY, "disable").exists() &&
                    !File(KsuTaskRepository.MODULE_DIRECTORY, "remove").exists()
            }
            when (command) {
                "enable" -> {
                    val input = readInput()
                    check(repository.withExecutionLock { repository.configure(input, enable = true); true } == true) { "任务正在执行" }
                }
                "configure" -> repository.configure(readInput())
                "snapshot" -> Unit
                "reschedule" -> {
                    check(repository.executionEnabled()) { "KSU 执行未启用" }
                    val count = repository.withExecutionLock { rescheduleLocalTasks(repository, System.currentTimeMillis()) }
                        ?: error("任务正在执行，请稍后重排")
                    respond(repository.snapshot().put("rescheduledCount", count))
                    return
                }
                "kick" -> {
                    check(repository.executionEnabled()) { "KSU 模式未启用" }
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
                            if (outcome.notify) repository.addNotification(account, task.taskType, outcome.result, outcome.message)
                        })
                        val completed = engine.runDue(force = request?.optBoolean("force") == true, requested = key)
                        if (completed.isNotEmpty()) notifyModule()
                    }
                }
                else -> error("不支持的 KSU 指令")
            }
            respond(repository.snapshot())
        } catch (error: Throwable) {
            if (arguments.firstOrNull() == "self-test") error.printStackTrace(System.err)
            respond(JSONObject().put("schema", KsuTaskRepository.SCHEMA).put("error", error.javaClass.simpleName))
            exitProcess(1)
        }
    }

    private fun readInput(): JSONObject {
        val text = System.`in`.bufferedReader(Charsets.UTF_8).use { it.readText() }
        require(text.length <= 4 * 1024 * 1024) { "配置过大" }
        return JSONObject(text.ifBlank { "{}" })
    }

    private fun selfTest() {
        val directory = File("/data/local/tmp", "reamicro-ksu-selftest-${Process.myPid()}")
        check(directory.mkdir()) { "无法创建独立诊断目录" }
        try {
            val repository = KsuTaskRepository(directory)
            val tasks = JSONObject().put("cloud_auto_read", JSONObject().put("enabled", true).put("nextRunAt", 1L))
            repository.configure(JSONObject().put("accounts", JSONObject().put("diagnostic",
                JSONObject().put("tasks", tasks).put("token", "diagnostic-only"))), enable = true)
            val engine = LocalTaskEngine(repository, execute = { _, _, _, _ ->
                CloudTaskLocalRunner.Outcome("success", "diagnostic", JSONObject().put("dailyReadMinutes", 1))
            })
            check(repository.withExecutionLock { engine.runDue().size } == 1)
            check(KsuTaskRepository(directory).runtimeState("diagnostic", "cloud_auto_read").optInt("dailyReadMinutes") == 1)
            check(!repository.snapshot().toString().contains("diagnostic-only"))
            check(Files.getPosixFilePermissions(directory.toPath()) == PosixFilePermissions.fromString("rwx------"))
            check(Files.getPosixFilePermissions(File(directory, "state.json").toPath()) == PosixFilePermissions.fromString("rw-------"))
            respond(JSONObject().put("schema", KsuTaskRepository.SCHEMA).put("uid", Process.myUid())
                .put("engine", "ok").put("persistence", "ok").put("credentialsRedacted", true)
                .put("privatePermissions", true)
                .put("nextRunAt", nextDailyRunAt("00:00", System.currentTimeMillis())))
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    private fun notifyModule() {
        runCatching {
            RootCommandRunner.execute(ProcessBuilder("/system/bin/am", "broadcast", "--user", "0", "--include-stopped-packages",
                "-a", KsuTaskRepository.SYNC_ACTION, "-n", "com.reamicro.fix/com.reamicro.fix.cloud.api.CloudTaskHeartbeatReceiver"), timeoutSeconds = 10)
        }
    }

    private fun respond(value: JSONObject) { println("REAMICRO_KSU:$value") }
}
