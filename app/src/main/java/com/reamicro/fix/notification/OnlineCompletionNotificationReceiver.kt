package com.reamicro.fix.notification

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.reamicro.fix.logging.ModuleAndroidLog
import com.reamicro.fix.logging.ModuleLogBuffer
import com.reamicro.fix.logging.ModuleLogState

class OnlineCompletionNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.packageName != CloudTaskNotifications.MODULE_PACKAGE_NAME) {
            if (isOrderedBroadcast) resultCode = Activity.RESULT_CANCELED
            return
        }
        ModuleLogState.applyFromIntent(intent)
        ModuleLogBuffer.attach(context.applicationContext)
        when (intent.action) {
            ACTION_ONLINE_COMPLETION_NOTIFICATION -> {
                if (intent.getBooleanExtra(BookshelfUpdateNotifications.EXTRA_REPORT, false)) {
                    runCatching { BookshelfUpdateNotifications.receive(context, intent) }.onFailure {
                        ModuleAndroidLog.legacy(LOG_TAG, "bookshelf report notification failed", it)
                    }
                } else {
                    val posted = runCatching { postProgressNotification(context, intent) }.getOrElse {
                        ModuleAndroidLog.legacy(LOG_TAG, "module receiver notification failed", it)
                        false
                    }
                    if (isOrderedBroadcast) resultCode = if (posted) Activity.RESULT_OK else Activity.RESULT_CANCELED
                }
            }
            ACTION_ONLINE_COMPLETION_CANCEL, OnlineCompletionNotificationActions.PAUSE -> handleStop(context, intent)
        }
    }

    private fun handleStop(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, 0).takeIf { it > 0 } ?: return
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        if (!OnlineNotificationEventOrder.acceptsStopRequest(id, key)) return
        if (intent.getBooleanExtra(OnlineCompletionNotificationActions.DISMISS_ONLY, false)) {
            OnlineNotificationEventOrder.accept(id, android.os.SystemClock.elapsedRealtimeNanos(), key)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(id)
            relayCancelToService(context, id, key)
            return
        }
        // A button requests a stop; only the host's real terminal result dismisses foreground work.
        relayStopToHost(context, id, key, intent.action ?: return)
        ModuleAndroidLog.legacy(LOG_TAG, "module receiver stop relayed id=$id action=${intent.action}")
    }

    private fun postProgressNotification(context: Context, intent: Intent): Boolean {
        val id = intent.getIntExtra(EXTRA_ID, 0).takeIf { it > 0 } ?: return false
        if (!OnlineNotificationEventOrder.accept(id,
                intent.getLongExtra(OnlineNotificationEventOrder.EXTRA_ISSUED_AT, 0L),
                intent.getStringExtra(EXTRA_KEY).orEmpty())) return true
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { ONLINE_COMPLETION_TITLE }
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val cancellable = intent.getBooleanExtra(EXTRA_CANCELLABLE, false)
        val progress = intent.getIntExtra(EXTRA_PROGRESS, 0).coerceIn(0, 100)
        val done = intent.getBooleanExtra(EXTRA_DONE, false)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ONLINE_COMPLETION_NOTIFICATION_CHANNEL,
                    ONLINE_COMPLETION_TITLE,
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        if (!ModuleNotificationPermission.canPost(context, ONLINE_COMPLETION_NOTIFICATION_CHANNEL)) return false
        if (!isOrderedBroadcast && startForegroundNotificationService(context, intent)) return true
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, ONLINE_COMPLETION_NOTIFICATION_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder
            .setReaMicroSmallIcon()
            .setContentTitle(onlineCompletionDownloadTitle(progress, text))
            .setContentText(onlineCompletionDownloadText(title, text))
            .setStyle(Notification.BigTextStyle().bigText(onlineCompletionDownloadBigText(title, text, progress)))
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setAutoCancel(done)
            .setProgress(100, progress, false)
        if (!done && cancellable) {
            OnlineCompletionNotificationActions.addTo(builder, context, id, key)
        }
        manager.notify(id, builder.build())
        cancelOnlineCompletionNotificationIfDone(manager, id, done, text)
        if (isOrderedBroadcast) startForegroundNotificationService(context, intent)
        ModuleAndroidLog.legacy(LOG_TAG, "module receiver notification posted id=$id progress=$progress done=$done title=$title")
        return true
    }

    private fun startForegroundNotificationService(context: Context, source: Intent): Boolean =
        runCatching {
            val serviceIntent = Intent(source).apply {
                setClass(context, OnlineCompletionNotificationService::class.java)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            ModuleAndroidLog.legacy(
                LOG_TAG,
                "module foreground notification service start requested " +
                    "id=${source.getIntExtra(EXTRA_ID, 0)} done=${source.getBooleanExtra(EXTRA_DONE, false)}",
            )
            true
        }.getOrElse {
            ModuleAndroidLog.legacy(LOG_TAG, "module foreground notification service start failed", it)
            false
        }

    private fun relayCancelToService(context: Context, id: Int, key: String) {
        runCatching {
            val serviceIntent = Intent(ACTION_ONLINE_COMPLETION_CANCEL).apply {
                setClass(context, OnlineCompletionNotificationService::class.java)
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_KEY, key)
            }
            context.startService(serviceIntent)
        }.onFailure {
            ModuleAndroidLog.legacy(LOG_TAG, "module receiver cancel service relay failed id=$id", it)
        }
    }

    private fun relayStopToHost(context: Context, id: Int, key: String, action: String) {
        HOST_PACKAGES.forEach { packageName ->
            runCatching {
                val relay = Intent(action).apply {
                    setPackage(packageName)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    putExtra(EXTRA_ID, id)
                    putExtra(EXTRA_KEY, key)
                }
                context.sendBroadcast(relay)
            }.onFailure {
                ModuleAndroidLog.legacy(LOG_TAG, "module receiver cancel relay failed package=$packageName", it)
            }
        }
    }

    private companion object {
        const val LOG_TAG = "ReaMicroNotify"
        const val ACTION_ONLINE_COMPLETION_NOTIFICATION = "com.reamicro.fix.ONLINE_COMPLETION_NOTIFICATION"
        const val ACTION_ONLINE_COMPLETION_CANCEL = "com.reamicro.fix.ONLINE_COMPLETION_CANCEL"
        const val EXTRA_ID = "id"
        const val EXTRA_KEY = "key"
        const val EXTRA_CANCELLABLE = "cancellable"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_DONE = "done"
        const val ONLINE_COMPLETION_TITLE = "\u5728\u7ebf\u8865\u5168"
        const val ONLINE_COMPLETION_NOTIFICATION_CHANNEL = "reamicro_online_completion_download"
        val HOST_PACKAGES = com.reamicro.fix.core.HostPackages.all
    }
}
