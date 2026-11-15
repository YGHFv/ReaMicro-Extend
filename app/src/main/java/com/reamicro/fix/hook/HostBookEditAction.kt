package com.reamicro.fix.hook

import com.reamicro.fix.core.HostBookUiBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import io.github.libxposed.api.XposedInterface
import java.util.ArrayDeque

/** Reuses the host's complete top-bar icon button, including its click indication. */
internal class HostBookEditAction(private val loader: ClassLoader) {
    private val host get() = requireNotNull(HostBookUiBindings.metadata)
    private val ui get() = requireNotNull(HostUiBindings.current)
    private val vector by lazy { requireNotNull(host.call("edit")) }

    fun render(label: String, composer: Any, onClick: () -> Unit) {
        EmbeddedHostUi.captureTypography(loader, composer)
        BookImageDialogStyle.capture(loader, composer)
        val book = host
        val native = ui
        check(book.type("button.function2") == native.type("function2") &&
            native.type("function3").isAssignableFrom(book.type("button.extra")))
        retainNativeIconLabels(book)
        val scheme = native.call("colorScheme", composer)
        val click = native.callback(book.type("function")) { onClick() }
        val args = arrayOfNulls<Any>(book.constructor("button").parameterCount)
        mapOf("tag" to book.number("button.tag").toByte(),
            "color" to book.field("button.foreground").getLong(scheme),
            "vector" to vector, "click" to click, "extra" to book.field("button.empty").get(null))
            .forEach { (role, value) -> args[book.number("button.slot.$role")] = value }
        val renderer = book.constructor("button").newInstance(*args)
        val frame = IconLabel(composer, vector, label)
        val stack = labels.get() ?: ArrayDeque<IconLabel>().also(labels::set)
        stack.addLast(frame)
        try {
            native.method("invoke2").invoke(native.lambda(0x524D5450, renderer), composer, 0)
        } finally {
            check(stack.removeLast() === frame)
            if (stack.isEmpty()) labels.remove()
        }
    }

    private companion object {
        data class IconLabel(val composer: Any, val vector: Any, val label: String)
        val labels = ThreadLocal<ArrayDeque<IconLabel>>()
        val labelGate = Any()
        var labelAttempted = false
        var labelHook: XposedInterface.HookHandle? = null

        fun retainNativeIconLabels(book: HostBookUiBindings.Contract) = synchronized(labelGate) {
            if (labelAttempted) return@synchronized
            labelAttempted = true
            runCatching {
                val handle = HookRuntime.hookMethod(book.method("icon"), object : HookCallback() {
                    override fun before(param: Call) {
                        val frame = labels.get()?.peekLast() ?: return
                        if (param.args.getOrNull(0) === frame.vector && param.args.getOrNull(4) === frame.composer &&
                            param.args.getOrNull(1) == "") param.args[1] = frame.label
                    }
                })
                labelHook = handle
                ModuleResources.cleanup {
                    synchronized(labelGate) {
                        labelHook = null
                        labels.remove()
                    }
                    handle.unhook()
                }
                // The native body inlines the button; keep its original icon call observable.
                HookRuntime.deoptimize(book.method("button.body"))
            }.onFailure { HookRuntime.logError("ReaMicro native edit icon label unavailable", it) }
        }
    }
}
