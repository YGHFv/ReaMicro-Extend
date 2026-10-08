package com.reamicro.fix.epub.editor

internal fun selectionBlankRemainder(source: String, deleted: BooleanArray, from: Int, until: Int, breaks: List<IntRange> = emptyList()): Boolean {
    var i = from
    while (i < until) {
        if (deleted[i] || source[i].isWhitespace()) { i++; continue }
        if (source[i] == '<') {
            val br = breaks.firstOrNull { it.first == i }
            if (br != null) { i = br.last + 1; continue }
        }
        if (source[i] == '&') {
            val end = source.indexOf(';', i + 1)
            if (end in (i + 1) until minOf(until, i + 14)) {
                val entity = source.substring(i + 1, end).lowercase()
                if (entity in setOf("nbsp", "ensp", "emsp", "thinsp", "#160", "#xa0", "#32", "#x20")) {
                    i = end + 1
                    continue
                }
            }
        }
        return false
    }
    return true
}

internal fun selectionClearEmptyBreakLines(
    source: String, deleted: BooleanArray, ranges: List<IntRange>,
    from: Int, until: Int, breaks: List<IntRange>,
) {
    var begin = from
    for (i in 0..breaks.size) {
        val end = if (i < breaks.size) breaks[i].first else until
        if (ranges.any { it.first < end && it.last >= begin } &&
            selectionBlankRemainder(source, deleted, begin, end)) {
            for (j in begin until end) deleted[j] = true
            val separator = breaks.getOrNull(i) ?: breaks.getOrNull(i - 1)
            separator?.forEach { deleted[it] = true }
        }
        if (i < breaks.size) begin = breaks[i].last + 1
    }
}

internal fun selectionClearLocalBlankLines(source: String, deleted: BooleanArray, preserve: BooleanArray) {
    var cursor = 0
    var runStart = -1
    var runTouched = false
    fun finish(end: Int) {
        if (runStart >= 0 && runTouched) for (i in runStart until end) deleted[i] = true
        runStart = -1
        runTouched = false
    }
    while (cursor < source.length) {
        val next = source.indexOf('\n', cursor).let { if (it < 0) source.length else it + 1 }
        var blank = true
        var touched = false
        for (i in cursor until next) {
            if (preserve[i] || (!deleted[i] && source[i] !in " \t\r\n")) blank = false
            if (deleted[i] && !source[i].isWhitespace()) touched = true
        }
        if (blank) {
            if (runStart < 0) runStart = cursor
            runTouched = runTouched || touched
        } else finish(cursor)
        cursor = next
    }
    finish(source.length)
}
