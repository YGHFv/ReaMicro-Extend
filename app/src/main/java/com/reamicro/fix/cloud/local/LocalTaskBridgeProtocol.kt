package com.reamicro.fix.cloud.local

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal data class LocalTaskBridgeRequest(
    val id: String, val operation: String, val payload: String? = null, val accountId: String? = null,
)
internal data class LocalTaskBridgeReply(
    val id: String, val snapshot: String? = null, val error: String? = null,
) { val success: Boolean get() = error == null && snapshot != null }

internal interface LocalTaskBridgeBackend {
    fun apply(accountId: String, tasks: JSONObject, token: String, clearedAt: Long)
    fun snapshot(accountId: String?, recordLimit: Int): String
}

internal object LocalTaskBridgeProtocol {
    const val SYNC = "sync"
    const val READ = "read"
    const val MAX_BYTES = 256 * 1024
    val allowedPackages = setOf("app.zhendong.reamicro", "app.zhendong.reamicro.fix", "com.reamicro.fix")

    fun authorized(uid: Int, ownUid: Int, uidPackages: Collection<String>): Boolean =
        uid >= 0 && (uid == ownUid || uidPackages.any { it in allowedPackages })

    fun fits(raw: String): Boolean = raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES

    fun execute(request: LocalTaskBridgeRequest, authorized: Boolean, backend: LocalTaskBridgeBackend): LocalTaskBridgeReply {
        if (!authorized) return LocalTaskBridgeReply(request.id, error = "unauthorized")
        if (!request.id.matches(Regex("[A-Za-z0-9-]{1,64}"))) return LocalTaskBridgeReply(request.id, error = "invalid_request")
        return try {
            when (request.operation) {
                SYNC -> {
                    val raw = request.payload ?: return LocalTaskBridgeReply(request.id, error = "invalid_payload")
                    if (!fits(raw)) return LocalTaskBridgeReply(request.id, error = "payload_too_large")
                    val accounts = JSONObject(raw).getJSONObject("accounts")
                    require(accounts.length() <= 128)

                    val entries = accounts.keys().asSequence().map { id ->
                        require(id.isNotBlank() && id.length <= 256)
                        val entry = accounts.getJSONObject(id)
                        val tasks = entry.getJSONObject("tasks")
                        val token = entry.opt("token")?.let { require(it is String); it } ?: ""
                        val cleared = entry.optLong("recordsClearedAt", 0L)
                        require(cleared >= 0)
                        MirrorEntry(id, tasks, token, cleared)
                    }.toList()
                    entries.forEach { backend.apply(it.id, it.tasks, it.token, it.clearedAt) }
                }
                READ -> require(request.accountId == null || request.accountId.length in 1..256)
                else -> return LocalTaskBridgeReply(request.id, error = "unsupported_operation")
            }
            val snapshot = backend.snapshot(if (request.operation == READ) request.accountId else null,
                if (request.operation == READ) 100 else 0)
            if (!fits(snapshot)) return LocalTaskBridgeReply(request.id, error = "snapshot_too_large")
            if (!safeSnapshot(snapshot)) return LocalTaskBridgeReply(request.id, error = "invalid_snapshot")
            LocalTaskBridgeReply(request.id, snapshot)
        } catch (_: LocalTaskCredentialException) {
            LocalTaskBridgeReply(request.id, error = "credential_failed")

        } catch (_: Exception) {

            LocalTaskBridgeReply(request.id, error = "processing_failed")
        }
    }

    fun safeSnapshot(raw: String): Boolean = runCatching {
        val accounts = JSONObject(raw).getJSONObject("accounts")
        accounts.length() <= 128 && accounts.keys().asSequence().all { id ->
            require(id.isNotBlank() && id.length <= 256)
            val entry = accounts.getJSONObject(id)
            entry.has("tasks") && entry.get("tasks") is JSONObject &&
                entry.keys().asSequence().all { it in setOf("tasks", "records", "recordsClearedAt") }
        }
    }.getOrDefault(false)

    fun validateReply(expectedId: String, reply: LocalTaskBridgeReply): String? = when {
        reply.id != expectedId -> "invalid_receipt"
        reply.error != null -> reply.error
        reply.snapshot == null || !fits(reply.snapshot) || !safeSnapshot(reply.snapshot) -> "invalid_snapshot"
        else -> null
    }
    private data class MirrorEntry(val id: String, val tasks: JSONObject, val token: String, val clearedAt: Long)
}

internal class LocalTaskBridgeCompletion {
    private val claimed = AtomicBoolean(false)
    fun claim(): Boolean = claimed.compareAndSet(false, true)
    fun isClaimed(): Boolean = claimed.get()
}
