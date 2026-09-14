package com.reamicro.fix.cloud.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 「下次执行时刻」必须等于用户配置的那个每天时间点。
 *
 * 回归背景：`persistOutcome` 此前一律用 `now + 24h`，用户配的是「每天 00:00」，模块却排到
 * "此刻之后 24 小时"，于是主界面显示的时间和阅微设置页里配的时间永远对不上，而且每天还在漂。
 */
class NextDailyRunAtTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(text: String): Long =
        Instant.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun local(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime().toString()

    @Test
    fun `今天还没到点就排今天`() {
        // 东八区 09-14 22:30 → 配的 23:00 还在今天。
        val now = at("2026-09-14T14:30:00Z")
        assertEquals("2026-09-14T23:00", local(nextDailyRunAt("23:00", now)))
    }

    @Test
    fun `今天已经过点就排明天`() {
        // 东八区 09-14 22:30 → 配的 00:00 已经过了，应排到 09-15 00:00。
        val now = at("2026-09-14T14:30:00Z")
        assertEquals("2026-09-15T00:00", local(nextDailyRunAt("00:00", now)))
    }

    @Test
    fun `正好等于当前分钟时排明天`() {
        // 相等时不能返回"就是现在"，否则会立刻再跑一次、形成热循环。
        val now = at("2026-09-14T14:30:00Z")
        assertTrue(local(nextDailyRunAt("22:30", now)).startsWith("2026-09-15T22:30"))
    }

    @Test
    fun `下一次永远在未来`() {
        val now = at("2026-09-14T14:30:00Z")
        listOf("00:00", "00:01", "22:29", "22:30", "22:31", "23:59").forEach { time ->
            val next = nextDailyRunAt(time, now)
            assertTrue("$time 应排在将来", next > now)
            assertTrue("$time 不该超过 24 小时", next - now <= 24 * 3_600_000L)
        }
    }

    @Test
    fun `脏配置不炸且退化成默认时间`() {
        val now = at("2026-09-14T14:30:00Z")
        // 空串按默认 00:05 处理；越界的小时被夹到合法范围（而不是抛异常让任务永不排程）。
        assertTrue(nextDailyRunAt("", now) > now)
        assertTrue(nextDailyRunAt("99:99", now) > now)
        assertTrue(nextDailyRunAt("abc", now) > now)
    }
}
