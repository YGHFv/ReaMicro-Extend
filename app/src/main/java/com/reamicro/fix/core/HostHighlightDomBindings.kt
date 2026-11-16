package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference

internal object HostHighlightDomBindings {
    const val KEY = "reader.highlightDom"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var descriptors: Map<String, String>? = null
    fun boundDescriptors() = descriptors?.toMap()

    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; descriptors = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, false, HostAutoScanner.queriedDescriptors().orEmpty())
            current = restore(values, loader); descriptors = values
        } }.onFailure { HookRuntime.logError("ReaMicro highlight DOM contract unavailable", it) }
        current?.let { HostSymbols.installFeatureDescriptors(it.logicalDescriptors()) }
    }

    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) = query(session, true, base)
    private fun query(session: HostQuerySession.Session, force: Boolean, base: Map<String, String>) =
        session.query(KEY, force, validate = { restore(it, session.loader) },
            find = { HostHighlightDomQueries.query(it, session.apk, base) }).values.toMap()

    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun type(role: String) = members.type(role)
        val roundIndex: Int get() = values.getValue("value:round").toInt()
        fun element(dom: Any): Any? = field("parent").get(dom)?.let { field("element").get(it) }
        fun text(element: Any): String = method("text").invoke(element) as String
        fun previous(element: Any): Any? = method("previous").invoke(element)
        fun next(element: Any): Any? = method("next").invoke(element)
        fun sourceParent(element: Any): Any? = field("source.parent").get(element)
        fun sourceChildren(element: Any): List<Any> = (method("source.children").invoke(element) as List<*>)
            .filterNotNull().filter(type("element")::isInstance)
        fun sourceTag(element: Any): String = method("source.tag").invoke(element) as String
        fun children(node: Any): List<Any> = (field(if (type("dom").isInstance(node)) "dom.children" else "parsed.children")
            .get(node) as List<*>).map { requireNotNull(it) }
        fun tag(node: Any): String = field("common.tag").get(node) as String
        fun nodeText(node: Any): String = field("parsed.text").get(node) as String
        fun paragraphChildren(node: Any): List<Any> = when {
            type("container").isInstance(node) -> (field("container.originalChildren").get(node) as List<*>).filterNotNull()
            type("table").isInstance(node) -> listOf("head", "body", "foot").mapNotNull { field("table.$it").get(node) }
            else -> emptyList()
        }
        fun documentCallers() = List(values.getValue("value:document.callers").toInt()) { method("document.caller.$it") }
        fun logicalDescriptors(): Map<String, String> = mapOf(
            "field:org.epub.html.node.ContentDom#content" to values.getValue("field:content"),
            "method:org.epub.html.node.ContentDom#adaptiveMeasure" to values.getValue("method:measure"),
        )
    }

    private fun restore(v: Map<String, String>, loader: ClassLoader): Contract {
        val required = listOf("dom", "container", "annotated", "element", "sourceNode", "parsed", "common", "document", "table").map { "class:$it" } +
            listOf("parent", "element", "content", "dom.children", "parsed.children", "parsed.text", "common.tag", "source.parent", "container.originalChildren", "table.head", "table.body", "table.foot").map { "field:$it" } +
            listOf("boundary", "text", "previous", "next", "onMeasure", "measure", "container.measure", "source.children", "source.tag", "document.body", "document.parse", "parsed.atomic").map { "method:$it" } +
            listOf("value:round", "value:document.callers")
        check(v.keys.containsAll(required)) { "Incomplete highlight DOM contract: ${required - v.keys}" }
        val c = Contract(v, HostRuntimeBindings.Members(v, loader))
        for ((key, owner, type) in listOf(Triple("parent", "dom", "container"), Triple("element", "container", "element"), Triple("content", "dom", "annotated"))) {
            check(c.field(key).let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type(owner) && it.type == c.type(type) })
        }
        check(c.type("element").superclass == c.type("sourceNode"))
        for (key in listOf("previous", "next")) check(c.method(key).let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("sourceNode") && it.parameterCount == 0 && it.returnType == c.type("element")
        })
        check(c.method("previous") != c.method("next"))
        check(c.method("text").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("element") && it.parameterCount == 0 && it.returnType == String::class.java })
        check(c.field("source.parent").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("sourceNode") && it.type == c.type("element") })
        for ((role, result) in listOf("source.children" to List::class.java, "source.tag" to String::class.java)) check(c.method(role).let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("element") && it.parameterCount == 0 && it.returnType == result
        })
        check(c.method("boundary").let { Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("dom") &&
            it.parameterTypes.toList() == listOf(c.type("dom"), java.lang.Boolean.TYPE) && it.returnType == java.lang.Boolean.TYPE })
        check(c.method("onMeasure").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("dom") &&
            it.parameterTypes.toList() == listOf(AtomicReference::class.java, c.type("container")) && it.returnType == ArrayList::class.java })
        check(c.method("container.measure").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("container") &&
            it.parameterCount == 2 && !it.parameterTypes.first().isPrimitive &&
            it.parameterTypes.last() == AtomicReference::class.java && it.returnType == Void.TYPE })
        check(c.method("measure").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("dom") && it.returnType == Void.TYPE &&
            it.parameterTypes.toList() == listOf(AtomicReference::class.java, java.lang.Integer.TYPE, ArrayList::class.java) })
        check(c.roundIndex in c.method("measure").parameterTypes.indices && c.method("measure").parameterTypes[c.roundIndex] == java.lang.Integer.TYPE)
        check(c.type("parsed").superclass == c.type("common"))
        check(c.method("parsed.atomic").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("parsed") &&
            it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE })
        for ((role, owner, type) in listOf(Triple("dom.children", "dom", ArrayList::class.java),
            Triple("parsed.children", "parsed", ArrayList::class.java), Triple("parsed.text", "parsed", String::class.java),
            Triple("common.tag", "common", String::class.java))) check(c.field(role).let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type(owner) && it.type == type
        })
        check(c.field("container.originalChildren").let { !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) &&
            it.declaringClass == c.type("container") && it.type == ArrayList::class.java })
        check(c.type("table").superclass == c.type("container").superclass)
        for (role in listOf("head", "body", "foot")) check(c.field("table.$role").let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("table") && it.type == c.type("container")
        })
        check(listOf("head", "body", "foot").map { c.field("table.$it") }.toSet().size == 3)
        check(c.method("document.body").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("document") &&
            it.parameterCount == 0 && it.returnType == c.type("container") })
        check(c.method("document.parse").let { Modifier.isStatic(it.modifiers) && it.returnType == c.type("container") &&
            it.parameterCount in 5..6 && it.parameterTypes.first() == c.type("sourceNode") &&
            it.parameterTypes[3] == String::class.java && it.parameterTypes[4] == java.lang.Boolean.TYPE &&
            (it.parameterCount == 5 || it.parameterTypes[5].isEnum) })
        check(v.getValue("value:document.callers").toInt() in 1..512)
        c.documentCallers()
        return c
    }
}
