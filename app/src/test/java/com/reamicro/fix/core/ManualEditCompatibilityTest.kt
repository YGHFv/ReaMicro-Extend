package com.reamicro.fix.core

import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/** 本地修复包只开放已声明正式版契约的功能源，旧包和未知宿主仍禁用。 */
class ManualEditCompatibilityTest {
    @Test
    fun verifiedReleaseSourceIsAcceptedWithoutOpeningOldSources() {
        assertNull(ExternalFeatureCompatibility.disabledReason(
            "manual-edit", setOf("manual_edit", "manual_edit_host_233"), "2.3.3"))
        assertNotNull(ExternalFeatureCompatibility.disabledReason(
            "manual-edit", setOf("manual_edit"), "2.3.3"))
        assertNotNull(ExternalFeatureCompatibility.disabledReason(
            "manual-edit", setOf("manual_edit_host_233"), "2.3.3-rc3"))
        assertNotNull(ExternalFeatureCompatibility.disabledReason(
            "unverified", setOf("manual_edit", "manual_edit_host_233"), "2.3.3"))
        assertNotNull(ExternalFeatureCompatibility.disabledReason(
            "manual-edit", setOf("manual_edit_host_233"), "2.3.3"))
    }
}
