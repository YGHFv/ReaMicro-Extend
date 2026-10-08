package com.reamicro.fix.core

import com.reamicro.fix.xposed.XposedBridge
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class ComposeInterop(
    private val classLoader: ClassLoader,
    private val resolveClass: (String) -> Class<*>,
    private val unitInstance: () -> Any?,
    private val logPrefix: String,
) {

    fun functionProxy(name: String, functionClassName: String, block: (Array<Any?>?) -> Any?): Any {
        val functionClass = resolveClass(functionClassName)
        return Proxy.newProxyInstance(classLoader, arrayOf(functionClass)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> runCatching { block(args) }
                    .onFailure {
                        XposedBridge.logAlways("$logPrefix failed in $name callback: ${it.stackTraceToString()}")
                    }
                    .getOrElse { unitInstance() }
                "toString" -> "ReaMicro$name"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
    }

    fun colorToArgb(color: Long, fallback: Int = 0): Int =
        runCatching {
            resolveClass(HostClasses.Compose.COLOR_KT).declaredMethods.firstOrNull { method ->
                method.name.contains("toArgb", ignoreCase = true) &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Long::class.javaPrimitiveType
            }?.apply { isAccessible = true }?.invoke(null, color) as? Int
        }.getOrNull() ?: colorFallbackToArgb(color, fallback)

    fun colorFallbackToArgb(color: Long, fallback: Int = 0): Int =
        if ((color and COLOR_SPACE_MASK) == 0L) {
            (color ushr COLOR_ARGB_SHIFT).toInt()
        } else {
            fallback
        }

    fun findMangledMethod(candidates: Array<Method>, namePart: String, parameterCount: Int): Method? =
        candidates.firstOrNull { method ->
            method.name.contains(namePart, ignoreCase = true) &&
                method.parameterTypes.size == parameterCount
        }?.apply { isAccessible = true }

    fun findComposableMethod(
        candidates: Array<Method>,
        baseName: String,
        composerClassName: String,
        firstParameterType: Class<*>? = null,
    ): Method? =
        candidates
            .filter { method ->
                val types = method.parameterTypes
                (method.name == baseName || method.name.startsWith("$baseName-")) &&
                    types.size >= COMPOSABLE_MIN_PARAMETER_COUNT &&
                    (firstParameterType == null || types.first() == firstParameterType) &&
                    types[types.size - 3].name == composerClassName &&
                    types[types.size - 2] == Integer.TYPE &&
                    types[types.size - 1] == Integer.TYPE
            }
            .maxByOrNull { it.parameterTypes.size }
            ?.apply { isAccessible = true }

    private companion object {

        const val COLOR_SPACE_MASK = 0x3fL
        const val COLOR_ARGB_SHIFT = 32

        const val COMPOSABLE_MIN_PARAMETER_COUNT = 4
    }
}
