package com.reamicro.fix.cloud.root

import com.reamicro.fix.notification.CloudTaskNotifications

internal data class RootTaskNotificationBroadcast(
    val id: String,
    val title: String,
    val text: String,
    val result: String,
    val items: String,
)

/** 组件名必须是「包名/类名」，裸类名会让 am 直接抛 Bad component name。 */
internal fun moduleComponent(className: String): String =
    CloudTaskNotifications.MODULE_PACKAGE_NAME + "/" + className

/**
 * 判断 `am broadcast` 是否真的被接收器处理：接收器成功落通知时把有序广播结果码置为 RESULT_OK(-1)。
 * 被系统冻结时 am 依旧返回退出码 0、结果码 0，因此只能从输出里的 result= 判断。
 */
internal fun rootTaskBroadcastConfirmed(output: String): Boolean =
    Regex("""result=(-?\d+)""").findAll(output).lastOrNull()?.groupValues?.get(1) == "-1"

/** 广播投递：参数以 argv 直传，交由 ProcessBuilder 处理，避免 shell 二次解析破坏含空格的正文。 */
internal fun rootTaskNotificationBroadcastArgs(message: RootTaskNotificationBroadcast): List<String> =
    amArgs(
        listOf("/system/bin/am", "broadcast", "--user", "0", "--include-stopped-packages", "-f", "0x01000000"),
        message,
        moduleComponent(CloudTaskNotifications.RECEIVER_CLASS),
    )

/**
 * 广播被系统冻结时的兜底：直接拉起免界面 Activity 投递。
 * Activity 启动不受冻结广播队列限制，能在 Root 任务跑完的当下立刻把通知发出。
 */
internal fun rootTaskNotificationActivityArgs(message: RootTaskNotificationBroadcast): List<String> =
    amArgs(
        listOf("/system/bin/am", "start-activity", "--user", "0", "-f", "0x10000000"),
        message,
        moduleComponent(CloudTaskNotifications.ACTIVITY_CLASS),
    )

private fun amArgs(head: List<String>, message: RootTaskNotificationBroadcast, component: String): List<String> =
    head + listOf("-a", CloudTaskNotifications.ACTION_POST, "-n", component) +
        notificationExtras(message).flatMap { (key, value) -> listOf("--es", key, value) }

private fun notificationExtras(message: RootTaskNotificationBroadcast): List<Pair<String, String>> = listOf(
    CloudTaskNotifications.EXTRA_ID to "ksu_${message.id}",
    CloudTaskNotifications.EXTRA_TITLE to message.title,
    CloudTaskNotifications.EXTRA_TEXT to message.text,
    CloudTaskNotifications.EXTRA_RESULT to message.result,
    CloudTaskNotifications.EXTRA_ITEMS to message.items,
)
