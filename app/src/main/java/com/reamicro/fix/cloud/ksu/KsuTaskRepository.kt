package com.reamicro.fix.cloud.ksu

import com.reamicro.fix.cloud.local.LocalTask
import com.reamicro.fix.cloud.local.LocalTaskRecord
import com.reamicro.fix.cloud.local.LocalTaskRepository
import com.reamicro.fix.cloud.local.LocalTaskStore
import com.reamicro.fix.cloud.local.appendLocalTaskRecord
import com.reamicro.fix.cloud.local.applyLocalTaskExecution
import com.reamicro.fix.cloud.local.clearLocalTaskRecordsBefore
import com.reamicro.fix.cloud.local.localTaskFromJson
import com.reamicro.fix.cloud.local.localTaskRuntimeState
import com.reamicro.fix.cloud.local.nextLocalTaskAt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

internal class KsuTaskRepository(
    private val directory: File,
    private val moduleEnabled: () -> Boolean = { true },
) : LocalTaskRepository {
    init {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建 KSU 私有目录" }
        protect(directory, directory = true)
    }

    override fun accountIds(): List<String> = access { accounts(it).keys().asSequence().toList() }
    override fun list(accountId: String): List<LocalTask> = access { root ->
        val tasks = account(root, accountId)?.optJSONObject("tasks") ?: return@access emptyList()
        tasks.keys().asSequence().mapNotNull { type -> tasks.optJSONObject(type)?.let { localTaskFromJson(type, it) } }.toList()
    }
    override fun token(accountId: String): String = access { account(it, accountId)?.optString("token").orEmpty() }
    override fun runtimeState(accountId: String, taskType: String): JSONObject = access { root ->
        task(root, accountId, taskType)?.let(::localTaskRuntimeState) ?: JSONObject()
    }
    override fun executionEnabled(): Boolean = moduleEnabled() && access { it.optBoolean("enabled") }
    override fun recordState(accountId: String, taskType: String, state: JSONObject) {
        access(write = true) { root ->
            val current = task(root, accountId, taskType) ?: return@access
            for (key in LocalTaskStore.RUNTIME_STATE_KEYS) if (state.has(key)) current.put(key, state.get(key))
        }
    }
    override fun recordExecution(accountId: String, task: LocalTask, state: JSONObject, record: LocalTaskRecord) {
        access(write = true) { root ->
            val account = account(root, accountId) ?: return@access
            val current = task(root, accountId, task.taskType) ?: return@access
            applyLocalTaskExecution(current, task, state)
            appendLocalTaskRecord(account, record)
            root.put("lastRunAt", record.at)
        }
    }

    fun configure(payload: JSONObject, enable: Boolean = false) {
        val incoming = payload.optJSONObject("accounts") ?: error("缺少任务配置")
        require(incoming.length() <= 50) { "账号数量超限" }
        access(write = true) { root ->
            if (enable && !root.optBoolean("enabled")) {
                root.put("accounts", JSONObject(incoming.toString()))
            } else {
                check(root.optBoolean("enabled")) { "KSU 执行已停用，请重新选择模式" }
                incoming.keys().forEach { accountId ->
                    val source = incoming.optJSONObject(accountId) ?: return@forEach
                    val target = account(root, accountId) ?: JSONObject().also { accounts(root).put(accountId, it) }
                    clearLocalTaskRecordsBefore(target, source.optLong("recordsClearedAt"))
                    val sourceTasks = source.optJSONObject("tasks") ?: JSONObject()
                    val targetTasks = target.optJSONObject("tasks") ?: JSONObject().also { target.put("tasks", it) }
                    sourceTasks.keys().forEach { taskType ->
                        val config = sourceTasks.optJSONObject(taskType) ?: return@forEach
                        targetTasks.put(taskType, LocalTaskStore.mergeMirroredTask(targetTasks.optJSONObject(taskType), config, System.currentTimeMillis()))
                    }
                    val token = source.optString("token")
                    if (token.isNotBlank()) target.put("token", token)
                }
            }
            root.put("enabled", true).put("schema", SCHEMA)
        }
    }

    fun disable() { access(write = true) { it.put("enabled", false).remove("kick"); it.remove("requests") } }

    fun clearCredentials() {
        access(write = true) { root ->
            accounts(root).keys().forEach { accountId -> account(root, accountId)?.remove("token") }
        }
    }

    fun requestRun(request: JSONObject) {
        access(write = true) { root ->
            val requests = root.optJSONArray("requests") ?: JSONArray()
            require(requests.length() < 32) { "待执行请求过多，请稍后重试" }
            requests.put(JSONObject(request.toString()))
            root.put("requests", requests)
        }
    }

    fun takeRequest(): JSONObject? = access(write = true) { root ->
        (root.remove("kick") as? JSONObject)?.let { return@access it }
        val requests = root.optJSONArray("requests") ?: return@access null
        val request = requests.optJSONObject(0) ?: return@access null
        val remaining = JSONArray()
        for (index in 1 until requests.length()) remaining.put(requests.get(index))
        root.put("requests", remaining)
        request
    }

    fun addNotification(accountId: String, taskType: String, result: String, message: String) {
        access(write = true) { root ->
            val pending = root.optJSONArray("notifications") ?: JSONArray()
            pending.put(JSONObject().put("id", UUID.randomUUID().toString()).put("accountId", accountId)
                .put("taskType", taskType).put("result", result).put("message", message))
            val trimmed = JSONArray()
            for (index in (pending.length() - 100).coerceAtLeast(0) until pending.length()) trimmed.put(pending.get(index))
            root.put("notifications", trimmed)
        }
    }

    fun acknowledge(ids: Set<String>) {
        access(write = true) { root ->
            val pending = root.optJSONArray("notifications") ?: return@access
            val remaining = JSONArray()
            for (index in 0 until pending.length()) {
                val item = pending.optJSONObject(index) ?: continue
                if (item.optString("id") !in ids) remaining.put(item)
            }
            root.put("notifications", remaining)
        }
    }

    fun snapshot(): JSONObject = access { root ->
        val result = JSONObject(root.toString())
        accounts(result).keys().forEach { accountId -> account(result, accountId)?.remove("token") }
        result.remove("kick")
        result.remove("requests")
        result.put("schema", SCHEMA).put("nextTaskAt", nextTaskAt(root)).put("moduleEnabled", moduleEnabled())
        val heartbeat = File(directory, "heartbeat").takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()?.toLongOrNull() ?: 0L
        result.put("heartbeatAt", heartbeat * 1000L)
    }

    fun <Result> withExecutionLock(wait: Boolean = false, block: () -> Result): Result? {
        RandomAccessFile(File(directory, "execution.lock"), "rw").use { file ->
            val lock = if (wait) file.channel.lock() else file.channel.tryLock()
            if (lock == null) return null
            lock.use { return block() }
        }
    }

    private fun nextTaskAt(root: JSONObject): Long {
        if (!root.optBoolean("enabled")) return 0L
        if (root.has("kick") || (root.optJSONArray("requests")?.length() ?: 0) > 0) return 1L
        val now = System.currentTimeMillis()
        return accounts(root).keys().asSequence().flatMap { accountId ->
            val tasks = account(root, accountId)?.optJSONObject("tasks") ?: JSONObject()
            val checkin = tasks.optJSONObject("yeshe_checkin") ?: JSONObject()
            tasks.keys().asSequence().mapNotNull { taskType ->
                val task = tasks.optJSONObject(taskType) ?: return@mapNotNull null
                nextLocalTaskAt(localTaskFromJson(taskType, task), task, checkin, now)
            }
        }.minOrNull() ?: 0L
    }

    private fun <Result> access(write: Boolean = false, block: (JSONObject) -> Result): Result = synchronized(LOCK) {
        RandomAccessFile(File(directory, "state.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                val stateFile = File(directory, "state.json")
                val root = if (stateFile.isFile) JSONObject(stateFile.readText(Charsets.UTF_8)) else JSONObject().put("schema", SCHEMA)
                val result = block(root)
                if (write) {
                    atomicWrite("state.json", root.toString())
                    atomicWrite("next-run-at", nextTaskAt(root).let { if (it <= 0L) 0L else (it / 1000L).coerceAtLeast(1L) }.toString())
                }
                result
            }
        }
    }

    private fun atomicWrite(name: String, text: String) {
        val temporary = File(directory, ".$name.tmp")
        temporary.writeText(text, Charsets.UTF_8)
        protect(temporary)
        RandomAccessFile(temporary, "rw").use { it.fd.sync() }
        Files.move(temporary.toPath(), File(directory, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun accounts(root: JSONObject): JSONObject = root.optJSONObject("accounts") ?: JSONObject().also { root.put("accounts", it) }
    private fun account(root: JSONObject, accountId: String): JSONObject? = accounts(root).optJSONObject(accountId)
    private fun task(root: JSONObject, accountId: String, taskType: String): JSONObject? = account(root, accountId)?.optJSONObject("tasks")?.optJSONObject(taskType)

    companion object {
        const val SCHEMA = 1
        const val STATE_DIRECTORY = "/data/adb/reamicro-automation"
        const val MODULE_DIRECTORY = "/data/adb/modules/reamicro_automation"
        const val SYNC_ACTION = "com.reamicro.fix.KSU_TASK_SYNC"
        private val LOCK = Any()
        private fun protect(file: File, directory: Boolean = false) {
            if ("posix" in file.toPath().fileSystem.supportedFileAttributeViews()) {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
            }
        }
    }
}
