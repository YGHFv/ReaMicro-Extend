package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostEpubRuntimeBindings {
    const val KEY = "reader.epub"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var snapshot: Map<String, String>? = null
    fun boundDescriptors() = snapshot?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; snapshot = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, HostAutoScanner.queriedDescriptors().orEmpty(), false)
            current = restore(values, loader); snapshot = values
        } }.onFailure { HookRuntime.logError("ReaMicro EPUB runtime query unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) = query(session, base, true)
    private fun query(session: HostQuerySession.Session, base: Map<String, String>, force: Boolean) = session.query(KEY, force, validate = { restore(it, session.loader) },
        find = { HostEpubRuntimeQueries.query(it, session.apk, base) }).values.toMap()
    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun type(role: String) = members.type(role)
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun constructor(role: String) = members.constructor(role)
        fun index(role: String) = values.getValue("value:$role").toInt()
        fun value(role: String, receiver: Any?): Any? = receiver?.takeIf { field(role).declaringClass.isInstance(it) }?.let { field(role).get(it) }
        fun text(role: String, receiver: Any?) = value(role, receiver)?.toString().orEmpty()
        fun int(role: String, receiver: Any?) = (value(role, receiver) as? Number)?.toInt()
        fun list(role: String, receiver: Any?) = (value(role, receiver) as? List<*>)?.filterNotNull().orEmpty()
        fun rootStyle(): Any? = method("rootStyle").invoke(null)
        fun state(vm: Any): Any = requireNotNull(method("current").invoke(vm))
        val io: Any get() = requireNotNull(field("io").get(null))
        fun anchor(page: Any?): String = text("page.anchor", page).ifBlank { text("page.start", page) }
        fun chapterCfi(chapter: Any?): Any? = value("chapter.cfi", chapter)
        fun mutableFlow(role: String, vm: Any): Any {
            val view = requireNotNull(value("vm.$role", vm))
            return requireNotNull(field("flowView.source").get(view))
        }
        fun initialPage(vm: Any, page: Int) { method("flow.set").invoke(mutableFlow("initial", vm), page) }
        fun updateMarks(vm: Any, transform: (List<Any>) -> List<Any>): Pair<Int, Int>? {
            val flow = mutableFlow("marks", vm)
            repeat(8) {
                val before = method("flow.get").invoke(flow) as? List<*> ?: error("Reader marks are not a list")
                require(before.all { it != null && field("catalogItem.mark").type.isInstance(it) })
                @Suppress("UNCHECKED_CAST") val current = before as List<Any>
                val next = transform(current)
                if (next === current || method("flow.cas").invoke(flow, before, next) == true) return current.size to next.size
            }
            return null
        }
        fun catalogItem(mark: Any, cfi: Any): Any {
            return record("catalogItem", mapOf("bookmark" to null, "mark" to mark, "progressCfi" to cfi, "isCurrentProgress" to false))
        }
        fun record(role: String, properties: Map<String, Any?>): Any {
            require(properties.keys == HostEpubRuntimeQueries.records.getValue(role).second.toSet())
            val args = arrayOfNulls<Any>(constructor(role).parameterCount)
            for ((name, value) in properties) args[index("$role.$name")] = value
            return constructor(role).newInstance(*args)
        }
    }
    private fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val required = HostEpubRuntimeQueries.records.flatMap { (role, schema) -> listOf("class:$role") + schema.second.map { "field:$role.$it" } } +
            "vm continuation cfi io".split(' ').map { "class:$it" } + "cfi rootStyle current read readElements resolvePath catalogIndex chapterAnchor".split(' ').map { "method:$it" } +
            listOf("field:io", "field:page.anchor", "field:page.chapter", "field:chapter.cfi") +
            listOf("catalogItem", "bookmark").flatMap { role -> listOf("constructor:$role") + HostEpubRuntimeQueries.records.getValue(role).second.map { "value:$role.$it" } } +
            listOf("MarkJump", "BookmarkJump").flatMap { listOf("class:intent.$it", "field:intent.$it", "constructor:$it") }
        val stateRequired = listOf("class:vector", "method:searchIcon", "class:flow", "class:flowView", "field:flowView.source", "field:vm.marks", "field:vm.initial",
            "field:vm.windowKey", "method:flow.get", "method:flow.set", "method:flow.cas", "class:intent.UpdateWindowKey",
            "field:UpdateWindowKey.key", "constructor:UpdateWindowKey")
        check(values.keys.containsAll(required))
        check(values.keys.containsAll(stateRequired))
        val s = Contract(values, HostRuntimeBindings.Members(values, loader))
        val int = Integer.TYPE; val any = Any::class.java; val string = String::class.java
        check(s.method("searchIcon").let { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == s.type("vector") })
        for ((role, schema) in HostEpubRuntimeQueries.records) for (name in schema.second) check(s.field("$role.$name").let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type(role)
        })
        for (role in "item.index item.spineIndex chapter.playOrder chapter.parentOrder epub.spineCfiIndex bookmark.chapter bookmark.type".split(' ')) check(s.field(role).type == int)
        check(s.field("state.catalogIndex").type == Integer::class.java)
        for (role in "epub.id item.id item.bookId item.size chapter.id chapter.bookId book.id book.uid".split(' ')) check(s.field(role).type == java.lang.Long.TYPE)
        for (role in "item.idRef item.href item.properties chapter.title chapter.href chapter.anchor chapter.epubcfi book.uuid book.title book.subtitle book.uri book.cover book.chapter book.epubcfi page.summary bookmark.title bookmark.summary bookmark.epubcfi bookmark.remark".split(' ')) check(s.field(role).type == string)
        for (role in "epub.itemRefs epub.chapters epub.bookmarks epub.tags state.catalog".split(' ')) check(s.field(role).type == List::class.java)
        check(s.field("state.book").type == s.type("book"))
        for (role in listOf("page.start", "page.end", "catalogItem.progressCfi")) check(s.field(role).type == s.type("cfi"))
        check(s.field("page.anchor").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("page") && it.type == string })
        check(s.field("page.chapter").let { !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) && it.declaringClass == s.type("page") && it.type == string })
        check(s.method("cfi").let { Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() == listOf(string) && it.returnType == s.type("cfi") })
        check(s.method("rootStyle").let { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && !it.returnType.isPrimitive })
        check(s.method("current").let { !Modifier.isStatic(it.modifiers) && it.declaringClass.isAssignableFrom(s.type("vm")) &&
            it.parameterCount == 0 && it.returnType.isAssignableFrom(s.type("state")) })
        check(s.method("catalogIndex").let { Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("vm") &&
            it.parameterTypes.toList() == listOf(s.type("cfi"), List::class.java) && it.returnType == Integer::class.java })
        check(s.method("chapterAnchor").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("vm") &&
            it.parameterTypes.toList() == listOf(s.type("chapter")) && it.returnType == string })
        check(s.field("chapter.cfi").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("chapter") && it.type == s.type("cfi") })
        for (role in listOf("read", "readElements")) check(s.method(role).let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("epub") &&
            it.returnType == any && it.parameterCount == (if (role == "read") 3 else 2) && it.parameterTypes.first() == int &&
            s.type("continuation").isAssignableFrom(it.parameterTypes.last()) })
        check(s.method("read").parameterTypes[1].isAssignableFrom(s.type("io")))
        check(s.method("resolvePath").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("epub") &&
            it.parameterTypes.toList() == listOf(string) && it.returnType == s.field("epub.directory").type })
        check(s.field("io").let { Modifier.isStatic(it.modifiers) && it.type == s.type("io") && it.declaringClass == it.type })
        for (role in listOf("catalogItem", "bookmark")) {
        val names = HostEpubRuntimeQueries.records.getValue(role).second
        val ctor = s.constructor(role); check(ctor.declaringClass == s.type(role) && ctor.parameterCount == names.size)
        val slots = names.map { name -> s.index("$role.$name").also {
            check(it in ctor.parameterTypes.indices && ctor.parameterTypes[it] == s.field("$role.$name").type)
        } }
        check(slots.toSet().size == names.size)
        }
        check(s.field("catalogItem.isCurrentProgress").type == java.lang.Boolean.TYPE)
        for ((role, data) in listOf("MarkJump" to s.field("catalogItem.mark").type, "BookmarkJump" to s.type("bookmark"))) {
            check(s.field("intent.$role").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("intent.$role") && it.type == data })
            check(s.constructor(role).let { it.declaringClass == s.type("intent.$role") && it.parameterTypes.toList() == listOf(data) })
        }
        for (role in listOf("marks", "initial")) check(s.field("vm.$role").let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("vm") && it.type == s.type("flowView") })
        check(s.field("flowView.source").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("flowView") && it.type.isAssignableFrom(s.type("flow")) })
        check(s.field("vm.windowKey").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("vm") && it.type == s.type("flow") })
        for ((role, params, result) in listOf(Triple("get", emptyList(), any), Triple("set", listOf(any), Void.TYPE),
            Triple("cas", listOf(any, any), java.lang.Boolean.TYPE))) check(s.method("flow.$role").let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("flow") && it.parameterTypes.toList() == params && it.returnType == result })
        check(s.field("UpdateWindowKey.key").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("intent.UpdateWindowKey") && it.type == string })
        check(s.constructor("UpdateWindowKey").let { it.declaringClass == s.type("intent.UpdateWindowKey") && it.parameterTypes.toList() == listOf(string) })
        return s
    }
}
