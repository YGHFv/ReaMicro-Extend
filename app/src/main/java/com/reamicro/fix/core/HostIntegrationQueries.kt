package com.reamicro.fix.core

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/** Contracts that connect module views and actions to the host navigation graph. */
internal object HostIntegrationQueries {
    private fun List<MethodData>.one(label: String): MethodData = distinctBy { it.descriptor }.singleOrNull()
        ?: error("$label: expected one method, found ${distinctBy { it.descriptor }.map { it.descriptor }}")

    private fun contains(value: HostDexCalls.Value, expected: HostDexCalls.Value): Boolean =
        value == expected || value is HostDexCalls.Call && value.arguments.any { contains(it, expected) }

    fun activity(bridge: DexKitBridge): Map<String, String> {
        val activity = requireNotNull(bridge.getClassData("Lapp/zhendong/reamicro/MainActivity;"))
        val create = activity.methods.filter { it.name == "onCreate" &&
            it.paramTypeNames == listOf("android.os.Bundle") && it.returnTypeName == "void" &&
            !Modifier.isStatic(it.modifiers) }.one("main activity creation")
        val resume = activity.methods.filter { it.name == "onResume" && it.paramCount == 0 &&
            it.returnTypeName == "void" && !Modifier.isStatic(it.modifiers) }.one("main activity resume")
        return mapOf("class:activity" to activity.descriptor, "method:create" to create.descriptor,
            "method:resume" to resume.descriptor)
    }

