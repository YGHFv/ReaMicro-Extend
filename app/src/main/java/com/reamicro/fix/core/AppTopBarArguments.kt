package com.reamicro.fix.core

internal object AppTopBarArguments {

    @Suppress("LongParameterList")
    fun plan(
        parameterTypes: Array<Class<*>>,
        composer: Any,
        title: String,
        onBack: Any? = null,
        navIcon: Any? = null,
        windowInsets: Any? = null,
        actions: Any? = null,
        function0ClassName: String = HostClasses.Kotlin.FUNCTION0,
        function3ClassName: String = HostClasses.Kotlin.FUNCTION3,
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

        val actionsIndex = if (actions != null) {
            (0 until composerIndex).lastOrNull { parameterTypes[it].name == function3ClassName } ?: -1
        } else {
            -1
        }
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
                i == actionsIndex -> args[i] = actions
                else -> {

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

    private const val TRAILING_PARAMETER_COUNT = 3
}
