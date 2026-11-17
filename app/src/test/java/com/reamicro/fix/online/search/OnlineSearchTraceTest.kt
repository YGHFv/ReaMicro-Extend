package com.reamicro.fix.online.search

import org.junit.Assert.*
import org.junit.Test

class OnlineSearchTraceTest {
    @Test fun queueTimeDoesNotConsumeTheRunningRequestBudget() {
        var now = 0L
        val trace = OnlineSearchTrace(1, 75_000L, 105_000L) { now }
        now += 20_000_000_000L
        assertEquals(85_000L, trace.remainingMs())
        assertTrue(trace.start())
        assertEquals(75_000L, trace.remainingMs())
        now += 9_000_000_000L
        assertEquals(66_000L, trace.remainingMs())
        assertTrue(trace.diagnostic().contains("queued:20000"))
    }

    @Test fun expiredQueuedTaskCannotStartOrChangeTheFinishedSnapshot() {
        var now = 0L
        val trace = OnlineSearchTrace(1, 75_000L, 30_000L) { now }
        now = 30_000_000_000L
        assertEquals(0L, trace.remainingMs())
        assertTrue(trace.finish())
        assertFalse(trace.start())
        assertFalse(trace.finish())
        trace.stage("dns")
        assertTrue(trace.diagnostic().contains("phase=queued"))
    }

    @Test fun partialResultsAndNetworkStagesSurviveTheDeadline() {
        var now = 0L
        val trace = OnlineSearchTrace(1, 75_000L, 30_000L) { now }
        trace.start()
        trace.stage("dns")
        now += 2_000_000_000L
        trace.stage("response-wait")
        trace.parsed()
        now += 74_000_000_000L
        assertTrue(trace.remainingMs() < 0L)
        trace.finish()
        assertTrue(trace.hasParsed())
        assertTrue(trace.diagnostic().contains("dns:2000"))
        assertTrue(trace.diagnostic().contains("response-wait:74000"))
    }

    @Test fun budgetsCoverHttpAndRateWaitInBothParserModes() {
        assertEquals(78_000L, onlineSearchRequestBudgetMs(true, 180_000, "10/3000"))
        assertEquals(228_000L, onlineSearchRequestBudgetMs(false, 180_000, "10/3000"))
        assertEquals(45_000L, onlineSearchRequestBudgetMs(false, 0, ""))
    }

    @Test fun eightSecondSlowSnapshotIsLoggedOnceWithoutCancellingTheRequest() {
        var now = 0L
        val trace = OnlineSearchTrace(1, 75_000L, 30_000L) { now }
        now = 10_000_000_000L
        assertFalse(trace.takeSlowSnapshot())
        trace.start()
        now += 8_000_000_000L
        assertTrue(trace.takeSlowSnapshot())
        assertFalse(trace.takeSlowSnapshot())
        assertEquals(67_000L, trace.remainingMs())
        assertTrue(trace.finish())
    }
}
