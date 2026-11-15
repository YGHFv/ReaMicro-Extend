package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier

internal object HostHighlightTextBindings {
    const val KEY = "reader.highlightText"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var descriptors: Map<String, String>? = null
    fun boundDescriptors() = descriptors?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; descriptors = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, false, HostAutoScanner.queriedDescriptors().orEmpty())
            current = restore(values, loader); descriptors = values
        } }.onFailure { HookRuntime.logError("ReaMicro highlight text contract unavailable", it) }
        current?.let { HostSymbols.installFeatureDescriptors(it.logicalDescriptors()) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>) = query(session, true, base)
    private fun query(session: HostQuerySession.Session, force: Boolean, base: Map<String, String>) =
        session.query(KEY, force, validate = { restore(it, session.loader) },
            find = { HostHighlightTextQueries.query(it, session.apk, base) }).values.toMap()

    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun type(role: String) = members.type(role)
        fun constructor(role: String) = members.constructor(role)
        fun index(role: String) = values.getValue("value:$role").toInt()
        fun text(value: Any): String = field("annotated.text").get(value) as String
        fun annotations(value: Any): List<Any> {
            val raw = field("annotated.annotations").get(value) as? List<*> ?: return emptyList()
            return raw.map { requireNotNull(it) }
        }
        fun annotated(text: String, ranges: List<Any>): Any {
            val args = arrayOfNulls<Any>(2)
            args[index("annotated.text")] = text
            args[index("annotated.annotations")] = ranges
            return constructor("annotated").newInstance(*args)
        }
        fun range(item: Any, start: Int, end: Int, tag: String): Any {
            val args = arrayOfNulls<Any>(4)
            args[index("range.item")] = if (item is String) constructor("string").newInstance(item) else item
            args[index("range.start")] = start
            args[index("range.end")] = end
            args[index("range.tag")] = tag
            return constructor("range").newInstance(*args)
        }
        fun span(color: Long, fontSize: Long?, family: Any?, background: Long?): Any {
            val args = arrayOfNulls<Any>(HostHighlightTextQueries.spanProperties.size)
            args[index("span.foreground")] = constructor("foreground").newInstance(color)
            args[index("span.fontSize")] = fontSize ?: 0L
            args[index("span.fontFamily")] = family
            args[index("span.letterSpacing")] = 0L
            args[index("span.background")] = background ?: 16L
            return constructor("span").newInstance(*args)
        }
        fun logicalDescriptors(): Map<String, String> = buildMap {
            put("field:androidx.compose.ui.text.AnnotatedString#text", this@Contract.values.getValue("field:annotated.text"))
            put("field:androidx.compose.ui.text.AnnotatedString#annotations", this@Contract.values.getValue("field:annotated.annotations"))
            put("field:androidx.compose.ui.text.StringAnnotation#value", this@Contract.values.getValue("field:string.value"))
            put("field:androidx.compose.foundation.text.modifiers.MultiParagraphLayoutCache#layoutCache", this@Contract.values.getValue("field:cache.layout"))
            put("field:androidx.compose.ui.text.TextLayoutResult#layoutInput", this@Contract.values.getValue("field:layout.input"))
            put("field:androidx.compose.ui.text.TextLayoutInput#text", this@Contract.values.getValue("field:input.text"))
        }
    }

    private fun restore(v: Map<String, String>, loader: ClassLoader): Contract {
        val required = listOf("annotated", "range", "span", "string", "composer", "foreground", "node", "layout", "input",
            "drawInterface", "paragraph", "rect", "modifier", "scope", "context", "canvasDraw", "canvas", "nativeCanvas").map { "class:$it" } +
            listOf("annotated", "range", "string", "span", "foreground").map { "constructor:$it" } +
            listOf("basic", "content", "unit", "draw", "layoutCache", "drawDirect", "lineForOffset", "lineEnd", "lineStart", "bounds", "rect", "invalidate", "canvas").map { "method:$it" } +
            listOf("basic.text", "basic.composer", "annotated.text", "annotated.annotations").map { "value:$it" } +
            listOf("annotated.text", "annotated.annotations", "string.value", "cache.layout", "layout.input", "input.text",
                "layout.paragraph", "paragraph.lineCount", "modifier.attached", "scope.draw", "draw.context", "canvas.native").map { "field:$it" } +
            HostHighlightTextQueries.rangeProperties.flatMap { listOf("field:range.$it", "value:range.$it") } +
            HostHighlightTextQueries.spanProperties.flatMap { listOf("field:span.$it", "value:span.$it") }
        check(v.keys.containsAll(required)) { "Incomplete highlight text contract: ${required - v.keys}" }
        val c = Contract(v, HostRuntimeBindings.Members(v, loader))
        for ((role, names) in listOf("range" to HostHighlightTextQueries.rangeProperties, "span" to HostHighlightTextQueries.spanProperties,
            "annotated" to listOf("text", "annotations"))) {
            val constructor = c.constructor(role)
            check(constructor.declaringClass == c.type(role) && constructor.parameterCount == names.size)
            check(names.map { c.index("$role.$it") }.toSet() == names.indices.toSet())
            for (name in names) check(c.field("$role.$name").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type(role) &&
                it.type == constructor.parameterTypes[c.index("$role.$name")] })
        }
        for ((name, type) in listOf("item" to Any::class.java, "start" to java.lang.Integer.TYPE, "end" to java.lang.Integer.TYPE, "tag" to String::class.java))
            check(c.field("range.$name").type == type)
        check(c.field("annotated.text").type == String::class.java && c.field("annotated.annotations").type == List::class.java)
        for (name in listOf("fontSize", "letterSpacing", "background")) check(c.field("span.$name").type == java.lang.Long.TYPE)
        check(HostHighlightTextQueries.spanProperties.filterNot { it in setOf("fontSize", "letterSpacing", "background") }.all { !c.field("span.$it").type.isPrimitive })
        check(c.constructor("foreground").let { it.declaringClass == c.type("foreground") && it.parameterTypes.toList() == listOf(java.lang.Long.TYPE) })
        check(c.field("span.foreground").type.isAssignableFrom(c.type("foreground")))
        check(c.constructor("string").let { it.declaringClass == c.type("string") && it.parameterTypes.toList() == listOf(String::class.java) })
        check(c.field("string.value").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("string") && it.type == String::class.java })
        val basic = c.method("basic")
        check(Modifier.isStatic(basic.modifiers) && basic.returnType == Void.TYPE)
        check(c.index("basic.text") in basic.parameterTypes.indices && basic.parameterTypes[c.index("basic.text")] == c.type("annotated"))
        check(c.index("basic.composer") in basic.parameterTypes.indices && basic.parameterTypes[c.index("basic.composer")] == c.type("composer"))
        check(basic.parameterTypes.getOrNull(c.index("basic.composer") + 1 + c.index("basic.text") / 10) == java.lang.Integer.TYPE)
        check(c.method("unit").let { Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() == listOf(java.lang.Float.TYPE, java.lang.Long.TYPE) && it.returnType == java.lang.Long.TYPE })
        check(c.method("draw").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("node") && it.parameterCount == 1 && it.returnType == Void.TYPE })
        check(c.method("layoutCache").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("node") && it.parameterCount == 1 })
        check(c.field("cache.layout").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.method("layoutCache").returnType && it.type == c.type("layout") })
        check(c.field("layout.input").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("layout") && it.type == c.type("input") })
        check(c.field("input.text").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("input") && it.type == c.type("annotated") })
        check(c.method("drawDirect").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.method("draw").parameterTypes.single() && it.returnType == Void.TYPE })
        fun field(role: String, owner: Class<*>, type: Class<*>) = check(c.field(role).let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == owner && it.type == type
        })
        fun method(role: String, owner: Class<*>?, args: List<Class<*>>, returns: Class<*>, static: Boolean = false) = check(c.method(role).let {
            Modifier.isStatic(it.modifiers) == static && (owner == null || it.declaringClass == owner) &&
                it.parameterTypes.toList() == args && it.returnType == returns
        })
        val int = java.lang.Integer.TYPE
        field("layout.paragraph", c.type("layout"), c.type("paragraph"))
        field("paragraph.lineCount", c.type("paragraph"), int)
        method("lineForOffset", c.type("paragraph"), listOf(int), int)
        method("lineEnd", c.type("paragraph"), listOf(int, java.lang.Boolean.TYPE), int)
        method("lineStart", c.type("layout"), listOf(int), int)
        method("bounds", c.type("layout"), listOf(int), c.type("rect"))
        method("rect", null, listOf(c.type("rect")), android.graphics.RectF::class.java, true)
        check(c.type("modifier").isAssignableFrom(c.type("node")))
        check(c.type("drawInterface").isAssignableFrom(c.type("node")))
        field("modifier.attached", c.type("modifier"), java.lang.Boolean.TYPE)
        method("invalidate", null, listOf(c.type("drawInterface")), Void.TYPE, true)
        check(c.method("draw").parameterTypes.single() == c.type("scope"))
        field("scope.draw", c.type("scope"), c.type("canvasDraw"))
        field("draw.context", c.type("canvasDraw"), c.type("context"))
        method("canvas", c.type("context"), emptyList(), c.type("canvas"))
        check(c.type("canvas").isAssignableFrom(c.type("nativeCanvas")))
        field("canvas.native", c.type("nativeCanvas"), android.graphics.Canvas::class.java)
        return c
    }
}
