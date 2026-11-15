package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/** Selection records and closures are related by their actual calls and constructor writes. */
internal object HostSelectionQueries {
    private const val P = "org.epub.ui."
    private fun List<MethodData>.one(label: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Selection $label: missing/ambiguous ${distinctBy { it.descriptor }.map { it.descriptor }}")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>, ui: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val flow = HostDexCalls(apk)
        fun method(key: String, value: MethodData) { out["method:$key"] = value.descriptor }
        fun type(key: String, logical: String) = requireNotNull(bridge.getClassData(base.getValue("class:$logical"))).also {
            out["class:$key"] = it.descriptor
        }
        val begin = requireNotNull(bridge.getMethodData(base.getValue("method:${P}EpubSelectionController#beginSelection-k-4lQ0M")))
        val controller = requireNotNull(begin.declaredClass)
        out["class:controller"] = controller.descriptor
        method("begin", begin)
        out["constructor:controller"] = controller.methods.filter { it.isConstructor && it.paramCount == 0 }.one("controller constructor").descriptor
        val action = type("action", "${P}SelectionMenuAction")
        val snapshot = type("snapshot", "${P}SelectionSnapshot")
        val payloadType = type("payload", "${P}EpubSelectionPayload")
        for ((role, logical, properties) in listOf(
            Triple("action", "${P}SelectionMenuAction", listOf("title", "icon", "onClick")),
            Triple("payload", "${P}EpubSelectionPayload", listOf("startCfi", "endCfi", "quote")),
            Triple("snapshot", "${P}SelectionSnapshot", listOf("anchor", "focus", "roleId")),
        )) for (property in properties) out["field:$role.$property"] = base.getValue("field:$logical#$property")
        val actionFields = listOf("title", "icon", "onClick").map { out.getValue("field:action.$it") }
        val actionConstructor = action.methods.filter { constructor ->
            constructor.isConstructor && constructor.paramCount == actionFields.size && actionFields.all { field ->
                val write = flow.writes(constructor.descriptor).singleOrNull { it.field == field && it.receiver == HostDexCalls.Parameter(0) }
                (write?.value as? HostDexCalls.Parameter)?.index?.let { it in 1..constructor.paramCount } == true
            }
        }.one("action constructor")
        out["constructor:action"] = actionConstructor.descriptor
        for (property in listOf("title", "icon", "onClick")) {
            val parameter = flow.writes(actionConstructor.descriptor).single {
                it.field == out.getValue("field:action.$property") && it.receiver == HostDexCalls.Parameter(0)
            }.value as HostDexCalls.Parameter
            out["value:action.$property"] = (parameter.index - 1).toString()
        }
        val selection = controller.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
            it.returnTypeName == snapshot.name }.one("snapshot getter")
        method("selection", selection)
        val payload = controller.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
            it.returnTypeName == payloadType.name }.one("selected payload")
        method("payload", payload)
        method("text", payload.invokes.filter { it.declaredClass?.descriptor == controller.descriptor &&
            !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "java.lang.String" }.one("selected text"))
        val setSelection = controller.methods.filter { !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf(snapshot.name) && it.returnTypeName == "void" }.one("snapshot setter")
        val clear = controller.methods.filter { candidate ->
            candidate.isMethod && !Modifier.isStatic(candidate.modifiers) && candidate.paramCount == 0 && candidate.returnTypeName == "void" &&
                flow.calls(candidate.descriptor).any { it.descriptor == setSelection.descriptor &&
                    it.arguments == listOf(HostDexCalls.Parameter(0), HostDexCalls.Literal(0)) } &&
                flow.calls(candidate.descriptor).any { call -> call.arguments == listOf(HostDexCalls.Parameter(0), HostDexCalls.Literal(0)) &&
                    candidate.invokes.any { it.descriptor == call.descriptor && it.declaredClass?.descriptor == controller.descriptor &&
                        it.paramTypeNames == listOf("boolean") && it.returnTypeName == "void" } }
        }.one("clear selection and deactivate")
        method("clear", clear)

        val body = requireNotNull(bridge.getClassData(base.getValue("class:${P}BodyKt")))
        val modifier = requireNotNull(bridge.getMethodData(ui.getValue("method:clickable"))).paramTypeNames[0]
        val composer = requireNotNull(bridge.getMethodData(ui.getValue("method:title"))).paramTypeNames[2]
        val vector = requireNotNull(bridge.getFieldData(out.getValue("field:action.icon"))).type
        val function0 = requireNotNull(bridge.getFieldData(out.getValue("field:action.onClick"))).type
        out["class:vector"] = vector.descriptor
        out["class:function0"] = function0.descriptor
        val bubble = body.methods.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
            it.paramTypeNames == listOf("java.util.List", "boolean", "int", "boolean", modifier, composer, "int") }.one("bubble")
        method("bubble", bubble)
        val item = body.methods.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
            it.paramTypeNames == listOf("java.lang.String", vector.name, "long", function0.name, modifier, composer, "int") }.one("item")
        method("item", item)
        method("icon", item.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
            it.paramTypeNames == listOf(vector.name, "java.lang.String", modifier, "long", composer, "int", "int") }.one("vector icon renderer"))
        method("editIcon", bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings("Outlined.Edit")))
            .filter { Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == vector.name }.one("edit icon"))
        val constructors = bubble.invokes.filter { constructor -> constructor.isConstructor &&
            constructor.declaredClass!!.methods.any { m -> m.paramTypeNames == List(2) { "java.lang.Object" } &&
                m.returnTypeName == "java.lang.Object" && m.invokes.any { it.descriptor == item.descriptor } } }.distinctBy { it.descriptor }
        check(constructors.size == 2) { "Expected inline and floating selection content" }
        val roles = HashSet<String>()
        for (constructor in constructors) {
            val construction = flow.calls(bubble.descriptor).filter { it.descriptor == constructor.descriptor }.single()
            val writes = flow.writes(constructor.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) }
            fun captured(source: HostDexCalls.Value) = writes.filter { write ->
                (write.value as? HostDexCalls.Parameter)?.let { construction.arguments[it.index] == source } == true
            }.single().field
            val floating = construction.arguments.contains(HostDexCalls.Parameter(1))
            val role = if (floating) "floating" else "inline"
            check(roles.add(role)) { "Duplicate selection content role $role" }
            val content = constructor.declaredClass!!.methods.filter { !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == List(2) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object" &&
                it.invokes.any { invoked -> invoked.descriptor == item.descriptor } }.one("$role content")
            method("$role.content", content)
            out["constructor:$role.content"] = constructor.descriptor
            out["field:$role.actions"] = captured(HostDexCalls.Parameter(0))
            val itemCall = flow.calls(content.descriptor).filter { it.descriptor == item.descriptor }.single()
            out["field:$role.tint"] = (itemCall.arguments[2] as? HostDexCalls.Field)?.descriptor
                ?: error("Selection $role tint has no field provenance")
            check(writes.any { it.field == out.getValue("field:$role.tint") && it.value is HostDexCalls.Parameter })
            if (floating) out["field:$role.arrow"] = captured(HostDexCalls.Parameter(1))
            val branchWrites = writes.filter { it.field.substringAfter(':') in setOf("B", "I") }
            check(branchWrites.size <= 1)
            out["value:$role.branchCount"] = branchWrites.size.toString()
            branchWrites.singleOrNull()?.let { write ->
                val value = when (val source = write.value) {
                    is HostDexCalls.Literal -> source
                    is HostDexCalls.Parameter -> construction.arguments[source.index] as? HostDexCalls.Literal
                    else -> null
                } ?: error("Selection $role branch lacks constant call-site proof")
                out["field:$role.tag"] = write.field
                out["value:$role.tag"] = value.value.toString()
            }
        }
        return out
    }
}
