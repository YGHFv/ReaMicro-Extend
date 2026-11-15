package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostReaderPositionBindings {
    const val KEY = "reader.position"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var descriptors: Map<String, String>? = null
    fun boundDescriptors() = descriptors?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; descriptors = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, false, HostAutoScanner.queriedDescriptors().orEmpty())
            current = restore(values, loader); descriptors = values
        } }.onFailure { HookRuntime.logError("ReaMicro reader position contract unavailable", it) }
        current?.let { HostSymbols.installFeatureDescriptors(it.logicalDescriptors()) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) = query(session, true, base)
    private fun query(session: HostQuerySession.Session, force: Boolean, base: Map<String, String>) =
        session.query(KEY, force, validate = { restore(it, session.loader) },
            find = { HostReaderPositionQueries.query(it, session.apk, base) }).values.toMap()

    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun type(role: String) = members.type(role)
        fun constructor(role: String) = members.constructor(role)
        fun properties(role: String) = values.filterKeys { it.startsWith("field:$role#") }
            .keys.associate { it.substringAfter('#') to field(it.removePrefix("field:")) }
        fun copy(role: String, source: Any, changes: Map<String, Any?>): Any {
            require(type(role).isInstance(source))
            val fields = properties(role)
            require(fields.keys.containsAll(changes.keys))
            val before = fields.mapValues { it.value.get(source) }
            val replaced = changes.mapKeys { fields.getValue(it.key) }
            val args = constructor(role).parameterTypes.indices.map { index ->
                val member = field("$role.copy.$index")
                if (replaced.containsKey(member)) replaced[member] else member.get(source)
            }.toTypedArray()
            val result = constructor(role).newInstance(*args)
            check(fields.mapValues { it.value.get(source) } == before &&
                fields.mapValues { it.value.get(result) } == before + changes) { "Reader $role copy changed unrelated fields" }
            return result
        }
        fun page(vm: Any, cfi: Any): Any? = (field("vm.pages").get(vm) as Map<*, *>).values.filterNotNull().firstOrNull {
            type("page").isInstance(it) && method("contains").invoke(field("page.range").get(it), cfi) == true
        }
        fun logicalDescriptors() = mapOf(
            "field:app.zhendong.reamicro.ui.reader.ReaderViewModel#lastVisibleCfi" to values.getValue("field:vm.visible"),
            "field:app.zhendong.reamicro.ui.reader.ReaderViewModel#repository" to values.getValue("field:vm.repository"),
            "field:app.zhendong.reamicro.ui.reader.ReaderViewModel#virtualPages" to values.getValue("field:vm.pages"),
            "field:app.zhendong.reamicro.data.epub.EpubPage#percentage" to values.getValue("field:page.progress"),
            "field:app.zhendong.reamicro.data.epub.EpubPage#chapter" to values.getValue("field:page.chapter"),
            "field:app.zhendong.reamicro.data.epub.EpubPage#range" to values.getValue("field:page.range"),
            "method:app.zhendong.reamicro.repository.ReadRepository#saveReadingState" to values.getValue("method:save"),
        )
    }

    private fun restore(v: Map<String, String>, loader: ClassLoader): Contract {
        val required = listOf("vm", "book", "state", "repository", "dao", "page", "cfi", "range", "continuation", "reducer").map { "class:$it" } +
            listOf("currentState", "updateState", "version", "visible", "getPage", "cfi", "contains", "save", "saveInner").map { "method:$it" } +
            listOf("vm.repository", "vm.visible", "vm.pages", "page.range", "page.progress", "page.chapter", "state#book",
                "book#id", "book#uuid", "book#epubcfi", "book#chapter", "book#progress", "book#updated").map { "field:$it" } +
            listOf("constructor:book", "constructor:state")
        check(v.keys.containsAll(required)) { "Incomplete reader position contract: ${required - v.keys}" }
        val c = Contract(v, HostRuntimeBindings.Members(v, loader))
        fun field(role: String, owner: String, type: Class<*>) = check(c.field(role).let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type(owner) && it.type == type
        })
        field("vm.repository", "vm", c.type("repository")); field("vm.visible", "vm", String::class.java)
        field("vm.pages", "vm", LinkedHashMap::class.java); field("state#book", "state", c.type("book"))
        field("page.range", "page", c.type("range")); field("page.progress", "page", java.lang.Float.TYPE)
        field("page.chapter", "page", String::class.java)
        for (name in listOf("uuid", "epubcfi", "chapter")) field("book#$name", "book", String::class.java)
        for (name in listOf("id", "updated")) field("book#$name", "book", java.lang.Long.TYPE)
        field("book#progress", "book", java.lang.Float.TYPE)
        for (role in listOf("book", "state")) {
            val constructor = c.constructor(role)
            val fields = c.properties(role).values.toSet()
            val all = c.type(role).declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.toSet()
            val ordered = constructor.parameterTypes.indices.map { c.field("$role.copy.$it") }
            check(constructor.declaringClass == c.type(role) && constructor.parameterCount == fields.size && fields == all)
            check(ordered.toSet() == fields && ordered.map { it.type } == constructor.parameterTypes.toList())
        }
        fun instance(role: String, owner: String, args: List<Class<*>>, result: Class<*>) = check(c.method(role).let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass.isAssignableFrom(c.type(owner)) &&
                it.parameterTypes.toList() == args && it.returnType == result
        })
        val state = c.method("currentState")
        check(!Modifier.isStatic(state.modifiers) && state.declaringClass.isAssignableFrom(c.type("vm")) &&
            state.parameterCount == 0 && state.returnType.isAssignableFrom(c.type("state")))
        instance("updateState", "vm", listOf(c.type("reducer")), Void.TYPE)
        instance("version", "vm", listOf(java.lang.Long.TYPE), java.lang.Long.TYPE)
        instance("visible", "vm", listOf(c.type("page"), String::class.java), String::class.java)
        instance("getPage", "vm", listOf(java.lang.Integer.TYPE), c.type("page"))
        check(c.method("contains").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("range") &&
            it.parameterCount == 1 && it.parameterTypes.single() in listOf(c.type("cfi"), Comparable::class.java) &&
            it.parameterTypes.single().isAssignableFrom(c.type("cfi")) && it.returnType == java.lang.Boolean.TYPE })
        check(c.method("cfi").let { Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() == listOf(String::class.java) && it.returnType == c.type("cfi") })
        for (role in listOf("save", "saveInner")) check(c.method(role).let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("repository") &&
            it.parameterCount == 4 && it.parameterTypes.take(3) == listOf(c.type("book"), java.lang.Boolean.TYPE, java.lang.Long::class.java) &&
            c.type("continuation").isAssignableFrom(it.parameterTypes.last()) && it.returnType == Any::class.java })
        check(c.method("save") != c.method("saveInner"))
        return c
    }
}
