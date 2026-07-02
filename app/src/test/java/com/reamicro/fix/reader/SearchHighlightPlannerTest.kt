package com.reamicro.fix.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHighlightPlannerTest {
    @Test
    fun markDraftPrefersExplicitEndCfi() {
        val draft = SearchHighlightPlanner.markDraft(
            resultIndex = 3,
            chapter = "Chapter",
            startCfi = "epubcfi(/6/2/4/2/1:10)",
            fallbackCfi = null,
            endCfi = "epubcfi(/6/2/4/2/1:18)",
            matchText = "keyword",
        )

        assertEquals(SearchHighlightPlanner.DEFAULT_MARK_ID_BASE + 3, draft?.id)
        assertEquals("epubcfi(/6/2/4/2/1:10)", draft?.startCfi)
        assertEquals("epubcfi(/6/2/4/2/1:18)", draft?.endCfi)
    }

    @Test
    fun markDraftDerivesEndCfiFromMatchLength() {
        val draft = SearchHighlightPlanner.markDraft(
            resultIndex = -5,
            chapter = "Chapter",
            startCfi = "epubcfi(/6/2/4/2/1:10)",
            fallbackCfi = null,
            endCfi = null,
            matchText = "four",
        )

        assertEquals(SearchHighlightPlanner.DEFAULT_MARK_ID_BASE, draft?.id)
        assertEquals("epubcfi(/6/2/4/2/1:14)", draft?.endCfi)
    }

    @Test
    fun quoteStartRequiresExpectedOffsetInsideVisibleWindow() {
        val content = "alpha beta beta gamma"

        assertEquals(
            11,
            SearchHighlightPlanner.quoteStart(
                content = content,
                quote = "beta",
                windowStart = 6,
                windowEnd = content.length,
                expectedLocalStart = 10,
                tolerance = 2,
            ),
        )
        assertNull(
            SearchHighlightPlanner.quoteStart(
                content = content,
                quote = "beta",
                windowStart = 6,
                windowEnd = content.length,
                expectedLocalStart = 1,
                tolerance = 8,
            ),
        )
    }

    @Test
    fun correctionDirectionUsesPageNumberBeforeFallbackKeys() {
        assertEquals(true, SearchHighlightPlanner.correctionDirection(5, 4, "same", "same", true))
        assertEquals(false, SearchHighlightPlanner.correctionDirection(3, 4, "same", "same", true))
        assertNull(SearchHighlightPlanner.correctionDirection(4, 4, "target", "current", false))
        assertEquals(true, SearchHighlightPlanner.correctionDirection(null, null, "target", "current", true))
        assertEquals(true, SearchHighlightPlanner.correctionDirection(null, null, null, null, false))
    }

    @Test
    fun highlightIdRangeIsBounded() {
        val base = SearchHighlightPlanner.DEFAULT_MARK_ID_BASE

        assertTrue(SearchHighlightPlanner.isHighlightId(base))
        assertTrue(SearchHighlightPlanner.isHighlightId(base + SearchHighlightPlanner.DEFAULT_MARK_ID_RANGE - 1))
        assertFalse(SearchHighlightPlanner.isHighlightId(base - 1))
        assertFalse(SearchHighlightPlanner.isHighlightId(base + SearchHighlightPlanner.DEFAULT_MARK_ID_RANGE))
    }
}
