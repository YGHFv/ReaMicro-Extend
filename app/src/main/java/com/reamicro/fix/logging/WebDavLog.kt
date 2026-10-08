package com.reamicro.fix.logging

import com.reamicro.fix.hook.webdav.LOG_PREFIX
import com.reamicro.fix.xposed.XposedBridge

internal fun logWebDav(
    message: String,
    level: ModuleLogLevel = legacyModuleLogLevel(message),
) {
    when (level) {
        ModuleLogLevel.ERROR -> XposedBridge.logError("$LOG_PREFIX WebDAV ERROR $message")
        ModuleLogLevel.WARN,
        ModuleLogLevel.INFO,
        -> XposedBridge.log("$LOG_PREFIX WebDAV $message")
    }
}
