package com.reamicro.fix.hook

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Native DataStore must receive a failed edit when validation or a preference mutation fails. */
internal object WebDavPreferencesEdit {
    fun create(loader: ClassLoader, invoke: Method, beforeWrite: () -> Unit, transform: (Any) -> Any): Any {
        val function = invoke.declaringClass
        require(function.isInterface && invoke.parameterTypes.toList() == List(2) { Any::class.java })
        return Proxy.newProxyInstance(loader, arrayOf(function)) { proxy, method, args ->
            when {
                method == invoke -> {
                    val preferences = requireNotNull(args?.getOrNull(0))
                    beforeWrite()
                    try {
                        transform(preferences)
                    } catch (error: InvocationTargetException) {
                        throw error.targetException ?: error
                    }
                }
                method.name == "toString" && method.parameterCount == 0 -> "ReaMicroWebDavPreferencesEdit"
                method.name == "hashCode" && method.parameterCount == 0 -> System.identityHashCode(proxy)
                method.name == "equals" && method.parameterCount == 1 -> proxy === args?.firstOrNull()
                else -> error("Unexpected DataStore transform callback: $method")
            }
        }
    }
}
