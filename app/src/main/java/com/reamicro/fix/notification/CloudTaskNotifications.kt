package com.reamicro.fix.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.reamicro.fix.logging.ModuleAndroidLog

object CloudTaskNotifications {
    const val ACTION_OPEN_RECORDS = "com.reamicro.fix.OPEN_TASK_RECORDS"
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

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_OPEN_RECORDS).apply {
            component = ComponentName(MODULE_PACKAGE_NAME, "$MODULE_PACKAGE_NAME.ui.ModuleMainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun post(context: Context, intent: Intent, source: String): Boolean {
        if (intent.action != ACTION_POST) return false
        val messageId = intent.getStringExtra(EXTRA_ID).orEmpty().ifBlank { return false }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "云端任务消息" }
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "任务状态已更新" }
        val result = intent.getStringExtra(EXTRA_RESULT).orEmpty()
        val displayText = cloudTaskNotificationText(text, intent.getStringExtra(EXTRA_ITEMS).orEmpty())
        if (!hasPermission(context)) {
            ModuleAndroidLog.legacy(LOG_TAG, "cloud task notification permission denied source=$source")
            record(context, title, displayText, result, source, delivered = false, detail = "未授予通知权限")
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
                .setReaMicroSmallIcon()
                .setContentTitle(title)
                .setContentText(displayText)
                .setStyle(Notification.BigTextStyle().bigText(displayText))
                .setContentIntent(contentIntent(context))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)

            manager.notify(notificationId(messageId), builder.build())
            ModuleAndroidLog.legacy(LOG_TAG, "cloud task notification posted source=$source id=$messageId")
            record(context, title, displayText, result, source, delivered = true)
            true
        }.getOrElse {
            ModuleAndroidLog.legacy(LOG_TAG, "cloud task notification failed source=$source id=$messageId", it)
            record(context, title, displayText, result, source, delivered = false, detail = it.message.orEmpty())
            false
        }
    }

    private fun record(
        context: Context,
        title: String,
        text: CharSequence,
        result: String,
        source: String,
        delivered: Boolean,
        detail: String = "",
    ) {
        runCatching {
            NotificationRecordStore { context.applicationContext }.append(
                NotificationRecord(
                    at = System.currentTimeMillis(),
                    title = title,
                    text = text.toString(),
                    result = result,
                    source = source,
                    delivered = delivered,
                    detail = detail,
                ),
            )
        }
    }

    private fun notificationId(messageId: String): Int = BASE_NOTIFICATION_ID + (messageId.hashCode() and 0xFFFF)

    private const val BASE_NOTIFICATION_ID = 4400
}
