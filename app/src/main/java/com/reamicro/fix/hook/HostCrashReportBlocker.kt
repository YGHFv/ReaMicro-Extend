package com.reamicro.fix.hook

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.reamicro.fix.settings.HostCrashUploadSettings
import com.reamicro.fix.settings.ModuleSettings
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostCrashBindings
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

internal object HostCrashReportBlocker {
    private const val FEATURE = "HostCrashReportBlocker"
    internal const val GUARD_ID_PREFIX = "ReaMicro:privacy.crash:"
    private val installedMethods = mutableSetOf<Method>()
    @Volatile private var preferences: SharedPreferences? = null
    private var settingsBootstrapInstalled = false
    private var cleanupRegistered = false

    internal fun isBlockingEnabled(): Boolean = HostCrashUploadSettings.shouldBlock(preferences)

    internal fun attachExistingApplication(application: Application) {
        readPreferences(application)
    }

    private fun readPreferences(context: Context) {
        // Do not retain an earlier permission to upload if the replacement read fails.
        preferences = null
        val result = runCatching {
            checkNotNull(context.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE))
        }
        preferences = result.getOrNull()
        val error = result.exceptionOrNull()
        HookInstallReport.record(FEATURE, "settings-read", error == null, error)
        if (error != null) runCatching {
            HookRuntime.logError("ReaMicro privacy: cannot read crash-upload switch; upload OFF, blocking ON", error)
        }
    }

    @Synchronized
    fun install(classLoader: ClassLoader, packageName: String) =
        install(classLoader, packageName, emptyList())

    @Synchronized
    fun install(
        classLoader: ClassLoader,
        packageName: String,
        previousGuards: List<XposedInterface.HookHandle>,
    ) {
        val contract = requireNotNull(HostCrashBindings.current) { "Crash reporting bindings unavailable" }
        check(contract.guards.all { it.first.declaringClass.classLoader === classLoader })
        val previous = previousGuards.associateBy { it.executable }
        check(previous.size == previousGuards.size) { "Duplicate previous privacy guards" }
        check(previousGuards.all { it.id == null || it.id?.startsWith(GUARD_ID_PREFIX) == true } &&
            previous.keys.all { executable -> contract.guards.any { it.first == executable } }) {
            "Previous privacy guards do not match the current host"
        }
        installSettingsBootstrap(packageName)
        if (!cleanupRegistered) {
            com.reamicro.fix.reload.ModuleResources.cleanup {
                installedMethods.clear(); preferences = null; settingsBootstrapInstalled = false
                cleanupRegistered = false
            }
            cleanupRegistered = true
        }
        var failure: Throwable? = null
        fun remember(error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
        contract.guards.forEach { (method, value) ->
            if (method !in installedMethods) {
                val seen = AtomicBoolean()
                val callback = object : HookCallback() {
                    override fun before(param: Call) {
                        if (!isBlockingEnabled()) return
                        param.result = value
                        if (seen.compareAndSet(false, true)) runCatching {
                            val role = when (value) {
                                null -> "initialization"
                                true -> "provider"
                                else -> "envelope transport"
                            }
                            HookRuntime.logAlways("ReaMicro privacy: blocked Sentry $role " +
                                "target=${method.declaringClass.name}.${method.name}")
                        }
                    }
                }
                val error = runCatching {
                    checkNotNull(HookRuntime.hookProcessGuardMethod(
                        method, callback, GUARD_ID_PREFIX + method.toGenericString(), previous[method],
                    )) { "Framework did not install $method" }
                    installedMethods += method
                }.exceptionOrNull()
                HookInstallReport.record(FEATURE, method.toGenericString(), error == null, error)
                error?.let(::remember)
            }
        }
        contract.deopt.forEach { method ->
            val error = runCatching {
                check(HookRuntime.deoptimize(method)) { "Framework could not deoptimize $method" }
            }.exceptionOrNull()
            HookInstallReport.record(FEATURE, "deopt:${method.toGenericString()}", error == null, error)
            if (error != null) {
                remember(error)
            }
        }
        // Keep every successful guard and any unreplaced old guard on failure.
        // Removing them here would turn a registration error into permission to upload.
        failure?.let { throw IllegalStateException("Crash-report guards incomplete; existing guards retained", it) }
        HookRuntime.logAlways("ReaMicro privacy: dynamic Sentry guards registered=${contract.guards.size}; crash uploads permanently blocked; registration alone is not proof of protection")
    }
    private fun installSettingsBootstrap(packageName: String) {
        if (settingsBootstrapInstalled) return
        HookInstallReport.install(FEATURE, "settings-bootstrap") {

            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            checkNotNull(HookRuntime.hookMethod(attach, object : HookCallback() {
                override fun before(param: Call) {
                    val context = param.args.firstOrNull() as? Context ?: return
                    if (context.packageName != packageName) return
                    readPreferences(context)
                    HookRuntime.logAlways("ReaMicro privacy: crash-upload allowed=false, blocking=${isBlockingEnabled()}")
                }
            })) { "Framework did not install Application.attach settings bootstrap" }
            settingsBootstrapInstalled = true
        }
    }
}
