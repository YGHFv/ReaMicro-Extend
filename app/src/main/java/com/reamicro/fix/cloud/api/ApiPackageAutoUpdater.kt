package com.reamicro.fix.cloud.api

import android.content.Context
import com.reamicro.fix.settings.XposedModuleSettings
import com.reamicro.fix.xposed.HookRuntime
import com.reamicro.fix.reload.TaskCancellation
import java.util.concurrent.atomic.AtomicBoolean

object ApiPackageAutoUpdater {
    private val running = AtomicBoolean(false)

    fun checkIfDue(context: Context, settings: XposedModuleSettings) {
        if (!settings.snapshot().moduleEnabled) return
        val store = ApiServerSettingsStore { context.applicationContext }
        val config = store.get()
        if (!config.enabled || !config.autoCheckUpdates) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val identity = config.requestIdentity()
        val lastCheckKey = "$KEY_LAST_CHECK:$identity"
        val retryKey = "$KEY_RETRY_NEEDED:$identity"
        val now = System.currentTimeMillis()
        val interval = if (prefs.getBoolean(retryKey, false)) RETRY_INTERVAL_MS else CHECK_INTERVAL_MS
        if (now - prefs.getLong(lastCheckKey, 0L) in 0 until interval) return
        if (!running.compareAndSet(false, true)) return
        val task = com.reamicro.fix.reload.ModuleResources.thread {
            var succeeded = false
            try {
                val client = ApiServerClient(store, config)
                val manager = ApiPackageManager(context.applicationContext, client, settings)
                var installed = 0
                var failed = 0
                var checkFailures = 0
                var loggedFailures = 0
                ApiPackageKind.entries.forEach { kind ->
                    if (!settings.snapshot().moduleEnabled) throw java.util.concurrent.CancellationException("补全计划已关闭")
                    client.checkCurrentRequest()
                    val packages = runCatching { manager.check(kind) }.getOrElse { error ->
                        TaskCancellation.rethrowIfCancelled(error)
                        checkFailures++
                        HookRuntime.logError("ReaMicro API package check failed: kind=${kind.wireValue} " +
                            "reason=${error.message.orEmpty().take(MAX_REASON_CHARS)}")
                        emptyList()
                    }
                    packages.forEach { contentPackage ->
                        val result = manager.install(contentPackage)
                        if (result.installed) {
                            installed++
                        } else {
                            failed++
                            if (loggedFailures++ < MAX_FAILURE_LOGS) {
                                HookRuntime.logError("ReaMicro API package install failed: " +
                                    "kind=${kind.wireValue} package=${result.packageId.take(MAX_REASON_CHARS)} " +
                                    "version=${result.version.take(MAX_REASON_CHARS)} " +
                                    "reason=${result.message.take(MAX_REASON_CHARS)}")
                            }
                        }
                    }
                }
                if (!settings.snapshot().moduleEnabled) throw java.util.concurrent.CancellationException("补全计划已关闭")
                val moduleUpdate = checkModuleUpdate(client)
                succeeded = checkFailures == 0 && failed == 0 && moduleUpdate.succeeded
                if (!moduleUpdate.succeeded) {
                    HookRuntime.logError("ReaMicro API module update check failed: " +
                        moduleUpdate.message.take(MAX_REASON_CHARS))
                }
                HookRuntime.log("ReaMicro API package auto update: installed=$installed failed=$failed " +
                    "checkFailed=$checkFailures module=${moduleUpdate.message.take(MAX_REASON_CHARS)}")
            } catch (error: Throwable) {
                if (error !is java.util.concurrent.CancellationException && error !is InterruptedException &&
                    !Thread.currentThread().isInterrupted) {
                    HookRuntime.logError("ReaMicro API package auto update failed", error)
                }
            } finally {
                try {
                    prefs.edit()
                        .putLong(lastCheckKey, System.currentTimeMillis())
                        .putBoolean(retryKey, !succeeded)
                        .apply()
                } finally {
                    running.set(false)
                }
            }
        }
        try {
            task.start()
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            running.set(false)
        } catch (error: Throwable) {
            running.set(false)
            HookRuntime.logError("ReaMicro API package auto update could not start", error)
        }
    }

    private const val PREFS = "reamicro_api_packages"
    private const val KEY_LAST_CHECK = "last_auto_check"
    private const val KEY_RETRY_NEEDED = "auto_check_retry_needed"
    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L
    private const val RETRY_INTERVAL_MS = 30L * 60L * 1_000L
    private const val MAX_FAILURE_LOGS = 8
    private const val MAX_REASON_CHARS = 240
}
