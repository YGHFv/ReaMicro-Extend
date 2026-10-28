package com.reamicro.fix.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HostTextDefaultsTest {
    @Test
    fun b2KeepsSourceMaskAfterRemovingParameters() {
        val bit = HostTextDefaults.fontFamilyBit("2.3.3-b2", 5)
        assertEquals(0x80, bit)
        assertEquals(0x7fff_ff7f, Int.MAX_VALUE and bit.inv())
    }

    @Test
    fun b4KeepsFontFamilySourceBit() {
        assertEquals(0x80, HostTextDefaults.fontFamilyBit("2.3.3-b4", 5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnverifiedB4Layout() {
        HostTextDefaults.fontFamilyBit("2.3.3-b4", 7)
    }

    @Test
    fun identityUsesOriginalParameterSlot() {
        assertEquals(0x80, HostTextDefaults.fontFamilyBit("identity", 7))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnverifiedB2Layout() {
        HostTextDefaults.fontFamilyBit("2.3.3-b2", 6)
    }
}
