package com.reamicro.fix.hook

import com.reamicro.fix.cloud.local.CloudTaskLocalRunner
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

    @Test
    fun `existing no blessing choice stays disabled in every task editor`() {
        for (spec in CLOUD_AUTOMATION_TASKS.filter { it.blessingOptions.isNotEmpty() }) {
            assertEquals(BLESSING_NONE, spec.resolveBlessingChoice(BLESSING_NONE))
            assertEquals(BLESSING_NONE, spec.resolveBlessingChoice("  "))
        }
    }

    @Test
    fun `only missing configuration uses the default blessing`() {
        val checkin = CLOUD_AUTOMATION_TASKS.first { it.taskType == "yeshe_checkin" }
        val merchant = CLOUD_AUTOMATION_TASKS.first { it.taskType == "traveling_merchant" }
        assertEquals(CloudTaskLocalRunner.BLESSING_LUCK, checkin.resolveBlessingChoice(null))
        assertEquals(CloudTaskLocalRunner.BLESSING_SAFETY, merchant.resolveBlessingChoice(null))
        assertEquals(BLESSING_NONE, checkin.resolveBlessingChoice(""))
        assertEquals(BLESSING_NONE, merchant.resolveBlessingChoice(""))
    }

    @Test
    fun `blessing wire values and labels round trip without changing choices`() {
        for (spec in CLOUD_AUTOMATION_TASKS) {
            for (blessing in spec.blessingOptions) {
                assertEquals(blessing, spec.resolveBlessingChoice(blessing.lowercase()))
                assertEquals(blessing, spec.resolveBlessingChoice(CloudTaskLocalRunner.blessingLabel(blessing)))
            }
        }
    }

    @Test
    fun `tasks without blessing options do not enable prayer`() {
        val reading = CLOUD_AUTOMATION_TASKS.first { it.taskType == "cloud_auto_read" }
        assertEquals(BLESSING_NONE, reading.resolveBlessingChoice(null))
        assertEquals(BLESSING_NONE, reading.resolveBlessingChoice("LUCK"))
    }
}
