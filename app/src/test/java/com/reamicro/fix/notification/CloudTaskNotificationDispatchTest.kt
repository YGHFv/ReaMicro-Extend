package com.reamicro.fix.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端任务通知的投递契约。
 *
 * 历史问题：轮询跑在阅微进程里，直接用阅微的 Context 发通知。POST_NOTIFICATIONS 是按应用
 * 授予的，阅微没授权就退化成 Toast——用户只看到 Toast 从没看到通知。修复方案是把消息投给
 * 模块自己的组件，由模块进程发出，与在线补全下载通知同一套路径。
 */
class CloudTaskNotificationDispatchTest {

    // 注：本工程单测没有 Robolectric，android.jar 是桩，Intent 的方法在运行时会抛
    // "not mocked"。所以这里只锁纯常量契约；Intent 组装本身靠编译期与 detekt 保证。

    @Test
    fun `extra 键名齐备且互不重复`() {
        val keys = listOf(
            CloudTaskNotifications.EXTRA_ID,
            CloudTaskNotifications.EXTRA_TITLE,
            CloudTaskNotifications.EXTRA_TEXT,
            CloudTaskNotifications.EXTRA_RESULT,
        )
        assertEquals("extra 键名不能重复", keys.size, keys.toSet().size)
        assertTrue("extra 键名不能为空", keys.none(String::isBlank))
    }

    @Test
    fun `通知渠道独立于在线补全下载渠道`() {
        // 云任务消息要能独立开关，不能和下载进度共用渠道。
        assertEquals("reamicro_cloud_tasks", CloudTaskNotifications.CHANNEL_ID)
        assertNotEquals("reamicro_online_completion_download", CloudTaskNotifications.CHANNEL_ID)
        assertTrue(CloudTaskNotifications.CHANNEL_NAME.isNotBlank())
    }

    @Test
    fun `组件常量指向模块包而非宿主`() {
        // 这三个常量是跨进程投递的全部依据；写错就会静默失败。
        assertEquals("com.reamicro.fix", CloudTaskNotifications.MODULE_PACKAGE_NAME)
        assertTrue(CloudTaskNotifications.RECEIVER_CLASS.startsWith("com.reamicro.fix."))
        assertTrue(CloudTaskNotifications.ACTIVITY_CLASS.startsWith("com.reamicro.fix."))
        assertNotEquals(CloudTaskNotifications.RECEIVER_CLASS, CloudTaskNotifications.ACTIVITY_CLASS)
    }

    @Test
    fun `广播 action 与 manifest intent-filter 一致`() {
        assertEquals("com.reamicro.fix.CLOUD_TASK_NOTIFICATION", CloudTaskNotifications.ACTION_POST)
    }

    @Test
    fun `同一条消息的通知 ID 稳定且不同消息不撞车`() {
        // 通知 ID 由消息 ID 派生：重复投递应覆盖而非堆叠，不同消息不能互相覆盖。
        val first = CloudTaskNotifications.notificationIdForTest("msg_1")
        assertEquals(first, CloudTaskNotifications.notificationIdForTest("msg_1"))
        assertNotEquals(first, CloudTaskNotifications.notificationIdForTest("msg_2"))
        assertTrue("通知 ID 必须为正", first > 0)
    }

    @Test
    fun `闹钟接收器类名指向模块包`() {
        // 这个常量替代了原先的 Intent(context, Receiver::class)：在阅微进程里那种写法会
        // 解析成阅微包名 + 模块类名，阅微 manifest 里没有该组件，闹钟永远投递不到。
        assertEquals(
            "com.reamicro.fix.cloud.api.CloudTaskHeartbeatReceiver",
            com.reamicro.fix.cloud.api.CloudTaskWakeScheduler.heartbeatReceiverClassForTest,
        )
        assertEquals("com.reamicro.fix.CLOUD_TASK_HEARTBEAT", com.reamicro.fix.cloud.api.CloudTaskWakeScheduler.ACTION_WAKE)
    }
}
