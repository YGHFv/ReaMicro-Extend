package com.reamicro.fix.reader

data class SearchHighlightMarkDraft(
    val id: Long,
    val chapter: String,
    val startCfi: String,
    val endCfi: String,
    val quote: String,
)

object SearchHighlightPlanner {
    const val DEFAULT_MARK_ID_BASE = -9_223_372_036_854_000_000L
    const val DEFAULT_MARK_ID_RANGE = 100_000L
    const val DEFAULT_OFFSET_TOLERANCE = 8

    fun highlightId(resultIndex: Int, base: Long = DEFAULT_MARK_ID_BASE): Long =
        base + resultIndex.coerceAtLeast(0)

    fun isHighlightId(
        id: Long,
        base: Long = DEFAULT_MARK_ID_BASE,
        range: Long = DEFAULT_MARK_ID_RANGE,
    ): Boolean =
        id >= base && id < base + range

    fun markDraft(
        resultIndex: Int,
        chapter: String,
        startCfi: String?,
        fallbackCfi: String?,
        endCfi: String?,
        matchText: String,
        base: Long = DEFAULT_MARK_ID_BASE,
    ): SearchHighlightMarkDraft? {
        val start = startCfi?.takeIf { it.isNotBlank() }
            ?: fallbackCfi?.takeIf { it.isNotBlank() }
            ?: return null
        val end = endCfi
            ?.takeIf { it.isNotBlank() && it != start }
            ?: endCfiFromLength(start, matchText.length)
            ?: fallbackCfi?.takeIf { it.isNotBlank() && it != start }
            ?: return null
        return SearchHighlightMarkDraft(
            id = highlightId(resultIndex, base),
            chapter = chapter,
            startCfi = start,
            endCfi = end,
            quote = matchText,
        )
    }

    fun endCfiFromLength(startCfi: String, length: Int): String? {
        if (length <= 0) return null
        val match = Regex(""":(\d+)\)?$""").find(startCfi) ?: return null
        val start = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val end = start + length.coerceAtLeast(1)
        return startCfi.replaceRange(match.groups[1]!!.range, end.toString())
    }

    fun quoteStart(
        content: String,
        quote: String,
        windowStart: Int,
        windowEnd: Int,
        expectedLocalStart: Int?,
        tolerance: Int = DEFAULT_OFFSET_TOLERANCE,
    ): Int? {
        if (quote.isEmpty()) return null
        if (expectedLocalStart != null) {
            if (expectedLocalStart !in windowStart..windowEnd || expectedLocalStart + quote.length > windowEnd) {
                return null
            }
            if (content.regionMatches(expectedLocalStart, quote, 0, quote.length)) {
                return expectedLocalStart
            }
            val nearbyStart = (expectedLocalStart - tolerance).coerceAtLeast(windowStart)
            val nearbyEnd = (expectedLocalStart + tolerance).coerceAtMost(windowEnd - quote.length)
            if (nearbyEnd >= nearbyStart) {
                val nearbyMatches = matchingStarts(content, quote, nearbyStart, nearbyEnd + quote.length)
                return nearbyMatches.minByOrNull { kotlin.math.abs(it - expectedLocalStart) }
            }
            return null
        }
        return matchingStarts(content, quote, windowStart, windowEnd).firstOrNull()
    }

    fun cfiCharacterOffset(cfi: String): Int? =
        Regex(""":(\d+)\)?$""").find(cfi)?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun correctionDirection(
        targetNumber: Int?,
        currentNumber: Int?,
        targetKey: String?,
        currentKey: String?,
        activeVisibleMatches: Boolean,
    ): Boolean? {
        if (targetNumber != null && currentNumber != null) {
            return when {
                targetNumber > currentNumber -> true
                targetNumber < currentNumber -> false
                else -> null
            }
        }
        if (targetKey != null && currentKey != null) {
            return if (targetKey != currentKey) true else null
        }
        return if (!activeVisibleMatches) true else null
    }

    private fun matchingStarts(content: String, quote: String, windowStart: Int, windowEnd: Int): List<Int> =
        generateSequence(content.indexOf(quote, windowStart.coerceAtLeast(0))) { previous ->
            content.indexOf(quote, previous + 1)
        }.takeWhile { it >= 0 && it + quote.length <= windowEnd.coerceAtMost(content.length) }.toList()
}
