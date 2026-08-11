package com.reamicro.fix.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区间匹配。范围沿用模块约定：first 是起点，last 存排他终点。
 */
class HighlightDelimiterRangeFinderTest {

    private fun ranges(text: String, pattern: String, maxParagraphs: Int = 1): List<String> =
        HighlightDelimiterRangeFinder.findRanges(text, pattern, maxParagraphs)
            .map { text.substring(it.first, it.last) }

    @Test
    fun evenLengthPatternSplitsInHalf() {
        val delimiters = HighlightDelimiterRangeFinder.parseDelimiters("【】")

        assertEquals("【", delimiters?.open)
        assertEquals("】", delimiters?.close)
    }

    @Test
    fun multiCharDelimitersSplitInHalf() {
        val delimiters = HighlightDelimiterRangeFinder.parseDelimiters("<<>>")

        assertEquals("<<", delimiters?.open)
        assertEquals(">>", delimiters?.close)
    }

    @Test
    fun oddLengthPatternUsesSameDelimiterForBothEnds() {
        val delimiters = HighlightDelimiterRangeFinder.parseDelimiters("|")

        assertEquals("|", delimiters?.open)
        assertEquals("|", delimiters?.close)
    }

    @Test
    fun blankPatternIsRejected() {
        assertNull(HighlightDelimiterRangeFinder.parseDelimiters("   "))
        assertTrue(HighlightDelimiterRangeFinder.findRanges("【标题】", "").isEmpty())
    }

    @Test
    fun rangeIncludesDelimiters() {
        assertEquals(listOf("【标题】"), ranges("前【标题】后", "【】"))
    }

    @Test
    fun multipleRangesDoNotOverlap() {
        assertEquals(listOf("【一】", "【二】"), ranges("【一】中间【二】", "【】"))
    }

    @Test
    fun sameDelimiterPairsUpSequentially() {
        assertEquals(listOf("|一|", "|二|"), ranges("|一||二|", "|"))
    }

    @Test
    fun unclosedOpenDelimiterIsSkippedInsteadOfEatingRest() {
        assertEquals(listOf("【二】"), ranges("【一 然后【二】", "【】"))
    }

    @Test
    fun emptyContentIsNotAMatchForSameDelimiter() {
        assertTrue(HighlightDelimiterRangeFinder.findRanges("||", "|").isEmpty())
    }

    @Test
    fun emptyContentIsAMatchForDistinctDelimiters() {
        assertEquals(listOf("【】"), ranges("前【】后", "【】"))
    }

    @Test
    fun nestedOpenDelimiterPairsWithNearestClose() {
        assertEquals(listOf("【内】"), ranges("【外【内】", "【】"))
    }

    @Test
    fun unclosedOpenBeforeValidPairDoesNotSwallowText() {
        val ranges = ranges("【游离 中间很长的正文【真正】尾巴", "【】")

        assertEquals(listOf("【真正】"), ranges)
    }

    @Test
    fun doesNotCrossNewlineByDefault() {
        assertTrue(HighlightDelimiterRangeFinder.findRanges("【一\n二】", "【】").isEmpty())
    }

    @Test
    fun crossesNewlineWhenParagraphBudgetAllows() {
        assertEquals(listOf("【一\n二】"), ranges("【一\n二】", "【】", maxParagraphs = 7))
    }

    @Test
    fun stopsAtParagraphBudget() {
        val text = "【一\n二\n三】"

        assertTrue(HighlightDelimiterRangeFinder.findRanges(text, "【】", maxParagraphs = 2).isEmpty())
        assertEquals(listOf(text), ranges(text, "【】", maxParagraphs = 3))
    }

    @Test
    fun multiCharDelimitersMatchInText() {
        assertEquals(listOf("<<注意>>"), ranges("正文<<注意>>正文", "<<>>"))
    }

    @Test
    fun emptyTextYieldsNothing() {
        assertTrue(HighlightDelimiterRangeFinder.findRanges("", "【】").isEmpty())
    }
}
