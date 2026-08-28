package com.reamicro.fix.cloud.api

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.reamicro.fix.notification.CloudTaskNotifications
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
                val result = item.optString("result")
                if (show(appContext, id, title, message, result)) acknowledged += id
            }
            // 只回执真正投出去的消息；投递失败的留到下次在线重发，避免消息被静默吞掉。
            if (acknowledged.isNotEmpty()) client.acknowledgeNotifications(acknowledged)
            CloudTaskWakeScheduler.schedule(appContext, data.optLong("nextTaskAt", 0L))
        } catch (error: Throwable) {
            XposedBridge.log("ReaMicro API task notification poll failed: ${error.message}")
            CloudTaskWakeScheduler.schedule(appContext)
        } finally {
            running.set(false)
        }
    }

    /**
     * 投递一条云任务消息。
     *
     * 轮询多数时候跑在**阅微进程**里（前台心跳），而 `POST_NOTIFICATIONS` 是按应用授予的：
     * 在阅微进程里发通知，阅微没授权就只能退化成 Toast，授权了也会挂在阅微名下。
     * 所以这里把消息交给模块自己的组件，由模块进程用模块的权限和渠道发出——
     * 与在线补全下载通知同一套路径。三级降级：
     * 1. 已经在模块进程里（闹钟唤醒）→ 直接发；
     * 2. 广播给模块的 Receiver（常规路径）；
     * 3. 广播被后台策略拦掉 → 拉起模块的无界面 Activity，它还能主动申请通知权限；
     * 4. 全都失败 → Toast，至少让用户看到一次。
     */
    private fun show(context: Context, id: String, title: String, message: String, result: String): Boolean {
        val intent = CloudTaskNotifications.intent(id, title, message, result)
        if (context.packageName == CloudTaskNotifications.MODULE_PACKAGE_NAME) {
            if (CloudTaskNotifications.post(context, intent, source = "module-process")) return true
        } else {
            if (dispatchToModule(context, intent, id)) return true
        }
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context, "$title：$message", Toast.LENGTH_LONG).show() }
        }
        XposedBridge.log("ReaMicro API task notification fell back to toast id=$id")
        return true
    }

    /** 先广播，再用无界面 Activity 兜底。任一成功即认为已投出。 */
    private fun dispatchToModule(context: Context, intent: Intent, id: String): Boolean {
        val broadcast = runCatching {
            context.sendBroadcast(
                Intent(intent).setClassName(
                    CloudTaskNotifications.MODULE_PACKAGE_NAME,
                    CloudTaskNotifications.RECEIVER_CLASS,
                ),
            )
            true
        }.getOrElse {
            XposedBridge.log("ReaMicro API task notification broadcast failed id=$id: ${it.message}")
            false
        }
        if (broadcast) return true
        return runCatching {
            context.startActivity(
                Intent(intent)
                    .setClassName(
                        CloudTaskNotifications.MODULE_PACKAGE_NAME,
                        CloudTaskNotifications.ACTIVITY_CLASS,
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            true
        }.getOrElse {
            XposedBridge.log("ReaMicro API task notification activity failed id=$id: ${it.message}")
            false
        }
    }

    private const val MIN_POLL_INTERVAL_MS = 30_000L
}

/** 使用系统闹钟在零点和下一次云任务完成附近静默唤醒模块。 */
object CloudTaskWakeScheduler {
    fun schedule(context: Context, nextTaskAt: Long = 0L) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        // 必须显式指定模块包名。这个方法也会在阅微进程里被调用（前台心跳后重排闹钟），
        // 那时 Intent(context, CloudTaskHeartbeatReceiver::class.java) 会解析成
        // 阅微包名 + 模块类名——阅微的 manifest 里没有这个组件，闹钟永远投递不到。
        val intent = Intent(ACTION_WAKE).setClassName(
            CloudTaskNotifications.MODULE_PACKAGE_NAME,
            HEARTBEAT_RECEIVER_CLASS,
        )
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
    private const val HEARTBEAT_RECEIVER_CLASS = "com.reamicro.fix.cloud.api.CloudTaskHeartbeatReceiver"
    internal val heartbeatReceiverClassForTest: String get() = HEARTBEAT_RECEIVER_CLASS
    private const val REQUEST_CODE = 260827
    private const val TASK_FINISH_GRACE_MS = 2 * 60_000L
}
