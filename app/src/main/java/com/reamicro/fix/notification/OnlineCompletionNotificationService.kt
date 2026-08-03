package com.reamicro.fix.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.reamicro.fix.R
import com.reamicro.fix.logging.ModuleAndroidLog
import com.reamicro.fix.logging.ModuleLogState

class OnlineCompletionNotificationService : Service() {
    private var foregroundId: Int = 0
    private val activeNotifications = linkedMapOf<Int, Notification>()
    private val activeNotificationKeys = linkedMapOf<Int, String>()
    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLockHandler = Handler(Looper.getMainLooper())
    private val wakeLockRenewal = object : Runnable {
        override fun run() {
            if (foregroundId == 0) return
            acquireOrRenewWakeLock()
            wakeLockHandler.postDelayed(this, WAKE_LOCK_RENEW_INTERVAL_MS)
        }
    }
    private val hostHeartbeat = object : Runnable {
        override fun run() {
            if (foregroundId == 0) return
            sendHostHeartbeat()
            wakeLockHandler.postDelayed(this, HOST_HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ModuleLogState.applyFromIntent(intent)
        val id = intent?.getIntExtra(EXTRA_ID, 0)?.takeIf { it > 0 } ?: FALLBACK_NOTIFICATION_ID
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { ONLINE_COMPLETION_TITLE }
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val key = intent?.getStringExtra(EXTRA_KEY).orEmpty()
        val cancellable = intent?.getBooleanExtra(EXTRA_CANCELLABLE, false) == true
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0)?.coerceIn(0, 100) ?: 0
        val done = intent?.getBooleanExtra(EXTRA_DONE, false) == true
        runCatching {
            if (intent?.action == ACTION_ONLINE_COMPLETION_CANCEL) {
                removeActiveNotification(id, startId)
                return START_NOT_STICKY
            }
            if (intent?.action != ACTION_ONLINE_COMPLETION_NOTIFICATION) {
                ModuleAndroidLog.legacy(LOG_TAG, "module foreground service ignored unexpected action=${intent?.action}")
                stopForegroundNow(removeNotification = true)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            val notification = buildNotification(id, key, cancellable, title, text, progress, done)
            val manager = notificationManager()
            if (!done) {
                activeNotifications[id] = notification
                activeNotificationKeys[id] = key
                if (foregroundId == 0) {
                    foregroundId = id
                    startForegroundCompat(id, notification)
                    startWakeLockRenewal()
                } else if (hasNotificationPermission()) {
                    manager?.notify(id, notification)
                }
            } else {
                if (foregroundId == 0) {
                    foregroundId = id
                    startForegroundCompat(id, notification)
                }
                activeNotifications.remove(id)
                activeNotificationKeys.remove(id)
                if (hasNotificationPermission()) manager?.notify(id, notification)
                if (id == foregroundId) {
                    val replacement = activeNotifications.entries.firstOrNull()
                    if (replacement != null) {
                        foregroundId = replacement.key
                        startForegroundCompat(replacement.key, replacement.value)
                    } else {
                        stopForegroundNow(removeNotification = false)
                        foregroundId = 0
                        stopWakeLockRenewal()
                    }
                }
                manager?.let { cancelOnlineCompletionNotificationIfDone(it, id, true) }
                if (activeNotifications.isEmpty()) stopSelf(startId)
            }
            if (!hasNotificationPermission()) {
                ModuleAndroidLog.legacy(LOG_TAG, "module notification permission denied")
            }
            ModuleAndroidLog.legacy(
                LOG_TAG,
                "module service notification posted id=$id progress=$progress done=$done " +
                    "active=${activeNotifications.size} title=$title",
            )
        }.onFailure {
            ModuleAndroidLog.legacy(LOG_TAG, "module foreground notification failed", it)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foregroundId = 0
        activeNotifications.clear()
        activeNotificationKeys.clear()
        stopWakeLockRenewal()
        super.onDestroy()
    }

    private fun removeActiveNotification(id: Int, startId: Int) {
        activeNotifications.remove(id)
        activeNotificationKeys.remove(id)
        notificationManager()?.cancel(id)
        if (id == foregroundId) {
            val replacement = activeNotifications.entries.firstOrNull()
            if (replacement != null) {
                foregroundId = replacement.key
                startForegroundCompat(replacement.key, replacement.value)
            } else {
                stopForegroundNow(removeNotification = true)
                foregroundId = 0
                stopWakeLockRenewal()
            }
        }
        if (activeNotifications.isEmpty()) stopSelf(startId)
        ModuleAndroidLog.legacy(
            LOG_TAG,
            "module service notification cancelled id=$id active=${activeNotifications.size}",
        )
    }

    private fun startWakeLockRenewal() {
        wakeLockHandler.removeCallbacks(wakeLockRenewal)
        wakeLockHandler.removeCallbacks(hostHeartbeat)
        acquireOrRenewWakeLock()
        sendHostHeartbeat()
        wakeLockHandler.postDelayed(wakeLockRenewal, WAKE_LOCK_RENEW_INTERVAL_MS)
        wakeLockHandler.postDelayed(hostHeartbeat, HOST_HEARTBEAT_INTERVAL_MS)
    }

    private fun acquireOrRenewWakeLock() {
        val lock = wakeLock ?: (
            (getSystemService(POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?.also {
                    it.setReferenceCounted(false)
                    wakeLock = it
                }
                ?: return
            )
        runCatching {
            if (lock.isHeld) lock.release()
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }.onFailure {
            ModuleAndroidLog.legacy(LOG_TAG, "module download wake lock acquire failed", it)
        }
    }

    private fun sendHostHeartbeat() {
        val key = activeNotificationKeys[foregroundId].orEmpty()
        HOST_PACKAGES.forEach { packageName ->
            runCatching {
                val heartbeat = Intent(ACTION_ONLINE_COMPLETION_HEARTBEAT).apply {
                    setPackage(packageName)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    putExtra(EXTRA_ID, foregroundId)
                    putExtra(EXTRA_KEY, key)
                }
                sendBroadcast(heartbeat)
            }.onFailure {
                ModuleAndroidLog.legacy(LOG_TAG, "module download heartbeat failed package=$packageName", it)
            }
        }
    }

    private fun stopWakeLockRenewal() {
        wakeLockHandler.removeCallbacks(wakeLockRenewal)
        wakeLockHandler.removeCallbacks(hostHeartbeat)
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }.onFailure {
            ModuleAndroidLog.legacy(LOG_TAG, "module download wake lock release failed", it)
        }
        wakeLock = null
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }.getOrElse {
                startForeground(id, notification)
            }
        } else {
            startForeground(id, notification)
        }
    }

    private fun stopForegroundNow(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(
                if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH,
            )
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    private fun buildNotification(
        id: Int,
        key: String,
        cancellable: Boolean,
        title: String,
        text: String,
        progress: Int,
        done: Boolean,
    ): Notification {
        ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ONLINE_COMPLETION_NOTIFICATION_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
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
                cancelPendingIntent(id, key),
            )
        }
        return builder.build()
    }

    private fun cancelPendingIntent(id: Int, key: String): PendingIntent {
        val intent = Intent(ACTION_ONLINE_COMPLETION_CANCEL).apply {
            setClass(this@OnlineCompletionNotificationService, OnlineCompletionNotificationReceiver::class.java)
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_KEY, key)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(this, id, intent, flags)
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
        const val ACTION_ONLINE_COMPLETION_CANCEL = "com.reamicro.fix.ONLINE_COMPLETION_CANCEL"
        const val ACTION_ONLINE_COMPLETION_HEARTBEAT = "com.reamicro.fix.ONLINE_COMPLETION_HEARTBEAT"
        const val EXTRA_ID = "id"
        const val EXTRA_KEY = "key"
        const val EXTRA_CANCELLABLE = "cancellable"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_DONE = "done"
        const val ONLINE_COMPLETION_TITLE = "\u5728\u7ebf\u8865\u5168"
        const val ONLINE_COMPLETION_NOTIFICATION_CHANNEL = "reamicro_online_completion_download"
        const val FALLBACK_NOTIFICATION_ID = 4300
        const val WAKE_LOCK_TAG = "ReaMicro:OnlineCompletion"
        const val WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1_000L
        const val WAKE_LOCK_RENEW_INTERVAL_MS = 10 * 60 * 1_000L
        const val HOST_HEARTBEAT_INTERVAL_MS = 15_000L
        val HOST_PACKAGES = arrayOf("app.zhendong.reamicro", "app.zhendong.reamicro.fix")
    }
}
