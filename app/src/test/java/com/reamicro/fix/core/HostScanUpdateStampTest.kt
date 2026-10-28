package com.reamicro.fix.core

import org.junit.Assert.*
import org.junit.Test

class HostScanUpdateStampTest {
    private val original = HostScanUpdateStamp(100, 200, "a".repeat(64), "b".repeat(64))

    @Test fun firstLaunchScansAndRecordedLaunchDoesNot() {
        assertTrue(original.needsScan(null, null))
        assertFalse(original.needsScan(original, null))
        assertEquals("首次启用更新检查", original.changeReason(null))
    }

    @Test fun hostReinstallWithSameVersionStillScans() {
        val next = original.copy(hostUpdatedAt = 101)
        assertTrue(next.needsScan(original, null))
        assertEquals("检测到阅微已更新", next.changeReason(original))
    }

    @Test fun moduleReinstallWithSameVersionStillScans() {
        val next = original.copy(moduleUpdatedAt = 201)
        assertTrue(next.needsScan(original, null))
        assertEquals("检测到模块已更新", next.changeReason(original))
    }

    @Test fun combinedUpdateAndRollbackAreDetected() {
        val next = original.copy(hostUpdatedAt = 99, moduleUpdatedAt = 199)
        assertTrue(next.needsScan(original, null))
        assertEquals("检测到阅微和模块已更新", next.changeReason(original))
    }

    @Test fun fileIdentityFallbackDetectsChanges() {
        assertTrue(original.copy(hostApk = "c".repeat(64)).needsScan(original, null))
        assertTrue(original.copy(moduleApk = "c".repeat(64)).needsScan(original, null))
    }

    @Test fun repeatedResumeDoesNotRetrySameFailedAttempt() {
        assertFalse(original.needsScan(null, original))
        assertTrue(original.copy(hostUpdatedAt = 101).needsScan(null, original))
        // 新进程没有 attempted，未持久化完成的任务会在下次打开重试。
        assertTrue(original.needsScan(null, null))
    }

    @Test fun roundTripAndMalformedRecords() {
        assertEquals(original, HostScanUpdateStamp.decode(original.encode()))
        listOf(null, "", "0|200|a|b", "bad", original.encode() + "|extra",
            "100|200||", "-1|200|${"a".repeat(64)}|${"b".repeat(64)}").forEach {
            assertNull(HostScanUpdateStamp.decode(it))
        }
    }
}
