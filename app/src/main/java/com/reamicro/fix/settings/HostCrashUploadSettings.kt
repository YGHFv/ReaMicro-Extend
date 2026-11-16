package com.reamicro.fix.settings

import android.content.SharedPreferences

internal object HostCrashUploadSettings {
    // 崩溃上报固定关闭，旧版本已保存的开启状态也不再生效。
    fun uploadsAllowed(@Suppress("UNUSED_PARAMETER") prefs: SharedPreferences?): Boolean = false

    fun shouldBlock(@Suppress("UNUSED_PARAMETER") prefs: SharedPreferences?): Boolean = true
}
