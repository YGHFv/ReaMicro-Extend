package com.reamicro.fix.cloud.api

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.reamicro.fix.notification.CloudTaskNotifications
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 阅微进入前台时拉取服务器积压的云任务消息，显示成功后再回执。 */
object CloudTaskNotificationPoller {
    private val running = AtomicBoolean(false)
    @Volatile private var lastPollAt = 0L

    fun poll(context: Context, source: String = "foreground", force: Boolean = false) {
        val appContext = context.applicationContext
        val store = ApiServerSettingsStore { appContext }
        if (!store.get().enabled) {
            // API 服务器没启用也要排闹钟：本地自动任务完全不依赖服务器，它们同样需要被唤醒。
            // 此前这里直接 return，导致只用本地任务的用户永远排不上闹钟、任务从不自启。
            CloudTaskWakeScheduler.schedule(appContext)
            return
        }
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
        val settings = store.get()
        XposedBridge.log(
            "ReaMicro API wake source=$source enabled=${settings.enabled} " +
                "baseUrl=${settings.baseUrl.isNotBlank()} auth=${settings.authMode.wireValue} " +
                "hostAccountId=${settings.hostAccountId.isNotBlank()}",
        )
        if (!settings.enabled || settings.baseUrl.isBlank() || !running.compareAndSet(false, true)) {
            if (!settings.enabled || settings.baseUrl.isBlank()) CloudTaskWakeScheduler.schedule(appContext)
            return
        }
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
                val resultItems = item.optJSONArray("items")?.toString().orEmpty()
                if (show(appContext, id, title, message, result, resultItems)) acknowledged += id
            }
            // 只回执**确认发出**的消息；发不出去的留到下次在线重发，避免消息被静默吞掉。
            // show() 只有在模块进程回报「已发出」时才返回 true（见 confirmModuleDelivery）。
            if (acknowledged.isNotEmpty()) client.acknowledgeNotifications(acknowledged)
            CloudTaskWakeScheduler.schedule(appContext, data.optLong("nextTaskAt", 0L))
        } catch (error: Throwable) {
            // 网络类失败是常态而非故障：用户可能没网，或自建服务器暂时不可达（DNS 解析不了、
            // 连接被中断）。这类失败会自愈，不能每次轮询都打一条 error 把日志刷满，
            // 因此限流成最多每 30 分钟一条提示。
            if (isTransientNetworkFailure(error)) {
                logNetworkFailureThrottled(error)
            } else {
                XposedBridge.log("ReaMicro API task notification poll failed: ${error.message}")
            }
            CloudTaskWakeScheduler.schedule(appContext)
        } finally {
            running.set(false)
        }
    }

    /**
     * 投递一条云任务消息，返回**是否已经确认投出**（只有确认了才允许回执给服务器）。
     *
     * 轮询多数时候跑在**阅微进程**里（前台心跳），而 `POST_NOTIFICATIONS` 是按应用授予的：
     * 在阅微进程里发通知，阅微没授权就只能退化成 Toast，授权了也会挂在阅微名下。
     * 所以这里把消息交给模块自己的组件，由模块进程用模块的权限和渠道发出——
     * 与在线补全下载通知同一套路径。三级降级：
     * 1. 已经在模块进程里（闹钟唤醒）→ 直接发，发出即确认；
     * 2. 有序广播给模块的 Receiver，用回执确认它**真的发了**；
     * 3. 没被确认（模块进程被冻结/没起来）→ 拉起模块的无界面 Activity 再试一次 + Toast，
     *    但**不确认、不回执**，让服务器保持未投递、下次轮询重发。
     */
    private fun show(
        context: Context,
        id: String,
        title: String,
        message: String,
        result: String,
        resultItems: String,
    ): Boolean {
        val intent = CloudTaskNotifications.intent(id, title, message, result, resultItems)
        if (context.packageName == CloudTaskNotifications.MODULE_PACKAGE_NAME) {
            return CloudTaskNotifications.post(context, intent, source = "module-process")
        }
        if (confirmModuleDelivery(context, intent, id)) return true
        // 无界面 Activity 同样跑在模块进程里，是进程冷启动时还能把消息投出去的一条路；
        // 但它是异步拉起的，无法同步确认，所以这里只当兜底、不当作已投递。
        startModuleActivity(context, intent, id)
        // 前台心跳触发的轮询就发生在阅微前台的时刻，此时提示条用户是能看到的。
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context, "$title：$message", Toast.LENGTH_LONG).show() }
        }
        XposedBridge.log("ReaMicro API task notification unconfirmed (module not reachable) id=$id")
        return false
    }

    /**
     * 有序广播给模块进程，等它**真正发出通知**后回报结果。
     *
     * 这是「服务器显示已发送、设备上没有」的根治点：此前只看 `sendBroadcast` 有没有抛异常，
     * 而模块被系统冻结时广播压根不会执行，异常却不会有——于是消息被乐观回执掉，永久消失。
     * 改成等回执后，发不出去的消息会留在服务器上，下次轮询重发。
     */
    private fun confirmModuleDelivery(context: Context, intent: Intent, id: String): Boolean {
        val latch = CountDownLatch(1)
        val confirmed = AtomicBoolean(false)
        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, resultIntent: Intent?) {
                // resultCode 由 CloudTaskNotificationReceiver 在 post 成功后置为 RESULT_OK。
                confirmed.set(resultCode == Activity.RESULT_OK)
                latch.countDown()
            }
        }
        return runCatching {
            context.sendOrderedBroadcast(
                Intent(intent).setClassName(
                    CloudTaskNotifications.MODULE_PACKAGE_NAME,
                    CloudTaskNotifications.RECEIVER_CLASS,
                ),
                null,
                resultReceiver,
                null,
                Activity.RESULT_CANCELED,
                null,
                null,
            )
            // 超时按「未投出」处理：晚到的回执不再影响本次判定（confirmed 是本次调用私有的）。
            latch.await(MODULE_CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS) && confirmed.get()
        }.getOrElse {
            XposedBridge.log("ReaMicro API task notification broadcast failed id=$id: ${it.message}")
            false
        }
    }

    /** 广播被后台策略拦掉时的兜底：拉起模块的无界面 Activity。 */
    private fun startModuleActivity(context: Context, intent: Intent, id: String): Boolean =
        runCatching {
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

    private const val MIN_POLL_INTERVAL_MS = 30_000L

    /** 等模块进程回执的时长。模块冷启动要拉起进程，给足时间但别把轮询线程挂太久。 */
    private const val MODULE_CONFIRM_TIMEOUT_MS = 8_000L

    /**
     * 是否属于可自愈的网络类失败（DNS 解析失败、连接中断、超时、无路由）。
     *
     * 这类失败在自建服务器或移动网络下很常见，重试即可，不该按故障记录。
     */
    private fun isTransientNetworkFailure(error: Throwable): Boolean {
        if (error is java.net.UnknownHostException ||
            error is java.net.ConnectException ||
            error is java.net.SocketTimeoutException ||
            error is java.net.NoRouteToHostException ||
            error is java.net.SocketException
        ) {
            return true
        }
        val message = error.message.orEmpty()
        return TRANSIENT_NETWORK_MESSAGES.any { it in message }
    }

    private fun logNetworkFailureThrottled(error: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastNetworkFailureLogAtMs < NETWORK_FAILURE_LOG_INTERVAL_MS) return
        lastNetworkFailureLogAtMs = now
        XposedBridge.log("ReaMicro API server unreachable (will keep retrying): ${error.message}")
    }

    @Volatile private var lastNetworkFailureLogAtMs = 0L
    private const val NETWORK_FAILURE_LOG_INTERVAL_MS = 30 * 60_000L
    private val TRANSIENT_NETWORK_MESSAGES = listOf(
        "No address associated with hostname",
        "Unable to resolve host",
        "Software caused connection abort",
        "Connection reset",
        "Connection refused",
        "timed out",
        "timeout",
    )
}

