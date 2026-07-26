package com.reamicro.fix.webdav

private val ONLINE_HTML_ENTITY_REGEX = Regex(
    """&(#(?:[xX][0-9A-Fa-f]+|\d+)|[A-Za-z][A-Za-z0-9]+);""",
)

private val ONLINE_HTML_NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "ensp" to " ",
    "emsp" to " ",
    "thinsp" to " ",
    "ldquo" to "“",
    "rdquo" to "”",
    "lsquo" to "‘",
    "rsquo" to "’",
    "laquo" to "«",
    "raquo" to "»",
    "hellip" to "…",
    "mdash" to "—",
    "ndash" to "–",
    "middot" to "·",
    "bull" to "•",
    "colon" to ":",
    "semi" to ";",
    "comma" to ",",
    "period" to ".",
    "excl" to "!",
    "quest" to "?",
)

/**
 * 解码在线正文里的 HTML 实体，并兼容 `&amp;#34;` 这类重复编码。
 */
internal fun String.decodeOnlineHtmlEntities(maxPasses: Int = 4): String {
    var current = this
    repeat(maxPasses.coerceAtLeast(1)) {
        val next = ONLINE_HTML_ENTITY_REGEX.replace(current) { match ->
            decodeOnlineHtmlEntity(match.groupValues[1]) ?: match.value
        }
        if (next == current) return current
        current = next
    }
    return current
}

private fun decodeOnlineHtmlEntity(raw: String): String? {
    if (!raw.startsWith('#')) return ONLINE_HTML_NAMED_ENTITIES[raw.lowercase()]
    val codePoint = when {
        raw.startsWith("#x", ignoreCase = true) -> raw.substring(2).toIntOrNull(16)
        else -> raw.substring(1).toIntOrNull()
    } ?: return null
    if (!Character.isValidCodePoint(codePoint) || codePoint in 0xD800..0xDFFF || codePoint == 0) return null
    if (codePoint == 0xA0) return " "
    return String(Character.toChars(codePoint))
}
