package com.reamicro.fix.reader

object CrossParagraphRangeMapper {

    fun mapToCurrentSegment(
        segments: List<String>,
        currentSegmentIndex: Int,
        find: (String) -> List<IntRange>,
    ): List<IntRange> {
        if (currentSegmentIndex !in segments.indices) return emptyList()
        val currentText = segments[currentSegmentIndex]
        if (currentText.isEmpty()) return emptyList()

        val combined = StringBuilder(segments.sumOf { it.length } + segments.size)
        var currentStart = 0
        segments.forEachIndexed { index, segment ->
            if (index > 0) combined.append('\n')
            if (index == currentSegmentIndex) currentStart = combined.length
            combined.append(segment)
        }
        val currentEndExclusive = currentStart + currentText.length
        return find(combined.toString()).mapNotNull { range ->
            val start = maxOf(range.first, currentStart)
            val endExclusive = minOf(range.last, currentEndExclusive)
            if (start >= endExclusive) null else (start - currentStart)..(endExclusive - currentStart)
        }
    }
}
