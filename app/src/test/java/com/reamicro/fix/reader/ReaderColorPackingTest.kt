package com.reamicro.fix.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderColorPackingTest {
    @Test
    fun srgbUsesUpperArgbBitsAndZeroColorSpaceId() {
        val colors = listOf(0, -1, 0xFF000000.toInt(), 0xFF3366FF.toInt(), 0x7F123456)
        for (argb in colors) {
            val packed = ReaderColorPacking.srgb(argb)
            assertEquals(argb, (packed ushr 32).toInt())
            assertEquals(0L, packed and 0xFFFFFFFFL)
            assertEquals(0L, packed and 63L)
        }
        assertEquals(-4294967296L, ReaderColorPacking.srgb(-1))
    }
}
