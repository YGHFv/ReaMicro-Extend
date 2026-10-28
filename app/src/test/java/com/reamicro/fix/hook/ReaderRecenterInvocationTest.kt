package com.reamicro.fix.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 固定 B2/B3/B4 四参重排与旧五参重排的反射分发。 */
class ReaderRecenterInvocationTest {
    private class FakeTarget {
        fun four(spine: Int, epub: Any, cfi: Any?, continuation: Any?): List<Any?> =
            listOf(spine, epub, cfi, continuation)

        fun five(spine: Int, epub: Any, cfi: Any?, replace: Boolean, continuation: Any?): List<Any?> =
            listOf(spine, epub, cfi, replace, continuation)
    }

    private fun method(name: String) =
        FakeTarget::class.java.declaredMethods.single { it.name == name }.apply { isAccessible = true }

    @Test
    fun `four argument recenter forwards without legacy boolean`() {
        val target = FakeTarget()
        val continuation = Any()
        val result = invokeOnDemandRecenter(method("four"), target, 7, "epub", "cfi", continuation)
        val arguments = result as List<*>
        assertEquals(listOf<Any?>(7, "epub", "cfi"), arguments.take(3))
        assertSame(continuation, arguments[3])
    }

    @Test
    fun `legacy five argument recenter keeps false boolean`() {
        val target = FakeTarget()
        val continuation = Any()
        val result = invokeOnDemandRecenter(method("five"), target, 9, "epub", "cfi", continuation)
        val arguments = result as List<*>
        assertEquals(listOf<Any?>(9, "epub", "cfi", false), arguments.take(4))
        assertSame(continuation, arguments[4])
    }
}
