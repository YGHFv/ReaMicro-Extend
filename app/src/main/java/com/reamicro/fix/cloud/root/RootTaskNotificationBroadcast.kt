package com.reamicro.fix.cloud.root

import com.reamicro.fix.notification.CloudTaskNotifications

internal data class RootTaskNotificationBroadcast(
    val id: String,
    val title: String,
    val text: String,
    val result: String,
    val items: String,
)

internal fun rootTaskNotificationBroadcastArgs(
    message: RootTaskNotificationBroadcast,
): List<String> = listOf(
    "/system/bin/am", "broadcast", "--user", "0", "--include-stopped-packages",
    "-a", CloudTaskNotifications.ACTION_POST,
    "-n", CloudTaskNotifications.RECEIVER_CLASS,
    "--es", CloudTaskNotifications.EXTRA_ID, "ksu_${message.id}",
    "--es", CloudTaskNotifications.EXTRA_TITLE, message.title,
    "--es", CloudTaskNotifications.EXTRA_TEXT, message.text,
    "--es", CloudTaskNotifications.EXTRA_RESULT, message.result,
    "--es", CloudTaskNotifications.EXTRA_ITEMS, message.items,
)
