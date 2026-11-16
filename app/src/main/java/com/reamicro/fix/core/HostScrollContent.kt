package com.reamicro.fix.core

import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

internal object HostScrollContent {
    data class Rendered(val paragraphs: List<Any>, val structured: Boolean)

    fun render(c: HostEpubSourceBindings.Contract, element: Any): Rendered {
        val dom = requireNotNull(HostHighlightDomBindings.current)
        fun property(name: String) = c.required("scroll.element.$name", element)
        val source = property("node")
        val id = property("id")
        val style = property("style")
        val constraints = property("constraints")
        val margins = c.required("scroll.constraints.margin", constraints)
        var width = c.field("scroll.constraints.maxWidth").getFloat(constraints) -
            c.field("scroll.margin.left").getFloat(margins) - c.field("scroll.margin.right").getFloat(margins)
        val structured = c.index("scroll.structured") == 1 && c.field("scroll.element.preserveStructure").getBoolean(element)
        if (!structured) for (edge in listOf("left", "right")) {
            val margin = (c.method("css.number").invoke(c.required("css.margin-$edge", style)) as Number).toFloat()
            width -= minOf(margin, 0f)
        }
        width = (c.method("scroll.px").invoke(null, width) as Number).toFloat()
        require(width.isFinite()) { "滚动排版宽度不可用" }
        if (structured) width = maxOf(width, 1f)
        require(width > 0f) { "滚动排版宽度尚未就绪" }
        val box = c.constructor("scroll.box").newInstance(width, width, emptyList<Any>())
        val root = if (structured) {
            val builder = requireNotNull(c.method("scroll.builder").invoke(id))
            c.parseBody(source, builder, property("styleSheets"), property("dir") as String,
                property("isFullScreen") as Boolean,
                if ("class:imageMode" in c.values) property("imagePageMode") else null)
                .also { c.method("scroll.measureContainer").invoke(it, box) }
        } else {
            val container = c.constructor("scroll.container").newInstance(source, id, style)
            val content = requireNotNull(c.method("scroll.create").invoke(null, id, style, container, false))
            @Suppress("UNCHECKED_CAST")
            (dom.field("container.originalChildren").get(container) as MutableList<Any>).add(content)
            val nodes = if (c.index("scroll.mediaRoot") == 1 && c.nodeName(source) in setOf("img", "image", "svg"))
                listOf(source) else c.children(source)
            for (node in nodes) c.method("scroll.buildNode").invoke(element, node)?.let {
                c.method("scroll.append").invoke(null, content, it)
            }
            c.method("scroll.measure").invoke(content, AtomicReference(box), null)
            container
        }
        val paragraphs = arrayListOf<Any>()
        val stack = ArrayDeque<Any>().apply { addLast(root) }
        val seen = IdentityHashMap<Any, Boolean>()
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            require(seen.put(node, true) == null && seen.size <= 65_536) { "滚动段落树异常" }
            if (dom.type("dom").isInstance(node)) paragraphs += node
            else dom.paragraphChildren(node).asReversed().forEach(stack::addLast)
        }
        return Rendered(paragraphs, structured)
    }

    fun validate(c: HostEpubSourceBindings.Contract) {
        val float = java.lang.Float.TYPE; val bool = java.lang.Boolean.TYPE; val int = Integer.TYPE
        fun field(role: String, owner: String, type: Class<*>) = check(c.field(role).let {
            !Modifier.isStatic(it.modifiers) && it.declaringClass == c.type(owner) && it.type == type
        })
        fun method(role: String, owner: Class<*>, params: List<Class<*>>, result: Class<*>, static: Boolean = false) = check(c.method(role).let {
            it.declaringClass == owner && Modifier.isStatic(it.modifiers) == static && it.parameterTypes.toList() == params && it.returnType == result
        })
        fun constructor(role: String, owner: Class<*>, params: List<Class<*>>) = check(c.constructor(role).let {
            it.declaringClass == owner && it.parameterTypes.toList() == params
        })
        for ((key, type) in listOf("id" to c.type("cfi"), "node" to c.type("element"), "style" to c.type("style"),
            "constraints" to c.type("scroll.constraints"), "styleSheets" to c.type("sheets"), "dir" to String::class.java, "isFullScreen" to bool))
            field("scroll.element.$key", "scroll.element", type)
        if ("class:imageMode" in c.values) field("scroll.element.imagePageMode", "scroll.element", c.type("imageMode"))
        field("scroll.constraints.maxWidth", "scroll.constraints", float)
        field("scroll.constraints.margin", "scroll.constraints", c.type("scroll.margin"))
        for (key in listOf("left", "top", "right", "bottom")) field("scroll.margin.$key", "scroll.margin", float)
        method("scroll.px", c.method("scroll.px").declaringClass, listOf(Number::class.java), float, true)
        constructor("scroll.container", c.type("container"), listOf(c.type("element"), c.type("cfi"), c.type("style")))
        constructor("scroll.box", c.type("scroll.box"), listOf(float, float, List::class.java))
        method("scroll.create", c.method("scroll.create").declaringClass, listOf(c.type("cfi"), c.type("style"), c.type("container"), bool), c.type("dom"), true)
        method("scroll.buildNode", c.type("scroll.element"), listOf(c.type("node")), c.type("parsed"))
        method("scroll.append", c.method("scroll.append").declaringClass, listOf(c.type("dom"), c.type("parsed")), Void.TYPE, true)
        method("scroll.measure", c.type("dom"), listOf(AtomicReference::class.java, c.type("container")), ArrayList::class.java)
        field("scroll.layout", "dom", c.type("scroll.layout"))
        check(c.type("layout") == c.type("scroll.layout"))
        field("scroll.paragraph", "scroll.layout", c.type("scroll.paragraph"))
        method("scroll.line", c.type("scroll.paragraph"), listOf(int), int)
        for (role in listOf("lineTop", "lineBottom")) method("scroll.$role", c.type("scroll.paragraph"), listOf(int), float)
        check(c.method("scroll.lineTop") != c.method("scroll.lineBottom"))
        check(c.index("scroll.structured") in 0..1 && c.index("scroll.mediaRoot") in 0..1)
        if (c.index("scroll.structured") == 1) {
            field("scroll.element.preserveStructure", "scroll.element", bool)
            method("scroll.builder", c.type("cfi"), emptyList(), c.type("builder"))
            method("scroll.measureContainer", c.type("container"), listOf(c.type("scroll.box")), Void.TYPE)
        }
        check(c.method("scroll.render").let { Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
            it.parameterTypes.getOrNull(c.index("scroll.element")) == c.type("scroll.element") })
        val capture = c.constructor("scroll.capture")
        check(capture.declaringClass == c.type("scroll.capture"))
        for ((role, type) in listOf("element" to c.type("scroll.element"), "controller" to c.type("controller"), "viewport" to c.type("scroll.viewport"))) {
            check(capture.parameterTypes.getOrNull(c.index("scroll.capture.$role")) == type)
            field("scroll.capture.$role", "scroll.capture", type)
        }
        method("scroll.capture", c.type("scroll.capture"), List(2) { Any::class.java }, Any::class.java)
        method("scroll.viewport", c.type("scroll.viewport"), emptyList(), Any::class.java)
        method("scroll.resolve", c.type("controller"), listOf(c.type("cfi")), c.type("endpoint"))
        method("scroll.glyph", c.type("layout"), listOf(int), c.type("rect"))
        HostScrollAnchorQueries.validate(c)
    }
}
