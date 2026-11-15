package com.reamicro.fix.core

/** 动态查询建立的逻辑符号、别名与实际成员映射。 */
object HostProfile {
    data class Parsed(
        val classes: Map<String, String>,
        val methods: Map<String, String>,
        val fields: Map<String, String>,
        val typeAliases: Map<String, String> = emptyMap(),
        val methodOwners: Map<String, String> = emptyMap(),
    ) {
        private val logicalByActual: Map<String, String> by lazy {
            buildMap {
                classes.forEach { (logical, actual) -> putIfAbsent(actual, logical) }
            }
        }
        private val aliasesByActual: Map<String, Set<String>> by lazy {
            (classes.entries + typeAliases.entries).groupBy({ it.value }, { it.key })
                .mapValues { (_, names) -> names.toSet() }
        }
        private val methodsByLogicalOwner: Map<String, List<Pair<String, String>>> by lazy {
            methods.entries.groupBy({ it.key.substringBefore('#') }, { it.key.substringAfter('#') to it.value })
        }
        private val methodsByActualOwner: Map<Pair<String, String>, List<String>> by lazy {
            methodOwners.entries.groupBy(
                { it.value to it.key.substringAfter('#') },
                { methods[it.key] },
            ).mapValues { (_, names) -> names.filterNotNull().distinct() }
        }

        /** 混淆名反查逻辑名，用于只有 Class 对象的调用点。 */
        fun logicalClassOf(actual: String): String? = logicalByActual[actual]

        fun classOf(logical: String): String? = classes[logical] ?: typeAliases[logical]

        fun methodOf(logicalClass: String, logicalName: String): String? =
            methods["$logicalClass#$logicalName"]

        fun fieldOf(logicalClass: String, logicalName: String): String? =
            fields["$logicalClass#$logicalName"]

        fun aliasesOf(actual: String): Set<String> = aliasesByActual[actual].orEmpty()

        fun relocatedMethodNames(actualOwner: String, logicalName: String): List<String> =
            methodsByActualOwner[actualOwner to logicalName].orEmpty()

        fun methodNamesOf(logicalOwner: String, logicalPrefix: String): List<String> =
            methodsByLogicalOwner[logicalOwner].orEmpty().mapNotNull { (name, actual) ->
                actual.takeIf {
                    name == logicalPrefix || name.startsWith("$logicalPrefix-") || name.startsWith("$logicalPrefix\$")
                }
            }
    }
}
