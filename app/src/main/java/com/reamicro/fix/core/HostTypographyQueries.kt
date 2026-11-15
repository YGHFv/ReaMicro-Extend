package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

internal object HostTypographyQueries {
    private fun List<MethodData>.one(label: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Typography $label: missing/ambiguous ${distinctBy { it.descriptor }.map { it.descriptor }}")
    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>, ui: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        fun method(key: String, m: MethodData): MethodData = m.also { out["method:$key"] = it.descriptor }
        fun anchor(vararg strings: String) = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings(*strings))).one(strings.first())
        fun baseType(key: String, logical: String) = requireNotNull(bridge.getClassData(base.getValue("class:$logical"))).also {
            out["class:$key"] = it.descriptor
        }
        val flow = HostDexCalls(apk)
        val density = baseType("density", "androidx.compose.ui.unit.Density")
        val textStyle = baseType("style", "androidx.compose.ui.text.TextStyle")
        val span = baseType("span", "androidx.compose.ui.text.SpanStyle")
        val weight = baseType("weight", "androidx.compose.ui.text.font.FontWeight")
        val paragraph = requireNotNull(anchor("ParagraphStyle(textAlign=").declaredClass)
        out["class:paragraph"] = paragraph.descriptor
        for ((key, type) in listOf("span" to span, "paragraph" to paragraph)) out["field:$key"] = textStyle.fields
            .filter { !Modifier.isStatic(it.modifiers) && it.typeName == type.name }.single().descriptor

        val format = anchor("Unspecified", ".sp", ".em", "Invalid")
        check(Modifier.isStatic(format.modifiers) && format.paramTypeNames == listOf("long") && format.returnTypeName == "java.lang.String")
        val units = requireNotNull(format.declaredClass)
        method("unitType", units.methods.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("long") && it.returnTypeName == "long" }.one("unit type"))
        method("unitValue", units.methods.filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("long") && it.returnTypeName == "float" }.one("unit value"))
        val fields = HostRecordQueries.query(apk, mapOf("class:span" to span.descriptor, "class:paragraph" to paragraph.descriptor,
            "class:weight" to weight.descriptor), scalarFormatters = setOf(format.descriptor), allowPartial = true)
        for ((key, record) in listOf("fontSize" to "span#fontSize", "letterSpacing" to "span#letterSpacing",
            "fontWeight" to "span#fontWeight", "lineHeight" to "paragraph#lineHeight", "weight" to "weight#weight")) {
            out["field:$key"] = fields.getValue("field:$record")
        }
        val toPx = method("toPx", density.methods.filter { m ->
            !Modifier.isStatic(m.modifiers) && m.paramTypeNames == listOf("long") && m.returnTypeName == "float" &&
                "Only Sp can convert to Px" in m.usingStrings && m.invokes.any { i ->
                    i.className == density.name && i.paramTypeNames == listOf("long") && i.returnTypeName == "float" }
        }.one("text unit to pixels"))
        val toDp = toPx.invokes.filter { it.className == density.name && it.paramTypeNames == listOf("long") && it.returnTypeName == "float" }.one("text unit to dp")
        method("fontScale", toDp.invokes.filter { it.className == density.name && it.paramCount == 0 && it.returnTypeName == "float" }.one("font scale"))
        val dpToPx = toPx.invokes.filter { it.className == density.name && it.paramTypeNames == listOf("float") && it.returnTypeName == "float" }.one("dp to pixels")
        method("density", dpToPx.invokes.filter { it.className == density.name && it.paramCount == 0 && it.returnTypeName == "float" }.one("density scale"))

        val typography = method("typography", requireNotNull(bridge.getMethodData(ui.getValue("method:typography"))))
        out["class:typography"] = requireNotNull(typography.returnType).descriptor
        ui.filterKeys { it.startsWith("field:typography.") }.forEach { (key, value) -> out[key] = value }
        val composer = typography.paramTypeNames.single()
        val locals = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings("CompositionLocal ", " not present")))
            .mapNotNull { it.declaredClass }.distinctBy { it.descriptor }
        val provider = locals.flatMap { it.methods }.filter { it.isMethod && Modifier.isStatic(it.modifiers) && it.paramCount == 5 &&
            it.paramTypeNames.takeLast(2) == listOf(composer, "int") && it.returnTypeName == "void" &&
            it.invokes.any { m -> m.paramCount == 0 && m.returnTypeName == density.name } }.one("composition density provider")
        val getter = provider.invokes.filter { it.paramCount == 0 && it.returnTypeName == density.name }.one("view density getter")
        val local = flow.calls(provider.descriptor).filter { call -> call.arguments.size == 2 &&
            (call.arguments[1] as? HostDexCalls.Call)?.descriptor == getter.descriptor }
            .mapNotNull { (it.arguments[0] as? HostDexCalls.Field)?.descriptor }.distinct().single()
        out["field:localDensity"] = local
        val densityLocal = requireNotNull(bridge.getFieldData(local))
        val consume = densityLocal.readers.flatMap { it.invokes }.filter {
            it.className == composer && !Modifier.isStatic(it.modifiers) && it.paramCount == 1 && it.returnTypeName == "java.lang.Object"
        }.one("composer consume")
        method("consume", consume)
        return out
    }
}
