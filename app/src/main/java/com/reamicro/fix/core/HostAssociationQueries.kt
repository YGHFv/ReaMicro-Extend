package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

internal object HostAssociationQueries {
    private const val P = "app.zhendong.reamicro."
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Association $role is missing or ambiguous")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> = buildMap {
        fun type(role: String, logical: String): ClassData = requireNotNull(bridge.getClassData(base.getValue("class:$logical")))
            .also { put("class:$role", it.descriptor) }
        fun known(owner: String, name: String) = requireNotNull(bridge.getMethodData(base.getValue("method:$owner#$name")))
        val vm = type("vm", P + "ui.book.BookPublishViewModel")
        val state = type("state", P + "ui.book.BookPublishUIState")
        val book = type("book", P + "data.db.entity.Book")
        val candidate = type("candidate", P + "data.search.ThirdPartyBook")
        val group = type("group", P + "data.search.ThirdParty")
        val repository = type("repository", P + "repository.BookRepository")
        val shelf = type("shelf", P + "repository.BookshelfRepository")
        val flow = HostDexCalls(apk)
        fun dependency(role: String, owner: ClassData, target: ClassData) {
            val field = owner.fields.single { !Modifier.isStatic(it.modifiers) &&
                Modifier.isFinal(it.modifiers) && it.type.descriptor == target.descriptor }
            val constructors = owner.methods.filter { it.isConstructor &&
                it.paramTypes.any { parameter -> parameter.descriptor == target.descriptor } }
            check(constructors.isNotEmpty() && constructors.all { constructor ->
                val slot = constructor.paramTypes.indices.single {
                    constructor.paramTypes[it].descriptor == target.descriptor
                }
                flow.writes(constructor.descriptor).any { it.receiver == HostDexCalls.Parameter(0) &&
                    it.field == field.descriptor && it.value == HostDexCalls.Parameter(slot + 1) }
            }) { "Association $role is not the native constructor dependency" }
            put("field:$role", field.descriptor)
        }
        dependency("vm.repository", vm, repository)
        dependency("repository.shelf", repository, shelf)
        val current = known(P + "arch.MVIViewModel", "getCurrentState")
        val update = known(P + "arch.MVIViewModel", "updateUiState")
        check(update.paramCount == 1 && update.paramTypeNames.single() == "kotlin.jvm.functions.Function1")
        put("class:reducer", update.paramTypes.single().descriptor)
        val flowField = requireNotNull(bridge.getFieldData(base.getValue("field:${P}arch.MVIViewModel#_uiState")))
        val flowType = requireNotNull(bridge.getClassData(flowField.typeName))
        put("class:flow", flowType.descriptor); put("field:vm.flow", flowField.descriptor)
        fun ClassData.isA(parent: ClassData): Boolean = descriptor == parent.descriptor ||
            interfaces.any { it.isA(parent) } || superClass?.isA(parent) == true
        val get = current.invokes.filter { !Modifier.isStatic(it.modifiers) && it.declaredClass?.let(flowType::isA) == true &&
            it.paramCount == 0 && it.returnTypeName == "java.lang.Object" }.one("state value")
        val cas = update.invokes.filter { !Modifier.isStatic(it.modifiers) && it.declaredClass?.let(flowType::isA) == true &&
            it.paramTypeNames == List(2) { "java.lang.Object" } && it.returnTypeName == "boolean" }.one("state compare and set")
        val set = flowType.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "void" }.one("state set")
        val baseCleared = known("androidx.lifecycle.ViewModel", "onCleared")
        // Virtual override names are related by the DEX hierarchy, not by a version table.
        val cleared = vm.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.methodName == baseCleared.methodName && it.paramCount == 0 && it.returnTypeName == "void" }.one("VM cleanup override")
        for ((role, method) in listOf("current" to current, "update" to update, "get" to get, "cas" to cas, "set" to set, "cleared" to cleared))
            put("method:$role", method.descriptor)
        val constructors = vm.methods.filter { it.isConstructor }.distinctBy { it.descriptor }
        check(constructors.isNotEmpty())
        constructors.forEachIndexed { index, method -> put("constructor:vm.$index", method.descriptor) }
        val records = mapOf("state" to state, "candidate" to candidate, "group" to group, "book" to book)
        val fields = HostRecordQueries.query(apk, records.mapKeys { "class:${it.key}" }.mapValues { it.value.descriptor })
            .filterKeys { it.startsWith("field:") }
        putAll(fields)
        for ((role, cls) in records.filterKeys { it != "book" }) {
            val owned = cls.fields.filter { !Modifier.isStatic(it.modifiers) }.map { it.descriptor }.toSet()
            check(fields.filterKeys { it.startsWith("field:$role#") }.values.toSet() == owned) { "Incomplete $role record" }
            // A serialization constructor can retain an extra presence mask. Fix that mask
            // to all present bits, then prove that each remaining argument writes one field.
            check(owned.size in 1..30)
            val mask = (1L shl owned.size) - 1L
            data class Layout(val method: MethodData, val maskSlot: Int?, val order: List<String?>)
            val layouts = cls.methods.filter { it.isConstructor }.flatMap { constructor ->
                val slots: List<Int?> = when (constructor.paramCount) {
                    owned.size -> listOf(null)
                    owned.size + 1 -> constructor.paramTypeNames.indices.filter { constructor.paramTypeNames[it] == "int" }
                    else -> emptyList()
                }
                slots.mapNotNull { maskSlot ->
                    val writes = if (maskSlot == null) flow.writes(constructor.descriptor)
                        else flow.writes(constructor.descriptor, mapOf(maskSlot + 1 to mask))
                    val direct = writes.filter { it.receiver == HostDexCalls.Parameter(0) && it.field in owned }
                    val order = constructor.paramTypeNames.indices.map { index ->
                        if (index == maskSlot) null else direct.filter { it.value == HostDexCalls.Parameter(index + 1) }
                            .map { it.field }.distinct().singleOrNull()
                    }
                    if (order.filterNotNull().toSet() != owned || order.count { it == null } != (if (maskSlot == null) 0 else 1)) return@mapNotNull null
                    if (direct.any { it.value !is HostDexCalls.Parameter }) return@mapNotNull null
                    Layout(constructor, maskSlot, order)
                }
            }
            val layout = (layouts.filter { it.maskSlot == null }.ifEmpty { layouts }).singleOrNull()
                ?: error("Association $role constructor does not preserve all properties")
            put("constructor:$role", layout.method.descriptor)
            layout.order.forEachIndexed { index, field ->
                if (field == null) put("value:$role.argument.$index", mask.toString())
                else put("field:$role.argument.$index", field)
            }
        }
    }
}
