package com.reamicro.fix.cloud.local

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object AutoReadTimeLock {
    const val TASK_TYPE = "cloud_auto_read"
    private const val MINUTE_MS = 60_000L

    private val recordZone = ZoneId.of("Asia/Shanghai")

    fun minutesOfDay(time: String): Int {
        val parts = time.trim().split(':')
        val hour = (parts.getOrNull(0)?.toIntOrNull() ?: 0).coerceIn(0, 23)
        val minute = (parts.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 59)
        return hour * 60 + minute
    }

    fun formatTime(minutes: Int): String =
        (minutes / 60).toString().padStart(2, '0') + ":" + (minutes % 60).toString().padStart(2, '0')

    fun safeTime(time: String, durationMinutes: Int): String =
        formatTime(maxOf(minutesOfDay(time), durationMinutes.coerceIn(1, 720)))

    fun recordDate(now: Long): String = Instant.ofEpochMilli(now).atZone(recordZone).toLocalDate().toString()

    fun usedMinutes(state: JSONObject, now: Long): Int =
        if (state.optString("dailyReadDate") == recordDate(now)) {
            state.optInt("dailyReadMinutes", 0).coerceIn(0, 1_440)
        } else 0

    fun unlockAt(durationMinutes: Int, usedMinutes: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val instant = Instant.ofEpochMilli(now)
        val localDay = instant.atZone(zone).toLocalDate()
        val localStart = localDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val recordStart = instant.atZone(recordZone).toLocalDate().atStartOfDay(recordZone).toInstant().toEpochMilli()

        val duration = durationMinutes.coerceIn(1, 720)
        val elapsedFloor = maxOf(localStart, recordStart) + (usedMinutes.coerceIn(0, 1_440) + duration) * MINUTE_MS
        val clockFloor = localDay.atTime(duration / 60, duration % 60).atZone(zone).toInstant().toEpochMilli()
        return maxOf(elapsedFloor, clockFloor)
    }

    fun nextDailyAt(time: String, durationMinutes: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val minutes = minutesOfDay(safeTime(time, durationMinutes))
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun candidate(days: Long): Long {
            val scheduled = day.plusDays(days).atTime(minutes / 60, minutes % 60).atZone(zone).toInstant().toEpochMilli()
            return maxOf(scheduled, unlockAt(durationMinutes, 0, scheduled, zone))
        }
        return candidate(0).takeIf { it > now } ?: candidate(1)
    }

    fun clampNextAt(task: LocalTask, state: JSONObject, candidate: Long, now: Long): Long {
        if (task.taskType != TASK_TYPE) return candidate
        val at = maxOf(candidate, now)
        val used = usedMinutes(state, at)
        if (used >= 720) return candidate
        return maxOf(candidate, unlockAt(minOf(task.durationMinutes.coerceIn(1, 720), 720 - used), used, at))
    }

    fun deferredOutcome(
        durationMinutes: Int,
        state: JSONObject,
        now: Long,
        dailyLimit: Int = 720,
        zone: ZoneId = ZoneId.systemDefault(),
    ): CloudTaskLocalRunner.Outcome? {
        val used = usedMinutes(state, now)
        if (used >= dailyLimit) return null
        val amount = minOf(durationMinutes.coerceIn(1, 720), dailyLimit - used)
        val unlock = maxOf(unlockAt(amount, used, now, zone), unlockAt(durationMinutes, 0, now, zone))
        if (now >= unlock) return null
        val next = JSONObject(state.toString()).put("nextRunAtOverride", unlock)
        val formatted = Instant.ofEpochMilli(unlock).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        return CloudTaskLocalRunner.Outcome(
            "paused", "自动阅读时间锁：今日已记 $used 分钟，本次 $amount 分钟，需等到 $formatted",
            next, notify = false,
        )
    }
}
