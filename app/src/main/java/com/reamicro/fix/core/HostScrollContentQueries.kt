package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

/** Reconstruct the same content and constraints used by the native scroll composable. */
internal object HostScrollContentQueries {
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Scroll content $role is missing or ambiguous")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>, dom: Map<String, String>): Map<String, String> = buildMap {
        val flow = HostDexCalls(apk)
        fun known(owner: String, name: String) = requireNotNull(bridge.getMethodData(base.getValue("method:$owner#$name")))
        fun method(role: String, value: MethodData) = value.also { put("method:scroll.$role", it.descriptor) }
        fun type(role: String, logical: String) = requireNotNull(bridge.getClassData(base.getValue("class:$logical")))
            .also { put("class:scroll.$role", it.descriptor) }
        val element = type("element", "org.epub.element.model.EpubElement")
        val content = requireNotNull(bridge.getClassData(dom.getValue("class:dom")))
        val container = requireNotNull(bridge.getClassData(dom.getValue("class:container")))
        val parsed = requireNotNull(bridge.getClassData(dom.getValue("class:parsed")))
        val source = requireNotNull(bridge.getClassData(dom.getValue("class:sourceNode")))
        val sourceElement = requireNotNull(bridge.getClassData(dom.getValue("class:element")))
        val native = method("render", known("app.zhendong.reamicro.ui.reader.components.ScrollPagerKt", "ScrollPagerElement"))
        check(Modifier.isStatic(native.modifiers))
        put("value:scroll.element", native.paramTypeNames.indices.single { native.paramTypeNames[it] == element.name }.toString())
        val controller = requireNotNull(known("org.epub.ui.EpubSelectionController", "beginSelection-k-4lQ0M").declaredClass)
        val viewport = type("viewport", "kotlin.jvm.functions.Function0")
        method("viewport", viewport.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramCount == 0 && it.returnTypeName == "java.lang.Object" }.one("viewport callback"))
        val capture = native.invokes.filter { it.isConstructor && it.paramTypeNames.count { p -> p == element.name } == 1 &&
            it.paramTypeNames.count { p -> p == controller.name } == 1 && content.name in it.paramTypeNames }.one("element controller capture")
        put("class:scroll.capture", requireNotNull(capture.declaredClass).descriptor)
        put("constructor:scroll.capture", capture.descriptor)
        for ((role, type) in listOf("element" to element, "controller" to controller, "viewport" to viewport)) {
            val slot = capture.paramTypeNames.indices.single { capture.paramTypeNames[it] == type.name }
            put("value:scroll.capture.$role", slot.toString())
            put("field:scroll.capture.$role", flow.writes(capture.descriptor).single {
                it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(slot + 1) }.field)
        }
        method("capture", capture.declaredClass!!.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == List(2) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object" }.one("render capture invocation"))
        for (key in listOf("id", "node", "style", "constraints", "styleSheets", "dir", "isFullScreen"))
            put("field:scroll.element.$key", base.getValue("field:org.epub.element.model.EpubElement#$key"))
        if (known("org.epub.html.node.BodyDom", "parseBody").paramCount == 6) {
            put("field:scroll.element.imagePageMode", base.getValue("field:org.epub.element.model.EpubElement#imagePageMode"))
        }
        val cfi = requireNotNull(bridge.getFieldData(getValue("field:scroll.element.id"))).type
        val endpoint = requireNotNull(bridge.getClassData(base.getValue("class:org.epub.ui.SelectionPosition")))
        method("resolve", controller.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf(cfi.name) && it.returnTypeName == endpoint.name }.one("selection position for CFI"))
        method("glyph", known("androidx.compose.ui.text.TextLayoutResult", "getBoundingBox"))
        val style = requireNotNull(bridge.getFieldData(getValue("field:scroll.element.style"))).type
        val constraints = requireNotNull(bridge.getFieldData(getValue("field:scroll.element.constraints"))).type
        put("class:scroll.constraints", constraints.descriptor)
        val records = HostRecordQueries.query(apk, mapOf("class:constraints" to constraints.descriptor), allowPartial = true)
        for (key in listOf("maxWidth", "margin")) put("field:scroll.constraints.$key", records.getValue("field:constraints#$key"))
        val margin = requireNotNull(bridge.getFieldData(getValue("field:scroll.constraints.margin"))).type
        put("class:scroll.margin", margin.descriptor)
        val edges = HostRecordQueries.query(apk, mapOf("class:margin" to margin.descriptor))
        for (edge in listOf("left", "top", "right", "bottom")) put("field:scroll.margin.$edge", edges.getValue("field:margin#$edge"))
        method("px", native.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("java.lang.Number") &&
            it.returnTypeName == "float" }.one("density conversion"))
        val containerCtor = container.methods.filter { it.isConstructor &&
            it.paramTypeNames == listOf(sourceElement.name, cfi.name, style.name) }.one("container constructor")
        check(native.invokes.any { it.descriptor == containerCtor.descriptor })
        put("constructor:scroll.container", containerCtor.descriptor)
        method("create", native.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == content.name &&
            it.paramTypeNames == listOf(cfi.name, style.name, container.name, "boolean") }.one("content factory"))
        method("buildNode", native.invokes.filter { !Modifier.isStatic(it.modifiers) && it.declaredClassName == element.name &&
            it.paramTypeNames == listOf(source.name) && it.returnTypeName == parsed.name }.one("parsed source node"))
        method("append", native.invokes.filter { Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
            it.paramTypeNames == listOf(content.name, parsed.name) }.one("append parsed child"))
        method("measure", known("org.epub.html.node.ContentDom", "onMeasure"))
        val box = type("box", "org.epub.html.BoxConstraints")
        val boxCtor = native.invokes.filter { it.isConstructor && it.declaredClassName == box.name &&
            it.paramTypeNames == listOf("float", "float", "java.util.List") }.one("measure constraints")
        put("constructor:scroll.box", boxCtor.descriptor)
        val layout = type("layout", "androidx.compose.ui.text.TextLayoutResult")
        put("field:scroll.layout", content.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == layout.name }.descriptor)
        val preserve = base["field:org.epub.element.model.EpubElement#preserveStructure"]
        put("value:scroll.structured", (if (preserve == null) 0 else 1).toString())
        if (preserve != null) {
            check(native.usingFields.any { it.field.descriptor == preserve })
            put("field:scroll.element.preserveStructure", preserve)
            val parse = known("org.epub.html.node.BodyDom", "parseBody")
            val builder = parse.paramTypes[1]
            method("builder", native.invokes.filter { !Modifier.isStatic(it.modifiers) && it.declaredClassName == cfi.name &&
                it.paramCount == 0 && it.returnTypeName == builder.name }.one("CFI builder"))
            method("measureContainer", native.invokes.filter { !Modifier.isStatic(it.modifiers) && it.declaredClassName == container.name &&
                it.paramTypeNames == listOf(box.name) && it.returnTypeName == "void" }.one("container measure"))
        }
        val mediaTags = native.usingStrings.filter { it in setOf("img", "image", "svg") }.toSet()
        check(mediaTags.isEmpty() || mediaTags == setOf("img", "image", "svg"))
        put("value:scroll.mediaRoot", (if (mediaTags.isEmpty()) 0 else 1).toString())
        // A virtual slot reaches Android Layout directly or through AndroidParagraph.
        val paragraph = type("paragraph", "androidx.compose.ui.text.MultiParagraph")
        put("field:scroll.paragraph", layout.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == paragraph.name }.descriptor)
        method("line", known("androidx.compose.ui.text.MultiParagraph", "getLineForOffset"))
        fun reaches(m: MethodData, target: String, seen: Set<String> = emptySet()): Boolean =
            m.descriptor !in seen && seen.size < 4 && m.invokes.any { it.descriptor == target ||
                (it.paramTypeNames == listOf("int") && it.returnTypeName in setOf("float", "int") && reaches(it, target, seen + m.descriptor)) }
        for ((role, name) in listOf("lineTop" to "getLineTop", "lineBottom" to "getLineBottom")) method(role,
            paragraph.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("int") &&
                it.returnTypeName == "float" && reaches(it, "Landroid/text/Layout;->$name(I)I") }.one(role))
        // Prove this is the native path that appends and measures the content factory result.
        val factory = getValue("method:scroll.create")
        check(flow.calls(native.descriptor).any { it.descriptor == getValue("method:scroll.measure") &&
            (it.arguments.firstOrNull() as? HostDexCalls.Call)?.descriptor == factory })
        putAll(HostScrollAnchorQueries.query(bridge, apk, base, native, preserve != null))
    }
}
