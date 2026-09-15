package com.reamicro.fix.cloud.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「任务唤醒时刻」必须只由任务自身决定，不能含 15 分钟兜底。
 *
 * 回归背景：`schedule()` 把**含兜底**的闹钟触发时刻写给了 root 看门狗。兜底是 `now + 15min` 且与
 * 任务时刻取 min，于是写出去的值永远 ≤15 分钟——用户开了 root 增强，看门狗照样每 15 分钟醒一次，
 * 与"按任务时刻唤醒"完全相反。这里锁住两件事：任务时刻要如实透出，兜底不能混进来。
 */
class TaskDrivenWakeAtTest {

    private val now = 1_700_000_000_000L
    private val midnight = now + 6 * 3_600_000L

    @Test
    fun `取任务时刻里最近的一个`() {
        val cloud = now + 3 * 3_600_000L
        val local = now + 2 * 3_600_000L
        assertEquals(local, CloudTaskWakeScheduler.taskDrivenWakeAt(now, midnight, cloud, local))
    }

    @Test
    fun `没有任务时间时退到零点`() {
        assertEquals(
            midnight,
            CloudTaskWakeScheduler.taskDrivenWakeAt(now, midnight, Long.MAX_VALUE, Long.MAX_VALUE),
        )
    }

    @Test
    fun `已经到期的任务尽快唤醒而不是跳到零点`() {
        val future = now + 3_600_000L
        assertEquals(
            now + 60_000L,
            CloudTaskWakeScheduler.taskDrivenWakeAt(now, future, now - 1_000L, now - 2_000L),
        )
    }

    @Test
    fun `本地重排和网络失败不能清掉已知云任务时间`() {
        assertEquals(now + 3_600_000L, CloudTaskWakeScheduler.updatedCloudTaskAt(now + 3_600_000L, null))
    }

    @Test
    fun `服务器明确没有云任务时清空缓存`() {
        assertEquals(0L, CloudTaskWakeScheduler.updatedCloudTaskAt(now + 3_600_000L, 0L))
    }

    @Test
    fun `服务器新时刻替换旧缓存`() {
        assertEquals(now + 60_000L, CloudTaskWakeScheduler.updatedCloudTaskAt(now + 3_600_000L, now + 60_000L))
    }

    @Test
    fun `停用云端后本地重排清除云端唤醒缓存`() {
        val cloudTaskAt = CloudTaskWakeScheduler.updatedCloudTaskAt(now - 60_000L, null, enabled = false)
        assertEquals(0L, cloudTaskAt)
        assertEquals(now + 3_600_000L,
            CloudTaskWakeScheduler.taskDrivenWakeAt(now, midnight, cloudTaskAt, now + 3_600_000L))
    }

    @Test
    fun `停用后迟到的云端响应不能恢复唤醒缓存`() {
        assertEquals(0L,
            CloudTaskWakeScheduler.updatedCloudTaskAt(now + 60_000L, now + 3_600_000L, enabled = false))
    }

    @Test
    fun `结果不含 15 分钟兜底`() {
        // 任务在 6 小时后：唤醒时刻就该是 6 小时后，而不是被兜底拉到 15 分钟内。
        val delayed = now + 6 * 3_600_000L
        val wake = CloudTaskWakeScheduler.taskDrivenWakeAt(now, Long.MAX_VALUE, Long.MAX_VALUE, delayed)
        assertTrue("看门狗时刻被兜底拉近了：${wake - now}ms", wake - now > 15 * 60_000L)
        assertEquals(delayed, wake)
    }
}
