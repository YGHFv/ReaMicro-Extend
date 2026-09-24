package com.reamicro.fix.cloud.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reamicro.fix.xposed.XposedBridge

/**
 * 模块进程侧：接收宿主下发的 API 服务器配置。
 *
 * 用模块自己的 Keystore 重新加密落盘（宿主加密的凭据模块解不开），随后立刻重排闹钟并拉一次
 * 消息——宿主刚下发配置通常意味着用户就在旁边改设置，此时立刻同步能马上验证配置对不对。
 */
class ApiServerSettingsMirrorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ApiServerSettingsMirror.ACTION) return
        val payload = intent.getStringExtra(ApiServerSettingsMirror.EXTRA_PAYLOAD) ?: return
        val appContext = context.applicationContext
        val settings = runCatching { apiServerSettingsFromMirrorJson(payload) }.getOrElse {
            XposedBridge.log("ReaMicro API settings mirror payload invalid: ${it.message}")
            return
        }
        ApiServerSettingsStore { appContext }.save(settings)
        XposedBridge.log(
            "ReaMicro API settings mirror applied enabled=${settings.enabled} " +
                "baseUrl=${settings.baseUrl.isNotBlank()} hostAccountId=${settings.hostAccountId.isNotBlank()}",
        )
        val pending = goAsync()
        Thread {
            try {
                CloudTaskWakeScheduler.schedule(appContext)
                CloudTaskNotificationPoller.pollBlocking(appContext, source = "settings-mirror")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
