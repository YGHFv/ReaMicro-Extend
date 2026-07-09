package com.reamicro.fix.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueHighlightRangeFinderTest {
    private val doubleQuotes = mapOf(
        '\u201c' to '\u201d',
        '"' to '"',
    )

    @Test
    fun doubleQuoteDialogueCanSpanUpToThreeParagraphsInOneRenderedText() {
        val text = "before \u201cfirst paragraph\nsecond paragraph\nthird paragraph\u201d after"

        val ranges = DialogueHighlightRangeFinder.findQuoteRanges(text, doubleQuotes, maxParagraphs = 3)

        assertEquals(1, ranges.size)
        assertEquals(text.indexOf('\u201c'), ranges.single().first)
        assertEquals(text.indexOf('\u201d') + 1, ranges.single().last)
    }

    @Test
    fun doubleQuoteDialogueStopsPastThreeParagraphsInOneRenderedText() {
        val text = "before \u201cfirst\nsecond\nthird\nfourth\u201d after"

        val ranges = DialogueHighlightRangeFinder.findQuoteRanges(text, doubleQuotes, maxParagraphs = 3)

        assertTrue(ranges.isEmpty())
    }

    @Test
    fun defaultQuoteScanKeepsOriginalSingleParagraphLimit() {
        val text = "before \u201cfirst\nsecond\u201d after"

        val ranges = DialogueHighlightRangeFinder.findQuoteRanges(text, doubleQuotes)

        assertTrue(ranges.isEmpty())
    }

    @Test
    fun doubleQuoteDialogueCanContinueAcrossSeparateRenderedSegments() {
        val first = "narration \u201cfirst paragraph"
        val second = "second paragraph"
        val third = "third paragraph.\u201d narration"

        val firstResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = first,
            quotes = doubleQuotes,
            incomingCarry = null,
            maxParagraphs = 3,
        )
        val secondResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = second,
            quotes = doubleQuotes,
            incomingCarry = firstResult.carry,
            maxParagraphs = 3,
        )
        val thirdResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = third,
            quotes = doubleQuotes,
            incomingCarry = secondResult.carry,
            maxParagraphs = 3,
        )

        assertEquals(listOf(first.indexOf('\u201c')..first.length), firstResult.ranges)
        assertEquals(listOf(0..second.length), secondResult.ranges)
        assertEquals(listOf(0..(third.indexOf('\u201d') + 1)), thirdResult.ranges)
        assertNull(thirdResult.carry)
    }

    @Test
    fun doubleQuoteDialogueDropsCarryAfterThreeSeparateRenderedSegments() {
        val firstResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = "before \u201cfirst",
            quotes = doubleQuotes,
            incomingCarry = null,
            maxParagraphs = 3,
        )
        val secondResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = "second",
            quotes = doubleQuotes,
            incomingCarry = firstResult.carry,
            maxParagraphs = 3,
        )
        val thirdResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = "third",
            quotes = doubleQuotes,
            incomingCarry = secondResult.carry,
            maxParagraphs = 3,
        )

        assertNull(thirdResult.carry)
    }
}
