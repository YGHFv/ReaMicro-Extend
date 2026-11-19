package com.reamicro.fix.cloud.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.reamicro.fix.xposed.HookRuntime

class ApiServerSettingsMirrorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ApiServerSettingsMirror.ACTION) return
        // 广播附带的包名可以伪造；只信任系统提供的 UID，旧系统使用 Provider。
        if (Build.VERSION.SDK_INT < 34 ||
            !ApiServerBridgeAccess.trustedUid(context, runCatching { sentFromUid }.getOrDefault(-1))) {
            HookRuntime.logError("ReaMicro API settings mirror rejected unauthenticated sender")
            return
        }
        val payload = intent.getStringExtra(ApiServerSettingsMirror.EXTRA_PAYLOAD) ?: return
        val appContext = context.applicationContext
        val settings = runCatching { apiServerSettingsFromMirrorJson(payload) }.getOrElse {
            HookRuntime.log("ReaMicro API settings mirror payload invalid: ${it.message}")
            return
        }
        ApiServerSettingsStore { appContext }.save(settings)
        // 显式广播可达而 Provider 被包可见性限制时，授权后让下次同步取得保存回执。
        ApiServerBridgeAccess.grantVisibility(appContext)
        HookRuntime.log(
            "ReaMicro API settings mirror applied enabled=${settings.enabled} " +
                "baseUrl=${settings.baseUrl.isNotBlank()} hostAccountId=${settings.hostAccountId.isNotBlank()}",
        )
        val pending = goAsync()
        com.reamicro.fix.reload.ModuleResources.thread {
            try {
                CloudTaskNotificationPoller.pollBlocking(appContext, source = "settings-mirror")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
