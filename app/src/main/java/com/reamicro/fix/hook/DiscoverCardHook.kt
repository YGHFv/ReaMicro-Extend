package com.reamicro.fix.hook

import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostDiscoverBindings
import com.reamicro.fix.core.HostGraphicsBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import io.github.libxposed.api.XposedInterface.HookHandle

internal class DiscoverCardHook(private val classLoader: ClassLoader) {
    private lateinit var host: HostDiscoverBindings.Contract
    private data class Frame(
        var click: Any? = null,
        var base: Any? = null,
        var pendingPadding: Boolean = false,
        var row: Boolean = false,
        var iconDecorated: Boolean = false,
        var labelDecorated: Boolean = false,
        var scope: Any? = null,
        var spacer: Any? = null,
        var dividerColor: Long? = null,
        var injected: Boolean = false,
        var rendering: Boolean = false,
    )
    private val frames = ThreadLocal<java.util.ArrayDeque<Frame>>()
    private val discoverIcon by lazy { com.reamicro.fix.hook.discover.DiscoverIcon(classLoader) }
    private fun frame() = frames.get()?.peekLast()?.takeUnless { it.rendering }

    fun install(): Boolean {
        val handles = arrayListOf<HookHandle>()
        val result = runCatching {
            host = requireNotNull(HostDiscoverBindings.current) { "Discover community contract unavailable" }
            requireNotNull(HostGraphicsBindings.current) { "Native vector contract unavailable" }
            requireNotNull(HostUiBindings.current) { "Native UI contract unavailable" }
            fun hook(key: String, before: (HookCallback.Call) -> Unit = {}, after: (HookCallback.Call) -> Unit = {}) {
                handles += HookRuntime.hookMethod(host.method(key), object : HookCallback(51) {
                    override fun before(param: Call) = before(param)
                    override fun after(param: Call) = after(param)
                })
            }
            hook("community", before = {
                val stack = frames.get() ?: java.util.ArrayDeque<Frame>().also(frames::set)
                stack.addLast(Frame())
            }, after = {
                val stack = frames.get()
                if (stack != null) { if (stack.isNotEmpty()) stack.removeLast(); if (stack.isEmpty()) frames.remove() }
            })
            hook("clickable", before = before@{ param ->
                val state = frame() ?: return@before
                if (state.click != null) return@before
                val click = param.args[host.index("clickable.callback")] ?: return@before
                if (!host.isEntryClick(click)) return@before
                if (ReaMicroSettingsHook.activeInstanceOrNull() == null || discoverIcon.imageVectorOrNull() == null) return@before
                state.click = click; state.base = param.args[host.index("clickable.modifier")]; state.pendingPadding = true
                param.result = state.base
            })
            hook("paddingAll", before = before@{ param ->
                val state = frame() ?: return@before
                if (!state.pendingPadding || param.args[0] !== state.base) return@before
                val ui = requireNotNull(HostUiBindings.current)
                check(param.args[1] == ui.call("dpInt", 16)) { "Community row padding changed" }
                state.pendingPadding = false; state.row = true
                param.result = ui.call("fillWidth", param.args[0], 1f)
            })
            hook("rowImage", before = before@{ param ->
                val state = frame() ?: return@before
                if (!state.row || state.iconDecorated || state.injected) return@before
                val hook = ReaMicroSettingsHook.activeInstanceOrNull() ?: return@before
                param.args[host.index("rowImage.modifier")] = hook.leftCellIconModifier(param.args[host.index("rowImage.composer")]!!, click(state))
                clearDefault(param, "rowImage", 4)
                state.iconDecorated = true
            })
            hook("text", before = before@{ param ->
                val state = frame() ?: return@before
                if (!state.row || !state.iconDecorated || state.labelDecorated || state.injected) return@before
                val hook = ReaMicroSettingsHook.activeInstanceOrNull() ?: return@before
                param.args[host.index("text.modifier")] = hook.leftCellLabelModifier(param.args[host.index("text.composer")]!!, click(state))
                clearDefault(param, "text", 2)
                state.labelDecorated = true
            })
            hook("weight", after = after@{ param ->
                val state = frame() ?: return@after
                if (!state.row || !state.labelDecorated || state.injected || state.spacer != null || param.throwable != null) return@after
                state.scope = param.thisObject; state.spacer = param.result
            })
            hook("spacer", before = before@{ param ->
                val state = frame() ?: return@before
                if (!state.row || state.injected || state.spacer == null || param.args[host.index("spacer.modifier")] !== state.spacer) return@before
                val hook = ReaMicroSettingsHook.activeInstanceOrNull() ?: return@before
                state.rendering = true
                try {
                    param.args[host.index("spacer.modifier")] = hook.leftCellSpacerModifier(requireNotNull(state.scope), param.args[host.index("spacer.composer")]!!, click(state))
                } finally { state.rendering = false }
            })
            hook("icon", before = before@{ param ->
                val state = frame() ?: return@before
                if (!state.row || !state.labelDecorated || state.injected) return@before
                val scope = state.scope ?: return@before
                val divider = state.dividerColor ?: return@before
                state.rendering = true
                try {
                    renderDiscoverButton(classLoader, scope, param.args[host.index("icon.composer")]!!,
                        param.args[host.index("icon.tint")] as Long, param.args[0]!!, divider, click(state), ::openDiscoverPage,
                        requireNotNull(discoverIcon.imageVectorOrNull()))
                    state.injected = true; param.result = null
                } finally { state.rendering = false }
            })
            hook("divider", before = before@{ param ->
                val state = frame() ?: return@before
                state.dividerColor = param.args[host.index("divider.color")] as Long
                if (state.row && state.injected) {
                    param.args[host.index("divider.modifier")] = requireNotNull(HostUiBindings.current).value("modifier")
                    state.row = false
                }
            })
            HookRuntime.deoptimize(host.method("community"))
            HookRuntime.deoptimize(host.method("divider"))
        }
        if (result.isFailure) handles.asReversed().forEach { runCatching { it.unhook() } }
        HookInstallReport.record("DiscoverCardHook", "community", result.isSuccess, result.exceptionOrNull())
        result.exceptionOrNull()?.let { HookRuntime.logError("ReaMicro Discover community install failed", it) }
        return result.isSuccess
    }

    private fun clearDefault(param: HookCallback.Call, key: String, bit: Int) {
        val index = host.index("$key.mask"); param.args[index] = (param.args[index] as Int) and bit.inv()
    }
    private fun click(frame: Frame): () -> Unit {
        val callback = requireNotNull(frame.click)
        return { host.method("callback").invoke(callback); Unit }
    }
    private fun openDiscoverPage() {
        val settings = ReaMicroSettingsHook.activeInstanceOrNull()?.settings?.snapshot() ?: return
        if (!settings.moduleEnabled || !settings.onlineDiscoverEnabled) return
        if (!ReaMicroSettingsHook.openDiscoverPage()) HookRuntime.log("[ReaMicro] Discover navigation unavailable")
    }
}
