package com.reamicro.fix.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

internal object OnlineCompletionNotificationActions {
    const val PAUSE = "com.reamicro.fix.ONLINE_COMPLETION_PAUSE"
    const val CANCEL = "com.reamicro.fix.ONLINE_COMPLETION_CANCEL"
    const val DISMISS_ONLY = "dismiss_only"

    fun addTo(builder: Notification.Builder, context: Context, id: Int, key: String) {
        builder.addAction(android.R.drawable.ic_media_pause, "暂停",
            pendingIntent(context, id, key, PAUSE))
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消",
            pendingIntent(context, id, key, CANCEL))
    }

    private fun pendingIntent(context: Context, id: Int, key: String, action: String): PendingIntent =
        PendingIntent.getBroadcast(context, id, Intent(action).apply {
            setClass(context, OnlineCompletionNotificationReceiver::class.java)
            putExtra("id", id)
            putExtra("key", key)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
