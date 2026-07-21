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
            val close = quotes[text[index]]
            if (close == null) {
                index++
                continue
            }
            val end = findClosingQuote(text, index + 1, close, paragraphLimit)
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
        if (carry != null) {
            val end = text.indexOf(carry.close)
            if (end >= 0) {
                ranges.add(0..(end + 1))
                index = end + 1
                carry = null
            } else {
                val remaining = carry.remainingSegments - 1
                val expired = remaining <= 0
                if (!expired && text.isNotEmpty()) ranges.add(0..text.length)
                return SegmentResult(
                    ranges = ranges,
                    carry = if (remaining > 0) carry.copy(remainingSegments = remaining) else null,
                    carryExpired = expired,
                )
            }
        }
        while (index < text.length) {
            val close = quotes[text[index]]
            if (close == null) {
                index++
                continue
            }
            val end = findClosingQuote(text, index + 1, close, carryLimit)
            if (end > index + 1) {
                ranges.add(index..(end + 1))
                index = end + 1
            } else {
                ranges.add(index..text.length)
                carry = if (carryLimit > 0) QuoteCarry(close, carryLimit) else null
                carryStartedInCurrentSegment = carry != null
                break
            }
        }
        return SegmentResult(ranges, carry, carryStartedInCurrentSegment = carryStartedInCurrentSegment)
    }

    private fun findClosingQuote(
        text: String,
        start: Int,
        close: Char,
        maxParagraphSeparators: Int,
    ): Int {
        var index = start
        var paragraphSeparators = 0
        while (index < text.length) {
            val char = text[index]
            if (char == close) return index
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
        val close: Char,
        val remainingSegments: Int,
    )

    data class SegmentResult(
        val ranges: List<IntRange>,
        val carry: QuoteCarry?,
        val carryExpired: Boolean = false,
        val carryStartedInCurrentSegment: Boolean = false,
    )
}
