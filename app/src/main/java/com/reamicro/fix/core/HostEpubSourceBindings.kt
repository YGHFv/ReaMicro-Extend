package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime
import java.io.StringReader
import java.lang.reflect.Modifier

internal object HostEpubSourceBindings {
    const val KEY = "reader.epubSource"
    @Volatile var current: Contract? = null; private set
    @Volatile var initializationAttempted = false; private set
    @Volatile private var snapshot: Map<String, String>? = null
    fun boundDescriptors() = snapshot?.toMap()
    fun initialize(loader: ClassLoader, apk: String, dataDir: String) {
        initializationAttempted = true; current = null; snapshot = null
        runCatching { HostQuerySession.use(loader, apk, dataDir) { session ->
            val values = query(session, HostAutoScanner.queriedDescriptors().orEmpty(),
                requireNotNull(HostHighlightDomBindings.boundDescriptors()), false)
            current = restore(values, loader); snapshot = values
        } }.onFailure { HookRuntime.logError("ReaMicro EPUB source query unavailable", it) }
    }
    fun rescan(session: HostQuerySession.Session, base: Map<String, String>, dom: Map<String, String>) = query(session, base, dom, true)
    private fun query(session: HostQuerySession.Session, base: Map<String, String>, dom: Map<String, String>, force: Boolean) =
        session.query(KEY, force, validate = { restore(it, session.loader) },
            find = { HostEpubSourceQueries.query(it, session.apk, base, dom) }).values.toMap()

    class Contract internal constructor(val values: Map<String, String>, private val members: HostRuntimeBindings.Members) {
        fun type(role: String) = members.type(role)
        fun method(role: String) = members.method(role)
        fun field(role: String) = members.field(role)
        fun constructor(role: String) = members.constructor(role)
        fun index(role: String) = values.getValue("value:$role").toInt()
        fun value(role: String, target: Any): Any? = field(role).get(target)
        fun required(role: String, target: Any): Any = requireNotNull(value(role, target)) { "Missing EPUB $role" }
        fun parse(source: String, baseUri: String): Any {
            val tree = constructor("htmlTree").newInstance()
            val parser = constructor("parser").newInstance(tree)
            val lock = required("parser.lock", parser)
            return synchronized(lock) { requireNotNull(method("parse").invoke(tree, StringReader(source), baseUri, parser)) }
        }
        fun children(node: Any): List<Any> = (method("node.children").invoke(node) as List<*>).filterNotNull()
        fun nodeName(node: Any): String = method("node.name").invoke(node) as String
        fun text(node: Any): String = method("text.normalized").invoke(node) as String
        fun wholeText(node: Any): String = method("text.whole").invoke(node) as String
        fun body(document: Any): Any = requireNotNull(method("source.body").invoke(document))
        fun cfi(raw: String): Any = requireNotNull(method("cfi.create").invoke(null, raw))
        fun compare(left: Any, right: Any) = method("cfi.compare").invoke(left, right) as Int
        fun step(index: Int, assertion: Any? = null): Any = record("step", mapOf("index" to index, "assertion" to assertion))
        fun offset(index: Int, offset: Int): Any = record("offset", mapOf("index" to index, "offset" to offset))
        fun bias(before: Boolean): Any = record("bias", mapOf("before" to before, "assertion" to ""))
        fun record(role: String, properties: Map<String, Any?>): Any {
            val args = arrayOfNulls<Any>(constructor(role).parameterCount)
            check(properties.size == args.size)
            for ((key, value) in properties) args[index("$role.$key")] = value
            return constructor(role).newInstance(*args)
        }
        fun stepIndex(step: Any) = field("step.index").getInt(step)
        fun cfiSteps(cfi: Any) = (required("cfi.steps", cfi) as List<*>).map { stepIndex(requireNotNull(it)) }
        fun hasOffset(cfi: Any) = value("cfi.offset", cfi) != null
        fun offsetIndex(cfi: Any) = value("cfi.offset", cfi)?.let { field("offset.index").getInt(it) } ?: 1
        fun offsetValue(cfi: Any) = value("cfi.offset", cfi)?.let { field("offset.offset").getInt(it) } ?: 0
        fun biasBefore(cfi: Any): Boolean? = value("cfi.bias", cfi)?.let { field("bias.before").getBoolean(it) }
        fun rootStyle(): Any = requireNotNull(method("rootStyle").invoke(null))
        fun location(node: Any): Any = requireNotNull(method("location").invoke(node))
        fun horizontal(node: Any): Boolean {
            val style = required("parsed.style", node)
            val mode = required("css.writing-mode", style)
            return type("horizontal").isInstance(method("css.actual").invoke(mode))
        }
        fun decorated(node: Any): Boolean {
            if (method("parsed.atomic").invoke(node) == true) return false
            val style = required("parsed.style", node)
            if (listOf("padding-left", "padding-right", "margin-left", "margin-right").any {
                val actual = requireNotNull(method("css.actual").invoke(required("css.$it", style)))
                (method("css.rawNumber").invoke(actual) as Number).toFloat() != 0f
            }) return true
            if (listOf("border-left-style", "border-right-style").any {
                method("css.$it.valid").invoke(required("css.$it", style)) == true
            }) return true
            return listOf("background-color", "background-image").any {
                field("css.$it.valid").getBoolean(required("css.$it", style))
            }
        }
        fun inlineReplacement(): String = values.getValue("text:inline.replacement")
        fun decorationSides(node: Any): Pair<Boolean, Boolean> {
            if (!decorated(node)) return false to false
            val style = required("parsed.style", node)
            fun width(side: String): Float = listOf("padding-$side", "margin-$side", "border-$side-width")
                .map { (method("css.number").invoke(required("css.$it", style)) as Number).toFloat() }
                .fold(0f, Float::plus)
            return (width("left") > 0f) to (width("right") > 0f)
        }
        fun endsInlineLine(node: Any): Boolean =
            method("css.actual").invoke(required("css.white-space", required("parsed.style", node))) == field("css.nowrap").get(null)

