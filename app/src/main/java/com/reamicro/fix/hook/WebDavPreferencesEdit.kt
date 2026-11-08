package com.reamicro.fix.hook

import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostClass
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Native DataStore must receive a failed edit when validation or a preference mutation fails. */
internal object WebDavPreferencesEdit {
    fun create(loader: ClassLoader, beforeWrite: () -> Unit, transform: (Any) -> Any): Any {
        val function = loader.hostClass("kotlin.jvm.functions.Function2")
        return Proxy.newProxyInstance(loader, arrayOf(function)) { proxy, method, args ->
            when (HostSymbols.logicalMethodName(method)) {
                "invoke" -> {
                    val preferences = requireNotNull(args?.getOrNull(0))
                    beforeWrite()
                    try {
                        transform(preferences)
                    } catch (error: InvocationTargetException) {
                        throw error.targetException ?: error
                    }
                }
                "toString" -> "ReaMicroWebDavPreferencesEdit"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
    }
}
