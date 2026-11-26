package com.reamicro.fix.cloud.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LocalTask(
    val taskType: String,
    val enabled: Boolean = false,
    val timeOfDay: String = "00:05",
    val durationMinutes: Int = 30,
    val dailyDrawLimit: Int = 3,
    val books: List<LocalTaskBook> = emptyList(),
    val merchantAutoComplete: Boolean = false,
    val merchantCityCode: String = "",
    val merchantPrincipal: Long = 0L,
    val merchantTransportId: Long = 0L,

    val forbiddenPawnPropIds: Set<String> = emptySet(),

    val blessingType: String = "",

    val nextRunAt: Long = 0L,
    val lastMessage: String = "",
    val lastRunAt: Long = 0L,
    val configUpdatedAt: Long = 0L,
    val accountGeneration: String = "",
    val drawAtCustomTime: Boolean = false,
    val hostReadOncePerDay: Boolean = true,
)

data class LocalTaskBook(
    val bookId: Long,
    val name: String,
)

data class LocalTaskRecord(
    val at: Long,
    val taskType: String,
    val result: String,
    val message: String,

    val detail: String = "",
)

class LocalTaskStore(private val contextProvider: () -> Context?) : LocalTaskRepository {

    internal fun accountLabel(accountId: String): String =
        readAccount(accountId)?.let { localTaskAccount(accountId, it).label } ?: accountId

    internal fun importCredentials(credentials: List<LocalTaskAccountCredential>) {
        require(credentials.isNotEmpty()) { "凭证中没有账号" }
        // Prepare every ciphertext before publishing any account from the import.
        val encrypted = credentials.associate { it.account.id to encrypt(it.token) }
        synchronized(WRITE_LOCK) {
            val preferences = prefs() ?: error("本地任务状态不可用")
            require((storedAccountIds() + encrypted.keys).toSet().size <= LocalTaskCredentials.MAX_ACCOUNTS) {
                "最多支持 50 个账号"
            }
            val previous = encrypted.keys.associateWith { preferences.getString(accountKey(it), null) }
            val editor = preferences.edit()
            for (credential in credentials) {
                val id = credential.account.id
                val account = prepareCredentialImport(previous[id]?.let(::JSONObject), credential, encrypted.getValue(id))
                editor.putString(accountKey(id), account.toString())
            }
            if (!editor.commit()) {
                val rollback = preferences.edit()
                previous.forEach { (id, value) ->
                    if (value == null) rollback.remove(accountKey(id)) else rollback.putString(accountKey(id), value)
                }
                check(rollback.commit()) { "凭证保存失败，回滚失败，请重新导入" }
                error("凭证保存失败，请重试")
            }
        }
        syncRootConfiguration()
    }

    internal fun prepareCredentialImport(previous: JSONObject?, credential: LocalTaskAccountCredential, ciphertext: String): JSONObject {
        val account = previous?.let { JSONObject(it.toString()) } ?: JSONObject()
        if (previous == null) account.put("accountGeneration", java.util.UUID.randomUUID().toString())
        account.put(KEY_TOKEN, ciphertext)
        if (credential.account.nickname.isNotBlank()) account.put("nickname", credential.account.nickname)
        val tasks = account.optJSONObject(KEY_TASKS) ?: JSONObject()
        for (type in LocalTaskCredentials.taskTypes) {
            if (!tasks.has(type)) tasks.put(type, writeTaskConfig(LocalTask(type,
                forbiddenPawnPropIds = if (type == "pawn") CloudTaskLocalRunner.PROHIBITED_PAWN_PROP_HINTS.keys else emptySet()))
                .put(KEY_CONFIG_UPDATED_AT, System.currentTimeMillis()))
        }
        return normalizeModuleMerchantConfig(account.put(KEY_TASKS, tasks))
    }