        data class NoteProjection(val placeholder: Boolean, val expanded: Boolean, val skipChildren: Set<Any>)
        fun noteProjection(node: Any, tag: String, nodes: List<Any>): NoteProjection? {
            val source = required("parsed.source", node)
            fun has(name: String) = method("node.hasAttr").invoke(source, name) == true
            fun attr(name: String) = (method("node.attr").invoke(source, name) as String).trim()
            val note = when {
                tag == "a" && has("title") && attr("href").substringBefore('#').substringAfterLast('/')
                    .lowercase(java.util.Locale.ROOT) !in setOf("nav.xhtml", "nav.html", "toc.ncx") -> attr("title")
                has("data-wr-footernote") -> attr("data-wr-footernote")
                else -> return null
            }
            val resolved = method("note.resolve").invoke(null, node, note) as String
            val text = if (resolved.startsWith("file:")) "" else resolved
            fun normalized(value: String) = method("note.normalize").invoke(null, value) as String
            val expected = normalized(text)
            fun matches(element: Any) = text.isNotBlank() && type("element").isInstance(element) &&
                normalized(method("element.text").invoke(element) as String) == expected
            val duplicate = type("element").isInstance(source) && children(source).any(::matches)
            val placeholder = !duplicate && method("note.hasBackground").invoke(null, node) != true &&
                method("note.hasImage").invoke(null, node) != true
            val skip = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            nodes.filter { matches(required("parsed.source", it)) }.forEach(skip::add)
            return NoteProjection(placeholder, duplicate, skip)
        }
    }

