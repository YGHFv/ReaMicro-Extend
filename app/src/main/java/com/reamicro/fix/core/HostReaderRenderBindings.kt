package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostReaderRenderBindings {
    const val KEY = "reader.render"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var descriptors: Map<String, String>? = null
    fun boundDescriptors() = descriptors?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; descriptors = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, false, HostAutoScanner.queriedDescriptors().orEmpty())
            current = restore(values, loader); descriptors = values
        } }.onFailure { HookRuntime.logError("ReaMicro reader render contract unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) = query(session, true, base)
    private fun query(session: HostQuerySession.Session, force: Boolean, base: Map<String, String>) =
        session.query(KEY, force, validate = { restore(it, session.loader) },
            find = { HostReaderRenderQueries.query(it, session.apk, base) }).values.toMap()

    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun method(key: String) = members.method(key)
        fun field(key: String) = members.field(key)
        fun type(key: String) = members.type(key)
        fun constructor(key: String) = members.constructor(key)
        fun index(key: String) = values.getValue("value:$key").toInt()
    }

    private fun restore(v: Map<String, String>, loader: ClassLoader): Contract {
        val required = listOf("mark", "resolved", "dom", "window", "page", "composer", "shared").map { "class:$it" } +
            listOf("body", "scroll", "catalog", "container", "overlay", "content", "consume", "scope").map { "method:$it" } +
            listOf("body.marks", "scroll.marks", "catalog.marks", "container.marks", "shared.marks", "overlay.marks",
                "body.composer", "scroll.composer", "content.composer", "content.dom", "container.page", "overlay.dom", "overlay.window", "overlay.length").map { "value:$it" } +
            listOf("constructor:shared", "field:shared.marks", "field:localMarks")
        check(v.keys.containsAll(required)) { "Incomplete reader render contract: ${required - v.keys}" }
        val c = Contract(v, HostRuntimeBindings.Members(v, loader))
        fun parameter(role: String, key: String, type: Class<*>) {
            val args = c.method(role).parameterTypes
            check(c.index("$role.$key") in args.indices && args[c.index("$role.$key")] == type)
        }
        for (role in listOf("body", "scroll", "catalog", "container", "overlay")) parameter(role, "marks", List::class.java)
        for (role in listOf("body", "scroll", "container", "content")) check(c.method(role).let { Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE })
        check(c.method("catalog").let { !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE })
        check(c.method("overlay").let { Modifier.isStatic(it.modifiers) && it.returnType == List::class.java })
        for (role in listOf("body", "scroll", "content")) {
            parameter(role, "composer", c.type("composer"))
            check(c.method(role).parameterTypes.getOrNull(c.index("$role.composer") + 1) == java.lang.Integer.TYPE)
        }
        parameter("container", "page", c.type("page"))
        parameter("content", "dom", c.type("dom"))
        parameter("overlay", "dom", c.type("dom"))
        parameter("overlay", "window", c.type("window"))
        parameter("overlay", "length", java.lang.Integer.TYPE)
        check(c.method("consume").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("composer") && it.parameterCount == 1 && it.returnType == Any::class.java })
        check(c.field("localMarks").let { Modifier.isStatic(it.modifiers) && c.method("consume").parameterTypes.single().isAssignableFrom(it.type) })
        check(c.method("scope").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("composer") && it.parameterCount == 0 && !it.returnType.isPrimitive })
        check(c.constructor("shared").let { it.declaringClass == c.type("shared") && it.parameterCount == 4 &&
            c.index("shared.marks") in it.parameterTypes.indices && it.parameterTypes[c.index("shared.marks")] == List::class.java })
        check(c.field("shared.marks").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("shared") && it.type == List::class.java })
        return c
    }
}
