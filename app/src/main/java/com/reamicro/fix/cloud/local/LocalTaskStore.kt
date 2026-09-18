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
    /**
     * 期物典当要跳过的 propId。空集合表示不禁止任何期物。
     *
     * 没保存过这个字段的旧配置由 [localTaskFromJson] 回落到
     * [CloudTaskLocalRunner.PROHIBITED_PAWN_PROP_HINTS] 的默认清单，保证老用户行为不变。
     */
    val forbiddenPawnPropIds: Set<String> = emptySet(),
    /** 执行前要祈禳的道观运签签种（空 = 不祈禳）。wire 值取自游戏：LUCK/SAFETY/WEALTH。 */
    val blessingType: String = "",
    // 运行时状态
    val nextRunAt: Long = 0L,
    val lastMessage: String = "",
    val lastRunAt: Long = 0L,
    val configUpdatedAt: Long = 0L,
)

data class LocalTaskBook(
    val bookId: Long,
    val name: String,
)

/** 一条本地任务执行记录。前后台（宿主/模块）各写各的，UI 合并展示。 */
data class LocalTaskRecord(
    val at: Long,
    val taskType: String,
    val result: String,
    val message: String,
    /**
     * 这条记录的细节，形如 `{"运签":"求运签 · 初晴签","效果":"下一次每日轶闻：绿色及以上概率提升 2 个百分点"}`。
     *
     * 存 JSON 而不是固定字段：不同任务要展示的东西完全不同（轶闻看奖励、行商看事件与收益、
     * 典当看期物与铜钱），固定字段会逼着每个任务都填一堆空值。
     */
    val detail: String = "",
)

/**
 * 本地自动任务存储。与云端任务不同，本地任务的凭据（阅微 token）只保存在本机，
 * 用 Android Keystore AES/GCM 加密，按阅微账号（accountId）分组保存到 SharedPreferences。
 *
 * 运行时状态（下次执行时间、每日计数、行商已通知 tripId 等）也一并落盘，
 * 便于系统闹钟静默唤醒时无 UI 也能续跑。
 */
class LocalTaskStore(private val contextProvider: () -> Context?) : LocalTaskRepository {

    /** 读取某账号下的所有本地任务配置（含运行时状态）。 */
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

