package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostReaderLifecycleBindings {
    const val KEY = "reader.lifecycle"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var snapshot: Map<String, String>? = null
    fun boundDescriptors() = snapshot?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; snapshot = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, HostAutoScanner.queriedDescriptors().orEmpty(), false)
            current = restore(values, loader); snapshot = values
        } }.onFailure { HookRuntime.logError("ReaMicro reader lifecycle query unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) = query(session, base, true)
    private fun query(session: HostQuerySession.Session, base: Map<String, String>, force: Boolean) = session.query(KEY, force, validate = { restore(it, session.loader) },
        find = { HostReaderLifecycleQueries.query(it, session.apk, base) }).values.toMap()
    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun type(role: String) = members.type(role)
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun constructor(role: String) = members.constructor(role)
        fun index(role: String) = values.getValue("value:$role").toInt()
        fun vmConstructors() = values.keys.filter { it.startsWith("constructor:vm.") }.map { constructor(it.removePrefix("constructor:")) }
        fun intentName(value: Any) = values.keys.filter { it.startsWith("class:intent.") }
            .singleOrNull { type(it.removePrefix("class:")).isInstance(value) }?.removePrefix("class:intent.")
        fun instance(role: String): Any = requireNotNull(field("$role.instance").get(null))
    }
    private fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val s = Contract(values, HostRuntimeBindings.Members(values, loader))
        val methods = "dispatch send current saveMark scroll read readElements home bookshelf width dp cleared capture".split(' ')
        val intents = "MarkJump BookmarkJump JumpChapter JumpProgress MarkUpsertHighlight Scroll Statistics ClearJump UpdateUIStatus JumpNextChapter JumpPreviousChapter ReturnToOriginalPosition".split(' ')
        check(values.keys.containsAll(methods.map { "method:$it" } + intents.map { "class:intent.$it" } +
            listOf("class:vm", "class:book", "class:session", "class:epub", "class:dom", "class:layout", "class:status.reader", "constructor:vm.0")))
        val dispatch = s.method("dispatch")
        check(dispatch.declaringClass == s.type("vm") && !Modifier.isStatic(dispatch.modifiers) && dispatch.parameterCount == 1 && dispatch.returnType == Void.TYPE)
        check(intents.all { dispatch.parameterTypes[0].isAssignableFrom(s.type("intent.$it")) })
        val send = s.method("send")
        check(send.declaringClass.isAssignableFrom(s.type("vm")) && !Modifier.isStatic(send.modifiers) && send.parameterTypes.contentEquals(dispatch.parameterTypes) && send.returnType == Void.TYPE)
        for (role in listOf("current", "cleared")) check(s.method(role).let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass.isAssignableFrom(s.type("vm")) && it.parameterCount == 0 && (role != "cleared" || it.returnType == Void.TYPE)
        })
        check(s.method("capture").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("vm") && it.parameterTypes.toList() == listOf(s.type("book")) && it.returnType == Void.TYPE })
        check(s.vmConstructors().all { it.declaringClass == s.type("vm") && it.parameterTypes.count { type -> type == s.type("session") } == 1 })
        for (role in listOf("intent.ClearJump", "intent.JumpNextChapter", "intent.JumpPreviousChapter", "intent.ReturnToOriginalPosition", "status.reader")) {
            val field = s.field("$role.instance"); check(Modifier.isStatic(field.modifiers) && field.declaringClass == s.type(role) && field.type == s.type(role))
        }
        for ((role, property, primitive) in listOf(Triple("MarkUpsertHighlight", "selection", false), Triple("MarkUpsertHighlight", "style", true),
            Triple("MarkUpsertHighlight", "color", false), Triple("Scroll", "element", false), Triple("Statistics", "page", false),
            Triple("JumpChapter", "index", true), Triple("JumpChapter", "chapter", false), Triple("UpdateUIStatus", "status", false))) {
            val field = s.field("$role.$property"); check(field.declaringClass == s.type("intent.$role") && !Modifier.isStatic(field.modifiers) && field.type.isPrimitive == primitive)
        }
        for (role in listOf("Statistics", "UpdateUIStatus")) {
            val field = s.field("$role.${if (role == "Statistics") "page" else "status"}")
            check(s.constructor(role).let { it.declaringClass == s.type("intent.$role") && it.parameterTypes.toList() == listOf(field.type) })
        }
        check(s.constructor("MarkUpsertHighlight").let { it.declaringClass == s.type("intent.MarkUpsertHighlight") && it.parameterCount == 3 })
        for ((role, names) in mapOf("MarkUpsertHighlight" to listOf("selection", "style", "color"),
            "Statistics" to listOf("page"), "UpdateUIStatus" to listOf("status"))) {
            val ctor = s.constructor(role)
            val indices = names.map { name -> s.index("$role.$name").also { index ->
                check(index in ctor.parameterTypes.indices && ctor.parameterTypes[index] == s.field("$role.$name").type)
            } }
            check(indices.distinct().size == ctor.parameterCount)
        }
        check(s.field("UpdateUIStatus.status").type.isAssignableFrom(s.type("status.reader")))
        for (role in listOf("read", "readElements")) check(s.method(role).let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("epub") && it.returnType == Any::class.java && it.parameterTypes.first() == Integer.TYPE })
        for (role in listOf("scroll", "home", "bookshelf")) check(s.method(role).let { Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE })
        check(s.method("saveMark").let { !Modifier.isStatic(it.modifiers) && it.returnType == Any::class.java && it.parameterCount == 11 })
        check(s.method("width").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("dom") && it.parameterCount == 0 && it.returnType == java.lang.Float.TYPE })
        check(s.method("dp").let { Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() == listOf(Number::class.java) && it.returnType == java.lang.Float.TYPE })
        check(s.field("dom.layout").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("dom") && it.type == s.type("layout") })
        check(s.field("layout.size").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == s.type("layout") && it.type == java.lang.Long.TYPE })
        return s
    }
}
