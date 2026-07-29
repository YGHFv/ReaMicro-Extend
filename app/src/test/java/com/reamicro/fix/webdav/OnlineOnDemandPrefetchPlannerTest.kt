package com.reamicro.fix.webdav

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineOnDemandPrefetchPlannerTest {
    @Test
    fun `reading prefetches next chapter only`() {
        assertEquals(listOf(5), OnlineOnDemandPrefetchPlanner.readingTargets(4, 10))
    }

    @Test
    fun `reading prefetch clips first and last chapter boundaries`() {
        assertEquals(listOf(1), OnlineOnDemandPrefetchPlanner.readingTargets(0, 5))
        assertEquals(emptyList<Int>(), OnlineOnDemandPrefetchPlanner.readingTargets(4, 5))
    }

    @Test
    fun `catalog jump leaves prefetch to visible page statistics`() {
        assertEquals(emptyList<Int>(), OnlineOnDemandPrefetchPlanner.catalogNeighborTargets(7, 12))
    }

    @Test
    fun `invalid anchor returns no targets`() {
        assertEquals(emptyList<Int>(), OnlineOnDemandPrefetchPlanner.readingTargets(-1, 10))
        assertEquals(emptyList<Int>(), OnlineOnDemandPrefetchPlanner.readingTargets(10, 10))
        assertEquals(emptyList<Int>(), OnlineOnDemandPrefetchPlanner.readingTargets(0, 0))
    }

    @Test
    fun `maps epub cfi itemref step to online chapter index`() {
        assertEquals(0, OnlineOnDemandPrefetchPlanner.chapterIndexFromCfi("epubcfi(/6/2/4/0)", 20))
        assertEquals(5, OnlineOnDemandPrefetchPlanner.chapterIndexFromCfi("epubcfi(/6/12/4/5)", 20))
        assertEquals(10, OnlineOnDemandPrefetchPlanner.hostSpineIndexFromCfi("epubcfi(/6/12/4/5)"))
        assertEquals(null, OnlineOnDemandPrefetchPlanner.chapterIndexFromCfi("epubcfi(/6/13/4/5)", 20))
    }
}
