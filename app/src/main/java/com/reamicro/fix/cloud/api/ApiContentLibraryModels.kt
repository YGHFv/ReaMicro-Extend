package com.reamicro.fix.cloud.api

import org.json.JSONArray
import org.json.JSONObject

/** 服务器返回的模块上传许可。 */
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

/**
 * 待上传或待关联的本地源。
 * [domains] 是书源地址解析出的域名，[identities] 是本地稳定 ID 与别名，
 * 服务器优先用“名称 + 域名”比对，关联源没有域名时退化为“名称 + 标识”。
 */
data class ApiLibraryItem(
    val kind: ApiPackageKind,
    val name: String,
    val contentId: String,
    val domains: Set<String>,
    val identities: Set<String>,
    val payloadName: String,
    val payload: ByteArray,
    /** 本地落地标识：书源为源 ID，关联源为文件名，安装时用于复用同一份本地内容。 */
    val localContentId: String,
) {
    fun toDescriptorJson(): JSONObject = JSONObject()
        .put("kind", kind.wireValue)
        .put("name", name)
        .put("contentId", contentId)
        .put("domains", JSONArray(domains.toList()))
        .put("identities", JSONArray(identities.toList()))

    override fun equals(other: Any?): Boolean =
        other is ApiLibraryItem && kind == other.kind && contentId == other.contentId

    override fun hashCode(): Int = 31 * kind.hashCode() + contentId.hashCode()
}

/** 服务器内容包摘要，仅包含关联和后续更新需要的字段。 */
data class ApiPackageSummary(
    val kind: ApiPackageKind,
    val packageId: String,
    val contentId: String,
    val version: String,
    val buildTime: Long,
    val name: String,
    val aliases: Set<String>,
) {
    companion object {
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
                aliases = buildSet {
                    val array = json.optJSONArray("aliases") ?: return@buildSet
                    for (index in 0 until array.length()) {
                        array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                    }
                },
            )
        }
    }
}

/** 单个源的比对结果。 */
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

/** 单个源的上传结果。 */
data class ApiUploadResult(
    val uploaded: Boolean,
    val linked: Boolean,
    val message: String,
    val summary: ApiPackageSummary?,
)

/** 内容库上传或关联的汇总结果。 */
data class ApiLibrarySyncSummary(
    val total: Int,
    val uploaded: Int,
    val linked: Int,
    val failed: Int,
    val messages: List<String>,
) {
    val changed: Int get() = uploaded + linked
}

/**
 * 从书源地址、域名或主机名解析出可比较的小写域名；
 * 纯标识（例如 youshu）不含点，不作为域名参与比对。
 */
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
