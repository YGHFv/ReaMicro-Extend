package com.reamicro.fix.cloud.local

import android.content.Context
import com.reamicro.fix.cloud.root.RootTaskBridge

/** Pause execution without deleting task configuration, records or the selected Root mode. */
internal object ModuleTaskActivation {
    private const val PREFS = "reamicro_task_activation"
    private const val ENABLED = "enabled"
    private const val RESUME_ROOT = "resume_root"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    @Synchronized
    fun change(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        val preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val restoreRoot = preferences.getBoolean(RESUME_ROOT, false)
        val rootEnabled = RootTaskBridge.isEnabled(app)
        val previous = preferences.getBoolean(ENABLED, true)
        val saved = preferences.edit().putBoolean(ENABLED, enabled)
            .putBoolean(RESUME_ROOT, restoreRoot || (!enabled && rootEnabled)).commit()
        if (enabled && !saved) {
            preferences.edit().putBoolean(ENABLED, previous).commit()
            error("执行状态保存失败")
        }
        if (!enabled) {
            var failure: Throwable? = if (saved) null else IllegalStateException("执行状态保存失败")
            fun attempt(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    val first = failure
                    if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
                }
            }
            attempt { LocalTaskRunner.stopForModuleSwitch() }
            // A local cancellation failure must not skip the independent Root pause attempt.
            if (rootEnabled || restoreRoot) attempt {
                val message = RootTaskBridge.disable(app, remove = false)
                check(!RootTaskBridge.isEnabled(app)) { message }
            }
            failure?.let { throw it }
        } else if (restoreRoot) {
            try {
                val message = RootTaskBridge.enable(app)
                check(RootTaskBridge.isEnabled(app)) { message }
                check(preferences.edit().putBoolean(RESUME_ROOT, false).commit()) { "执行状态保存失败" }
            } catch (error: Throwable) {
                preferences.edit().putBoolean(ENABLED, false).commit()
                throw error
            }
        }
    }
}
