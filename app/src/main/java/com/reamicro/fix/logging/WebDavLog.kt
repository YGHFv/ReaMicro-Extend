package com.reamicro.fix.logging

import com.reamicro.fix.hook.webdav.LOG_PREFIX
import de.robv.android.xposed.XposedBridge

// WebDAV 与在线补全共用的日志入口。
//
// 原先它是 WebDavDriveHook 的成员。功能拆到 online/cloud 多个包之后，日志属于
// 横切关注点，放在任何一个业务包里都会让其它包反向依赖它，因此收到 logging 包。

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
