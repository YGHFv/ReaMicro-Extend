package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.result.ClassData

/** Recover mutable state through the values consumed by native reader rendering. */
internal object HostReaderStateQueries {
    private const val P = "app.zhendong.reamicro."
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Reader state $role is missing or ambiguous")
    private fun ClassData.isA(parent: ClassData): Boolean = descriptor == parent.descriptor ||
        interfaces.any { it.isA(parent) } || superClass?.isA(parent) == true

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> = buildMap {
        val flow = HostDexCalls(apk)
        val vm = requireNotNull(bridge.getClassData(base.getValue("class:${P}ui.reader.ReaderViewModel")))
        val searchIcon = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings("Outlined.Search")))
            .filter { it.isMethod && Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName != "void" }
            .one("native search icon")
        put("class:vector", requireNotNull(searchIcon.returnType).descriptor)
        put("method:searchIcon", searchIcon.descriptor)
        val sources = LinkedHashMap<String, String>()
        fun fields(value: HostDexCalls.Value): List<String> = when (value) {
            is HostDexCalls.Field -> listOf(value.descriptor)
            is HostDexCalls.Call -> value.arguments.flatMap(::fields)
            else -> emptyList()
        }
        for ((role, logical, property) in listOf(
            Triple("marks", P + "ui.reader.components.ReaderSharedState", "marks"),
            Triple("initial", P + "ui.reader.components.ReaderPagerState", "initialPage"),
        )) {
            val owner = requireNotNull(bridge.getClassData(base.getValue("class:$logical")))
            val destination = base.getValue("field:$logical#$property")
            val constructor = owner.methods.filter { it.isConstructor && flow.writes(it.descriptor).any { write ->
                write.receiver == HostDexCalls.Parameter(0) && write.field == destination && write.value is HostDexCalls.Parameter
            } }.one("$role model constructor")
            val slot = (flow.writes(constructor.descriptor).single { it.field == destination &&
                it.receiver == HostDexCalls.Parameter(0) }.value as HostDexCalls.Parameter).index
            val sourcesForRole = constructor.callers.flatMap { caller -> flow.calls(caller.descriptor) }
                .filter { it.descriptor == constructor.descriptor }
                .flatMap { call -> call.arguments.getOrNull(slot)?.let(::fields).orEmpty() }
                .filter { it.startsWith(vm.descriptor + "->") }.distinct()
            sources[role] = sourcesForRole.single()
            put("field:vm.$role", sourcesForRole.single())
        }
        val wrappers = sources.values.map { requireNotNull(bridge.getFieldData(it)).type }.distinctBy { it.descriptor }
        val wrapper = wrappers.single()
        put("class:flowView", wrapper.descriptor)
        val readView = wrapper.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramCount == 0 && it.returnTypeName == "java.lang.Object" }.one("state view read")
        val viewGetter = readView.invokes.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramCount == 0 && it.returnTypeName == "java.lang.Object" }.one("mutable state read")
        val viewType = requireNotNull(viewGetter.declaredClass)
        val mutable = requireNotNull(bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .usingEqStrings("MutableStateFlow.resetReplayCache is not supported")))
            .filter { it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "void" }
            .one("mutable state implementation").declaredClass)
        check(mutable.isA(viewType) && vm.fields.any { it.typeName == mutable.name })
        val getter = mutable.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.methodName == viewGetter.methodName && it.paramCount == 0 && it.returnTypeName == "java.lang.Object" }
            .one("mutable state read implementation")
        put("class:flow", mutable.descriptor); put("method:flow.get", getter.descriptor)
        put("field:flowView.source", wrapper.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == viewType.name }.descriptor)
        val setter = mutable.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "void" }.one("mutable state write")
        val update = setter.invokes.filter { it.className == mutable.name && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == List(2) { "java.lang.Object" } && it.returnTypeName == "boolean" }.one("state update implementation")
        val compare = mutable.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == List(2) { "java.lang.Object" } && it.returnTypeName == "boolean" &&
            it.descriptor != update.descriptor && it.invokes.any { call -> call.descriptor == update.descriptor }
        }.one("state compare and set")
        put("method:flow.set", setter.descriptor); put("method:flow.cas", compare.descriptor)
        val updateString = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings("UpdateWindowKey(key=")))
            .filter { it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "java.lang.String" }
            .one("window key intent")
        val intent = requireNotNull(updateString.declaredClass)
        put("class:intent.UpdateWindowKey", intent.descriptor)
        val key = intent.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.String" }
        put("field:UpdateWindowKey.key", key.descriptor)
        put("constructor:UpdateWindowKey", intent.methods.filter { it.isConstructor && it.paramTypeNames == listOf("java.lang.String") }
            .one("window key capture").descriptor)
        val dispatch = requireNotNull(bridge.getMethodData(base.getValue("method:${P}ui.reader.ReaderViewModel#onIntent")))
        fun isKey(value: HostDexCalls.Value): Boolean {
            if (value == HostDexCalls.Field(key.descriptor)) return true
            val call = value as? HostDexCalls.Call ?: return false
            val method = bridge.getMethodData(call.descriptor) ?: return false
            return method.className == intent.name && method.paramCount == 0 && method.returnTypeName == "java.lang.String" &&
                method.usingFields.any { it.field.descriptor == key.descriptor }
        }
        val window = flow.calls(dispatch.descriptor).filter { call ->
            call.descriptor in setOf(setter.descriptor, update.descriptor, compare.descriptor) && call.arguments.drop(1).any(::isKey)
        }.mapNotNull { (it.arguments.firstOrNull() as? HostDexCalls.Field)?.descriptor }
            .filter { it.startsWith(vm.descriptor + "->") }.distinct().single()
        put("field:vm.windowKey", window)
        check(requireNotNull(bridge.getFieldData(window)).typeName == mutable.name)
    }
}
