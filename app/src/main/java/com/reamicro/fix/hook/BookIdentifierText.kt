package com.reamicro.fix.hook

internal object BookIdentifierText {
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val md5 = Regex("^[0-9a-fA-F]{32}$")
    fun display(raw: String): String = raw.trim().takeIf {
        uuid.matches(it) || md5.matches(it)
    }.orEmpty()
}
