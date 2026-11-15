package com.reamicro.fix.hook

import com.reamicro.fix.core.HostDialogBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.core.hostMethodReturning
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.ArrayDeque

/** Keep the native action's text layout and theme; replace its words only while it renders. */
internal object HostSentenceActionContent {
    private val labels = ThreadLocal<ArrayDeque<String>>()
    private val renderers = hashSetOf<Method>()
    val label: String? get() = labels.get()?.peekLast()

    init {
        ModuleResources.cleanup {
            labels.remove()
            synchronized(renderers) { renderers.clear() }
        }
    }

    fun labeled(key: Int, original: Any, title: String): Any {
        val ui = requireNotNull(HostUiBindings.current)
        val native = requireNotNull(HostDialogBindings.current)
        check(ui.type("function3").isInstance(original))
        val callback = if (native.type("lambda").isInstance(original)) {
            requireNotNull(native.field("contentCallback").get(original))
        } else original
        check(ui.type("function3").isInstance(callback))
        val invoke = ui.method("invoke3")
        val body = callback.javaClass.hostMethodReturning(invoke.name, invoke.returnType, *invoke.parameterTypes)
        synchronized(renderers) {
            if (body !in renderers) {
                HookRuntime.deoptimize(body)
                renderers += body
            }
        }
        return ui.lambda(key, ui.function(ui.type("function3")) { args ->
            render(original, title, requireNotNull(args))
        })
    }

    fun render(original: Any, title: String, arguments: Array<out Any?>): Any? {
        val ui = requireNotNull(HostUiBindings.current)
        check(arguments.size == 3 && ui.type("function3").isInstance(original))
        val stack = labels.get() ?: ArrayDeque<String>().also(labels::set)
        stack.addLast(title)
        try {
            return ui.method("invoke3").invoke(original, *arguments)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        } finally {
            stack.pollLast()
            if (stack.isEmpty()) labels.remove()
        }
    }
}
