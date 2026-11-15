package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

internal object HostBookQueries {
    const val PREFIX = "app.zhendong.reamicro."
    private fun List<MethodData>.one(label: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("$label: ambiguous/missing ${distinctBy { it.descriptor }.map { it.descriptor }}")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>, reader: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        fun cls(key: String, logical: String): ClassData = requireNotNull(bridge.getClassData(base.getValue("class:$logical"))).also {
            out["class:$key"] = it.descriptor
        }
        fun method(key: String, value: MethodData): MethodData = value.also {
            out["${if (it.isConstructor) "constructor" else "method"}:$key"] = it.descriptor
        }
        fun anchored(vararg strings: String) = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(*strings)))
        val book = cls("book", PREFIX + "data.db.entity.Book")
        val vm = cls("vm", PREFIX + "ui.book.BookDetailsViewModel")
        val repository = cls("repository", PREFIX + "repository.BookRepository")
        val shelf = cls("shelf", PREFIX + "repository.BookshelfRepository")
        val dao = cls("dao", PREFIX + "data.db.dao.BookDao")
        val route = cls("route", PREFIX + "Route\$BookDetail")
        val state = cls("state", PREFIX + "ui.book.BookDetailsUiState")
        val screen = method("screen", requireNotNull(bridge.getMethodData(base.getValue("method:${PREFIX}ui.book.BookDetailsScreenKt#BookDetailsScreen"))))
        check(screen.paramCount == 4 && Modifier.isStatic(screen.modifiers))
        val args = screen.paramTypes[1]
        out["class:args"] = args.descriptor
        out["class:scope"] = screen.paramTypes[0].descriptor
        method("vm", vm.methods.filter { it.isConstructor && it.paramTypeNames.take(2) == listOf(args.name, repository.name) }.one("details VM constructor"))
        fun ownedField(key: String, owner: ClassData, type: ClassData) {
            out["field:$key"] = owner.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == type.name }.descriptor
        }
        ownedField("args", vm, args); ownedField("repository", vm, repository)
        ownedField("shelf", repository, shelf); ownedField("dao", shelf, dao)
        val properties = HostRecordQueries.query(apk, mapOf("class:book" to book.descriptor, "class:args" to args.descriptor,
            "class:route" to route.descriptor, "class:state" to state.descriptor))
        out.putAll(properties.filterKeys { it.startsWith("field:") })
        for (key in listOf("args#bookId", "args#compat", "route#bookId", "route#compat", "state#barcode", "book#id", "book#cloudId", "book#publisher")) {
            requireNotNull(out["field:$key"]) { "Missing book property $key" }
        }
        method("currentState", requireNotNull(bridge.getMethodData(base.getValue("method:${PREFIX}arch.MVIViewModel#getCurrentState"))))
        val update = method("update", shelf.methods.filter { candidate ->
            candidate.isMethod && !Modifier.isStatic(candidate.modifiers) && candidate.paramCount == 2 &&
                candidate.paramTypeNames[0] == book.name && candidate.returnTypeName == "java.lang.Object" && candidate.invokes.any {
                    it.className == dao.name && it.paramCount == 2 && it.paramTypeNames[0] == book.name
                }
        }.one("shelf update book"))
        method("find", update.invokes.filter {
            it.className == dao.name && it.paramCount == 2 && it.paramTypeNames[0] == "long" && it.returnTypeName == "java.lang.Object"
        }.one("read book before update"))
        val routeConstructor = route.methods.filter { it.isConstructor && it.paramTypeNames == listOf("long", "boolean") }.one("book detail route")
        val sourceClick = method("sourceClick", routeConstructor.callers.filter { candidate ->
            candidate.paramCount == 0 && !Modifier.isStatic(candidate.modifiers) && candidate.returnTypeName == "java.lang.Object" &&
                candidate.declaredClass?.fields?.count { !Modifier.isStatic(it.modifiers) && it.typeName == book.name } == 1
        }.one("local book detail navigation"))
        ownedField("sourceBook", requireNotNull(sourceClick.declaredClass), book)
        val topBar = method("topBar", requireNotNull(bridge.getMethodData(reader.getValue("settingsTopBar"))))
        val flow = HostDexCalls(apk)
        val screenCalls = flow.calls(screen.descriptor)
        val callbacks = screenCalls.mapNotNull { call ->
            val constructor = bridge.getMethodData(call.descriptor)?.takeIf { it.isConstructor } ?: return@mapNotNull null
            val allocation = call.arguments.firstOrNull() as? HostDexCalls.Allocation ?: return@mapNotNull null
            val render = constructor.declaredClass?.methods.orEmpty().filter {
                !Modifier.isStatic(it.modifiers) && it.paramTypeNames == List(2) { "java.lang.Object" } &&
                    it.returnTypeName == "java.lang.Object" && it.invokes.any { invoked -> invoked.descriptor == topBar.descriptor }
            }.distinctBy { it.descriptor }.singleOrNull() ?: return@mapNotNull null
            val wrapped = screenCalls.any { wrapper -> wrapper.arguments.contains(allocation) &&
                bridge.getMethodData(wrapper.descriptor)?.let { it.isMethod && Modifier.isStatic(it.modifiers) &&
                    it.paramCount == 3 && it.paramTypeNames.first() == "int" && it.paramTypeNames.last() == screen.paramTypeNames[2] } == true }
            if (wrapped) Triple(constructor, render, call) else null
        }.distinctBy { it.first.descriptor to it.third.arguments }
        val (callbackConstructor, callbackRender, construction) = callbacks.singleOrNull() ?: error("Details top bar callback: $callbacks")
        method("topCallback", callbackConstructor); method("topRender", callbackRender)
        val captures = flow.writes(callbackConstructor.descriptor).filter { write ->
            write.receiver == HostDexCalls.Parameter(0) && write.value is HostDexCalls.Parameter &&
                write.field.substringAfter(':').let { it.startsWith('L') || it.startsWith('[') }
        }.map { it.field }.distinct()
        check(captures.isNotEmpty())
        captures.forEachIndexed { index, field -> out["field:topCapture.$index"] = field }
        val stateReads = callbackRender.invokes.filter {
            it.methodName == "getValue" && it.paramCount == 0 && it.returnTypeName == "java.lang.Object" &&
                !Modifier.isStatic(it.modifiers)
        }.distinctBy { it.descriptor }
        check(stateReads.isNotEmpty())
        stateReads.forEachIndexed { index, read -> method("topRead.$index", read) }
        // Identify the merged branch from the actual constructor invocation, never from an old tag.
        val branch = callbackConstructor.paramTypeNames.indices.singleOrNull { callbackConstructor.paramTypeNames[it] == "byte" }
        if (branch != null) {
            val tag = construction.arguments[branch + 1] as? HostDexCalls.Literal ?: error("Details callback branch is not constant")
            out["value:topTag"] = tag.value.toString()
            out["field:topTag"] = flow.writes(callbackConstructor.descriptor).filter {
                it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(branch + 1)
            }.single().field
        }
        val publishSerializer = requireNotNull(anchored("app.zhendong.reamicro.Route.BookPublish")
            .one("book publish serializer").declaredClass)
        val publishConstructors = publishSerializer.methods.flatMap { it.invokes }.filter {
            it.isConstructor && it.paramTypeNames == listOf("long", "int") &&
                it.declaredClass?.methods.orEmpty().any { m -> "BookPublish(bookId=" in m.usingStrings }
        }
        val publish = requireNotNull(publishConstructors.one("book publish serializer route").declaredClass)
        out["class:publish"] = publish.descriptor
        method("publish", publish.methods.filter { it.isConstructor && it.paramTypeNames == listOf("long") }.one("book publish constructor"))
        method("editVector", anchored("Outlined.Edit").filter { Modifier.isStatic(it.modifiers) && it.paramCount == 0 }.one("native edit vector"))
        val vector = bridge.getMethodData(out.getValue("method:editVector"))!!.returnTypeName
        val modifier = requireNotNull(bridge.getMethodData(reader.getValue("title"))).paramTypeNames[1]
        method("editIcon", bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .paramTypes(vector, "java.lang.String", modifier, "long", screen.paramTypeNames[2], "int", "int").returnType("void"))).filter {
                Modifier.isStatic(it.modifiers)
            }.one("native edit icon render"))
        val bookFields = out.filterKeys { it.startsWith("field:book#") }
        val instanceFields = book.fields.filter { !Modifier.isStatic(it.modifiers) }.map { it.descriptor }.toSet()
        check(bookFields.values.toSet() == instanceFields) { "Incomplete book record properties" }
        val copies = book.methods.filter { it.isConstructor && it.paramCount == instanceFields.size }.mapNotNull { constructor ->
            val writes = flow.writes(constructor.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) }
            val mapping = (1..constructor.paramCount).map { index ->
                writes.filter { it.value == HostDexCalls.Parameter(index) }.map { it.field }.distinct().singleOrNull()
            }
            if (mapping.all { it != null } && mapping.toSet() == instanceFields) constructor to mapping.filterNotNull() else null
        }
        val (copy, order) = copies.singleOrNull() ?: error("Book constructor does not preserve every property")
        method("book", copy)
        order.forEachIndexed { index, field -> out["field:copy.$index"] = field }
        return out
    }
}
