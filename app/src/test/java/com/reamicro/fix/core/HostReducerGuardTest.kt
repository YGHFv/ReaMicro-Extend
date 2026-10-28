package com.reamicro.fix.core

import org.junit.Assert.*
import org.junit.Test

class HostReducerGuardTest {
    private data class State(val value: String)

    @Test
    fun acceptsOnlyExpectedRecord() {
        val before = State("before")
        val after = State("after")
        assertSame(after, HostReducerGuard.reduce(before, State::class.java, { fail() }) { after })
        assertEquals("before", before.value)
    }

    @Test
    fun retainsOriginalOnMissingHostMember() {
        val before = State("before")
        var failure: Throwable? = null
        assertSame(before, HostReducerGuard.reduce(before, State::class.java, { failure = it }) {
            throw NoSuchMethodException("copy was removed")
        })
        assertTrue(failure is NoSuchMethodException)
    }

    @Test
    fun neverCommitsUnitOrNullToRecordFlow() {
        val before = State("before")
        for (output in arrayOf(Unit, null, "foreign")) {
            var failures = 0
            assertSame(before, HostReducerGuard.reduce(before, State::class.java, { failures++ }) { output })
            assertEquals(1, failures)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesForeignInputBeforeRunningReducer() {
        HostReducerGuard.reduce(Unit, State::class.java, { fail() }) { fail(); null }
    }
}
