package com.reamicro.fix.cloud.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reamicro.fix.xposed.XposedBridge

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
                CloudTaskNotificationPoller.pollBlocking(appContext, source = "settings-mirror")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
