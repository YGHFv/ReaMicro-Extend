package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

/** Text-only projection uses the same predicates and replacement string as ContentDom. */
internal object HostHighlightProjectionQueries {
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Highlight projection $role is missing or ambiguous")

    fun query(bridge: DexKitBridge, flow: HostDexCalls, build: MethodData, parsed: ClassData,
        source: ClassData, elementDescriptor: String, css: Map<String, String>): Map<String, String> = buildMap {
        val owner = requireNotNull(build.declaredClass)
        val element = requireNotNull(bridge.getClassData(elementDescriptor))
        fun method(role: String, method: MethodData) { put("method:$role", method.descriptor) }
        val rawNumber = build.invokes.filter { !Modifier.isStatic(it.modifiers) && it.paramCount == 0 &&
            it.returnTypeName == "float" && it.declaredClass?.superClass?.descriptor == css.getValue("class:cssValue") }
            .one("raw CSS length")
        method("css.rawNumber", rawNumber)
        val decoration = build.invokes.filter { it.declaredClassName == owner.name && !Modifier.isStatic(it.modifiers) &&
            it.paramCount == 5 && it.paramTypeNames[1] == parsed.name &&
            it.paramTypeNames.takeLast(3) == listOf("java.lang.String", "boolean", "int") &&
            it.usingStrings.containsAll(listOf("start", "end", "androidx.compose.foundation.text.inlineContent")) }
            .one("inline decoration")
        // These builds compare two immutable singleton sentinels. Prove the unreachable
        // legacy font-mode branch rather than assuming a global font mode or field name.
        val modePair = flow.comparisons(decoration.descriptor).filter {
            it.left is HostDexCalls.Field && it.right is HostDexCalls.Field
        }.single()
        val modeFields = listOf(modePair.left, modePair.right).map { value ->
            requireNotNull(bridge.getFieldData((value as HostDexCalls.Field).descriptor))
        }
        check(modeFields.map { it.typeName }.distinct().size == 2)
        modeFields.forEachIndexed { index, field ->
            check(Modifier.isStatic(field.modifiers) && Modifier.isFinal(field.modifiers))
            val init = field.declaredClass.methods.single { it.name == "<clinit>" }
            check((flow.writes(init.descriptor).single { it.field == field.descriptor }.value as? HostDexCalls.Allocation)?.type == field.type.descriptor)
            put("field:inline.mode.$index", field.descriptor)
        }
        val inlineAppend = decoration.invokes.filter { Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf(build.paramTypeNames[0], "java.lang.String") && it.returnTypeName == "void" }
            .one("inline append")
        val appendText = inlineAppend.invokes.filter { Modifier.isStatic(it.modifiers) &&
            it.paramTypeNames == listOf(build.paramTypeNames[0], "java.lang.String", "java.lang.String") &&
            it.returnTypeName == "void" && it.usingStrings.contains("androidx.compose.foundation.text.inlineContent") }
            .one("inline append replacement")
        val replacement = (flow.calls(inlineAppend.descriptor).single { it.descriptor == appendText.descriptor }
            .arguments[2] as HostDexCalls.Text).value
        check(replacement.length == 1)
        put("text:inline.replacement", replacement)
        val noWrap = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingEqStrings("nowrap")))
            .filter { it.name == "<clinit>" && it.usingFields.any { used ->
                used.field.typeName == it.declaredClassName && build.usingFields.any { field -> field.field.descriptor == used.field.descriptor }
            } }.one("nowrap value")
        val noWrapField = noWrap.usingFields.map { it.field }.single { Modifier.isStatic(it.modifiers) &&
            it.typeName == noWrap.declaredClassName && build.usingFields.any { used -> used.field.descriptor == it.descriptor } }
        put("field:css.nowrap", noWrapField.descriptor)
        val noWrapType = requireNotNull(noWrap.declaredClass)
        val whiteSpace = requireNotNull(bridge.getClassData(css.getValue("class:css.white-space")))
        check(noWrapType.superClass?.descriptor == whiteSpace.fields.single {
            !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers)
        }.type.descriptor)
        val sameOwner = build.invokes.filter { it.declaredClassName == owner.name && Modifier.isStatic(it.modifiers) }
        val hasImage = sameOwner.filter { it.paramTypeNames == listOf(parsed.name) && it.returnTypeName == "boolean" &&
            it.usingStrings.containsAll(listOf("img", "image")) }.one("note image predicate")
        method("note.hasImage", hasImage)
        val background = css.getValue("field:css.background-image.valid")
        method("note.hasBackground", sameOwner.filter { it.paramTypeNames == listOf(parsed.name) && it.returnTypeName == "boolean" &&
            it.usingFields.any { used -> used.field.descriptor == background } }.one("note background predicate"))
        val skip = sameOwner.filter { it.paramCount == 2 && it.paramTypeNames[0] == parsed.name &&
            it.returnTypeName == "boolean" && it.usingStrings.contains("#text") }.one("note duplicate child")
        val noteText = skip.usingFields.map { it.field }.distinctBy { it.descriptor }
            .single { it.declaredClassName == skip.paramTypeNames[1] && it.typeName == "java.lang.String" }
        method("note.normalize", skip.invokes.filter { Modifier.isStatic(it.modifiers) && it.declaredClassName == owner.name &&
            it.paramTypeNames == listOf("java.lang.String") && it.returnTypeName == "java.lang.String" }.one("note text normalization"))
        method("element.text", skip.invokes.filter { !Modifier.isStatic(it.modifiers) && it.declaredClassName == element.name &&
            it.paramCount == 0 && it.returnTypeName == "java.lang.String" }.one("element text"))
        method("node.attr", build.invokes.filter { !Modifier.isStatic(it.modifiers) && it.declaredClassName == source.name &&
            it.paramTypeNames == listOf("java.lang.String") && it.returnTypeName == "java.lang.String" }.one("source attribute"))
        method("note.resolve", sameOwner.filter { it.paramTypeNames == listOf(parsed.name, "java.lang.String") &&
            it.returnTypeName == "java.lang.String" && it.usingStrings.contains("file:") }.one("note text"))
        check(build.usingFields.any { it.field.descriptor == noteText.descriptor })
    }
}
