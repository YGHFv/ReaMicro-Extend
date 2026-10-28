package com.reamicro.fix.hook
import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiField

import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostNavigation
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostMethodsNamed
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

/** 精确限定 R8 合并闭包分支，恢复原生设置列表中的单个模块行。 */
internal class B2SettingsUiBridge(private val hook: ReaMicroSettingsHook) {
    private var builderCalls = 0
    private var entries = 0
    fun install() {
        HookInstallReport.install("ReaMicroSettingsHook", "b2NativeSettingsList") {
            val loader = hook.classLoader
            val builder = loader.hostClass("ReaMicroHost.SettingsListBuilder")
            val invoke = builder.hostAbiMethod("invoke", Any::class.java)
            XposedBridge.hookMethod(invoke, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val owner = param.thisObject ?: return
                    val branch = builder.hostAbiField("a").getByte(owner).toInt()
                    if (++builderCalls <= 8 || branch == 15) {
                        XposedBridge.logAlways("ReaMicro B2 settings builder branch=$branch")
                    }
                    if (branch != 15) return
                    val scope = builder.hostAbiField("b").get(owner) ?: return
                    hook.currentSettingsNavGraphScope = scope
                    hook.currentSettingsNavController = HostNavigation.controller(scope)
                    hook.settingsBuildDepth.set(hook.settingsBuildDepth.get() + 1)
                    hook.itemCount.set(0)
                    param.setObjectExtra("reamicro.settings.builder", true)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.getObjectExtra("reamicro.settings.builder") != true) return
                    hook.settingsBuildDepth.set((hook.settingsBuildDepth.get() - 1).coerceAtLeast(0))
                    hook.itemCount.set(0)
                }
            })
            val scope = loader.hostClass("ReaMicroHost.LazyListScope")
            val item = scope.declaredMethods.single { it.name == "m" && it.parameterCount == 3 }
            XposedBridge.hookMethod(item, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (hook.settingsBuildDepth.get() <= 0 || hook.injectingModuleItem.get()) return
                    val count = hook.itemCount.get() + 1
                    hook.itemCount.set(count)
                    if (count == INSERT_AFTER_SETTINGS_ITEM_COUNT) {
                        entries++
                        XposedBridge.logAlways("ReaMicro B2 settings insertion count=$entries")
                        hook.insertModuleSettingsItem(param.thisObject ?: return)
                    }
                }
            })
            val text = loader.hostClass("androidx.compose.material3.TextKt").hostMethodsNamed(TEXT_METHOD)
                .single { it.parameterCount == 20 && it.parameterTypes.first() == String::class.java }
            XposedBridge.hookMethod(text, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val title = hook.settingsEntryTitleOverride.get() ?: return
                    if (param.args?.firstOrNull() == HOST_ABOUT_TITLE) param.args[0] = title
                }
            })
            val callers = listOf(invoke) + scope.declaredMethods.filter { it.name == "n" } + scope.declaredConstructors
            val deoptimized = callers.count { XposedBridge.deoptimize(it) }
            XposedBridge.logAlways("ReaMicro B2 native settings list installed: scoped builder/item/text deoptimized=$deoptimized/${callers.size}")
        }
        HookInstallReport.install("ReaMicroSettingsHook", "b2NativeNavigation") {
            val scope = hook.classLoader.hostClass(NAV_GRAPH_SCOPE_CLASS)
            val navigate = scope.declaredMethods.single { it.name == "a" && it.parameterCount == 2 && it.parameterTypes[0] == scope }
            XposedBridge.hookMethod(navigate, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    hook.lastKnownNavGraphScope = param.args?.firstOrNull()
                    val route = param.args?.getOrNull(1) ?: return
                    if (route.javaClass.name == HostSymbols.resolveClass(ROUTE_ABOUT_CLASS) && !hook.navigatingModuleRoute.get()) {
                        hook.injectedRouteStack = emptyList()
                        hook.setInjectedRouteState(null)
                    }
                }
            })
            XposedBridge.hookMethod(scope.hostAbiMethod("b"), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (hook.poppingInjectedRoute.get() || hook.currentSettingsNavGraphScope !== param.thisObject) return
                    if (hook.handleNestedInjectedBack()) {
                        param.result = null
                    } else hook.consumeTopInjectedRoute("b2-native-back")
                }
            })
        }
        HookInstallReport.install("ReaMicroSettingsHook", "b2ControllerBack") {
            val controller = hook.classLoader.hostClass("ReaMicroHost.NavController")
            val pop = controller.hostAbiMethod("a")
            check(pop.returnType == Boolean::class.javaPrimitiveType)
            XposedBridge.hookMethod(pop, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (hook.poppingInjectedRoute.get() || hook.currentSettingsNavController !== param.thisObject) return
                    if (hook.activeInjectedRoute() == null) return
                    if (hook.handleNestedInjectedBack()) param.result = true
                    else hook.consumeTopInjectedRoute("b2-controller-back")
                }
            })
        }
        HookInstallReport.install("ReaMicroSettingsHook", "b2AboutRoute") {
            val about = hook.classLoader.hostClass(ABOUT_SCREEN_CLASS).hostMethodsNamed(ABOUT_SCREEN_METHOD)
                .single { it.parameterCount == 2 }
            XposedBridge.hookMethod(about, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val route = hook.activeInjectedRoute() ?: return
                    val composer = param.args?.getOrNull(1) ?: return
                    XposedBridge.logAlways("ReaMicro B2 injected settings route=${route.title}")
                    hook.renderInjectedSettingsScreen(route, composer)
                    param.result = null
                }
            })
        }
    }
}

