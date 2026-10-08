package com.reamicro.fix.epub.editor

internal fun selectionEmptyElementRanges(source: String, ranges: List<IntRange>): List<IntRange> {
    val deleted = BooleanArray(source.length)
    val preserve = BooleanArray(source.length)
    for (range in ranges) for (i in range) deleted[i] = true
    data class Open(val name: String, val start: Int, val end: Int, val protected: Boolean,
        val preserve: Boolean, val breaks: MutableList<IntRange> = arrayListOf())
    val stack = ArrayList<Open>()
    val removable = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
        "span", "b", "i", "em", "strong", "u", "s", "small", "sup", "sub", "font")
    val voids = setOf("area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr")

    val markup = SelectionMarkupIndex(source)
    if (!markup.valid) return ranges
    fun protectedElement(at: Int): Boolean = markup.atStart(at)?.let { element ->
        element.preserved || element.attrs.any {
            it.name in setOf("id", "name", "href", "src", "epub:type", "role") || it.name.startsWith("on")
        }
    } ?: true
    var cursor = 0
    while (cursor < source.length) {
        val at = source.indexOf('<', cursor)
        if (at < 0) break
        if (source.startsWith("<!--", at)) {
            val end = source.indexOf("-->", at + 4)
            if (end < 0) return ranges
            cursor = end + 3
            continue
        }
        var end = at + 1
        var quote: Char? = null
        while (end < source.length) {
            val c = source[end++]
            if (quote != null) { if (quote == c) quote = null }
            else if (c == '\'' || c == '"') quote = c
            else if (c == '>') break
        }
        if (end > source.length || source[end - 1] != '>' || quote != null) return ranges
        val token = source.substring(at + 1, end - 1).trim()
        cursor = end
        if (token.startsWith("!") || token.startsWith("?")) continue
        val closing = token.startsWith("/")
        val body = if (closing) token.drop(1).trimStart() else token
        val name = body.takeWhile { it.isLetterOrDigit() || it == ':' || it == '-' }.lowercase()
        if (name.isEmpty()) return ranges
        if (closing) {
            if (stack.isEmpty() || stack.last().name != name) return ranges
            val open = stack.removeAt(stack.lastIndex)
            val touched = ranges.any { it.first < at && it.last >= open.end }
            if (open.preserve) {
                for (i in open.start until end) preserve[i] = true
            } else if (name in removable) {
                selectionClearEmptyBreakLines(source, deleted, ranges, open.end, at, open.breaks)
                if (!open.protected && touched && selectionBlankRemainder(source, deleted, open.end, at, open.breaks)) {
                    for (i in open.start until end) deleted[i] = true
                }
            }
        } else if (name == "br" && !protectedElement(at)) {
            stack.forEach { it.breaks += at until end }
        } else if (name !in voids && !token.endsWith("/")) {

            if (name in setOf("script", "style", "textarea", "title")) {
                val close = Regex("</\\s*$name\\s*>", RegexOption.IGNORE_CASE).find(source, end)
                    ?: return ranges
                cursor = close.range.last + 1
                for (i in at until cursor) preserve[i] = true
            } else {
                if (stack.size >= 256) return ranges
                stack += Open(name, at, end, protectedElement(at),
                    name == "pre" || stack.lastOrNull()?.preserve == true ||
                        body.contains("xml:space", true) || body.contains("white-space", true))
            }
        }
    }
    if (stack.isNotEmpty()) return ranges
    selectionClearLocalBlankLines(source, deleted, preserve)
    val result = ArrayList<IntRange>()
    cursor = 0
    while (cursor < source.length) {
        if (!deleted[cursor]) { cursor++; continue }
        val start = cursor
        while (cursor < source.length && deleted[cursor]) cursor++
        result += start until cursor
    }
    return result
}
