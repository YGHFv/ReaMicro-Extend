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
import android.net.Uri
import com.reamicro.fix.logging.ModuleAndroidLog

object CloudTaskNotifications {
    const val ACTION_OPEN_RECORDS = "com.reamicro.fix.OPEN_TASK_RECORDS"
    const val ACTION_POST = "com.reamicro.fix.CLOUD_TASK_NOTIFICATION"
    const val CHANNEL_ID = "reamicro_cloud_tasks"
    const val CHANNEL_NAME = "任务执行结果"
    const val EXTRA_ID = "notificationId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_RESULT = "result"
    const val EXTRA_ITEMS = "items"
    const val EXTRA_ACCOUNT_ID = "accountId"
    const val EXTRA_ACCOUNT_LABEL = "accountLabel"
    const val EXTRA_ACCOUNT_GENERATION = "taskAccountGeneration"
    const val EXTRA_TASK_TYPE = "taskType"
    const val EXTRA_PHASE = "taskPhase"
    const val EXTRA_EVENT_AT = "taskEventAt"
    private const val PROGRESS_CHANNEL = "reamicro_task_progress"

    const val MODULE_PACKAGE_NAME = "com.reamicro.fix"
    const val RECEIVER_CLASS = "com.reamicro.fix.notification.CloudTaskNotificationReceiver"
    const val ACTIVITY_CLASS = "com.reamicro.fix.notification.CloudTaskNotificationActivity"

    private const val LOG_TAG = "ReaMicroNotify"

