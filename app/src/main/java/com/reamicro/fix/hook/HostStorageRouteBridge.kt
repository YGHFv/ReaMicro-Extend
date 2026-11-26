package com.reamicro.fix.hook

import com.reamicro.fix.cloud.webdav.hasWebDavLogin
import com.reamicro.fix.core.HostStorageBindings
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import io.github.libxposed.api.XposedInterface
import java.util.Collections
import java.util.WeakHashMap

/** Actual registered class tokens identify destinations, including merged content callbacks. */
internal class HostStorageRouteBridge(private val drive: WebDavDriveHook) {
    private val registered = Collections.synchronizedMap(WeakHashMap<Any, String>())
    fun install() {
        val c = requireNotNull(HostStorageBindings.routes) { "Storage routes unavailable" }
        requireNotNull(com.reamicro.fix.core.HostUiBindings.current) { "Native storage UI unavailable" }
        requireNotNull(HostStorageBindings.account) { "Native account contract unavailable" }
        for (name in listOf("webDavAccountScreenReuse", "webDavAccountSettingComponents", "webDavAccountAuthFlow", "webDavAccountTopBarTitle")) check(
            com.reamicro.fix.core.HookInstallReport.snapshot().any { it.feature == FEATURE_ID && it.name == name && it.ok }
        ) { "Native account dependency did not install: $name" }
        requireNotNull(com.reamicro.fix.core.HostIntegrationBindings.androidView) { "Native login view unavailable" }
        val routeKeys = listOf("login", "account")
        val tokens = routeKeys.associateWith(c::token)
        check(tokens.values.distinct().size == 2)
        val handles = arrayListOf<XposedInterface.HookHandle>()
        try {
            for ((register, keys) in routeKeys.groupBy { c.method("$it.register") }) {
                handles += HookRuntime.hookMethod(register, object : HookCallback() {
                    override fun before(param: Call) {
                        val key = keys.singleOrNull { tokens.getValue(it) == param.args[1] } ?: return
                        val content = c.field("$key.callback").get(param.args[c.value("$key.contentSlot")]) ?: return
                        check(c.type("$key.content").isInstance(content)) { "Unexpected storage route content" }
                        registered[content] = key
                    }
                })
                HookRuntime.deoptimize(register)
            }
            for ((render, keys) in routeKeys.groupBy { c.method("$it.render") }) {
                handles += HookRuntime.hookMethod(render, object : HookCallback() {
                    override fun before(param: Call) {
                        val callback = param.thisObject ?: return
                        val key = registered[callback]?.takeIf(keys::contains)
                            ?: keys.singleOrNull { c.matchesRoute(it, callback) } ?: return
                        try {
                            val route = c.method("$key.decode").invoke(null, param.args[1], tokens.getValue(key)) ?: return
                            val kind = c.field("$key.kind").getInt(route)
                            if (kind != BACKUP_TYPE_WEBDAV && kind != BACKUP_TYPE_LOCAL_LIBRARY) {
                                clearAccountContext()
                                return
                            }
                            if (key == "login" && kind != BACKUP_TYPE_WEBDAV) return
                            val scope = requireNotNull(c.field("$key.scope").get(callback))
                            val composer = requireNotNull(param.args[2])
                            val rendered = if (key == "login") {
                                drive.renderWebDavLoginRoute(scope, composer)
                                true
                            } else {
                                clearAccountContext()
                                if (kind == BACKUP_TYPE_WEBDAV) drive.renderWebDavAccountRoute(scope, composer)
                                else drive.renderLocalLibraryAccountRoute(scope, composer)
                            }
                            check(rendered) { "Extension account screen could not be rendered" }
                            param.result = drive.targetUnit()
                        } catch (error: Throwable) { param.throwable = error }
                    }
                })
                HookRuntime.deoptimize(render)
            }
            routeKeys.flatMap { key -> listOf("setup", "invoke", "body").map { c.method("$key.$it") } }
                .distinct().forEach(HookRuntime::deoptimize)
            val navigate = c.method("navigate")
            handles += HookRuntime.hookMethod(navigate, object : HookCallback() {
                override fun before(param: Call) {
                    clearAccountContext()
                    val route = param.args[1]?.takeIf(c.type("storage")::isInstance) ?: return
                    if (c.field("storageKind").getInt(route) == BACKUP_TYPE_WEBDAV && !hasWebDavLogin(drive.currentContext())) {
                        param.args[1] = c.constructor("login").newInstance(BACKUP_TYPE_WEBDAV)
                    }
                }
            })
            HookRuntime.deoptimize(navigate)
            val controller = c.method("controllerPop")
            handles += HookRuntime.hookMethod(controller, object : HookCallback() {
                override fun after(param: Call) {
                    if (param.result == true) clearAccountContext()
                }
            })
            HookRuntime.deoptimize(controller)
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            registered.clear()
            throw error
        }
    }
    private fun clearAccountContext() {
        drive.clearWebDavAccountContext()
        drive.clearLocalLibraryAccountContext()
    }
}
