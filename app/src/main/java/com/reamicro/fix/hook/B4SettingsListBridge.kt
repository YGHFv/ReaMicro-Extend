package com.reamicro.fix.hook

import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiField

import com.reamicro.fix.core.HostNavigation
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

/** LazyColumn 的构建 lambda 会晚于设置根 composable 执行，作用域必须覆盖实际构建。 */
internal class B4SettingsListBridge(private val hook: ReaMicroSettingsHook) {
    fun install() {
        val loader = hook.classLoader
        val type = loader.hostClass("ReaMicroHost.SettingsListContent")
        val scopeType = loader.hostClass(NAV_GRAPH_SCOPE_CLASS)
        val vmType = loader.hostClass("ReaMicroHost.SettingsListViewModel")
        val invoke = type.hostAbiMethod("invoke", Any::class.java)
        val branch = type.hostAbiField("a")
        val scopeField = type.hostAbiField("b")
        val vmField = type.hostAbiField("c")
        XposedBridge.hookMethod(invoke, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val callback = param.thisObject ?: return
                if (branch.getByte(callback).toInt() != 15 || !vmType.isInstance(vmField.get(callback))) return
                val scope = scopeField.get(callback)?.takeIf(scopeType::isInstance) ?: return
                hook.currentSettingsNavGraphScope = scope
                hook.currentSettingsNavController = HostNavigation.controller(scope)
                param.setObjectExtra("reamicro.settings.listDepth", hook.settingsBuildDepth.get())
                param.setObjectExtra("reamicro.settings.listCount", hook.itemCount.get())
                hook.settingsBuildDepth.set(hook.settingsBuildDepth.get() + 1)
                hook.itemCount.set(0)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val depth = param.getObjectExtra("reamicro.settings.listDepth") as? Int ?: return
                val count = hook.itemCount.get()
                hook.settingsBuildDepth.set(depth)
                hook.itemCount.set(param.getObjectExtra("reamicro.settings.listCount") as Int)
                XposedBridge.logAlways("ReaMicro B4 settings native list built items=$count")
            }
        })
        XposedBridge.deoptimize(invoke)
        loader.hostClass(LAZY_LIST_SCOPE_CLASS).hostAbiMethod(
            "o", loader.hostClass(LAZY_LIST_SCOPE_CLASS), String::class.java,
            loader.hostClass(FUNCTION3_CLASS), Int::class.javaPrimitiveType,
        ).let(XposedBridge::deoptimize)
    }
}
