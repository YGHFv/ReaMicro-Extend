package com.reamicro.fix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostNativeUiAbiTest {
    @Test
    fun onlyVerifiedProfilesUseTheNativeUi() {
        assertTrue(HostNativeUiAbi.supports("2.3.3-b2"))
        assertTrue(HostNativeUiAbi.supports("2.3.3-b4"))
        assertFalse(HostNativeUiAbi.supports("2.3.3-b3"))
        assertFalse(HostNativeUiAbi.supports("2.3.3-b5"))
    }

    @Test
    fun splitFacadesUseTheirVerifiedB4Members() {
        assertEquals("e", HostNativeUiAbi.method("2.3.3-b4", "StateFactory", "d"))
        assertEquals("a", HostNativeUiAbi.method("2.3.3-b4", "Scaffold", "J"))
        assertEquals("e", HostNativeUiAbi.method("2.3.3-b4", "Updater", "d"))
        assertEquals("d", HostNativeUiAbi.method("2.3.3-b4", "Updater", "c"))
        assertEquals("d", HostNativeUiAbi.method("2.3.3-b4", "Size", "d"))
        assertEquals("a", HostNativeUiAbi.method("2.3.3-b4", "HomeSearchFooter", "a"))
        assertEquals("J", HostNativeUiAbi.method("2.3.3-b2", "Scaffold", "J"))
    }

    @Test
    fun alignmentFieldsAreNotCopiedFromB2() {
        assertEquals("n", HostNativeUiAbi.alignment("2.3.3-b4", "start"))
        assertEquals("f", HostNativeUiAbi.alignment("2.3.3-b4", "center"))
        assertEquals("l", HostNativeUiAbi.alignment("2.3.3-b4", "centerVertically"))
        assertEquals("m", HostNativeUiAbi.alignment("2.3.3-b2", "start"))
    }

    @Test(expected = IllegalStateException::class)
    fun unknownB4MembersFailClosed() {
        HostNativeUiAbi.method("2.3.3-b4", "UnknownFacade", "a")
    }
}
