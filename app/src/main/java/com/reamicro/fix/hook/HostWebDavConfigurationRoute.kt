package com.reamicro.fix.hook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.View
import com.reamicro.fix.core.HostNativeUi
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostNavigation
import com.reamicro.fix.core.HostAndroidViews
import com.reamicro.fix.core.HostIntegrationBindings
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.lang.reflect.Executable
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
    private val ui = HostNativeUi(loader)
    private val entries = LinkedHashMap<String, Entry>()
    private val registered = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private var latestScope = WeakReference<Any>(null)
    private var activeMarker: String? = null
    private var installed = false
    private var application: Application? = null
    private val bindings by lazy { requireNotNull(HostIntegrationBindings.webDavRoute) { "Native WebDAV route contract unavailable" } }
    private val routeType by lazy { bindings.type("route") }
    private val routeToken by lazy {
        val token = bindings.method("token")
        val receiver = if (Modifier.isStatic(token.modifiers)) null else bindings.field("tokenReceiver").get(null)
        requireNotNull(token.invoke(receiver, routeType))
    }
    private val contentType by lazy { bindings.type("content") }
    private val scopeField by lazy { bindings.field("scope") }
    private val decode by lazy { bindings.method("decode") }
    private val branchValues by lazy {
        val values = requireNotNull(HostIntegrationBindings.boundDescriptors()["integration.webDavRoute"])
        (0 until values.getValue("value:branchCount").toInt()).map { index ->
            bindings.field("branch.$index") to values.getValue("value:branch.$index").toLong()
        }
    }
    private fun isConfigurationContent(callback: Any): Boolean = contentType.isInstance(callback) &&
        branchValues.all { (field, value) ->
            val actual = field.get(callback)
            (if (actual is Boolean) if (actual) 1L else 0L else (actual as? Number)?.toLong()) == value
        }
    private val unit by lazy { com.reamicro.fix.core.HostCoroutines.unit(loader) }
    private val androidView = HostAndroidViews(loader)
    private val noOp by lazy { function("WebDavRouteUpdate") { unit } }
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(activity: Activity) {
            entries.values.filter { it.activity.get() === activity }.toList().forEach { entry ->
                runCatching { release(entry) }
                    .onFailure { HookRuntime.logError("ReaMicro native WebDAV release failed", it) }
            }
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }

    fun install(registerHook: (Executable, HookCallback) -> Unit) {
        if (installed) return
        HookInstallReport.install("WebDavSyncHook", "nativeConfigurationRoute") {
            check(HostIntegrationBindings.androidView != null) { "Native AndroidView unavailable" }
            val wrapper = bindings.field("callback")
            val register = bindings.method("register")
            val render = bindings.method("render")
            try {
            registerHook(register, object : HookCallback() {
                override fun before(param: Call) {
                    if (param.args.getOrNull(1) != routeToken) return
                    val holder = param.args.getOrNull(2) ?: return
                    val content = wrapper.get(holder) ?: return
                    check(isConfigurationContent(content)) { "Unverified native S3 route content" }
                    registered[content] = true
                    scopeField.get(content)?.let { latestScope = WeakReference(it) }
                }
            })
            registerHook(render, object : HookCallback() {
                override fun before(param: Call) {
                    val callback = param.thisObject ?: return
                    // A hot reload may happen after the graph was registered. The constructor
                    // branch proven at registration identifies the pre-existing callback too.
                    if (registered[callback] != true && !isConfigurationContent(callback)) return
                    registered[callback] = true
                    scopeField.get(callback)?.let { latestScope = WeakReference(it) }
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
                        val view = entry.view.get() ?: entry.page.createEmbeddedView { pop(entry) }
                            .also { entry.view = WeakReference(it) }
                        val factory = function("WebDavRouteView") { view }
                        val onRelease = function("WebDavRouteRelease") { release(entry); unit }
                        val native = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
                        ui.backHandler(!entry.canceled, native.callback(native.type("function0")) { pop(entry) }, composer)
                        entry.page.render(composer)
                        // This node owns release after the native destination's exit animation.
                        val sentinel = ui.width(ui.height(ui.modifier(), ui.dp(0)), ui.dp(0))
                        androidView.render(factory, sentinel, composer, noOp, onRelease)
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
                        if (!entry.canceled) HookRuntime.logError("ReaMicro native WebDAV page unavailable", error)
                    }
                }
            })
            val navigate = HostNavigation.navigateMethod(loader)
            registerHook(navigate, object : HookCallback() {
                override fun before(param: Call) {
                    val scope = param.args[0] ?: return
                    if (bindings.type("scope").isInstance(scope)) latestScope = WeakReference(scope)
                    val destination = param.args[1] ?: return
                    val controller = HostNavigation.controller(scope)
                    val next = marker(destination)
                    entries.values.filter {
                        it.controller.get() === controller && it.marker != next
                    }.toList().forEach(::cancel)
                }
            })
            registerHook(HostNavigation.controllerPopMethod(loader), object : HookCallback() {
                override fun before(param: Call) {
                    val entry = activeMarker?.let(entries::get) ?: return
                    if (entry.controller.get() === param.thisObject) cancel(entry)
                }
            })
            HookRuntime.deoptimize(register)
            HookRuntime.deoptimize(render)
            HookRuntime.deoptimize(navigate)
            HookRuntime.deoptimize(bindings.method("setup"))
            installed = true
            ModuleResources.cleanup {
                entries.values.toList().forEach(::release)
                registered.clear()
                latestScope = WeakReference(null)
                installed = false
            }
            } catch (error: Throwable) {
                registered.clear()
                latestScope = WeakReference(null)
                throw error
            }
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
            val route = bindings.constructor("route").newInstance(marker)
            HostNavigation.navigate(loader, scope, route)
            true
        } catch (error: Throwable) {
            release(entry)
            HookRuntime.logError("ReaMicro native WebDAV navigation failed", error)
            false
        }
    }

    private fun marker(route: Any): String? =
        if (routeType.isInstance(route)) bindings.field("preset").get(route) as? String else null

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

    private fun function(name: String, block: (Array<out Any?>?) -> Any?): Any {
        val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current) { "$name native callback unavailable" }
        return ui.function(ui.type("function1"), block)
    }
    private companion object {
        const val MARKER_PREFIX = "reamicro-webdav-"
    }
}
