package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/** Follows the host prompt to its surface, window and remembered parent composition. */
internal object HostDialogQueries {
    fun query(
        bridge: DexKitBridge,
        apk: String,
        ui: Map<String, String>,
        composition: Map<String, String>,
    ): Map<String, String> {
        fun method(descriptor: String) = requireNotNull(bridge.getMethodData(descriptor))
        fun type(descriptor: String) = requireNotNull(bridge.getClassData(descriptor))
        fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
            ?: error("Native dialog $role is missing or ambiguous")
        val flow = HostDexCalls(apk)
        val composer = method(ui.getValue("method:title")).paramTypeNames[2]
        val function0 = type(ui.getValue("class:function0")).name
        val function2 = type(ui.getValue("class:function2"))
        val function3 = type(ui.getValue("class:function3"))
        val lambda = type(ui.getValue("class:lambda")).name
        val prompt = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .usingEqStrings("提示").paramTypes("java.lang.String", function0, composer, "int")
            .returnType("void"))).filter { Modifier.isStatic(it.modifiers) }.one("prompt")
        val wrapper = prompt.invokes.filter {
            Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" && it.paramCount == 7 &&
                it.paramTypeNames.take(2) == listOf("java.lang.String", function0) &&
                it.paramTypeNames.drop(3) == listOf(lambda, composer, "int", "int")
        }.one("surface wrapper")
        val promptCall = flow.calls(prompt.descriptor).filter { it.descriptor == wrapper.descriptor }.distinct().single()
        check(promptCall.arguments[0] == HostDexCalls.Text("提示") &&
            promptCall.arguments[1] == HostDexCalls.Parameter(1) &&
            promptCall.arguments[4] == HostDexCalls.Parameter(2)) { "Native prompt argument flow changed" }
        val promptFlags = (promptCall.arguments[5] as HostDexCalls.Literal).value
        val promptMask = (promptCall.arguments[6] as HostDexCalls.Literal).value
        // The window root is Function2, but the surface's ColumnScope content is Function3.
        // Follow that actual slot through the host's merged callback and wrapper cast.
        val invoke3 = method(ui.getValue("method:invoke3"))
        val contentInvoke = flow.calls(wrapper.descriptor).filter { call ->
            bridge.getMethodData(call.descriptor)?.isConstructor == true &&
                call.arguments.drop(1).contains(HostDexCalls.Parameter(3))
        }.distinct().flatMap callback@{ constructorCall ->
            val constructor = method(constructorCall.descriptor)
            val slot = constructorCall.arguments.indices.singleOrNull {
                constructorCall.arguments[it] == HostDexCalls.Parameter(3)
            } ?: return@callback emptyList()
            val contentField = flow.writes(constructor.descriptor).filter {
                it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(slot)
            }.map { it.field }.distinct().singleOrNull() ?: return@callback emptyList()
            requireNotNull(constructor.declaredClass).methods.filter {
                !Modifier.isStatic(it.modifiers) && it.returnTypeName == "java.lang.Object" &&
                    it.paramTypeNames == List(2) { "java.lang.Object" }
            }.flatMap { content -> flow.calls(content.descriptor) }.filter { call ->
                val target = bridge.getMethodData(call.descriptor) ?: return@filter false
                call.arguments.firstOrNull() == HostDexCalls.Field(contentField) &&
                    target.className == lambda && target.methodName == invoke3.methodName &&
                    target.paramTypeNames == invoke3.paramTypeNames && target.returnTypeName == invoke3.returnTypeName
            }.map { method(it.descriptor) }
        }.one("scoped content invocation")
        val scopedDispatch = contentInvoke.invokes.filter {
            it.className == lambda && it.returnTypeName == "java.lang.Object" &&
                it.paramTypeNames == listOf("java.lang.Object", composer, "int")
        }.one("scoped content dispatch")
        val callbackField = flow.writes(ui.getValue("constructor:lambda")).filter {
            it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(3)
        }.map { it.field }.distinct().single()
        check(flow.casts(scopedDispatch.descriptor).any {
            it.type == function3.descriptor && it.value == HostDexCalls.Field(callbackField)
        }) { "Native dialog content no longer uses Function3" }
        check(function2.descriptor != function3.descriptor)
        val paddingConstructor = wrapper.invokes.filter {
            it.isConstructor && it.paramTypeNames == List(4) { "float" }
        }.one("padding constructor")
        val dpInt = method(ui.getValue("method:dpInt"))
        // Resolve the actual default bit, rather than assuming a Kotlin parameter mask.
        val defaults = (0..8).mapNotNull { shift ->
            val bit = 1L shl shift
            val calls = flow.calls(wrapper.descriptor, mapOf(5 to 0L, 6 to bit))
                .filter { it.descriptor == paddingConstructor.descriptor }.distinct()
            val call = calls.singleOrNull() ?: return@mapNotNull null
            val dimensions = call.arguments.drop(1)
            if (dimensions.size != 4 || dimensions.distinct().size != 1) return@mapNotNull null
            val scaled = dimensions.first() as? HostDexCalls.Call ?: return@mapNotNull null
            if (scaled.descriptor != dpInt.descriptor || scaled.arguments.size != 1) return@mapNotNull null
            val size = scaled.arguments.first() as? HostDexCalls.Literal ?: return@mapNotNull null
            bit to size.value
        }
        val (paddingMask, paddingSize) = defaults.singleOrNull()
            ?: error("Native dialog default padding is missing or ambiguous")
        val nativeDialog = wrapper.invokes.filter {
            Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" && it.paramCount == 6 &&
                it.paramTypeNames[0] == function0 &&
                it.paramTypeNames.drop(2) == listOf(lambda, composer, "int", "int")
        }.one("window composition")
        val propertiesConstructor = wrapper.invokes.filter {
            it.isConstructor && it.className == nativeDialog.paramTypeNames[1] &&
                it.paramTypeNames == listOf("int")
        }.one("window properties")
        val propertiesCall = flow.calls(wrapper.descriptor).filter {
            it.descriptor == propertiesConstructor.descriptor
        }.distinct().single()
        val propertiesArgument = (propertiesCall.arguments[1] as HostDexCalls.Literal).value
        val windowConstructor = nativeDialog.invokes.filter {
            it.isConstructor && it.paramCount == 6 &&
                it.paramTypeNames.take(3) == listOf(function0, nativeDialog.paramTypeNames[1], "android.view.View") &&
                it.paramTypeNames[5] == "java.util.UUID" &&
                generateSequence(it.declaredClass) { owner -> owner.superClass }.any { owner ->
                    owner.name == "android.app.Dialog"
                }
        }.one("window constructor")
        val window = requireNotNull(windowConstructor.declaredClass)
        val windowCall = flow.calls(nativeDialog.descriptor, mapOf(5 to 0L)).filter {
            it.descriptor == windowConstructor.descriptor
        }.distinct().single()
        check(windowCall.arguments[1] == HostDexCalls.Parameter(0) &&
            windowCall.arguments[2] == HostDexCalls.Parameter(1)) { "Native window argument flow changed" }
        val dismissField = flow.writes(windowConstructor.descriptor).filter {
            it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(1)
        }.mapNotNull { bridge.getFieldData(it.field) }.filter {
            it.className == window.name && it.typeName == function0 && !Modifier.isStatic(it.modifiers)
        }.distinctBy { it.descriptor }.singleOrNull()
            ?: error("Native window dismissal field is missing or ambiguous")
        val windowTouch = window.methods.filter {
            it.methodName == "onTouchEvent" && it.paramTypeNames == listOf("android.view.MotionEvent") &&
                it.returnTypeName == "boolean" && !Modifier.isStatic(it.modifiers)
        }.one("outside touch dispatch")
        val windowKeyUp = window.methods.filter {
            it.methodName == "onKeyUp" && it.paramTypeNames == listOf("int", "android.view.KeyEvent") &&
                it.returnTypeName == "boolean" && !Modifier.isStatic(it.modifiers)
        }.one("escape dispatch")
        fun dismisses(calls: List<HostDexCalls.Call>) = calls.any {
            val target = bridge.getMethodData(it.descriptor) ?: return@any false
            target.className == function0 && target.paramCount == 0 &&
                it.arguments == listOf(HostDexCalls.Field(dismissField.descriptor))
        }
        check(dismisses(flow.calls(windowTouch.descriptor)) && dismisses(flow.calls(windowKeyUp.descriptor))) {
            "Native touch and escape no longer use the window dismissal callback"
        }
        val backConstructor = windowConstructor.invokes.filter {
            it.isConstructor && it.paramTypeNames == listOf(window.name, "byte") &&
                it.declaredClass?.interfaces?.any { owner -> owner.name == "kotlin.jvm.functions.Function1" } == true
        }.one("back callback constructor")
        val backCall = flow.calls(windowConstructor.descriptor).filter {
            it.descriptor == backConstructor.descriptor && it.arguments[1] == HostDexCalls.Parameter(0)
        }.distinct().single()
        val backMode = backCall.arguments[2] as? HostDexCalls.Literal
            ?: error("Native back callback discriminator changed")
        val backModeField = flow.writes(backConstructor.descriptor).filter {
            it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(2)
        }.mapNotNull { bridge.getFieldData(it.field) }.filter { it.typeName == "byte" }
            .distinctBy { it.descriptor }.singleOrNull()
            ?: error("Native back callback discriminator field changed")
        val backInvoke = requireNotNull(backConstructor.declaredClass).methods.filter {
            it.methodName == "invoke" && it.paramTypeNames == listOf("java.lang.Object") &&
                it.returnTypeName == "java.lang.Object" && !Modifier.isStatic(it.modifiers)
        }.one("back dispatch")
        check(dismisses(flow.callsWithFields(backInvoke.descriptor, mapOf(backModeField.descriptor to backMode.value)))) {
            "Native back no longer uses the window dismissal callback"
        }
        // Recomposition can enter the host Dialog without executing its outer surface again.
        // Deoptimize these exact callers so the scoped nativeDialog hook also sees that path.
        val renderCallers = nativeDialog.callers.filter { it.isMethod }.distinctBy { it.descriptor }
            .sortedBy { it.descriptor }
        check(renderCallers.size in 1..32 && renderCallers.any { it.descriptor == wrapper.descriptor })
        val setParent = nativeDialog.invokes.filter {
            it.methodName == "setParentCompositionContext" && !Modifier.isStatic(it.modifiers) &&
                it.paramCount == 1 && it.returnTypeName == "void"
        }.one("parent composition setter")
        val parentType = requireNotNull(setParent.paramTypes.single())
        val root = type(composition.getValue("class:root"))
        check(generateSequence(root) { it.superClass }.any { it.name == setParent.className })
        val rememberContext = nativeDialog.invokes.filter {
            Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf(composer) &&
                generateSequence(it.returnType) { owner -> owner.superClass }.any { owner ->
                    owner.descriptor == parentType.descriptor
                }
        }.one("remembered parent composition")
        val sideEffect = nativeDialog.invokes.filter {
            Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                it.paramTypeNames == listOf(function0, composer)
        }.one("window composition commit")
        val themeOwner = requireNotNull(method(ui.getValue("method:backgroundAuto")).declaredClass)
        val themeEntry = themeOwner.methods.filter {
            Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                it.paramCount == 3 && it.paramTypeNames[0] in listOf(lambda, function2.name) &&
                it.paramTypeNames.drop(1) == listOf(composer, "int")
        }.one("application theme content")
        val theme = themeEntry.invokes.filter {
            Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" && it.paramCount == 7 &&
                it.paramTypeNames[0] == method(ui.getValue("method:colorScheme")).returnTypeName &&
                it.paramTypeNames[2] == method(ui.getValue("method:typography")).returnTypeName &&
                it.paramTypeNames.drop(3) == listOf(function2.name, composer, "int", "int")
        }.one("material theme content")
        val themeCallers = theme.callers.filter { it.isMethod }.distinctBy { it.descriptor }
            .sortedBy { it.descriptor }
        check(themeCallers.size in 1..64 && themeCallers.any { it.descriptor == themeEntry.descriptor })
        return linkedMapOf(
            "class:activity" to composition.getValue("class:activity"),
            "class:root" to root.descriptor,
            "class:composer" to requireNotNull(wrapper.paramTypes[4]).descriptor,
            "class:function0" to ui.getValue("class:function0"),
            "class:function2" to ui.getValue("class:function2"),
            "class:function3" to function3.descriptor,
            "class:lambda" to ui.getValue("class:lambda"),
            "class:padding" to requireNotNull(wrapper.paramTypes[2]).descriptor,
            "class:properties" to requireNotNull(nativeDialog.paramTypes[1]).descriptor,
            "class:window" to window.descriptor,
            "class:parentContext" to parentType.descriptor,
            "class:rememberContext" to requireNotNull(rememberContext.returnType).descriptor,
            "constructor:root" to composition.getValue("constructor:root"),
            "constructor:lambda" to ui.getValue("constructor:lambda"),
            "constructor:padding" to paddingConstructor.descriptor,
            "constructor:properties" to propertiesConstructor.descriptor,
            "constructor:window" to windowConstructor.descriptor,
            "field:windowDismiss" to dismissField.descriptor,
            "field:contentCallback" to callbackField,
            "method:prompt" to prompt.descriptor,
            "method:wrapper" to wrapper.descriptor,
            "method:contentInvoke" to contentInvoke.descriptor,
            "method:nativeDialog" to nativeDialog.descriptor,
            "method:windowTouch" to windowTouch.descriptor,
            "method:rememberContext" to rememberContext.descriptor,
            "method:sideEffect" to sideEffect.descriptor,
            "method:theme" to theme.descriptor,
            "method:applicationTheme" to themeEntry.descriptor,
            "value:themeContent" to "3",
            "value:themeComposer" to "4",
            "method:setParent" to setParent.descriptor,
            "method:setContent" to composition.getValue("method:setContent"),
            "method:dispose" to composition.getValue("method:dispose"),
            "method:dpInt" to dpInt.descriptor,
            "value:paddingMask" to paddingMask.toString(),
            "value:paddingSize" to paddingSize.toString(),
            "value:promptFlags" to promptFlags.toString(),
            "value:promptMask" to promptMask.toString(),
            "value:propertiesArgument" to propertiesArgument.toString(),
        ).apply {
            renderCallers.forEachIndexed { index, caller -> put("method:renderCaller.$index", caller.descriptor) }
            themeCallers.forEachIndexed { index, caller -> put("method:themeCaller.$index", caller.descriptor) }
            putAll(HostSentenceDialogQueries.query(bridge, apk, ui, nativeDialog, propertiesConstructor))
        }
    }
}
