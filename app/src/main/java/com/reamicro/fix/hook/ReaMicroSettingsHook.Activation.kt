package com.reamicro.fix.hook

import android.app.Activity
import android.widget.Toast
import com.reamicro.fix.core.HostRestart

internal fun ReaMicroSettingsHook.changeModuleEnabled(enabled: Boolean, update: (Boolean) -> Unit = {}): Boolean {
    val activity = activityProvider() ?: return settings.snapshot().moduleEnabled
    val previous = settings.snapshot().moduleEnabled
    if (previous == enabled) return previous
    if (!settings.setModuleEnabled(enabled)) {
        Toast.makeText(activity, "保存失败，开关未更改", Toast.LENGTH_SHORT).show()
        return settings.snapshot().moduleEnabled
    }
    update(enabled)
    activationRestartPending = true
    finishActivationRestart(activity)
    return settings.snapshot().moduleEnabled
}

internal fun ReaMicroSettingsHook.finishActivationRestart(activity: Activity) {
    if (activationRestartPending && HostRestart.request(activity)) activationRestartPending = false
}
