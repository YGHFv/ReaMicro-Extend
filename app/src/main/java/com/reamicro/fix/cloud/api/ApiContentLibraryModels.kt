package com.reamicro.fix.cloud.api

import org.json.JSONArray
import org.json.JSONObject

data class ApiUploadPolicy(
    val enabled: Boolean,
    val allowed: Boolean,
    val reason: String = "",
    val hostAccountId: String = "",
    val kinds: Set<ApiPackageKind> = emptySet(),
    val maxSize: Long = 0L,
) {
    companion object {
        fun fromJson(json: JSONObject): ApiUploadPolicy = ApiUploadPolicy(
            enabled = json.optBoolean("enabled", false),
            allowed = json.optBoolean("allowed", false),
            reason = json.optString("reason"),
            hostAccountId = json.optString("hostAccountId"),
            kinds = buildSet {
                val array = json.optJSONArray("kinds") ?: return@buildSet
                for (index in 0 until array.length()) {
                    ApiPackageKind.fromWireValue(array.optString(index))?.let(::add)
                }
            },
            maxSize = json.optLong("maxSize", 0L),
        )
    }
}

data class ApiLibraryItem(
    val kind: ApiPackageKind,
    val name: String,
    val contentId: String,

    val names: Set<String> = emptySet(),
    val domains: Set<String>,

    val primaryDomain: String = "",
    val identities: Set<String>,
    val payloadName: String,
    val payload: ByteArray,

    val localContentId: String,

    val usesLocalAssets: Boolean = false,
) {
    fun toDescriptorJson(): JSONObject = JSONObject()
        .put("kind", kind.wireValue)
        .put("name", name)
        .put("names", JSONArray((names + name).filter(String::isNotBlank)))
        .put("contentId", contentId)
        .put("domains", JSONArray(orderedDomains()))
        .put("primaryDomain", primaryDomain)
        .put("identities", JSONArray(identities.toList()))

    fun orderedDomains(): List<String> =
        (listOf(primaryDomain) + domains).filter(String::isNotBlank).distinct()

    override fun equals(other: Any?): Boolean =
        other is ApiLibraryItem && kind == other.kind && contentId == other.contentId

    override fun hashCode(): Int = 31 * kind.hashCode() + contentId.hashCode()
}

data class ApiPackageSummary(
    val kind: ApiPackageKind,
    val packageId: String,
    val contentId: String,
    val version: String,
    val buildTime: Long,
    val name: String,
    val aliases: Set<String>,
    val names: Set<String> = emptySet(),
    val domains: List<String> = emptyList(),
    val primaryDomain: String = "",

    val matchReason: String = "",
) {
    companion object {
        private fun stringSet(json: JSONObject, key: String): Set<String> = buildSet {
            val array = json.optJSONArray(key) ?: return@buildSet
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }

        fun fromJson(json: JSONObject): ApiPackageSummary? {
            val kind = ApiPackageKind.fromWireValue(json.optString("kind")) ?: return null
            val packageId = json.optString("packageId").trim().ifBlank { return null }
            return ApiPackageSummary(
                kind = kind,
                packageId = packageId,
                contentId = json.optString("contentId").trim().ifBlank { packageId },
                version = json.optString("version"),
                buildTime = json.optLong("buildTime", 0L),
                name = json.optString("name"),
                aliases = stringSet(json, "aliases"),
                names = stringSet(json, "names"),
                domains = stringSet(json, "domains").toList(),
                primaryDomain = json.optString("primaryDomain"),
                matchReason = json.optString("matchReason"),
            )
        }
    }
}

data class ApiLibraryMatch(
    val kind: ApiPackageKind,
    val name: String,
    val contentId: String,
    val matched: Boolean,
    val summary: ApiPackageSummary?,
) {
    companion object {
        fun fromJson(json: JSONObject): ApiLibraryMatch? {
            val kind = ApiPackageKind.fromWireValue(json.optString("kind")) ?: return null
            return ApiLibraryMatch(
                kind = kind,
                name = json.optString("name"),
                contentId = json.optString("contentId"),
                matched = json.optBoolean("matched", false),
                summary = json.optJSONObject("package")?.let(ApiPackageSummary::fromJson),
            )
        }
    }
}

data class ApiUploadResult(
    val uploaded: Boolean,
    val linked: Boolean,
    val message: String,
    val summary: ApiPackageSummary?,
)

data class ApiLibrarySyncSummary(
    val total: Int,
    val uploaded: Int,
    val linked: Int,
    val failed: Int,
    val messages: List<String>,
) {
    val changed: Int get() = uploaded + linked
}

internal fun normalizeSourceDomain(value: String): String {
    val text = value.trim()
    if (text.isBlank()) return ""
    var host = when {
        text.contains("://") -> runCatching { java.net.URI(text).host }.getOrNull().orEmpty()
            .ifBlank { text.substringAfter("://").substringBefore('/') }
        text.contains('/') -> text.substringBefore('/')
        else -> text
    }
    host = host.substringAfterLast('@').trim().trim('.').lowercase()
    host = when {
        host.startsWith("[") -> host.drop(1).substringBefore(']')
        host.contains(':') -> host.substringBeforeLast(':')
        else -> host
    }
    if (host.startsWith("www.")) host = host.removePrefix("www.")
    if (host.isBlank() || host.contains(' ') || !host.contains('.')) return ""
    if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return ""
    return host.take(120)
}
