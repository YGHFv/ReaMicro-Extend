package com.reamicro.fix.core

/** 保留已验证的 ABI 类身份；相同类型身份的成员使用当前 DEX 的扫描结果。 */
object HostScanOverlay {
    fun merge(verified: HostProfile.Parsed, scanned: HostProfile.Parsed): HostProfile.Parsed {
        val names = verified.classes.keys + verified.typeAliases.keys
        val actuals = verified.classes.values.toSet()
        val extraClasses = scanned.classes.filter { (logical, actual) -> logical !in names && actual !in actuals }
        val extraAliases = scanned.typeAliases.filterKeys { it !in names }
        fun compatibleOwner(key: String): Boolean {
            val logical = key.substringBefore('#')
            val expected = verified.classOf(logical)
            val actual = scanned.classOf(logical)
            return actual != null && (expected == actual ||
                (expected == null && (logical in extraClasses || logical in extraAliases)))
        }
        return verified.copy(
            classes = verified.classes + extraClasses,
            typeAliases = verified.typeAliases + extraAliases,
            methods = verified.methods + scanned.methods.filterKeys(::compatibleOwner),
            fields = verified.fields + scanned.fields.filterKeys(::compatibleOwner),
            methodOwners = verified.methodOwners + scanned.methodOwners.filterKeys(::compatibleOwner),
        )
    }
}
