package com.reamicro.fix.hook.settings

import com.reamicro.fix.hook.settings.*

internal fun String.compactOnlineSourceLine(): String =
    replace(Regex("\\s+"), " ").trim()

internal fun Any.method0(name: String): Any =
    requireNotNull(com.reamicro.fix.xposed.XposedHelpers.callMethod(this, name))

internal fun Any.longMethod(name: String): Long =
    method0(name) as Long
