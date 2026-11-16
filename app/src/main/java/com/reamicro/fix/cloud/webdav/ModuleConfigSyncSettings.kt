package com.reamicro.fix.cloud.webdav

import android.content.Context

internal class ModuleConfigSyncSettings(context: Context) {
    private val prefs = context.getSharedPreferences(ModuleConfigPolicy.PREFS, Context.MODE_PRIVATE)
    val enabled get() = prefs.getBoolean("enabled", false)
    val backupOnly get() = prefs.getBoolean("backup_only", false)
    val skipOnlineBooks get() = prefs.getBoolean("skip_online_books", false)
    val onlineProgressOnly get() = prefs.getBoolean("online_progress_only", false)
    val scopes: Set<ModuleConfigScope> get() {
        val names = prefs.getStringSet("scopes", ModuleConfigScope.entries.map { it.name }.toSet()).orEmpty()
        return ModuleConfigScope.entries.filterTo(linkedSetOf()) { it.name in names }
    }
    fun setEnabled(value: Boolean) { check(prefs.edit().putBoolean("enabled", value).commit()) }
    fun setBackupOnly(value: Boolean) { check(prefs.edit().putBoolean("backup_only", value).commit()) }
    fun setSkipOnlineBooks(value: Boolean) { check(prefs.edit().putBoolean("skip_online_books", value).commit()) }
    fun setOnlineProgressOnly(value: Boolean) { check(prefs.edit().putBoolean("online_progress_only", value).commit()) }
    fun setScope(scope: ModuleConfigScope, enabled: Boolean) {
        val selected = scopes.toMutableSet()
        if (enabled) selected += scope else selected -= scope
        check(prefs.edit().putStringSet("scopes", selected.map { it.name }.toSet()).commit())
    }
}
