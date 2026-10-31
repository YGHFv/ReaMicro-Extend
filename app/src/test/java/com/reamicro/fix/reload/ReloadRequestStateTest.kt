package com.reamicro.fix.reload

import org.junit.Assert.*
import org.junit.Test

class ReloadRequestStateTest {
    @Test fun submitsExactlyOnce() {
        val state = ReloadRequestState()
        assertTrue(state.submit())
        assertFalse(state.submit())
        assertTrue(state.complete(true))
        assertFalse(state.submit())
        assertFalse(state.complete(false))
    }

    @Test fun serviceFailureBeforeSubmissionIsFinal() {
        val state = ReloadRequestState()
        assertTrue(state.complete(false))
        assertFalse(state.submit())
        assertFalse(state.complete(true))
        assertEquals(ReloadRequestState.Phase.FAILED, state.phase)
    }

    @Test fun pendingRemainsOwnedUntilLateCallback() {
        val state = ReloadRequestState()
        state.submit()
        repeat(100) { assertFalse(state.submit()) }
        assertEquals(ReloadRequestState.Phase.SUBMITTED, state.phase)
        assertTrue(state.complete(true))
        assertFalse(state.complete(true))
    }
}
