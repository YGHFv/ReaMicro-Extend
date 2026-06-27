package com.reamicro.fix.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.reamicro.fix.R

class OnlineCompletionNotificationService : Service() {
    private var foregroundId: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_ONLINE_COMPLETION_NOTIFICATION) return START_NOT_STICKY
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(LOG_TAG, "module notification permission denied")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val id = intent.getIntExtra(EXTRA_ID, 0).takeIf { it > 0 } ?: return START_NOT_STICKY
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { ONLINE_COMPLETION_TITLE }
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val progress = intent.getIntExtra(EXTRA_PROGRESS, 0).coerceIn(0, 100)
        val done = intent.getBooleanExtra(EXTRA_DONE, false)
        val notification = buildNotification(title, text, progress, done)
        runCatching {
            val manager = notificationManager()
            if (!done && foregroundId == 0) {
                foregroundId = id
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(id, notification)
                }
            } else {
                manager?.notify(id, notification)
            }
            Log.i(LOG_TAG, "module service notification posted id=$id progress=$progress done=$done title=$title")
            if (done) {
                manager?.notify(id, notification)
                if (id == foregroundId) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(false)
                    }
                    foregroundId = 0
                }
                manager?.let { cancelOnlineCompletionNotificationIfDone(it, id, true) }
                stopSelf(startId)
            }
        }.onFailure {
            Log.i(LOG_TAG, "module foreground notification failed", it)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, text: String, progress: Int, done: Boolean): Notification {
        ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ONLINE_COMPLETION_NOTIFICATION_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(onlineCompletionDownloadTitle(progress, text))
            .setContentText(onlineCompletionDownloadText(title, text))
            .setStyle(Notification.BigTextStyle().bigText(onlineCompletionDownloadBigText(title, text, progress)))
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setAutoCancel(done)
            .setProgress(100, progress, false)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager()?.createNotificationChannel(
            NotificationChannel(
                ONLINE_COMPLETION_NOTIFICATION_CHANNEL,
                ONLINE_COMPLETION_TITLE,
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(NOTIFICATION_SERVICE) as? NotificationManager

    private companion object {
        const val LOG_TAG = "ReaMicroNotify"
        const val ACTION_ONLINE_COMPLETION_NOTIFICATION = "com.reamicro.fix.ONLINE_COMPLETION_NOTIFICATION"
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_DONE = "done"
        const val ONLINE_COMPLETION_TITLE = "\u5728\u7ebf\u8865\u5168"
        const val ONLINE_COMPLETION_NOTIFICATION_CHANNEL = "reamicro_online_completion_download"
    }
}
