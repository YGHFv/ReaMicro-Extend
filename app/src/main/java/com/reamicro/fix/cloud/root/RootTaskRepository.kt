package com.reamicro.fix.cloud.root

import com.reamicro.fix.cloud.local.LocalTask
import com.reamicro.fix.cloud.local.LocalTaskRecord
import com.reamicro.fix.cloud.local.LocalTaskRepository
import com.reamicro.fix.cloud.local.LocalTaskRuntimeMerge
import com.reamicro.fix.cloud.local.LocalTaskStore
import com.reamicro.fix.cloud.local.LocalTaskAccountBundle
import com.reamicro.fix.cloud.local.appendTaskNotification
import com.reamicro.fix.cloud.local.appendLocalTaskRecord
import com.reamicro.fix.cloud.local.applyLocalTaskExecution
import com.reamicro.fix.cloud.local.applyLocalTaskRecordDeletionCounts
import com.reamicro.fix.cloud.local.applyLocalTaskRecordsSnapshot
import com.reamicro.fix.cloud.local.clearLocalTaskRecordsBefore
import com.reamicro.fix.cloud.local.localTaskFromJson
import com.reamicro.fix.cloud.local.localTaskRuntimeState
import com.reamicro.fix.cloud.local.localTaskAccountStateCurrent
import com.reamicro.fix.cloud.local.mergeLocalTaskSnapshot
import com.reamicro.fix.cloud.local.mergeRestoredLocalTaskRecords
import com.reamicro.fix.cloud.local.nextLocalTaskAt
import com.reamicro.fix.cloud.local.normalizeModuleMerchantConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal class RootTaskRepository(
    private val directory: File,
    private val moduleEnabled: () -> Boolean = { true },
) : LocalTaskRepository {
    private val stateFile = File(directory, "state.json")
    private var cachedStamp: Triple<Any?, FileTime, Long>? = null
    private var cachedRoot: JSONObject? = null
    private var cachedText: String? = null

    override fun accountIds(): List<String> = access { accounts(it).keys().asSequence().toList() }
    override fun list(accountId: String): List<LocalTask> = access { root ->
        val tasks = account(root, accountId)?.optJSONObject("tasks") ?: return@access emptyList()
        tasks.keys().asSequence().mapNotNull { type -> tasks.optJSONObject(type)?.let { localTaskFromJson(type, it) } }.toList()
    }
    fun accountLabel(accountId: String): String = access { root ->
        account(root, accountId)?.let { com.reamicro.fix.cloud.local.localTaskAccount(accountId, it).label } ?: accountId
    }
    override fun token(accountId: String): String = access {
        val account = account(it, accountId)
        check(account?.optString("credentialError").isNullOrBlank()) { "登录凭据无法解密，请重新导入该账号凭证" }
        account?.optString("token").orEmpty()
    }
    override fun accountGeneration(accountId: String): String = access { account(it, accountId)?.optString("accountGeneration").orEmpty() }
    override fun runtimeState(accountId: String, taskType: String): JSONObject = access { root ->
        task(root, accountId, taskType)?.let { JSONObject(localTaskRuntimeState(it).toString()) } ?: JSONObject()
    }
    override fun executionEnabled(): Boolean = moduleEnabled() && access { it.optBoolean("enabled") }
    override fun recordState(accountId: String, taskType: String, state: JSONObject) {
        access(write = true) { root ->
            val account = account(root, accountId) ?: return@access
            if (!localTaskAccountStateCurrent(account, state)) return@access
            val current = task(root, accountId, taskType) ?: return@access
            LocalTaskRuntimeMerge.patch(current, JSONObject(state.toString()))
        }
    }
    override fun recordExecution(accountId: String, task: LocalTask, state: JSONObject, record: LocalTaskRecord, notify: Boolean) {
        access(write = true) { root ->
            val account = account(root, accountId) ?: return@access
            if (!localTaskAccountStateCurrent(account, state)) return@access
            val current = task(root, accountId, task.taskType) ?: return@access
            applyLocalTaskExecution(current, task, state)
            appendLocalTaskRecord(account, record)
            root.put("lastRunAt", record.at)
            if (notify) appendTaskNotification(root, accountId, record,
                com.reamicro.fix.cloud.local.localTaskAccount(accountId, account).label, account.optString("accountGeneration"))
        }
    }

    fun enable(payload: JSONObject) {
        val incoming = configAccounts(payload)
        check(moduleEnabled()) { "Root 模块未启用，拒绝写入凭据" }
        val path = directory.toPath()
        val created = try {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            true
        } catch (exists: FileAlreadyExistsException) {
            check(directory.isDirectory && !Files.isSymbolicLink(path)) { "Root 私有目录无效" }
            false
        }
        try {
            check(moduleEnabled()) { "Root 模块已停用，取消启用" }
            protect(directory, directory = true)
            check(lockExecution(wait = false) {
                updateConfiguration(incoming, enable = true)
                true
            } == true) { "任务正在执行" }
        } catch (failure: Exception) {
            if (created) directory.delete()
            throw failure
        }
    }

    fun configure(payload: JSONObject) = updateConfiguration(configAccounts(payload), enable = false)

    private fun configAccounts(payload: JSONObject): JSONObject {
        val incoming = payload.optJSONObject("accounts") ?: error("缺少任务配置")
        require(incoming.length() <= 50) { "账号数量超限" }
        val validated = JSONObject(incoming.toString())
        validated.keys().forEach { id ->
            val source = validated.optJSONObject(id)
            require(id.isNotBlank() && source?.optJSONObject("tasks") != null) { "账号任务配置无效，未同步 Root 配置" }
            normalizeModuleMerchantConfig(requireNotNull(source))
        }
        return validated
    }

    private fun updateConfiguration(incoming: JSONObject, enable: Boolean) {
        access(write = true, initialize = enable) { root ->
            check(moduleEnabled()) { "Root 模块未启用，拒绝写入凭据" }
            val removed = accounts(root).keys().asSequence().filter { id ->
                !incoming.has(id) || incoming.optJSONObject(id)?.optString("accountGeneration") !=
                    account(root, id)?.optString("accountGeneration")
            }.toList()
            removed.forEach { accounts(root).remove(it) }
            if (enable && !root.optBoolean("enabled")) {
                val resumed = JSONObject(incoming.toString())
                resumed.keys().forEach { accountId ->
                    val source = resumed.optJSONObject(accountId) ?: return@forEach
                    val saved = account(root, accountId) ?: return@forEach
                    val sourceTasks = source.optJSONObject("tasks") ?: JSONObject()
                    val savedTasks = saved.optJSONObject("tasks") ?: JSONObject()
                    sourceTasks.keys().forEach { taskType ->
                        val task = sourceTasks.optJSONObject(taskType) ?: return@forEach
                        sourceTasks.put(taskType, mergeLocalTaskSnapshot(savedTasks.optJSONObject(taskType), task))
                    }
                    applyLocalTaskRecordDeletionCounts(saved, source)
                    clearLocalTaskRecordsBefore(saved, source.optLong("recordsClearedAt"))
                    applyLocalTaskRecordsSnapshot(source, JSONObject(saved.toString()).apply { remove("records") })
                    source.put("records", mergeRestoredLocalTaskRecords(
                        source.optJSONArray("records") ?: JSONArray(), saved.optJSONArray("records") ?: JSONArray(),
                        source.optLong(LocalTaskAccountBundle.RECORDS_IMPORTED_AT),
                    ))
                }
                root.put("accounts", resumed)
            } else {
                check(root.optBoolean("enabled")) { "KSU 执行已停用，请重新选择模式" }
                incoming.keys().forEach { accountId ->
                    val source = incoming.optJSONObject(accountId) ?: return@forEach
                    val target = account(root, accountId) ?: JSONObject().also { accounts(root).put(accountId, it) }
                    target.put("accountGeneration", source.optString("accountGeneration"))
                    target.put("nickname", source.optString("nickname")).put("remark", source.optString("remark"))
                    applyLocalTaskRecordDeletionCounts(target, source)
                    clearLocalTaskRecordsBefore(target, source.optLong("recordsClearedAt"))
                    val sourceTasks = source.optJSONObject("tasks") ?: JSONObject()
                    val targetTasks = target.optJSONObject("tasks") ?: JSONObject().also { target.put("tasks", it) }
                    sourceTasks.keys().forEach { taskType ->
                        val config = sourceTasks.optJSONObject(taskType) ?: return@forEach
                        targetTasks.put(taskType, LocalTaskStore.mergeMirroredTask(taskType, targetTasks.optJSONObject(taskType), config, System.currentTimeMillis()))
                    }
                    // Empty credentials revoke a previously synchronized token for this account.
                    if (source.has("token")) target.put("token", source.optString("token"))
                    target.put("credentialError", source.optString("credentialError"))
                }
            }
            for (queue in listOf("requests", "notifications")) {
                val pending = root.optJSONArray(queue) ?: continue
                root.put(queue, JSONArray().apply {
                    for (index in 0 until pending.length()) {
                        val item = pending.optJSONObject(index) ?: continue
                        if (item.optString("accountId") !in removed) put(item)
                    }
                })
            }
            root.optJSONObject("kick")?.takeIf { it.optString("accountId") in removed }?.let { root.remove("kick") }
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
            check(root.optBoolean("enabled") && moduleEnabled()) { "Root 执行已停止，拒绝排入旧请求" }
            val accountId = request.optString("accountId")
            val taskType = request.optString("taskType")
            if (accountId.isNotBlank() || taskType.isNotBlank()) {
                check(task(root, accountId, taskType) != null &&
                    request.optString("accountGeneration") == account(root, accountId)?.optString("accountGeneration")
                ) { "账号或任务已删除，拒绝排入旧请求" }
            }
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

    // Older states omit notifications until the first delivery. An unreadable queue must keep the short retry.
    fun pendingNotifications(): JSONArray? = access { root ->
        if (root.has("notifications")) root.optJSONArray("notifications")?.let { JSONArray(it.toString()) } else JSONArray()
    }

    fun snapshot(): JSONObject = access { root ->
        val result = JSONObject(root.toString())
        accounts(result).keys().forEach { accountId ->
            account(result, accountId)?.apply {
                remove("token")
                put("recordsMerge", true)
            }
        }
        result.remove("kick")
        result.remove("requests")
        result.put("schema", SCHEMA).put("enabled", root.optBoolean("enabled"))
            .put("nextTaskAt", nextTaskAt(root)).put("moduleEnabled", moduleEnabled())
        val heartbeat = File(directory, "heartbeat").takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()?.toLongOrNull() ?: 0L
        result.put("heartbeatAt", heartbeat * 1000L)
    }

    fun <Result> withExecutionLock(wait: Boolean = false, block: () -> Result): Result? =
        if (hasState()) lockExecution(wait, block) else null

    private fun <Result> lockExecution(wait: Boolean, block: () -> Result): Result? {
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

    private fun hasState(): Boolean {
        check(!Files.isSymbolicLink(directory.toPath()) && !Files.isSymbolicLink(stateFile.toPath())) {
            "Root 私有目录和状态文件不能是符号链接"
        }
        if (Files.notExists(stateFile.toPath())) {
            check(!directory.exists() || directory.isDirectory) { "Root 私有目录无效" }
            return false
        }
        check(stateFile.isFile) { "无法读取 Root 任务状态" }
        return true
    }

    private fun <Result> access(
        write: Boolean = false, initialize: Boolean = false, block: (JSONObject) -> Result,
    ): Result = synchronized(LOCK) {
        if (!hasState() && !initialize) return@synchronized block(JSONObject())
        RandomAccessFile(File(directory, "state.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                val stamp = stateStamp()
                if (stamp != cachedStamp) {
                    cachedRoot = null
                    cachedText = null
                }
                val original = cachedText ?: if (stateFile.isFile) stateFile.readText(Charsets.UTF_8) else null
                if (original == null && !initialize) return@synchronized block(JSONObject())
                val root = cachedRoot ?: (if (original != null) JSONObject(original) else JSONObject().put("schema", SCHEMA)).also {
                    accounts(it).keys().forEach { id -> account(it, id)?.let(::normalizeModuleMerchantConfig) }
                }
                try {
                    val result = block(root)
                    var updated = original
                    if (write) {
                        Files.deleteIfExists(File(directory, ".state.json.tmp").toPath())
                        Files.deleteIfExists(File(directory, ".next-run-at.tmp").toPath())
                        updated = root.toString()
                        if (updated != original) atomicWrite("state.json", updated)
                        // The helper arms whole wall-clock seconds. Rounding down can launch
                        // the runner before LocalTaskEngine considers the task due.
                        val next = nextTaskAt(root).let { if (it <= 0L) 0L else 1L + (it - 1L) / 1000L }.toString()
                        val schedule = File(directory, "next-run-at")
                        if (!schedule.isFile || schedule.readText(Charsets.UTF_8) != next) atomicWrite("next-run-at", next)
                    }
                    cachedRoot = root
                    cachedText = updated
                    cachedStamp = stateStamp()
                    result
                } catch (error: Throwable) {
                    cachedRoot = null
                    cachedText = null
                    cachedStamp = null
                    throw error
                }
            }
        }
    }

    // Atomic replacement changes the inode, including same-size writes within one clock tick.
    private fun stateStamp(): Triple<Any?, FileTime, Long>? = if (!stateFile.isFile) null else
        Files.readAttributes(stateFile.toPath(), BasicFileAttributes::class.java).let {
            Triple(it.fileKey(), it.lastModifiedTime(), it.size())
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
        const val SYNC_COMPONENT = "com.reamicro.fix/com.reamicro.fix.cloud.root.RootTaskSyncReceiver"
        const val SYNC_ACTION = "com.reamicro.fix.ROOT_TASK_SYNC"
        private val LOCK = Any()
        private fun protect(file: File, directory: Boolean = false) {
            if ("posix" in file.toPath().fileSystem.supportedFileAttributeViews()) {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
            }
        }
    }
}
