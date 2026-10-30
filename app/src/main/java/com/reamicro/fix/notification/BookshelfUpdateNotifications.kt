package com.reamicro.fix.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.reamicro.fix.BuildConfig
import com.reamicro.fix.logging.ModuleAndroidLog
import com.reamicro.fix.logging.ModuleLogState

internal object BookshelfUpdateNotifications {
    const val EXTRA_REPORT = "bookshelf_update_report"
    private const val EXTRA_TITLE = "bookshelf_report_title"
    private const val EXTRA_SUMMARY = "bookshelf_report_summary"
    private const val EXTRA_DETAILS = "bookshelf_report_details"
    private const val CHANNEL = "reamicro_bookshelf_update_report"
    private const val NOTIFICATION_ID = 0x524d5342

    fun send(context: Context, report: BookshelfUpdateReport, conciseLogEnabled: Boolean) {
        // 使用模块通知权限，不经过单书下载服务及其完成后自动消失逻辑。
        val intent = Intent("com.reamicro.fix.ONLINE_COMPLETION_NOTIFICATION").apply {
            setClassName(BuildConfig.APPLICATION_ID, OnlineCompletionNotificationReceiver::class.java.name)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(EXTRA_REPORT, true)
            putExtra(EXTRA_TITLE, report.title)
            putExtra(EXTRA_SUMMARY, report.summary)
            putExtra(EXTRA_DETAILS, report.details.take(30_000))
            putExtra(ModuleLogState.EXTRA_CONCISE_LOG_ENABLED, conciseLogEnabled)
        }
        context.sendBroadcast(intent)
    }

    fun receive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ModuleAndroidLog.legacy("ReaMicroNotify", "bookshelf report notification permission denied")
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val title = intent.getStringExtra(EXTRA_TITLE)?.take(100) ?: return
        val summary = intent.getStringExtra(EXTRA_SUMMARY)?.take(500) ?: return
        val details = intent.getStringExtra(EXTRA_DETAILS)?.take(30_000) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "书架更新详情", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(context, CHANNEL)
            .setReaMicroSmallIcon()
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(details).setSummaryText(summary))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        // 固定ID替换上次汇总；保留到用户清除，不设置完成后自动取消计时。
        manager.notify(NOTIFICATION_ID, notification)
        ModuleAndroidLog.legacy("ReaMicroNotify", "bookshelf report posted: $summary")
    }
}
