package com.reamicro.fix.cloud.api

import android.content.Context
import android.content.Intent
import com.reamicro.fix.xposed.XposedBridge
import org.json.JSONObject

object ApiServerSettingsMirror {
    const val ACTION = "com.reamicro.fix.API_SETTINGS_MIRROR"
    const val EXTRA_PAYLOAD = "payload"
    const val MODULE_PACKAGE = "com.reamicro.fix"
    const val RECEIVER_CLASS = "com.reamicro.fix.cloud.api.ApiServerSettingsMirrorReceiver"

    fun push(context: Context, settings: ApiServerSettings): Boolean {
        val appContext = context.applicationContext

        val payload = settings.toMirrorJson()
        return runCatching {
            appContext.sendBroadcast(
                Intent(ACTION)
                    .setClassName(MODULE_PACKAGE, RECEIVER_CLASS)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(EXTRA_PAYLOAD, payload),
            )
            true
        }.onFailure {
            XposedBridge.log("ReaMicro API settings mirror broadcast failed: ${it.message}")
        }.getOrDefault(false)
    }
}

internal fun ApiServerSettings.toMirrorJson(): String = JSONObject()
    .put("enabled", enabled)
    .put("baseUrl", baseUrl)
    .put("authMode", authMode.wireValue)
    .put("apiKey", apiKey)
    .put("accountName", accountName)
    .put("accountPassword", accountPassword)
    .put("hostAccountId", hostAccountId)
    .put("allowHttp", allowHttp)
    .put("timeoutSeconds", timeoutSeconds)
    .put("autoCheckUpdates", autoCheckUpdates)
    .put("updateChannel", updateChannel.wireValue)
    .toString()

internal fun apiServerSettingsFromMirrorJson(raw: String): ApiServerSettings {
    val json = JSONObject(raw)
    return ApiServerSettings(
        enabled = json.optBoolean("enabled", false),
        baseUrl = json.optString("baseUrl"),
        authMode = ApiAuthMode.fromWireValue(json.optString("authMode")),
        apiKey = json.optString("apiKey"),
        accountName = json.optString("accountName"),
        accountPassword = json.optString("accountPassword"),
        hostAccountId = json.optString("hostAccountId"),
        allowHttp = json.optBoolean("allowHttp", false),
        timeoutSeconds = json.optInt("timeoutSeconds", 8).coerceIn(5, 60),
        autoCheckUpdates = json.optBoolean("autoCheckUpdates", true),
        updateChannel = ApiUpdateChannel.fromWireValue(json.optString("updateChannel")),
    )
}
