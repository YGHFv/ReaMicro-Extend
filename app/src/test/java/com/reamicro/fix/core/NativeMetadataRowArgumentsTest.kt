package com.reamicro.fix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NativeMetadataRowArgumentsTest {
    @Test
    fun preservesBoxedAlignmentAndWeightAcrossNativeTranslation() {
        val modifier = Any()
        val alignment = Any()
        val style = Any()
        val composer = Any()
        val args = HostUiArguments.b2Text(
            NativeMetadataRowArguments.text("Book", modifier, 42L, alignment, style, composer),
        )
        assertEquals(20, args.size)
        assertSame(modifier, args[1])
        assertSame(alignment, args[8])
        assertEquals(2, args[10])
        assertEquals(1, args[12])
        assertSame(style, args[15])
        assertSame(composer, args[16])
        assertEquals(0, (args[19] as Int) and (2 or 1024 or 4096 or 16384))
    }

    @Test
    fun retainsDefaultsForUnspecifiedTextOptions() {
        val args = NativeMetadataRowArguments.text("", Any(), 1L, Any(), Any(), Any())
        val defaults = args[21] as Int
        assertEquals(131066 and (2 or 1024 or 4096 or 16384).inv(), defaults)
        assertEquals(0, defaults and 4)
        assertEquals(131072, 131072 and defaults.inv())
    }
}
