package com.reamicro.fix.hook
import com.reamicro.fix.core.hostAbiMethodByArity

import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiField

import com.reamicro.fix.cloud.webdav.hasWebDavLogin
import com.reamicro.fix.core.HostNativeAbi
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostField
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/** 从实际注册的 KClass 识别页面，不依赖合成 lambda 的分支编号。 */
internal class B4StorageRouteBridge(private val drive: WebDavDriveHook) {
    private val loader = drive.classLoader
    private fun cls(name: String) = loader.hostClass(name)
    private val registered = Collections.synchronizedMap(WeakHashMap<Any, String>())

    fun install() {
        check(HostSymbols.abiProfileId == "2.3.3-b4")
        val scopeType = cls(NAV_GRAPH_SCOPE_CLASS)
        val contentType = cls("ReaMicroHost.RouteContent")
        val wrapperType = cls("ReaMicroHost.ComposableLambda")
        val reflection = cls("ReaMicroHost.Reflection").hostAbiMethod("a", Class::class.java)
        val routeClasses = listOf(ROUTE_THIRD_LOGIN_CLASS, ROUTE_THIRD_ACCOUNT_CLASS)
            .associateWith(::cls)
        val routeTokens = routeClasses.mapValues { reflection.invoke(null, it.value) }
        val wrapperContent = wrapperType.hostAbiField("c").apply { isAccessible = true }
        val scopeField = contentType.declaredFields.single { it.type == scopeType }
            .apply { isAccessible = true }
        val decode = cls("ReaMicroHost.RouteDecoder").hostAbiMethodByArity("e", 2).apply { isAccessible = true }
        val register = cls("ReaMicroHost.RouteRegistration").hostAbiMethodByArity("d", 3).apply { isAccessible = true }
        XposedBridge.hookMethod(register, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val route = routeTokens.entries.singleOrNull { it.value == param.args[1] }?.key ?: return
                val content = wrapperContent.get(param.args[2]) ?: return
                check(contentType.isInstance(content)) { "Unverified B4 route content: $route" }
                registered[content] = route
                XposedBridge.logAlways("ReaMicro B4 storage route registered: $route")
            }
        })
        val invoke = contentType.hostAbiMethod("y", Any::class.java, Any::class.java,
            Any::class.java, Any::class.java)
        XposedBridge.hookMethod(invoke, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val callback = param.thisObject ?: return
                val routeName = registered[callback] ?: return
                val route = decode.invoke(null, param.args[1], routeTokens.getValue(routeName)) ?: return
                val kind = route.javaClass.hostField("type").getInt(route)
                if (kind != BACKUP_TYPE_WEBDAV && kind != BACKUP_TYPE_LOCAL_LIBRARY) {
                    clearAccountContext()
                    return
                }
                val scope = scopeField.get(callback) ?: return
                val composer = param.args[2] ?: return
                val rendered = when (routeName) {
                    ROUTE_THIRD_LOGIN_CLASS -> {
                        if (kind != BACKUP_TYPE_WEBDAV) return
                        drive.renderWebDavLoginRoute(scope, composer)
                        true
                    }
                    else -> {
                        clearAccountContext()
                        if (kind == BACKUP_TYPE_WEBDAV) drive.renderWebDavAccountRoute(scope, composer)
                        else drive.renderLocalLibraryAccountRoute(scope, composer)
                    }
                }
                if (rendered) {
                    param.result = drive.targetUnit()
                    XposedBridge.logAlways("ReaMicro B4 storage route rendered: $routeName kind=$kind")
                }
            }
        })
        XposedBridge.deoptimize(invoke)
        cls("ReaMicroHost.RouteSetup").hostAbiMethod("invoke", Any::class.java)
            .let(XposedBridge::deoptimize)

        val navigation = requireNotNull(HostNativeAbi.navigation(HostSymbols.profileId))
        val navigate = scopeType.hostAbiMethod(navigation.navigate, scopeType, Any::class.java)
        val storageType = cls(ROUTE_STORAGE_CLASS)
        XposedBridge.hookMethod(navigate, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                clearAccountContext()
                val route = param.args[1] ?: return
                if (route.javaClass != storageType) return
                if (storageType.hostField("type").getInt(route) == BACKUP_TYPE_WEBDAV &&
                    !hasWebDavLogin(drive.currentContext())) {
                    param.args[1] = drive.newThirdLoginRoute()
                }
            }
        })
        XposedBridge.deoptimize(navigate)
        val controller = cls("ReaMicroHost.NavController").hostAbiMethod(navigation.controllerPop)
        XposedBridge.hookMethod(controller, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.result == true) clearAccountContext()
            }
        })
    }

    private fun clearAccountContext() {
        drive.clearWebDavAccountContext()
        drive.clearLocalLibraryAccountContext()
    }
}
