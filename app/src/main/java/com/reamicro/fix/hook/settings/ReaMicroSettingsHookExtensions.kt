package com.reamicro.fix.hook.settings

import com.reamicro.fix.hook.settings.*

internal fun String.compactOnlineSourceLine(): String =
    replace(Regex("\\s+"), " ").trim()

internal fun Any.method0(name: String): Any =
    javaClass.methods.first {
        it.parameterTypes.isEmpty() && (it.name == name || it.name.startsWith("$name-"))
    }.invoke(this)

internal fun Any.longMethod(name: String): Long =
    method0(name) as Long
