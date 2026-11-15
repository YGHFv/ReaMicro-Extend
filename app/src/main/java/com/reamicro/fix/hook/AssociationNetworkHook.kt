package com.reamicro.fix.hook

import android.annotation.SuppressLint
import com.reamicro.fix.association.network.AssociationNetworkScope
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.net.URL

internal object AssociationNetworkHook {
    @Volatile private var installed = false

    @SuppressLint("BlockedPrivateApi")
    @Synchronized fun install() {
        if (installed) return
        var count = 0

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
                HookRuntime.hookMethod(method, object : HookCallback() {
                    override fun before(param: Call) {
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
            HookRuntime.hookMethod(method, object : HookCallback() {
                override fun before(param: Call) {
                    val url = param.args?.firstOrNull() as? URL ?: return
                    if (AssociationNetworkScope.allowsUrl(url)) param.result = null
                }
            })
            count++
        }
        installed = count > 0
        if (installed) {
            HookRuntime.log("ReaMicro LSP association HTTP search scope installed: $count")
        } else {
            HookRuntime.logError("ReaMicro LSP association HTTP search scope unavailable; host may reject FanQie HTTP requests")
        }
    }
}
