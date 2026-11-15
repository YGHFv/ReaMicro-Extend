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

internal fun isSemanticVersion(value: String): Boolean {
    val normalized = value.trim().substringBefore('+')
    val core = normalized.substringBefore('-')
    if (core.isBlank()) return false
    if (!core.split('.').all { part -> part.isNotEmpty() && part.all { it in '0'..'9' } }) return false
    return '-' !in normalized || normalized.substringAfter('-').split('.')
        .all { part -> part.isNotEmpty() && part.all { it.isLetterOrDigit() || it == '-' } }
}

internal fun comparePackageVersions(left: String, right: String): Int {
    val normalizedLeft = left.trim().substringBefore('+')
    val normalizedRight = right.trim().substringBefore('+')
    fun parts(value: String): List<java.math.BigInteger> =
        value.substringBefore('-').split('.').map { it.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO }
    val a = parts(normalizedLeft)
    val b = parts(normalizedRight)
    for (index in 0 until maxOf(a.size, b.size)) {
        val comparison = a.getOrElse(index) { java.math.BigInteger.ZERO }
            .compareTo(b.getOrElse(index) { java.math.BigInteger.ZERO })
        if (comparison != 0) return comparison
    }
    val leftSuffix = normalizedLeft.substringAfter('-', "")
    val rightSuffix = normalizedRight.substringAfter('-', "")
    if (leftSuffix.isEmpty() || rightSuffix.isEmpty()) {
        return when {
            leftSuffix == rightSuffix -> 0
            leftSuffix.isEmpty() -> 1
            else -> -1
        }
    }
    val x = leftSuffix.split('.')
    val y = rightSuffix.split('.')
    for (index in 0 until minOf(x.size, y.size)) {
        val xNumber = x[index].takeIf { it.all { char -> char in '0'..'9' } }?.toBigIntegerOrNull()
        val yNumber = y[index].takeIf { it.all { char -> char in '0'..'9' } }?.toBigIntegerOrNull()
        val comparison = when {
            xNumber != null && yNumber != null -> xNumber.compareTo(yNumber)
            xNumber != null -> -1
            yNumber != null -> 1
            else -> x[index].compareTo(y[index])
        }
        if (comparison != 0) return comparison
    }
    return x.size.compareTo(y.size)
}
