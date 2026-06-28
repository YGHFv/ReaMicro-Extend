package com.reamicro.fix.notification

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.reamicro.fix.R

class OnlineCompletionNotificationActivity : Activity() {
    private var pendingNotificationIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handleNotificationIntent(intent)) finishWithoutAnimation()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleNotificationIntent(intent)) finishWithoutAnimation()
    }

    private fun handleNotificationIntent(intent: Intent?): Boolean {
        if (intent?.action != ACTION_ONLINE_COMPLETION_NOTIFICATION) return true
        Log.i(LOG_TAG, "module notification activity launched")
        if (!hasNotificationPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pendingNotificationIntent = Intent(intent)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
                Log.i(LOG_TAG, "module notification permission requested")
                return false
            }
            Log.i(LOG_TAG, "module notification permission denied")
            return true
        }
        postNotification(this, intent)
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            val intent = pendingNotificationIntent
            pendingNotificationIntent = null
            if (granted && intent != null) {
                postNotification(this, intent)
            } else {
                Log.i(LOG_TAG, "module notification permission denied after request")
            }
            finishWithoutAnimation()
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
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
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val cancellable = intent.getBooleanExtra(EXTRA_CANCELLABLE, false)
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
            .setSmallIcon(R.drawable.ic_notification_reamicro)
            .setContentTitle(onlineCompletionDownloadTitle(progress, text))
            .setContentText(onlineCompletionDownloadText(title, text))
            .setStyle(Notification.BigTextStyle().bigText(onlineCompletionDownloadBigText(title, text, progress)))
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setAutoCancel(done)
            .setProgress(100, progress, false)
        if (!done && cancellable) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "\u53d6\u6d88",
                cancelPendingIntent(context, id, key),
            )
        }
        manager.notify(id, builder.build())
        cancelOnlineCompletionNotificationIfDone(manager, id, done)
        Log.i(LOG_TAG, "module activity notification posted fallback id=$id progress=$progress done=$done title=$title")
    }

    private fun cancelPendingIntent(context: Context, id: Int, key: String): PendingIntent {
        val intent = Intent(ACTION_ONLINE_COMPLETION_CANCEL).apply {
            setClass(context, OnlineCompletionNotificationReceiver::class.java)
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_KEY, key)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, id, intent, flags)
    }

    private fun finishWithoutAnimation() {
        finish()
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val LOG_TAG = "ReaMicroNotify"
        const val ACTION_ONLINE_COMPLETION_NOTIFICATION = "com.reamicro.fix.ONLINE_COMPLETION_NOTIFICATION"
        const val ACTION_ONLINE_COMPLETION_CANCEL = "com.reamicro.fix.ONLINE_COMPLETION_CANCEL"
        const val REQUEST_POST_NOTIFICATIONS = 4301
        const val EXTRA_ID = "id"
        const val EXTRA_KEY = "key"
        const val EXTRA_CANCELLABLE = "cancellable"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_DONE = "done"
        const val ONLINE_COMPLETION_TITLE = "\u5728\u7ebf\u8865\u5168"
        const val ONLINE_COMPLETION_NOTIFICATION_CHANNEL = "reamicro_online_completion_download"
    }
}
