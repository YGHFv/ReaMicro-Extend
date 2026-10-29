package com.reamicro.fix.hook

import org.junit.Assert.*
import org.junit.Test

class DexKitScanPresentationTest {
    private fun notice(
        scanning: Boolean = false,
        error: String? = null,
        profile: String = "2.3.3",
        report: Boolean = true,
        resolved: Boolean = true,
        failures: Boolean = false,
        unchanged: Boolean = false,
    ) = dexKitScanNotice(scanning, error, profile, report, resolved, failures, unchanged)

    @Test fun changedResultOffersRestartWithoutExpandingDetails() {
        assertTrue(notice().restartRequired)
        assertTrue(notice(failures = true).restartRequired)
        assertTrue(notice(failures = true).message.contains("日志"))
        assertFalse(notice(failures = true).message.contains("class:"))
    }

    @Test fun runningUnchangedAndUnsupportedStatesDoNotSuggestRestartFixes() {
        assertFalse(notice(scanning = true).restartRequired)
        assertFalse(notice(unchanged = true).restartRequired)
        assertFalse(notice(unchanged = true, failures = true).restartRequired)
        assertFalse(notice(profile = "automatic").restartRequired)
        assertFalse(notice(profile = "identity").restartRequired)
        assertFalse(notice(report = false, resolved = false).restartRequired)
    }

    @Test fun errorsRemainConciseAndOfferRetryAfterRestart() {
        val message = notice(error = "A long stack trace class:old.Host")
        assertTrue(message.restartRequired)
        assertFalse(message.message.contains("stack trace"))
        assertFalse(message.message.contains("class:"))
    }
}
