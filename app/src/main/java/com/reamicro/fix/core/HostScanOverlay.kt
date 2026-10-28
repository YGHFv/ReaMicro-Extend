package com.reamicro.fix.core

/** 已验证 profile 的类身份、别名和 ABI 优先，扫描不能改变既有反向类型身份。 */
object HostScanOverlay {
    fun merge(verified: HostProfile.Parsed, scanned: HostProfile.Parsed): HostProfile.Parsed {
        val names = verified.classes.keys + verified.typeAliases.keys
        val actuals = verified.classes.values.toSet()
        val extraClasses = scanned.classes.filter { (logical, actual) -> logical !in names && actual !in actuals }
        val extraAliases = scanned.typeAliases.filterKeys { it !in names }
        return verified.copy(
            classes = verified.classes + extraClasses,
            typeAliases = verified.typeAliases + extraAliases,
            methods = scanned.methods + verified.methods,
            fields = scanned.fields + verified.fields,
            methodOwners = scanned.methodOwners + verified.methodOwners,
        )
    }
}
