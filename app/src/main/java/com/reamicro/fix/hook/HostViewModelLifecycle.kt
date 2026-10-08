package com.reamicro.fix.hook

import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers

internal fun hookViewModelCleared(viewModelClass: Class<*>, onCleared: (Any) -> Unit) {
    val clear = XposedHelpers.findMethodExact(viewModelClass, "clear\$lifecycle_viewmodel")
    check(clear.returnType == Void.TYPE) { "Unexpected ViewModel clear signature: $clear" }
    XposedBridge.hookMethod(clear, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val viewModel = param.thisObject ?: return
            if (viewModelClass.isInstance(viewModel)) onCleared(viewModel)
        }
    })
}
