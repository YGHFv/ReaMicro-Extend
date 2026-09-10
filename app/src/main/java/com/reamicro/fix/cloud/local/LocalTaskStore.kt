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

/** 本地自动任务的一条配置 + 运行时状态。与云端 CloudTask 字段保持对应，便于两套 UI 共用。 */
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
    // 运行时状态
    val nextRunAt: Long = 0L,
    val lastMessage: String = "",
    val lastRunAt: Long = 0L,
)

data class LocalTaskBook(
    val bookId: Long,
    val name: String,
)

/**
 * 本地自动任务存储。与云端任务不同，本地任务的凭据（阅微 token）只保存在本机，
 * 用 Android Keystore AES/GCM 加密，按阅微账号（accountId）分组保存到 SharedPreferences。
 *
 * 运行时状态（下次执行时间、每日计数、行商已通知 tripId 等）也一并落盘，
 * 便于系统闹钟静默唤醒时无 UI 也能续跑。
 */
class LocalTaskStore(private val contextProvider: () -> Context?) {

    /** 读取某账号下的所有本地任务配置（含运行时状态）。 */
    fun list(accountId: String): List<LocalTask> {
        if (accountId.isBlank()) return emptyList()
        val root = readAccount(accountId) ?: return emptyList()
        val tasks = root.optJSONObject(KEY_TASKS) ?: return emptyList()
        return tasks.keys().asSequence().mapNotNull { type ->
            tasks.optJSONObject(type)?.let { parseTask(type, it) }
        }.toList()
    }

    fun get(accountId: String, taskType: String): LocalTask? =
        list(accountId).firstOrNull { it.taskType == taskType }

    /** 保存（新建或更新）一条任务的配置。保存时刷新加密 token，重置 nextRunAt 让其尽快执行。 */
    fun saveTask(accountId: String, task: LocalTask, token: String) {
        if (accountId.isBlank()) return
        editAccount(accountId) { root, tasks ->
            val existing = tasks.optJSONObject(task.taskType)
            val merged = writeTaskConfig(task)
            // 保留运行时状态：仅在启用切换或首次创建时重排 nextRunAt。
            if (existing != null) {
                for (stateKey in RUNTIME_STATE_KEYS) {
                    if (existing.has(stateKey)) merged.put(stateKey, existing.get(stateKey))
                }
            }
            if (task.enabled) {
                // 启用时立即安排一次执行（行商任务会在执行内部再自行排到 endTime）。
                merged.put(KEY_NEXT_RUN_AT, System.currentTimeMillis())
            } else {
                merged.put(KEY_NEXT_RUN_AT, 0L)
            }
            tasks.put(task.taskType, merged)
            if (token.isNotBlank()) root.put(KEY_TOKEN, encrypt(token))
        }
    }

    /** 切换启用状态，不改动其它配置。 */
    fun setEnabled(accountId: String, taskType: String, enabled: Boolean, token: String = "") {
        if (accountId.isBlank()) return
        editAccount(accountId) { root, tasks ->
            val obj = tasks.optJSONObject(taskType) ?: JSONObject().put(KEY_TASK_TYPE, taskType)
            obj.put(KEY_ENABLED, enabled)
            obj.put(KEY_NEXT_RUN_AT, if (enabled) System.currentTimeMillis() else 0L)
            tasks.put(taskType, obj)
            if (enabled && token.isNotBlank()) root.put(KEY_TOKEN, encrypt(token))
        }
    }

    /** 写回运行时状态（下次执行时间、最近消息、每日计数等）。state 为要合并的键值。 */
    fun recordState(accountId: String, taskType: String, state: JSONObject) {
        if (accountId.isBlank()) return
        editAccount(accountId) { _, tasks ->
            val obj = tasks.optJSONObject(taskType) ?: return@editAccount
            state.keys().forEach { key -> obj.put(key, state.get(key)) }
            tasks.put(taskType, obj)
        }
    }

    /** 取当前账号已加密保存的阅微 token（供无 UI 唤醒时使用）。 */
    fun token(accountId: String): String {
        val root = readAccount(accountId) ?: return ""
        return decrypt(root.optString(KEY_TOKEN, null))
    }

    /** 读取行商任务已通知过的 tripId，避免重复通知同一趟行商。 */
    fun merchantLastNotifiedTripId(accountId: String, taskType: String): Long {
        val tasks = readAccount(accountId)?.optJSONObject(KEY_TASKS) ?: return 0L
        return tasks.optJSONObject(taskType)?.optLong("merchantLastNotifiedTripId", 0L) ?: 0L
    }

    /**
     * 读取某任务已落盘的运行时状态（每日计数、轮转、签到时间、行商已通知 tripId 等），
     * 供执行器读取上次执行结果续跑。返回的 JSON 只含 RUNTIME_STATE_KEYS 里的键。
     */
    fun runtimeState(accountId: String, taskType: String): JSONObject {
        val obj = readAccount(accountId)?.optJSONObject(KEY_TASKS)?.optJSONObject(taskType) ?: return JSONObject()
        val state = JSONObject()
        for (stateKey in RUNTIME_STATE_KEYS) {
            if (obj.has(stateKey)) state.put(stateKey, obj.get(stateKey))
        }
        return state
    }

    /** 所有账号中「已启用任务」的最近一次待执行时间，供闹钟排程取 min。0 表示无。 */
    fun earliestNextRunAt(): Long {
        val prefs = prefs() ?: return 0L
        var earliest = Long.MAX_VALUE
        for (accountId in prefs.all.keys) {
            for (task in list(accountId)) {
                if (!task.enabled) continue
                val next = task.nextRunAt
                if (next in 1 until earliest) earliest = next
            }
        }
        return if (earliest == Long.MAX_VALUE) 0L else earliest
    }

