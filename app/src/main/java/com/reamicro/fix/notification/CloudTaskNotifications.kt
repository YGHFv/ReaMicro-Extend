package com.reamicro.fix.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.reamicro.fix.R
import com.reamicro.fix.logging.ModuleAndroidLog

/**
 * 云端任务消息的通知投递。
 *
 * 这些消息由跑在阅微进程里的 [com.reamicro.fix.cloud.api.CloudTaskNotificationPoller] 拉取，
 * 但**不能在阅微进程里发通知**：`POST_NOTIFICATIONS` 是按应用授予的，阅微没授权就只能退化成
 * Toast；即便授权了，通知也会挂在阅微名下、用阅微的渠道。所以这里沿用在线补全下载通知那套
 * 成熟做法——把消息投给模块自己的组件，由模块进程用模块的权限、渠道和图标发出。
 */
object CloudTaskNotifications {
    const val ACTION_POST = "com.reamicro.fix.CLOUD_TASK_NOTIFICATION"
    const val CHANNEL_ID = "reamicro_cloud_tasks"
    const val CHANNEL_NAME = "云端任务消息"
    const val EXTRA_ID = "notificationId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_RESULT = "result"
    const val EXTRA_ITEMS = "items"

    const val MODULE_PACKAGE_NAME = "com.reamicro.fix"
    const val RECEIVER_CLASS = "com.reamicro.fix.notification.CloudTaskNotificationReceiver"
    const val ACTIVITY_CLASS = "com.reamicro.fix.notification.CloudTaskNotificationActivity"

    private const val LOG_TAG = "ReaMicroNotify"

    /** 构造投递用的 Intent；调用方再决定走广播还是 Activity。 */
    fun intent(messageId: String, title: String, text: String, result: String, itemsJson: String = ""): Intent =
        Intent(ACTION_POST).apply {
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(EXTRA_ID, messageId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_RESULT, result)
            putExtra(EXTRA_ITEMS, itemsJson)
        }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * 在当前进程发出通知。只应由模块进程里的组件调用。
     * 返回是否成功发出，调用方据此决定要不要继续兜底。
     */
    fun post(context: Context, intent: Intent, source: String): Boolean {
        if (intent.action != ACTION_POST) return false
        val messageId = intent.getStringExtra(EXTRA_ID).orEmpty().ifBlank { return false }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "云端任务消息" }
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "任务状态已更新" }
        val displayText = cloudTaskNotificationText(text, intent.getStringExtra(EXTRA_ITEMS).orEmpty())
        if (!hasPermission(context)) {
            ModuleAndroidLog.legacy(LOG_TAG, "cloud task notification permission denied source=$source")
            return false
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            builder
                .setSmallIcon(R.drawable.ic_notification_reamicro)
                .setContentTitle(title)
                .setContentText(displayText)
                .setStyle(Notification.BigTextStyle().bigText(displayText))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
            // 用消息 ID 派生通知 ID，同一条消息重复投递只会覆盖而不是堆叠。
            manager.notify(notificationId(messageId), builder.build())
            ModuleAndroidLog.legacy(LOG_TAG, "cloud task notification posted source=$source id=$messageId")
            true
        }.getOrElse {
            ModuleAndroidLog.legacy(LOG_TAG, "cloud task notification failed source=$source id=$messageId", it)
            false
        }
    }

    private fun notificationId(messageId: String): Int = BASE_NOTIFICATION_ID + (messageId.hashCode() and 0xFFFF)

    internal fun notificationIdForTest(messageId: String): Int = notificationId(messageId)

    private const val BASE_NOTIFICATION_ID = 4400
}
