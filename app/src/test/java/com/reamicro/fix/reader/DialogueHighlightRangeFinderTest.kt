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
    fun doubleQuoteDialogueDefersMiddleSegmentsUntilClosingQuoteArrives() {
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
        assertTrue(secondResult.ranges.isEmpty())
        assertEquals(listOf(0..(third.indexOf('\u201d') + 1)), thirdResult.ranges)
        assertTrue(thirdResult.incomingCarryClosed)
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

    @Test
    fun doubleQuoteDialogueCanCloseOnSeventhSeparateRenderedSegment() {
        val firstResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = "before \u201cfirst",
            quotes = doubleQuotes,
            incomingCarry = null,
            maxParagraphs = 7,
        )
        var carry = firstResult.carry
        listOf("second", "third", "fourth", "fifth", "sixth").forEach { text ->
            carry = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
                text = text,
                quotes = doubleQuotes,
                incomingCarry = carry,
                maxParagraphs = 7,
            ).carry
        }

        val seventh = "seventh\u201d narration"
        val seventhResult = DialogueHighlightRangeFinder.findQuoteRangesInSegment(
            text = seventh,
            quotes = doubleQuotes,
            incomingCarry = carry,
            maxParagraphs = 7,
        )

        assertEquals(listOf(0..(seventh.indexOf('\u201d') + 1)), seventhResult.ranges)
        assertTrue(seventhResult.incomingCarryClosed)
        assertNull(seventhResult.carry)
    }

    @Test
    fun contextualMatchDoesNotHighlightNarrationAfterClosingQuote() {
        val segments = listOf(
            "\u201cThese are only stones and branches,",
            "made to look like gold.\u201d",
            "The dragon mother laughed and released the magic.",
        )

        val middleRanges = DialogueHighlightRangeFinder.findQuoteRangesInContext(
            segments = segments,
            currentSegmentIndex = 1,
            quotes = doubleQuotes,
            maxParagraphs = 7,
        )
        val narrationRanges = DialogueHighlightRangeFinder.findQuoteRangesInContext(
            segments = segments,
            currentSegmentIndex = 2,
            quotes = doubleQuotes,
            maxParagraphs = 7,
        )

        assertEquals(listOf(0..segments[1].length), middleRanges)
        assertTrue(narrationRanges.isEmpty())
    }

    @Test
    fun contextualUnclosedQuoteOnlyHighlightsOpeningSegment() {
        val segments = listOf(
            "before \u201cunclosed dialogue",
            "ordinary narration",
            "more ordinary narration",
        )

        val openingRanges = DialogueHighlightRangeFinder.findQuoteRangesInContext(
            segments = segments,
            currentSegmentIndex = 0,
            quotes = doubleQuotes,
            maxParagraphs = 7,
        )
        val middleRanges = DialogueHighlightRangeFinder.findQuoteRangesInContext(
            segments = segments,
            currentSegmentIndex = 1,
            quotes = doubleQuotes,
            maxParagraphs = 7,
        )

        assertEquals(listOf(segments[0].indexOf('\u201c')..segments[0].length), openingRanges)
        assertTrue(middleRanges.isEmpty())
    }

    @Test
    fun repeatedChineseOpeningQuoteCannotCloseAcrossNarration() {
        val malformed = "\u201c这哪里是黄金，只不过让你感觉像是金子。\u201c"
        val narration = "龙母大笑间解除了魔法。"
        val nextDialogue = "\u201c这是下一句正常对话。\u201d"
        val segments = listOf(malformed, narration, nextDialogue)

        val malformedRanges = DialogueHighlightRangeFinder.findQuoteRangesInContext(
            segments = segments,
            currentSegmentIndex = 0,
            quotes = doubleQuotes,
            maxParagraphs = 7,
        )
        val narrationRanges = DialogueHighlightRangeFinder.findQuoteRangesInContext(
            segments = segments,
            currentSegmentIndex = 1,
            quotes = doubleQuotes,
            maxParagraphs = 7,
        )
        val nextDialogueRanges = DialogueHighlightRangeFinder.findQuoteRangesInContext(
            segments = segments,
            currentSegmentIndex = 2,
            quotes = doubleQuotes,
            maxParagraphs = 7,
        )

        assertEquals(listOf(0..malformed.length), malformedRanges)
        assertTrue(narrationRanges.isEmpty())
        assertEquals(listOf(0..nextDialogue.length), nextDialogueRanges)
    }
}
