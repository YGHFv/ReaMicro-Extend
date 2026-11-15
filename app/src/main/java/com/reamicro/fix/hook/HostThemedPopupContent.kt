package com.reamicro.fix.hook

import com.reamicro.fix.core.HostUiBindings
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.util.WeakHashMap

/** The host lambda owns the restart group; capturing a parent never emits its siblings. */
internal class HostThemedPopupContent(private val capture: (Any) -> Unit) {
    private val contents = WeakHashMap<Any, WeakReference<Any>>()
    private val wrappers = WeakHashMap<Any, Boolean>()

    fun wrap(original: Any): Any = synchronized(contents) {
        if (wrappers.containsKey(original)) return original
        contents[original]?.get()?.let { return it }
        val ui = requireNotNull(HostUiBindings.current)
        check(ui.type("function2").isInstance(original))
        val invoke = ui.method("invoke2")
        fun forward(args: Array<out Any?>?): Any? {
            try {
                return invoke.invoke(original, *requireNotNull(args))
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
        val wrapped = ui.lambda(0x524D5443, ui.function(ui.type("function2"), ::forward) { args ->
            args?.getOrNull(0)?.takeIf(ui.composerClass::isInstance)?.let(capture)
            forward(args)
        })
        wrappers[wrapped] = true
        contents[original] = WeakReference(wrapped)
        wrapped
    }

    fun clear() = synchronized(contents) {
        contents.clear()
        wrappers.clear()
    }
}