/** 使用系统闹钟在零点和下一次云任务完成附近静默唤醒模块。 */
object CloudTaskWakeScheduler {
    fun schedule(context: Context, nextTaskAt: Long = 0L) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            wakeIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val now = System.currentTimeMillis()
        val calendar = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val taskWake = nextTaskAt.takeIf { it > now }?.plus(TASK_FINISH_GRACE_MS) ?: Long.MAX_VALUE
        // 本地自动任务（含行商 endTime）也纳入排程，让行商完成时刻能较准时唤醒。
        val localWake = runCatching {
            com.reamicro.fix.cloud.local.LocalTaskStore { context.applicationContext }.earliestNextRunAt()
        }.getOrDefault(0L).takeIf { it > now } ?: Long.MAX_VALUE
        // 服务器没有任务时间、网络失败或系统错过闹钟时，固定短周期保证模块仍能自行恢复。
        val fallbackWake = now + FALLBACK_POLL_INTERVAL_MS
        val triggerAt = minOf(calendar.timeInMillis, taskWake, localWake, fallbackWake)
        // 把这次排出来的时刻告诉 root 看门狗，让它按任务时刻唤醒而不是固定周期空转。
        NextWakeHint.write(context, triggerAt)
        val exact = canScheduleExact(alarm)
        runCatching {
            if (exact) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                // AlarmClock 属于系统允许的用户可见闹钟，即使没有 SCHEDULE_EXACT_ALARM
                // 也能在 Doze 中准时唤醒；比 setAndAllowWhileIdle 更适合自动任务。
                alarm.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pending), pending)
            }
        }.onFailure {
            runCatching { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending) }
        }
        XposedBridge.log(
            "ReaMicro API cloud task alarm scheduled exact=$exact at=$triggerAt " +
                "(in ${(triggerAt - now) / 60_000} min)",
        )
    }

    /**
     * 闹钟唤醒用的 Intent。
     *
     * 两个都不能少：
     * - **显式指定模块包名**。这个方法也会在阅微进程里被调用（前台心跳后重排闹钟），那时
     *   `Intent(context, CloudTaskHeartbeatReceiver::class.java)` 会解析成「阅微包名 + 模块类名」，
     *   而阅微的 manifest 里没有这个组件，闹钟永远投递不到。
     * - **FLAG_INCLUDE_STOPPED_PACKAGES**。模块 App 没有 launcher activity，装完可能长期处于
     *   stopped 状态，而 stopped 应用的 manifest receiver 收不到不带该标志的广播——闹钟就白排了。
     *   同仓 `LocalTaskMirror.push` / `CloudTaskNotifications.intent` 都带了，只有这里漏过。
     */
    internal fun wakeIntent(): Intent = Intent(ACTION_WAKE)
        .setClassName(CloudTaskNotifications.MODULE_PACKAGE_NAME, HEARTBEAT_RECEIVER_CLASS)
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

    /** Android 12+ 未获精确闹钟授权时只能用非精确闹钟。 */
    fun canScheduleExact(alarm: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < 31 || runCatching { alarm.canScheduleExactAlarms() }.getOrDefault(false)

    fun canScheduleExact(context: Context): Boolean =
        context.getSystemService(AlarmManager::class.java)?.let(::canScheduleExact) ?: false

    const val ACTION_WAKE = "com.reamicro.fix.CLOUD_TASK_HEARTBEAT"
    private const val HEARTBEAT_RECEIVER_CLASS = "com.reamicro.fix.cloud.api.CloudTaskHeartbeatReceiver"
    internal val heartbeatReceiverClassForTest: String get() = HEARTBEAT_RECEIVER_CLASS
    private const val REQUEST_CODE = 260827
    private const val TASK_FINISH_GRACE_MS = 2 * 60_000L
    private const val FALLBACK_POLL_INTERVAL_MS = 15 * 60_000L
}
