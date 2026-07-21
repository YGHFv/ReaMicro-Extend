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
    fun doubleQuoteDialogueCanSpanUpToSevenParagraphsInOneRenderedText() {
        val text = "before \u201cfirst\nsecond\nthird\nfourth\nfifth\nsixth\nseventh\u201d after"

        val ranges = DialogueHighlightRangeFinder.findQuoteRanges(text, doubleQuotes, maxParagraphs = 7)

        assertEquals(1, ranges.size)
        assertEquals(text.indexOf('\u201c'), ranges.single().first)
        assertEquals(text.indexOf('\u201d') + 1, ranges.single().last)
    }

    @Test
    fun unclosedDoubleQuoteOnlyHighlightsItsOpeningParagraph() {
        val text = "before \u201cfirst\nsecond narration\nthird narration"

        val ranges = DialogueHighlightRangeFinder.findQuoteRanges(text, doubleQuotes, maxParagraphs = 7)

        assertEquals(listOf(text.indexOf('\u201c')..text.indexOf('\n')), ranges)
    }

    @Test
    fun defaultQuoteScanKeepsUnclosedHighlightInsideFirstParagraph() {
        val text = "before \u201cfirst\nsecond\u201d after"

        val ranges = DialogueHighlightRangeFinder.findQuoteRanges(text, doubleQuotes)

        assertEquals(listOf(text.indexOf('\u201c')..text.indexOf('\n')), ranges)
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
            maxParagraphs = 7,
        )
        val secondResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = second,
            quotes = doubleQuotes,
            incomingCarry = firstResult.carry,
            maxParagraphs = 7,
        )
        val thirdResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = third,
            quotes = doubleQuotes,
            incomingCarry = secondResult.carry,
            maxParagraphs = 7,
        )

        assertEquals(listOf(first.indexOf('\u201c')..first.length), firstResult.ranges)
        assertEquals(listOf(0..second.length), secondResult.ranges)
        assertEquals(listOf(0..(third.indexOf('\u201d') + 1)), thirdResult.ranges)
        assertNull(thirdResult.carry)
    }

    @Test
    fun doubleQuoteDialogueExpiresAfterSevenSeparateRenderedSegments() {
        val firstResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = "before \u201cfirst",
            quotes = doubleQuotes,
            incomingCarry = null,
            maxParagraphs = 7,
        )
        val secondResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = "second",
            quotes = doubleQuotes,
            incomingCarry = firstResult.carry,
            maxParagraphs = 7,
        )
        val thirdResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = "third",
            quotes = doubleQuotes,
            incomingCarry = secondResult.carry,
            maxParagraphs = 7,
        )
        val fourthResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = "fourth",
            quotes = doubleQuotes,
            incomingCarry = thirdResult.carry,
            maxParagraphs = 7,
        )
        val fifthResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = "fifth",
            quotes = doubleQuotes,
            incomingCarry = fourthResult.carry,
            maxParagraphs = 7,
        )
        val sixthResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = "sixth",
            quotes = doubleQuotes,
            incomingCarry = fifthResult.carry,
            maxParagraphs = 7,
        )
        val seventhResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = "seventh narration",
            quotes = doubleQuotes,
            incomingCarry = sixthResult.carry,
            maxParagraphs = 7,
        )

        assertNull(seventhResult.carry)
        assertTrue(seventhResult.carryExpired)
        assertTrue(seventhResult.ranges.isEmpty())
    }
}
