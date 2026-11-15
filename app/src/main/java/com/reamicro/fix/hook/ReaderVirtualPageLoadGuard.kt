package com.reamicro.fix.hook

import com.reamicro.fix.core.HostReaderExtrasBindings
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

internal class ReaderVirtualPageLoadGuard(private val reader: ReaderHook) {
    fun install() {
        val host = requireNotNull(HostReaderExtrasBindings.virtualPageGuard) { "Virtual page guard contract unavailable" }
        val unit = requireNotNull(host.field("unit").get(null))
        val discarded = AtomicLong()
        val installed = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            for (key in listOf("window", "mapped")) {
                installed += HookRuntime.hookMethod(host.method(key), object : HookCallback() {
                    override fun before(param: Call) {
                        if (param.thisObject !== reader.currentViewModelRef?.get() || !reader.canRunFullTextSearch()) return
                        val args = param.args
                        // Resumption invokes the original state machine with a null EPUB and placeholder page.
                        if (!host.type("epub").isInstance(args[host.index("$key.epub")])) return
                        val requested = args[host.index("$key.page")] as Int
                        @Suppress("UNCHECKED_CAST")
                        val pages = host.field("pages").get(param.thisObject) as Map<Int, *>
                        if (ReaderVirtualPageLoadPolicy.accepts(requested, pages.keys)) return
                        // The outer loader otherwise clamps its old page after the window call. Cancellation
                        // reaches its native finally block and releases the mutex without moving the window.
                        if (host.type("loadContinuation").isInstance(args[host.index("$key.continuation")])) {
                            param.throwable = CancellationException("Stale reader virtual page load: $requested")
                        } else param.result = unit
                        val count = discarded.incrementAndGet()
                        if (count <= 4 || count % 64L == 0L) {
                            val spines = host.field("spines").get(param.thisObject) as? Map<*, *>
                            HookRuntime.log("ReaMicro stale virtual page load dropped page=$requested " +
                                "window=${pages.keys.minOrNull()}..${pages.keys.maxOrNull()} spines=${spines?.size} count=$count")
                        }
                    }
                })
            }
            host.methods("caller.").forEach(HookRuntime::deoptimize)
        } catch (failure: Throwable) {
            installed.asReversed().forEach { runCatching { it.unhook() } }
            throw failure
        }
    }
}
