package com.reamicro.fix.cloud.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台唤醒自检的判定逻辑。
 *
 * 历史问题：manifest 没申请 SCHEDULE_EXACT_ALARM，Android 12+ 上
 * canScheduleExactAlarms() 恒为 false，闹钟静默降级到 setAndAllowWhileIdle——
 * 该 API 在 Doze 下可能被推迟数小时，而且界面上完全看不出发生了降级。
 */
class CloudTaskWakeStatusTest {

    private fun status(exact: Boolean = true, battery: Boolean = true, notify: Boolean = true) =
        CloudTaskWakeStatus(exactAlarmAllowed = exact, batteryUnrestricted = battery, notificationAllowed = notify)

    @Test
    fun `三项齐备才算健康`() {
        assertTrue(status().healthy)
        assertFalse(status(exact = false).healthy)
        assertFalse(status(battery = false).healthy)
        assertFalse(status(notify = false).healthy)
    }

    @Test
    fun `通知权限缺失优先提示`() {
        // 没有通知权限时，精确闹钟和电池优化都无意义——先解决通知。
        val text = status(exact = false, battery = false, notify = false).summary()
        assertTrue("应优先提示通知权限，实际：$text", text.contains("通知权限"))
    }

    @Test
    fun `分别缺失时给出对应提示`() {
        assertTrue(status(exact = false).summary().contains("精确闹钟"))
        assertTrue(status(battery = false).summary().contains("电池优化"))
        assertTrue(status(exact = false, battery = false).summary().let {
            it.contains("精确闹钟") && it.contains("电池优化")
        })
    }

    @Test
    fun `健康时不提示任何问题`() {
        val text = status().summary()
        assertTrue(text.contains("正常"))
        assertFalse(text.contains("未"))
    }

    @Test
    fun `逐项说明始终三行且标明后果`() {
        val details = status(exact = false, battery = false, notify = false).details()
        assertEquals(3, details.size)
        // 每项都要说明"未放行会怎样"，否则用户不知道该不该管。
        assertTrue(details.all { it.contains("（") })
        assertTrue(details.any { it.contains("推迟") })
        assertTrue(details.any { it.contains("冻结") })
    }

    // 注：Intent/Uri 在本工程单测里没有 Robolectric 支撑，运行时会抛 not mocked，
    // 所以跳转 Intent 的构造只能靠编译期保证，这里只锁住它们依据的包名常量。

    @Test
    fun `自检针对模块包而非宿主包`() {
        // 闹钟与通知都由模块进程承担，检查或跳转到阅微都毫无意义。
        assertEquals(
            "com.reamicro.fix",
            com.reamicro.fix.notification.CloudTaskNotifications.MODULE_PACKAGE_NAME,
        )
    }
}
