package com.reamicro.fix.reader

object SearchCfiAddress {
    data class Point(val steps: List<Int>, val offset: Int, val offsetIndex: Int? = null) {
        val spineKey: String get() = "epubcfi(/${steps[0]}/${steps[1]}"
    }
    private val point = Regex("""^((?:/\d+)+)(?::(\d+))?(?:;s=[ab])?$""")

    fun parse(raw: String): Point? {
        val cfi = raw.trim()
        if (!cfi.startsWith("epubcfi(") || !cfi.endsWith(')')) return null
        val clean = StringBuilder()
        var assertion = false
        var escaped = false
        for (char in cfi.substring(8, cfi.length - 1)) {
            if (escaped) { escaped = false; if (!assertion) return null; continue }
            if (char == '^') { escaped = true; continue }
            if (assertion) { if (char == ']') assertion = false; continue }
            when (char) {
                '[' -> assertion = true
                ']' -> return null
                '!' -> Unit
                else -> clean.append(char)
            }
        }
        if (assertion || escaped) return null
        val match = point.matchEntire(clean.toString()) ?: return null
        val steps = match.groupValues[1].split('/').drop(1).map { it.toIntOrNull() ?: return null }
        if (steps.size < 2) return null
        val offset = match.groupValues[2].let { if (it.isEmpty()) 0 else it.toIntOrNull() ?: return null }

        val hasOffset = match.groupValues[2].isNotEmpty()
        if (hasOffset && (steps.size < 3 || steps.last() != 1)) return null
        return Point(if (hasOffset) steps.dropLast(1) else steps, offset, if (hasOffset) steps.last() else null)
    }

    fun spineKey(raw: String): String? = parse(raw)?.spineKey

    fun sameNode(mark: Point, content: Point): Boolean = mark.steps == content.steps
}
