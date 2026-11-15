package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

internal object HostReaderWindowQueries {
    private const val P = "app.zhendong.reamicro."
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Reader window $role is missing or ambiguous")
    private fun ClassData.isA(parent: ClassData): Boolean = name == parent.name || interfaces.any { it.isA(parent) } || superClass?.isA(parent) == true
    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> = buildMap {
        val flow = HostDexCalls(apk)
        fun type(role: String, logical: String) = requireNotNull(bridge.getClassData(base.getValue("class:$logical")))
            .also { put("class:$role", it.descriptor) }
        fun method(role: String, value: MethodData) = value.also { put("method:$role", it.descriptor) }
        fun known(role: String, owner: String, name: String) = method(role, requireNotNull(bridge.getMethodData(base.getValue("method:$owner#$name"))))
        fun field(value: HostDexCalls.Value?) = (value as? HostDexCalls.Field)?.descriptor
        val vm = type("vm", P + "ui.reader.ReaderViewModel")
        val epub = type("epub", P + "data.epub.Epub")
        val page = type("page", P + "data.epub.EpubPage")
        val createCfi = requireNotNull(bridge.getMethodData(base.getValue("method:org.epub.html.EpubCFI\$Companion#create")))
        val cfi = requireNotNull(createCfi.returnType)
        put("class:cfi", cfi.descriptor)
        val pair = type("pair", "kotlin.Pair")
        val continuation = type("continuation", "kotlin.coroutines.Continuation")
        val recenter = known("recenter", P + "ui.reader.ReaderViewModel", "recenterVirtualWindow")
        check(recenter.paramTypeNames.take(3) == listOf("int", epub.name, cfi.name) && recenter.paramTypes.last().isA(continuation))
        val getPage = known("getPage", P + "ui.reader.ReaderViewModel", "getVirtualPage")
        val pages = flow.casts(getPage.descriptor).filter { it.type == page.descriptor }.mapNotNull {
            val call = it.value as? HostDexCalls.Call ?: return@mapNotNull null
            if (call.descriptor != "Ljava/util/LinkedHashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;") return@mapNotNull null
            field(call.arguments.firstOrNull())?.takeIf { it.startsWith(vm.descriptor + "->") }
        }.distinct().single()
        put("field:vm.pages", pages)
        val forward = getPage.usingFields.map { it.field }.filter { it.declaredClassName == vm.name &&
            it.typeName == "java.util.LinkedHashMap" && it.descriptor != pages }.distinctBy { it.descriptor }.single()
        put("field:vm.forward", forward.descriptor)
        val mapPages = vm.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("int", "int", "java.util.List") && it.returnTypeName == "void" &&
            it.usingFields.any { f -> f.field.descriptor == pages } && it.usingFields.any { f -> f.field.descriptor == forward.descriptor } }.one("page registration")
        val reverse = flow.calls(mapPages.descriptor).filter { it.descriptor == "Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" &&
            (it.arguments.getOrNull(1) as? HostDexCalls.Allocation)?.type == pair.descriptor }.mapNotNull { field(it.arguments.firstOrNull()) }.distinct().single()
        put("field:vm.reverse", reverse)
        val constructors = recenter.invokes.filter { it.isConstructor && it.paramTypeNames.contains(vm.name) &&
            it.paramTypeNames.count { p -> p in setOf("java.util.Map", "java.util.LinkedHashMap") } >= 3 }.distinctBy { it.descriptor }
        val publisher = method("publish", constructors.flatMap { requireNotNull(it.declaredClass).methods }.filter { it.isMethod &&
            !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "java.lang.Object" &&
            it.invokes.any { m -> m.descriptor == "Ljava/util/Map;->putAll(Ljava/util/Map;)V" } }.one("window publication"))
        val publisherClass = requireNotNull(publisher.declaredClass)
        put("class:publisher", publisherClass.descriptor)
        val ctor = constructors.filter { it.className == publisher.className }.one("publication capture")
        put("constructor:publisher", ctor.descriptor)
        val ownerSlot = ctor.paramTypeNames.indices.single { ctor.paramTypeNames[it] == vm.name }
        put("value:publish.owner", ownerSlot.toString())
        put("field:publish.owner", flow.writes(ctor.descriptor).single { it.receiver == HostDexCalls.Parameter(0) &&
            it.value == HostDexCalls.Parameter(ownerSlot + 1) }.field)
        val publishCalls = flow.calls(publisher.descriptor)
        for ((role, destination) in listOf("pages" to pages, "forward" to forward.descriptor, "reverse" to reverse)) {
            put("field:publish.$role", publishCalls.filter { it.descriptor == "Ljava/util/Map;->putAll(Ljava/util/Map;)V" &&
                field(it.arguments.firstOrNull()) == destination }.mapNotNull { field(it.arguments.getOrNull(1)) }.distinct().single())
        }
        val loaded = publishCalls.filter { it.descriptor == "Ljava/util/Set;->addAll(Ljava/util/Collection;)Z" }
            .mapNotNull { field(it.arguments.firstOrNull()) }.distinct().single()
        put("field:vm.loaded", loaded)
        fun bound(value: HostDexCalls.Value): String? {
            val unbox = value as? HostDexCalls.Call ?: return null
            if (unbox.descriptor != "Ljava/lang/Integer;->intValue()I") return null
            val read = unbox.arguments.singleOrNull() as? HostDexCalls.Call ?: return null
            val method = bridge.getMethodData(read.descriptor) ?: return null
            if (Modifier.isStatic(method.modifiers) || method.paramCount != 0 || method.returnTypeName != "java.lang.Object") return null
            return field(read.arguments.singleOrNull())?.takeIf { it.startsWith(vm.descriptor + "->") }
        }
        val comparisons = flow.comparisons(getPage.descriptor)
        for ((role, op) in listOf("minimum" to "IF_GE", "maximum" to "IF_LE")) {
            val state = comparisons.filter { it.operation == op && it.left == HostDexCalls.Parameter(1) }.mapNotNull { bound(it.right) }.distinct().single()
            put("field:vm.$role", state)
            put("field:publish.$role", publishCalls.filter { field(it.arguments.firstOrNull()) == state &&
                bridge.getMethodData(it.descriptor)?.let { m -> !Modifier.isStatic(m.modifiers) &&
                    m.paramTypeNames == listOf("java.lang.Object") && m.returnTypeName == "void" } == true }
                .mapNotNull { field(it.arguments.getOrNull(1)) }.distinct().single())
        }
        val captures = flow.writes(ctor.descriptor)
        for (role in listOf("forward", "reverse", "pages", "minimum", "maximum")) {
            val source = getValue("field:publish.$role")
            put("value:publish.$role", (captures.filter { it.field == source && it.receiver == HostDexCalls.Parameter(0) }
                .mapNotNull { (it.value as? HostDexCalls.Parameter)?.index }.distinct().single() - 1).toString())
        }
        val pairCtor = pair.methods.filter { it.isConstructor && it.paramTypeNames == List(2) { "java.lang.Object" } }.one("pair capture")
        for ((role, slot) in listOf("first" to 1, "second" to 2)) put("field:pair.$role", flow.writes(pairCtor.descriptor).single {
            it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(slot)
        }.field)
        put("field:page.start", base.getValue("field:${P}data.epub.EpubPage#start"))
        val range = requireNotNull(bridge.getMethodData(base.getValue("method:org.epub.html.EpubCFIRange#toString")))
            .declaredClass ?: error("CFI range owner missing")
        put("class:range", range.descriptor)
        put("field:page.range", page.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == range.name }.descriptor)
        method("contains", range.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramCount == 1 &&
            it.returnTypeName == "boolean" && it.paramTypeNames.single() in setOf(cfi.name, "java.lang.Comparable") }.one("CFI containment"))
        val mutex = requireNotNull(bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings("Mutex@", "[isLocked=", ",owner=")))
            .filter { it.paramCount == 0 && it.returnTypeName == "java.lang.String" }.one("mutex").declaredClass)
        put("class:mutex", mutex.descriptor)
        put("field:vm.mutex", vm.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == mutex.name }.descriptor)
        val lock = mutex.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) && it.returnTypeName == "java.lang.Object" &&
            it.paramTypes.lastOrNull()?.isA(continuation) == true }.one("mutex acquire")
        method("tryLock", lock.invokes.filter { it.className == mutex.name && !Modifier.isStatic(it.modifiers) &&
            it.returnTypeName == "boolean" && it.paramTypeNames in listOf(emptyList(), listOf("java.lang.Object")) }.one("mutex try acquire"))
        method("unlock", mutex.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "void" &&
            "This mutex is not locked" in it.usingStrings }.one("mutex release"))
        val pagerFlows = type("pagerFlows", P + "ui.reader.components.ReaderPagerFlows")
        val correction = requireNotNull(bridge.getFieldData(base.getValue("field:${P}ui.reader.components.ReaderPagerFlows#pagerCorrection")))
        val flowsCtor = pagerFlows.methods.filter { it.isConstructor && it.paramTypeNames.count { p -> p == correction.typeName } == 1 }.one("pager flows capture")
        val correctionSlot = flow.writes(flowsCtor.descriptor).filter { it.field == correction.descriptor }.mapNotNull {
            (it.value as? HostDexCalls.Parameter)?.index
        }.distinct().single()
        val correctionField = flowsCtor.callers.flatMap { flow.calls(it.descriptor) }.filter { it.descriptor == flowsCtor.descriptor }
            .mapNotNull { field(it.arguments.getOrNull(correctionSlot)) }.filter { it.startsWith(vm.descriptor + "->") }.distinct().single()
        put("field:vm.correction", correctionField)
        val wrapper = requireNotNull(bridge.getFieldData(correctionField)).type
        put("class:correction", wrapper.descriptor)
        val shared = type("shared", "kotlinx.coroutines.flow.SharedFlowImpl")
        val wrapperCtor = wrapper.methods.filter { it.isConstructor && it.paramTypeNames.count { p -> p == shared.name } == 1 }.one("correction source capture")
        val sourceSlot = wrapperCtor.paramTypeNames.indexOf(shared.name) + 1
        put("field:correction.source", flow.writes(wrapperCtor.descriptor).single {
            it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(sourceSlot)
        }.field)
        method("emitCorrection", shared.methods.filter { candidate -> candidate.isMethod && !Modifier.isStatic(candidate.modifiers) &&
            candidate.paramTypeNames == listOf("java.lang.Object") && candidate.returnTypeName == "boolean" && candidate.invokes.any {
                it.className == continuation.name && it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "void"
            } }.one("pager correction emission"))
    }
}
