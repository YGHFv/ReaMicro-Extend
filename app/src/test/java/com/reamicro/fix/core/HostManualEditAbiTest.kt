package com.reamicro.fix.core

import org.junit.Assert.*
import org.junit.Test

class HostManualEditAbiTest {
    @Test fun newHostUsesItsVerifiedScreenAndLambdaBranch() {
        val abi = requireNotNull(HostManualEditAbi.forProfile("2.3.4-b1"))
        assertEquals("jvd", abi.publishScreen)
        assertEquals("b", abi.screenMethod)
        assertEquals("mr1", abi.topBarContent)
        assertEquals(11.toByte(), abi.topBarBranch)
        assertEquals("o0", abi.relateMethod)
        assertEquals("manual_edit_host_234_b1", abi.capability)
    }

    @Test fun releaseContractRemainsIndependent() {
        val abi = requireNotNull(HostManualEditAbi.forProfile("2.3.3"))
        assertEquals("utd", abi.publishScreen)
        assertEquals("a", abi.screenMethod)
        assertEquals("vo1", abi.topBarContent)
        assertEquals(14.toByte(), abi.topBarBranch)
        assertEquals("k", abi.relateMethod)
        assertNull(HostManualEditAbi.forProfile("2.3.3-rc3"))
        assertNull(HostManualEditAbi.forProfile("2.3.5"))
    }

    @Test fun capabilityMustMatchBothHostAndFeatureIdentity() {
        val capability = "manual_edit_host_234_b1"
        assertNull(ExternalFeatureCompatibility.disabledReason("manual-edit", setOf("manual_edit", capability), "2.3.4-b1"))
        listOf(setOf("manual_edit"), setOf(capability), setOf("manual_edit", "manual_edit_host_233")).forEach {
            assertNotNull(ExternalFeatureCompatibility.disabledReason("manual-edit", it, "2.3.4-b1"))
        }
        assertNotNull(ExternalFeatureCompatibility.disabledReason("other", setOf("manual_edit", capability), "2.3.4-b1"))
        assertNotNull(ExternalFeatureCompatibility.disabledReason("manual-edit", setOf("manual_edit", capability), "2.3.3"))
    }

    @Test fun unknownHostsCannotFallThroughToLegacyHooks() {
        val capabilities = setOf("manual_edit", "manual_edit_host_233", "manual_edit_host_234_b1")
        listOf("automatic", "2.3.5", "unknown").forEach {
            assertNotNull(ExternalFeatureCompatibility.disabledReason("manual-edit", capabilities, it))
        }
        listOf("identity", "2.3.3-b2", "2.3.3-b3").forEach {
            assertNull(ExternalFeatureCompatibility.disabledReason("manual-edit", setOf("manual_edit"), it))
        }
    }

    @Test fun metadataRowsUseTheRelocatedEditAndCopyIcons() {
        assertEquals("yke" to "e", HostNativeUiAbi.metadataIcon("2.3.4-b1", false))
        assertEquals("cee" to "d", HostNativeUiAbi.metadataIcon("2.3.4-b1", true))
        assertEquals("bgd" to "d", HostNativeUiAbi.metadataIcon("2.3.3", false))
        assertEquals("drc" to "l", HostNativeUiAbi.metadataIcon("2.3.3", true))
    }
}
