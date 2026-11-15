package com.reamicro.fix.hook

import com.reamicro.fix.core.HostAccountBindings
import com.reamicro.fix.core.HostNavigation
import com.reamicro.fix.core.HostSettingsFeatures
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime

/** 保留账号安全页全部原生项及注销行为，只追加模块账号管理项。 */
internal class HostAccountSecurityBridge(private val hook: ReaMicroSettingsHook) {
    fun install() {
        val symbols = requireNotNull(HostAccountBindings.ui)
        check(symbols.method("item") == requireNotNull(HostSettingsFeatures.current).item)
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            handles += HookRuntime.hookMethod(symbols.method("root"), object : HookCallback() {
                override fun before(param: Call) {
                    val scope = param.args.firstOrNull()?.takeIf(symbols.type("scope")::isInstance) ?: return
                    hook.lastKnownNavGraphScope = scope
                    hook.currentSettingsNavGraphScope = scope
                    hook.currentSettingsNavController = HostNavigation.controller(scope)
                }
            })
            handles += HookRuntime.hookMethod(symbols.method("builder"), object : HookCallback() {
                override fun after(param: Call) {
                    val owner = param.thisObject ?: return
                    if (param.throwable != null || !symbols.type("info").isInstance(symbols.field("builder.info").get(owner)) ||
                        !symbols.type("viewModel").isInstance(symbols.field("builder.viewModel").get(owner))) return
                    val settings = hook.settings.snapshot()
                    if (!settings.canRunAccountCompletion) return
                    val scope = param.args.firstOrNull()?.takeIf(symbols.type("listScope")::isInstance) ?: return
                    ModuleResources.lease()?.use {
                        hook.insertAccountSwitchEntryItem(scope)
                        if (settings.canRunAccountDataExport) {
                            hook.addLazyItem(scope, 0x524D7061, itemKey = "reamicro-account-data-export") { composer ->
                                hook.renderAccountSecurityExportContent(composer)
                            }
                        }
                        if (installed.compareAndSet(false, true)) {
                            HookRuntime.logAlways("ReaMicro settings placement: account-switch inserted in native account configuration")
                        }
                    }
                }
            })
            listOf("root", "content", "builder").forEach { HookRuntime.deoptimize(symbols.method(it)) }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
    }
    private val installed = java.util.concurrent.atomic.AtomicBoolean()
}
