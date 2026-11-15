package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

internal object HostReaderMenuQueries {
    private fun List<MethodData>.one(label: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Reader menu $label: missing/ambiguous ${distinctBy { it.descriptor }.map { it.descriptor }}")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>, ui: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val flow = HostDexCalls(apk)
        fun method(key: String, value: MethodData) { out["method:$key"] = value.descriptor }
        val body = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .usingEqStrings("Outlined.DarkMode", "Outlined.LightMode", "听书")))
            .filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == List(4) { "java.lang.Object" } &&
                it.returnTypeName == "java.lang.Object" }.one("render")
        method("render", body)
        out["class:menu"] = body.declaredClass!!.descriptor
        for ((key, logical) in listOf("book" to "app.zhendong.reamicro.data.db.entity.Book",
            "receiver" to "app.zhendong.reamicro.arch.MVIViewModel")) {
            val type = base.getValue("class:$logical")
            out["class:$key"] = type
            out["field:$key"] = flow.casts(body.descriptor).filter { it.type == type }
                .mapNotNull { (it.value as? HostDexCalls.Field)?.descriptor }
                .filter { it.substringBefore("->") == out.getValue("class:menu") }.distinct().single()
        }
        out["class:vm"] = base.getValue("class:app.zhendong.reamicro.ui.reader.ReaderViewModel")
        out["class:session"] = base.getValue("class:app.zhendong.reamicro.repository.core.Session")
        val clickable = requireNotNull(bridge.getMethodData(ui.getValue("method:clickable")))
        check(body.invokes.any { it.descriptor == clickable.descriptor })
        method("clickable", clickable)
        val function0 = clickable.paramTypeNames[4]
        val constructors = body.invokes.filter { it.isConstructor &&
            it.paramTypeNames.sorted() == listOf("byte", "boolean", "java.lang.Object", "java.lang.Object", "java.lang.Object").sorted() &&
            it.declaredClass?.interfaces.orEmpty().any { t -> t.name == function0 } }
        val constructor = constructors.one("theme callback constructor")
        out["constructor:theme"] = constructor.descriptor
        out["class:theme"] = constructor.declaredClass!!.descriptor
        val callback = constructor.declaredClass!!.methods.filter {
            it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "java.lang.Object"
        }.one("theme callback")
        method("theme", callback)
        out["field:session"] = flow.casts(callback.descriptor).filter { it.type == out.getValue("class:session") }
            .mapNotNull { (it.value as? HostDexCalls.Field)?.descriptor }
            .filter { it.substringBefore("->") == out.getValue("class:theme") }.distinct().single()
        val branchIndex = constructor.paramTypeNames.indexOf("byte")
        val construction = flow.calls(body.descriptor).filter { it.descriptor == constructor.descriptor }.single()
        val tag = construction.arguments[branchIndex + 1] as? HostDexCalls.Literal
            ?: error("Reader theme branch has no constant call-site proof")
        out["value:tag"] = tag.value.toString()
        out["field:tag"] = flow.writes(constructor.descriptor).filter {
            it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(branchIndex + 1)
        }.single().field
        val modifier = clickable.paramTypeNames[0]
        val composer = requireNotNull(bridge.getMethodData(ui.getValue("method:title"))).paramTypeNames[2]
        val receiver = requireNotNull(bridge.getClassData(out.getValue("class:receiver"))).name
        val book = requireNotNull(bridge.getClassData(out.getValue("class:book"))).name
        val status = requireNotNull(bridge.getFieldData(base.getValue("field:app.zhendong.reamicro.ui.reader.ReaderUiState#status"))).type
        out["class:status"] = status.descriptor
        val bottom = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().paramCount(10).returnType("void")))
            .filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames.take(3) == listOf(receiver, book, status.name) &&
                it.paramTypeNames.subList(4, 7) == List(3) { function0 } && it.paramTypeNames.takeLast(2) == listOf(composer, "int") }
            .one("bottom bar")
        method("bottomBar", bottom)
        val catalogContent = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings("章（累计阅读", "显示标记")))
            .filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(receiver, status.name, book, "int", "java.util.List",
                "java.util.Map", "java.lang.String", "java.util.List", "boolean", "java.lang.Integer", composer, "int") }
            .one("catalog content")
        method("catalog", catalogContent)
        val bottomCalls = flow.calls(bottom.descriptor)
        val contentInits = bottomCalls.filter { call ->
            bridge.getMethodData(call.descriptor)?.isConstructor == true && call.arguments.firstOrNull() is HostDexCalls.Allocation &&
                call.arguments.contains(HostDexCalls.Parameter(0)) && call.arguments.contains(HostDexCalls.Parameter(1)) &&
                call.arguments.contains(HostDexCalls.Parameter(2)) && bottomCalls.any { wrapper ->
                    wrapper.arguments.contains(call.arguments[0]) && bridge.getMethodData(wrapper.descriptor)?.let {
                        it.isMethod && Modifier.isStatic(it.modifiers) && it.paramTypeNames.takeLast(1) == listOf(composer) &&
                            it.paramCount == 3 && it.paramTypeNames.first() == "int"
                    } == true
                }
        }
        val contentInit = contentInits.single()
        val contentConstructor = requireNotNull(bridge.getMethodData(contentInit.descriptor))
        out["class:bottomContent"] = contentConstructor.declaredClass!!.descriptor
        method("bottomContent", contentConstructor.declaredClass!!.methods.filter { !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == List(3) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object" }.one("bottom bar content"))
        for ((key, argument) in listOf("receiver" to 0, "book" to 1, "status" to 2)) {
            val index = contentInit.arguments.indices.single { contentInit.arguments[it] == HostDexCalls.Parameter(argument) }
            out["field:bottom.$key"] = flow.writes(contentInit.descriptor).filter {
                it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(index)
            }.single().field
        }
        val branchWrites = flow.writes(contentInit.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) &&
            it.field.substringAfter(':') in setOf("B", "I") }
        check(branchWrites.size <= 1)
        out["value:bottomBranchCount"] = branchWrites.size.toString()
        branchWrites.singleOrNull()?.let { write ->
            val value = when (val source = write.value) {
                is HostDexCalls.Literal -> source
                is HostDexCalls.Parameter -> contentInit.arguments[source.index] as? HostDexCalls.Literal
                else -> null
            } ?: error("Reader bottom content branch is not a proven constant")
            out["field:bottomTag"] = write.field
            out["value:bottomTag"] = value.value.toString()
        }
        val icon = body.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramCount == 7 &&
            it.paramTypeNames.drop(1) == listOf("java.lang.String", modifier, "long", composer, "int", "int") &&
            it.returnTypeName == "void" }.one("vector icon")
        method("icon", icon)
        val vector = requireNotNull(icon.paramTypes[0])
        out["class:vector"] = vector.descriptor
        out["field:vectorName"] = vector.fields.filter { !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.String" }.single().descriptor
        body.callers.filter { it.isMethod }.distinctBy { it.descriptor }.forEachIndexed { i, m -> method("caller.$i", m) }
        return out
    }
}
