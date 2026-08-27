package com.reamicro.fix.cloud.api

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

    fun poll(context: Context, source: String = "foreground", force: Boolean = false) {
        val appContext = context.applicationContext
        val store = ApiServerSettingsStore { appContext }
        if (!store.get().enabled) return
        val now = System.currentTimeMillis()
        if ((!force && now - lastPollAt < MIN_POLL_INTERVAL_MS) || !running.compareAndSet(false, true)) return
        lastPollAt = now
        Thread {
            perform(appContext, store, source)
        }.start()
    }

    fun pollBlocking(context: Context, source: String = "alarm") {
        val appContext = context.applicationContext
        val store = ApiServerSettingsStore { appContext }
        if (!store.get().enabled || !running.compareAndSet(false, true)) return
        lastPollAt = System.currentTimeMillis()
        perform(appContext, store, source)
    }

    private fun perform(appContext: Context, store: ApiServerSettingsStore, source: String) {
        try {
            val client = ApiServerClient(store)
            val heartbeat = client.heartbeat(source)
            val data = heartbeat.optJSONObject("data") ?: heartbeat
            val items = data.optJSONArray("notifications") ?: org.json.JSONArray()
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
            CloudTaskWakeScheduler.schedule(appContext, data.optLong("nextTaskAt", 0L))
        } catch (error: Throwable) {
            XposedBridge.log("ReaMicro API task notification poll failed: ${error.message}")
            CloudTaskWakeScheduler.schedule(appContext)
        } finally {
            running.set(false)
        }
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

/** 使用系统闹钟在零点和下一次云任务完成附近静默唤醒模块。 */
object CloudTaskWakeScheduler {
    fun schedule(context: Context, nextTaskAt: Long = 0L) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, CloudTaskHeartbeatReceiver::class.java).setAction(ACTION_WAKE)
        val pending = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val now = System.currentTimeMillis()
        val calendar = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val taskWake = nextTaskAt.takeIf { it > now }?.plus(TASK_FINISH_GRACE_MS) ?: Long.MAX_VALUE
        val triggerAt = minOf(calendar.timeInMillis, taskWake)
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }.onFailure {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    const val ACTION_WAKE = "com.reamicro.fix.CLOUD_TASK_HEARTBEAT"
    private const val REQUEST_CODE = 260827
    private const val TASK_FINISH_GRACE_MS = 2 * 60_000L
}
