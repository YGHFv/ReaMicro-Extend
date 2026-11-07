package com.reamicro.fix.core

import java.lang.reflect.Field
import java.lang.reflect.Method

/** 预绑定被 R8 内联的 getter，热路径不再用异常探测方法是否存在。 */
class HostAccessor private constructor(
    private val method: Method?,
    private val field: Field?,
) {
    fun get(target: Any): Any? = method?.invoke(target) ?: field?.get(target)

    companion object {
        fun bind(type: Class<*>, getter: String): HostAccessor? {
            val resolved = HostSymbols.resolveMethodName(type, getter)
            val publicMethods = type.methods
            val method = sequenceOf(resolved, getter).distinct().firstNotNullOfOrNull { name ->
                publicMethods.firstOrNull { it.parameterCount == 0 && it.name == name }
            }?.apply { isAccessible = true } ?: declaredMethod(type, resolved)
            if (method != null) return HostAccessor(method, null)
            val name = HostSymbols.resolveAccessorFieldName(type, getter)
            if (name != null) {
                var owner: Class<*>? = type
                while (owner != null) {
                    val field = owner.declaredFields.firstOrNull { it.name == name }
                        ?.apply { isAccessible = true }
                    if (field != null) return HostAccessor(null, field)
                    owner = owner.superclass
                }
            }
            return if (resolved != getter) declaredMethod(type, getter)?.let { HostAccessor(it, null) } else null
        }
        private fun declaredMethod(type: Class<*>, name: String): Method? {
            var owner: Class<*>? = type
            while (owner != null) {
                owner.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?.let { return it.apply { isAccessible = true } }
                owner = owner.superclass
            }
            return null
        }
    }
}
