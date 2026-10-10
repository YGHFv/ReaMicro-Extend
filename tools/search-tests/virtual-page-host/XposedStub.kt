package com.reamicro.fix.xposed

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

internal open class XC_MethodHook {
    class MethodHookParam(val thisObject: Any?, val args: Array<Any?>?) {
        var stopped = false
        var result: Any? = null
            set(value) { field = value; stopped = true }
    }
    open fun beforeHookedMethod(param: MethodHookParam) = Unit
}

internal object XposedBridge {
    val hooks = linkedMapOf<Method, XC_MethodHook>()
    fun hookMethod(method: Method, hook: XC_MethodHook): XposedInterface.HookHandle {
        hooks[method] = hook
        return object : XposedInterface.HookHandle {
            override fun unhook() { hooks.remove(method) }
        }
    }
    fun log(message: String) = Unit
}

internal object XposedHelpers {
    fun getObjectField(target: Any?, name: String): Any? = requireNotNull(target).javaClass
        .getDeclaredField(name).apply { isAccessible = true }.get(target)
}
