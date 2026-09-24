package com.reamicro.fix.cloud.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reamicro.fix.cloud.api.CloudTaskWakeScheduler
import com.reamicro.fix.logging.ModuleLogBuffer
import com.reamicro.fix.xposed.XposedBridge
import org.json.JSONObject

/**
 * 模块进程侧：接收宿主下发的本地任务配置。
 *
 * 写入模块自己的 [LocalTaskStore]（token 用模块的 Keystore 重新加密），随后重排闹钟。
 * 只有宿主显式要求时（[LocalTaskMirror.EXTRA_RUN_DUE]，用户打开阅微的那一刻）才顺带跑一遍
 * 到期任务——配置刚保存就抢跑会和模块自己的执行撞车，实机表现为阅微报「操作过于频繁」。
 */
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
        val runDue = intent.getBooleanExtra(LocalTaskMirror.EXTRA_RUN_DUE, false)
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
        XposedBridge.log("ReaMicro local task mirror applied accounts=${accounts.length()} runDue=$runDue")
        val pending = goAsync()
        Thread {
            try {
                // 配置变了必须重排闹钟，否则新启用的任务不会被唤醒执行。
                CloudTaskWakeScheduler.schedule(appContext)
                if (runDue) {
                    runCatching { LocalTaskJobService.enqueue(appContext, "local-task-mirror") }
                        .onFailure { XposedBridge.log("ReaMicro local task run failed: ${it.message}") }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
