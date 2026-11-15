package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/** Follows the sentence-template editor to its actual centered surface and content slots. */
internal object HostSentenceDialogQueries {
    fun query(
        bridge: DexKitBridge,
        apk: String,
        ui: Map<String, String>,
        nativeDialog: MethodData,
        propertiesConstructor: MethodData,
    ): Map<String, String> {
        fun method(key: String) = requireNotNull(bridge.getMethodData(ui.getValue("method:$key")))
        fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
            ?: error("Sentence dialog $role is missing or ambiguous")
        val function0 = requireNotNull(bridge.getClassData(ui.getValue("class:function0"))).name
        val function1 = requireNotNull(bridge.getClassData(ui.getValue("class:function1"))).name
        val function3 = requireNotNull(bridge.getClassData(ui.getValue("class:function3"))).name
        // The state factory returns a concrete implementation; the editor accepts its mutable interface.
        val state = requireNotNull(method("stateSet").declaredClass)
        val composer = method("title").paramTypeNames[2]
        val modifier = method("title").paramTypeNames[1]
        val body = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .usingEqStrings("句子查询模板", "固定内容", "《书名》里的“查询内容”")
            .paramTypes("java.lang.Object", "java.lang.Object").returnType("java.lang.Object")))
            .filter { !Modifier.isStatic(it.modifiers) }.one("content")
        val constructor = requireNotNull(body.declaredClass).methods.filter {
            it.isConstructor && it.paramTypeNames == listOf(function0, state.name, function1)
        }.one("content constructor")
        val flow = HostDexCalls(apk)
        val mode = flow.writes(constructor.descriptor).mapNotNull { write ->
            val value = write.value as? HostDexCalls.Literal ?: return@mapNotNull null
            val field = bridge.getFieldData(write.field) ?: return@mapNotNull null
            if (write.receiver == HostDexCalls.Parameter(0) && field.className == body.className &&
                field.typeName in listOf("byte", "int") && !Modifier.isStatic(field.modifiers)) field.descriptor to value.value
            else null
        }.distinct().singleOrNull() ?: error("Sentence dialog content discriminator is missing or ambiguous")
        // R8 merges several editors into this body; only the constructor's branch owns these slots.
        val activeCalls = flow.callsWithFields(body.descriptor, mapOf(mode))
        val activeMethods = activeCalls.mapNotNull { bridge.getMethodData(it.descriptor) }
        val entry = constructor.callers.filter {
            it.isMethod && Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                it.invokes.any { called -> called.descriptor == nativeDialog.descriptor }
        }.one("editor entry")
        check(activeMethods.any { it.descriptor == method("text").descriptor }) {
            "Sentence dialog no longer uses the host text component"
        }
        val field = activeMethods.filter {
            Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                it.paramTypeNames.take(3) == listOf("java.lang.String", function1, modifier) &&
                it.paramTypeNames.count { name -> name == composer } == 1
        }.one("form slot")
        val button = activeMethods.filter {
            Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                it.paramTypeNames.take(3) == listOf(function0, modifier, "boolean") &&
                it.paramTypeNames.count { name -> name == composer } == 1
        }.one("action slot")
        val calls = flow.calls(entry.descriptor)
        val window = calls.filter { it.descriptor == nativeDialog.descriptor }.distinct().single()
        val properties = calls.filter { it.descriptor == propertiesConstructor.descriptor }.distinct().single()
        val argument = (properties.arguments[1] as HostDexCalls.Literal).value
        val flags = (window.arguments[4] as HostDexCalls.Literal).value
        val mask = (window.arguments[5] as HostDexCalls.Literal).value
        check(argument in 0L..7L && flags >= 0L && mask >= 0L)
        return linkedMapOf(
            "class:sentenceState" to state.descriptor,
            "constructor:sentenceContent" to constructor.descriptor,
            "method:sentenceBody" to body.descriptor,
            "method:sentenceField" to field.descriptor,
            "method:sentenceButton" to button.descriptor,
            "value:sentenceProperties" to argument.toString(),
            "value:sentenceFlags" to flags.toString(),
            "value:sentenceMask" to mask.toString(),
            "value:sentenceFieldModifier" to "2",
            "value:sentenceFieldComposer" to field.paramTypeNames.indexOf(composer).toString(),
            "value:sentenceButtonContent" to button.paramTypeNames.indices.single {
                button.paramTypeNames[it] == function3
            }.toString(),
            "value:sentenceButtonComposer" to button.paramTypeNames.indexOf(composer).toString(),
        )
    }
}
