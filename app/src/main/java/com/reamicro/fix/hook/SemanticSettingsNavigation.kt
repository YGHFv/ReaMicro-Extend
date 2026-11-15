package com.reamicro.fix.hook

import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostNavigation
import com.reamicro.fix.core.HostReaderFeatures
import com.reamicro.fix.core.HostRuntimeBindings
import com.reamicro.fix.core.HostSettingsFeatures
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime

/** Uses the host's registered About destination and current settings scope, without R8 branch numbers. */
internal class SemanticSettingsNavigation(private val hook: ReaMicroSettingsHook) {
    fun install(): Boolean =
        HookInstallReport.install("ReaMicroSettingsHook", "semanticNativeSettings") {
            val settings = requireNotNull(HostSettingsFeatures.current)
            val ui = requireNotNull(HostUiBindings.current)
            val navigation = requireNotNull(HostRuntimeBindings.navigation)
            val unit = requireNotNull(HostReaderFeatures.current).unit
            val about = requireNotNull(ui.field("destinationCallback").get(ui.value("aboutContent")))
            val body = ui.method("aboutBody")
            val composition = requireNotNull(com.reamicro.fix.core.HostCompositionBindings.current)
            val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
            try {
                handles += HookRuntime.hookMethod(composition.method("rememberNavigation"), object : HookCallback() {
                    override fun before(param: Call) {
                        hook.navigationPages.beginRememberNavigation()
                    }
                    override fun after(param: Call) {
                        hook.navigationPages.endRememberNavigation()
                    }
                })
                handles += HookRuntime.hookMethod(composition.method("navigationFactory"), object : HookCallback() {
                    override fun before(param: Call) {
                        val context = param.args.firstOrNull() as? android.content.Context ?: return
                        hook.navigationPages.restoreNavigationFactory(context)?.let { param.result = it }
                    }
                })
                handles += HookRuntime.hookMethod(composition.method("navigationRestore"), object : HookCallback() {
                    override fun before(param: Call) {
                        val snapshot = hook.navigationPages.beginNativeRestore() ?: return
                        param.args[0] = snapshot
                        param.setObjectExtra("ReaMicroNavigationRestore", true)
                    }
                    override fun after(param: Call) {
                        if (param.getObjectExtra("ReaMicroNavigationRestore") == true) {
                            hook.navigationPages.finishNativeRestore(param.result, param.throwable)
                        }
                    }
                })
                handles += HookRuntime.hookMethod(settings.root, object : HookCallback() {
                    override fun before(param: Call) {
                        val scope = param.args.firstOrNull()?.takeIf(navigation.scope::isInstance) ?: return
                        hook.activityProvider()?.let { hook.consumeModuleSettingsIntent(it, it.intent) }
                        hook.captureModuleNavigationScope(scope)
                    }
                })
                val home = requireNotNull(com.reamicro.fix.core.HostHomeBindings.bookshelf)
                handles += HookRuntime.hookMethod(home.method("screen"), object : HookCallback() {
                    override fun before(param: Call) {
                        hook.activityProvider()?.let { hook.consumeModuleSettingsIntent(it, it.intent) }
                        param.args.firstOrNull()?.takeIf(navigation.scope::isInstance)?.let(hook::captureModuleNavigationScope)
                    }
                })
                handles += HookRuntime.hookMethod(navigation.constructor,
                    object : HookCallback() {
                        override fun after(param: Call) {
                            if (param.throwable == null) param.thisObject?.let(hook::captureModuleNavigationScope)
                        }
                    })
                SettingsEntryPlacement.install(settings, handles, hook::insertModuleSettingsItem)
                handles += HookRuntime.hookMethod(body, object : HookCallback() {
                    override fun before(param: Call) {
                        if (param.thisObject !== about) return
                        val entry = param.args.getOrNull(1) ?: return
                        val route = hook.navigationPages.route(entry) ?: return
                        val composer = param.args.getOrNull(2)?.takeIf(ui.composerClass::isInstance) ?: return
                        ModuleResources.lease()?.use {
                            HostSettingsDialogs.capture(hook, composer)
                            ui.instance("startGroup", composer, 0x524D534E)
                            try {
                                hook.renderInjectedSettingsScreen(route, composer)
                            } finally {
                                ui.instance("endGroup", composer, false)
                            }
                            param.result = unit
                        }
                    }
                })
                handles += HookRuntime.hookMethod(navigation.navigate, object : HookCallback() {
                    override fun before(param: Call) {
                        param.args.firstOrNull()?.takeIf(navigation.scope::isInstance)?.let(hook::captureModuleNavigationScope)
                    }
                    override fun after(param: Call) {
                        if (param.throwable == null && !hook.navigatingModuleRoute.get()) {
                            hook.navigationPages.synchronize(hook.currentSettingsNavController)
                        }
                    }
                })
                handles += HookRuntime.hookMethod(navigation.pop, object : HookCallback() {
                    override fun after(param: Call) {
                        if (param.throwable == null) {
                            param.thisObject?.let(HostNavigation::controller)?.let(hook.navigationPages::synchronize)
                        }
                    }
                })
                handles += HookRuntime.hookMethod(navigation.controllerPop, object : HookCallback() {
                    override fun after(param: Call) {
                        if (param.throwable == null) hook.navigationPages.synchronize(param.thisObject)
                    }
                })
                listOf(settings.root, settings.builder, home.method("screen"), body, ui.method("aboutInvoke"), ui.method("aboutWrapper"), navigation.navigate, navigation.pop,
                    navigation.controllerPop, composition.method("rememberNavigation"),
                    composition.method("navigationFactory"), composition.method("navigationRestore"))
                    .forEach(HookRuntime::deoptimize)
            } catch (error: Throwable) {
                handles.asReversed().forEach { handle ->
                    runCatching { handle.unhook() }.onFailure { error.addSuppressed(it) }
                }
                throw error
            }
        }
}
