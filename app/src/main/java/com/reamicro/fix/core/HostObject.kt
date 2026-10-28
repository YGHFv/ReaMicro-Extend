package com.reamicro.fix.core

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Kotlin `object` 单例的兼容访问层。
 *
 * R8 允许把 `object` 的成员全部提升为静态方法并删除 `INSTANCE` 字段（B2 的
 * `org.epub.UIEpubWindow` 就是这种形态），此时任何 `getField("INSTANCE").get(null)`
 * 都会抛 NoSuchFieldException。这里同时支持「实例 receiver」与「纯静态」两种形态，
 * 调用方只写稳定的逻辑方法名。
 */
class HostObject private constructor(
    val type: Class<*>,
    private val receiver: Any?,
) {
    /** 宿主被混淆归约后只剩静态方法，没有实例可用。 */
    val isStaticOnly: Boolean get() = receiver == null

    fun call(logicalName: String, vararg args: Any?): Any? {
        val method = findMethod(logicalName, args.size)
        if (method == null) {
            if (args.isEmpty()) {
                val fieldName = HostSymbols.resolveAccessorFieldName(HostSymbols.logicalClass(type.name), logicalName)
                val field = fieldName?.let(type::hostFieldOrNull)
                if (field != null && (receiver != null || Modifier.isStatic(field.modifiers))) return field.get(receiver)
            }
            throw NoSuchMethodException("${type.name}#$logicalName/${args.size}")
        }
        return try {
            method.invoke(receiver, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        }
    }

    fun callOrNull(logicalName: String, vararg args: Any?): Any? =
        runCatching { call(logicalName, *args) }.getOrNull()

    private fun findMethod(logicalName: String, parameterCount: Int): Method? {
        val resolved = HostSymbols.resolveMethodName(HostSymbols.logicalClass(type.name), logicalName)
        val candidates = (listOf(resolved, logicalName).distinct())
        // 有实例就走实例方法；R8 静态归约形态（无 INSTANCE）只能走静态方法。
        val wantStatic = receiver == null
        var current: Class<*>? = type
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name !in candidates || method.parameterTypes.size != parameterCount) continue
                if (Modifier.isStatic(method.modifiers) == wantStatic) {
                    return method.apply { isAccessible = true }
                }
            }
            current = current.superclass
        }
        return null
    }

    companion object {
        /** 用逻辑类名解析单例；宿主没有 `INSTANCE` 时按静态类使用。 */
        @JvmStatic
        fun of(classLoader: ClassLoader, logicalName: String): HostObject {
            val type = classLoader.hostClass(logicalName)
            val instance = type.hostFieldOrNull("INSTANCE")
                ?.apply { isAccessible = true }
                ?.get(null)
            return HostObject(type, instance)
        }
    }
}
