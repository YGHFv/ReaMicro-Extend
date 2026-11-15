package com.reamicro.fix.hook.settings

import com.reamicro.fix.hook.settings.*

internal fun String.compactOnlineSourceLine(): String =
    replace(Regex("\\s+"), " ").trim()

internal fun Any.method0(name: String): Any {
    val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current)
    if (name == "getValue") return requireNotNull(ui.stateValue(this))
    val property = name.removePrefix("get").replaceFirstChar(Char::lowercaseChar)
    val role = when {
        ui.method("colorScheme").returnType.isInstance(this) -> "color.$property"
        ui.method("typography").returnType.isInstance(this) -> "typography.$property"
        else -> error("Unbound settings accessor: ${javaClass.name}#$name")
    }
    return requireNotNull(ui.field(role).get(this))
}

internal fun Any.longMethod(name: String): Long =
    method0(name) as Long
