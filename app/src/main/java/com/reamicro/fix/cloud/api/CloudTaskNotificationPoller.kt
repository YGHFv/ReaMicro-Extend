package com.reamicro.fix.cloud.api

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.reamicro.fix.notification.CloudTaskNotifications
import com.reamicro.fix.xposed.XposedBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object CloudTaskNotificationPoller {
    private val running = AtomicBoolean(false)
    @Volatile private var lastPollAt = 0L

    fun poll(context: Context, source: String = "foreground", force: Boolean = false) {
        val appContext = context.applicationContext
        val store = ApiServerSettingsStore { appContext }
        if (!store.get().enabled) {
            return
        }
        val now = System.currentTimeMillis()
        if ((!force && now - lastPollAt < MIN_POLL_INTERVAL_MS) || !running.compareAndSet(false, true)) return
        lastPollAt = now
        Thread {
            perform(appContext, store, source)
        }.start()
    }

    fun pollBlocking(context: Context, source: String = "foreground-sync") {
        val appContext = context.applicationContext
        val store = ApiServerSettingsStore { appContext }
        val settings = store.get()
        XposedBridge.log(
            "ReaMicro API wake source=$source enabled=${settings.enabled} " +
                "baseUrl=${settings.baseUrl.isNotBlank()} auth=${settings.authMode.wireValue} " +
                "hostAccountId=${settings.hostAccountId.isNotBlank()}",
        )
        if (!settings.enabled || settings.baseUrl.isBlank() || !running.compareAndSet(false, true)) {
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

            if (acknowledged.isNotEmpty()) client.acknowledgeNotifications(acknowledged)
        } catch (error: Throwable) {

            if (isTransientNetworkFailure(error)) {
                logNetworkFailureThrottled(error)
            } else {
                XposedBridge.log("ReaMicro API task notification poll failed: ${error.message}")
            }
        } finally {
            running.set(false)
        }
    }

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

        startModuleActivity(context, intent, id)

        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context, "$title：$message", Toast.LENGTH_LONG).show() }
        }
        XposedBridge.log("ReaMicro API task notification unconfirmed (module not reachable) id=$id")
        return false
    }

    private fun confirmModuleDelivery(context: Context, intent: Intent, id: String): Boolean {
        val latch = CountDownLatch(1)
        val confirmed = AtomicBoolean(false)
        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, resultIntent: Intent?) {

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

            latch.await(MODULE_CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS) && confirmed.get()
        }.getOrElse {
            XposedBridge.log("ReaMicro API task notification broadcast failed id=$id: ${it.message}")
            false
        }
    }

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

    private const val MODULE_CONFIRM_TIMEOUT_MS = 8_000L

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
