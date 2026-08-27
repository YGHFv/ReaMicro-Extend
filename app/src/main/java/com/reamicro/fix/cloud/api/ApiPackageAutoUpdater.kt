package com.reamicro.fix.cloud.api

import android.content.Context
import com.reamicro.fix.settings.XposedModuleSettings
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

object ApiPackageAutoUpdater {
    private val running = AtomicBoolean(false)

    fun checkIfDue(context: Context, settings: XposedModuleSettings) {
        val store = ApiServerSettingsStore { context.applicationContext }
        val config = store.get()
        if (!config.enabled || !config.autoCheckUpdates) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                val manager = ApiPackageManager(context.applicationContext, ApiServerClient(store), settings)
                val results = listOf(
                    ApiPackageKind.ONLINE_SOURCE,
                    ApiPackageKind.EPUB_STYLE,
                    ApiPackageKind.HIGHLIGHT_STYLE,
                    ApiPackageKind.ASSOCIATION_SOURCE,
                    ApiPackageKind.THEME,
                ).flatMap(manager::check).map(manager::install)
                val moduleUpdate = checkModuleUpdate(ApiServerClient(store))
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                val installed = results.count { it.installed }
                val failed = results.count { !it.installed }
                XposedBridge.log("ReaMicro API package auto update: installed=$installed failed=$failed module=${moduleUpdate.message}")
            } catch (error: Throwable) {
                XposedBridge.log("ReaMicro API package auto update failed: ${error.stackTraceToString()}")
            } finally {
                running.set(false)
            }
        }.start()
    }

    private const val PREFS = "reamicro_api_packages"
    private const val KEY_LAST_CHECK = "last_auto_check"
    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L
}
