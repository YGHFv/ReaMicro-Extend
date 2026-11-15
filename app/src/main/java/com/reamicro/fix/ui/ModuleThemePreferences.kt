package com.reamicro.fix.ui

import android.content.SharedPreferences

internal class ModuleThemePreferences(private val preferences: SharedPreferences) {
    fun read(): ModuleThemeConfig = ModuleThemeValues.read(preferences.all)

    fun write(config: ModuleThemeConfig) {
        val current = preferences.all
        val editor = preferences.edit()
        // A style switch writes only changed values; the inactive style remains untouched.
        ModuleThemeValues.write(config).forEach { (key, value) ->
            if (current[key] != value) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is String -> editor.putString(key, value)
                }
            }
        }
        editor.apply()
    }
}