    fun intent(messageId: String, title: String, text: String, result: String, itemsJson: String = "",
        accountId: String = "", accountLabel: String = "", taskType: String = "", accountGeneration: String = ""): Intent =
        Intent(ACTION_POST).apply {
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(EXTRA_ID, messageId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_RESULT, result)
            putExtra(EXTRA_ITEMS, itemsJson)
            putExtra(EXTRA_ACCOUNT_ID, accountId)
            putExtra(EXTRA_ACCOUNT_LABEL, accountLabel)
            putExtra(EXTRA_ACCOUNT_GENERATION, accountGeneration)
            putExtra(EXTRA_TASK_TYPE, taskType)
        }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun contentIntent(context: Context, accountId: String): PendingIntent {
        val intent = Intent(ACTION_OPEN_RECORDS).apply {
            component = ComponentName(MODULE_PACKAGE_NAME, "$MODULE_PACKAGE_NAME.ui.ModuleMainActivity")
            data = Uri.parse(taskRecordsUri(accountId))
            putExtra(EXTRA_ACCOUNT_ID, accountId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @Synchronized
    fun post(context: Context, intent: Intent, source: String): Boolean {
        if (context.packageName != MODULE_PACKAGE_NAME || intent.action != ACTION_POST) return false
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()
        val messageId = intent.getStringExtra(EXTRA_ID).orEmpty()
        if (accountId.isNotBlank() && (messageId.startsWith("ksu_") || messageId.startsWith("local_") ||
                source == "local-task-progress")) {
            val store = com.reamicro.fix.cloud.local.LocalTaskStore { context }
            if (store.get(accountId, intent.getStringExtra(EXTRA_TASK_TYPE).orEmpty()) == null ||
                store.accountGeneration(accountId) != intent.getStringExtra(EXTRA_ACCOUNT_GENERATION).orEmpty()
            ) return true
        }
        if (!intent.getStringExtra(EXTRA_PHASE).isNullOrBlank()) return postProgress(context, intent)
        if (messageId.isBlank()) return false
        val receipts = context.applicationContext.getSharedPreferences("reamicro_task_notification_receipts", Context.MODE_PRIVATE)
        val receiptKey = "delivered_$messageId"
        if (receipts.getBoolean(receiptKey, false)) return true
        val accountLabel = intent.getStringExtra(EXTRA_ACCOUNT_LABEL).orEmpty().ifBlank { accountId }
        val taskTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "任务消息" }
        val title = taskNotificationTitle(taskTitle, accountId, accountLabel)
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "任务状态已更新" }
        val result = intent.getStringExtra(EXTRA_RESULT).orEmpty()
        val displayText = cloudTaskNotificationText(text, intent.getStringExtra(EXTRA_ITEMS).orEmpty())
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager == null) {
            record(context, messageId, title, displayText, result, source, delivered = false, detail = "通知服务不可用")
            return false
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            if (!ModuleNotificationPermission.canPost(context, CHANNEL_ID)) {
                record(context, messageId, title, displayText, result, source, delivered = false, detail = "通知权限或通知类别未开启")
                return false
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
                .setContentIntent(contentIntent(context, accountId))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
            if (accountId.isNotBlank()) {
                builder.setSubText(accountLabel).setGroup("reamicro.tasks.$accountId")
            }

            manager.notify(messageId, BASE_NOTIFICATION_ID, builder.build())
            check(receipts.edit().putBoolean(receiptKey, true).commit()) { "通知投递回执保存失败" }
            ModuleAndroidLog.legacy(LOG_TAG, "cloud task notification posted source=$source id=$messageId")
            record(context, messageId, title, displayText, result, source, delivered = true)
            true
        }.getOrElse {
            ModuleAndroidLog.legacy(LOG_TAG, "cloud task notification failed source=$source id=$messageId", it)
            record(context, messageId, title, displayText, result, source, delivered = false, detail = it.message.orEmpty())
            false
        }
    }

    internal fun progress(context: Context, accountId: String, accountLabel: String, taskType: String, phase: String) {
        val update = intent("", "", "", "", accountId = accountId, accountLabel = accountLabel, taskType = taskType)
            .putExtra(EXTRA_ACCOUNT_GENERATION, com.reamicro.fix.cloud.local.LocalTaskStore { context }.accountGeneration(accountId))
            .putExtra(EXTRA_PHASE, phase).putExtra(EXTRA_EVENT_AT, System.currentTimeMillis().toString())
        post(context, update, "local-task-progress")
    }

    @Synchronized
    internal fun cancelAccount(context: Context, accountId: String) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.activeNotifications.filter {
                it.notification.group == "reamicro.tasks.$accountId" &&
                    (it.tag?.startsWith("ksu_") == true || it.tag?.startsWith("local_") == true ||
                        it.tag?.startsWith("task-progress:") == true)
            }.forEach { manager.cancel(it.tag, it.id) }
        }.onFailure { ModuleAndroidLog.error(LOG_TAG, "Failed to clear deleted account notifications", it) }
    }

    private fun postProgress(context: Context, intent: Intent): Boolean {
        val phase = intent.getStringExtra(EXTRA_PHASE).orEmpty()
        val rank = taskProgressPhaseRank(phase) ?: return false
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty().takeIf { it.isNotBlank() } ?: return false
        val taskType = intent.getStringExtra(EXTRA_TASK_TYPE).orEmpty().takeIf { it.isNotBlank() } ?: return false
        val at = intent.getStringExtra(EXTRA_EVENT_AT)?.toLongOrNull()?.takeIf { it > 0L } ?: return false
        val tag = taskProgressTag(accountId, taskType)
        val prefs = context.getSharedPreferences("reamicro_task_progress_order", Context.MODE_PRIVATE)
        val previousAt = prefs.getLong("$tag.at", 0L)
        val previousRank = prefs.getInt("$tag.phase", -1)
        if (!taskProgressEventAccepted(previousAt, previousRank, at, rank)) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching {
            if (phase == "finished") manager.cancel(tag, BASE_NOTIFICATION_ID)
            else {
                manager.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, "任务运行状态", NotificationManager.IMPORTANCE_LOW))
                if (!ModuleNotificationPermission.canPost(context, PROGRESS_CHANNEL)) return false
                val label = intent.getStringExtra(EXTRA_ACCOUNT_LABEL).orEmpty().ifBlank { accountId }
                val title = com.reamicro.fix.cloud.local.LocalTaskRunner.localTaskTitle(taskType)
                val text = if (phase == "waking") "已唤醒，正在准备网络" else "正在执行"
                val builder = Notification.Builder(context, PROGRESS_CHANNEL).setReaMicroSmallIcon()
                    .setContentTitle(taskNotificationTitle(title, accountId, label))
                    .setContentText(text).setSubText(label).setGroup("reamicro.tasks.$accountId")
                    .setContentIntent(contentIntent(context, accountId))
                    .setOngoing(true).setOnlyAlertOnce(true).setTimeoutAfter(15 * 60_000L)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                manager.notify(tag, BASE_NOTIFICATION_ID, builder.build())
            }
            check(prefs.edit().putLong("$tag.at", at).putInt("$tag.phase", rank).commit())
            true
        }.getOrDefault(false)
    }

    private fun record(
        context: Context,
        messageId: String,
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
                    messageId = messageId,
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


    private const val BASE_NOTIFICATION_ID = 4400
}
