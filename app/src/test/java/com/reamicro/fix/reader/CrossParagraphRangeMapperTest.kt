package com.reamicro.fix.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨段映射：在拼接串上匹配，只把落进当前段的部分裁回来。
 * 范围沿用模块约定：first 起点，last 排他终点。
 */
class CrossParagraphRangeMapperTest {

    private fun map(
        segments: List<String>,
        current: Int,
        pattern: String,
    ): List<IntRange> = CrossParagraphRangeMapper.mapToCurrentSegment(segments, current) { combined ->
        HighlightDelimiterRangeFinder.findRanges(combined, pattern, maxParagraphs = 7)
    }

    @Test
    fun rangeStartingInPreviousSegmentIsClippedToCurrent() {
        val segments = listOf("开头【一", "二】结尾")

        val ranges = map(segments, current = 1, pattern = "【】")

        // 当前段是 "二】结尾"，跨段范围裁到段内 0..2 ("二】")
        assertEquals(1, ranges.size)
        assertEquals(0, ranges[0].first)
        assertEquals("二】", segments[1].substring(ranges[0].first, ranges[0].last))
    }

    @Test
    fun rangeEndingInNextSegmentIsClippedToCurrent() {
        val segments = listOf("开头【一", "二】结尾")

        val ranges = map(segments, current = 0, pattern = "【】")

        assertEquals(1, ranges.size)
        assertEquals("【一", segments[0].substring(ranges[0].first, ranges[0].last))
    }

    @Test
    fun rangeFullyInsideAnotherSegmentIsDropped() {
        val segments = listOf("【一】", "无关内容")

        assertTrue(map(segments, current = 1, pattern = "【】").isEmpty())
    }

    @Test
    fun rangeWhollyInsideCurrentSegmentKeepsLocalOffsets() {
        val segments = listOf("前一段", "有【标题】在这", "后一段")

        val ranges = map(segments, current = 1, pattern = "【】")

        assertEquals(1, ranges.size)
        assertEquals("【标题】", segments[1].substring(ranges[0].first, ranges[0].last))
    }

    @Test
    fun outOfBoundsIndexYieldsNothing() {
        assertTrue(map(listOf("a"), current = 3, pattern = "【】").isEmpty())
    }

    @Test
    fun emptyCurrentSegmentYieldsNothing() {
        assertTrue(map(listOf("【一】", ""), current = 1, pattern = "【】").isEmpty())
    }

    @Test
    fun matchesAcrossThreeSegmentsKeepMiddleFully() {
        val segments = listOf("【起", "中间", "止】")

        val ranges = map(segments, current = 1, pattern = "【】")

        assertEquals(1, ranges.size)
        assertEquals("中间", segments[1].substring(ranges[0].first, ranges[0].last))
    }
}
