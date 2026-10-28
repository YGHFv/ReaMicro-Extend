package com.reamicro.fix.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class HostSuspendArgumentsTest {
    private class Continuation

    @Test
    fun `reordered continuation preserves business argument order`() {
        val continuation = Continuation()
        assertArrayEquals(arrayOf<Any?>(7, continuation, "first", "second"), HostSuspendArguments.bind(
            arrayOf(Integer.TYPE, Continuation::class.java, String::class.java, String::class.java),
            1, continuation, arrayOf<Any?>(7, "first", "second"),
        ))
    }

    @Test
    fun `tail continuation retains original contract`() {
        val continuation = Continuation()
        assertArrayEquals(arrayOf<Any?>(7L, continuation), HostSuspendArguments.bind(
            arrayOf(java.lang.Long.TYPE, Continuation::class.java), 1, continuation, arrayOf(7L),
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `removed parameters must be explicitly adapted by caller`() {
        HostSuspendArguments.bind(arrayOf(Integer.TYPE, Continuation::class.java), 1, Continuation(), arrayOf<Any?>(7, true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `narrowed parameter rejects incompatible value`() {
        HostSuspendArguments.bind(arrayOf(Integer.TYPE, Continuation::class.java), 1, Continuation(), arrayOf(7L))
    }
}
