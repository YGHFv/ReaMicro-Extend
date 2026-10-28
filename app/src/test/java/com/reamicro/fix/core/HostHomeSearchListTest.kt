package com.reamicro.fix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class HostHomeSearchListTest {
    class Callback(val b: Any, val c: Any, val d: Any, val e: Any, val a: Byte)

    @Test fun preservesRestartInputsAndFiltersOnlyNativeCallback() {
        val results = linkedMapOf(1 to listOf("native"), 8 to listOf("webdav"), 9 to listOf("local"))
        val callback = Callback(listOf("book"), Any(), results, Any(), 10)
        val copied = HostHomeSearchList(Callback::class.java).nativeOnly(callback, setOf(8, 9)) as Callback
        assertNotSame(callback, copied)
        assertSame(results, callback.d)
        assertEquals(setOf(1, 8, 9), results.keys)
        assertEquals(mapOf(1 to listOf("native")), copied.d)
        assertSame(callback.b, copied.b)
        assertSame(callback.c, copied.c)
        assertSame(callback.e, copied.e)
        assertEquals(callback.a, copied.a)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherMergedCallbackBranches() {
        HostHomeSearchList(Callback::class.java)
            .nativeOnly(Callback(Any(), Any(), emptyMap<Int, Any>(), Any(), 9), setOf(8))
    }
}
