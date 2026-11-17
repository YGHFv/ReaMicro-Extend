package com.reamicro.fix.hook

import com.reamicro.fix.settings.ModuleSettingsSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookshelfCompletionPolicyTest {
    private val enabled = ModuleSettingsSnapshot(
        moduleEnabled = true,
        shelfBatchUpdate = true,
        shelfAutoUpdate = true,
    )
    private val baseTime = 1_700_000_000_000L

    @Test fun noHistoryAllowsTheFirstAutomaticUpdate() {
        assertTrue(BookshelfCompletionPolicy.canStartAutomatic(enabled, baseTime, 0L, false))
    }

    @Test fun completedBatchWaitsUntilExactlyOneHour() {
        assertFalse(BookshelfCompletionPolicy.canStartAutomatic(
            enabled, baseTime + 1_800_000L, baseTime, false, lastStartedAt = 0L,
        ))
        assertFalse(BookshelfCompletionPolicy.canStartAutomatic(
            enabled, baseTime + 3_599_999L, baseTime, false, lastStartedAt = 0L,
        ))
        assertTrue(BookshelfCompletionPolicy.canStartAutomatic(
            enabled, baseTime + 3_600_000L, baseTime, false, lastStartedAt = 0L,
        ))
    }

    @Test fun persistedStartThrottlesIncompleteBatchAfterFailureCancellationOrRestart() {
        // 失败、取消或进程重建没有完成时间时，仍使用持久化的开始时间。
        assertFalse(BookshelfCompletionPolicy.canStartAutomatic(
            enabled, baseTime + 60_000L, 0L, false, lastStartedAt = baseTime,
        ))
        assertFalse(BookshelfCompletionPolicy.canStartAutomatic(
            enabled, baseTime + 3_599_999L, 0L, false, lastStartedAt = baseTime,
        ))
        assertTrue(BookshelfCompletionPolicy.canStartAutomatic(
            enabled, baseTime + 3_600_000L, 0L, false, lastStartedAt = baseTime,
        ))
    }

    @Test fun newerStartOrManualCompletionPostponesAutomaticUpdate() {
        val recentTime = baseTime + 2_400_000L
        val now = baseTime + 3_600_000L
        assertFalse(BookshelfCompletionPolicy.canStartAutomatic(
            enabled, now, baseTime, false, lastStartedAt = recentTime,
        ))
        // 手动批次完成后，调用方传入最新完成时间，覆盖旧自动批次的开始时间。
        assertFalse(BookshelfCompletionPolicy.canStartAutomatic(
            enabled, now, recentTime, false, lastStartedAt = baseTime,
        ))
        assertTrue(BookshelfCompletionPolicy.canStartAutomatic(
            enabled, recentTime + 3_600_000L, recentTime, false, lastStartedAt = baseTime,
        ))
    }

    @Test fun legacyCompletionRemainsTheFallbackWithoutStartedTimestamp() {
        assertFalse(BookshelfCompletionPolicy.canStartAutomatic(
            enabled, baseTime + 3_599_999L, baseTime, false,
        ))
        assertTrue(BookshelfCompletionPolicy.canStartAutomatic(
            enabled, baseTime + 3_600_000L, baseTime, false,
        ))
    }

    @Test fun scanningBlocksBothFirstAndAlreadyDueAutomaticUpdates() {
        assertFalse(BookshelfCompletionPolicy.canStartAutomatic(enabled, baseTime, 0L, true))
        assertFalse(BookshelfCompletionPolicy.canStartAutomatic(
            enabled, baseTime + 3_600_000L, baseTime, true,
        ))
        assertTrue(BookshelfCompletionPolicy.canStartAutomatic(
            enabled, baseTime + 3_600_000L, baseTime, false,
        ))
    }

    @Test fun disablingAnyRequiredSwitchBlocksAutomaticUpdate() {
        listOf(
            enabled.copy(moduleEnabled = false),
            enabled.copy(shelfBatchUpdate = false),
            enabled.copy(shelfAutoUpdate = false),
        ).forEach { settings ->
            assertFalse(BookshelfCompletionPolicy.canStartAutomatic(settings, baseTime, 0L, false))
        }
    }

    @Test fun futureStartOrCompletionDoesNotAllowAnEarlyAutomaticUpdate() {
        assertFalse(BookshelfCompletionPolicy.canStartAutomatic(
            enabled, baseTime, baseTime + 60_000L, false,
            lastStartedAt = baseTime - 3_600_000L,
        ))
        assertFalse(BookshelfCompletionPolicy.canStartAutomatic(
            enabled, baseTime, baseTime - 3_600_000L, false,
            lastStartedAt = baseTime + 60_000L,
        ))
    }
}
