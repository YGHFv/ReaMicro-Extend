package com.reamicro.fix.hook

import android.util.Base64
import com.reamicro.fix.cloud.webdav.WebDavSyncProtocol
import com.reamicro.fix.cloud.github.GitHubSyncProtocol
import org.json.JSONObject

/** Connection and remote reconciliation state travel together; device id and Wi-Fi policy stay on this device. */
internal class AccountSyncPreferences private constructor(private val entries: Map<String, Any?>) {
    fun values(): Map<String, Any?> = entries.mapValues { (_, value) ->
        if (value is ByteArray) value.copyOf() else value
    }.let { values ->
        if (values["s3_sync_bucket"] in setOf(WebDavSyncProtocol.BUCKET, GitHubSyncProtocol.BUCKET))
            values + ("s3_sync_cloud_config_enabled" to false)
        else values
    }

    fun toJson(): JSONObject = JSONObject().apply {
        entries.forEach { (key, value) ->
            put(key, when (value) {
                null -> JSONObject.NULL
                is ByteArray -> Base64.encodeToString(value, Base64.NO_WRAP)
                else -> value
            })
        }
    }

    companion object {
        val KEYS = listOf("s3_sync_provider", "s3_sync_endpoint", "s3_sync_bucket", "s3_sync_region",
            "s3_sync_access_key", "s3_sync_secret_key", "s3_sync_prefix", "s3_sync_cloud_config_enabled",
            "s3_sync_baseline_cbor", "s3_sync_pending_deletions")

        fun capture(read: (String) -> Any?): AccountSyncPreferences = AccountSyncPreferences(
            KEYS.associateWith { key ->
                val value = read(key)
                check(valid(key, value)) { "Invalid native sync preference type: $key" }
                if (value is ByteArray) value.copyOf() else value
            },
        )

        fun fromJson(json: JSONObject?): AccountSyncPreferences? {
            if (json == null || KEYS.any { !json.has(it) }) return null
            val values = linkedMapOf<String, Any?>()
            for (key in KEYS) {
                val raw = json.opt(key).takeUnless { it == JSONObject.NULL }
                val value = if (key == "s3_sync_baseline_cbor" && raw is String) {
                    runCatching { Base64.decode(raw, Base64.DEFAULT) }.getOrNull() ?: return null
                } else raw
                if (!valid(key, value)) return null
                values[key] = value
            }
            return AccountSyncPreferences(values)
        }

        fun restoreValues(target: AccountSyncPreferences?, previous: AccountSyncPreferences): Map<String, Any?> {
            if (target != null) return target.values()
            val before = previous.values()
            // Old account credentials carry no S3 snapshot. Keep native device connection behavior,
            // but never reuse another account's merge baseline, deletion queue or module WebDAV login.
            return if (before["s3_sync_bucket"] in setOf(WebDavSyncProtocol.BUCKET, GitHubSyncProtocol.BUCKET)) {
                KEYS.associateWith { if (it == "s3_sync_cloud_config_enabled") false else null }
            } else before + mapOf("s3_sync_baseline_cbor" to null, "s3_sync_pending_deletions" to null)
        }

        private fun valid(key: String, value: Any?): Boolean = value == null || when (key) {
            "s3_sync_cloud_config_enabled" -> value is Boolean
            "s3_sync_baseline_cbor" -> value is ByteArray
            else -> value is String
        }
    }
}
