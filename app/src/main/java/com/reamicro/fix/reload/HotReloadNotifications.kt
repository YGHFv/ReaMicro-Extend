package com.reamicro.fix.reload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** 系统禁止后台拉起时，让用户通过通知显式返回；不申请后台启动特权。 */
internal object HotReloadNotifications {
    private const val CHANNEL = "module_hot_reload"
    private const val ID = 10273

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    fun show(context: Context, pkg: String, message: String, finished: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "模块热重载", NotificationManager.IMPORTANCE_DEFAULT))
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val action = launch?.let {
            PendingIntent.getActivity(context, ID, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (finished) "阅微热重载结果" else "阅微模块正在热重载")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setOngoing(!finished)
            .setAutoCancel(finished)
            .setOnlyAlertOnce(true)
        if (finished && action != null) notification.setContentIntent(action)
            .addAction(Notification.Action.Builder(null, "返回阅微", action).build())
        runCatching { manager.notify(ID, notification.build()) }
        if (finished) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
