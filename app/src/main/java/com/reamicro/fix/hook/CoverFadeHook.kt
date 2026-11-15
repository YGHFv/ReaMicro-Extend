package com.reamicro.fix.hook

import com.reamicro.fix.core.HostBookUiBindings
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/** One transition decision for shelf, loading, book details and reading banners. */
internal class CoverFadeHook(
    private val loader: ClassLoader,
    private val settingsProvider: () -> ModuleSettingsSnapshot,
) {
    private val knownSources = CoverSources()
    private val handles = ArrayList<XposedInterface.HookHandle>()
    private val runtimeFailureLogged = AtomicBoolean()
    @Volatile private var closed = false
    private var installed = false

    @Synchronized fun install() {
        if (closed || installed) return
        val host = requireNotNull(HostBookUiBindings.fade) { "Cover transition contract unavailable" }
        val noFade = requireNotNull(host.field("noFade").get(null))
        val pending = ArrayList<XposedInterface.HookHandle>()
        try {
            host.constructors("publisher.").forEach { constructor ->
                pending += HookRuntime.hookMethod(constructor, object : HookCallback() {
                    override fun after(param: Call) {
                        if (!closed && param.throwable == null) knownSources.remember(host.field("publisherCover").get(param.thisObject))
                    }
                })
            }
            pending += HookRuntime.hookMethod(host.method("oldCover"), object : HookCallback() {
                override fun before(param: Call) {
                    if (!closed) knownSources.remember(param.args[host.number("coverSource")])
                }
            })
            pending += HookRuntime.hookMethod(host.method("create"), object : HookCallback() {
                override fun before(param: Call) {
                    if (closed || !CoverFadePolicy.enabled(settingsProvider())) return
                    val result = param.args[1] ?: return
                    runCatching {
                        val request = host.method("request").invoke(result) ?: return@runCatching
                        if ((0..1).any { CoverFadePolicy.isCoverKey(host.field("key.$it").get(request) as? String) } ||
                            knownSources.contains(host.field("data").get(request))) {
                            param.result = requireNotNull(host.method("immediate").invoke(noFade, param.args[0], result))
                        }
                    }.onFailure { error ->
                        if (runtimeFailureLogged.compareAndSet(false, true)) HookRuntime.logError("ReaMicro cover transition binding failed", error)
                    }
                }
            })
            HookRuntime.deoptimize(host.method("update"))
            handles += pending
            installed = true
            HookRuntime.logAlways("ReaMicro cover transition hook ready (DexKit)")
        } catch (error: Throwable) {
            pending.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            knownSources.clear()
            throw error
        }
    }
    fun onHostStarted() = install()
    @Synchronized fun closeForReload() {
        closed = true
        handles.asReversed().forEach { runCatching { it.unhook() } }
        handles.clear()
        knownSources.clear()
    }
}
