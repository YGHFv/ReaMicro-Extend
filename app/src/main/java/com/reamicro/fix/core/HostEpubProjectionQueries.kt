package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** Rendered node and CSS members needed to prove source edits against the native reading flow. */
internal object HostEpubProjectionQueries {
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("EPUB projection $role is missing or ambiguous")
    private fun ClassData.isA(name: String): Boolean = this.name == name || interfaces.any { it.isA(name) } || superClass?.isA(name) == true
    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>, dom: Map<String, String>): Map<String, String> = buildMap {
        val flow = HostDexCalls(apk)
        fun known(role: String, owner: String, name: String) = requireNotNull(bridge.getMethodData(base.getValue("method:$owner#$name")))
            .also { put("method:$role", it.descriptor) }
        val parsed = requireNotNull(bridge.getClassData(dom.getValue("class:parsed")))
        val source = requireNotNull(bridge.getClassData(dom.getValue("class:sourceNode")))
        put("class:parsed", parsed.descriptor)
        put("field:parsed.source", parsed.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == source.name }.descriptor)
        val styleField = requireNotNull(bridge.getFieldData(base.getValue("field:org.epub.html.node.NodeDom#style")))
        val style = styleField.type
        put("class:style", style.descriptor); put("field:parsed.style", styleField.descriptor)
        val location = requireNotNull(bridge.getMethodData(base.getValue("method:org.epub.html.EpubCFI\$Companion#create"))).returnTypeName
        val common = requireNotNull(parsed.superClass)
        put("class:common", common.descriptor)
        // Dispatch through the native node contract, including placeholders that have
        // both start and end CFI fields. A field-type guess cannot distinguish those.
        put("method:location", common.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramCount == 0 && it.returnTypeName == location }.one("native node location").descriptor)
        for (role in listOf("dom", "container", "table")) {
            val owner = requireNotNull(bridge.getClassData(dom.getValue("class:$role")))
            check(owner.isA(common.name))
            put("class:$role", owner.descriptor)
        }
        known("parsed.atomic", "org.epub.html.node.NodeDom", "getRequiresInlineAtomicBox")
        val number = known("css.number", "org.epub.css.property.CSSProperty", "getNumber")
        val property = requireNotNull(number.declaredClass)
        put("class:cssProperty", property.descriptor)
        val actual = number.invokes.filter { !Modifier.isStatic(it.modifiers) && it.declaredClassName == property.name &&
            it.paramCount == 0 && !it.returnTypeName.startsWith("java.") && it.returnTypeName != "void" }.one("CSS actual value")
        put("method:css.actual", actual.descriptor)
        put("class:cssValue", requireNotNull(actual.returnType).descriptor)
        val labels = listOf("padding-left", "padding-right", "margin-left", "margin-top", "margin-right", "border-left-style", "border-right-style",
            "border-left-width", "border-right-width", "background-color", "background-image", "writing-mode", "white-space")
        val build = requireNotNull(bridge.getMethodData(base.getValue("method:org.epub.html.node.ContentDom#buildContent")))
        for (label in labels) {
            val ctor = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(label))).filter {
                it.isConstructor && it.paramCount == 1 && it.declaredClass?.isA(property.name) == true
            }.one("CSS $label")
            val type = requireNotNull(ctor.declaredClass)
            put("class:css.$label", type.descriptor)
            val field = style.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == type.name }
            put("field:css.$label", field.descriptor)
            if (label.endsWith("-style")) {
                put("method:css.$label.valid", type.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
                    it.paramCount == 0 && it.returnTypeName == "boolean" }.one("$label valid").descriptor)
            } else if (label.startsWith("background-")) {
                val valid = type.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "boolean" &&
                    build.usingFields.any { f -> f.field.descriptor == it.descriptor } }
                check(flow.writes(ctor.descriptor).any { it.field == valid.descriptor })
                put("field:css.$label.valid", valid.descriptor)
            }
        }
        putAll(HostHighlightProjectionQueries.query(bridge, flow, build, parsed, source, dom.getValue("class:element"), this))
        put("class:horizontal", base.getValue("class:org.epub.css.property.WritingMode\$HorizontalTB"))
        val begin = known("selection.begin", "org.epub.ui.EpubSelectionController", "beginSelection-k-4lQ0M")
        val controller = requireNotNull(begin.declaredClass)
        put("class:controller", controller.descriptor)
        put("field:selection.nodes", controller.fields.single { !Modifier.isStatic(it.modifiers) && it.type.isA("java.util.Map") }.descriptor)
        for ((role, logical, names) in listOf(
            Triple("snapshot", "org.epub.ui.SelectionSnapshot", listOf("anchor", "focus")),
            Triple("endpoint", "org.epub.ui.SelectionPosition", listOf("nodeId", "offset")),
            Triple("selectable", "org.epub.ui.SelectableTextNode", listOf("id", "cfi", "text", "boundsInWindow", "textLayoutResult")),
        )) {
            put("class:$role", base.getValue("class:$logical"))
            for (name in names) put("field:$role.$name", base.getValue("field:$logical#$name"))
        }
        base["field:org.epub.ui.SelectableTextNode#scrollBoundsInWindow"]?.let {
            put("field:selectable.scrollBoundsInWindow", it)
        }
        val snapshot = requireNotNull(bridge.getClassData(getValue("class:snapshot")))
        for ((role, field) in listOf("annotated" to "text", "layout" to "textLayoutResult", "rect" to "boundsInWindow"))
            put("class:$role", requireNotNull(bridge.getFieldData(getValue("field:selectable.$field"))).type.descriptor)
        put("method:selection.get", controller.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
            it.returnTypeName == snapshot.name }.one("selection snapshot").descriptor)
        known("rootStyle", "org.epub.UIEpubWindow", "getRootStyle")
    }
}
