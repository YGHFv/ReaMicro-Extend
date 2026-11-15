package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

internal object HostHighlightTextQueries {
    val spanProperties = "foreground fontSize fontWeight fontStyle fontSynthesis fontFamily fontFeatureSettings letterSpacing baselineShift textGeometricTransform localeList background textDecoration shadow platformStyle drawStyle".split(' ')
    val rangeProperties = listOf("item", "start", "end", "tag")
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Highlight text $role is missing or ambiguous")

    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val flow = HostDexCalls(apk)
        fun type(role: String, logical: String) = requireNotNull(bridge.getClassData(base.getValue("class:$logical"))).also { out["class:$role"] = it.descriptor }
        fun method(role: String, logical: String, name: String) = requireNotNull(bridge.getMethodData(base.getValue("method:$logical#$name"))).also { out["method:$role"] = it.descriptor }
        val annotated = type("annotated", "androidx.compose.ui.text.AnnotatedString")
        val range = type("range", "androidx.compose.ui.text.AnnotatedString\$Range")
        val span = type("span", "androidx.compose.ui.text.SpanStyle")
        val string = type("string", "androidx.compose.ui.text.StringAnnotation")
        type("composer", "androidx.compose.runtime.Composer")
        val basic = method("basic", "androidx.compose.foundation.text.BasicTextKt", "BasicTextAnnotated")
        val content = method("content", "org.epub.ui.ContentKt", "Content")
        check(content.invokes.any { it.descriptor == basic.descriptor })
        out["value:basic.text"] = basic.paramTypeNames.indices.single { basic.paramTypeNames[it] == annotated.name }.toString()
        out["value:basic.composer"] = basic.paramTypes.indices.single { basic.paramTypes[it].descriptor == out.getValue("class:composer") }.toString()

