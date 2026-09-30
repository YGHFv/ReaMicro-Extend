package com.reamicro.fix.cloud.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reamicro.fix.logging.ModuleLogBuffer
import com.reamicro.fix.xposed.XposedBridge
import org.json.JSONObject

/** Receives configuration only. Store synchronization notifies Root only when ROOT enhancement is enabled. */
class LocalTaskMirrorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LocalTaskMirror.ACTION) return
        val appContext = context.applicationContext
        // 绑定日志落盘位置：模块进程没有界面，这些接收器是最早、也往往是唯一拿到 Context 的地方。
        ModuleLogBuffer.attach(appContext)
        val payload = runCatching {
            JSONObject(intent.getStringExtra(LocalTaskMirror.EXTRA_PAYLOAD) ?: "")
        }.getOrElse {
            XposedBridge.log("ReaMicro local task mirror payload invalid: ${it.message}")
            return
        }
        val accounts = payload.optJSONObject(LocalTaskMirror.PAYLOAD_ACCOUNTS) ?: return
        val store = LocalTaskStore { appContext }
        accounts.keys().forEach { accountId ->
            val entry = accounts.optJSONObject(accountId) ?: return@forEach
            store.applyMirror(
                accountId,
                entry.optJSONObject(LocalTaskStore.KEY_TASKS) ?: JSONObject(),
                entry.optString(LocalTaskStore.KEY_TOKEN),
                entry.optLong("recordsClearedAt"),
            )
        }
        XposedBridge.log("ReaMicro local task mirror applied accounts=${accounts.length()}")
    }
}
