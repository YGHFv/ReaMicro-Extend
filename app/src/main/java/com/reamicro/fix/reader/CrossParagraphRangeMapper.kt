package com.reamicro.fix.reader

/**
 * 跨段匹配的通用做法，与双引号对话规则完全同一套：
 * 把当前段前后的兄弟段落用 '\n' 拼成一串、在整串上跑匹配、再把落在当前段内的部分裁回来。
 *
 * 对话规则原本把这段逻辑写死在 [DialogueHighlightRangeFinder.findQuoteRangesInContext] 里，
 * 正则与区间匹配开启「允许跨段」后走的就是这里，行为一致。
 */
object CrossParagraphRangeMapper {

    /**
     * 范围沿用本模块约定：first 是起点、last 存排他终点（`start..endExclusive`），
     * 传入的 [find] 与返回值都按这个约定。
     *
     * @param segments 上下文段落，含当前段
     * @param currentSegmentIndex 当前段在 [segments] 中的下标
     * @param find 在拼接后的整串上求范围
     * @return 裁剪到当前段、且已转成段内偏移的范围
     */
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
