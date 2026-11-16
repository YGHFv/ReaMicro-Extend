package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostReaderPagerBindings {
    const val KEY = "reader.pager"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var snapshot: Map<String, String>? = null
    fun boundDescriptors() = snapshot?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; snapshot = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, HostAutoScanner.queriedDescriptors().orEmpty(), false)
            current = restore(values, loader); snapshot = values
        } }.onFailure { HookRuntime.logError("ReaMicro reader pager query unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) = query(session, base, true)
    private fun query(session: HostQuerySession.Session, base: Map<String, String>, force: Boolean) = session.query(KEY, force, validate = { restore(it, session.loader) },
        find = { HostReaderPagerQueries.query(it, session.apk, base) }).values.toMap()
    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun type(role: String) = members.type(role)
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun constructor(role: String) = members.constructor(role)
        fun index(role: String) = values.getValue("value:$role").toInt()
    }
    private fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val types = "vm receiver model flows actions deps composer pager function0 function1 function2 swipeDeps page pair curl.content curl.restart".split(' ')
        val methods = "swipe overlay static current scrolling curl.platform curl.root curl.content curl.restart".split(' ')
        val fields = "model.progress model.bookProgress vm.pages curl.content.owner curl.content.model".split(' ')
        check(values.keys.containsAll(types.map { "class:$it" } + methods.map { "method:$it" } + fields.map { "field:$it" } +
            listOf("constructor:curl.content", "constructor:curl.restart")))
        val s = Contract(values, HostRuntimeBindings.Members(values, loader))
        val any = Any::class.java; val int = Integer.TYPE
        check(s.type("receiver").isAssignableFrom(s.type("vm")))
        fun argument(method: String, role: String, type: Class<*>) {
            val params = s.method(method).parameterTypes; val slot = s.index("$method.$role")
            check(slot in params.indices && params[slot] == type)
        }
        fun instance(role: String, owner: String, result: Class<*>, params: List<Class<*>> = emptyList()) = check(s.method(role).let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type(owner) && it.returnType == result && it.parameterTypes.toList() == params
        })
        for (role in listOf("swipe", "overlay", "static")) {
            val m = s.method(role)
            check(Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE &&
                m.parameterTypes.takeLast(2) == listOf(s.type("composer"), int))
            argument(role, "owner", s.type("receiver")); argument(role, "model", s.type("model"))
            val slot = s.index("$role.current")
            if (role == "swipe" && values["value:swipe.native"] == "true") {
                check(slot == -1)
                check(s.method("swipe.render").parameterTypes.take(4) ==
                    listOf(int, int, java.lang.Boolean.TYPE, int))
                for ((key, type) in listOf("owner" to "receiver", "model" to "model")) check(
                    s.field("swipe.content.$key").let { !Modifier.isStatic(it.modifiers) &&
                        it.declaringClass == s.method("swipe.content").declaringClass &&
                        it.type.isAssignableFrom(s.type(type)) })
            } else {
            check(slot in m.parameterTypes.indices)
            if (role == "swipe") check(s.type("pager").isAssignableFrom(m.parameterTypes[slot])) else check(m.parameterTypes[slot] == int)
            check(setOf(s.index("$role.owner"), s.index("$role.model"), slot).size == 3)
            }
            val prefix = listOf("receiver", "model", "flows", "actions", "deps", "swipeDeps").map(s::type)
            check(m.parameterTypes.filter { it in prefix } == prefix)
            val tail = m.parameterTypes.drop(m.parameterTypes.indexOf(s.type("swipeDeps")) + 1).dropLast(2)
            if (role == "overlay") check(tail in listOf(
                listOf(int, java.lang.Boolean.TYPE, s.type("function1"), s.type("function2")),
                listOf(int, java.lang.Boolean.TYPE, s.type("function1"), s.type("function1"), s.type("function2"))))
            if (role == "static") check(tail == listOf(int, s.type("function1"), s.type("function1"), s.type("function0")))
        }
        instance("current", "pager", int); instance("scrolling", "pager", java.lang.Boolean.TYPE)
        for (role in listOf("progress", "bookProgress")) check(s.field("model.$role").let {
            it.declaringClass == s.type("model") && it.type == String::class.java && !Modifier.isStatic(it.modifiers)
        })
        check(s.field("model.progress") != s.field("model.bookProgress"))
        check(s.field("vm.pages").let { it.declaringClass == s.type("vm") && !Modifier.isStatic(it.modifiers) && Map::class.java.isAssignableFrom(it.type) })
        val platform = s.method("curl.platform")
        check(Modifier.isStatic(platform.modifiers) && platform.returnType == Void.TYPE && platform.parameterCount == 12)
        val composer = platform.parameterTypes.indices.single { platform.parameterTypes[it] == s.type("composer") }
        for (role in listOf("current", "version", "flags")) {
            val slot = s.index("curl.$role"); check(slot in platform.parameterTypes.indices && platform.parameterTypes[slot] == int)
        }
        check(s.index("curl.current") < composer && s.index("curl.version") < composer && s.index("curl.current") != s.index("curl.version"))
        check(s.index("curl.flags") == composer + 1 + s.index("curl.version") / 10)
        val root = s.method("curl.root")
        check(Modifier.isStatic(root.modifiers) && root.returnType == Void.TYPE && root.parameterTypes.takeLast(2) == listOf(s.type("composer"), int))
        for ((role, type) in listOf("owner" to "receiver", "model" to "model")) {
            argument("curl.root", role, s.type(type))
            val ctor = s.constructor("curl.content"); val slot = s.index("curl.content.$role")
            check(ctor.declaringClass == s.type("curl.content") && slot in ctor.parameterTypes.indices && ctor.parameterTypes[slot] == s.type(type))
            check(s.field("curl.content.$role").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == ctor.declaringClass && it.type.isAssignableFrom(s.type(type)) })
        }
        check(s.field("curl.content.owner") != s.field("curl.content.model"))
        instance("curl.content", "curl.content", any, listOf(any, any))
        instance("curl.restart", "curl.restart", any, listOf(any, any))
        val restart = s.constructor("curl.restart"); val version = s.index("curl.restart.version")
        check(restart.declaringClass == s.type("curl.restart") && version in restart.parameterTypes.indices && restart.parameterTypes[version] == int)
        return s
    }
}