    internal fun setAccountRemark(accountId: String, remark: String) {
        val normalized = remark.trim()
        require(normalized.length <= 80 && normalized.none(Char::isISOControl)) { "备注最多 80 个字符，不能包含换行" }
        check(readAccount(accountId) != null) { "账号不存在，请刷新后重试" }
        editAccount(accountId, requireExisting = true) { root, _ -> root.put("remark", normalized) }
        syncRootConfiguration()
    }

    internal fun refreshAccountNickname(accountId: String, generation: String): Boolean {
        val account = readAccount(accountId) ?: return false
        if (account.optString("accountGeneration") != generation || localTaskNickname(account).isNotBlank()) return false
        val ciphertext = account.optString(KEY_TOKEN)
        val token = decrypt(ciphertext).takeIf { it.isNotBlank() } ?: return false
        val nickname = CloudTaskLocalRunner.fetchAccountNickname(accountId, token)
        return applyAccountNickname(accountId, generation, ciphertext, nickname)
    }

    internal fun applyAccountNickname(accountId: String, generation: String, ciphertext: String, nickname: String): Boolean {
        val normalized = normalizeLocalTaskNickname(nickname)
        if (normalized.isBlank()) return false
        var updated = false
        editAccount(accountId) { root, _ ->
            if (root.optString("accountGeneration") != generation || root.optString(KEY_TOKEN) != ciphertext ||
                localTaskNickname(root).isNotBlank()) return@editAccount
            root.put("nickname", normalized)
            updated = true
        }
        if (updated) syncRootConfiguration()
        return updated
    }

    internal fun deleteAccount(accountId: String) {
        require(accountId.isNotBlank()) { "账号不可用" }
        synchronized(WRITE_LOCK) {
            val preferences = prefs() ?: error("本地任务状态不可用")
            val key = accountKey(accountId)
            val previous = preferences.getString(key, null) ?: return
            commitLocalTaskAccountRestore(previous, null, { preferences.getString(key, null) }) { value ->
                val editor = preferences.edit()
                if (value == null) editor.remove(key) else editor.putString(key, value)
                editor.commit()
            }
        }
        syncRootConfiguration()
    }

    override fun list(accountId: String): List<LocalTask> {
        if (accountId.isBlank()) return emptyList()
        val root = readAccount(accountId) ?: return emptyList()
        val tasks = root.optJSONObject(KEY_TASKS) ?: return emptyList()
        return tasks.keys().asSequence().mapNotNull { type ->
            tasks.optJSONObject(type)?.let { localTaskFromJson(type, it).copy(accountGeneration = root.optString("accountGeneration")) }
        }.toList()
    }

    override fun get(accountId: String, taskType: String): LocalTask? =
        list(accountId).firstOrNull { it.taskType == taskType }

    fun saveTask(accountId: String, task: LocalTask, token: String) {
        if (accountId.isBlank()) return
        saveTaskInternal(accountId, task, token, null, false)
    }

    fun saveEditedTask(accountId: String, task: LocalTask, token: String, expected: LocalTask?) {
        require(expected == null || expected.taskType == task.taskType) { "任务类型已变更，请重新打开配置" }
        saveTaskInternal(accountId, task, token, expected, true)
    }

    private fun saveTaskInternal(accountId: String, task: LocalTask, token: String, expected: LocalTask?, checkVersion: Boolean) {
        require(accountId.isNotBlank()) { "账号不可用，请重新登录" }
        editAccount(accountId, create = com.reamicro.fix.core.HostPackages.contains(contextProvider()?.packageName.orEmpty()),
            requireExisting = true) { root, tasks ->
            val existing = tasks.optJSONObject(task.taskType)
            if (checkVersion) {
                check(expected == null || expected.accountGeneration == root.optString("accountGeneration")) {
                    "账号已变更，请重新打开配置"
                }
                check(if (expected == null) existing == null else existing != null &&
                    existing.optLong(KEY_CONFIG_UPDATED_AT) == expected.configUpdatedAt &&
                    existing.optBoolean(KEY_ENABLED) == expected.enabled) {
                    "任务配置已更新，请重新打开配置后保存"
                }
            }
            val now = System.currentTimeMillis()
            val merged = writeTaskConfig(task)
            merged.put(KEY_CONFIG_UPDATED_AT, maxOf(now, (existing?.optLong(KEY_CONFIG_UPDATED_AT) ?: 0L) + 1L))

            if (existing != null) {
                for (stateKey in RUNTIME_STATE_KEYS) {
                    if (existing.has(stateKey)) merged.put(stateKey, existing.get(stateKey))
                }
            }
            LocalTaskRuntimeMerge.schedule(merged, task.taskType, now, existing)
            tasks.put(task.taskType, merged)
            if (token.isNotBlank()) root.put(KEY_TOKEN, encrypt(token))
        }
        syncRootConfiguration()
    }

