package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** Native pager effects and curl captures, including argument dataflow through R8 closures. */
internal object HostReaderPagerQueries {
    private const val P = "app.zhendong.reamicro."
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Reader pager $role is missing or ambiguous")
    private fun ClassData.isA(parent: ClassData): Boolean = name == parent.name ||
        interfaces.any { it.isA(parent) } || superClass?.isA(parent) == true

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> = buildMap {
        val flow = HostDexCalls(apk)
        fun type(role: String, logical: String) = requireNotNull(bridge.getClassData(base.getValue("class:$logical")))
            .also { put("class:$role", it.descriptor) }
        fun method(role: String, value: MethodData) = value.also { put("method:$role", it.descriptor) }
        fun known(owner: String, name: String) = requireNotNull(bridge.getMethodData(base.getValue("method:$owner#$name")))
        fun index(role: String, value: Int) { put("value:$role", value.toString()) }
        val vm = type("vm", P + "ui.reader.ReaderViewModel")
        val receiver = type("receiver", P + "arch.MVIViewModel")
        val model = type("model", P + "ui.reader.components.ReaderPagerState")
        val flows = type("flows", P + "ui.reader.components.ReaderPagerFlows")
        val actions = type("actions", P + "ui.reader.components.ReaderPagerActions")
        val deps = type("deps", P + "ui.reader.components.ReaderPagerDeps")
        val composer = type("composer", "androidx.compose.runtime.Composer")
        val pager = type("pager", "androidx.compose.foundation.pager.PagerState")
        val function0 = type("function0", "kotlin.jvm.functions.Function0")
        val function1 = type("function1", "kotlin.jvm.functions.Function1")
        val function2 = type("function2", "kotlin.jvm.functions.Function2")
        val swipeDeps = requireNotNull(bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .usingEqStrings("SwipePagerDeps(getVirtualPage="))).filter { it.paramCount == 0 && it.returnTypeName == "java.lang.String" }
            .one("swipe dependencies").declaredClass)
        put("class:swipeDeps", swipeDeps.descriptor)
        val effects = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().returnType("void").paramCount(9, 13)))
            .filter { Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames.filter { p -> p in listOf(receiver.name, model.name, flows.name, actions.name, deps.name, swipeDeps.name) } ==
                    listOf(receiver.name, model.name, flows.name, actions.name, deps.name, swipeDeps.name) &&
                it.paramTypeNames.takeLast(2) == listOf(composer.name, "int") }
        for ((role, suffix) in listOf("swipe" to emptyList(), "overlay" to listOf("int", "boolean", function1.name, function1.name, function2.name),
            "static" to listOf("int", function1.name, function1.name, function0.name))) {
            val candidates = effects.filter {
                val tail = it.paramTypeNames.drop(it.paramTypeNames.indexOf(swipeDeps.name) + 1).dropLast(2)
                if (role == "swipe") tail.isEmpty() || (tail.size == 1 && it.paramTypes[it.paramCount - 3].isA(pager))
                else tail == suffix || (role == "overlay" && tail == listOf("int", "boolean", function1.name, function2.name))
            }
            val legacy = if (role == "swipe") candidates.filter {
                it.paramCount == 9 && it.paramTypes[it.paramCount - 3].isA(pager)
            } else emptyList()
            val effect = method(role, legacy.ifEmpty { candidates }.one("$role effects"))
            index("$role.owner", effect.paramTypeNames.indices.single { effect.paramTypeNames[it] == receiver.name })
            index("$role.model", effect.paramTypeNames.indices.single { effect.paramTypeNames[it] == model.name })
            val modern = role == "swipe" && effect.paramTypeNames.indexOf(swipeDeps.name) == effect.paramCount - 3
            if (modern) {
                put("value:swipe.native", "true")
                index("swipe.current", -1)
                val render = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .paramCount(12).returnType("void"))).filter {
                    it.paramCount == 12 && it.paramTypeNames.take(9) ==
                        listOf("int", "int", "boolean", "int", function1.name,
                            function2.name, function2.name, function2.name, function0.name) &&
                        it.paramTypeNames.takeLast(2) == listOf(composer.name, "int")
                }.one("native swipe render")
                val callback = render.callers.filter { Modifier.isPublic(it.modifiers) &&
                    !Modifier.isStatic(it.modifiers) && it.paramTypeNames == List(2) { "java.lang.Object" } &&
                    it.declaredClass?.methods?.any { ctor -> ctor.isConstructor &&
                        ctor.paramTypeNames.containsAll(listOf(receiver.name, model.name, swipeDeps.name)) } == true }
                    .one("native swipe content")
                val ctor = callback.declaredClass!!.methods.filter { it.isConstructor &&
                    it.paramTypeNames.containsAll(listOf(receiver.name, model.name, swipeDeps.name)) }
                    .one("native swipe content capture")
                method("swipe.content", callback)
                method("swipe.render", render)
                for ((key, cls) in listOf("owner" to receiver, "model" to model)) {
                    val slot = ctor.paramTypeNames.indices.single { ctor.paramTypeNames[it] == cls.name }
                    put("field:swipe.content.$key", flow.writes(ctor.descriptor).single {
                        it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(slot + 1)
                    }.field)
                }
            } else index("$role.current", effect.paramTypeNames.indices.single {
                if (role == "swipe") effect.paramTypes[it].isA(pager)
                else it < effect.paramCount - 2 && effect.paramTypeNames[it] == "int"
            })
        }
        val shelf = known(P + "ui.home.BookshelfScreenKt", "BookshelfPage-NpZTi58")
        method("current", shelf.invokes.filter { !Modifier.isStatic(it.modifiers) && it.className == pager.name &&
            it.paramCount == 0 && it.returnTypeName == "int" }.one("current page"))
        val scroll = known("androidx.compose.foundation.pager.PagerState", "scroll")
        val scrollImpl = known("androidx.compose.foundation.pager.PagerState", "scroll\$suspendImpl")
        val delegate = scrollImpl.invokes.filter { !Modifier.isStatic(it.modifiers) && it.className != pager.name &&
            it.paramTypeNames == scroll.paramTypeNames && it.returnTypeName == scroll.returnTypeName }.one("scroll delegate")
        method("scrolling", pager.methods.filter { candidate -> candidate.isMethod && !Modifier.isStatic(candidate.modifiers) &&
            candidate.paramCount == 0 && candidate.returnTypeName == "boolean" && candidate.invokes.any {
                it.className == delegate.className && it.paramCount == 0 && it.returnTypeName == "boolean"
            } }.one("scroll in progress"))
        for (role in listOf("progress", "bookProgress")) put("field:model.$role", base.getValue("field:${P}ui.reader.components.ReaderPagerState#$role"))
        val getPage = known(P + "ui.reader.ReaderViewModel", "getVirtualPage")
        val page = requireNotNull(getPage.returnType); put("class:page", page.descriptor)
        put("field:vm.pages", flow.casts(getPage.descriptor).filter { it.type == page.descriptor }.mapNotNull {
            val call = it.value as? HostDexCalls.Call ?: return@mapNotNull null
            if (call.descriptor != "Ljava/util/LinkedHashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;") return@mapNotNull null
            (call.arguments.firstOrNull() as? HostDexCalls.Field)?.descriptor?.takeIf { it.startsWith(vm.descriptor + "->") }
        }.distinct().single())

        val pair = type("pair", "kotlin.Pair")
        val pairCtor = pair.methods.filter { it.isConstructor && it.paramTypeNames == List(2) { "java.lang.Object" } }.one("cache pair")
        val platform = method("curl.platform", pairCtor.callers.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
            it.paramCount == 12 && it.paramTypeNames.takeLast(2) == listOf(composer.name, "int") &&
            it.paramTypeNames.count { p -> p == function1.name } == 1 && it.paramTypeNames.count { p -> p == function2.name } == 3 &&
            it.paramTypeNames.count { p -> p == function0.name } == 1 }.one("curl platform"))
        val platformCalls = flow.calls(platform.descriptor)
        val version = platformCalls.filter { it.descriptor == pairCtor.descriptor }.flatMap { it.arguments.drop(1) }
            .filterIsInstance<HostDexCalls.Call>().filter { it.descriptor == "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;" }
            .mapNotNull { (it.arguments.singleOrNull() as? HostDexCalls.Parameter)?.index }.distinct().single()
        check(platform.paramTypeNames[version] == "int")
        index("curl.version", version)
        val composerIndex = platform.paramTypeNames.indices.single { platform.paramTypeNames[it] == composer.name }
        index("curl.flags", composerIndex + 1 + version / 10)
        check(version < composerIndex && composerIndex + 1 + version / 10 < platform.paramCount)
        val updates = platform.invokes.filter { it.isConstructor }.flatMap { ctor -> requireNotNull(ctor.declaredClass).methods.map { ctor to it } }
            .filter { (_, invoke) -> invoke.isMethod && !Modifier.isStatic(invoke.modifiers) &&
                invoke.paramTypeNames == listOf("java.lang.Object") && invoke.returnTypeName == "java.lang.Object" }
            .flatMap { (ctor, invoke) -> invoke.invokes.filter { setter -> !Modifier.isStatic(setter.modifiers) &&
                setter.paramTypeNames == listOf("int") && setter.returnTypeName == "void" && setter.invokes.any {
                    it.descriptor == "Ljava/lang/Math;->min(II)I"
                } }.map { Triple(ctor, invoke, it) } }.distinctBy { it.first.descriptor + it.second.descriptor + it.third.descriptor }
        val (updateCtor, update, setter) = updates.single()
        val currentField = flow.calls(update.descriptor).filter { it.descriptor == setter.descriptor }.mapNotNull {
            (it.arguments.getOrNull(1) as? HostDexCalls.Field)?.descriptor
        }.distinct().single()
        val currentParameter = flow.writes(updateCtor.descriptor).filter { it.field == currentField && it.receiver == HostDexCalls.Parameter(0) }
            .mapNotNull { (it.value as? HostDexCalls.Parameter)?.index }.distinct().single()
        val current = platformCalls.filter { it.descriptor == updateCtor.descriptor }.mapNotNull {
            (it.arguments.getOrNull(currentParameter) as? HostDexCalls.Parameter)?.index
        }.distinct().single()
        check(current != version && platform.paramTypeNames[current] == "int")
        index("curl.current", current)

        val contentCtor = platform.callers.flatMap { requireNotNull(it.declaredClass).methods }.filter { it.isConstructor &&
            it.paramTypeNames.count { p -> p == receiver.name } == 1 && it.paramTypeNames.count { p -> p == model.name } == 1 }.one("curl content capture")
        val contentClass = requireNotNull(contentCtor.declaredClass)
        put("class:curl.content", contentClass.descriptor); put("constructor:curl.content", contentCtor.descriptor)
        method("curl.content", platform.callers.filter { it.className == contentClass.name &&
            !Modifier.isStatic(it.modifiers) && it.paramTypeNames == List(2) { "java.lang.Object" } }.one("curl content invocation"))
        for ((role, cls) in listOf("owner" to receiver, "model" to model)) {
            val slot = contentCtor.paramTypeNames.indices.single { contentCtor.paramTypeNames[it] == cls.name }
            index("curl.content.$role", slot)
            put("field:curl.content.$role", flow.writes(contentCtor.descriptor).single {
                it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(slot + 1)
            }.field)
        }
        val rootCtors = contentCtor.callers.flatMap { requireNotNull(it.declaredClass).methods }.filter { it.isConstructor &&
            it.paramTypeNames.containsAll(listOf(receiver.name, model.name)) }.distinctBy { it.descriptor }
        val root = method("curl.root", rootCtors.flatMap { it.callers }.filter { Modifier.isStatic(it.modifiers) &&
            it.returnTypeName == "void" && it.paramTypeNames.containsAll(listOf(receiver.name, model.name)) &&
            it.paramTypeNames.takeLast(2) == listOf(composer.name, "int") }.one("curl root"))
        for ((role, cls) in listOf("owner" to receiver, "model" to model))
            index("curl.root.$role", root.paramTypeNames.indices.single { root.paramTypeNames[it] == cls.name })
        val restart = method("curl.restart", platform.callers.filter { it.className != contentClass.name && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == List(2) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object" }.one("curl restart"))
        put("class:curl.restart", requireNotNull(restart.declaredClass).descriptor)
        val restartCtor = platform.invokes.filter { it.isConstructor && it.className == restart.className }.one("curl restart capture")
        put("constructor:curl.restart", restartCtor.descriptor)
        val restartCall = platformCalls.filter { it.descriptor == restartCtor.descriptor }.distinct().single()
        index("curl.restart.version", restartCall.arguments.indices.filter { it > 0 }.single {
            restartCall.arguments[it] == HostDexCalls.Parameter(version)
        } - 1)
    }
}
