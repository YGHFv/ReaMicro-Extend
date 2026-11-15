package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/** Root content is followed from the Android lifecycle call, including the original host callback. */
internal object HostCompositionQueries {
    private fun List<MethodData>.one(role: String) = distinctBy { it.descriptor }.singleOrNull()
        ?: error("Composition $role is missing or ambiguous")

    fun query(bridge: DexKitBridge, apk: String, ui: Map<String, String>): Map<String, String> {
        fun method(d: String) = requireNotNull(bridge.getMethodData(d))
        fun type(d: String) = requireNotNull(bridge.getClassData(d))
        fun field(d: String) = requireNotNull(bridge.getFieldData(d))
        val flow = HostDexCalls(apk)
        val activity = type("Lapp/zhendong/reamicro/MainActivity;")
        val onCreate = activity.methods.filter { it.methodName == "onCreate" &&
            it.paramTypeNames == listOf("android.os.Bundle") && it.returnTypeName == "void" }.one("activity lifecycle")
        val function2 = type(ui.getValue("class:function2"))
        val setContent = onCreate.invokes.filter { !Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
            it.paramTypeNames == listOf(function2.name) && it.invokes.any { call ->
                call.descriptor == "Landroid/view/View;->isAttachedToWindow()Z"
            } }.one("root content setter")
        val root = requireNotNull(setContent.declaredClass)
        val rootConstructor = onCreate.invokes.filter { it.isConstructor && it.className == root.name &&
            it.paramTypeNames == listOf(activity.name) }.one("root view constructor")
        val lambdaConstructor = method(ui.getValue("constructor:lambda"))
        val wrapper = requireNotNull(lambdaConstructor.declaredClass)
        val activityCalls = flow.calls(onCreate.descriptor)
        val wrapperValue = activityCalls.filter { it.descriptor == setContent.descriptor }
            .map { it.arguments[1] }.distinct().single() as HostDexCalls.Allocation
        check(wrapperValue.type == wrapper.descriptor)
        val wrapCall = activityCalls.filter { it.descriptor == lambdaConstructor.descriptor &&
            it.arguments.first() == wrapperValue }.single()
        val callbackValue = wrapCall.arguments[3] as HostDexCalls.Allocation
        val callbackCall = activityCalls.filter { it.descriptor.contains("-><init>(") &&
            it.arguments.firstOrNull() == callbackValue }.single()
        val callbackConstructor = method(callbackCall.descriptor)
        val callback = requireNotNull(callbackConstructor.declaredClass)
        check(callback.descriptor == callbackValue.type)
        val activitySlot = callbackCall.arguments.indices.single { callbackCall.arguments[it] == HostDexCalls.Parameter(0) }
        fun parameterField(constructor: MethodData, slot: Int) = field(flow.writes(constructor.descriptor)
            .filter { it.receiver == HostDexCalls.Parameter(0) && it.value == HostDexCalls.Parameter(slot) }
            .map { it.field }.distinct().single())
        val callbackActivity = parameterField(callbackConstructor, activitySlot)
        val stateSet = method(ui.getValue("method:stateSet"))
        val stateField = field(flow.calls(setContent.descriptor).filter { call ->
            val target = bridge.getMethodData(call.descriptor) ?: return@filter false
            target.methodName == stateSet.methodName && target.paramTypeNames == stateSet.paramTypeNames &&
                target.returnTypeName == stateSet.returnTypeName && call.arguments.getOrNull(1) == HostDexCalls.Parameter(1)
        }.map { (it.arguments.first() as HostDexCalls.Field).descriptor }.distinct().single())
        check(stateField.declaredClass?.descriptor == root.descriptor)
        val ancestors = generateSequence(root.superClass) { it.superClass }.toList()
        val dispose = ancestors.flatMap { it.methods }.filter { !Modifier.isStatic(it.modifiers) && it.isMethod &&
            it.paramCount == 0 && it.returnTypeName == "void" &&
            it.invokes.any { call -> call.descriptor == "Landroid/view/ViewGroup;->getChildAt(I)Landroid/view/View;" } &&
            it.invokes.any { call -> call.descriptor == "Landroid/view/View;->requestLayout()V" }
        }.one("composition disposal")
        val controller = requireNotNull(bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .usingEqStrings("No destination with route "))).one("navigation controller").declaredClass)
        val navigationCache = bridge.findField(FindField.create().matcher(FieldMatcher.create().type(controller.name)))
            .filter { Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) }.single()
        return linkedMapOf(
            "class:activity" to activity.descriptor, "class:root" to root.descriptor,
            "class:wrapper" to wrapper.descriptor, "class:callback" to callback.descriptor,
            "class:function2" to function2.descriptor, "class:controller" to controller.descriptor,
            "constructor:root" to rootConstructor.descriptor, "constructor:wrapper" to lambdaConstructor.descriptor,
            "method:setContent" to setContent.descriptor, "method:dispose" to dispose.descriptor,
            "method:stateGet" to ui.getValue("method:stateGet"), "field:state" to stateField.descriptor,
            "field:callbackActivity" to callbackActivity.descriptor, "field:navCache" to navigationCache.descriptor,
            "field:key" to parameterField(lambdaConstructor, 1).descriptor,
            "field:tracked" to parameterField(lambdaConstructor, 2).descriptor,
            "field:callback" to parameterField(lambdaConstructor, 3).descriptor,
        ).apply {
            putAll(HostNavigationStateQueries.query(bridge, apk, ui, navigationCache.descriptor))
        }
    }
}
