package com.reamicro.fix.cloud.api

import org.json.JSONObject

enum class ApiPackageKind(val wireValue: String) {
    ONLINE_SOURCE("online_source"),
    EPUB_STYLE("epub_style"),
    HIGHLIGHT_STYLE("highlight_style"),
    ASSOCIATION_SOURCE("association_source"),
    THEME("theme"),
    ;

    companion object {
        fun fromWireValue(value: String): ApiPackageKind? = entries.firstOrNull { it.wireValue == value }
    }
}

data class ApiContentPackage(
    val packageId: String,
    val kind: ApiPackageKind,
    val version: String,
    val schemaVersion: Int,
    val minModuleVersion: String,
    val sha256: String,
    val signature: String,
    val downloadUrl: String,
    val name: String,
    val description: String,
    val contentId: String = "",
    val aliases: Set<String> = emptySet(),
    val buildTime: Long = 0L,
    val payloadName: String = "payload.bin",
) {
    fun signedMessage(): ByteArray =
        "$packageId\n${kind.wireValue}\n${contentId.ifBlank { packageId }}\n$version\n$buildTime\n$sha256".toByteArray(Charsets.UTF_8)

    companion object {
        fun fromJson(json: JSONObject): ApiContentPackage? {
            val kind = ApiPackageKind.fromWireValue(json.optString("kind")) ?: return null
            val packageId = json.optString("packageId").trim().ifBlank { return null }
            val version = json.optString("version").trim().ifBlank { return null }
            return ApiContentPackage(
                packageId = packageId,
                kind = kind,
                version = version,
                schemaVersion = json.optInt("schemaVersion", 1),
                minModuleVersion = json.optString("minModuleVersion"),
                sha256 = json.optString("sha256").lowercase(),
                signature = json.optString("signature"),
                downloadUrl = json.optString("downloadUrl"),
                name = json.optString("name").ifBlank { packageId },
                description = json.optString("description"),
                contentId = json.optString("contentId"),
                aliases = buildSet {
                    val array = json.optJSONArray("aliases") ?: return@buildSet
                    for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                },
                buildTime = json.optLong("buildTime", 0L),
                payloadName = json.optString("payload").ifBlank { "payload.bin" },
            )
        }
    }
}

internal fun isPackageUpdateAvailable(
    remoteVersion: String,
    remoteBuildTime: Long,
    installedVersion: String,
    installedBuildTime: Long,
): Boolean {
    val versionComparison = comparePackageVersions(remoteVersion, installedVersion)
    return versionComparison > 0 || (versionComparison == 0 && remoteBuildTime > installedBuildTime)
}

/**
 * 判断版本号是否能被 [comparePackageVersions] 有意义地比较。
 * `comparePackageVersions` 会把无法解析的段当成 0，所以 `ci-123-1` 这类标签
 * 会被解析成 0.0.0 并被判定为比任何正式版本旧，调用方需要先用本函数排除。
 */
internal fun isSemanticVersion(value: String): Boolean {
    val core = value.trim().substringBefore('-')
    if (core.isBlank()) return false
    return core.split('.').all { it.isNotBlank() && it.toIntOrNull() != null }
}

internal fun comparePackageVersions(left: String, right: String): Int {
    fun parts(value: String): List<Int> = value.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(left)
    val b = parts(right)
    for (index in 0 until maxOf(a.size, b.size)) {
        val comparison = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    val leftSuffix = left.substringAfter('-', "")
    val rightSuffix = right.substringAfter('-', "")
    return when {
        leftSuffix.isBlank() && rightSuffix.isBlank() -> 0
        leftSuffix.isBlank() -> 1
        rightSuffix.isBlank() -> -1
        else -> leftSuffix.compareTo(rightSuffix)
    }
}
