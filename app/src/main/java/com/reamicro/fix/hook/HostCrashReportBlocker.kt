package com.reamicro.fix.hook

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.reamicro.fix.settings.HostCrashUploadSettings
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object HostCrashReportBlocker {
    private const val FEATURE = "HostCrashReportBlocker"
    private val installedMethods = mutableSetOf<Method>()
    @Volatile private var preferences: SharedPreferences? = null
    private var settingsBootstrapInstalled = false

    internal fun isBlockingEnabled(): Boolean = HostCrashUploadSettings.shouldBlock(preferences)

    internal enum class ResultKind { VOID, PROVIDER_READY, TRANSPORT_SUCCESS }
    internal data class Target(val className: String, val methodName: String, val result: ResultKind)
    internal val targets = listOf(

        Target("io.sentry.transport.HttpConnection", "send", ResultKind.TRANSPORT_SUCCESS),
        Target("io.sentry.android.core.SentryAndroid", "init", ResultKind.VOID),
        Target("io.sentry.Sentry", "init", ResultKind.VOID),
        Target("io.sentry.ndk.SentryNdk", "init", ResultKind.VOID),
        Target("io.sentry.ndk.SentryNdk", "preload", ResultKind.VOID),
        Target("io.sentry.android.core.SentryInitProvider", "onCreate", ResultKind.PROVIDER_READY),
        Target("io.sentry.android.core.SentryPerformanceProvider", "onCreate", ResultKind.PROVIDER_READY),
        Target("io.sentry.ndk.SentryNdkPreloadProvider", "onCreate", ResultKind.PROVIDER_READY),
    )

    @Synchronized
    fun install(classLoader: ClassLoader, packageName: String) {
        val loadClass = { name: String -> Class.forName(name, false, classLoader) }
        try {
            loadClass("io.sentry.android.core.SentryAndroid")
        } catch (_: ClassNotFoundException) {
            XposedBridge.logAlways("ReaMicro privacy: named Sentry SDK not found; no hooks applied (not a protection guarantee for other SDKs)")
            return
        }
        installSettingsBootstrap(packageName)
        val count = installHooks(loadClass, { method, callback ->
            if (method !in installedMethods) {
                checkNotNull(XposedBridge.hookMethod(method, callback)) { "Framework did not install $method" }
                installedMethods += method
            }
        }, { target, error ->
            HookInstallReport.record(FEATURE, "${target.className}.${target.methodName}", error == null, error)
            if (error != null) XposedBridge.logError("ReaMicro privacy: Sentry hook failed: $target", error)
        }, shouldBlock = ::isBlockingEnabled)
        val failed = HookInstallReport.snapshot().count { it.feature == FEATURE && !it.ok }
        XposedBridge.logAlways("ReaMicro privacy: Sentry guards registered=$count, failed=$failed; upload permission defaults OFF; registration alone is not proof of protection")
    }

    private fun installSettingsBootstrap(packageName: String) {
        if (settingsBootstrapInstalled) return
        HookInstallReport.install(FEATURE, "settings-bootstrap") {

            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            checkNotNull(XposedBridge.hookMethod(attach, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val context = param.args.firstOrNull() as? Context ?: return
                    if (context.packageName != packageName) return
                    val error = runCatching {

                        preferences = context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)
                    }.exceptionOrNull()
                    if (error != null) {
                        preferences = null
                        XposedBridge.logError("ReaMicro privacy: cannot read crash-upload switch; upload OFF, blocking ON", error)
                    }
                    HookInstallReport.record(FEATURE, "settings-read", error == null, error)
                    XposedBridge.logAlways("ReaMicro privacy: crash-upload allowed=${HostCrashUploadSettings.uploadsAllowed(preferences)}, blocking=${isBlockingEnabled()}; changes require host restart for complete SDK lifecycle")
                }
            })) { "Framework did not install Application.attach settings bootstrap" }
            settingsBootstrapInstalled = true
        }
    }

    internal fun installHooks(
        loadClass: (String) -> Class<*>,
        hook: (Method, XC_MethodHook) -> Unit,
        report: (Target, Throwable?) -> Unit,
        shouldBlock: () -> Boolean = { true },
    ): Int {
        var installed = 0
        targets.forEach { target ->
            val error = runCatching {
                val methods = loadClass(target.className).declaredMethods.filter { it.name == target.methodName }
                check(methods.isNotEmpty()) { "Missing ${target.className}.${target.methodName}" }
                methods.forEach { method ->
                    hook(method, replacement(method, target.result, shouldBlock))
                    installed += 1
                }
            }.exceptionOrNull()

            report(target, error)
        }
        return installed
    }

    internal fun replacement(method: Method, kind: ResultKind, shouldBlock: () -> Boolean = { true }): XC_MethodHook {
        val value: Any? = when (kind) {
            ResultKind.VOID -> {
                check(method.returnType == Void.TYPE && Modifier.isStatic(method.modifiers)) { "Unexpected init signature: $method" }
                null
            }
            ResultKind.PROVIDER_READY -> {
                check(method.returnType == Boolean::class.javaPrimitiveType && method.parameterCount == 0) { "Unexpected provider signature: $method" }
                true
            }
            ResultKind.TRANSPORT_SUCCESS -> {
                check(!method.returnType.isPrimitive && method.parameterCount == 1) { "Unexpected transport signature: $method" }
                val factory = method.returnType.getMethod("success")
                check(Modifier.isStatic(factory.modifiers) && factory.returnType == method.returnType)
                checkNotNull(factory.invoke(null))
            }
        }
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {

                if (!runCatching(shouldBlock).getOrDefault(true)) return

                param.result = value
            }
        }
    }
}
