package com.reamicro.fix.core

import org.junit.Assert.*
import org.junit.Test

class HostScanRulesTest {
    @Test fun ambiguousCandidatesAreRejected() {
        assertNull(HostScanRules.unique(listOf("a", "b")))
        assertNull(HostScanRules.unique(emptyList<String>()))
        assertEquals("a", HostScanRules.unique(listOf("a")))
    }

    @Test fun signaturesRetainPrimitiveAndArraySlots() {
        assertTrue(HostScanRules.compatibleSignature("(La;I)J", "(Lb;I)J"))
        assertFalse(HostScanRules.compatibleSignature("(La;I)J", "(Lb;Z)J"))
        assertFalse(HostScanRules.compatibleSignature("(La;I)J", "(Lb;II)J"))
        assertFalse(HostScanRules.compatibleSignature("([La;)V", "(Lb;)V"))
    }

    @Test fun cacheTracksBothApkAndRules() {
        assertNotEquals(HostScanRules.cacheKey("apk1", "r1"), HostScanRules.cacheKey("apk2", "r1"))
        assertNotEquals(HostScanRules.cacheKey("apk1", "r1"), HostScanRules.cacheKey("apk1", "r2"))
    }

    @Test fun payloadsMatchDexKitWithoutDroppingPositions() {
        assertEquals(listOf(18, 43, 0, 0, 0, 0, 255),
            HostScanRules.dexKitOpCodes(listOf(18, 43, 0, 0x100, 0x200, 0x300, 255)))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownPayloadInsteadOfTruncatingIt() {
        HostScanRules.dexKitOpCodes(listOf(0x400))
    }

    @Test fun engineChangeInvalidatesOldPartialCaches() {
        assertNotEquals("scan-v2:apk:rules", HostScanRules.cacheKey("apk", "rules"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsNegativeOpcode() {
        HostScanRules.dexKitOpCodes(listOf(-1))
    }
}
