package com.reamicro.fix.cloud.root

import com.reamicro.fix.notification.CloudTaskNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTaskNotificationBroadcastTest {

    private val message = RootTaskNotificationBroadcast(
        id = "abc",
        title = "自动行商",
        text = "白狐报恩 · 收益 +54，已开启新行商",
        result = "success",
        items = "",
    )

    @Test
    fun broadcastTargetsNotificationReceiverWithFullComponentName() {
        val args = rootTaskNotificationBroadcastArgs(message)

        // am 只接受「包名/类名」，裸类名会直接抛 Bad component name。
        assertEquals("com.reamicro.fix/" + CloudTaskNotifications.RECEIVER_CLASS, valueAfter(args, "-n"))
        assertEquals(CloudTaskNotifications.ACTION_POST, valueAfter(args, "-a"))
        assertTrue(args.toString(), "--include-stopped-packages" in args)
        assertTrue(args.toString(), "0x01000000" in args)
    }

    @Test
    fun activityFallbackTargetsNoDisplayActivity() {
        val args = rootTaskNotificationActivityArgs(message)

        assertEquals("/system/bin/am", args.first())
        assertEquals("start-activity", args[1])
        assertEquals("com.reamicro.fix/" + CloudTaskNotifications.ACTIVITY_CLASS, valueAfter(args, "-n"))
        assertTrue(args.toString(), "0x10000000" in args)
    }

    @Test
    fun bothPathsCarryTheSameWholePayloadAsSeparateArguments() {
        listOf(rootTaskNotificationBroadcastArgs(message), rootTaskNotificationActivityArgs(message))
            .forEach { args ->
                assertEquals("ksu_abc", valueAfter(args, CloudTaskNotifications.EXTRA_ID))
                assertEquals("自动行商", valueAfter(args, CloudTaskNotifications.EXTRA_TITLE))
                // 含空格与「·」的正文必须是单个参数，不能被 shell 拆开。
                assertEquals("白狐报恩 · 收益 +54，已开启新行商", valueAfter(args, CloudTaskNotifications.EXTRA_TEXT))
                assertEquals("success", valueAfter(args, CloudTaskNotifications.EXTRA_RESULT))
                assertEquals("", valueAfter(args, CloudTaskNotifications.EXTRA_ITEMS))
            }
    }

    private fun valueAfter(args: List<String>, key: String): String? =
        args.indexOf(key).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    @Test
    fun onlyReceiverResultCodeCountsAsBroadcastSuccess() {
        // 接收器落通知成功后把有序广播结果码置为 RESULT_OK(-1)。
        assertTrue(rootTaskBroadcastConfirmed("Broadcasting: Intent { ... }\nBroadcast completed: result=-1"))
        // 被 ROM 冻结时 am 仍返回退出码 0，但结果码是 0，必须判定为未送达。
        assertTrue(!rootTaskBroadcastConfirmed("Broadcasting: Intent { ... }\nBroadcast completed: result=0"))
        assertTrue(!rootTaskBroadcastConfirmed("Broadcast completed: result=3"))
        assertTrue(!rootTaskBroadcastConfirmed(""))
    }
}
