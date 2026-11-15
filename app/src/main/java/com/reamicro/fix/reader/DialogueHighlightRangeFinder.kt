package com.reamicro.fix.reader

object DialogueHighlightRangeFinder {
    const val DEFAULT_MAX_PARAGRAPHS = 1

    fun findQuoteRanges(
        text: String,
        quotes: Map<Char, Char>,
        maxParagraphs: Int = DEFAULT_MAX_PARAGRAPHS,
    ): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        val paragraphLimit = maxParagraphs.coerceAtLeast(1) - 1
        var index = 0
        while (index < text.length) {
            val open = text[index]
            val close = quotes[open]
            if (close == null) {
                index++
                continue
            }
            val end = findClosingQuote(text, index + 1, open, close, paragraphLimit)
            if (end >= index + 1) {
                ranges.add(index..(end + 1))
                index = end + 1
            } else {
                val paragraphEnd = findParagraphEnd(text, index + 1)
                ranges.add(index..paragraphEnd)
                index = paragraphEnd.coerceAtLeast(index + 1)
            }
        }
        return ranges
    }

    fun findQuoteRangesInContext(
        segments: List<String>,
        currentSegmentIndex: Int,
        quotes: Map<Char, Char>,
        maxParagraphs: Int,
    ): List<IntRange> {
        if (currentSegmentIndex !in segments.indices) return emptyList()
        val currentText = segments[currentSegmentIndex]
        if (currentText.isEmpty()) return emptyList()

        val combined = StringBuilder(segments.sumOf { it.length } + segments.size - 1)
        var currentStart = 0
        segments.forEachIndexed { index, segment ->
            if (index > 0) combined.append('\n')
            if (index == currentSegmentIndex) currentStart = combined.length
            combined.append(segment)
        }
        val currentEnd = currentStart + currentText.length
        return findQuoteRanges(combined.toString(), quotes, maxParagraphs)
            .mapNotNull { range ->
                val start = maxOf(range.first, currentStart)
                val end = minOf(range.last, currentEnd)
                if (start >= end) null else (start - currentStart)..(end - currentStart)
            }
    }

    private fun findClosingQuote(
        text: String,
        start: Int,
        open: Char,
        close: Char,
        maxParagraphSeparators: Int,
    ): Int {
        var index = start
        var paragraphSeparators = 0
        while (index < text.length) {
            val char = text[index]
            if (char == close) return index
            if (open != close && char == open) return -1
            if (isParagraphSeparator(char)) {
                paragraphSeparators++
                if (paragraphSeparators > maxParagraphSeparators) return -1
                index = skipParagraphSeparatorRun(text, index)
                continue
            }
            index++
        }
        return -1
    }

    private fun isParagraphSeparator(char: Char): Boolean =
        char == '\n' || char == '\r'

    private fun skipParagraphSeparatorRun(text: String, start: Int): Int {
        var index = start
        while (index < text.length && isParagraphSeparator(text[index])) {
            index++
        }
        return index
    }

    private fun findParagraphEnd(text: String, start: Int): Int {
        var index = start
        while (index < text.length && !isParagraphSeparator(text[index])) index++
        return index
    }

}
