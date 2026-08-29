package com.reamicro.fix.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudAutomationSettingsTest {
    @Test
    fun `normalizes daily task time`() {
        assertEquals("07:05", normalizeCloudAutomationTime("7:5"))
        assertEquals("00:05", normalizeCloudAutomationTime("00:05"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid daily task time`() {
        normalizeCloudAutomationTime("24:00")
    }
}