    fun setEnabled(accountId: String, taskType: String, enabled: Boolean, token: String = "", expected: LocalTask? = null) {
        if (accountId.isBlank()) return
        editAccount(accountId, create = com.reamicro.fix.core.HostPackages.contains(contextProvider()?.packageName.orEmpty()),
            requireExisting = true) { root, tasks ->
            val obj = tasks.optJSONObject(taskType) ?: JSONObject().put(KEY_TASK_TYPE, taskType)
            if (expected != null) {
                check(expected.accountGeneration == root.optString("accountGeneration") &&
                    expected.configUpdatedAt == obj.optLong(KEY_CONFIG_UPDATED_AT) &&
                    expected.enabled == obj.optBoolean(KEY_ENABLED)) { "账号或任务配置已更新，请刷新后重试" }
            }
            if (enabled && token.isBlank()) requireStoredCredential(root)
            val now = System.currentTimeMillis()
            obj.put(KEY_CONFIG_UPDATED_AT, maxOf(now, obj.optLong(KEY_CONFIG_UPDATED_AT) + 1L))
            obj.put(KEY_ENABLED, enabled)
            LocalTaskRuntimeMerge.schedule(obj, taskType, now)
            tasks.put(taskType, obj)
            if (enabled && token.isNotBlank()) root.put(KEY_TOKEN, encrypt(token))
        }
        syncRootConfiguration()
    }

    override fun recordState(accountId: String, taskType: String, state: JSONObject) {
        if (accountId.isBlank()) return
        editAccount(accountId) { root, tasks ->
            if (!localTaskAccountStateCurrent(root, state)) return@editAccount
            val obj = tasks.optJSONObject(taskType) ?: return@editAccount
            LocalTaskRuntimeMerge.patch(obj, state)
            tasks.put(taskType, obj)
        }
    }

    override fun token(accountId: String): String {
        val root = readAccount(accountId) ?: return ""
        return decrypt(root.optString(KEY_TOKEN))
    }

    override fun accountGeneration(accountId: String): String =
        readAccount(accountId)?.optString("accountGeneration").orEmpty()

    fun refreshToken(accountId: String, token: String) {
        require(accountId.isNotBlank()) { "账号不可用，请重新登录" }
        require(token.isNotBlank()) { "当前阅微登录密钥为空，请重新登录阅微" }
        editAccount(accountId, create = com.reamicro.fix.core.HostPackages.contains(contextProvider()?.packageName.orEmpty()),
            requireExisting = true) { root, _ -> root.put(KEY_TOKEN, encrypt(token)) }
    }

    override fun runtimeState(accountId: String, taskType: String): JSONObject {
        val obj = readAccount(accountId)?.optJSONObject(KEY_TASKS)?.optJSONObject(taskType) ?: return JSONObject()
        val state = JSONObject()
        for (stateKey in RUNTIME_STATE_KEYS) {
            if (obj.has(stateKey)) state.put(stateKey, obj.get(stateKey))
        }
        return state
    }

