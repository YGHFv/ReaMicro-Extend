package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

internal object HostReaderLifecycleQueries {
    private const val P = "app.zhendong.reamicro."
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Reader lifecycle $role is missing or ambiguous")
    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> = buildMap {
        val flow = HostDexCalls(apk)
        fun type(role: String, logical: String) = requireNotNull(bridge.getClassData(base.getValue("class:$logical")))
            .also { put("class:$role", it.descriptor) }
        fun method(role: String, owner: String, name: String) = requireNotNull(bridge.getMethodData(base.getValue("method:$owner#$name")))
            .also { put("method:$role", it.descriptor) }
        fun singleton(role: String) {
            val cls = requireNotNull(bridge.getClassData(getValue("class:$role")))
            put("field:$role.instance", cls.fields.single { Modifier.isStatic(it.modifiers) && it.typeName == cls.name }.descriptor)
        }
        val vm = type("vm", P + "ui.reader.ReaderViewModel")
        val book = type("book", P + "data.db.entity.Book")
        val session = type("session", P + "repository.core.Session")
        type("epub", P + "data.epub.Epub")
        val dom = type("dom", "org.epub.html.node.ContentDom")
        val layout = type("layout", "androidx.compose.ui.text.TextLayoutResult")
        val dispatch = method("dispatch", P + "ui.reader.ReaderViewModel", "onIntent")
        method("send", P + "arch.MVIViewModel", "intent")
        method("current", P + "arch.MVIViewModel", "getCurrentState")
        method("saveMark", P + "repository.ReadRepository", "saveMark")
        method("scroll", P + "ui.reader.components.ScrollPagerKt", "ScrollPager")
        method("read", P + "data.epub.Epub", "read")
        method("readElements", P + "data.epub.Epub", "readElements")
        method("home", P + "ui.home.HomeScreenKt", "HomeScreen")
        val bookshelf = base.filterKeys { it.startsWith("method:${P}ui.home.BookshelfScreenKt#BookshelfScreen") }
            .values.map { requireNotNull(bridge.getMethodData(it)) }.one("bookshelf entry")
        put("method:bookshelf", bookshelf.descriptor)
        val width = method("width", "org.epub.html.node.ContentDom", "getRenderTextWidthDp")
        put("field:dom.layout", dom.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == layout.name }.descriptor)
        put("field:layout.size", layout.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "long" }.descriptor)
        val dp = width.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("java.lang.Number") && it.returnTypeName == "float" }.one("reader pixels to dp")
        put("method:dp", dp.descriptor)
        val cleared = requireNotNull(bridge.getMethodData(base.getValue("method:androidx.lifecycle.ViewModel#onCleared")))
        put("method:cleared", vm.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) && it.methodName == cleared.methodName &&
            it.paramCount == 0 && it.returnTypeName == "void" }.one("cleanup override").descriptor)
        val constructors = vm.methods.filter { it.isConstructor && it.paramTypeNames.count { it == session.name } == 1 }
        check(constructors.isNotEmpty())
        constructors.forEachIndexed { index, ctor -> put("constructor:vm.$index", ctor.descriptor) }
        val bookFields = listOf("epubcfi", "chapter", "progress").map { base.getValue("field:${P}data.db.entity.Book#$it") }.toSet()
        val capture = vm.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(book.name) && it.returnTypeName == "void" &&
            it.usingFields.map { field -> field.field.descriptor }.containsAll(bookFields) }.one("capture return position")
        put("method:capture", capture.descriptor)
        val intentNames = "MarkJump BookmarkJump JumpChapter JumpProgress MarkUpsertHighlight Scroll Statistics ClearJump UpdateUIStatus".split(' ')
        for (name in intentNames) type("intent.$name", P + "ui.reader.ReaderUiIntent\$$name")
        for (name in listOf("JumpNextChapter", "JumpPreviousChapter", "ReturnToOriginalPosition")) {
            val string = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(name)))
                .filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "java.lang.String" }.one("$name intent")
            val cls = requireNotNull(string.declaredClass)
            check(dispatch.usingFields.any { it.field.typeName == cls.name } ||
                dispatch.descriptor in flow.referencingType(cls.descriptor)) { "$name is not used by Reader dispatch" }
            put("class:intent.$name", cls.descriptor); singleton("intent.$name")
        }
        singleton("intent.ClearJump")
        type("status.reader", P + "ui.reader.UiStatus\$Reader"); singleton("status.reader")
        for ((intent, names) in mapOf("MarkUpsertHighlight" to listOf("selection", "style", "color"), "Scroll" to listOf("element"),
            "Statistics" to listOf("page"), "JumpChapter" to listOf("index", "chapter"), "UpdateUIStatus" to listOf("status"))) {
            for (name in names) put("field:$intent.$name", base.getValue("field:${P}ui.reader.ReaderUiIntent\$$intent#$name"))
        }
        for (role in listOf("MarkUpsertHighlight", "Statistics", "UpdateUIStatus")) {
            val cls = requireNotNull(bridge.getClassData(getValue("class:intent.$role")))
            val fields = cls.fields.filterNot { Modifier.isStatic(it.modifiers) }
            val ctor = cls.methods.filter { it.isConstructor && it.paramCount == fields.size &&
                flow.writes(it.descriptor).filter { write -> write.receiver == HostDexCalls.Parameter(0) }.map { write -> write.field }.toSet() == fields.map { field -> field.descriptor }.toSet()
            }.one("$role constructor")
            put("constructor:$role", ctor.descriptor)
            for ((key, descriptor) in entries.filter { it.key.startsWith("field:$role.") }.map { it.key to it.value }) {
                val parameter = flow.writes(ctor.descriptor).single { it.receiver == HostDexCalls.Parameter(0) && it.field == descriptor }.value
                    as? HostDexCalls.Parameter ?: error("$key constructor argument is not proven")
                put("value:${key.removePrefix("field:")}", (parameter.index - 1).toString())
            }
        }
    }
}
