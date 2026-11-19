package com.reamicro.fix.core

import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class HostRowWeight(private val method: Method, modifier: Class<*>) {
    private val explicitFill: Boolean

    init {
        require(!Modifier.isStatic(method.modifiers) && method.returnType == modifier) {
            "Invalid host row weight: $method"
        }
        val float = Float::class.javaPrimitiveType
        explicitFill = when (method.parameterTypes.toList()) {
            listOf(modifier, float) -> false
            listOf(float, modifier, Boolean::class.javaPrimitiveType) -> true
            else -> throw IllegalArgumentException("Unsupported host row weight parameters: $method")
        }
    }

    operator fun invoke(scope: Any, base: Any, weight: Float = 1f): Any = requireNotNull(
        if (explicitFill) method.invoke(scope, weight, base, true) else method.invoke(scope, base, weight)
    )
}
