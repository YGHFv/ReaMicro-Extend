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
    val books: List<CloudTaskBook>,
    val nextRunAt: Long,
    val lastMessage: String,
)

data class CloudTaskBook(
    val cloudBookId: Long,
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
            .put("schedule", JSONObject()
                .put("intervalSeconds", 86_400)
                .put("timeOfDay", timeOfDay)
                .put("timezoneOffsetMinutes", 480))
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
        val schedule = JSONObject()
            .put("intervalSeconds", 86_400)
            .put("timeOfDay", timeOfDay)
            .put("timezoneOffsetMinutes", 480)
        val taskRequest = request.put("credentialId", credentialId)
        val existing = list().firstOrNull { it.taskType == taskType && it.credentialId == credentialId }
        return if (existing == null) {
            val body = JSONObject()
                .put("taskType", taskType)
                .put("enabled", enabled)
                .put("schedule", schedule)
                .put("request", taskRequest)
            parseCloudTask(client.createTask(body))
        } else {
            parseCloudTask(client.configureTask(existing.id, JSONObject()
                .put("enabled", enabled)
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

internal fun parseCloudTask(root: JSONObject): CloudTask {
    val data = root.optJSONObject("data") ?: root
    val configuration = data.optJSONObject("configuration") ?: JSONObject()
    val booksJson = configuration.optJSONArray("books") ?: JSONArray()
    val books = (0 until booksJson.length()).mapNotNull { index ->
        val item = booksJson.optJSONObject(index) ?: return@mapNotNull null
        val cloudBookId = item.optLong("cloudBookId", item.optLong("bookId", 0L))
        if (cloudBookId <= 0L) return@mapNotNull null
        CloudTaskBook(
            cloudBookId = cloudBookId,
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
        books = books,
        nextRunAt = data.optLong("nextRunAt", 0L),
        lastMessage = data.optString("lastMessage"),
    )
}
