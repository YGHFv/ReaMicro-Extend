package com.reamicro.fix.core

import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiField

import com.reamicro.fix.xposed.XposedHelpers

/** 导航静态化后仍只操作宿主自己的路由栈。 */
object HostNavigation {
    @JvmStatic
    fun navigate(loader: ClassLoader, scope: Any, route: Any) {
        val contract = HostNativeAbi.navigation(HostSymbols.profileId)
        if (contract != null) {
            val type = loader.hostClass("app.zhendong.reamicro.NavGraphScope")
            val method = type.declaredMethods.single {
                it.name == contract.navigate && it.parameterCount == 2 && it.parameterTypes[0] == type
            }
            method.invoke(null, scope, route)
        } else XposedHelpers.callMethod(scope, "navigate", route, null, null)
    }

    @JvmStatic
    fun pop(scope: Any) {
        val contract = HostNativeAbi.navigation(HostSymbols.profileId)
        if (contract != null) {
            scope.javaClass.hostAbiMethod(contract.pop).invoke(scope)
        } else XposedHelpers.callMethod(scope, "popBackStack")
    }

    @JvmStatic
    fun controller(scope: Any): Any =
        if (HostNativeAbi.navigation(HostSymbols.profileId) != null) requireNotNull(scope.javaClass
            .hostAbiField(HostNativeAbi.navigation(HostSymbols.profileId)!!.controllerField).get(scope))
        else requireNotNull(XposedHelpers.callMethod(scope, "getNavController"))
}
