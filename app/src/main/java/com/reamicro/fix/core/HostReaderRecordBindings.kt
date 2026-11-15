package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostReaderRecordBindings {
    const val KEY = "reader.records"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var descriptors: Map<String, String>? = null
    fun boundDescriptors() = descriptors?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; descriptors = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, false, HostAutoScanner.queriedDescriptors().orEmpty())
            current = restore(values, loader); descriptors = values
        } }.onFailure { HookRuntime.logError("ReaMicro reader record contract unavailable", it) }
        current?.let { HostSymbols.installFeatureDescriptors(it.logicalDescriptors()) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) = query(session, true, base)
    private fun query(session: HostQuerySession.Session, force: Boolean, base: Map<String, String>) =
        session.query(KEY, force, validate = { restore(it, session.loader) },
            find = { HostReaderRecordQueries.query(it, session.apk, base) }).values.toMap()

    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun method(key: String) = members.method(key)
        fun field(key: String) = members.field(key)
        fun type(key: String) = members.type(key)
        fun constructor(key: String) = members.constructor(key)
        fun index(key: String) = values.getValue("value:$key").toInt()
        fun record(role: String, values: Map<String, Any?>): Any {
            val properties = HostReaderRecordQueries.schemas.getValue(role)
            check(values.keys == properties.toSet()) { "Incomplete $role values" }
            val args = arrayOfNulls<Any>(properties.size)
            for (name in properties) args[index("$role.$name")] = values.getValue(name)
            return constructor(role).newInstance(*args)
        }
        fun resolved(mark: Any): Any? {
            val start = method("cfi").invoke(null, field("mark.startCfi").get(mark)) ?: return null
            val end = method("cfi").invoke(null, field("mark.endCfi").get(mark)) ?: return null
            return record("resolved", HostReaderRecordQueries.schemas.getValue("resolved").associateWith { name ->
                when (name) { "startCfi" -> start; "endCfi" -> end; else -> field("mark.$name").get(mark) }
            })
        }
        fun overlay(mark: Any, range: Long, style: Int, color: Long, alpha: Float = 1f) = record("overlay",
            mapOf("mark" to mark, "range" to range, "style" to style, "color" to color, "fillAlpha" to alpha))
        fun text(dom: Any): String = field("dom.content").get(dom)?.let { field("annotated.text").get(it) as String }.orEmpty()
        fun logicalDescriptors(): Map<String, String> = buildMap {
            for ((role, logical) in HostReaderRecordQueries.logicals) {
                for (name in HostReaderRecordQueries.schemas.getValue(role)) put("field:$logical#$name", this@Contract.values.getValue("field:$role.$name"))
            }
            put("field:org.epub.html.node.ContentDom#location", this@Contract.values.getValue("field:dom.location"))
            put("field:org.epub.html.node.ContentDom#content", this@Contract.values.getValue("field:dom.content"))
        }
    }
    private fun restore(v: Map<String, String>, loader: ClassLoader): Contract {
        val required = HostReaderRecordQueries.schemas.flatMap { (role, names) ->
            listOf("class:$role", "constructor:$role") + names.flatMap { listOf("field:$role.$it", "value:$role.$it") }
        } + listOf("class:cfi", "class:dom", "class:annotated", "class:window", "class:page", "method:cfi", "method:color", "method:range",
            "field:dom.location", "field:dom.content", "field:annotated.text", "field:window.start", "field:window.endExclusive", "field:page.start")
        check(v.keys.containsAll(required)) { "Incomplete reader record contract: ${required - v.keys}" }
        val c = Contract(v, HostRuntimeBindings.Members(v, loader))
        val expected = mapOf(
            "mark" to listOf(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, String::class.java,
                Int::class.javaPrimitiveType, String::class.java, String::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType,
                String::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType),
            "resolved" to listOf(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, c.type("cfi"), c.type("cfi"), Int::class.javaPrimitiveType,
                String::class.java, String::class.java, String::class.java, Long::class.javaPrimitiveType),
            "overlay" to listOf(c.type("resolved"), Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Float::class.javaPrimitiveType),
        )
        for ((role, names) in HostReaderRecordQueries.schemas) {
            val constructor = c.constructor(role)
            check(constructor.declaringClass == c.type(role) && constructor.parameterCount == names.size)
            check(names.map { c.index("$role.$it") }.toSet() == names.indices.toSet())
            for ((i, name) in names.withIndex()) {
                val field = c.field("$role.$name")
                check(!Modifier.isStatic(field.modifiers) && field.declaringClass == c.type(role) && field.type == expected.getValue(role)[i])
                check(constructor.parameterTypes[c.index("$role.$name")] == field.type)
            }
        }
        for ((key, owner, type) in listOf(Triple("dom.location", "dom", c.type("cfi")), Triple("dom.content", "dom", c.type("annotated")),
            Triple("annotated.text", "annotated", String::class.java), Triple("page.start", "page", c.type("cfi")),
            Triple("window.start", "window", java.lang.Integer.TYPE), Triple("window.endExclusive", "window", java.lang.Integer.TYPE))) {
            check(c.field(key).let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type(owner) && it.type == type })
        }
        for ((key, args, result) in listOf(Triple("cfi", listOf(String::class.java), c.type("cfi")),
            Triple("color", listOf(String::class.java), java.lang.Long.TYPE), Triple("range", listOf(java.lang.Integer.TYPE, java.lang.Integer.TYPE), java.lang.Long.TYPE))) {
            check(c.method(key).let { Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() == args && it.returnType == result })
        }
        return c
    }
}