    fun recoverPendingAutomationTasks(now: Long = System.currentTimeMillis()) {
        for (accountId in storedAccountIds()) {
            val generation = accountGeneration(accountId)
            for (task in list(accountId)) {
                if (!task.enabled) continue
                val state = recoveredAutomationState(task.taskType, runtimeState(accountId, task.taskType), now) ?: continue
                recordState(accountId, task.taskType, state
                    .put(LocalTaskRuntimeMerge.CONFIG_CONTEXT, task.configUpdatedAt)
                    .put(LocalTaskRuntimeMerge.ACCOUNT_CONTEXT, generation))
            }
        }
    }

    override fun accountIds(): List<String> = storedAccountIds()

    fun rescheduleEnabledTasks(now: Long = System.currentTimeMillis()): Int {
        val context = contextProvider()
        if (context != null && com.reamicro.fix.cloud.root.RootTaskBridge.isEnabled(context)) {
            return com.reamicro.fix.cloud.root.RootTaskBridge.reschedule(context)
        }
        return rescheduleLocalTasks(this, now)
    }

    fun accountsWithEnabledTasks(): Set<String> =
        storedAccountIds().filterTo(linkedSetOf()) { accountId -> list(accountId).any { it.enabled } }

    private fun storedAccountIds(): List<String> =
        accountIdsFromStorageKeys(prefs()?.all?.keys.orEmpty())

    fun appendRecord(
        accountId: String,
        taskType: String,
        result: String,
        message: String,
        at: Long = System.currentTimeMillis(),
        detail: String = "",
    ) {
        if (accountId.isBlank()) return
        editAccount(accountId) { root, _ ->
            val records = root.optJSONArray(KEY_RECORDS) ?: JSONArray()
            records.put(
                JSONObject()
                    .put("at", at)
                    .put("taskType", taskType)
                    .put("result", result)
                    .put("message", message)
                    .put("detail", detail),
            )
            val trimmed = JSONArray()
            for (index in (records.length() - MAX_RECORDS).coerceAtLeast(0) until records.length()) {
                trimmed.put(records.opt(index))
            }
            root.put(KEY_RECORDS, trimmed)
        }
    }

