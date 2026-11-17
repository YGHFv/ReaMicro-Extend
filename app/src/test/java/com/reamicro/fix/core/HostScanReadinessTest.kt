package com.reamicro.fix.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HostScanReadinessTest {
    private val original = HostScanUpdateStamp(1000, 2000, "a".repeat(64), "b".repeat(64))
    private val updated = original.copy(moduleUpdatedAt = 3000, moduleApk = "c".repeat(64))

    private fun validated(): HostScanReadiness = HostScanReadiness().apply {
        beginStartup()
        observeInstallation(original)
        finishStartup(original)
    }

    @Test fun startupBlocksBeforeAnyScanWindowExistsAndUntilValidationCompletes() {
        val readiness = HostScanReadiness()
        assertNotNull(readiness.automaticUpdateBlockReason())
        readiness.beginStartup()
        assertTrue(readiness.startupRunning)
        assertTrue(readiness.checking)
        readiness.observeInstallation(original)
        assertNotNull(readiness.automaticUpdateBlockReason())
        readiness.finishStartup(original)
        assertFalse(readiness.startupRunning)
        assertFalse(readiness.checking)
        assertNull(readiness.automaticUpdateBlockReason())
    }

    @Test fun anOrdinaryResumeCheckTemporarilyBlocksAndRetainsSameInstallationValidation() {
        val readiness = validated()
        readiness.beginChecking()
        assertNotNull(readiness.automaticUpdateBlockReason())
        readiness.finishChecking(original)
        assertNull(readiness.automaticUpdateBlockReason())
    }

    @Test fun finishedFailedStartupRemainsBlocked() {
        val readiness = HostScanReadiness()
        readiness.beginStartup()
        readiness.observeInstallation(original)
        readiness.finishStartup(null, "功能查询未完成")
        assertFalse(readiness.startupRunning)
        assertFalse(readiness.checking)
        assertNotNull(readiness.automaticUpdateBlockReason())
        readiness.beginChecking()
        readiness.finishChecking(original)
        assertNotNull(readiness.automaticUpdateBlockReason())
    }

    @Test fun failedManualScanCannotReuseTheEarlierSuccessfulValidation() {
        val readiness = validated()
        readiness.beginScan(original)
        assertNotNull(readiness.automaticUpdateBlockReason())
        readiness.finishScan(original, successful = false, failure = "功能查询未完成")
        assertNotNull(readiness.automaticUpdateBlockReason())
        readiness.finishChecking(original)
        assertNotNull(readiness.automaticUpdateBlockReason())
    }

    @Test fun anUpdatedInstallationNeedsItsOwnSuccessfulScan() {
        val readiness = validated()
        readiness.beginChecking()
        readiness.finishChecking(updated)
        assertNotNull(readiness.automaticUpdateBlockReason())
        readiness.beginScan(updated)
        assertNotNull(readiness.automaticUpdateBlockReason())
        readiness.finishScan(updated, successful = true)
        assertNull(readiness.automaticUpdateBlockReason())
    }

    @Test fun unreadableInstallationBlocksUntilItIsConfirmedToBeTheValidatedOne() {
        val readiness = validated()
        readiness.beginChecking()
        readiness.finishChecking(null, "安装包暂不可读")
        assertNotNull(readiness.automaticUpdateBlockReason())
        readiness.beginChecking()
        readiness.finishChecking(original)
        assertNull(readiness.automaticUpdateBlockReason())
    }

    @Test fun aNewModuleGenerationCannotTrustAnEarlierCompletedInstallationStamp() {
        assertNull(validated().automaticUpdateBlockReason())
        val newGeneration = HostScanReadiness()
        newGeneration.observeInstallation(original)
        assertNotNull(newGeneration.automaticUpdateBlockReason())
    }

    @Test fun batchPreflightDoesNotClearAScanThatStartedOnAnotherThread() {
        val readiness = validated()
        readiness.beginScan(original)
        readiness.observeInstallation(original)
        assertNotNull(readiness.automaticUpdateBlockReason())
        readiness.finishScan(original, successful = true)
        assertNull(readiness.automaticUpdateBlockReason())
    }

    @Test fun batchPreflightDoesNotClearAConcurrentResumeCheck() {
        val readiness = validated()
        readiness.beginChecking()
        readiness.observeInstallation(original)
        assertTrue(readiness.checking)
        assertNotNull(readiness.automaticUpdateBlockReason())
        readiness.finishChecking(original)
        assertNull(readiness.automaticUpdateBlockReason())
    }

    @Test fun aStaleStartupCompletionCannotValidateADifferentObservedInstallation() {
        val readiness = HostScanReadiness()
        readiness.beginStartup()
        readiness.observeInstallation(original)
        readiness.observeInstallation(updated)
        readiness.finishStartup(original)
        assertFalse(readiness.startupRunning)
        assertNotNull(readiness.automaticUpdateBlockReason())
    }

    @Test fun aStaleScanCompletionCannotOverwriteANewerInstallationObservation() {
        val readiness = validated()
        readiness.beginScan(original)
        readiness.observeInstallation(updated)
        readiness.finishScan(original, successful = true)
        assertNotNull(readiness.automaticUpdateBlockReason())
        readiness.finishChecking(updated)
        assertNotNull(readiness.automaticUpdateBlockReason())
    }

    @Test fun theBackgroundBookLoopSeesAScanStartedAfterTheFirstBook() {
        val readiness = validated()
        val firstBook = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val stopped = AtomicBoolean()
        val firstReason = AtomicReference<String?>()
        val laterReason = AtomicReference<String?>()
        val worker = Thread {
            firstReason.set(readiness.automaticUpdateBlockReason())
            firstBook.countDown()
            while (!stopped.get()) {
                val reason = readiness.automaticUpdateBlockReason()
                if (reason != null) {
                    laterReason.set(reason)
                    blocked.countDown()
                    break
                }
                Thread.yield()
            }
        }
        worker.start()
        try {
            assertTrue(firstBook.await(3, TimeUnit.SECONDS))
            assertNull(firstReason.get())
            readiness.beginScan(original)
            assertTrue(blocked.await(3, TimeUnit.SECONDS))
            assertNotNull(laterReason.get())
        } finally {
            stopped.set(true)
            worker.interrupt()
            worker.join(3000)
        }
    }
}
