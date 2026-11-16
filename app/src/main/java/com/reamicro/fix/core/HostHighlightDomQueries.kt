package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/** Paragraph order follows the native parser's logical tree, before pagination replaces nodes. */
internal object HostHighlightDomQueries {
    private fun List<MethodData>.one(role: String): MethodData = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Highlight DOM $role is missing or ambiguous")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val flow = HostDexCalls(apk)
        fun method(role: String, value: MethodData) { out["method:$role"] = value.descriptor }
        fun knownMethod(owner: String, name: String) = requireNotNull(bridge.getMethodData(base.getValue("method:$owner#$name")))
        val domName = "org.epub.html.node.ContentDom"
        val containerName = "org.epub.html.node.ContainerDom"
        val dom = requireNotNull(bridge.getClassData(base.getValue("class:$domName")))
        val container = requireNotNull(bridge.getClassData(base.getValue("class:$containerName")))
        val annotated = requireNotNull(bridge.getClassData(base.getValue("class:androidx.compose.ui.text.AnnotatedString")))
        out["class:dom"] = dom.descriptor
        out["class:container"] = container.descriptor
        out["class:annotated"] = annotated.descriptor
        val parent = requireNotNull(bridge.getFieldData(base.getValue("field:$domName#parent")))
        val elementField = requireNotNull(bridge.getFieldData(base.getValue("field:$containerName#element")))
        check(parent.declaredClassName == dom.name && parent.typeName == container.name)
        check(elementField.declaredClassName == container.name)
        out["field:parent"] = parent.descriptor
        out["field:element"] = elementField.descriptor
        val element = elementField.type
        val node = requireNotNull(element.superClass)
        out["class:element"] = element.descriptor
        out["class:sourceNode"] = node.descriptor
        val content = dom.fields.filter { !Modifier.isStatic(it.modifiers) && it.typeName == annotated.name }.single()
        out["field:content"] = content.descriptor

