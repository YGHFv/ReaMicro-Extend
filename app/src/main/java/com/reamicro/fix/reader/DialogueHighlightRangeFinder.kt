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
            if (end > index + 1) {
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

    fun findQuoteRangesInSegment(
        text: String,
        quotes: Map<Char, Char>,
        incomingCarry: QuoteCarry?,
        maxParagraphs: Int,
    ): SegmentResult {
        val ranges = ArrayList<IntRange>()
        val carryLimit = maxParagraphs.coerceAtLeast(1) - 1
        var index = 0
        var carry: QuoteCarry? = incomingCarry
        var carryStartedInCurrentSegment = false
        var incomingCarryClosed = false
        if (carry != null) {
            val end = findClosingQuote(
                text = text,
                start = 0,
                open = carry.open,
                close = carry.close,
                maxParagraphSeparators = Int.MAX_VALUE,
            )
            if (end >= 0) {
                ranges.add(0..(end + 1))
                index = end + 1
                carry = null
                incomingCarryClosed = true
            } else {
                val remaining = carry.remainingSegments - 1
                val expired = remaining <= 0
                return SegmentResult(
                    ranges = ranges,
                    carry = if (remaining > 0) carry.copy(remainingSegments = remaining) else null,
                    carryExpired = expired,
                )
            }
        }
        while (index < text.length) {
            val open = text[index]
            val close = quotes[open]
            if (close == null) {
                index++
                continue
            }
            val end = findClosingQuote(text, index + 1, open, close, carryLimit)
            if (end > index + 1) {
                ranges.add(index..(end + 1))
                index = end + 1
            } else {
                ranges.add(index..text.length)
                carry = if (carryLimit > 0) QuoteCarry(open, close, carryLimit) else null
                carryStartedInCurrentSegment = carry != null
                break
            }
        }
        return SegmentResult(
            ranges = ranges,
            carry = carry,
            carryStartedInCurrentSegment = carryStartedInCurrentSegment,
            incomingCarryClosed = incomingCarryClosed,
        )
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

    data class QuoteCarry(
        val open: Char,
        val close: Char,
        val remainingSegments: Int,
    )

    data class SegmentResult(
        val ranges: List<IntRange>,
        val carry: QuoteCarry?,
        val carryExpired: Boolean = false,
        val carryStartedInCurrentSegment: Boolean = false,
        val incomingCarryClosed: Boolean = false,
    )
}
