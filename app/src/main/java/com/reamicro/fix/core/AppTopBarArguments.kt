package com.reamicro.fix.core

/**
 * 宿主 `AppTopBar` 的反射实参铺设。
 *
 * 模块把自己的设置页伪装成宿主 About 路由后，顶栏是直接调宿主 `AppTopBar` 渲染的，
 * 所以必须自己拼出这个 @Composable 的完整实参数组。这处下标映射已经连续崩过三次：
 *
 * - **2.2.0**：`AppTopBar(String, WindowInsets?, ImageVector?, Function0?, Function2?, Composer, I, I)` 8 参。
 * - **2.3.0 beta**：参数重排 + 新增尾随内容槽 `Function3` →
 *   `AppTopBar(String title, Function2 titleContent, WindowInsets, ImageVector navIcon,
 *   Function0 onBack, Function3 actions, Composer, I $changed, I $default)` 9 参。
 *   按固定 8 参、固定顺序传值的旧代码既找不到方法又参数错位。
 * - **2.3.1 beta**：在 `onNavigationBack` 之后新增 `contentColor: Color` →
 *   10 参，且因 `Color` 是 inline value class（运行时 `long`），JVM 方法名被 mangling 成
 *   `AppTopBar-cd68TDI`。
 *
 * 所以这里**不认下标、只认类型**：title 填首个 `String`，onBack/navIcon/windowInsets 按类型
 * 首次匹配，其余业务参数一律交给宿主默认值（在 `$default` 掩码里置位）。末三参固定是
 * Compose 编译器追加的 `Composer`/`$changed`/`$default`。
 *
 * 抽出到 `core` 是因为它在 `hook/` 里没法写单测：这是全模块最脆的一处下标映射，
 * 而三次回归全都表现为「顶栏静默消失」——异常被 Compose 代理的 runCatching 吞掉，
 * 只在 LSPosed 日志留一行，编译器和真机 UI 都不报错。
 */
internal object AppTopBarArguments {

    /**
     * 按 [parameterTypes] 的实际形状铺出 `AppTopBar` 的完整反射实参（含末三参）。
     *
     * [onBack]/[navIcon]/[windowInsets] 传 null 表示「不覆盖，走宿主默认值」。
     * 函数接口代理由调用方构造好再传进来，这里只做纯粹的位置与掩码计算。
     */
    @Suppress("LongParameterList")
    fun plan(
        parameterTypes: Array<Class<*>>,
        composer: Any,
        title: String,
        onBack: Any? = null,
        navIcon: Any? = null,
        windowInsets: Any? = null,
        function0ClassName: String = HostClasses.Kotlin.FUNCTION0,
        imageVectorClassName: String = HostClasses.Compose.IMAGE_VECTOR,
        windowInsetsClassName: String = HostClasses.Compose.WINDOW_INSETS,
    ): Array<Any?> {
        val count = parameterTypes.size
        val composerIndex = count - TRAILING_PARAMETER_COUNT
        val args = arrayOfNulls<Any?>(count)
        var defaultMask = 0
        var backUsed = false
        var navUsed = false
        var insetsUsed = false
        for (i in 0 until composerIndex) {
            val type = parameterTypes[i]
            when {
                i == 0 && type == String::class.java -> args[i] = title
                !backUsed && onBack != null && type.name == function0ClassName -> {
                    args[i] = onBack
                    backUsed = true
                }
                !navUsed && navIcon != null && type.name == imageVectorClassName -> {
                    args[i] = navIcon
                    navUsed = true
                }
                !insetsUsed && windowInsets != null && type.name == windowInsetsClassName -> {
                    args[i] = windowInsets
                    insetsUsed = true
                }
                else -> {
                    // 置位后宿主会自己算默认值并忽略实参，但 Method.invoke 仍要过一遍形参类型校验：
                    // 给基元形参传 null 会直接抛 IllegalArgumentException（2.3.1 的 contentColor 就是
                    // 这种 long）。填零值只为过校验，取值由宿主决定。
                    args[i] = if (type.isPrimitive) primitiveZero(type) else null
                    defaultMask = defaultMask or (1 shl i)
                }
            }
        }
        args[composerIndex] = composer
        args[composerIndex + 1] = 0
        args[composerIndex + 2] = defaultMask
        return args
    }

    /**
     * 基元形参的占位零值。
     *
     * 逐个类型列全而不是只处理 `long`：Compose 的 inline value class 落到 JVM 上是各种基元
     * （`Color`→`long`、`Dp`→`float`、`TextUnit`→`long`），宿主下次再加一个可选参数时不该再断一次。
     */
    private fun primitiveZero(type: Class<*>): Any = when (type) {
        java.lang.Long.TYPE -> 0L
        Integer.TYPE -> 0
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Boolean.TYPE -> false
        Character.TYPE -> Character.MIN_VALUE
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        else -> 0
    }

    /** Compose 编译器给每个 @Composable 追加的尾参：`$composer`、`$changed`、`$default`。 */
    private const val TRAILING_PARAMETER_COUNT = 3
}
