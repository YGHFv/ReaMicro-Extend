package com.reamicro.fix.core

import android.content.Context
import android.view.View
import com.reamicro.fix.xposed.HookRuntime

/** The host's own overload proves the reusable-view callback slots. */
class HostAndroidViews(private val loader: ClassLoader) {
    private val bindings by lazy { requireNotNull(HostIntegrationBindings.androidView) { "Native AndroidView contract unavailable" } }
    private val noOp by lazy {
        val ui = requireNotNull(HostUiBindings.current)
        ui.callback(ui.type("function1")) {}
    }
    fun render(factory: Any, modifier: Any, composer: Any, update: Any? = null, onRelease: Any? = null) {
        val ui = requireNotNull(HostUiBindings.current)
        require(ui.loader === loader && ui.type("function1").isInstance(factory))
        val invoke = ui.method("invoke1")
        val guardedFactory = ui.function(ui.type("function1"), fallback = ::emptyView) { args ->
            runCatching {
                requireNotNull(invoke.invoke(factory, args?.firstOrNull()) as? View) {
                    "Native AndroidView factory did not return a View"
                }
            }.onFailure { HookRuntime.logError("ReaMicro native AndroidView factory failed", it) }
                .getOrElse { emptyView(args) }
        }
        bindings.method("render").invoke(null, guardedFactory, modifier, null,
            onRelease ?: bindings.field("release").get(null), update ?: noOp, composer, 0, 4)
    }

    // Only a framework View is created on rejection; no old module UI or listener is installed.
    private fun emptyView(args: Array<out Any?>?): View =
        View(requireNotNull(args?.firstOrNull() as? Context) { "Native AndroidView context unavailable" })
}
