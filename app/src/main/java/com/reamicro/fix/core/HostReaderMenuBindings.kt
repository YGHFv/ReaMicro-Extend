package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostReaderMenuBindings {
    const val KEY = "reader.menu"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var descriptors: Map<String, String>? = null
    fun boundDescriptors() = descriptors?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true
        current = null; descriptors = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, false, HostAutoScanner.queriedDescriptors().orEmpty(), requireNotNull(HostUiBindings.boundDescriptors()))
            current = restore(values, loader); descriptors = values
        } }.onFailure { HookRuntime.logError("ReaMicro reader menu contract unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>, ui: Map<String, String>) = query(session, true, base, ui)
    private fun query(session: HostQuerySession.Session, force: Boolean, base: Map<String, String>, ui: Map<String, String>) =
        session.query(KEY, force, validate = { restore(it, session.loader) },
            find = { HostReaderMenuQueries.query(it, session.apk, base, ui) }).values.toMap()
    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun method(key: String) = members.method(key)
        fun field(key: String) = members.field(key)
        fun type(key: String) = members.type(key)
        fun callers() = values.keys.filter { it.startsWith("method:caller.") }.map { method(it.removePrefix("method:")) }
        fun themeMatches(callback: Any, expectedSession: Any?): Boolean {
            if (!type("theme").isInstance(callback) || field("tag").getByte(callback).toLong() != values.getValue("value:tag").toLong()) return false
            val session = field("session").get(callback) ?: return false
            return type("session").isInstance(session) && (expectedSession == null || session === expectedSession)
        }
        fun bottomMatches(callback: Any): Boolean = type("bottomContent").isInstance(callback) &&
            (values.getValue("value:bottomBranchCount") == "0" ||
                (field("bottomTag").get(callback) as? Number)?.toLong() == values.getValue("value:bottomTag").toLong())
    }
    private fun restore(values: Map<String, String>, loader: ClassLoader): Contract {
        val required = setOf("method:render", "class:menu", "class:book", "class:receiver", "class:vm", "field:book", "field:receiver",
            "class:session", "class:theme", "constructor:theme", "method:theme", "field:session", "field:tag", "value:tag",
            "method:clickable", "method:icon", "class:vector", "field:vectorName", "method:caller.0", "method:bottomBar", "method:catalog",
            "class:status", "class:bottomContent", "method:bottomContent", "field:bottom.receiver", "field:bottom.book", "field:bottom.status", "value:bottomBranchCount")
        check(values.keys.containsAll(required)) { "Incomplete reader menu contract: ${required - values.keys}" }
        val members = HostRuntimeBindings.Members(values, loader)
        val c = Contract(values, members)
        check(c.method("render").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("menu") &&
            it.parameterTypes.toList() == List(4) { Any::class.java } && it.returnType == Any::class.java })
        check(c.type("receiver").isAssignableFrom(c.type("vm")))
        for (key in listOf("book", "receiver")) check(c.field(key).let {
            it.declaringClass == c.type("menu") && !Modifier.isStatic(it.modifiers) && it.type.isAssignableFrom(c.type(key)) })
        check(members.constructor("theme").declaringClass == c.type("theme"))
        check(c.method("theme").let { it.declaringClass == c.type("theme") && !Modifier.isStatic(it.modifiers) &&
            it.parameterCount == 0 && it.returnType == Any::class.java })
        check(c.field("tag").let { it.declaringClass == c.type("theme") && !Modifier.isStatic(it.modifiers) && it.type == java.lang.Byte.TYPE })
        check(values.getValue("value:tag").toLong() in Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong())
        check(c.field("session").let { it.declaringClass == c.type("theme") && !Modifier.isStatic(it.modifiers) && it.type.isAssignableFrom(c.type("session")) })
        val clickable = c.method("clickable")
        check(Modifier.isStatic(clickable.modifiers) && clickable.parameterCount == 6 &&
            clickable.parameterTypes[1] == java.lang.Boolean.TYPE && clickable.parameterTypes[2] == String::class.java &&
            clickable.parameterTypes[4].isAssignableFrom(c.type("theme")) && clickable.parameterTypes[5] == java.lang.Integer.TYPE)
        val icon = c.method("icon")
        check(Modifier.isStatic(icon.modifiers) && icon.parameterCount == 7 && icon.parameterTypes[0] == c.type("vector") &&
            icon.parameterTypes[1] == String::class.java && icon.parameterTypes[2] == clickable.parameterTypes[0] &&
            icon.parameterTypes[3] == java.lang.Long.TYPE && icon.parameterTypes.takeLast(2) == List(2) { java.lang.Integer.TYPE })
        check(c.field("vectorName").let { it.declaringClass == c.type("vector") && !Modifier.isStatic(it.modifiers) && it.type == String::class.java })
        val bottom = c.method("bottomBar")
        check(Modifier.isStatic(bottom.modifiers) && bottom.returnType == Void.TYPE && bottom.parameterCount == 10 &&
            bottom.parameterTypes.take(3) == listOf(c.type("receiver"), c.type("book"), c.type("status")) &&
            bottom.parameterTypes.slice(4..6) == List(3) { clickable.parameterTypes[4] } && bottom.parameterTypes[8] == icon.parameterTypes[4])
        check(c.method("catalog").let { Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
            it.parameterTypes.toList() == listOf(c.type("receiver"), c.type("status"), c.type("book"), java.lang.Integer.TYPE,
                List::class.java, Map::class.java, String::class.java, List::class.java, java.lang.Boolean.TYPE,
                java.lang.Integer::class.java, icon.parameterTypes[4], java.lang.Integer.TYPE) })
        check(c.method("bottomContent").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("bottomContent") &&
            it.parameterTypes.toList() == List(3) { Any::class.java } && it.returnType == Any::class.java })
        for (key in listOf("receiver", "book", "status")) check(c.field("bottom.$key").let { !Modifier.isStatic(it.modifiers) &&
            it.declaringClass == c.type("bottomContent") && it.type.isAssignableFrom(c.type(key)) })
        check(values.getValue("value:bottomBranchCount") in setOf("0", "1"))
        if (values.getValue("value:bottomBranchCount") == "1") {
            check(c.field("bottomTag").let { it.declaringClass == c.type("bottomContent") && !Modifier.isStatic(it.modifiers) &&
                it.type in setOf(java.lang.Byte.TYPE, java.lang.Integer.TYPE) })
            values.getValue("value:bottomTag").toInt()
        }
        return c
    }
}
