package com.reamicro.fix.hook

import android.annotation.SuppressLint
import com.reamicro.fix.association.network.AssociationNetworkScope
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import java.net.URL

/** Independent of WebDAV/online-reading switches: association is a separate feature. */
internal object AssociationNetworkHook {
    @Volatile private var installed = false

    // Intentional Xposed-only access to optional host/platform internals.
    // Keep each lookup guarded and restrict bypasses to AssociationNetworkScope.
    @SuppressLint("BlockedPrivateApi")
    @Synchronized fun install() {
        if (installed) return
        var count = 0
        // Never hook the no-argument policy: it could allow unrelated traffic.
        val policyClasses = linkedSetOf<Class<*>>()
        listOf(
            "android.security.NetworkSecurityPolicy",
            "com.android.okhttp.internal.Platform",
        ).forEach { name ->
            runCatching { Class.forName(name) }.getOrNull()?.let(policyClasses::add)
        }
        runCatching {
            val base = Class.forName("libcore.net.NetworkSecurityPolicy")
            policyClasses += base.getMethod("getInstance").invoke(null).javaClass
        }
        policyClasses.forEach { policy ->
            runCatching {
                val method = policy.getDeclaredMethod("isCleartextTrafficPermitted", String::class.java)
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val host = param.args?.firstOrNull() as? String ?: return
                        if (AssociationNetworkScope.allowsHost(host)) param.result = true
                    }
                })
                count++
            }
        }
        runCatching {
            val filter = Class.forName("com.android.okhttp.HttpHandler\$CleartextURLFilter")
            val method = filter.getDeclaredMethod("checkURLPermitted", URL::class.java)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val url = param.args?.firstOrNull() as? URL ?: return
                    if (AssociationNetworkScope.allowsUrl(url)) param.result = null
                }
            })
            count++
        }
        installed = count > 0
        if (installed) {
            XposedBridge.log("ReaMicro LSP association HTTP search scope installed: $count")
        } else {
            XposedBridge.logError("ReaMicro LSP association HTTP search scope unavailable; host may reject FanQie HTTP requests")
        }
    }
}
