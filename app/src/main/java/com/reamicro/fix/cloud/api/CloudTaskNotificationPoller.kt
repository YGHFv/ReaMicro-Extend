package com.reamicro.fix.cloud.api

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

/** 阅微进入前台时拉取服务器积压的云任务消息，显示成功后再回执。 */
object CloudTaskNotificationPoller {
    private val running = AtomicBoolean(false)
    @Volatile private var lastPollAt = 0L

    fun poll(context: Context) {
        val appContext = context.applicationContext
        val store = ApiServerSettingsStore { appContext }
        if (!store.get().enabled) return
        val now = System.currentTimeMillis()
        if (now - lastPollAt < MIN_POLL_INTERVAL_MS || !running.compareAndSet(false, true)) return
        lastPollAt = now
        Thread {
            try {
                val client = ApiServerClient(store)
                val root = client.listNotifications()
                val data = root.optJSONObject("data") ?: root
                val items = data.optJSONArray("items") ?: return@Thread
                val acknowledged = mutableListOf<String>()
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    val title = item.optString("title").ifBlank { "云端任务消息" }
                    val message = item.optString("message").ifBlank { "任务状态已更新" }
                    if (show(appContext, id, title, message)) acknowledged += id
                }
                if (acknowledged.isNotEmpty()) client.acknowledgeNotifications(acknowledged)
            } catch (error: Throwable) {
                XposedBridge.log("ReaMicro API task notification poll failed: ${error.message}")
            } finally {
                running.set(false)
            }
        }.start()
    }

    private fun show(context: Context, id: String, title: String, message: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Handler(Looper.getMainLooper()).post { Toast.makeText(context, "$title：$message", Toast.LENGTH_LONG).show() }
            return true
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "云端任务消息", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(android.app.Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        manager.notify(id.hashCode(), notification)
        return true
    }

    private const val CHANNEL_ID = "reamicro_cloud_tasks"
    private const val MIN_POLL_INTERVAL_MS = 30_000L
}
