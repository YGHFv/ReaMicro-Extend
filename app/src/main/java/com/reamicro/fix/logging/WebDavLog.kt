package com.reamicro.fix.logging

import com.reamicro.fix.hook.webdav.LOG_PREFIX
import com.reamicro.fix.xposed.HookRuntime

internal fun logWebDav(
    message: String,
    level: ModuleLogLevel = legacyModuleLogLevel(message),
) {
    when (level) {
        ModuleLogLevel.ERROR -> HookRuntime.logError("$LOG_PREFIX WebDAV ERROR $message")
        ModuleLogLevel.WARN,
        ModuleLogLevel.INFO,
        -> HookRuntime.log("$LOG_PREFIX WebDAV $message")
    }
}
