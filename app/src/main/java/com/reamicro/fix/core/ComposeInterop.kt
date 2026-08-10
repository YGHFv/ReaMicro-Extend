package com.reamicro.fix.core

import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Compose 反射互操作的共用实现。
 *
 * 这些逻辑此前在六七个 hook 里各存一份，并且已经出现了实现漂移——例如
 * [colorToArgb]：三处带「按名字找 toArgb 方法 + 位运算兜底」，第四处却硬编码了
 * 混淆后的方法名 `toArgb-8_81llA` 且没有兜底，Compose 版本一变就只有那一处会崩。
 *
 * 这里只收拢「逻辑」，不统一「解析语义」：各 hook 的 `cls()` / `staticObject()`
 * 兜底链各不相同（有的先 declaredMethods、有的先 methods），贸然统一会改变实际
 * 解析到的成员。因此类名解析与 Unit 实例仍由调用方注入。
 */
class ComposeInterop(
    private val classLoader: ClassLoader,
    private val resolveClass: (String) -> Class<*>,
    private val unitInstance: () -> Any?,
    private val logPrefix: String,
) {

    /**
     * 生成一个 Kotlin 函数接口（Function0/1/2/3）的动态代理，用于把模块的 lambda
     * 传给宿主 Compose 代码。
     *
     * [name] 只用于出错时的日志定位；回调内抛出的异常会被记录并降级为 Unit，
     * 避免异常穿透到宿主的 Compose 渲染栈里导致整页崩溃。
     */
    fun functionProxy(name: String, functionClassName: String, block: (Array<Any?>?) -> Any?): Any {
        val functionClass = resolveClass(functionClassName)
        return Proxy.newProxyInstance(classLoader, arrayOf(functionClass)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> runCatching { block(args) }
                    .onFailure {
                        XposedBridge.log("$logPrefix failed in $name callback: ${it.stackTraceToString()}")
                    }
                    .getOrElse { unitInstance() }
                "toString" -> "ReaMicro$name"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
    }

    /**
     * 把 Compose 的 `Color`（inline class，运行时是 ULong）转成 Android ARGB。
     *
     * 方法名带 Compose 的 inline class mangling 后缀（形如 `toArgb-8_81llA`），
     * 后缀会随 Compose 版本变化，所以按名字前缀 + 签名查找而不是写死全名。
     * 查找失败时退回位运算：sRGB 色彩空间下高 32 位即为 ARGB。
     */
    fun colorToArgb(color: Long, fallback: Int = 0): Int =
        runCatching {
            resolveClass(HostClasses.Compose.COLOR_KT).declaredMethods.firstOrNull { method ->
                method.name.contains("toArgb", ignoreCase = true) &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Long::class.javaPrimitiveType
            }?.apply { isAccessible = true }?.invoke(null, color) as? Int
        }.getOrNull() ?: colorFallbackToArgb(color, fallback)

    /**
     * [colorToArgb] 的兜底：sRGB（色彩空间编号 0）时高 32 位就是 ARGB，
     * 其他色彩空间无法用位运算还原，返回调用方指定的默认色。
     */
    fun colorFallbackToArgb(color: Long, fallback: Int = 0): Int =
        if ((color and COLOR_SPACE_MASK) == 0L) {
            (color ushr COLOR_ARGB_SHIFT).toInt()
        } else {
            fallback
        }

    /** 在 [candidates] 中挑出名字匹配、参数个数匹配的方法，用于混淆名不稳定的场景。 */
    fun findMangledMethod(candidates: Array<Method>, namePart: String, parameterCount: Int): Method? =
        candidates.firstOrNull { method ->
            method.name.contains(namePart, ignoreCase = true) &&
                method.parameterTypes.size == parameterCount
        }?.apply { isAccessible = true }

    private companion object {
        /** Compose Color 低 6 位是色彩空间编号，0 表示 sRGB。 */
        const val COLOR_SPACE_MASK = 0x3fL
        const val COLOR_ARGB_SHIFT = 32
    }
}
