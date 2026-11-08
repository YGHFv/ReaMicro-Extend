package com.reamicro.fix.hook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.View
import com.reamicro.fix.core.B2NativeUi
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostNativeAbi
import com.reamicro.fix.core.HostNavigation
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostAbiField
import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiMethodByArity
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostStaticMethod
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

/** Keeps the editor inside the native S3 destination and its push/pop transitions. */
internal class HostWebDavConfigurationRoute(
    private val loader: ClassLoader,
    private val activityProvider: () -> Activity?,
) {
    private class Entry(
        val marker: String,
        val page: WebDavSyncConfigurationPage,
        scope: Any,
        controller: Any,
        activity: Activity,
    ) {
        val scope = WeakReference(scope)
        val controller = WeakReference(controller)
        val activity = WeakReference(activity)
        var view = WeakReference<View>(null)
        var canceled = false
    }

    private val main = ModuleResources.handler(Looper.getMainLooper())
    private val ui = B2NativeUi(loader)
    private val entries = LinkedHashMap<String, Entry>()
    private val registered = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private var latestScope = WeakReference<Any>(null)
    private var activeMarker: String? = null
    private var installed = false
    private var application: Application? = null
    private val scopeType by lazy { loader.hostClass("app.zhendong.reamicro.NavGraphScope") }
    private val routeType by lazy { loader.hostClass(ROUTE_CLASS) }
    private val routeToken by lazy {
        requireNotNull(loader.hostClass("ReaMicroHost.Reflection")
            .hostAbiMethod("a", Class::class.java).invoke(null, routeType))
    }
    private val contentType by lazy { loader.hostClass("ReaMicroHost.RouteContent") }
    private val scopeField by lazy {
        contentType.declaredFields.single { it.type == scopeType }.apply { isAccessible = true }
    }
    private val decode by lazy {
        loader.hostClass("ReaMicroHost.RouteDecoder").hostAbiMethodByArity("e", 2)
            .apply { isAccessible = true }
    }
    private val unit by lazy { loader.hostClass("kotlin.Unit").hostField("INSTANCE").get(null) }
    private val androidView by lazy {
        val function = loader.hostClass("kotlin.jvm.functions.Function1")
        loader.hostClass("androidx.compose.ui.viewinterop.AndroidView_androidKt").hostAbiMethod(
            "b", function, loader.hostClass("androidx.compose.ui.Modifier"),
            function, function, function, loader.hostClass("androidx.compose.runtime.Composer"),
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
    }
    private val noOp by lazy { function("WebDavRouteUpdate") { unit } }
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(activity: Activity) {
            entries.values.filter { it.activity.get() === activity }.toList().forEach { entry ->
                runCatching { release(entry) }
                    .onFailure { XposedBridge.logError("ReaMicro native WebDAV release failed", it) }
            }
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }

    fun install() {
        if (HostSymbols.profileId != "2.3.4-b1" || installed) return
        HookInstallReport.install("WebDavSyncHook", "nativeConfigurationRoute") {
            val choices = loader.hostStaticMethod(
                "app.zhendong.reamicro.ui.sync.S3SyncScreenKt", "ProviderChoices",
                Boolean::class.javaPrimitiveType, loader.hostClass("kotlin.jvm.functions.Function0"),
                loader.hostClass("kotlin.jvm.functions.Function1"), loader.hostClass("androidx.compose.ui.Modifier"),
                loader.hostClass("androidx.compose.runtime.Composer"), Int::class.javaPrimitiveType,
            )
            XposedBridge.hookMethod(choices, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val callback = param.args.getOrNull(2) ?: return
                    val fields = callback.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
                    if (fields.size != 2 || fields.count { it.type == Byte::class.javaPrimitiveType } != 1) return
                    val field = fields.singleOrNull { it.type == scopeType } ?: return
                    field.isAccessible = true
                    field.get(callback)?.let { latestScope = WeakReference(it) }
                }
            })
            val wrapper = loader.hostClass("ReaMicroHost.ComposableLambda").hostAbiField("c")
                .apply { isAccessible = true }
            val register = loader.hostClass("ReaMicroHost.RouteRegistration").hostAbiMethodByArity("d", 3)
            XposedBridge.hookMethod(register, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.getOrNull(1) != routeToken) return
                    val holder = param.args.getOrNull(2) ?: return
                    val content = wrapper.get(holder) ?: return
                    check(contentType.isInstance(content)) { "Unverified native S3 route content" }
                    registered[content] = true
                }
            })
            val render = contentType.hostAbiMethod("y", Any::class.java, Any::class.java,
                Any::class.java, Any::class.java)
            XposedBridge.hookMethod(render, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val callback = param.thisObject ?: return
                    if (registered[callback] != true) return
                    val route = decode.invoke(null, param.args[1], routeToken) ?: return
                    val marker = marker(route) ?: return
                    if (!marker.startsWith(MARKER_PREFIX)) return
                    val scope = scopeField.get(callback) ?: return
                    val entry = entries[marker]
                    if (entry == null) {
                        param.result = unit
                        return
                    }
                    if (entry.controller.get() !== HostNavigation.controller(scope)) {
                        param.result = unit
                        return
                    }
                    if (entry.canceled && entry.view.get() == null) {
                        param.result = unit
                        return
                    }
                    val composer = param.args[2] ?: return
                    try {
                        val factory = function("WebDavRouteView") {
                            entry.view.get()?.let { return@function it }
                            check(!entry.canceled) { "同步配置页已退出" }
                            val view = entry.page.createEmbeddedView { pop(entry) }
                            if (entry.view.get() !== view) {
                                entry.view = WeakReference(view)
                                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                                    override fun onViewAttachedToWindow(v: View) = Unit
                                    override fun onViewDetachedFromWindow(v: View) { release(entry) }
                                })
                            }
                            view
                        }
                        val onRelease = function("WebDavRouteRelease") { release(entry); unit }
                        // The verified native eight-slot AndroidView supplies an explicit release callback.
                        androidView.invoke(null, factory, ui.fillSize(ui.modifier()), null, onRelease, noOp,
                            composer, 0, 4)
                        param.result = unit
                    } catch (error: Throwable) {
                        param.result = unit
                        val wasActive = activeMarker == marker && !entry.canceled
                        if (wasActive) main.post {
                            if (entries[marker] === entry && activeMarker == marker && !entry.canceled) {
                                runCatching { pop(entry) }
                                release(entry)
                            }
                        } else release(entry)
                        if (!entry.canceled) XposedBridge.logError("ReaMicro native WebDAV page unavailable", error)
                    }
                }
            })
            val navigation = requireNotNull(HostNativeAbi.navigation(HostSymbols.profileId))
            val navigate = scopeType.declaredMethods.single {
                it.name == navigation.navigate && it.parameterCount == 2 && it.parameterTypes[0] == scopeType
            }
            XposedBridge.hookMethod(navigate, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val scope = param.args[0] ?: return
                    val destination = param.args[1] ?: return
                    val controller = HostNavigation.controller(scope)
                    val next = marker(destination)
                    entries.values.filter {
                        it.controller.get() === controller && it.marker != next
                    }.toList().forEach(::cancel)
                }
            })
            val controller = loader.hostClass("ReaMicroHost.NavController")
            XposedBridge.hookMethod(controller.hostAbiMethod(navigation.controllerPop), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val entry = activeMarker?.let(entries::get) ?: return
                    if (entry.controller.get() === param.thisObject) cancel(entry)
                }
            })
            XposedBridge.deoptimize(choices)
            XposedBridge.deoptimize(register)
            XposedBridge.deoptimize(render)
            XposedBridge.deoptimize(navigate)
            loader.hostClass("ReaMicroHost.RouteSetup").hostAbiMethod("invoke", Any::class.java)
                .let(XposedBridge::deoptimize)
            installed = true
        }
    }

    fun open(page: WebDavSyncConfigurationPage): Boolean {
        if (!installed || Looper.myLooper() != Looper.getMainLooper()) return false
        val host = activityProvider()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return false
        val scope = latestScope.get() ?: return false
        val marker = MARKER_PREFIX + UUID.randomUUID()
        val entry = Entry(marker, page, scope, HostNavigation.controller(scope), host)
        entries[marker] = entry
        if (application == null) {
            application = host.application
            host.application.registerActivityLifecycleCallbacks(lifecycle)
        }
        return try {
            activeMarker = marker
            val route = routeType.getDeclaredConstructor(String::class.java).newInstance(marker)
            HostNavigation.navigate(loader, scope, route)
            true
        } catch (error: Throwable) {
            release(entry)
            XposedBridge.logError("ReaMicro native WebDAV navigation failed", error)
            false
        }
    }

    private fun marker(route: Any): String? =
        if (routeType.isInstance(route)) routeType.hostField("preset").get(route) as? String else null

    private fun pop(entry: Entry) {
        if (entries[entry.marker] !== entry || activeMarker != entry.marker) return
        cancel(entry)
        entry.scope.get()?.let(HostNavigation::pop) ?: release(entry)
    }

    private fun cancel(entry: Entry) {
        if (entry.canceled) return
        entry.canceled = true
        if (activeMarker == entry.marker) activeMarker = null
        try {
            entry.page.cancelPending()
        } finally {
            if (entry.view.get() == null) release(entry)
        }
    }

    private fun release(entry: Entry) {
        if (entries[entry.marker] !== entry) return
        entries.remove(entry.marker)
        if (activeMarker == entry.marker) activeMarker = null
        try {
            entry.page.closeEmbedded()
        } finally {
            if (entries.isEmpty()) {
                application?.unregisterActivityLifecycleCallbacks(lifecycle)
                application = null
            }
        }
    }

    private fun function(name: String, block: (Array<Any?>?) -> Any?): Any =
        Proxy.newProxyInstance(loader, arrayOf(loader.hostClass("kotlin.jvm.functions.Function1"))) { self, method, args ->
            when (HostSymbols.logicalMethodName(method)) {
                "invoke" -> block(args)
                "equals" -> self === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "ReaMicro$name"
                else -> null
            }
        }

    private companion object {
        const val ROUTE_CLASS = "app.zhendong.reamicro.Route\$S3SyncConfig"
        const val MARKER_PREFIX = "reamicro-webdav-"
    }
}
