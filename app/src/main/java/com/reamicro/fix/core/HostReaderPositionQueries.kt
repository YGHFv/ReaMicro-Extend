package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

internal object HostReaderPositionQueries {
    private const val P = "app.zhendong.reamicro."
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Reader position $role is missing or ambiguous")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val flow = HostDexCalls(apk)
        fun type(role: String, logical: String) = requireNotNull(bridge.getClassData(base.getValue("class:$logical"))).also {
            out["class:$role"] = it.descriptor
        }
        fun method(role: String, logical: String, name: String) = requireNotNull(bridge.getMethodData(base.getValue("method:$logical#$name"))).also {
            out["method:$role"] = it.descriptor
        }
        val vm = type("vm", P + "ui.reader.ReaderViewModel")
        val book = type("book", P + "data.db.entity.Book")
        val state = type("state", P + "ui.reader.ReaderUiState")
        val repository = type("repository", P + "repository.ReadRepository")
        val dao = type("dao", P + "data.db.dao.BookDao")
        val page = type("page", P + "data.epub.EpubPage")
        val continuation = type("continuation", "kotlin.coroutines.Continuation")
        type("reducer", "kotlin.jvm.functions.Function1")
        method("currentState", P + "arch.MVIViewModel", "getCurrentState")
        method("updateState", P + "arch.MVIViewModel", "updateUiState")
        method("version", P + "ui.reader.ReaderViewModel", "nextReadingStateVersion")
        val visible = method("visible", P + "ui.reader.ReaderViewModel", "resolveVisibleCfi")
        val getPage = method("getPage", P + "ui.reader.ReaderViewModel", "getVirtualPage")
        val create = method("cfi", "org.epub.html.EpubCFI\$Companion", "create")
        val cfi = requireNotNull(create.returnType)
        out["class:cfi"] = cfi.descriptor
        out["field:vm.repository"] = vm.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == repository.name }.descriptor
        out["field:vm.visible"] = visible.usingFields.map { it.field }.filter {
            !Modifier.isStatic(it.modifiers) && it.declaredClassName == vm.name && it.typeName == "java.lang.String"
        }.distinctBy { it.descriptor }.single().descriptor
        val pageMaps = flow.casts(getPage.descriptor).filter { it.type == page.descriptor }.mapNotNull { cast ->
            val call = cast.value as? HostDexCalls.Call ?: return@mapNotNull null
            if (call.descriptor != "Ljava/util/LinkedHashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;") return@mapNotNull null
            (call.arguments.firstOrNull() as? HostDexCalls.Field)?.descriptor?.takeIf { it.startsWith(vm.descriptor + "->") }
        }.distinct()
        out["field:vm.pages"] = pageMaps.single()
        fun containsCfi(method: MethodData) = !Modifier.isStatic(method.modifiers) &&
            method.paramTypeNames.singleOrNull() in listOf(cfi.name, "java.lang.Comparable") && method.returnTypeName == "boolean"
        val contains = visible.invokes.filter { candidate -> containsCfi(candidate) && page.fields.any {
            !Modifier.isStatic(it.modifiers) && it.typeName == candidate.declaredClassName
        } }.one("contains CFI")
        val range = requireNotNull(contains.declaredClass)
        out["class:range"] = range.descriptor
        val rangeField = page.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == range.name }
        check(visible.usingFields.any { it.field.descriptor == rangeField.descriptor } || visible.invokes.any { getter ->
            getter.declaredClassName == page.name && getter.paramCount == 0 && getter.returnTypeName == range.name &&
                getter.usingFields.any { it.field.descriptor == rangeField.descriptor }
        })
        out["field:page.range"] = rangeField.descriptor
        out["method:contains"] = contains.descriptor
        val progress = page.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "float" }
        val chapter = page.fields.single { !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) && it.typeName == "java.lang.String" }
        check(progress.readers.any { reader -> reader.usingFields.any { it.field.descriptor == chapter.descriptor } &&
            reader.invokes.any { it.declaredClassName == book.name && Modifier.isStatic(it.modifiers) && it.returnTypeName == book.name } &&
            reader.invokes.any { it.declaredClassName == repository.name && it.paramTypeNames.contains(book.name) } })
        out["field:page.progress"] = progress.descriptor
        out["field:page.chapter"] = chapter.descriptor

        val saves = repository.methods.filter { !Modifier.isStatic(it.modifiers) && it.isMethod && it.paramCount == 4 &&
            it.paramTypeNames.take(3) == listOf(book.name, "boolean", "java.lang.Long") && it.returnTypeName == "java.lang.Object" &&
            generateSequence(it.paramTypes.last()) { t -> t.superClass }.any { t ->
                t.name == continuation.name || t.interfaces.any { i -> i.name == continuation.name }
            } }
        val inner = saves.filter { candidate -> candidate.invokes.any { it.declaredClassName == dao.name && it.paramTypeNames.contains(book.name) } &&
            candidate.invokes.any { it.descriptor == "Ljava/lang/Math;->max(JJ)J" } }.one("versioned save implementation")
        val save = saves.filter { candidate -> candidate.descriptor != inner.descriptor && candidate.invokes.any { it.descriptor == inner.descriptor } }
            .one("serialized save entry")
        out["method:save"] = save.descriptor
        out["method:saveInner"] = inner.descriptor

        for ((role, logical, record) in listOf(Triple("book", P + "data.db.entity.Book", book), Triple("state", P + "ui.reader.ReaderUiState", state))) {
            val properties = base.filterKeys { it.startsWith("field:$logical#") }.mapKeys { it.key.substringAfter('#') }
            val fields = record.fields.filter { !Modifier.isStatic(it.modifiers) }.map { it.descriptor }.toSet()
            check(properties.values.toSet() == fields && properties.size == fields.size) { "Incomplete $role record mapping" }
            properties.forEach { (name, field) -> out["field:$role#$name"] = field }
            val candidates = record.methods.filter { it.isConstructor && it.paramCount == fields.size }.mapNotNull { constructor ->
                val writes = flow.writes(constructor.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) }
                val ordered = (1..constructor.paramCount).map { parameter ->
                    writes.filter { it.value == HostDexCalls.Parameter(parameter) }.map { it.field }.distinct().singleOrNull()
                }
                if (ordered.all { it != null } && ordered.toSet() == fields) constructor to ordered.filterNotNull() else null
            }
            val (constructor, ordered) = candidates.single()
            out["constructor:$role"] = constructor.descriptor
            ordered.forEachIndexed { index, field -> out["field:$role.copy.$index"] = field }
        }
        return out
    }
}
