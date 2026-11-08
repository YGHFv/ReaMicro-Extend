package com.reamicro.fix.core

/**
 * 自动扫描的纯数据约束。扫描只接受唯一候选，不用排序第一名代替唯一性。
 * 版本参数变化不在本层修补，交给对应 ABI 适配明确处理。
 */
object HostScanRules {
    const val ENGINE_VERSION = 4
    fun <T> unique(candidates: Collection<T>): T? = candidates.singleOrNull()

    fun cacheKey(apkDigest: String, rulesDigest: String): String =
        "scan-v$ENGINE_VERSION:$apkDigest:$rulesDigest"

    fun compatibleSignature(expected: String, actual: String): Boolean =
        normalize(expected) == normalize(actual)

    /** 空字符串才是通配符；指定但尚未定位的逻辑类型不能降级为任意引用类型。 */
    fun matchesLogicalType(logical: String, actual: String, resolved: Map<String, String>): Boolean =
        logical.isEmpty() || resolved[logical] == actual

    /** DexKit 2.x 的 opcode 流把三种 DEX payload 保留为低字节 0，不能删除它们或跳过整段比较。 */
    fun dexKitOpCodes(opcodes: List<Int>): List<Int> = opcodes.map { opcode ->
        require(opcode in 0..255 || opcode == 0x100 || opcode == 0x200 || opcode == 0x300) {
            "Invalid DEX opcode: $opcode"
        }
        opcode and 0xff
    }

    fun normalize(descriptor: String): String =
        Regex("L[^;]+;").replace(descriptor.filterNot(Char::isWhitespace)) { match ->
            if (listOf("Ljava/", "Ljavax/", "Landroid/", "Ldalvik/").any(match.value::startsWith)) {
                match.value
            } else "L*;"
        }
}
