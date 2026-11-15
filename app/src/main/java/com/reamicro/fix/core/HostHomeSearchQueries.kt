package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

internal object HostHomeSearchQueries {
    private fun ClassData.isA(parent: ClassData): Boolean = name == parent.name ||
        superClass?.isA(parent) == true || interfaces.any { it.isA(parent) }
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Home search $role is missing or ambiguous")
    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>, ui: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val flow = HostDexCalls(apk)
        fun known(name: String) = requireNotNull(bridge.getClassData(base.getValue("class:$name")))
        fun cls(role: String, c: ClassData): ClassData = c.also { out["class:$role"] = c.descriptor }
        fun save(role: String, m: MethodData): MethodData = m.also {
            out["${if (it.isConstructor) "constructor" else "method"}:$role"] = it.descriptor
        }
        fun method(descriptor: String) = requireNotNull(bridge.getMethodData(descriptor))
        fun field(role: String, descriptor: String) { requireNotNull(bridge.getFieldData(descriptor)); out["field:$role"] = descriptor }
        fun value(role: String, n: Int) { out["value:$role"] = n.toString() }
        val home = cls("home", known("app.zhendong.reamicro.ui.home.HomeViewModel"))
        val search = cls("search", known("ReaMicroHost.HomeSearchEvent"))
        val close = cls("close", known("ReaMicroHost.HomeSearchClose"))
        val cloud = cls("cloud", known("app.zhendong.reamicro.data.storage.CloudBook"))
        val state = cls("state", known("app.zhendong.reamicro.ui.home.HomeUiState"))
        val function = cls("function1", known("kotlin.jvm.functions.Function1"))
        val function3 = cls("function3", known("kotlin.jvm.functions.Function3"))
        val event = cls("event", (search.interfaces + listOfNotNull(search.superClass)).single { candidate ->
            home.methods.any { it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(candidate.name) && it.returnTypeName == "void" }
        })
        check(close.isA(event))
        val dispatch = save("dispatch", home.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf(event.name) && it.returnTypeName == "void" }.one("event dispatcher"))
        check(dispatch.descriptor in flow.referencingType(search.descriptor) && dispatch.descriptor in flow.referencingType(close.descriptor))
        save("entry", home.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 1 &&
            it.returnTypeName == "void" && it.invokes.any { call -> call.descriptor == dispatch.descriptor } }.one("intent entry"))
        val taps = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().name("toString").paramCount(0)
            .returnType("java.lang.String").usingEqStrings("Tap(book=")))
            .mapNotNull { it.declaredClass }.distinctBy { it.descriptor }.filter { it.isA(event) &&
                it.fields.count { f -> !Modifier.isStatic(f.modifiers) && f.typeName == cloud.name } == 1 }
        val tap = cls("tap", taps.single())
        check(dispatch.descriptor in flow.referencingType(tap.descriptor))
        save("tap", tap.methods.filter { it.isConstructor && it.paramTypeNames == listOf(cloud.name) }.one("cloud tap constructor"))
        field("tap.book", tap.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == cloud.name }.descriptor)
        val records = HostRecordQueries.query(apk, mapOf("class:state" to state.descriptor, "class:search" to search.descriptor))
        field("search.query", records.getValue("field:search#query"))
        val properties = records.filterKeys { it.startsWith("field:state#") }.mapKeys { it.key.substringAfter('#') }
        check(properties.keys.containsAll(listOf("cloudSearchResults", "localSearchResults", "searching")))
        val stateFields = state.fields.filter { !Modifier.isStatic(it.modifiers) }.map { it.descriptor }.toSet()
        check(properties.values.toSet() == stateFields) { "Home state contains an unbound property" }
        val stateCtor = save("state", state.methods.filter { it.isConstructor && it.paramCount == properties.size }.one("full state constructor"))
        val writes = flow.writes(stateCtor.descriptor)
        val slots = properties.map { (name, descriptor) ->
            field("state.$name", descriptor)
            val write = writes.single { it.field == descriptor && it.receiver == HostDexCalls.Parameter(0) }
            val slot = (write.value as HostDexCalls.Parameter).index - 1
            check(stateCtor.paramTypeNames[slot] == requireNotNull(bridge.getFieldData(descriptor)).typeName)
            value("state.$name", slot); slot
        }
        check(slots.toSet() == stateCtor.paramTypeNames.indices.toSet())
        val composer = cls("composer", known("androidx.compose.runtime.Composer"))
        val root = save("root", known("app.zhendong.reamicro.ui.home.components.HomeSearchBarKt").methods.filter {
            it.isMethod && Modifier.isStatic(it.modifiers) && it.paramCount == 6 && it.returnTypeName == "void" &&
                it.paramTypeNames.takeLast(2) == listOf(composer.name, "int") &&
                it.paramTypeNames.count { name -> name == "java.util.List" } == 1 &&
                it.paramTypeNames.count { name -> name in setOf("java.util.Map", "java.util.LinkedHashMap") } == 1 &&
                it.paramTypes.count { type -> home.isA(type) } == 1
        }.one("search results composition"))
        val receiverIndex = root.paramTypes.indices.single { home.isA(root.paramTypes[it]) }
        val receiver = cls("receiver", root.paramTypes[receiverIndex])
        value("root.receiver", receiverIndex)
        value("root.results", root.paramTypeNames.indices.single { itName -> root.paramTypeNames[itName] in setOf("java.util.Map", "java.util.LinkedHashMap") })
        value("root.local", root.paramTypeNames.indices.single { root.paramTypeNames[it] == "java.util.List" })
        val scopeIndex = (0..3).single { it !in listOf(receiverIndex, out.getValue("value:root.results").toInt(), out.getValue("value:root.local").toInt()) }
        cls("scope", root.paramTypes[scopeIndex]); value("root.scope", scopeIndex)
        val lazy = save("lazy", method(ui.getValue("method:lazyColumn")))
        check(root.invokes.any { it.descriptor == lazy.descriptor })
        value("lazy.content", lazy.paramTypeNames.indices.single { lazy.paramTypeNames[it] == function.name })
        val invoke = save("invoke", function.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "java.lang.Object" }.one("Function1 invoke"))
        val builderCtor = save("builder", root.invokes.filter { it.isConstructor && it.declaredClass?.isA(function) == true }.one("list builder constructor"))
        val builder = cls("builder", requireNotNull(builderCtor.declaredClass))
        val builderCall = flow.calls(root.descriptor).single { it.descriptor == builderCtor.descriptor }
        val builderWrites = flow.writes(builderCtor.descriptor)
        check(builder.fields.filter { !Modifier.isStatic(it.modifiers) }.size == builderCtor.paramCount)
        for (slot in builderCtor.paramTypeNames.indices) {
            val f = builderWrites.single { it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(slot + 1) }.field
            field("builder.$slot", f)
            when (val arg = builderCall.arguments[slot + 1]) {
                is HostDexCalls.Parameter -> value("builder.root.$slot", arg.index)
                is HostDexCalls.Literal -> { value("builder.tag", slot); value("builder.tagValue", arg.value.toInt()) }
                else -> error("Unproven home search capture $slot")
            }
        }
        check(out.keys.count { it.startsWith("value:builder.root.") } == 4 && "value:builder.tag" in out)
        val body = save("builder", builder.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.name == invoke.name &&
            it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "java.lang.Object" }.one("list builder invoke"))
        val item = save("item", method(ui.getValue("method:item")))
        val lazyScope = cls("lazyScope", requireNotNull(item.declaredClass))
        val lambda = cls("lambda", known("ReaMicroHost.ComposableLambda"))
        val footer = save("footer", body.invokes.filter { Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf(lazyScope.name, lambda.name) &&
            it.returnTypeName == "void" && it.invokes.any { call -> call.descriptor == item.descriptor }
        }.one("footer insertion"))
        check(body.invokes.any { it.descriptor == footer.descriptor })
        val results = save("results", method(base.getValue("method:app.zhendong.reamicro.ui.home.components.HomeSearchBarKt#CloudResultList")))
        check(Modifier.isStatic(results.modifiers) && results.paramCount == 6 && results.returnTypeName == "void" &&
            results.paramTypeNames.takeLast(2) == listOf(composer.name, "int"))
        value("results.type", results.paramTypeNames.indices.single { it < results.paramCount - 2 && results.paramTypeNames[it] == "int" })
        value("results.list", results.paramTypeNames.indices.single { results.paramTypeNames[it] == "java.util.List" })
        value("results.callback", results.paramTypeNames.indices.single { results.paramTypeNames[it] == function.name })
        val rowScope = cls("rowScope", results.paramTypes[(0..3).single { it !in listOf(out.getValue("value:results.type").toInt(),
            out.getValue("value:results.list").toInt(), out.getValue("value:results.callback").toInt()) }])
        value("results.scope", results.paramTypeNames.indices.single { results.paramTypeNames[it] == rowScope.name })
        val row = save("row", method(base.getValue("method:app.zhendong.reamicro.ui.home.components.HomeSearchBarKt#CloudBookRow")))
        check(Modifier.isStatic(row.modifiers) && row.returnTypeName == "void" &&
            row.paramTypeNames.takeLast(2) == listOf(composer.name, "int") &&
            row.paramTypeNames.count { it == cloud.name } == 1 &&
            row.paramTypeNames.count { it == function.name } == 1)
        value("row.book", row.paramTypeNames.indexOf(cloud.name))
        value("row.composer", row.paramTypeNames.indexOf(composer.name))
        check(body.invokes.any { it.descriptor == results.descriptor } || body.invokes.any { call ->
            call.isConstructor && call.declaredClass?.methods.orEmpty().any { m -> m.invokes.any { it.descriptor == results.descriptor } }
        })
        save("text", method(ui.getValue("method:text")))
        val update = save("update", method(base.getValue("method:app.zhendong.reamicro.arch.MVIViewModel#updateUiState")))
        check(receiver.isA(requireNotNull(update.declaredClass)) && update.paramTypeNames == listOf(function.name))
        return out
    }
}