    fun androidView(bridge: DexKitBridge, apk: String, reader: Map<String, String>): Map<String, String> {
        val title = requireNotNull(bridge.getMethodData(reader.getValue("title")))
        val modifier = title.paramTypeNames[1]
        val composer = title.paramTypeNames[2]
        val function = "kotlin.jvm.functions.Function1"
        val render = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .paramTypes(function, modifier, function, function, function, composer, "int", "int")
            .returnType("void"))).filter { Modifier.isStatic(it.modifiers) }.one("AndroidView with release callback")
        // The simple overload proves reset/release/update slots and the default reset mask.
        val simple = render.callers.filter {
            Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" &&
                it.paramTypeNames == listOf(function, modifier, function, composer, "int")
        }.one("AndroidView simple overload")
        val call = HostDexCalls(apk).calls(simple.descriptor).filter { it.descriptor == render.descriptor }.single()
        check(call.arguments[0] == HostDexCalls.Parameter(0) && call.arguments[1] == HostDexCalls.Parameter(1) &&
            call.arguments[2] == HostDexCalls.Literal(0) && call.arguments[3] is HostDexCalls.Field &&
            call.arguments[4] == HostDexCalls.Parameter(2) && call.arguments[5] == HostDexCalls.Parameter(3) &&
            call.arguments[7] == HostDexCalls.Literal(4)) { "AndroidView callback/default slots changed" }
        return linkedMapOf("method:render" to render.descriptor,
            "class:modifier" to title.paramTypes[1].descriptor, "class:composer" to title.paramTypes[2].descriptor,
            "field:release" to (call.arguments[3] as HostDexCalls.Field).descriptor)
    }

    fun webDavRoute(bridge: DexKitBridge, apk: String, navigation: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        fun method(key: String, descriptor: String): MethodData = requireNotNull(bridge.getMethodData(descriptor)).also {
            out["${if (it.isConstructor) "constructor" else "method"}:$key"] = it.descriptor
        }
        val route = requireNotNull(bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
            .usingEqStrings("S3SyncConfig(preset="))).filter { it.paramCount == 0 && it.returnTypeName == "java.lang.String" }
            .one("S3 configuration route").declaredClass)
        out["class:route"] = route.descriptor
        method("route", route.methods.filter { it.isConstructor && it.paramTypeNames == listOf("java.lang.String") }
            .one("S3 preset constructor").descriptor)
        out["field:preset"] = route.fields.single { !Modifier.isStatic(it.modifiers) && it.typeName == "java.lang.String" }.descriptor
        val flow = HostDexCalls(apk)
        val routeValue = HostDexCalls.Type(route.descriptor)
        val (setup, registration) = HostNavigationRegistration.find(bridge, flow, route.descriptor)
        method("setup", setup)
        val register = method("register", registration.descriptor)
        val tokenCall = registration.arguments[1] as? HostDexCalls.Call ?: error("S3 token is not a class conversion")
        val token = method("token", tokenCall.descriptor)
        check(token.paramTypeNames == listOf("java.lang.Class") && token.returnTypeName == register.paramTypeNames[1])
        if (!Modifier.isStatic(token.modifiers)) {
            out["field:tokenReceiver"] = (tokenCall.arguments.firstOrNull() as? HostDexCalls.Field)?.descriptor
                ?: error("S3 token receiver is not a static singleton")
        }
        out["value:contentSlot"] = registration.arguments.lastIndex.toString()
        val wrapper = registration.arguments.last() as HostDexCalls.Allocation
        val wrapperInit = flow.calls(setup).single {
            it.arguments.firstOrNull() == wrapper && it.descriptor.contains("-><init>(IZLjava/lang/Object;)V")
        }
        val content = wrapperInit.arguments[3] as? HostDexCalls.Allocation ?: error("S3 content allocation unavailable")
        val contentType = requireNotNull(bridge.getClassData(content.type))
        out["class:content"] = contentType.descriptor
        out["class:scope"] = navigation.getValue("class:scope")
        out["field:scope"] = contentType.fields.single {
            !Modifier.isStatic(it.modifiers) && it.type.descriptor == navigation.getValue("class:scope")
        }.descriptor
        val render = method("render", contentType.methods.filter {
            !Modifier.isStatic(it.modifiers) && it.paramTypeNames == List(4) { "java.lang.Object" } &&
                it.returnTypeName == "java.lang.Object"
        }.one("S3 destination content").descriptor)
        val contentInit = flow.calls(setup).single { it.arguments.firstOrNull() == content &&
            bridge.getMethodData(it.descriptor)?.isConstructor == true }
        val branchReads = render.usingFields.map { it.field.descriptor }.toSet()
        flow.writes(contentInit.descriptor).filter { it.receiver == HostDexCalls.Parameter(0) && it.field in branchReads &&
            it.field.substringAfter(':') in setOf("B", "I", "S", "Z") }.forEachIndexed { index, write ->
            val value = when (val source = write.value) {
                is HostDexCalls.Literal -> source
                is HostDexCalls.Parameter -> contentInit.arguments[source.index] as? HostDexCalls.Literal
                else -> null
            } ?: error("S3 destination branch is not a proven constant")
            out["field:branch.$index"] = write.field
            out["value:branch.$index"] = value.value.toString()
        }
        out["value:branchCount"] = out.keys.count { it.startsWith("field:branch.") }.toString()
        val decodeCall = flow.calls(render.descriptor).filter { call ->
            call.arguments.size == 2 && contains(call.arguments[1], routeValue) &&
                bridge.getMethodData(call.descriptor)?.let {
                    Modifier.isStatic(it.modifiers) && it.paramCount == 2 && it.returnTypeName == "java.lang.Object" &&
                        it.paramTypeNames[1] == token.returnTypeName
                } == true
        }.singleOrNull() ?: error("S3 back-stack decoder unavailable")
        method("decode", decodeCall.descriptor)
        check(decodeCall.arguments[0] == HostDexCalls.Parameter(2)) { "S3 back-stack argument moved" }
        val lambda = requireNotNull(bridge.getClassData(wrapper.type))
        out["class:lambda"] = lambda.descriptor
        val invocation = lambda.methods.filter {
            it.paramTypeNames == List(4) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object"
        }.one("destination lambda invocation")
        val body = invocation.invokes.filter {
            it.className == lambda.name && it.paramCount == 4 && it.paramTypeNames.take(2) == List(2) { "java.lang.Object" } &&
                it.paramTypeNames.last() == "int"
        }.one("destination lambda body")
        val callback = body.invokes.filter {
            it.declaredClass?.let { c -> Modifier.isInterface(c.modifiers) } == true &&
                it.paramTypeNames == List(4) { "java.lang.Object" } && it.returnTypeName == "java.lang.Object"
        }.one("destination callback")
        val receiver = flow.calls(body.descriptor).single { it.descriptor == callback.descriptor }.arguments.first()
        out["field:callback"] = (receiver as? HostDexCalls.Field)?.descriptor ?: error("Destination callback field unavailable")
        return out
    }
}
