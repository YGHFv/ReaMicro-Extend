package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostReaderWindowBindings {
    const val KEY = "reader.window"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var snapshot: Map<String, String>? = null
    fun boundDescriptors() = snapshot?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; snapshot = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, HostAutoScanner.queriedDescriptors().orEmpty(), false)
            current = restore(values, loader); snapshot = values
        } }.onFailure { HookRuntime.logError("ReaMicro reader window query unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) = query(session, base, true)
    private fun query(session: HostQuerySession.Session, base: Map<String, String>, force: Boolean) = session.query(KEY, force, validate = { restore(it, session.loader) },
        find = { HostReaderWindowQueries.query(it, session.apk, base) }).values.toMap()
    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun type(role: String) = members.type(role)
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun constructor(role: String) = members.constructor(role)
        fun index(role: String) = values.getValue("value:$role").toInt()
        fun pages(vm: Any) = field("vm.pages").get(vm) as Map<*, *>
        fun forward(vm: Any) = field("vm.forward").get(vm) as Map<*, *>
        fun pair(value: Any): Pair<Int, Int> {
            require(type("pair").isInstance(value))
            return (field("pair.first").get(value) as Number).toInt() to (field("pair.second").get(value) as Number).toInt()
        }
        fun contains(page: Any, cfi: Any): Boolean {
            require(type("page").isInstance(page) && type("cfi").isInstance(cfi))
            val range = field("page.range").get(page) ?: return false
            return method("contains").invoke(range, cfi) == true
        }
        fun start(page: Any): Any = requireNotNull(field("page.start").get(page))
        fun emitCorrection(vm: Any, page: Int): Boolean {
            val wrapper = requireNotNull(field("vm.correction").get(vm))
            val source = requireNotNull(field("correction.source").get(wrapper))
            return method("emitCorrection").invoke(source, page) == true
        }
    }
    private fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val types = "vm epub page cfi pair continuation publisher range mutex pagerFlows correction shared".split(' ')
        val methods = "recenter getPage publish contains tryLock unlock emitCorrection".split(' ')
        val fields = "vm.pages vm.forward vm.reverse vm.loaded vm.minimum vm.maximum vm.mutex vm.correction correction.source publish.owner publish.pages publish.forward publish.reverse publish.minimum publish.maximum pair.first pair.second page.start page.range".split(' ')
        check(values.keys.containsAll(types.map { "class:$it" } + methods.map { "method:$it" } + fields.map { "field:$it" } + "constructor:publisher"))
        val s = Contract(values, HostRuntimeBindings.Members(values, loader))
        val any = Any::class.java; val int = Integer.TYPE
        fun field(role: String, owner: String, kind: Class<*>) = check(s.field(role).let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type(owner) && kind.isAssignableFrom(it.type)
        })
        fun instance(role: String, owner: String, result: Class<*>, params: List<Class<*>>) = check(s.method(role).let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type(owner) && it.returnType == result && it.parameterTypes.toList() == params
        })
        val recenter = s.method("recenter")
        check(!Modifier.isStatic(recenter.modifiers) && recenter.declaringClass == s.type("vm") && recenter.returnType == any &&
            recenter.parameterCount == 4 && recenter.parameterTypes.take(3) == listOf(int, s.type("epub"), s.type("cfi")) &&
            s.type("continuation").isAssignableFrom(recenter.parameterTypes.last()))
        instance("getPage", "vm", s.type("page"), listOf(int)); instance("publish", "publisher", any, listOf(any))
        val ctor = s.constructor("publisher"); check(ctor.declaringClass == s.type("publisher"))
        for (role in listOf("pages", "forward", "reverse")) {
            field("vm.$role", "vm", Map::class.java); field("publish.$role", "publisher", Map::class.java)
        }
        check(listOf("pages", "forward", "reverse").map { s.field("vm.$it") }.distinct().size == 3)
        field("publish.owner", "publisher", s.type("vm")); field("vm.loaded", "vm", Set::class.java)
        for (role in listOf("minimum", "maximum")) {
            field("publish.$role", "publisher", Integer::class.java)
            check(s.field("vm.$role").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("vm") && !it.type.isPrimitive })
        }
        val captures = listOf("owner", "pages", "forward", "reverse", "minimum", "maximum")
        check(captures.map { s.index("publish.$it") }.distinct().size == captures.size)
        for (role in captures) {
            val slot = s.index("publish.$role")
            check(slot in ctor.parameterTypes.indices && ctor.parameterTypes[slot] == s.field("publish.$role").type)
        }
        for (role in listOf("first", "second")) field("pair.$role", "pair", any)
        check(s.field("pair.first") != s.field("pair.second"))
        field("page.start", "page", s.type("cfi")); field("page.range", "page", s.type("range"))
        check(s.method("contains").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("range") &&
            it.returnType == java.lang.Boolean.TYPE && it.parameterCount == 1 && it.parameterTypes.single().isAssignableFrom(s.type("cfi")) })
        field("vm.mutex", "vm", s.type("mutex"))
        val tryLock = s.method("tryLock")
        check(!Modifier.isStatic(tryLock.modifiers) && tryLock.declaringClass == s.type("mutex") && tryLock.returnType == java.lang.Boolean.TYPE &&
            tryLock.parameterTypes.toList() in listOf(emptyList(), listOf(any)))
        instance("unlock", "mutex", Void.TYPE, listOf(any))
        field("vm.correction", "vm", s.type("correction"))
        check(s.field("correction.source").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("correction") &&
            it.type.isAssignableFrom(s.type("shared")) })
        instance("emitCorrection", "shared", java.lang.Boolean.TYPE, listOf(any))
        return s
    }
}
