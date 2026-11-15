package com.reamicro.fix.hook

import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostReaderRenderBindings
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime

/** Content subscribes to paint generations and supplies the queried resolved-mark local. */
internal class ReaderSearchContentBridge(private val reader: ReaderHook) {
    private val depth = ThreadLocal.withInitial { 0 }
    fun install() {
        HookInstallReport.install("ReaderHook", "searchContentPaint") {
            val symbols = requireNotNull(HostReaderRenderBindings.current)
            val localMarks = requireNotNull(symbols.field("localMarks").get(null))
            val composer = symbols.index("content.composer")
            val handles = mutableListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
            try {
                handles += HookRuntime.hookMethod(symbols.method("content"), object : HookCallback() {
                    override fun before(param: Call) {
                        val args = param.args
                        val dom = args[symbols.index("content.dom")] ?: return
                        if (reader.observeSearchPaint(dom)) args[composer + 1] = (args[composer + 1] as Int) or 1
                        param.setObjectExtra("reamicro.search.contentDepth", depth.get())
                        depth.set(depth.get() + 1)
                    }
                    override fun after(param: Call) {
                        val previous = param.getObjectExtra("reamicro.search.contentDepth") as? Int ?: return
                        if (previous == 0) depth.remove() else depth.set(previous)
                    }
                })
                handles += HookRuntime.hookMethod(symbols.method("consume"), object : HookCallback() {
                    override fun after(param: Call) {
                        if (depth.get() <= 0 || param.throwable != null || param.args.firstOrNull() !== localMarks) return
                        val original = param.result as? List<*> ?: return
                        val next = reader.appendSearchResolvedMarker(original)
                        if (next != original) param.result = next
                    }
                })
                HookRuntime.deoptimize(symbols.method("content"))
            } catch (error: Throwable) {
                handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
                throw error
            }
            HookRuntime.log("ReaMicro search Content paint bridge installed")
        }
    }
}
