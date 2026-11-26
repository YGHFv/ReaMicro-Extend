package com.reamicro.fix.core

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge

/** Resolve the same destination registration for settings, storage and S3 routes. */
internal object HostNavigationRegistration {
    fun find(bridge: DexKitBridge, flow: HostDexCalls, route: String): Pair<String, HostDexCalls.Call> {
        fun containsRoute(value: HostDexCalls.Value): Boolean = value == HostDexCalls.Type(route) ||
            value is HostDexCalls.Call && value.arguments.any(::containsRoute)
        val candidates = flow.referencingType(route).flatMap { caller ->
            flow.calls(caller).filter { call ->
                call.arguments.size in setOf(3, 7) && containsRoute(call.arguments[1]) &&
                    (call.arguments.last() is HostDexCalls.Allocation || call.arguments.last() is HostDexCalls.Field) &&
                    bridge.getMethodData(call.descriptor)?.let {
                        Modifier.isStatic(it.modifiers) && it.returnTypeName == "void" && it.paramCount == call.arguments.size
                    } == true
            }.map { caller to it }
        }.distinct()
        return candidates.singleOrNull() ?: error("Destination $route registration missing or ambiguous: $candidates")
    }

    fun validate(register: Method, token: Method, lambda: Class<*>, contentSlot: Int) {
        check(Modifier.isStatic(register.modifiers) && register.returnType == Void.TYPE &&
            register.parameterCount in setOf(3, 7) && contentSlot == register.parameterCount - 1 &&
            register.parameterTypes[1] == token.returnType && register.parameterTypes.last().isAssignableFrom(lambda))
        val function = Class.forName("kotlin.jvm.functions.Function1", false, lambda.classLoader)
        check(register.parameterTypes.drop(2).dropLast(1).all(function::isAssignableFrom))
    }
}
