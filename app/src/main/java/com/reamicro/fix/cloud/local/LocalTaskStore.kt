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

    override fun list(accountId: String): List<LocalTask> {
        if (accountId.isBlank()) return emptyList()
        val root = readAccount(accountId) ?: return emptyList()
        val tasks = root.optJSONObject(KEY_TASKS) ?: return emptyList()
        return tasks.keys().asSequence().mapNotNull { type ->
            tasks.optJSONObject(type)?.let { localTaskFromJson(type, it) }
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
        editAccount(accountId) { root, tasks ->
            val existing = tasks.optJSONObject(task.taskType)
            if (checkVersion) {
                check(if (expected == null) existing == null else existing != null &&
                    existing.optLong(KEY_CONFIG_UPDATED_AT) == expected.configUpdatedAt &&
                    existing.optBoolean(KEY_ENABLED) == expected.enabled) {
                    "任务配置已更新，请重新打开配置后保存"
                }
            }
            val merged = writeTaskConfig(task)
            merged.put(KEY_CONFIG_UPDATED_AT, maxOf(System.currentTimeMillis(), (existing?.optLong(KEY_CONFIG_UPDATED_AT) ?: 0L) + 1L))

            if (existing != null) {
                for (stateKey in RUNTIME_STATE_KEYS) {
                    if (existing.has(stateKey)) merged.put(stateKey, existing.get(stateKey))
                }
            }
            if (task.enabled) {

                val now = System.currentTimeMillis()
                val next = if (task.taskType == AutoReadTimeLock.TASK_TYPE) {
                    AutoReadTimeLock.nextDailyAt(task.timeOfDay, task.durationMinutes, now)
                } else now
                merged.put(KEY_NEXT_RUN_AT, next)
            } else {
                merged.put(KEY_NEXT_RUN_AT, 0L)
            }
            tasks.put(task.taskType, merged)
            if (token.isNotBlank()) root.put(KEY_TOKEN, encrypt(token))
        }
        syncRootConfiguration()
    }

    fun setEnabled(accountId: String, taskType: String, enabled: Boolean, token: String = "") {
        if (accountId.isBlank()) return
        editAccount(accountId) { root, tasks ->
            val obj = tasks.optJSONObject(taskType) ?: JSONObject().put(KEY_TASK_TYPE, taskType)
            obj.put(KEY_CONFIG_UPDATED_AT, maxOf(System.currentTimeMillis(), obj.optLong(KEY_CONFIG_UPDATED_AT) + 1L))
            obj.put(KEY_ENABLED, enabled)
            val now = System.currentTimeMillis()
            val next = when {
                !enabled -> 0L
                taskType == AutoReadTimeLock.TASK_TYPE -> AutoReadTimeLock.nextDailyAt(
                    obj.optString(KEY_TIME_OF_DAY, "00:05"), obj.optInt(KEY_DURATION_MINUTES, 30), now,
                )
                else -> now
            }
            obj.put(KEY_NEXT_RUN_AT, next)
            tasks.put(taskType, obj)
            if (enabled && token.isNotBlank()) root.put(KEY_TOKEN, encrypt(token))
        }
        syncRootConfiguration()
    }

    override fun recordState(accountId: String, taskType: String, state: JSONObject) {
        if (accountId.isBlank()) return
        editAccount(accountId) { _, tasks ->
            val obj = tasks.optJSONObject(taskType) ?: return@editAccount
            state.keys().forEach { key -> obj.put(key, state.get(key)) }
            tasks.put(taskType, obj)
        }
    }

    override fun token(accountId: String): String {
        val root = readAccount(accountId) ?: return ""
        return decrypt(root.optString(KEY_TOKEN))
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
            for (task in list(accountId)) {
                if (!task.enabled) continue
                val state = recoveredAutomationState(task.taskType, runtimeState(accountId, task.taskType), now) ?: continue
                recordState(accountId, task.taskType, state)
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
            LocalTaskRecord(
                at = item.optLong("at", 0L),
                taskType = item.optString("taskType"),
                result = item.optString("result"),
                message = item.optString("message"),
                detail = item.optString("detail"),
            )
        }.sortedByDescending { it.at }
    }

    fun clearRecords(accountId: String) {
        if (accountId.isBlank()) return
        editAccount(accountId) { root, _ ->
            clearLocalTaskRecordsBefore(root, maxOf(System.currentTimeMillis(), root.optLong("recordsClearedAt") + 1L))
        }
        val context = contextProvider() ?: return
        if (context.packageName == LocalTaskMirror.MODULE_PACKAGE) syncRootConfiguration()
        else LocalTaskMirror.push(context)
    }

    fun mirrorPayload(): JSONObject {
        val prefs = prefs() ?: return JSONObject()
        val accounts = JSONObject()
        for (storageKey in prefs.all.keys) {
            val accountId = accountIdFromStorageKey(storageKey) ?: continue
            val root = runCatching { JSONObject(prefs.getString(storageKey, null) ?: "") }.getOrNull() ?: continue
            accounts.put(
                accountId,
                JSONObject()
                    .put(KEY_TOKEN, decrypt(root.optString(KEY_TOKEN)))
                    .put("recordsClearedAt", root.optLong("recordsClearedAt"))
                    .put(KEY_TASKS, root.optJSONObject(KEY_TASKS) ?: JSONObject()),
            )
        }
        return JSONObject().put(KEY_ACCOUNTS, accounts)
    }

    fun applyMirror(accountId: String, tasks: JSONObject, token: String, recordsClearedAt: Long = 0L) {
        if (accountId.isBlank()) return
        editAccount(accountId) { root, existing ->
            tasks.keys().forEach { taskType ->
                val incoming = tasks.optJSONObject(taskType) ?: return@forEach
                val current = existing.optJSONObject(taskType)
                existing.put(taskType, mergeMirroredTask(taskType, current, incoming, System.currentTimeMillis()).put(KEY_TASK_TYPE, taskType))
            }
            if (token.isNotBlank()) root.put(KEY_TOKEN, encrypt(token))
            clearLocalTaskRecordsBefore(root, recordsClearedAt)
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
                .put("recordsClearedAt", root.optLong("recordsClearedAt"))
                .apply { if (limit > 0) put(KEY_RECORDS, recent) })
        }
        return JSONObject().put(KEY_ACCOUNTS, accounts)
    }

    fun applySnapshot(payload: JSONObject) {
        val accounts = payload.optJSONObject(KEY_ACCOUNTS) ?: return
        accounts.keys().forEach { accountId ->
            val account = accounts.optJSONObject(accountId) ?: return@forEach
            val incomingTasks = account.optJSONObject(KEY_TASKS) ?: return@forEach
            editAccount(accountId) { root, existing ->
                incomingTasks.keys().forEach taskLoop@ { taskType ->
                    val incoming = incomingTasks.optJSONObject(taskType) ?: return@taskLoop
                    val current = existing.optJSONObject(taskType)
                    existing.put(taskType, mergeLocalTaskSnapshot(current, incoming))
                }
                applyLocalTaskRecordsSnapshot(root, account)
            }
        }
    }

    override fun recordExecution(accountId: String, task: LocalTask, state: JSONObject, record: LocalTaskRecord) {
        editAccount(accountId) { root, tasks ->
            val current = tasks.optJSONObject(task.taskType) ?: return@editAccount
            applyLocalTaskExecution(current, task, state)
            appendLocalTaskRecord(root, record)
        }
    }

    internal fun executionPayload(): JSONObject {
        val accounts = JSONObject()
        for (accountId in accountIds()) {
            val root = readAccount(accountId) ?: continue
            accounts.put(accountId, root.put(KEY_TOKEN, token(accountId)))
        }
        return JSONObject().put(KEY_ACCOUNTS, accounts)
    }

    private fun syncRootConfiguration() {
        contextProvider()?.let { context ->
            com.reamicro.fix.cloud.root.RootTaskBridge.requestSync(context)
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
        .put(KEY_BOOKS, JSONArray(task.books.map { JSONObject().put("bookId", it.bookId).put("name", it.name) }))
        .put(KEY_MERCHANT_AUTO_COMPLETE, task.merchantAutoComplete)
        .put(KEY_MERCHANT_CITY_CODE, task.merchantCityCode)
        .put(KEY_MERCHANT_PRINCIPAL, task.merchantPrincipal)
        .put(KEY_MERCHANT_TRANSPORT_ID, task.merchantTransportId)
        .put(KEY_FORBIDDEN_PAWN_PROP_IDS, JSONArray(task.forbiddenPawnPropIds.sorted()))
        .put(KEY_BLESSING_TYPE, task.blessingType.trim().uppercase())

    private fun readAccount(accountId: String): JSONObject? {
        val raw = prefs()?.getString(accountKey(accountId), null) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private inline fun editAccount(accountId: String, block: (root: JSONObject, tasks: JSONObject) -> Unit) {
        synchronized(WRITE_LOCK) {
            val prefs = prefs() ?: return
            val root = readAccount(accountId) ?: JSONObject()
            val tasks = root.optJSONObject(KEY_TASKS) ?: JSONObject().also { root.put(KEY_TASKS, it) }
            block(root, tasks)
            root.put(KEY_TASKS, tasks)
            check(prefs.edit().putString(accountKey(accountId), root.toString()).commit()) { "本地任务状态保存失败" }
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
            "dailyCounterDate", "dailyCounter", "lastDrawItems", "lastDrawResult", "lastDrawAt",
            "drawPending", "drawRewardLoreId",
            "dailyReadDate", "dailyReadMinutes", "bookRotation",
            "lastPawnDate", "pawnUsedToday", "pawnLastCoin",

            CloudTaskLocalRunner.KEY_PAWN_PROP_CATALOG,
            "lastCheckinDate", "lastCheckinAt", "claimDueAt", "claimCompletedDate", "claimLoreId", "automationStateVersion",
            CloudTaskLocalRunner.KEY_MERCHANT_NOTIFIED_TRIP, "merchantPausedUntil", "merchantStartAfterSettle",

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

        private val CONFIG_KEYS = setOf(
            KEY_ENABLED, KEY_TIME_OF_DAY, KEY_DURATION_MINUTES, KEY_DAILY_DRAW_LIMIT, KEY_BOOKS,
            KEY_MERCHANT_AUTO_COMPLETE, KEY_MERCHANT_CITY_CODE, KEY_MERCHANT_PRINCIPAL, KEY_MERCHANT_TRANSPORT_ID,
            KEY_FORBIDDEN_PAWN_PROP_IDS,
            KEY_BLESSING_TYPE,
        )

        internal fun mergeMirroredTask(taskType: String, current: JSONObject?, incoming: JSONObject, now: Long): JSONObject {
            if (current != null && incoming.optLong(KEY_CONFIG_UPDATED_AT) <= current.optLong(KEY_CONFIG_UPDATED_AT)) {
                return JSONObject(current.toString())
            }
            val merged = if (current == null) JSONObject() else JSONObject(current.toString())
            val changed = current == null || CONFIG_KEYS.any { current.opt(it)?.toString() != incoming.opt(it)?.toString() }
            for (key in CONFIG_KEYS) {
                if (incoming.has(key)) merged.put(key, incoming.get(key)) else merged.remove(key)
            }
            merged.put(KEY_CONFIG_UPDATED_AT, incoming.optLong(KEY_CONFIG_UPDATED_AT))
            if (changed || !merged.has(KEY_NEXT_RUN_AT)) {

                val next = when {
                    !merged.optBoolean(KEY_ENABLED) -> 0L
                    taskType == AutoReadTimeLock.TASK_TYPE -> AutoReadTimeLock.nextDailyAt(
                        merged.optString(KEY_TIME_OF_DAY, "00:05"), merged.optInt(KEY_DURATION_MINUTES, 30), now,
                    )
                    else -> now
                }
                merged.put(KEY_NEXT_RUN_AT, next)
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
