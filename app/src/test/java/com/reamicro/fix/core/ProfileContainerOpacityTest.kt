package com.reamicro.fix.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileContainerOpacityTest {
    @Test fun dividerFadesWithContainerWithoutChangingRgb() {
        val native = 0x4dabcdef
        assertEquals(native, ProfileContainerOpacity.applyToArgb(native, 0))
        assertEquals(0x26abcdef, ProfileContainerOpacity.applyToArgb(native, 50))
        assertEquals(0x00abcdef, ProfileContainerOpacity.applyToArgb(native, 100))
    }

    @Test fun transparencyIsClampedAndTransparentInputStaysTransparent() {
        assertEquals(0xff123456.toInt(), ProfileContainerOpacity.applyToArgb(0xff123456.toInt(), -1))
        assertEquals(0x00123456, ProfileContainerOpacity.applyToArgb(0xff123456.toInt(), 101))
        assertEquals(0x00123456, ProfileContainerOpacity.applyToArgb(0x00123456, 50))
    }
}
