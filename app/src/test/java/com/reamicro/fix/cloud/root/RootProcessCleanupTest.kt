package com.reamicro.fix.cloud.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootProcessCleanupTest {
    @Test
    fun `legacy and old supervisor cleanup commands have valid shell syntax`() {
        for (script in listOf(RootProcessCleanup.legacy(), RootProcessCleanup.oldSupervisor(),
            "if false; then :; else ${RootProcessCleanup.oldSupervisor()}; fi")) {
            val result = RootCommandRunner.execute(ProcessBuilder("sh", "-n"), script)
            assertEquals(result.output, 0, result.exitCode)
        }
    }

    @Test
    fun `cleanup matches complete argv entries and not broad process substrings`() {
        val script = RootProcessCleanup.legacy()
        assertTrue(script.contains("grep -Fxq"))
        assertFalse(script.contains("pkill"))
        assertTrue(script.contains(RootProcessCleanup.LEGACY_SCRIPT))
    }

    @Test
    fun `supervisor cleanup cannot delete the active module directory`() {
        val script = RootProcessCleanup.oldSupervisor()
        assertFalse(script.contains("rm -"))
        assertTrue(script.contains("/modules/reamicro_automation/watchdog.sh"))
    }
}