    fun records(accountId: String): List<LocalTaskRecord> {
        val array = readAccount(accountId)?.optJSONArray(KEY_RECORDS) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            localTaskRecordFromJson(item)
        }.sortedByDescending { it.at }
    }

    fun deleteRecords(accountId: String, selected: List<LocalTaskRecord>): Int {
        if (accountId.isBlank() || selected.isEmpty()) return 0
        var deleted = 0
        editAccount(accountId) { root, _ ->
            deleted = deleteSelectedLocalTaskRecords(root, selected)
        }
        if (deleted > 0) syncRootConfiguration()
        return deleted
    }

    fun clearRecords(accountId: String) {
        if (accountId.isBlank()) return
        editAccount(accountId) { root, _ ->
            clearLocalTaskRecordsBefore(root, maxOf(System.currentTimeMillis(), root.optLong("recordsClearedAt") + 1L))
        }
        syncRootConfiguration()
    }

    @JvmOverloads
    fun snapshotPayload(accountId: String? = null, recordLimit: Int = 20): JSONObject {
        val accounts = JSONObject()
        val selectedAccounts = storedAccountIds().filter { accountId == null || it == accountId }
        val limit = recordLimit.coerceIn(0, 100)
        for (accountId in selectedAccounts) {
            val root = readAccount(accountId) ?: continue
            val records = root.optJSONArray(KEY_RECORDS) ?: JSONArray()
            val recent = JSONArray()
            for (index in (records.length() - limit).coerceAtLeast(0) until records.length()) recent.put(records.get(index))
            accounts.put(accountId, JSONObject()
                .put(KEY_TASKS, root.optJSONObject(KEY_TASKS) ?: JSONObject())
                .put("nickname", root.optString("nickname"))
                .put("remark", root.optString("remark"))
                .put("accountGeneration", root.optString("accountGeneration"))
                .put("recordsClearedAt", root.optLong("recordsClearedAt"))
                .apply { if (limit > 0) put(KEY_RECORDS, recent) })
        }
        return JSONObject().put(KEY_ACCOUNTS, accounts)
    }

    fun applySnapshot(payload: JSONObject) {
        val accounts = payload.optJSONObject(KEY_ACCOUNTS) ?: return
        accounts.keys().forEach { accountId ->
            val account = accounts.optJSONObject(accountId) ?: return@forEach
            editAccount(accountId) { root, _ ->
                applyExistingLocalTaskAccountSnapshot(root, account)
            }
        }
    }

    override fun recordExecution(accountId: String, task: LocalTask, state: JSONObject, record: LocalTaskRecord, notify: Boolean) {
        editAccount(accountId) { root, tasks ->
            if (!localTaskAccountStateCurrent(root, state)) return@editAccount
            val current = tasks.optJSONObject(task.taskType) ?: return@editAccount
            applyLocalTaskExecution(current, task, state)
            appendLocalTaskRecord(root, record)
            if (notify) appendTaskNotification(root, accountId, record)
        }
    }

    internal fun pendingNotifications(): List<JSONObject> = accountIds().flatMap { accountId ->
        val pending = readAccount(accountId)?.optJSONArray("notifications") ?: JSONArray()
        (0 until pending.length()).mapNotNull { pending.optJSONObject(it) }
    }
    internal fun acknowledgeNotifications(accountId: String, ids: Set<String>) {
        editAccount(accountId) { root, _ ->
            val pending = root.optJSONArray("notifications") ?: return@editAccount
            val remaining = JSONArray()
            for (index in 0 until pending.length()) {
                val item = pending.optJSONObject(index) ?: continue
                if (item.optString("id") !in ids) remaining.put(item)
            }
            root.put("notifications", remaining)
        }
    }
    internal fun executionPayload(): JSONObject = synchronized(WRITE_LOCK) {
        check(prefs() != null) { "本地任务状态不可用，未同步 Root 配置" }
        val accounts = JSONObject()
        for (accountId in accountIds()) {
            val root = readAccount(accountId) ?: error("账号 $accountId 配置无法读取，未同步 Root 配置")
            accounts.put(accountId, root.apply {
                remove("notifications")
                // History stays local; Root returns its newer records separately.
                remove(KEY_RECORDS)
                val credential = runCatching { decrypt(optString(KEY_TOKEN)) }
                put(KEY_TOKEN, credential.getOrDefault(""))
                put("credentialError", if (credential.isFailure) "登录凭据无法解密，请重新导入该账号凭证" else "")
            })
        }
        JSONObject().put(KEY_ACCOUNTS, accounts)
    }

    private fun requireStoredCredential(account: JSONObject) {
        check(decrypt(account.optString(KEY_TOKEN)).isNotBlank()) {
            "登录凭据不可用，请从账号管理重新导入该账号凭证"
        }
    }


    internal class PreparedAccountBundle(
        internal val accountId: String,
        internal val account: JSONObject,
        internal val encryptedToken: String,
    ) {
        override fun toString(): String = "LocalTaskAccountRestore(accountId=$accountId)"
    }

    internal fun exportAccountBundle(accountId: String): JSONObject = synchronized(WRITE_LOCK) {
        val preferences = prefs() ?: error("本地任务状态不可用")
        val raw = preferences.getString(accountKey(accountId), null)
        val account = raw?.let { JSONObject(it) }
        LocalTaskAccountBundle.export(accountId, account)
    }

    internal fun prepareAccountBundle(accountId: String, token: String, payload: JSONObject): PreparedAccountBundle {
        val account = LocalTaskAccountBundle.read(payload, accountId)
        require(token.isNotBlank()) { "本地任务账号凭证不完整" }
        // Encryption is completed before any imported database, files or preferences are written.
        return PreparedAccountBundle(accountId, account, encrypt(token))
    }

    internal fun restoreAccountBundle(prepared: PreparedAccountBundle, canRestore: () -> Boolean) {
        synchronized(WRITE_LOCK) {
            check(canRestore()) { "阅微账号已经变化，请重新导入" }
            val preferences = prefs() ?: error("本地任务状态不可用")
            val key = accountKey(prepared.accountId)
            val previous = preferences.getString(key, null)
            val account = previous?.let { JSONObject(it) }
            val replacement = LocalTaskAccountBundle.restore(account, prepared.account, System.currentTimeMillis())
                .put(KEY_TOKEN, prepared.encryptedToken).toString()
            check(canRestore()) { "阅微账号已经变化，请重新导入" }
            commitLocalTaskAccountRestore(previous, replacement, { preferences.getString(key, null) }) { value ->
                val editor = preferences.edit()
                if (value == null) editor.remove(key) else editor.putString(key, value)
                editor.commit()
            }
        }
    }

    private fun syncRootConfiguration() {
        contextProvider()?.let { context ->
            if (context.packageName == com.reamicro.fix.notification.CloudTaskNotifications.MODULE_PACKAGE_NAME) {
                com.reamicro.fix.cloud.root.RootTaskBridge.requestSync(context)
            }
        }
    }

    private fun writeTaskConfig(task: LocalTask): JSONObject = JSONObject()
        .put(KEY_TASK_TYPE, task.taskType)
        .put(KEY_ENABLED, task.enabled)
        .put(KEY_TIME_OF_DAY, if (task.taskType == AutoReadTimeLock.TASK_TYPE) {
            AutoReadTimeLock.safeTime(task.timeOfDay, task.durationMinutes)
        } else task.timeOfDay)
        .put(KEY_DURATION_MINUTES, task.durationMinutes)
        .put(KEY_DAILY_DRAW_LIMIT, task.dailyDrawLimit)
        .put(KEY_DRAW_AT_CUSTOM_TIME, task.drawAtCustomTime)
        .put(KEY_HOST_READ_ONCE_PER_DAY, task.hostReadOncePerDay)
        .put(KEY_BOOKS, JSONArray(task.books.map { JSONObject().put("bookId", it.bookId).put("name", it.name) }))
        .put(KEY_MERCHANT_AUTO_COMPLETE, task.merchantAutoComplete)
        .put(KEY_MERCHANT_CITY_CODE, task.merchantCityCode)
        .put(KEY_MERCHANT_PRINCIPAL, task.merchantPrincipal)
        .put(KEY_MERCHANT_TRANSPORT_ID, task.merchantTransportId)
        .put(KEY_FORBIDDEN_PAWN_PROP_IDS, JSONArray(task.forbiddenPawnPropIds.sorted()))
        .put(KEY_BLESSING_TYPE, task.blessingType.trim().uppercase())

    private fun readAccount(accountId: String): JSONObject? {
        val raw = prefs()?.getString(accountKey(accountId), null) ?: return null
        return runCatching { JSONObject(raw).also(::normalizeAccountConfig) }.getOrNull()
    }

    private fun normalizeAccountConfig(account: JSONObject) {
        if (contextProvider()?.packageName == com.reamicro.fix.notification.CloudTaskNotifications.MODULE_PACKAGE_NAME) {
            normalizeModuleMerchantConfig(account)
        }
    }

    private inline fun editAccount(
        accountId: String, create: Boolean = false, requireExisting: Boolean = false,
        block: (root: JSONObject, tasks: JSONObject) -> Unit,
    ) {
        synchronized(WRITE_LOCK) {
            val prefs = prefs() ?: error("本地任务状态不可用")
            val stored = prefs.getString(accountKey(accountId), null)
            if (stored == null && !create) {
                check(!requireExisting) { "账号不存在，请刷新后重试" }
                return
            }
            val root = stored?.let(::JSONObject) ?: JSONObject()
            val tasks = root.optJSONObject(KEY_TASKS) ?: JSONObject().also { root.put(KEY_TASKS, it) }
            val before = root.toString()
            block(root, tasks)
            root.put(KEY_TASKS, tasks)
            normalizeAccountConfig(root)
            val after = root.toString()
            if (after != before) {
                val key = accountKey(accountId)
                commitLocalTaskAccountRestore(stored, after, { prefs.getString(key, null) }) { value ->
                    val editor = prefs.edit()
                    if (value == null) editor.remove(key) else editor.putString(key, value)
                    editor.commit()
                }
            }
        }
    }

    private fun accountKey(accountId: String): String = "$KEY_ACCOUNT_PREFIX$accountId"

    private fun prefs(): SharedPreferences? =
        contextProvider()?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun encrypt(value: String): String = transformLocalTaskCredential(value) { plain ->
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(ByteBuffer.allocate(4 + iv.size + body.size).apply {
            putInt(iv.size)
            put(iv)
            put(body)
        }.array(), Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String = transformLocalTaskCredential(value) { encrypted ->
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(bytes)
        val ivSize = buffer.int
        require(ivSize in 12..32)
        val iv = ByteArray(ivSize).also(buffer::get)
        val body = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        const val PREFS_NAME = "reamicro_local_tasks"
        internal const val KEY_ACCOUNT_PREFIX = "account_"
        private const val KEY_ACCOUNTS = "accounts"
        internal const val KEY_TASKS = "tasks"
        internal const val KEY_TOKEN = "token"
        private const val KEY_TASK_TYPE = "taskType"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TIME_OF_DAY = "timeOfDay"
        private const val KEY_DURATION_MINUTES = "durationMinutes"
        private const val KEY_DAILY_DRAW_LIMIT = "dailyLimit"
        private const val KEY_DRAW_AT_CUSTOM_TIME = "drawAtCustomTime"
        private const val KEY_HOST_READ_ONCE_PER_DAY = "hostReadOncePerDay"
        private const val KEY_BOOKS = "books"
        private const val KEY_MERCHANT_AUTO_COMPLETE = "merchantAutoComplete"
        private const val KEY_MERCHANT_CITY_CODE = "merchantCityCode"
        private const val KEY_MERCHANT_PRINCIPAL = "merchantPrincipal"
        private const val KEY_MERCHANT_TRANSPORT_ID = "merchantTransportId"
        internal const val KEY_FORBIDDEN_PAWN_PROP_IDS = "forbiddenPawnPropIds"
        internal const val KEY_BLESSING_TYPE = "blessingType"
        internal const val KEY_CONFIG_UPDATED_AT = "configUpdatedAt"
        private val WRITE_LOCK = Any()
        const val KEY_NEXT_RUN_AT = "nextRunAt"
        const val KEY_LAST_MESSAGE = "lastMessage"
        const val KEY_LAST_RUN_AT = "lastRunAt"

        internal val RUNTIME_STATE_KEYS = setOf(
            KEY_NEXT_RUN_AT, KEY_LAST_MESSAGE, KEY_LAST_RUN_AT,
            LocalTaskRuntimeMerge.UPDATED_AT,
            "dailyCounterDate", "dailyCounter", "lastDrawItems", "lastDrawResult", "lastDrawAt",
            "drawPending", "drawRewardLoreId", "drawScheduleVersion",
            "dailyReadDate", "dailyReadMinutes", "bookRotation",
            HOST_READ_ATTEMPT_DATE,
            CloudTaskLocalRunner.KEY_LORE_PRAYER_ATTEMPT_DATE,
            CloudTaskLocalRunner.KEY_READ_PENDING_BATCH,
            "lastPawnDate", "pawnUsedToday", "pawnLastCoin",

            CloudTaskLocalRunner.KEY_PAWN_PROP_CATALOG,
            "lastCheckinDate", "lastCheckinAt", "claimDueAt", "claimCompletedDate", "claimLoreId", "automationStateVersion",
            CloudTaskLocalRunner.KEY_MERCHANT_NOTIFIED_TRIP, "merchantPausedUntil", "merchantStartAfterSettle",
            CloudTaskLocalRunner.KEY_MERCHANT_DEPARTURE,

            KEY_MERCHANT_SETTLED_TRIP, KEY_MERCHANT_RESTART_AFTER, KEY_MERCHANT_BLESSED_TRIP, KEY_MERCHANT_END_TIME,

            KEY_MERCHANT_LAST_CITY, KEY_MERCHANT_LAST_TRANSPORT, KEY_MERCHANT_LAST_PRINCIPAL,
        )

        internal const val KEY_MERCHANT_LAST_CITY = "merchantLastCityCode"
        internal const val KEY_MERCHANT_END_TIME = "merchantEndTime"
        internal const val KEY_MERCHANT_LAST_TRANSPORT = "merchantLastTransportId"
        internal const val KEY_MERCHANT_LAST_PRINCIPAL = "merchantLastPrincipal"

        internal const val KEY_MERCHANT_SETTLED_TRIP = "merchantSettledTripId"

        internal const val KEY_MERCHANT_RESTART_AFTER = "merchantRestartedAfterTripId"

        internal const val KEY_MERCHANT_BLESSED_TRIP = "merchantBlessedTripId"

        internal val CONFIG_KEYS = setOf(
            KEY_ENABLED, KEY_TIME_OF_DAY, KEY_DURATION_MINUTES, KEY_DAILY_DRAW_LIMIT, KEY_DRAW_AT_CUSTOM_TIME, KEY_BOOKS,
            KEY_HOST_READ_ONCE_PER_DAY,
            KEY_MERCHANT_AUTO_COMPLETE, KEY_MERCHANT_CITY_CODE, KEY_MERCHANT_PRINCIPAL, KEY_MERCHANT_TRANSPORT_ID,
            KEY_FORBIDDEN_PAWN_PROP_IDS,
            KEY_BLESSING_TYPE,
        )

        internal fun mergeMirroredTask(taskType: String, current: JSONObject?, incoming: JSONObject, now: Long): JSONObject {
            if (current == null) return mergeLocalTaskSnapshot(null, incoming).also {
                if (!it.has(KEY_NEXT_RUN_AT)) LocalTaskRuntimeMerge.schedule(it, taskType, now)
            }
            if (incoming.optLong(KEY_CONFIG_UPDATED_AT) <= current.optLong(KEY_CONFIG_UPDATED_AT)) {
                return JSONObject(current.toString())
            }
            val merged = JSONObject(current.toString())
            val changed = CONFIG_KEYS.any { current.opt(it)?.toString() != incoming.opt(it)?.toString() }
            for (key in CONFIG_KEYS) {
                if (incoming.has(key)) merged.put(key, incoming.get(key)) else merged.remove(key)
            }
            merged.put(KEY_CONFIG_UPDATED_AT, incoming.optLong(KEY_CONFIG_UPDATED_AT))
            if (changed || !merged.has(KEY_NEXT_RUN_AT)) {
                LocalTaskRuntimeMerge.schedule(merged, taskType, now, current)
            }
            return merged
        }

        private const val KEY_RECORDS = "records"
        internal const val MAX_RECORDS = 100

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "reamicro-local-task-credentials"
    }
}

internal fun accountIdFromStorageKey(storageKey: String): String? =
    storageKey.takeIf { it.startsWith(LocalTaskStore.KEY_ACCOUNT_PREFIX) }
        ?.removePrefix(LocalTaskStore.KEY_ACCOUNT_PREFIX)
        ?.takeIf { it.isNotBlank() }

internal fun accountIdsFromStorageKeys(keys: Collection<String>): List<String> =
    keys.mapNotNull(::accountIdFromStorageKey)

internal fun recoveredAutomationState(taskType: String, state: JSONObject, now: Long): JSONObject? {
    if (taskType !in setOf("yeshe_checkin", "traveling_merchant") || state.optInt("automationStateVersion") >= 1) return null
    return JSONObject(state.toString()).put("automationStateVersion", 1).put(LocalTaskStore.KEY_NEXT_RUN_AT, now).apply {
        if (taskType == "traveling_merchant") {
            put(LocalTaskStore.KEY_MERCHANT_SETTLED_TRIP, 0L)
            put(LocalTaskStore.KEY_MERCHANT_RESTART_AFTER, 0L)
        }
    }
}
