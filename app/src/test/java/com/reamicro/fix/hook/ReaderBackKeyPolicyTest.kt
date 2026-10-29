package com.reamicro.fix.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderBackKeyPolicyTest {
    private fun release(
        profile: String = "2.3.3-rc3",
        key: Int = 4,
        action: Int = 0,
        repeat: Int = 0,
        cancelled: Boolean = false,
        status: String = "Reader",
        hidden: Boolean = true,
        empty: Boolean = true,
        editing: Boolean = false,
    ) = shouldReleaseReaderBackKey(profile, key, action, repeat, cancelled, status, hidden, empty, editing)

    @Test
    fun `plain reader back down reaches the activity instead of moving focus`() {
        assertTrue(release())
        assertTrue(release(status = "Menu"))
        assertTrue(release(profile = "2.3.3"))
    }

    @Test
    fun `settings sheets selections and editors retain native consumers`() {
        for (status in listOf("", "Catalog", "TypeSetting", "Theme", "Font", "Highlight", "MarginEditor")) {
            assertFalse(release(status = status))
        }
        assertFalse(release(hidden = false))
        assertFalse(release(empty = false))
        assertFalse(release(editing = true))
    }

    @Test
    fun `other keys releases repeats cancellations and hosts remain unchanged`() {
        assertFalse(release(key = 111))
        assertFalse(release(key = 24))
        assertFalse(release(action = 1))
        assertFalse(release(repeat = 1))
        assertFalse(release(cancelled = true))
        for (profile in listOf("identity", "2.3.3-b4", "2.3.3-rc4")) {
            assertFalse(release(profile = profile))
        }
    }
}
