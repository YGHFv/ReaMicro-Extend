package com.reamicro.fix.cloud.root

import com.reamicro.fix.notification.CloudTaskNotifications
import org.junit.Assert.assertEquals
import org.junit.Test

class RootTaskNotificationBroadcastTest {

    @Test
    fun broadcastTargetsNotificationReceiverWithPayload() {
        val args = rootTaskNotificationBroadcastArgs(
            RootTaskNotificationBroadcast(
                id = "abc",
                title = "自动行商",
                text = "行商已完成",
                result = "success",
                items = "",
            ),
        )

        assertEquals(CloudTaskNotifications.ACTION_POST, valueAfter(args, "-a"))
        assertEquals(CloudTaskNotifications.RECEIVER_CLASS, valueAfter(args, "-n"))
        assertEquals("ksu_abc", valueAfter(args, CloudTaskNotifications.EXTRA_ID))
        assertEquals("自动行商", valueAfter(args, CloudTaskNotifications.EXTRA_TITLE))
        assertEquals("行商已完成", valueAfter(args, CloudTaskNotifications.EXTRA_TEXT))
        assertEquals("success", valueAfter(args, CloudTaskNotifications.EXTRA_RESULT))
        assertEquals("", valueAfter(args, CloudTaskNotifications.EXTRA_ITEMS))
    }

    @Test
    fun broadcastIncludesStoppedPackages() {
        val args = rootTaskNotificationBroadcastArgs(
            RootTaskNotificationBroadcast("id", "title", "text", "success", ""),
        )

        assertEquals(true, "--include-stopped-packages" in args)
    }

    private fun valueAfter(args: List<String>, key: String): String? =
        args.indexOf(key).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
}
