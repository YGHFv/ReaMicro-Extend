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
        val nestable = open != close
        var start = -1
        var separators = 0
        var skippedOpen = -1
        var index = 0
        while (index < text.length) {
            if (start < 0) {
                start = text.indexOf(open, index)
                if (start < 0) break
                index = start + open.length
                separators = 0
                skippedOpen = -1
                continue
            }
            if (text[index] == '\n') {
                if (skippedOpen < 0 && open.startsWith('\n') && text.startsWith(open, index)) skippedOpen = index
                if (++separators > separatorLimit) {
                    start = -1
                    // 换行开符在旧语义中优先算分段；超出上限后仍可作为下一次匹配起点。
                    index = if (skippedOpen >= 0) skippedOpen else index + 1
                } else index++
            } else if (text.startsWith(close, index) && (nestable || index > start + open.length)) {
                index += close.length
                ranges.add(start..index)
                start = -1
            } else if (nestable && text.startsWith(open, index)) {
                // 嵌套时沿用最近开符；失败后不再回扫已经检查过的后缀。
                start = index
                index += open.length
                separators = 0
                skippedOpen = -1
            } else {
                index++
            }
        }
        return ranges
    }
}
