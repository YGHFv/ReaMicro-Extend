package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

internal object HostReaderScrollQueries {
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Reader scroll $role is missing or ambiguous")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> = buildMap {
        val flow = HostDexCalls(apk)
        fun type(role: String, logical: String) = requireNotNull(bridge.getClassData(base.getValue("class:$logical")))
            .also { put("class:$role", it.descriptor) }
        fun method(role: String, value: MethodData) = value.also { put("method:$role", it.descriptor) }
        fun known(role: String, owner: String, name: String) = method(role, requireNotNull(bridge.getMethodData(base.getValue("method:$owner#$name"))))
        val list = type("list", "androidx.compose.foundation.lazy.LazyListState")
        val paging = type("paging", "androidx.paging.compose.LazyPagingItems")
        val vm = type("vm", "app.zhendong.reamicro.ui.reader.ReaderViewModel")
        val receiver = type("receiver", "app.zhendong.reamicro.arch.MVIViewModel")
        val continuation = type("continuation", "kotlin.coroutines.Continuation")
        val function2 = type("function2", "kotlin.jvm.functions.Function2")
        val element = type("element", "org.epub.element.model.EpubElement")
        val entry = known("entry", "app.zhendong.reamicro.ui.reader.components.ScrollPagerKt", "ScrollPager")
        put("value:owner", entry.paramTypeNames.indices.single { entry.paramTypeNames[it] == receiver.name }.toString())
        method("rememberList", entry.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == list.name }.one("list factory"))
        method("collectItems", entry.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == paging.name }.one("paging factory"))
        val captures = entry.invokes.filter { it.isConstructor && it.paramTypeNames.count { name -> name == list.name } == 1 &&
            it.paramTypeNames.count { name -> name == paging.name } == 1 && it.paramTypeNames.count { name -> name == receiver.name } == 1 }
            .distinctBy { it.descriptor }
        check(captures.isNotEmpty())
        put("value:captures", captures.size.toString())
        captures.forEachIndexed { index, ctor ->
            val role = "capture.$index"
            put("class:$role", ctor.declaredClass!!.descriptor); put("constructor:$role", ctor.descriptor)
            val writes = flow.writes(ctor.descriptor)
            for ((key, cls) in listOf("owner" to receiver, "list" to list, "paging" to paging)) {
                val slot = ctor.paramTypeNames.indices.single { ctor.paramTypeNames[it] == cls.name }
                put("value:$role.$key", slot.toString())
                put("field:$role.$key", writes.single { it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(slot + 1) }.field)
            }
            method("$role.invoke", ctor.declaredClass!!.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == List(2) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object" }.one("$role invocation"))
            method("$role.resume", ctor.declaredClass!!.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                it.paramTypeNames == listOf("java.lang.Object") && it.returnTypeName == "java.lang.Object" }.one("$role resumption"))
        }
        val snapshotGet = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings("Illegal attempt to access index ", " in ItemSnapshotList of size ")))
            .filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("int") && it.returnTypeName == "java.lang.Object" }.one("snapshot index")
        val snapshot = requireNotNull(snapshotGet.declaredClass); put("class:snapshot", snapshot.descriptor)
        method("snapshot", paging.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == snapshot.name }.one("snapshot"))
        put("field:snapshot.items", snapshot.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName in setOf("java.util.List", "java.util.ArrayList") }.descriptor)
        put("field:snapshot.before", snapshotGet.usingFields.map { it.field }.filter { it.declaredClassName == snapshot.name && it.typeName == "int" }.distinctBy { it.descriptor }.single().descriptor)
        val scroll = method("scroll", list.methods.filter { !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf("int", "int", continuation.name) && it.returnTypeName == "java.lang.Object" }.one("scroll to item"))
        val snap = list.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("int", "int") && it.returnTypeName == "void" }.one("snap position")
        val update = snap.invokes.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("int", "int") &&
            it.returnTypeName == "void" && it.usingStrings.any { text -> text.startsWith("Index should be non-negative") } }.one("position update")
        for ((role, parameter) in listOf("index" to 1, "offset" to 2)) {
            val stateFields = flow.calls(update.descriptor).filter { it.arguments.size == 2 && it.arguments[1] == HostDexCalls.Parameter(parameter) }
                .mapNotNull { (it.arguments[0] as? HostDexCalls.Field)?.descriptor }.toSet()
            method(role, list.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "int" &&
                it.usingFields.any { field -> field.field.descriptor in stateFields } }.one("visible $role"))
        }
        val item = type("item", "androidx.compose.foundation.lazy.LazyListMeasuredItem")
        val layoutMethod = method("layout", list.methods.filter { candidate -> !Modifier.isStatic(candidate.modifiers) && candidate.paramCount == 0 &&
            candidate.returnType?.fields.orEmpty().any { !Modifier.isStatic(it.modifiers) && it.typeName == item.name } }.one("layout info"))
        val layout = requireNotNull(layoutMethod.returnType); put("class:layout", layout.descriptor)
        put("field:layout.visible", layout.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "java.util.List" }.descriptor)
        // Native ensure-visible code compares item offset with viewportStart + padding,
        // then item end with viewportEnd - the same padding. Recover those actual fields.
        fun field(value: HostDexCalls.Value, owner: String): String? = (value as? HostDexCalls.Field)?.descriptor
            ?.takeIf { it.startsWith("$owner->") && it.endsWith(":I") }
        data class Geometry(val start: String, val end: String, val offset: String, val index: String)
        val geometry = layoutMethod.callers.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("java.lang.Object") &&
            it.returnTypeName == "java.lang.Object" }.mapNotNull { caller ->
            val comparisons = flow.comparisons(caller.descriptor)
            val starts = comparisons.mapNotNull { comparison ->
                if (comparison.operation != "IF_GE") return@mapNotNull null
                val offset = field(comparison.left, item.descriptor) ?: return@mapNotNull null
                val sum = comparison.right as? HostDexCalls.Arithmetic ?: return@mapNotNull null
                if (sum.operation != "ADD") return@mapNotNull null
                val start = field(sum.left, layout.descriptor) ?: return@mapNotNull null
                Triple(start, offset, sum.right)
            }.distinct()
            val start = starts.singleOrNull() ?: return@mapNotNull null
            val ends = comparisons.mapNotNull { comparison ->
                if (comparison.operation != "IF_LE") return@mapNotNull null
                val itemEnd = comparison.left as? HostDexCalls.Arithmetic ?: return@mapNotNull null
                if (itemEnd.operation != "ADD" || HostDexCalls.Field(start.second) !in listOf(itemEnd.left, itemEnd.right)) return@mapNotNull null
                val end = comparison.right as? HostDexCalls.Arithmetic ?: return@mapNotNull null
                if (end.operation != "SUB" || end.right != start.third) return@mapNotNull null
                field(end.left, layout.descriptor)
            }.distinct()
            val targetIndices = flow.calls(caller.descriptor).filter { call -> bridge.getMethodData(call.descriptor)?.let {
                it.className == list.name && it.paramTypeNames == listOf("int", continuation.name) && it.returnTypeName == scroll.returnTypeName
            } == true }.map { it.arguments[1] }.toSet()
            val itemIndices = comparisons.filter { it.operation in setOf("IF_EQ", "IF_NE") }.mapNotNull {
                when { it.right in targetIndices -> field(it.left, item.descriptor); it.left in targetIndices -> field(it.right, item.descriptor); else -> null }
            }.distinct()
            if (ends.size != 1 || itemIndices.size != 1) null else Geometry(start.first, ends.single(), start.second, itemIndices.single())
        }.distinct().single()
        put("field:layout.start", geometry.start); put("field:layout.end", geometry.end)
        put("field:item.offset", geometry.offset); put("field:item.index", geometry.index)
        known("rootStyle", "org.epub.UIEpubWindow", "getRootStyle")
        put("field:element.id", base.getValue("field:org.epub.element.model.EpubElement#id"))
        val cfi = requireNotNull(bridge.getFieldData(getValue("field:element.id"))).type
        put("class:cfi", cfi.descriptor)
        method("compare", cfi.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(cfi.name) && it.returnTypeName == "int" }.one("CFI ordering"))
        val read = requireNotNull(bridge.getMethodData(base.getValue("method:app.zhendong.reamicro.data.epub.Epub#readElements")))
        val written = flow.writes(read.descriptor).map { it.field }.toSet()
        for ((role, kind) in listOf("chapter" to "int", "title" to "java.lang.String")) {
            put("field:element.$role", element.fields.single { !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) &&
                it.typeName == kind && it.descriptor in written &&
                it.descriptor != base.getValue("field:org.epub.element.model.EpubElement#type") }.descriptor)
        }
        val context = type("context", "kotlin.coroutines.CoroutineContext")
        known("withContext", "kotlinx.coroutines.BuildersKt", "withContext")
        val dispatcher = type("dispatcher", "kotlinx.coroutines.internal.MainDispatcherLoader")
        put("field:main", dispatcher.fields.single { Modifier.isStatic(it.modifiers) && it.typeName != "boolean" }.descriptor)
        method("invoke2", function2.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == List(2) { "java.lang.Object" } &&
            it.returnTypeName == "java.lang.Object" }.one("function2 invocation"))
        val unitFields = scroll.usingFields.map { it.field }.filter { Modifier.isStatic(it.modifiers) && it.declaredClassName == it.typeName &&
            it.type.methods.any { m -> m.paramCount == 0 && m.returnTypeName == "java.lang.String" && "kotlin.Unit" in m.usingStrings } }.distinctBy { it.descriptor }
        put("field:unit", unitFields.single().descriptor)
        check(context.descriptor.isNotBlank() && vm.descriptor.isNotBlank())
    }
}
