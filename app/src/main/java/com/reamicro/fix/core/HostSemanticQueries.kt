package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/** Behavioral anchors and type relationships. No host-version names or complete opcode snapshots. */
internal object HostSemanticQueries {
    data class TypeRule(val id: String, val strings: List<String>, val stable: String = "")
    data class Relation(val id: String, val owner: String, val signatures: List<String>,
        val static: Boolean, val strings: List<String>, val index: Int)
    data class MemberRule(val id: String, val owner: String, val signatures: List<String>,
        val static: Boolean, val strings: List<String>, val types: List<String>,
        val stableCalls: List<String>, val callsOnly: Boolean = false)

    val groups = setOf("runtime", "data", "reader", "library", "storage", "epub", "graphics")
    fun group(id: String): String = when {
        id.startsWith("org.epub.") -> "epub"
        id.startsWith("androidx.") || id.startsWith("kotlin.") || id.startsWith("kotlinx.") -> "runtime"
        id.contains(".ui.reader.") -> "reader"
        id.contains(".ui.storage.") || id.contains(".ui.backup.") -> "storage"
        id.contains(".data.") || id.contains(".repository.") || id.contains(".arch.Work") -> "data"
        id.contains("Svg") || id.startsWith("okio.") || id.startsWith("okhttp3.") -> "graphics"
        else -> "library"
    }
    fun query(bridge: DexKitBridge): Map<String, String> {
        val types = queryTypes(bridge)
        return types + groups.flatMap { queryGroup(bridge, types, it).entries }.associate { it.toPair() }
    }
    fun queryTypes(bridge: DexKitBridge): Map<String, String> {
        val output = linkedMapOf<String, String>()
        val types = linkedMapOf<String, String>()
        fun saveType(id: String, name: String) {
            types[id] = name
            output["class:$id"] = "L${name.replace('.', '/')};"
        }
        HostSemanticCatalog.types().forEach { rule ->
            val candidates = if (rule.stable.isNotEmpty()) listOfNotNull(bridge.getClassData(rule.stable))
            else if (rule.strings.isNotEmpty()) bridge.findClass(FindClass.create().matcher(
                ClassMatcher.create().usingEqStrings(rule.strings))) else emptyList()
            candidates.distinctBy { it.name }.singleOrNull()?.let { saveType(rule.id, it.name) }
        }
        HostFeatureTypes.query(bridge, output).forEach { (key, descriptor) ->
            saveType(key.removePrefix("class:"), descriptor.removePrefix("L").removeSuffix(";").replace('/', '.'))
        }
        val relations = HostSemanticCatalog.relations()
        repeat(8) {
            relations.filter { it.id !in types }.forEach { rule ->
                val owner = types[rule.owner] ?: return@forEach
                val candidates = bridge.getClassData(owner)?.methods.orEmpty().filter {
                    matches(it, rule.signatures, rule.static, rule.strings)
                }
                candidates.mapNotNull { if (rule.index < 0) it.returnTypeName else it.paramTypeNames.getOrNull(rule.index) }
                    .distinct().singleOrNull()?.takeIf { bridge.getClassData(it) != null }?.let { saveType(rule.id, it) }
            }
        }
        return output
    }
    fun queryGroup(bridge: DexKitBridge, base: Map<String, String>, group: String): Map<String, String> {
        require(group in groups)
        val output = linkedMapOf<String, String>()
        val types = base.filterKeys { it.startsWith("class:") }.mapKeys { it.key.removePrefix("class:") }
            .mapValues { it.value.removePrefix("L").removeSuffix(";").replace('/', '.') }.toMutableMap()
        if (group == "runtime") output.putAll(HostCoroutineQueries.query(bridge, base))
        output.putAll(HostFeatureTypes.queryMembers(bridge, base, group))
        fun saveType(id: String, name: String) {
            types[id] = name
            output["class:$id"] = "L${name.replace('.', '/')};"
        }
        val methods = HostSemanticCatalog.methods().filter { group(it.id) == group }
        val anchored = HashMap<String, List<MethodData>>()
        repeat(3) {
            methods.forEach { rule ->
                if ("method:${rule.id}" in output) return@forEach
                val owner = types[rule.owner]
                val pool = if (rule.strings.isNotEmpty()) anchored.getOrPut(rule.id) {
                    bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(rule.strings)))
                }
                else owner?.let { bridge.getClassData(it)?.methods }.orEmpty()
                val candidates = pool.filter { candidate ->
                    matches(candidate, rule.signatures, rule.static, rule.strings) &&
                        (rule.types.isEmpty() || (candidate.paramTypeNames + candidate.returnTypeName).let { actual ->
                            actual.size == rule.types.size && actual.indices.all { index ->
                                val logical = rule.types[index]
                                logical.isEmpty() || types[logical] == actual[index]
                            }
                        }) &&
                        (!rule.callsOnly || candidate.invokes.map { it.descriptor }.containsAll(rule.stableCalls))
                }.distinctBy { it.descriptor }
                // A relocated facade method needs a behavioral anchor and global uniqueness.
                val local = candidates.filter { it.declaredClassName == owner }
                (local.singleOrNull() ?: candidates.singleOrNull()?.takeIf { rule.strings.isNotEmpty() })?.let {
                    output["method:${rule.id}"] = it.descriptor
                }
            }
            // R8 merges and moves file facades. Derive their owner from their own methods,
            // never from an unrelated icon/log string that happens to share a class today.
            methods.groupBy { it.owner }.filterKeys { it.endsWith("Kt") }.forEach { (owner, rules) ->
                if (owner in types) return@forEach
                val found = rules.mapNotNull { rule -> output["method:${rule.id}"]?.let { rule to it.substringBefore("->") } }
                val role = owner.substringAfterLast('.').removeSuffix("Kt")
                val primary = found.filter { it.first.id.substringAfter('#').substringBefore('-') == role }
                val descriptor = primary.map { it.second }.distinct().singleOrNull()
                    ?: found.map { it.second }.distinct().singleOrNull()
                descriptor?.let { saveType(owner, it.removePrefix("L").removeSuffix(";").replace('/', '.')) }
            }
        }
        // Record properties are linked through uniquely identified accessor methods, never field order.
        output.toMap().filterKeys { it.startsWith("method:") }.forEach { (key, descriptor) ->
            val logical = key.removePrefix("method:")
            val name = logical.substringAfter('#')
            val property = when {
                name.startsWith("get") && name.length > 3 -> name.substring(3).replaceFirstChar(Char::lowercaseChar)
                name.startsWith("is") && name.length > 2 -> name
                else -> null
            } ?: return@forEach
            val method = bridge.getMethodData(descriptor) ?: return@forEach
            if (method.paramCount != 0 || method.returnTypeName == "void") return@forEach
            method.usingFields.map { it.field }.filter { !Modifier.isStatic(it.modifiers) && it.typeName == method.returnTypeName }
                .distinctBy { it.descriptor }.singleOrNull()?.let {
                    output["field:${logical.substringBefore('#')}#$property"] = it.descriptor
                }
        }
        return output
    }

    private fun matches(method: MethodData, signatures: List<String>, static: Boolean, strings: List<String>): Boolean =
        Modifier.isStatic(method.modifiers) == static &&
            signatures.any { HostScanRules.compatibleSignature(it, method.descriptor.substringAfter('(').let { tail -> "($tail" }) } &&
            method.usingStrings.containsAll(strings)
}