        val annotatedCtor = annotated.methods.filter { it.isConstructor && it.paramCount == 2 &&
            it.paramTypeNames.toSet() == setOf("java.util.List", "java.lang.String") &&
            "Paragraph overlap not allowed, end " in it.usingStrings }.one("annotated constructor")
        out["constructor:annotated"] = annotatedCtor.descriptor
        for ((role, name) in listOf("text" to "java.lang.String", "annotations" to "java.util.List")) {
            val index = annotatedCtor.paramTypeNames.indexOf(name)
            out["value:annotated.$role"] = index.toString()
            out["field:annotated.$role"] = flow.writes(annotatedCtor.descriptor).filter {
                it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(index + 1) && it.field.startsWith(annotated.descriptor + "->")
            }.map { it.field }.distinct().single()
        }
        val rangeCtor = range.methods.filter { it.isConstructor && it.paramCount == 4 && "Reversed range is not supported" in it.usingStrings }.one("range constructor")
        out["constructor:range"] = rangeCtor.descriptor
        for (property in rangeProperties) {
            val field = base.getValue("field:androidx.compose.ui.text.AnnotatedString\$Range#$property")
            val source = flow.writes(rangeCtor.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) && it.field == field }
                .map { it.value }.distinct().single() as HostDexCalls.Parameter
            out["field:range.$property"] = field
            out["value:range.$property"] = (source.index - 1).toString()
        }
        val stringCtor = string.methods.filter { it.isConstructor && it.paramTypeNames == listOf("java.lang.String") }.one("string annotation")
        out["constructor:string"] = stringCtor.descriptor
        out["field:string.value"] = flow.writes(stringCtor.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(1) }
            .map { it.field }.distinct().single()

        val onMeasure = requireNotNull(bridge.getMethodData(base.getValue("method:org.epub.html.node.ContentDom#onMeasure")))
        val unit = onMeasure.invokes.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("float", "long") && it.returnTypeName == "long" &&
            it.invokes.any { m -> m.descriptor == "Ljava/lang/Float;->floatToRawIntBits(F)I" } &&
            it.usingNumbers.any { n -> n.longValue() == 0xffffffffL } }.one("text unit factory")
        out["method:unit"] = unit.descriptor
        val unitFormatter = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings("Unspecified", ".sp", ".em", "Invalid")))
            .filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("long") && it.returnTypeName == "java.lang.String" }.one("text unit formatter")
        val colorFormatter = span.methods.filter { it.name == "toString" && it.paramCount == 0 }.one("span toString").invokes.filter {
            Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("long") && it.returnTypeName == "java.lang.String" && "Color(" in it.usingStrings
        }.one("color formatter")
        val properties = HostRecordQueries.query(apk, mapOf("class:span" to span.descriptor),
            scalarFormatters = setOf(unitFormatter.descriptor, colorFormatter.descriptor), allowPartial = true)
        val namedFields = spanProperties.drop(1).associateWith { properties.getValue("field:span#$it") }
        val foreground = span.fields.filter { !Modifier.isStatic(it.modifiers) && it.descriptor !in namedFields.values }.single()
        val allFields = namedFields + ("foreground" to foreground.descriptor)
        val canonical = span.methods.filter { candidate -> candidate.isConstructor && candidate.paramCount == allFields.size &&
            flow.writes(candidate.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) && it.field in allFields.values }.let { writes ->
                writes.map { it.field }.toSet() == allFields.values.toSet() &&
                    writes.mapNotNull { it.value as? HostDexCalls.Parameter }.map { it.index }.toSet() == (1..allFields.size).toSet()
            }
        }.one("span canonical constructor")
        out["constructor:span"] = canonical.descriptor
        for ((property, field) in allFields) {
            val source = flow.writes(canonical.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) && it.field == field }
                .map { it.value }.distinct().single() as HostDexCalls.Parameter
            out["field:span.$property"] = field
            out["value:span.$property"] = (source.index - 1).toString()
        }
        val colorConstructor = span.methods.filter { it.isConstructor }.flatMap { it.invokes }.filter {
            it.isConstructor && it.paramTypeNames == listOf("long") && it.declaredClass?.interfaces.orEmpty().any { t -> t.name == foreground.typeName }
        }.one("solid foreground")
        out["constructor:foreground"] = colorConstructor.descriptor
        out["class:foreground"] = colorConstructor.declaredClass!!.descriptor

        val node = type("node", "androidx.compose.foundation.text.modifiers.TextAnnotatedStringNode")
        val draw = method("draw", "androidx.compose.foundation.text.modifiers.TextAnnotatedStringNode", "draw")
        val cache = method("layoutCache", "androidx.compose.foundation.text.modifiers.TextAnnotatedStringNode", "getLayoutCache")
        val direct = method("drawDirect", "androidx.compose.ui.node.LayoutNodeDrawScope", "drawDirect")
        val drawInterface = direct.invokes.filter { called -> called.name == draw.name && called.paramTypeNames == draw.paramTypeNames && called.returnTypeName == "void" &&
            node.interfaces.any { it.name == called.declaredClassName } }.one("draw interface").declaredClass!!
        out["class:drawInterface"] = drawInterface.descriptor
        val result = type("layout", "androidx.compose.ui.text.TextLayoutResult")
        val input = type("input", "androidx.compose.ui.text.TextLayoutInput")
        out["field:cache.layout"] = cache.returnType!!.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == result.name }.descriptor
        out["field:layout.input"] = result.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == input.name }.descriptor
        out["field:input.text"] = input.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == annotated.name }.descriptor

        val paragraph = type("paragraph", "androidx.compose.ui.text.MultiParagraph")
        out["field:layout.paragraph"] = result.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == paragraph.name }.descriptor
        method("lineForOffset", "androidx.compose.ui.text.MultiParagraph", "getLineForOffset")
        method("lineEnd", "androidx.compose.ui.text.MultiParagraph", "getLineEnd")
        method("lineStart", "androidx.compose.ui.text.TextLayoutResult", "getLineStart")
        val bounds = method("bounds", "androidx.compose.ui.text.TextLayoutResult", "getBoundingBox")
        val validateLine = paragraph.methods.filter { it.paramTypeNames == listOf("int") && it.returnTypeName == "void" &&
            it.usingStrings.containsAll(listOf("lineIndex(", ") is out of bounds [0, ")) }.one("line validator")
        out["field:paragraph.lineCount"] = validateLine.usingFields.map { it.field }.filter {
            !Modifier.isStatic(it.modifiers) && it.declaredClassName == paragraph.name && it.typeName == "int"
        }.distinctBy { it.descriptor }.single().descriptor
        val rect = requireNotNull(bounds.returnType)
        out["class:rect"] = rect.descriptor
        val toRect = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().paramTypes(rect.name).returnType("android.graphics.RectF")))
            .filter { Modifier.isStatic(it.modifiers) && it.invokes.any { call -> call.descriptor == "Landroid/graphics/RectF;-><init>(FFFF)V" } }.one("native rectangle")
        out["method:rect"] = toRect.descriptor

        val modifier = requireNotNull(node.superClass)
        out["class:modifier"] = modifier.descriptor
        val reset = modifier.methods.filter { it.usingStrings.contains("reset() called on an unattached node") }.one("node attachment guard")
        val attached = reset.usingFields.map { it.field }.filter {
            !Modifier.isStatic(it.modifiers) && it.declaredClassName == modifier.name && it.typeName == "boolean"
        }.distinctBy { it.descriptor }.single()
        check(draw.usingFields.any { it.field.descriptor == attached.descriptor })
        out["field:modifier.attached"] = attached.descriptor
        val invalidate = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().paramTypes(drawInterface.name).returnType("void")))
            .filter { Modifier.isStatic(it.modifiers) && it.usingFields.any { used -> used.field.descriptor == attached.descriptor } }.one("invalidate draw")
        out["method:invalidate"] = invalidate.descriptor

        val scope = draw.paramTypes.single()
        out["class:scope"] = scope.descriptor
        val getCanvas = draw.invokes.filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
            it.returnTypeName in direct.paramTypeNames && it.returnType?.let { t -> Modifier.isInterface(t.modifiers) } == true }.one("draw canvas")
        out["method:canvas"] = getCanvas.descriptor
        val context = requireNotNull(getCanvas.declaredClass)
        out["class:context"] = context.descriptor
        val scopeField = draw.usingFields.map { it.field }.filter { !Modifier.isStatic(it.modifiers) && it.declaredClassName == scope.name &&
            it.type.fields.any { f -> !Modifier.isStatic(f.modifiers) && f.typeName == context.name } }.distinctBy { it.descriptor }.single()
        out["field:scope.draw"] = scopeField.descriptor
        out["class:canvasDraw"] = scopeField.type.descriptor
        out["field:draw.context"] = scopeField.type.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == context.name }.descriptor
        val canvas = requireNotNull(getCanvas.returnType)
        out["class:canvas"] = canvas.descriptor
        val native = bridge.findClass(FindClass.create().matcher(ClassMatcher.create().addInterface(canvas.name))).filter {
            it.fields.any { f -> !Modifier.isStatic(f.modifiers) && f.typeName == "android.graphics.Canvas" }
        }.single()
        out["class:nativeCanvas"] = native.descriptor
        out["field:canvas.native"] = native.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "android.graphics.Canvas" }.descriptor
        return out
    }
}
