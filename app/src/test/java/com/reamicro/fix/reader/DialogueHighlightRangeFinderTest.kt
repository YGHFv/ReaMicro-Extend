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
    fun doubleQuoteDialogueCanSpanUpToFiveParagraphsInOneRenderedText() {
        val text = "before \u201cfirst paragraph\nsecond paragraph\nthird paragraph\nfourth paragraph\nfifth paragraph\u201d after"

        val ranges = DialogueHighlightRangeFinder.findQuoteRanges(text, doubleQuotes, maxParagraphs = 5)

        assertEquals(1, ranges.size)
        assertEquals(text.indexOf('\u201c'), ranges.single().first)
        assertEquals(text.indexOf('\u201d') + 1, ranges.single().last)
    }

    @Test
    fun doubleQuoteDialogueStopsPastFiveParagraphsInOneRenderedText() {
        val text = "before \u201cfirst\nsecond\nthird\nfourth\nfifth\nsixth\u201d after"

        val ranges = DialogueHighlightRangeFinder.findQuoteRanges(text, doubleQuotes, maxParagraphs = 5)

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
            maxParagraphs = 5,
        )
        val secondResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = second,
            quotes = doubleQuotes,
            incomingCarry = firstResult.carry,
            maxParagraphs = 5,
        )
        val thirdResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = third,
            quotes = doubleQuotes,
            incomingCarry = secondResult.carry,
            maxParagraphs = 5,
        )

        assertEquals(listOf(first.indexOf('\u201c')..first.length), firstResult.ranges)
        assertEquals(listOf(0..second.length), secondResult.ranges)
        assertEquals(listOf(0..(third.indexOf('\u201d') + 1)), thirdResult.ranges)
        assertNull(thirdResult.carry)
    }

    @Test
    fun doubleQuoteDialogueDropsCarryAfterFiveSeparateRenderedSegments() {
        val firstResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = "before \u201cfirst",
            quotes = doubleQuotes,
            incomingCarry = null,
            maxParagraphs = 5,
        )
        val secondResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = "second",
            quotes = doubleQuotes,
            incomingCarry = firstResult.carry,
            maxParagraphs = 5,
        )
        val thirdResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = "third",
            quotes = doubleQuotes,
            incomingCarry = secondResult.carry,
            maxParagraphs = 5,
        )
        val fourthResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = "fourth",
            quotes = doubleQuotes,
            incomingCarry = thirdResult.carry,
            maxParagraphs = 5,
        )
        val fifthResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = "fifth",
            quotes = doubleQuotes,
            incomingCarry = fourthResult.carry,
            maxParagraphs = 5,
        )

        assertNull(fifthResult.carry)
    }
}