    /** 保存（新建或更新）一条任务的配置。保存时刷新加密 token，重置 nextRunAt 让其尽快执行。 */
    fun saveTask(accountId: String, task: LocalTask, token: String) {
        if (accountId.isBlank()) return
        editAccount(accountId) { root, tasks ->
            val existing = tasks.optJSONObject(task.taskType)
            val merged = writeTaskConfig(task)
            merged.put(KEY_CONFIG_UPDATED_AT, maxOf(System.currentTimeMillis(), (existing?.optLong(KEY_CONFIG_UPDATED_AT) ?: 0L) + 1L))
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
        syncKsuConfiguration()
    }

    /** 切换启用状态，不改动其它配置。 */
    fun setEnabled(accountId: String, taskType: String, enabled: Boolean, token: String = "") {
        if (accountId.isBlank()) return
        editAccount(accountId) { root, tasks ->
            val obj = tasks.optJSONObject(taskType) ?: JSONObject().put(KEY_TASK_TYPE, taskType)
            obj.put(KEY_CONFIG_UPDATED_AT, maxOf(System.currentTimeMillis(), obj.optLong(KEY_CONFIG_UPDATED_AT) + 1L))
            obj.put(KEY_ENABLED, enabled)
            obj.put(KEY_NEXT_RUN_AT, if (enabled) System.currentTimeMillis() else 0L)
            tasks.put(taskType, obj)
            if (enabled && token.isNotBlank()) root.put(KEY_TOKEN, encrypt(token))
        }
        syncKsuConfiguration()
    }

    /** 写回运行时状态（下次执行时间、最近消息、每日计数等）。state 为要合并的键值。 */
    override fun recordState(accountId: String, taskType: String, state: JSONObject) {
        if (accountId.isBlank()) return
        editAccount(accountId) { _, tasks ->
            val obj = tasks.optJSONObject(taskType) ?: return@editAccount
            state.keys().forEach { key -> obj.put(key, state.get(key)) }
            tasks.put(taskType, obj)
        }
    }

    /** 取当前账号已加密保存的阅微 token（供无 UI 唤醒时使用）。 */
    override fun token(accountId: String): String {
        val root = readAccount(accountId) ?: return ""
        return decrypt(root.optString(KEY_TOKEN))
    }

    /**
     * 读取某任务已落盘的运行时状态（每日计数、轮转、签到时间、行商已通知 tripId 等），
     * 供执行器读取上次执行结果续跑。返回的 JSON 只含 RUNTIME_STATE_KEYS 里的键。
     */
    override fun runtimeState(accountId: String, taskType: String): JSONObject {
        val obj = readAccount(accountId)?.optJSONObject(KEY_TASKS)?.optJSONObject(taskType) ?: return JSONObject()
        val state = JSONObject()
        for (stateKey in RUNTIME_STATE_KEYS) {
            if (obj.has(stateKey)) state.put(stateKey, obj.get(stateKey))
        }
        return state
    }

    /** 所有账号中「已启用任务」的最近一次待执行时间，供闹钟排程取 min。0 表示无。 */
    fun earliestNextRunAt(): Long {
        var earliest = Long.MAX_VALUE
        val now = System.currentTimeMillis()
        for (accountId in storedAccountIds()) {
            val checkin = runtimeState(accountId, "yeshe_checkin")
            for (task in list(accountId)) {
                val next = nextLocalTaskAt(task, runtimeState(accountId, task.taskType), checkin, now) ?: continue
                if (next in 1 until earliest) earliest = next
            }
        }
        return if (earliest == Long.MAX_VALUE) 0L else earliest
    }

    /** 是否存在任意账号的任意已启用本地任务。 */
    fun hasEnabledTasks(): Boolean =
        storedAccountIds().any { accountId -> list(accountId).any { it.enabled } }

    fun recoverPendingAutomationTasks(now: Long = System.currentTimeMillis()) {
        for (accountId in storedAccountIds()) {
            for (task in list(accountId)) {
                if (!task.enabled) continue
                val state = recoveredAutomationState(task.taskType, runtimeState(accountId, task.taskType), now) ?: continue
                recordState(accountId, task.taskType, state)
            }
        }
    }

    /**
     * 本机存过数据的所有账号 ID（不区分任务是否启用）。
     *
     * 给模块主界面用：那里要展示"本机存了哪些账号的任务记录"，只取已启用账号会在用户
     * 临时关掉任务后让记录凭空消失。
     */
    override fun accountIds(): List<String> = storedAccountIds()

    /**
     * 按任务配置的时间点重算已启用任务的下次执行时刻（**不执行任务**）。
     *
     * 用来修正历史遗留的旧值：早前排程用 `now + 24h`，与用户配的「每天 HH:mm」无关，
     * 那些任务因为时刻在将来又不会被 runDue 选中，光靠执行永远修不回来。
     *
     * 但它只能**提前**、不能推后：签到没领到奖励时它的下次执行是"解锁时刻"（比如次日 08:00），
     * 直接按每日时间点重算会把这个约定抹成次日 00:00，用户看到的就是"任务时刻刷新了、
     * 奖励却没下文"。所以取两者中更早的那个——旧值更晚说明是遗留的 `now + 24h`，按配置纠正；
     * 旧值更早说明是一个仍在等待中的节点，保留它。
     */
    fun rescheduleEnabledTasks(now: Long = System.currentTimeMillis()): Int {
        val context = contextProvider()
        if (context != null && com.reamicro.fix.cloud.ksu.KsuTaskBridge.isEnabled(context)) {
            return com.reamicro.fix.cloud.ksu.KsuTaskBridge.reschedule(context)
        }
        return rescheduleLocalTasks(this, now)
    }

    /** 所有已启用任务的账号集合（去重）。 */
    fun accountsWithEnabledTasks(): Set<String> =
        storedAccountIds().filterTo(linkedSetOf()) { accountId -> list(accountId).any { it.enabled } }

    /**
     * prefs 里实际存了数据的账号 ID。
     *
     * 必须先把 [KEY_ACCOUNT_PREFIX] 剥掉再交给 [list]：prefs 的 key 形如 `account_<accountId>`，
     * 直接把它当 accountId 传进去，[accountKey] 会再加一次前缀，永远查不到账号。踩过这个坑的
     * 表现是「本地任务配置得好好的，前台后台却从不执行」——[accountsWithEnabledTasks] 恒空集，
     * 于是 [LocalTaskRunner.runDue] 一个任务都不遍历。
     */
    private fun storedAccountIds(): List<String> =
        accountIdsFromStorageKeys(prefs()?.all?.keys.orEmpty())

    /** 追加一条执行记录；只保留最近 [MAX_RECORDS] 条，避免无限增长。 */
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

    /** 读取某账号的执行记录，最新在前。 */
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
        if (context.packageName == LocalTaskMirror.MODULE_PACKAGE) syncKsuConfiguration()
        else LocalTaskMirror.push(context)
    }

