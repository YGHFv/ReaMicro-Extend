package com.reamicro.fix.webdav

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineOnDemandPrefetchPlannerTest {
    @Test
    fun `reading prefetches next two chapters only`() {
        assertEquals(listOf(5, 6), OnlineOnDemandPrefetchPlanner.readingTargets(4, 10))
    }

    @Test
    fun `reading prefetch clips first and last chapter boundaries`() {
        assertEquals(listOf(1, 2), OnlineOnDemandPrefetchPlanner.readingTargets(0, 5))
        assertEquals(emptyList<Int>(), OnlineOnDemandPrefetchPlanner.readingTargets(4, 5))
    }

    @Test
    fun `catalog neighborhood excludes blocking target and keeps previous plus next`() {
        assertEquals(listOf(6, 8), OnlineOnDemandPrefetchPlanner.catalogNeighborTargets(7, 12))
    }

    @Test
    fun `invalid anchor returns no targets`() {
        assertEquals(emptyList<Int>(), OnlineOnDemandPrefetchPlanner.readingTargets(-1, 10))
        assertEquals(emptyList<Int>(), OnlineOnDemandPrefetchPlanner.readingTargets(10, 10))
        assertEquals(emptyList<Int>(), OnlineOnDemandPrefetchPlanner.readingTargets(0, 0))
    }
}