/** B4 的设置页已被 R8 合并到 moc/wq5，不能再复用 B2 的 facade。 */
internal class B4SettingsUiBridge(private val hook: ReaMicroSettingsHook) {
    private var settingsCalls = 0

    fun install() {
        HookInstallReport.install("ReaMicroSettingsHook", "b4NativeSettingsList") {
            B4SettingsListBridge(hook).install()
            val settings = hook.classLoader.hostClass(SETTINGS_SCREEN_CLASS)
                .hostAbiMethod("a", hook.classLoader.hostClass(NAV_GRAPH_SCOPE_CLASS), hook.classLoader.hostClass(COMPOSER_CLASS), Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            XposedBridge.hookMethod(settings, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    hook.currentSettingsNavGraphScope = param.args?.getOrNull(0)
                    hook.currentSettingsNavController = hook.currentSettingsNavGraphScope?.let {
                        runCatching { HostNavigation.controller(it) }.getOrNull()
                    }
                    hook.settingsBuildDepth.set(hook.settingsBuildDepth.get() + 1)
                    hook.itemCount.set(0)
                    if (++settingsCalls <= 4) {
                        XposedBridge.logAlways("ReaMicro B4 settings screen captured scope=${hook.currentSettingsNavGraphScope != null}")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    hook.settingsBuildDepth.set((hook.settingsBuildDepth.get() - 1).coerceAtLeast(0))
                    if (hook.settingsBuildDepth.get() == 0) hook.itemCount.set(0)
                }
            })

            val scope = hook.classLoader.hostClass(LAZY_LIST_SCOPE_CLASS)
            val item = scope.hostAbiMethod(
                "n", Any::class.java, Any::class.java,
                hook.classLoader.hostClass(FUNCTION3_CLASS),
            ).apply { isAccessible = true }
            XposedBridge.hookMethod(item, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (hook.settingsBuildDepth.get() <= 0 || hook.injectingModuleItem.get()) return
                    val count = hook.itemCount.get() + 1
                    hook.itemCount.set(count)
                    if (count == INSERT_AFTER_SETTINGS_ITEM_COUNT) {
                        hook.insertModuleSettingsItem(param.thisObject ?: return)
                    }
                }
            })

            val about = hook.classLoader.hostClass(ABOUT_SCREEN_CLASS).hostAbiMethod(
                "a", Int::class.javaPrimitiveType, hook.classLoader.hostClass(COMPOSER_CLASS),
            ).apply { isAccessible = true }
            XposedBridge.hookMethod(about, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val route = hook.activeInjectedRoute() ?: return
                    val composer = param.args?.getOrNull(1) ?: return
                    hook.renderInjectedSettingsScreen(route, composer)
                    param.result = null
                }
            })
            XposedBridge.deoptimize(about)
            val text = hook.classLoader.hostClass(TEXT_KT_CLASS).hostMethodsNamed(TEXT_METHOD)
                .single { it.parameterCount == 20 && it.parameterTypes.first() == String::class.java }
            XposedBridge.hookMethod(text, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val title = hook.settingsEntryTitleOverride.get() ?: return
                    if (param.args.firstOrNull() == HOST_ABOUT_TITLE) param.args[0] = title
                }
            })
            hook.classLoader.hostClass("ReaMicroHost.SettingsAboutLabel").hostAbiMethod(
                "C", Any::class.java, Any::class.java,
            ).let(XposedBridge::deoptimize)
            XposedBridge.logAlways("ReaMicro B4 native settings list installed")
        }

        HookInstallReport.install("ReaMicroSettingsHook", "b4NativeNavigation") {
            val scope = hook.classLoader.hostClass(NAV_GRAPH_SCOPE_CLASS)
            val navigate = scope.hostAbiMethod("b", scope, Any::class.java).apply { isAccessible = true }
            XposedBridge.hookMethod(navigate, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    hook.lastKnownNavGraphScope = param.args?.getOrNull(0)
                    val route = param.args?.getOrNull(1) ?: return
                    if (!hook.navigatingModuleRoute.get() && route.javaClass == hook.classLoader.hostClass(ROUTE_ABOUT_CLASS)) {
                        hook.injectedRouteStack = emptyList()
                        hook.setInjectedRouteState(null)
                    }
                }
            })
            XposedBridge.hookMethod(scope.hostAbiMethod("a").apply { isAccessible = true }, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (hook.poppingInjectedRoute.get() || hook.currentSettingsNavGraphScope !== param.thisObject) return
                    if (hook.handleNestedInjectedBack()) param.result = null
                    else hook.consumeTopInjectedRoute("b4-native-back")
                }
            })
            val controller = hook.classLoader.hostClass("ReaMicroHost.NavController")
            XposedBridge.hookMethod(controller.hostAbiMethod("c").apply { isAccessible = true }, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (hook.poppingInjectedRoute.get() || !hook.isCurrentSettingsNavController(param.thisObject)) return
                    if (hook.handleNestedInjectedBack()) param.result = true
                    else hook.consumeTopInjectedRoute("b4-controller-back")
                }
            })
        }
    }
}
