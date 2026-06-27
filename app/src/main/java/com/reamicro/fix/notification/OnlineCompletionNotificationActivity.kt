package com.reamicro.fix.notification

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.reamicro.fix.R

class OnlineCompletionNotificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNotificationIntent(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.action != ACTION_ONLINE_COMPLETION_NOTIFICATION) return
        Log.i(LOG_TAG, "module notification activity launched")
        if (!startNotificationService(intent)) {
            postNotification(this, intent)
        }
    }

    private fun startNotificationService(intent: Intent): Boolean {
        val done = intent.getBooleanExtra(EXTRA_DONE, false)
        val serviceIntent = Intent(intent).apply {
            setClass(this@OnlineCompletionNotificationActivity, OnlineCompletionNotificationService::class.java)
        }
        return runCatching {
            val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !done) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.i(LOG_TAG, "module notification activity started service component=$component done=$done")
            component != null
        }.getOrElse {
            Log.i(LOG_TAG, "module notification activity service start failed", it)
            false
        }
    }

    private fun postNotification(context: Context, intent: Intent) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(LOG_TAG, "module notification permission denied")
            return
        }
        val id = intent.getIntExtra(EXTRA_ID, 0).takeIf { it > 0 } ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { ONLINE_COMPLETION_TITLE }
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val progress = intent.getIntExtra(EXTRA_PROGRESS, 0).coerceIn(0, 100)
        val done = intent.getBooleanExtra(EXTRA_DONE, false)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ONLINE_COMPLETION_NOTIFICATION_CHANNEL,
                    ONLINE_COMPLETION_TITLE,
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, ONLINE_COMPLETION_NOTIFICATION_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(onlineCompletionDownloadTitle(progress, text))
            .setContentText(onlineCompletionDownloadText(title, text))
            .setStyle(Notification.BigTextStyle().bigText(onlineCompletionDownloadBigText(title, text, progress)))
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setAutoCancel(done)
            .setProgress(100, progress, false)
        manager.notify(id, builder.build())
        cancelOnlineCompletionNotificationIfDone(manager, id, done)
        Log.i(LOG_TAG, "module activity notification posted fallback id=$id progress=$progress done=$done title=$title")
    }

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
