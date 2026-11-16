package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostReaderScrollBindings {
    const val KEY = "reader.scroll"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var snapshot: Map<String, String>? = null
    fun boundDescriptors() = snapshot?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; snapshot = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, HostAutoScanner.queriedDescriptors().orEmpty(), false)
            current = restore(values, loader); snapshot = values
        } }.onFailure { HookRuntime.logError("ReaMicro reader scroll query unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) = query(session, base, true)
    private fun query(session: HostQuerySession.Session, base: Map<String, String>, force: Boolean) = session.query(KEY, force, validate = { restore(it, session.loader) },
        find = { HostReaderScrollQueries.query(it, session.apk, base) }).values.toMap()
    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun type(role: String) = members.type(role)
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun constructor(role: String) = members.constructor(role)
        fun index(role: String) = values.getValue("value:$role").toInt()
        fun captures() = (0 until index("captures")).map { "capture.$it" }
        val unit: Any get() = requireNotNull(field("unit").get(null))
        val main: Any get() = requireNotNull(field("main").get(null))
    }
    private fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val types = "list paging vm receiver continuation function2 element snapshot item layout cfi context dispatcher".split(' ')
        val methods = "entry rememberList collectItems snapshot scroll index offset layout rootStyle compare withContext invoke2".split(' ')
        val fields = "snapshot.items snapshot.before layout.visible layout.start layout.end item.offset item.index element.id element.chapter element.title main unit".split(' ')
        check(values.keys.containsAll(types.map { "class:$it" } + methods.map { "method:$it" } + fields.map { "field:$it" } +
            listOf("value:owner", "value:captures")))
        val s = Contract(values, HostRuntimeBindings.Members(values, loader))
        check(s.index("captures") in 1..8 && s.type("receiver").isAssignableFrom(s.type("vm")))
        fun field(role: String, owner: String, expected: Class<*>) = check(s.field(role).let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type(owner) && it.type.isAssignableFrom(expected)
        })
        fun instance(role: String, owner: String, result: Class<*>, params: List<Class<*>> = emptyList()) = check(s.method(role).let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type(owner) && it.returnType == result && it.parameterTypes.toList() == params
        })
        val any = Any::class.java; val int = Integer.TYPE
        val entry = s.method("entry")
        check(Modifier.isStatic(entry.modifiers) && entry.returnType == Void.TYPE && s.index("owner") in entry.parameterTypes.indices &&
            entry.parameterTypes[s.index("owner")] == s.type("receiver"))
        for ((role, result) in listOf("rememberList" to "list", "collectItems" to "paging")) check(s.method(role).let {
            Modifier.isStatic(it.modifiers) && it.returnType == s.type(result)
        })
        for (role in s.captures()) {
            val ctor = s.constructor(role); check(ctor.declaringClass == s.type(role))
            for ((key, type) in listOf("owner" to "receiver", "list" to "list", "paging" to "paging")) {
                val index = s.index("$role.$key")
                check(index in ctor.parameterTypes.indices && ctor.parameterTypes[index] == s.type(type))
                field("$role.$key", role, s.type(type))
            }
            instance("$role.invoke", role, any, listOf(any, any)); instance("$role.resume", role, any, listOf(any))
        }
        instance("snapshot", "paging", s.type("snapshot"))
        check(List::class.java.isAssignableFrom(s.type("snapshot")))
        field("snapshot.items", "snapshot", s.field("snapshot.items").type)
        check(List::class.java.isAssignableFrom(s.field("snapshot.items").type))
        field("snapshot.before", "snapshot", int)
        instance("scroll", "list", any, listOf(int, int, s.type("continuation")))
        instance("index", "list", int); instance("offset", "list", int)
        check(s.method("index") != s.method("offset"))
        instance("layout", "list", s.type("layout"))
        field("layout.visible", "layout", List::class.java)
        for (key in listOf("start", "end")) field("layout.$key", "layout", int)
        check(s.field("layout.start") != s.field("layout.end"))
        for (key in listOf("offset", "index")) field("item.$key", "item", int)
        check(s.field("item.offset") != s.field("item.index"))
        field("element.id", "element", s.type("cfi")); field("element.chapter", "element", int); field("element.title", "element", String::class.java)
        instance("compare", "cfi", int, listOf(s.type("cfi")))
        check(s.method("rootStyle").let { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && !it.returnType.isPrimitive })
        check(s.method("withContext").let { Modifier.isStatic(it.modifiers) && it.returnType == any &&
            it.parameterTypes.toList() == listOf(s.type("context"), s.type("function2"), s.type("continuation")) })
        check(s.type("function2").isInterface)
        instance("invoke2", "function2", any, listOf(any, any))
        check(s.field("main").let { Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("dispatcher") && s.type("context").isAssignableFrom(it.type) })
        check(s.field("unit").let { Modifier.isStatic(it.modifiers) && it.declaringClass == it.type })
        return s
    }
}