    /**
     * 组装下发给模块进程的镜像载荷：所有账号的任务配置 + **明文** token。
     *
     * 必须带明文 token：Android Keystore 的密钥按应用（UID）隔离，宿主进程加密的 token
     * 模块进程根本解不开，只能由模块收到后用自己的密钥重新加密落盘。
     */
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

    /** 镜像只接纳更新的配置，执行状态以模块进程为准。 */
    fun applyMirror(accountId: String, tasks: JSONObject, token: String, recordsClearedAt: Long = 0L) {
        if (accountId.isBlank()) return
        editAccount(accountId) { root, existing ->
            tasks.keys().forEach { taskType ->
                val incoming = tasks.optJSONObject(taskType) ?: return@forEach
                val current = existing.optJSONObject(taskType)
                existing.put(taskType, mergeMirroredTask(current, incoming, System.currentTimeMillis()).put(KEY_TASK_TYPE, taskType))
            }
            if (token.isNotBlank()) root.put(KEY_TOKEN, encrypt(token))
            clearLocalTaskRecordsBefore(root, recordsClearedAt)
        }
        syncKsuConfiguration()
    }

    fun snapshotPayload(): JSONObject {
        val accounts = JSONObject()
        for (accountId in storedAccountIds()) {
            val root = readAccount(accountId) ?: continue
            val records = root.optJSONArray(KEY_RECORDS) ?: JSONArray()
            val recent = JSONArray()
            for (index in (records.length() - 20).coerceAtLeast(0) until records.length()) recent.put(records.get(index))
            accounts.put(accountId, JSONObject()
                .put(KEY_TASKS, root.optJSONObject(KEY_TASKS) ?: JSONObject())
                .put("recordsClearedAt", root.optLong("recordsClearedAt"))
                .put(KEY_RECORDS, recent))
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

    private fun syncKsuConfiguration() {
        contextProvider()?.let { com.reamicro.fix.cloud.ksu.KsuTaskBridge.requestSync(it) }
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

        // 由 LocalTaskRunner 写回、saveTask 更新配置时需保留的运行时状态键。
        internal val RUNTIME_STATE_KEYS = setOf(
            KEY_NEXT_RUN_AT, KEY_LAST_MESSAGE, KEY_LAST_RUN_AT,
            "dailyCounterDate", "dailyCounter", "lastDrawItems", "lastDrawResult", "lastDrawAt",
            "drawPending", "drawRewardLoreId",
            "dailyReadDate", "dailyReadMinutes", "bookRotation",
            "lastPawnDate", "pawnUsedToday", "pawnLastCoin",
            "lastCheckinDate", "lastCheckinAt", "claimDueAt", "claimCompletedDate", "claimLoreId", "automationStateVersion",
            CloudTaskLocalRunner.KEY_MERCHANT_NOTIFIED_TRIP, "merchantPausedUntil", "merchantStartAfterSettle",
            // 行商按趟记账：结算过哪趟、为哪趟开过新行商、哪趟查过运签。三者缺一都会让
            // 某件事被重复做或永远不做，所以必须一起保留。
            KEY_MERCHANT_SETTLED_TRIP, KEY_MERCHANT_RESTART_AFTER, KEY_MERCHANT_BLESSED_TRIP, KEY_MERCHANT_END_TIME,
            // 上次观察到的行商参数：自动开新行商未填城池/本金/车马时沿用。
            KEY_MERCHANT_LAST_CITY, KEY_MERCHANT_LAST_TRANSPORT, KEY_MERCHANT_LAST_PRINCIPAL,
        )

        internal const val KEY_MERCHANT_LAST_CITY = "merchantLastCityCode"
        internal const val KEY_MERCHANT_END_TIME = "merchantEndTime"
        internal const val KEY_MERCHANT_LAST_TRANSPORT = "merchantLastTransportId"
        internal const val KEY_MERCHANT_LAST_PRINCIPAL = "merchantLastPrincipal"
        /** 已经自动结算过的行商趟次 id。 */
        internal const val KEY_MERCHANT_SETTLED_TRIP = "merchantSettledTripId"
        /** 已经为哪一趟结算成功开启过新行商。用于失败后重试。 */
        internal const val KEY_MERCHANT_RESTART_AFTER = "merchantRestartedAfterTripId"
        /** 已经检查/祈禳过运签的行商趟次 id，避免每次轮询都重复祈禳。 */
        internal const val KEY_MERCHANT_BLESSED_TRIP = "merchantBlessedTripId"
        // 镜像只下发这些「配置」字段；其余（运行时状态）由各自进程保留。
        private val CONFIG_KEYS = setOf(
            KEY_ENABLED, KEY_TIME_OF_DAY, KEY_DURATION_MINUTES, KEY_DAILY_DRAW_LIMIT, KEY_BOOKS,
            KEY_MERCHANT_AUTO_COMPLETE, KEY_MERCHANT_CITY_CODE, KEY_MERCHANT_PRINCIPAL, KEY_MERCHANT_TRANSPORT_ID,
            KEY_FORBIDDEN_PAWN_PROP_IDS,
            KEY_BLESSING_TYPE,
        )

        internal fun mergeMirroredTask(current: JSONObject?, incoming: JSONObject, now: Long): JSONObject {
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
                merged.put(KEY_NEXT_RUN_AT, if (merged.optBoolean(KEY_ENABLED)) now else 0L)
            }
            return merged
        }
        // 执行记录只保留最近若干条，避免 SharedPreferences 无限增长。
        private const val KEY_RECORDS = "records"
        internal const val MAX_RECORDS = 100

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "reamicro-local-task-credentials"
    }
}

/**
 * `account_<accountId>` → `<accountId>`；不是账号键或 ID 为空时返回 null。
 *
 * 与 [LocalTaskStore] 内部的账号键拼法（`KEY_ACCOUNT_PREFIX + accountId`）严格互逆，
 * 这个往返关系是那组聚合方法正确性的基础，所以单独抽出来并配了单测
 * （`LocalTaskStoreAccountKeysTest`）。
 */
internal fun accountIdFromStorageKey(storageKey: String): String? =
    storageKey.takeIf { it.startsWith(LocalTaskStore.KEY_ACCOUNT_PREFIX) }
        ?.removePrefix(LocalTaskStore.KEY_ACCOUNT_PREFIX)
        ?.takeIf { it.isNotBlank() }

/** 从 SharedPreferences 的 key 集合里取出账号 ID；非账号键一律忽略。 */
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
