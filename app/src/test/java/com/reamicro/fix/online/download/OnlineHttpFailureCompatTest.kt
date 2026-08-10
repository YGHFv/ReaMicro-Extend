package com.reamicro.fix.online.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineHttpFailureCompatTest {
    @Test
    fun circuitOpenUsesTaskLevelCooldown() {
        val error = OnlineSourceHttpException(503, "fanqie upstream circuit is open")

        assertEquals(OnlineHttpRetryKind.CIRCUIT_OPEN, onlineHttpRetryKind(error))
        assertEquals(30_000L, onlineHttpRetryDelayMs(error, 1))
        assertEquals(120_000L, onlineHttpRetryDelayMs(error, 8))
    }

    @Test
    fun transientGatewayFailureUsesIncreasingDelay() {
        val error = OnlineSourceHttpException(502, "bad gateway")

        assertEquals(OnlineHttpRetryKind.TRANSIENT, onlineHttpRetryKind(error))
        assertEquals(6_000L, onlineHttpRetryDelayMs(error, 1))
        assertEquals(18_000L, onlineHttpRetryDelayMs(error, 3))
    }

    @Test
    fun retryAfterHeaderTakesPriority() {
        val error = OnlineSourceHttpException(503, "busy", retryAfterMs = 45_000L)
        assertEquals(45_000L, onlineHttpRetryDelayMs(error, 1))
    }

    @Test
    fun unsupportedFanqieCryptStatusIsPermanentForCurrentChapter() {
        assertTrue(isPermanentOnlineChapterFailure(OnlineSourceHttpException(502, "full: unsupported crypt_status=NaN")))
        assertFalse(isPermanentOnlineChapterFailure(OnlineSourceHttpException(502, "bad gateway")))
    }
}
