package com.reamicro.fix.settings

import android.content.SharedPreferences

internal object HostCrashUploadSettings {
    fun uploadsAllowed(prefs: SharedPreferences?): Boolean = runCatching {
        prefs?.getBoolean(
            ModuleSettings.KEY_HOST_CRASH_UPLOAD_ENABLED,
            ModuleSettings.DEFAULT_HOST_CRASH_UPLOAD_ENABLED,
        ) ?: false
    }.getOrDefault(false)

    fun shouldBlock(prefs: SharedPreferences?): Boolean = !uploadsAllowed(prefs)

    fun save(prefs: SharedPreferences?, enabled: Boolean): Boolean {
        prefs ?: return false
        val before = uploadsAllowed(prefs)
        val saved = runCatching {
            prefs.edit().putBoolean(ModuleSettings.KEY_HOST_CRASH_UPLOAD_ENABLED, enabled).commit()
        }.getOrDefault(false)
        if (!saved) {

            runCatching { prefs.edit().putBoolean(ModuleSettings.KEY_HOST_CRASH_UPLOAD_ENABLED, before).commit() }
        }
        return saved
    }
}