    /** 是否存在任意账号的任意已启用本地任务。 */
    fun hasEnabledTasks(): Boolean {
        val prefs = prefs() ?: return false
        return prefs.all.keys.any { accountId -> list(accountId).any { it.enabled } }
    }

    /** 所有已启用任务的账号集合（去重）。 */
    fun accountsWithEnabledTasks(): Set<String> {
        val prefs = prefs() ?: return emptySet()
        return prefs.all.keys.filterTo(linkedSetOf()) { accountId -> list(accountId).any { it.enabled } }
    }

    private fun parseTask(taskType: String, obj: JSONObject): LocalTask {
        val booksJson = obj.optJSONArray(KEY_BOOKS) ?: JSONArray()
        val books = (0 until booksJson.length()).mapNotNull { index ->
            val item = booksJson.optJSONObject(index) ?: return@mapNotNull null
            val bookId = item.optLong("bookId", 0L)
            if (bookId <= 0L) return@mapNotNull null
            LocalTaskBook(bookId = bookId, name = item.optString("name"))
        }
        return LocalTask(
            taskType = taskType,
            enabled = obj.optBoolean(KEY_ENABLED, false),
            timeOfDay = obj.optString(KEY_TIME_OF_DAY, "00:05").ifBlank { "00:05" },
            durationMinutes = obj.optInt(KEY_DURATION_MINUTES, 30).coerceIn(1, 720),
            dailyDrawLimit = obj.optInt(KEY_DAILY_DRAW_LIMIT, 3).coerceIn(0, 20),
            books = books,
            merchantAutoComplete = obj.optBoolean(KEY_MERCHANT_AUTO_COMPLETE, false),
            merchantCityCode = obj.optString(KEY_MERCHANT_CITY_CODE),
            merchantPrincipal = obj.optLong(KEY_MERCHANT_PRINCIPAL, 0L).coerceAtLeast(0L),
            merchantTransportId = obj.optLong(KEY_MERCHANT_TRANSPORT_ID, 0L).coerceAtLeast(0L),
            nextRunAt = obj.optLong(KEY_NEXT_RUN_AT, 0L),
            lastMessage = obj.optString(KEY_LAST_MESSAGE),
            lastRunAt = obj.optLong(KEY_LAST_RUN_AT, 0L),
        )
    }

    private fun writeTaskConfig(task: LocalTask): JSONObject = JSONObject()
        .put(KEY_TASK_TYPE, task.taskType)
        .put(KEY_ENABLED, task.enabled)
        .put(KEY_TIME_OF_DAY, task.timeOfDay)
        .put(KEY_DURATION_MINUTES, task.durationMinutes)
        .put(KEY_DAILY_DRAW_LIMIT, task.dailyDrawLimit)
        .put(KEY_BOOKS, JSONArray(task.books.map { JSONObject().put("bookId", it.bookId).put("name", it.name) }))
        .put(KEY_MERCHANT_AUTO_COMPLETE, task.merchantAutoComplete)
        .put(KEY_MERCHANT_CITY_CODE, task.merchantCityCode)
        .put(KEY_MERCHANT_PRINCIPAL, task.merchantPrincipal)
        .put(KEY_MERCHANT_TRANSPORT_ID, task.merchantTransportId)

    private fun readAccount(accountId: String): JSONObject? {
        val raw = prefs()?.getString(accountKey(accountId), null) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private inline fun editAccount(accountId: String, block: (root: JSONObject, tasks: JSONObject) -> Unit) {
        val prefs = prefs() ?: return
        val root = readAccount(accountId) ?: JSONObject()
        val tasks = root.optJSONObject(KEY_TASKS) ?: JSONObject().also { root.put(KEY_TASKS, it) }
        block(root, tasks)
        root.put(KEY_TASKS, tasks)
        prefs.edit().putString(accountKey(accountId), root.toString()).commit()
    }

    private fun accountKey(accountId: String): String = "$KEY_ACCOUNT_PREFIX$accountId"

    private fun prefs(): SharedPreferences? =
        contextProvider()?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(ByteBuffer.allocate(4 + iv.size + body.size).apply {
                putInt(iv.size)
                put(iv)
                put(body)
            }.array(), Base64.NO_WRAP)
        }.getOrElse { "" }
    }

    private fun decrypt(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return runCatching {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(bytes)
            val ivSize = buffer.int
            require(ivSize in 12..32)
            val iv = ByteArray(ivSize).also(buffer::get)
            val body = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrDefault("")
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
        private const val KEY_ACCOUNT_PREFIX = "account_"
        private const val KEY_TASKS = "tasks"
        private const val KEY_TOKEN = "token"
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
        const val KEY_NEXT_RUN_AT = "nextRunAt"
        const val KEY_LAST_MESSAGE = "lastMessage"
        const val KEY_LAST_RUN_AT = "lastRunAt"

        // 由 LocalTaskRunner 写回、saveTask 更新配置时需保留的运行时状态键。
        internal val RUNTIME_STATE_KEYS = setOf(
            KEY_NEXT_RUN_AT, KEY_LAST_MESSAGE, KEY_LAST_RUN_AT,
            "dailyCounterDate", "dailyCounter", "lastDrawItems", "lastDrawResult", "lastDrawAt",
            "dailyReadDate", "dailyReadMinutes", "bookRotation",
            "lastCheckinDate", "lastCheckinAt", "claimDueAt", "claimCompletedDate",
            "merchantLastNotifiedTripId", "merchantPausedUntil", "merchantStartAfterSettle",
        )

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "reamicro-local-task-credentials"
    }
}
