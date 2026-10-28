package com.reamicro.fix.hook

import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers

/**
 * 订阅宿主 ViewModel 的销毁时机。
 *
 * androidx.lifecycle.ViewModel 在混淆版里连类名带方法名一起被改：
 * `clear$lifecycle_viewmodel` 在 2.3.3-b2 里实际叫 `gyb#b`。因此这里不再写死名字，
 * 而是按「逻辑宿主类 + 逻辑成员名」走符号解析，并保留多重回退：
 * 解析名 -> 原始名 -> 结构探测（父类里唯一的无参 void final 方法）-> onCleared。
 */
internal fun hookViewModelCleared(viewModelClass: Class<*>, onCleared: (Any) -> Unit) {
    val clear = findViewModelClear(viewModelClass)
        ?: findViewModelOnCleared(viewModelClass)
        ?: error("ViewModel clear/onCleared not resolvable on $viewModelClass")
    XposedBridge.hookMethod(clear, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val viewModel = param.thisObject ?: return
            if (viewModelClass.isInstance(viewModel)) onCleared(viewModel)
        }
    })
}

private const val VIEW_MODEL_CLASS = "androidx.lifecycle.ViewModel"
private const val CLEAR_LOGICAL_NAME = "clear\$lifecycle_viewmodel"

private fun findViewModelClear(viewModelClass: Class<*>): java.lang.reflect.Method? {
    val candidates = linkedSetOf(
        HostSymbols.resolveMethodName(VIEW_MODEL_CLASS, CLEAR_LOGICAL_NAME),
        CLEAR_LOGICAL_NAME,
    )
    for (name in candidates) {
        var current: Class<*>? = viewModelClass.superclass
        while (current != null && current != Any::class.java) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterTypes.isEmpty() &&
                    method.returnType == Void.TYPE
            }?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
    }
    return null
}

/** 兜底：宿主 ViewModel 自己覆写的 onCleared（符号解析后可能改名为单字母）。 */
private fun findViewModelOnCleared(viewModelClass: Class<*>): java.lang.reflect.Method? {
    val logicalOwner = HostSymbols.logicalClass(viewModelClass.name)
    val names = linkedSetOf(
        HostSymbols.resolveMethodName(logicalOwner, "onCleared"),
        "onCleared",
    )
    for (name in names) {
        runCatching {
            XposedHelpers.findMethodExact(viewModelClass, name)
                .takeIf { it.parameterTypes.isEmpty() && it.returnType == Void.TYPE }
        }.getOrNull()?.let { return it }
    }
    return null
}
