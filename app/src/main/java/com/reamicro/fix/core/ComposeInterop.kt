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

    /**
     * 按「基础名 + Composable 尾参形状」定位宿主 @Composable 方法，不写死参数个数与 mangling 后缀。
     *
     * 两件事都会随宿主版本变化，写死任何一个都会在升级后静默失效：
     * - **参数个数**：新增一个可选参数就变。
     * - **方法名**：函数签名里出现 inline value class（`Color`/`Dp`/`TextUnit`）时 Kotlin 会给
     *   JVM 方法名追加 mangling 后缀，形如 `AppTopBar-cd68TDI`。宿主 2.3.1 给 `AppTopBar`
     *   加了 `contentColor: Color` 参数，方法名就从裸 `AppTopBar` 变成了带后缀的形式。
     *
     * 因此按 `name == baseName || name.startsWith("$baseName-")` 匹配：连字符是关键，它接受
     * mangling 后缀，同时排除 `AppTopBarPreview` 这类同前缀的不同函数，以及 `AppTopBar$lambda$0`
     * 这类合成方法。再要求末三参为 `(Composer, int, int)`——Compose 编译器给每个 @Composable
     * 追加的 `$composer`/`$changed`/`$default`，这一条同时排除了 `$default` 桥接方法（其末三参
     * 是三个 int）。命中多个时取参数最多者：宿主同时留有新旧重载时，参数多的那个是当前版本。
     *
     * 刻意不复用 [findMangledMethod]：那里用 `contains` + 固定参数个数，既会命中合成 lambda，
     * 又在参数个数变化时失配，正是这里要避免的两种失效。
     */
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
        /** Compose Color 低 6 位是色彩空间编号，0 表示 sRGB。 */
        const val COLOR_SPACE_MASK = 0x3fL
        const val COLOR_ARGB_SHIFT = 32

        /** @Composable 至少有 Composer/$changed/$default 三个尾参，加上一个业务参数。 */
        const val COMPOSABLE_MIN_PARAMETER_COUNT = 4
    }
}
