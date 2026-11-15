package com.reamicro.fix.core

import com.reamicro.fix.xposed.HookRuntime

class ComposeInterop(
    private val classLoader: ClassLoader,
    private val resolveClass: (String) -> Class<*>,
    private val unitInstance: () -> Any?,
    private val logPrefix: String,
) {

    fun functionProxy(name: String, functionClassName: String, block: (Array<Any?>?) -> Any?): Any =
        functionProxy(name, functionClassName, { unitInstance() }, block)

    fun functionProxy(name: String, functionClassName: String, fallback: (Array<Any?>?) -> Any?, block: (Array<Any?>?) -> Any?): Any {
        val ui = requireNotNull(HostUiBindings.current)
        require(ui.loader === classLoader)
        val functionClass = when (functionClassName) {
            "kotlin.jvm.functions.Function0" -> ui.type("function0")
            "kotlin.jvm.functions.Function1" -> ui.type("function1")
            "kotlin.jvm.functions.Function2" -> ui.type("function2")
            "kotlin.jvm.functions.Function3" -> ui.type("function3")
            else -> resolveClass(functionClassName)
        }
        return ui.function(functionClass, fallback = { args -> fallback(args?.map { it }?.toTypedArray()) }) { args ->
            val callbackArgs = args?.map { it }?.toTypedArray()
            runCatching { block(callbackArgs) }
                .onFailure { HookRuntime.logAlways("$logPrefix failed in $name callback: ${it.stackTraceToString()}") }
                .getOrElse { fallback(callbackArgs) }
        }
    }
    fun colorToArgb(color: Long, fallback: Int = 0): Int =
        runCatching { requireNotNull(HostUiBindings.current).call("colorToArgb", color) as Int }
            .getOrElse { colorFallbackToArgb(color, fallback) }
    fun colorFallbackToArgb(color: Long, fallback: Int = 0): Int =
        if ((color and 0x3fL) == 0L) {
            (color ushr 32).toInt()
        } else {
            fallback
        }

}
