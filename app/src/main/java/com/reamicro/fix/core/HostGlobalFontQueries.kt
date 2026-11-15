package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher

internal object HostGlobalFontQueries {
    fun query(bridge: DexKitBridge, apk: String, base: Map<String, String>, ui: Map<String, String>,
        preview: Map<String, String>, menu: Map<String, String>): Map<String, String> = buildMap {
        fun type(role: String, logical: String) = requireNotNull(bridge.getClassData(base.getValue("class:$logical")))
            .also { put("class:$role", it.descriptor) }
        val family = type("family", "androidx.compose.ui.text.font.FontFamily")
        val style = type("style", "androidx.compose.ui.text.TextStyle")
        val span = type("span", "androidx.compose.ui.text.SpanStyle")
        val composer = type("composer", "androidx.compose.runtime.Composer")
        val text = requireNotNull(bridge.getMethodData(ui.getValue("method:text")))
        val familyIndex = text.paramTypeNames.indices.single { text.paramTypeNames[it] == family.name }
        val styleIndex = text.paramTypeNames.indices.single { text.paramTypeNames[it] == style.name }
        val composerIndex = text.paramTypeNames.indices.single { text.paramTypeNames[it] == composer.name }
        val flow = HostDexCalls(apk)
        require(Modifier.isStatic(text.modifiers) && text.paramTypeNames.takeLast(3) == List(3) { "int" })
        val default = flow.nullDefaults(text.descriptor).single { it.parameter == familyIndex && it.maskParameter == text.paramCount - 1 }
        put("method:text", text.descriptor)
        put("value:family", familyIndex.toString()); put("value:style", styleIndex.toString())
        put("value:composer", composerIndex.toString()); put("value:mask", default.maskParameter.toString())
        put("value:familyBit", default.bit.toString())
        val overloads = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().paramCount(text.paramCount).returnType("void")))
            .filter { Modifier.isStatic(it.modifiers) && it.paramTypeNames.drop(1) == text.paramTypeNames.drop(1) }
            .distinctBy { it.descriptor }
        require(overloads.any { it.descriptor == text.descriptor })
        overloads.sortedBy { it.descriptor }.forEachIndexed { index, method ->
            require(flow.nullDefaults(method.descriptor).nullDefaultsOrBit(familyIndex, text.paramCount - 1) == default.bit)
            put("method:text.$index", method.descriptor)
        }
        put("field:style.span", style.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == span.name }.descriptor)
        val fields = HostRecordQueries.query(apk, mapOf("class:span" to span.descriptor), allowPartial = true)
        put("field:span.family", fields.getValue("field:span#fontFamily"))
        val owner = "app.zhendong.reamicro.ui.reader.ReaderScreenKt"
        for (name in listOf("ReaderScreen", "InitEpubWindow", "BottomSheet"))
            put("method:reader.$name", base.getValue("method:$owner#$name"))
        for (role in listOf("render", "bottomBar")) put("method:reader.$role", menu.getValue("method:$role"))
        put("method:preview", preview.getValue("method:row"))
    }
    private fun List<HostDexCalls.NullDefault>.nullDefaultsOrBit(parameter: Int, mask: Int): Int =
        single { it.parameter == parameter && it.maskParameter == mask }.bit
}
