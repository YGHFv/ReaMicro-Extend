package com.reamicro.fix.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HostUiArgumentsTest {
    @Test
    fun topBarActionChangeMustInitializeNativeDefaults() {
        val flags = HostUiArguments.b2TopBarActionsFlags(1 or 0x40)
        assertEquals(0, flags and 1)
        assertEquals(0x800000, flags and 0x800000)
        assertEquals(0x40, flags and 0x40)
    }

    @Test
    fun removesOnlyVerifiedInlinedSlotsAndKeepsMask() {
        val args = Array<Any?>(22) { it }
        args[3] = null
        args[5] = null
        val actual = HostUiArguments.b2Text(args)
        assertEquals(20, actual.size)
        assertEquals(7, actual[5])
        assertEquals(18, actual[16])
        assertEquals(21, actual[19])
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesExplicitRemovedFontStyle() {
        val args = arrayOfNulls<Any>(22)
        args[5] = "explicit font style"
        HostUiArguments.b2Text(args)
    }
}
