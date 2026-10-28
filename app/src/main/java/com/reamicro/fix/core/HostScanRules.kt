package com.reamicro.fix.core

/**
 * 自动扫描的纯数据约束。扫描只接受唯一候选，不用排序第一名代替唯一性。
 * 版本参数变化不在本层修补，交给对应 ABI 适配明确处理。
 */
object HostScanRules {
    const val ENGINE_VERSION = 2
    fun <T> unique(candidates: Collection<T>): T? = candidates.singleOrNull()

    fun cacheKey(apkDigest: String, rulesDigest: String): String =
        "scan-v$ENGINE_VERSION:$apkDigest:$rulesDigest"

    fun compatibleSignature(expected: String, actual: String): Boolean =
        normalize(expected) == normalize(actual)

    fun normalize(descriptor: String): String =
        Regex("L[^;]+;").replace(descriptor.filterNot(Char::isWhitespace)) { match ->
            if (listOf("Ljava/", "Ljavax/", "Landroid/", "Ldalvik/").any(match.value::startsWith)) {
                match.value
            } else "L*;"
        }
}
