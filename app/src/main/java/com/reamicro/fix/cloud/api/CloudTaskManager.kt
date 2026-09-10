package com.reamicro.fix.cloud.api

import org.json.JSONArray
import org.json.JSONObject

data class CloudTask(
    val id: String,
    val taskType: String,
    val credentialId: String,
    val status: String,
    val enabled: Boolean,
    val timeOfDay: String,
    val durationMinutes: Int,
    val dailyDrawLimit: Int,
    val books: List<CloudTaskBook>,
    val nextRunAt: Long,
    val lastMessage: String,
    val executionMode: String,
    val merchantAutoComplete: Boolean = false,
    val merchantCityCode: String = "",
    val merchantPrincipal: Long = 0L,
    val merchantTransportId: Long = 0L,
)

data class CloudTaskBook(
    val bookId: Long,
    val name: String,
)

data class ReaMicroCredential(
    val id: String,
    val label: String,
    val accountId: String,
    val updatedAt: Long,
    val lastVerifyMessage: String,
)

class CloudTaskManager(private val client: ApiServerClient) {
    fun create(taskType: String, scheduleSeconds: Long, request: JSONObject = JSONObject()): CloudTask {
        val body = JSONObject()
            .put("taskType", taskType)
            .put("executionMode", cloudTaskExecutionMode(taskType))
            .put("schedule", JSONObject().put("intervalSeconds", scheduleSeconds.coerceAtLeast(60)))
            .put("request", request)
        return parseCloudTask(client.createTask(body))
    }

    fun createAutomation(
        taskType: String,
        credentialId: String,
        timeOfDay: String,
        request: JSONObject = JSONObject(),
    ): CloudTask {
        val body = JSONObject()
            .put("taskType", taskType)
            .put("executionMode", cloudTaskExecutionMode(taskType))
            .put("schedule", cloudAutomationSchedule(taskType, timeOfDay))
            .put("request", request.put("credentialId", credentialId))
        return parseCloudTask(client.createTask(body))
    }

    fun saveAutomation(
        taskType: String,
        enabled: Boolean,
        credentialId: String,
        timeOfDay: String,
        request: JSONObject = JSONObject(),
    ): CloudTask {
        val schedule = cloudAutomationSchedule(taskType, timeOfDay)
        val taskRequest = request.put("credentialId", credentialId)
        val existing = list().firstOrNull { it.taskType == taskType && it.credentialId == credentialId }
        return if (existing == null) {
            val body = JSONObject()
                .put("taskType", taskType)
                .put("executionMode", cloudTaskExecutionMode(taskType))
                .put("enabled", enabled)
                .put("schedule", schedule)
                .put("request", taskRequest)
            parseCloudTask(client.createTask(body))
        } else {
            parseCloudTask(client.configureTask(existing.id, JSONObject()
                .put("enabled", enabled)
                .put("executionMode", cloudTaskExecutionMode(taskType))
                .put("schedule", schedule)
                .put("request", taskRequest)))
        }
    }

    fun list(): List<CloudTask> {
        val json = client.listTasks()
        val items = json.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it)?.let(::parseCloudTask) }
    }

    fun pause(id: String) = parseCloudTask(client.taskAction(id, "pause"))
    fun resume(id: String) = parseCloudTask(client.taskAction(id, "resume"))
    fun cancel(id: String) = parseCloudTask(client.taskAction(id, "cancel"))

    fun runNow(id: String) = parseCloudTask(client.taskAction(id, "run"))

    fun credentials(): List<ReaMicroCredential> {
        val items = client.listReaMicroCredentials().optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            ReaMicroCredential(
                id = item.optString("id"),
                label = item.optString("label", "阅微账号"),
                accountId = item.optString("accountId"),
                updatedAt = item.optLong("updatedAt"),
                lastVerifyMessage = item.optString("lastVerifyMessage"),
            )
        }
    }

    fun uploadCredential(token: String, label: String, accountId: String = ""): ReaMicroCredential {
        val root = client.saveReaMicroCredential(JSONObject()
            .put("token", token)
            .put("label", label)
            .put("accountId", accountId))
        val item = root.optJSONObject("data") ?: root
        return ReaMicroCredential(
            id = item.optString("id"),
            label = item.optString("label", "阅微账号"),
            accountId = item.optString("accountId"),
            updatedAt = item.optLong("updatedAt"),
            lastVerifyMessage = item.optString("lastVerifyMessage"),
        )
    }

    fun deleteCredential(id: String) {
        client.deleteReaMicroCredential(id)
    }

}

/** 会跑在设备（模块进程）或服务器上的阅微任务族。行商通知与签到等同属此族。 */
internal val REAMICRO_AUTOMATION_TASK_TYPES = setOf(
    "yeshe_checkin",
    "yeshe_draw_card",
    "cloud_auto_read",
    "traveling_merchant",
)

internal fun cloudTaskExecutionMode(taskType: String): String =
    if (taskType in REAMICRO_AUTOMATION_TASK_TYPES) "device" else "server"

internal fun cloudAutomationSchedule(taskType: String, timeOfDay: String): JSONObject =
    when (taskType) {
        "yeshe_draw_card" -> JSONObject().put("event", "yeshe_checkin_reward_claimed")
        // 行商通知按固定间隔轮询（默认 4 小时），不绑定每日时间点。
        "traveling_merchant" -> JSONObject().put("intervalSeconds", TRAVELING_MERCHANT_POLL_SECONDS)
        else -> JSONObject()
            .put("intervalSeconds", 86_400)
            .put("timeOfDay", timeOfDay)
            .put("timezoneOffsetMinutes", 480)
    }

/** 行商轮询间隔：每 4 小时检查一次行商状态。 */
internal const val TRAVELING_MERCHANT_POLL_SECONDS = 4L * 3_600L

internal fun parseCloudTask(root: JSONObject): CloudTask {
    val data = root.optJSONObject("data") ?: root
    val configuration = data.optJSONObject("configuration") ?: JSONObject()
    val booksJson = configuration.optJSONArray("books") ?: JSONArray()
    val books = (0 until booksJson.length()).mapNotNull { index ->
        val item = booksJson.optJSONObject(index) ?: return@mapNotNull null
        val bookId = item.optLong("bookId", item.optLong("cloudBookId", 0L))
        if (bookId <= 0L) return@mapNotNull null
        CloudTaskBook(
            bookId = bookId,
            name = item.optString("name"),
        )
    }
    return CloudTask(
        id = data.optString("id"),
        taskType = data.optString("taskType"),
        credentialId = data.optString("credentialId"),
        status = data.optString("status"),
        enabled = data.optBoolean("enabled", false),
        timeOfDay = data.optJSONObject("schedule")?.optString("timeOfDay").orEmpty(),
        durationMinutes = configuration.optInt("durationMinutes", 30).coerceIn(1, 720),
        dailyDrawLimit = configuration.optInt("dailyLimit", 3).coerceIn(0, 20),
        books = books,
        nextRunAt = data.optLong("nextRunAt", 0L),
        lastMessage = data.optString("lastMessage"),
        executionMode = data.optString("executionMode", "server"),
        merchantAutoComplete = configuration.optBoolean("merchantAutoComplete", false),
        merchantCityCode = configuration.optString("merchantCityCode"),
        merchantPrincipal = configuration.optLong("merchantPrincipal", 0L).coerceAtLeast(0L),
        merchantTransportId = configuration.optLong("merchantTransportId", 0L).coerceAtLeast(0L),
    )
}