    private fun restore(v: Map<String, String>, loader: ClassLoader): Contract {
        val c = Contract(v, HostRuntimeBindings.Members(v, loader))
        val string = String::class.java; val int = java.lang.Integer.TYPE; val bool = java.lang.Boolean.TYPE
        fun field(role: String, owner: String, type: Class<*>) = check(c.field(role).let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type(owner) && it.type == type
        })
        fun method(role: String, owner: Class<*>, params: List<Class<*>>, result: Class<*>, static: Boolean = false) = check(c.method(role).let {
            Modifier.isStatic(it.modifiers) == static && it.declaringClass == owner && it.parameterTypes.toList() == params && it.returnType == result
        })
        check(c.type("tree").isAssignableFrom(c.type("htmlTree")))
        check(c.constructor("htmlTree").let { it.declaringClass == c.type("htmlTree") && it.parameterCount == 0 })
        check(c.constructor("parser").let { it.declaringClass == c.type("parser") && it.parameterTypes.toList() == listOf(c.type("tree")) })
        field("parser.lock", "parser", Any::class.java)
        method("parse", c.type("tree"), listOf(StringReader::class.java, string, c.type("parser")), c.type("sourceDocument"))
        check(c.type("node").isAssignableFrom(c.type("element")) && c.type("element").isAssignableFrom(c.type("sourceDocument")))
        check(c.type("node").isAssignableFrom(c.type("textNode")))
        method("source.body", c.type("sourceDocument"), emptyList(), c.type("element"))
        method("node.name", c.type("node"), emptyList(), string)
        method("node.children", c.type("node"), emptyList(), List::class.java)
        method("node.hasAttr", c.type("node"), listOf(string), bool)
        method("text.normalized", c.type("textNode"), emptyList(), string)
        check(c.method("text.whole").let { !Modifier.isStatic(it.modifiers) && it.declaringClass.isAssignableFrom(c.type("textNode")) &&
            it.parameterCount == 0 && it.returnType == string })
        method("normaliseWhitespace", c.method("normaliseWhitespace").declaringClass, listOf(string), string, true)
        method("cfi.create", c.method("cfi.create").declaringClass, listOf(string), c.type("cfi"), true)
        method("cfi.text", c.type("cfi"), emptyList(), string)
        method("cfi.compare", c.type("cfi"), listOf(c.type("cfi")), int)
        for (role in listOf("spine", "item", "body")) field("cfi.$role", "cfi", c.type("step"))
        field("cfi.steps", "cfi", List::class.java)
        field("cfi.offset", "cfi", c.type("offset")); field("cfi.bias", "cfi", c.type("bias"))
        field("step.index", "step", int); field("step.assertion", "step", c.type("assertion"))
        field("offset.index", "offset", int); field("offset.offset", "offset", int)
        field("bias.before", "bias", bool); field("bias.assertion", "bias", string)
        for ((role, properties) in mapOf("cfi" to listOf("spine", "item", "body", "steps", "offset", "bias"),
            "step" to listOf("index", "assertion"), "offset" to listOf("index", "offset"), "bias" to listOf("before", "assertion"))) {
            val ctor = c.constructor(role)
            check(ctor.declaringClass == c.type(role) && ctor.parameterCount == properties.size)
            check(properties.map { c.field("$role.$it") }.toSet().size == properties.size)
            val slots = properties.map { name -> c.index("$role.$name").also {
                check(it in ctor.parameterTypes.indices && ctor.parameterTypes[it] == c.field("$role.$name").type)
            } }
            check(slots.toSet().size == properties.size)
        }
        method("node.step", c.method("node.step").declaringClass, listOf(c.type("node")), c.type("step"), true)
        method("node.steps", c.method("node.steps").declaringClass, listOf(c.type("node")), List::class.java, true)
        field("range.start", "range", c.type("cfi")); field("range.end", "range", c.type("cfi"))
        check(c.field("range.start") != c.field("range.end"))
        method("range.text", c.type("range"), emptyList(), string)
        check(c.constructor("range").let { it.declaringClass == c.type("range") && it.parameterTypes.toList() == listOf(c.type("cfi"), c.type("cfi")) })
        field("document.builder", "document", c.type("builder")); field("document.sheets", "document", c.type("sheets"))
        field("document.directory", "document", string); field("sheets.rules", "sheets", List::class.java)
        check(c.constructor("sheets").let { it.declaringClass == c.type("sheets") && it.parameterTypes.toList() == listOf(List::class.java) })
        check(c.method("body.parse").let { Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() ==
            listOf(c.type("node"), c.type("builder"), c.type("sheets"), string, bool) &&
            it.returnType == c.type("container") })
        method("document.fullScreen", c.type("document"), emptyList(), bool)
        method("builder.build", c.type("builder"), emptyList(), c.type("cfi"))
        method("builder.copy", c.type("builder"), emptyList(), c.type("builder"))
        check(c.field("builder.body").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("builder") &&
            it.type.isAssignableFrom(c.type("step")) })
        field("parsed.source", "parsed", c.type("node")); field("parsed.style", "parsed", c.type("style"))
        for (role in listOf("parsed", "dom", "container", "table")) check(c.type("common").isAssignableFrom(c.type(role)))
        method("location", c.type("common"), emptyList(), c.type("cfi"))
        method("parsed.atomic", c.type("parsed"), emptyList(), bool)
        method("css.number", c.type("cssProperty"), emptyList(), java.lang.Float.TYPE)
        method("css.actual", c.type("cssProperty"), emptyList(), c.type("cssValue"))
        check(c.method("css.rawNumber").let { !Modifier.isStatic(it.modifiers) &&
            c.type("cssValue").isAssignableFrom(it.declaringClass) && it.parameterCount == 0 && it.returnType == java.lang.Float.TYPE })
        check(c.type("cssValue").isAssignableFrom(c.type("horizontal")))
        for (label in listOf("padding-left", "padding-right", "margin-left", "margin-top", "margin-right", "border-left-style", "border-right-style",
            "border-left-width", "border-right-width", "background-color", "background-image", "writing-mode", "white-space")) {
            field("css.$label", "style", c.type("css.$label"))
            check(c.type("cssProperty").isAssignableFrom(c.type("css.$label")))
            if (label.endsWith("-style")) method("css.$label.valid", c.type("css.$label"), emptyList(), bool)
            if (label.startsWith("background-")) field("css.$label.valid", "css.$label", bool)
        }
        check(c.inlineReplacement().length == 1)
        check(c.field("css.nowrap").let { Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) &&
            c.type("cssValue").isAssignableFrom(it.type) })
        val modes = (0..1).map { c.field("inline.mode.$it") }
        check(modes.all { Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) } && modes[0].type != modes[1].type)
        for (role in listOf("note.hasImage", "note.hasBackground")) method(role, c.type("dom"), listOf(c.type("parsed")), bool, true)
        method("note.normalize", c.type("dom"), listOf(string), string, true)
        method("note.resolve", c.type("dom"), listOf(c.type("parsed"), string), string, true)
        method("element.text", c.type("element"), emptyList(), string)
        method("node.attr", c.type("node"), listOf(string), string)
        check(c.field("selection.nodes").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("controller") &&
            Map::class.java.isAssignableFrom(it.type) })
        field("snapshot.anchor", "snapshot", c.type("endpoint")); field("snapshot.focus", "snapshot", c.type("endpoint"))
        field("endpoint.nodeId", "endpoint", string); field("selectable.id", "selectable", string)
        field("endpoint.offset", "endpoint", int)
        field("selectable.cfi", "selectable", c.type("cfi"))
        field("selectable.text", "selectable", c.type("annotated"))
        field("selectable.textLayoutResult", "selectable", c.type("layout"))
        field("selectable.boundsInWindow", "selectable", c.type("rect"))
        if ("field:selectable.scrollBoundsInWindow" in v) field("selectable.scrollBoundsInWindow", "selectable", c.type("rect"))
        method("selection.get", c.type("controller"), emptyList(), c.type("snapshot"))
        check(c.method("selection.begin").let { !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type("controller") &&
            it.parameterTypes.toList() == listOf(java.lang.Long.TYPE) && it.returnType == bool })
        method("rootStyle", c.method("rootStyle").declaringClass, emptyList(), c.type("style"), true)
        HostScrollContent.validate(c)
        return c
    }
}
