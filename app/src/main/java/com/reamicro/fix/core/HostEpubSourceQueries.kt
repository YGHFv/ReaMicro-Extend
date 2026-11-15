package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** Parser and CFI contracts used by both search and verified source edits. */
internal object HostEpubSourceQueries {
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("EPUB source $role is missing or ambiguous")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>, dom: Map<String, String>): Map<String, String> = buildMap {
        val flow = HostDexCalls(apk)
        fun method(role: String, value: MethodData) = value.also { put("method:$role", it.descriptor) }
        fun known(role: String, owner: String, name: String) = method(role,
            requireNotNull(bridge.getMethodData(base.getValue("method:$owner#$name"))))
        fun type(role: String, logical: String) = requireNotNull(bridge.getClassData(base.getValue("class:$logical")))
            .also { put("class:$role", it.descriptor) }
        fun constructor(role: String, owner: ClassData, params: List<String>) = owner.methods.filter {
            it.isConstructor && it.paramTypeNames == params
        }.one("$role constructor").also { put("constructor:$role", it.descriptor) }
        fun parameterField(role: String, ctor: MethodData, slot: Int) {
            val write = flow.writes(ctor.descriptor).single { it.receiver == HostDexCalls.Parameter(0) &&
                it.value == HostDexCalls.Parameter(slot + 1) }
            put("field:$role", write.field)
            put("value:$role", slot.toString())
        }

        val document = type("sourceDocument", "com.fleeksoft.ksoup.nodes.Document")
        val htmlTree = type("htmlTree", "com.fleeksoft.ksoup.parser.HtmlTreeBuilder")
        val tree = type("tree", "com.fleeksoft.ksoup.parser.TreeBuilder")
        val parser = type("parser", "com.fleeksoft.ksoup.parser.Parser")
        constructor("htmlTree", htmlTree, emptyList())
        val parserConstructor = constructor("parser", parser, listOf(tree.name))
        val lock = flow.writes(parserConstructor.descriptor).single { it.receiver == HostDexCalls.Parameter(0) &&
            (it.value as? HostDexCalls.Allocation)?.type == "Ljava/lang/Object;" }
        put("field:parser.lock", lock.field)
        known("parse", "com.fleeksoft.ksoup.parser.TreeBuilder", "parse")
        val body = known("source.body", "com.fleeksoft.ksoup.nodes.Document", "body")
        val element = requireNotNull(body.returnType)
        put("class:element", element.descriptor)
        val nodeName = known("node.name", "com.fleeksoft.ksoup.nodes.Node", "nodeName")
        val node = requireNotNull(nodeName.declaredClass)
        put("class:node", node.descriptor)
        known("node.hasAttr", "com.fleeksoft.ksoup.nodes.Node", "hasAttr")
        val elementChildren = requireNotNull(bridge.getMethodData(dom.getValue("method:source.children")))
        method("node.children", node.methods.filter { it.name == elementChildren.name &&
            it.paramCount == 0 && it.returnTypeName == "java.util.List" && !Modifier.isStatic(it.modifiers) }.one("node children slot"))
        val textName = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings("#text")))
            .filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "java.lang.String" }.one("text node")
        val textNode = requireNotNull(textName.declaredClass)
        put("class:textNode", textNode.descriptor)
        val normalise = known("normaliseWhitespace", "com.fleeksoft.ksoup.internal.StringUtil", "normaliseWhitespace")
        val text = method("text.normalized", textNode.methods.filter { it.isMethod && !Modifier.isStatic(it.modifiers) &&
            it.paramCount == 0 && it.returnTypeName == "java.lang.String" &&
            it.invokes.any { call -> call.descriptor == normalise.descriptor } }.one("normalized text"))
        val wholeCall = flow.calls(text.descriptor).single { it.descriptor == normalise.descriptor }.arguments.single() as HostDexCalls.Call
        check(wholeCall.arguments == listOf(HostDexCalls.Parameter(0)))
        method("text.whole", requireNotNull(bridge.getMethodData(wholeCall.descriptor)))

        val create = known("cfi.create", "org.epub.html.EpubCFI\$Companion", "create")
        val cfi = requireNotNull(create.returnType)
        put("class:cfi", cfi.descriptor)
        val cfiText = known("cfi.text", "org.epub.html.EpubCFI", "toString")
        // These are the three step values appended to the actual CFI string, in grammar
        // order (spine/itemref/body), not the order of fields in the DEX declaration.
        val ordered = flow.calls(cfiText.descriptor).filter {
            it.descriptor == "Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;"
        }.map { (it.arguments[1] as HostDexCalls.Field).descriptor }
        check(ordered.size == 3 && ordered.toSet().size == 3)
        val step = requireNotNull(bridge.getFieldData(ordered.first())).type
        check(ordered.all { requireNotNull(bridge.getFieldData(it)).typeName == step.name && it.startsWith(cfi.descriptor + "->") })
        put("class:step", step.descriptor)
        listOf("spine", "item", "body").forEachIndexed { index, role -> put("field:cfi.$role", ordered[index]) }
        val stepIndex = step.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "int" }
        put("field:step.index", stepIndex.descriptor)
        val assertion = step.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName != "int" }.type
        put("class:assertion", assertion.descriptor)
        val stepCtor = constructor("step", step, listOf("int", assertion.name))
        parameterField("step.index", stepCtor, 0)
        parameterField("step.assertion", stepCtor, 1)
        val steps = cfiText.usingFields.map { it.field }.filter { it.declaredClassName == cfi.name && it.typeName == "java.util.List" }
            .distinctBy { it.descriptor }.single()
        put("field:cfi.steps", steps.descriptor)
        val extra = cfiText.usingFields.map { it.field }.filter { it.declaredClassName == cfi.name &&
            it.typeName != "java.util.List" && it.typeName != step.name && !Modifier.isStatic(it.modifiers) }.distinctBy { it.descriptor }
        val offset = extra.single { f -> f.type.methods.any { it.name == "toString" && it.usingStrings.containsAll(listOf("/", ":")) } }
        val bias = extra.single { f -> f.type.methods.any { it.name == "toString" && ";s=" in it.usingStrings } }
        put("class:offset", offset.type.descriptor); put("field:cfi.offset", offset.descriptor)
        put("class:bias", bias.type.descriptor); put("field:cfi.bias", bias.descriptor)
        val cfiCtor = cfi.methods.filter { it.isConstructor && it.paramCount == 6 &&
            it.paramTypeNames.count { p -> p == step.name } == 3 && "java.util.List" in it.paramTypeNames &&
            offset.typeName in it.paramTypeNames && bias.typeName in it.paramTypeNames }.one("CFI constructor")
        put("constructor:cfi", cfiCtor.descriptor)
        for (role in listOf("spine", "item", "body", "steps", "offset", "bias")) {
            val parameter = flow.writes(cfiCtor.descriptor).single { it.field == getValue("field:cfi.$role") }.value as HostDexCalls.Parameter
            put("value:cfi.$role", (parameter.index - 1).toString())
        }
        val offsetCtor = constructor("offset", offset.type, listOf("int", "int"))
        // Resolve the slash and colon operands through the small StringBuilder formatter.
        val offsetText = offset.type.methods.filter { it.name == "toString" && it.paramCount == 0 }.one("offset text")
        fun formatParts(m: MethodData, arguments: List<HostDexCalls.Value>, depth: Int = 0): List<HostDexCalls.Value> {
            check(depth < 3)
            fun value(v: HostDexCalls.Value) = if (v is HostDexCalls.Parameter) arguments[v.index] else v
            val calls = flow.calls(m.descriptor)
            val appends = calls.filter { it.descriptor.startsWith("Ljava/lang/StringBuilder;->") &&
                (it.descriptor.contains("->append(") || it.descriptor == "Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V") }
            if (appends.isNotEmpty()) return appends.map { value(it.arguments[1]) }
            val helper = calls.single { bridge.getMethodData(it.descriptor)?.let { candidate ->
                Modifier.isStatic(candidate.modifiers) && candidate.returnTypeName == "java.lang.String" } == true }
            return formatParts(requireNotNull(bridge.getMethodData(helper.descriptor)), helper.arguments.map(::value), depth + 1)
        }
        val parts = formatParts(offsetText, listOf(HostDexCalls.Parameter(0)))
        for ((role, delimiter) in listOf("index" to "/", "offset" to ":")) {
            val at = parts.indices.single { parts[it] == HostDexCalls.Text(delimiter) }
            val field = (parts[at + 1] as HostDexCalls.Field).descriptor
            check(field.startsWith(offset.type.descriptor + "->"))
            put("field:offset.$role", field)
            val parameter = flow.writes(offsetCtor.descriptor).single { it.field == field }.value as HostDexCalls.Parameter
            put("value:offset.$role", (parameter.index - 1).toString())
        }
        val biasCtor = constructor("bias", bias.type, listOf("boolean", "java.lang.String"))
        parameterField("bias.before", biasCtor, 0)
        parameterField("bias.assertion", biasCtor, 1)
        method("cfi.compare", cfi.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(cfi.name) &&
            it.returnTypeName == "int" }.one("CFI comparison"))

        val stepMethod = stepCtor.callers.filter { Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf(node.name) && it.returnTypeName == step.name && "body" in it.usingStrings }.one("node step")
        method("node.step", stepMethod)
        method("node.steps", stepMethod.callers.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(node.name) &&
            it.returnTypeName == "java.util.List" && "body" in it.usingStrings }.one("node ancestry"))
        val rangeText = known("range.text", "org.epub.html.EpubCFIRange", "toString")
        val range = requireNotNull(rangeText.declaredClass)
        put("class:range", range.descriptor)
        val rangeCtor = constructor("range", range, listOf(cfi.name, cfi.name))
        val bounds = flow.calls(rangeText.descriptor).filter {
            it.descriptor == "Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;"
        }.map { (it.arguments[1] as HostDexCalls.Field).descriptor }
        check(bounds.size == 2)
        for ((index, role) in listOf("start", "end").withIndex()) {
            check(flow.writes(rangeCtor.descriptor).any { it.field == bounds[index] && it.value is HostDexCalls.Parameter })
            put("field:range.$role", bounds[index])
        }

        val nativeDocument = type("document", "org.epub.html.HtmlDocument")
        val parseBody = known("body.parse", "org.epub.html.node.BodyDom", "parseBody")
        put("class:container", requireNotNull(parseBody.returnType).descriptor)
        val builder = parseBody.paramTypes[1]
        put("class:builder", builder.descriptor)
        val sheets = type("sheets", "org.epub.css.StyleSheets")
        constructor("sheets", sheets, listOf("java.util.List"))
        put("field:sheets.rules", base.getValue("field:org.epub.css.StyleSheets#ruleSets"))
        for ((role, expected) in listOf("builder" to builder, "sheets" to sheets)) put("field:document.$role",
            nativeDocument.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == expected.name }.descriptor)
        put("field:document.directory", nativeDocument.fields.single { !Modifier.isStatic(it.modifiers) &&
            Modifier.isFinal(it.modifiers) && it.typeName == "java.lang.String" }.descriptor)
        known("document.fullScreen", "org.epub.html.HtmlDocument", "isFullScreen")
        val build = method("builder.build", builder.methods.filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
            it.returnTypeName == cfi.name }.one("builder result"))
        val buildCall = flow.calls(build.descriptor).single { it.descriptor == cfiCtor.descriptor }
        val bodySlot = getValue("value:cfi.body").toInt()
        put("field:builder.body", (buildCall.arguments[bodySlot + 1] as HostDexCalls.Field).descriptor)
        method("builder.copy", builder.methods.filter { !Modifier.isStatic(it.modifiers) && it.isMethod && it.paramCount == 0 &&
            it.returnTypeName == builder.name && it.usingFields.any { f -> f.field.descriptor == getValue("field:builder.body") } }.one("builder clone"))
        check(document.name == body.declaredClassName)
        putAll(HostEpubProjectionQueries.query(bridge, apk, base, dom))
        putAll(HostScrollContentQueries.query(bridge, apk, base, dom))
    }
}
