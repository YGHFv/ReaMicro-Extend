package com.reamicro.fix.hook

import android.app.Activity
import android.widget.Toast
import com.reamicro.fix.cloud.local.LocalTaskMirror
import com.reamicro.fix.core.HostRestart
import com.reamicro.fix.xposed.HookRuntime

internal fun ReaMicroSettingsHook.changeModuleEnabled(enabled: Boolean, update: (Boolean) -> Unit = {}): Boolean {
    val activity = activityProvider() ?: return settings.snapshot().moduleEnabled
    if (moduleActivationChanging && enabled) return settings.snapshot().moduleEnabled
    val previous = settings.snapshot().moduleEnabled
    if (previous == enabled) return previous
    if (!settings.setModuleEnabled(enabled)) {
        Toast.makeText(activity, "保存失败，开关未更改", Toast.LENGTH_SHORT).show()
        return settings.snapshot().moduleEnabled
    }
    update(enabled)
    moduleActivationChanging = true
    val revision = ++moduleActivationRevision
    val queued = LocalTaskMirror.setModuleEnabled(activity, enabled) { result ->
        if (moduleActivationRevision != revision) return@setModuleEnabled
        moduleActivationChanging = false
        if (!result.success && enabled) {
            settings.setModuleEnabled(previous)
            LocalTaskMirror.setModuleEnabled(activity.applicationContext, previous) {}
        }
        if (result.success || !enabled) activationRestartPending = true
        val owner = activityProvider()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return@setModuleEnabled
        if (result.success) {
            finishActivationRestart(owner)
        } else {
            // Re-enabling an inactive generation requires a completed handoff and a fresh host.
            update(settings.snapshot().moduleEnabled)
            HookRuntime.logError("ReaMicro activation handoff unconfirmed: ${result.error}")
            val message = if (enabled) "启用未完成，请稍后重试"
                else "补全计划已关闭，本地执行暂停未确认，请重试开关"
            Toast.makeText(owner, message, Toast.LENGTH_LONG).show()
            if (!enabled) finishActivationRestart(owner)
        }
    }
    if (!queued) {
        if (moduleActivationRevision != revision) return settings.snapshot().moduleEnabled
        moduleActivationRevision++
        moduleActivationChanging = false
        if (enabled) {
            settings.setModuleEnabled(previous)
            LocalTaskMirror.setModuleEnabled(activity.applicationContext, previous) {}
        }
        update(settings.snapshot().moduleEnabled)
        Toast.makeText(activity, "执行状态同步繁忙，请稍后重试", Toast.LENGTH_LONG).show()
        if (!enabled) {
            activationRestartPending = true
            finishActivationRestart(activity)
        }
    }
    return settings.snapshot().moduleEnabled
}

internal fun ReaMicroSettingsHook.finishActivationRestart(activity: Activity) {
    if (activationRestartPending && HostRestart.request(activity)) activationRestartPending = false
}
