package com.reamicro.fix.notification

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.reamicro.fix.R
import com.reamicro.fix.logging.ModuleAndroidLog

internal object ModuleNotificationPermission {
    const val PREFS_NAME = "reamicro_notification_permission"
    private const val REMINDED = "reminded"
    private const val PENDING_CHANNEL = "pending_channel"

    fun canPost(context: Context, channelId: String): Boolean {
        if (context.packageName != CloudTaskNotifications.MODULE_PACKAGE_NAME) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (enabled(context, manager, channelId)) return true
        remindOnce(context, channelId)
        return false
    }

    private fun enabled(context: Context, manager: NotificationManager, channelId: String): Boolean {
        if (!CloudTaskNotifications.hasPermission(context) || !manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(channelId) ?: return true
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.P || channel.group == null ||
            manager.getNotificationChannelGroup(channel.group)?.isBlocked != true
    }

    @Synchronized
    private fun remindOnce(context: Context, channelId: String) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(REMINDED, false)) return
        // Keep the foreground prompt pending even if Android suppresses a background toast.
        prefs.edit().putBoolean(REMINDED, true).putString(PENDING_CHANNEL, channelId).apply()
        Handler(Looper.getMainLooper()).post {
            runCatching {
                Toast.makeText(app, app.getString(R.string.notification_permission_reminder,
                    app.getString(R.string.module_name)), Toast.LENGTH_LONG).show()
            }.onFailure { ModuleAndroidLog.error("ReaMicroNotify", "Notification permission reminder failed", it) }
        }
    }

    @Synchronized
    fun takePendingChannel(context: Context): String? {
        if (context.packageName != CloudTaskNotifications.MODULE_PACKAGE_NAME) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val channelId = prefs.getString(PENDING_CHANNEL, null) ?: return null
        prefs.edit().remove(PENDING_CHANNEL).apply()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        return channelId.takeUnless { enabled(context, manager, it) }
    }
}