        val boundary = knownMethod(domName, "computeIsDialogueContinuationParagraph\$hasDialogueBoundary")
        check(Modifier.isStatic(boundary.modifiers) && boundary.paramTypeNames == listOf(dom.name, "boolean") && boundary.returnTypeName == "boolean")
        method("boundary", boundary)
        val text = boundary.invokes.filter {
            !Modifier.isStatic(it.modifiers) && it.declaredClassName == element.name && it.paramCount == 0 && it.returnTypeName == "java.lang.String"
        }.one("source text")
        method("text", text)
        val siblings = boundary.invokes.filter {
            !Modifier.isStatic(it.modifiers) && it.declaredClassName == node.name && it.paramCount == 0 && it.returnTypeName == element.name
        }.distinctBy { it.descriptor }
        check(siblings.size == 2)
        val neighbors = siblings.associateWith { sibling -> sibling.invokes.filter {
            !Modifier.isStatic(it.modifiers) && it.declaredClassName == node.name && it.paramCount == 0 && it.returnTypeName == node.name
        }.one("source neighbor") }
        // The two leaf methods index the same parent's child list at siblingIndex +/- 1.
        // Arithmetic immediate evidence distinguishes direction without a name or method-order hint.
        val sourceParent = node.fields.filter { !Modifier.isStatic(it.modifiers) && it.typeName == element.name }.single()
        check(neighbors.values.all { m -> m.usingFields.any { it.field.descriptor == sourceParent.descriptor } })
        val children = neighbors.values.flatMap { m -> m.invokes.filter {
            it.declaredClassName == element.name && it.paramCount == 0 && it.returnTypeName == "java.util.List"
        } }.one("source children")
        check(neighbors.values.all { m -> m.invokes.any { it.descriptor == children.descriptor } })
        out["field:source.parent"] = sourceParent.descriptor
        method("source.children", children)
        // The text node's literal name identifies the virtual node-name slot. Resolve the
        // element override of that slot instead of assuming a jsoup/R8 method name.
        val textNodeName = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings("#text")))
            .filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "java.lang.String" }
            .one("text node name")
        var textAncestor = textNodeName.declaredClass?.superClass
        while (textAncestor != null && textAncestor.name != node.name) textAncestor = textAncestor.superClass
        check(textAncestor?.name == node.name)
        val tag = element.methods.filter { !Modifier.isStatic(it.modifiers) && it.name == textNodeName.name &&
            it.paramCount == 0 && it.returnTypeName == "java.lang.String" }.one("source element name")
        method("source.tag", tag)
        for ((role, step) in listOf("previous" to -1L, "next" to 1L)) {
            val candidate = neighbors.entries.single { (_, leaf) ->
                leaf.usingNumbers.any { it.opCode in setOf(0xd0, 0xd8) && it.longValue() == step } &&
                    leaf.usingNumbers.none { it.opCode in setOf(0xd0, 0xd8) && it.longValue() == -step }
            }
            method(role, candidate.key)
        }

        val onMeasure = knownMethod(domName, "onMeasure")
        method("onMeasure", onMeasure)
        val containerMeasure = knownMethod(containerName, "onMeasure")
        check(!Modifier.isStatic(containerMeasure.modifiers) && containerMeasure.declaredClassName == container.name &&
            containerMeasure.paramCount == 2 && containerMeasure.returnTypeName == "void" &&
            containerMeasure.paramTypeNames.last() == "java.util.concurrent.atomic.AtomicReference")
        method("container.measure", containerMeasure)
        val measure = dom.methods.filter {
            !Modifier.isStatic(it.modifiers) && it.isMethod && it.returnTypeName == "void" &&
                it.paramTypeNames == listOf("java.util.concurrent.atomic.AtomicReference", "int", "java.util.ArrayList") &&
                it.usingStrings.containsAll(listOf("androidx.compose.foundation.text.inlineContent", "dialogue", "role-name", "role-default-emphasis")) &&
                onMeasure.invokes.any { called -> called.descriptor == it.descriptor }
        }.one("adaptive measure")
        method("measure", measure)
        out["value:round"] = measure.paramTypeNames.indices.single { measure.paramTypeNames[it] == "int" }.toString()

        val parsed = requireNotNull(bridge.getClassData(base.getValue("class:org.epub.html.node.NodeDom")))
        val common = requireNotNull(parsed.superClass)
        out["class:parsed"] = parsed.descriptor
        out["class:common"] = common.descriptor
        method("parsed.atomic", knownMethod("org.epub.html.node.NodeDom", "getRequiresInlineAtomicBox"))
        val append = dom.methods.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(dom.name, parsed.name) &&
            it.returnTypeName == "void" && it.invokes.any { call -> call.descriptor == "Ljava/util/ArrayList;->add(ILjava/lang/Object;)V" } }.one("append parsed child")
        val domChildren = flow.calls(append.descriptor).filter { it.descriptor == "Ljava/util/ArrayList;->add(ILjava/lang/Object;)V" &&
            it.arguments.getOrNull(2) == HostDexCalls.Parameter(1) }.map { it.arguments.first() as HostDexCalls.Field }.distinct().single()
        check(domChildren.descriptor.startsWith(dom.descriptor + "->"))
        out["field:dom.children"] = domChildren.descriptor
        val build = knownMethod(domName, "buildContent")
        val parsedChildren = build.usingFields.map { it.field }.filter {
            it.declaredClassName == parsed.name && it.typeName == "java.util.ArrayList" && !Modifier.isStatic(it.modifiers)
        }.distinctBy { it.descriptor }.single()
        check(flow.calls(build.descriptor).any { it.descriptor == "Ljava/util/ArrayList;->iterator()Ljava/util/Iterator;" &&
            it.arguments.firstOrNull() == HostDexCalls.Field(parsedChildren.descriptor) })
        out["field:parsed.children"] = parsedChildren.descriptor
        for ((role, owner) in listOf("parsed.text" to parsed, "common.tag" to common)) {
            val value = owner.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.String" }
            check(build.usingFields.any { it.field.descriptor == value.descriptor })
            out["field:$role"] = value.descriptor
        }
        val containerAppend = knownMethod(containerName, "append")
        val originalChildren = container.fields.single { field ->
            !Modifier.isStatic(field.modifiers) && Modifier.isFinal(field.modifiers) &&
                field.typeName == "java.util.ArrayList" &&
                containerAppend.usingFields.any { it.field.descriptor == field.descriptor }
        }
        val displayChildren = container.fields.single { field ->
            !Modifier.isStatic(field.modifiers) && !Modifier.isFinal(field.modifiers) &&
                field.typeName == "java.util.ArrayList" &&
                containerAppend.usingFields.any { it.field.descriptor == field.descriptor }
        }
        // The parser appends to one list. Its final alias retains logical paragraphs when
        // layout replaces the mutable list with page fragments.
        val containerConstructor = container.methods.filter { it.isConstructor &&
            it.paramCount == 3 && it.paramTypeNames.first() == element.name }.one("container constructor")
        val listWrites = flow.writes(containerConstructor.descriptor)
        val originalValue = listWrites.single { it.field == originalChildren.descriptor }.value
        check(originalValue is HostDexCalls.Allocation && originalValue.type == "Ljava/util/ArrayList;")
        check(listWrites.single { it.field == displayChildren.descriptor }.value == originalValue)
        out["field:container.originalChildren"] = originalChildren.descriptor

        val body = knownMethod("org.epub.html.HtmlDocument", "getBody")
        val document = requireNotNull(body.declaredClass)
        check(!Modifier.isStatic(body.modifiers) && body.paramCount == 0 && body.returnTypeName == container.name)
        out["class:document"] = document.descriptor
        method("document.body", body)
        val bodyField = body.usingFields.map { it.field }.single {
            it.declaredClassName == document.name && it.typeName == container.name && !Modifier.isStatic(it.modifiers)
        }
        val parser = bodyField.writers.flatMap { it.invokes }.filter {
            Modifier.isStatic(it.modifiers) && it.returnTypeName == container.name &&
                it.paramCount in 5..6 && it.paramTypeNames.first() == node.name &&
                it.paramTypeNames[3] == "java.lang.String" && it.paramTypeNames[4] == "boolean" &&
                (it.paramCount == 5 || it.paramTypes.last()?.let { type -> type.modifiers and 0x4000 != 0 } == true)
        }.one("document parser")
        method("document.parse", parser)
        val parseNode = parser.invokes.filter { Modifier.isStatic(it.modifiers) &&
            it.usingStrings.containsAll(listOf("thead", "tbody", "tfoot")) }.one("table parser")
        fun labels(value: HostDexCalls.Value): Set<String> = when (value) {
            is HostDexCalls.Text -> setOf(value.value)
            is HostDexCalls.Call -> value.arguments.flatMap { labels(it) }.toSet()
            else -> emptySet()
        }
        for ((role, tagName) in listOf("head" to "thead", "body" to "tbody", "foot" to "tfoot")) {
            val write = flow.writes(parseNode.descriptor).single {
                it.field.endsWith(":" + container.descriptor) && tagName in labels(it.value)
            }
            val field = requireNotNull(bridge.getFieldData(write.field))
            if (role == "head") out["class:table"] = field.declaredClass.descriptor
            check(field.declaredClass.descriptor == out.getValue("class:table"))
            out["field:table.$role"] = field.descriptor
        }
        val callers = (body.callers + parser.callers + containerMeasure.callers).distinctBy { it.descriptor }.filter { it.isMethod }
        callers.forEachIndexed { index, caller -> method("document.caller.$index", caller) }
        out["value:document.callers"] = callers.size.toString()
        return out
    }
}
