package com.reamicro.fix.reader

object HighlightDelimiterRangeFinder {

    data class Delimiters(val open: String, val close: String)

    fun parseDelimiters(pattern: String): Delimiters? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length % 2 != 0) return Delimiters(trimmed, trimmed)
        val half = trimmed.length / 2
        return Delimiters(trimmed.substring(0, half), trimmed.substring(half))
    }

    fun findRanges(text: String, pattern: String, maxParagraphs: Int = 1): List<IntRange> {
        val delimiters = parseDelimiters(pattern) ?: return emptyList()
        return findRanges(text, delimiters, maxParagraphs)
    }

    fun findRanges(text: String, delimiters: Delimiters, maxParagraphs: Int = 1): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val open = delimiters.open
        val close = delimiters.close
        if (open.isEmpty() || close.isEmpty()) return emptyList()
        val separatorLimit = maxParagraphs.coerceAtLeast(1) - 1
        val ranges = ArrayList<IntRange>()
        var index = 0
        while (index < text.length) {
            val start = text.indexOf(open, index)
            if (start < 0) break
            val matched = findPair(text, start, open, close, separatorLimit)
            if (matched == null) {

                index = start + open.length
                continue
            }
            ranges.add(matched)
            index = matched.last
        }
        return ranges
    }

    private fun findPair(
        text: String,
        openStart: Int,
        open: String,
        close: String,
        separatorLimit: Int,
    ): IntRange? {
        val nestable = open != close
        var start = openStart
        while (true) {
            val contentStart = start + open.length
            var separators = 0
            var index = contentStart
            var restart = -1
            while (index < text.length) {
                if (text[index] == '\n') {
                    separators++
                    if (separators > separatorLimit) return null
                    index++
                    continue
                }

                if (text.startsWith(close, index) && (nestable || index > contentStart)) {
                    return start..(index + close.length)
                }
                if (nestable && text.startsWith(open, index)) {
                    restart = index
                    break
                }
                index++
            }
            if (restart < 0) return null
            start = restart
        }
    }
}
