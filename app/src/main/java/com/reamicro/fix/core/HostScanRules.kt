package com.reamicro.fix.core

/**
 * 自动扫描的纯数据约束。扫描只接受唯一候选，不用排序第一名代替唯一性。
 * 版本参数变化不在本层修补，交给对应 ABI 适配明确处理。
 */
object HostScanRules {
    private val referenceType = Regex("L[^;]+;")
    private val stablePrefixes = listOf("Ljava/", "Ljavax/", "Landroid/", "Ldalvik/")

    fun compatibleSignature(expected: String, actual: String): Boolean =
        normalize(expected) == normalize(actual)

    private fun normalize(descriptor: String): String =
        referenceType.replace(descriptor.filterNot(Char::isWhitespace)) { match ->
            if (stablePrefixes.any(match.value::startsWith)) {
                match.value
            } else "L*;"
        }
}
