package com.reamicro.fix.hook

import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiField

import com.reamicro.fix.core.HostNavigation
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.hook.settings.*
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

/** 保留账号安全页全部原生项及注销行为，只追加模块账号管理项。 */
internal class B4AccountSecurityBridge(private val hook: ReaMicroSettingsHook) {
    fun install() {
        check(HostSymbols.abiProfileId == "2.3.3-b4")
        val callback = hook.cls("ReaMicroHost.HomeSearchList")
        val branch = callback.hostAbiField("a")
        val user = callback.hostAbiField("b")
        val vm = callback.hostAbiField("c")
        val state = callback.hostAbiField("d")
        val dialog = callback.hostAbiField("e")
        val accountVm = hook.cls("ReaMicroHost.AccountSecurityViewModel")
        val accountUser = hook.cls("ReaMicroHost.AccountSecurityUser")
        val invoke = callback.hostAbiMethod("invoke", Any::class.java)
        XposedBridge.hookMethod(invoke, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject ?: return
                if (param.throwable != null || branch.getByte(owner) != 0.toByte()) return
                if (!accountVm.isInstance(vm.get(owner)) || !accountUser.isInstance(user.get(owner))) return
                val settings = hook.settings.snapshot()
                if (!settings.canRunAccountCompletion) return
                val scope = param.args[0] ?: return
                hook.lastKnownNavGraphScope?.let {
                    hook.currentSettingsNavGraphScope = it
                    hook.currentSettingsNavController = HostNavigation.controller(it)
                }
                hook.insertAccountSwitchEntryItem(scope)
                if (settings.canRunAccountDataExport) {
                    hook.addLazyItem(scope, 0x524D7061, itemKey = "b4-account-data-export") { composer ->
                        hook.renderAccountSecurityExportContent(
                            state.get(owner), dialog.get(owner), composer, includeDelete = false,
                        )
                    }
                }
                XposedBridge.logAlways("ReaMicro B4 account security extensions appended")
            }
        })
        XposedBridge.deoptimize(invoke)
        hook.cls("ReaMicroHost.AccountSecurityContent").hostAbiMethod(
            "r", Any::class.java, Any::class.java, Any::class.java,
        ).let(XposedBridge::deoptimize)
    }
}
