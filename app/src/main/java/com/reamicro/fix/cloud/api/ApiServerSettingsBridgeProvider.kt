package com.reamicro.fix.cloud.api

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Binder
import com.reamicro.fix.cloud.local.LocalTaskBridgeRuntime
import com.reamicro.fix.xposed.XposedBridge

class ApiServerSettingsBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val appContext = context?.applicationContext ?: return null
        val caller = callingPackage
        if (caller !in ALLOWED_CALLERS || !LocalTaskBridgeRuntime.trustedUid(appContext, Binder.getCallingUid())) {
            XposedBridge.log("ReaMicro API settings mirror rejected caller=$caller method=$method")
            return Bundle().apply { putBoolean("saved", false) }
        }
        if (method == LocalTaskBridgeRuntime.PROVIDER_EXCHANGE) {
            val identity = Binder.clearCallingIdentity()
            try {
                return runCatching {
                    val request = LocalTaskBridgeRuntime.request(extras ?: Bundle())
                    LocalTaskBridgeRuntime.replyBundle(LocalTaskBridgeRuntime.execute(appContext, request))
                }.getOrElse {
                    Bundle().apply { putString(LocalTaskBridgeRuntime.ERROR, "processing_failed") }
                }
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
        if (method != METHOD_SAVE) return Bundle().apply { putBoolean("saved", false) }
        val settings = extras?.toSettings() ?: return null
        ApiServerSettingsStore { appContext }.save(settings)
        val persisted = ApiServerSettingsStore { appContext }.get()
        val saved = persisted.enabled == settings.enabled &&
            persisted.baseUrl == settings.baseUrl.trim().removeSuffix("/") &&
            persisted.hostAccountId == settings.hostAccountId.trim()
        XposedBridge.log(
            "ReaMicro API settings mirror caller=$caller saved=$saved enabled=${persisted.enabled} " +
                "baseUrl=${persisted.baseUrl.isNotBlank()}",
        )
        return Bundle().apply { putBoolean("saved", saved) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.reamicro.fix.api-settings"
        const val METHOD_SAVE = "save"
        val URI: Uri = Uri.parse("content://$AUTHORITY")
        private val ALLOWED_CALLERS = setOf("app.zhendong.reamicro", "app.zhendong.reamicro.fix", "com.reamicro.fix")
    }
}

fun ApiServerSettings.mirrorToModule(context: android.content.Context): Boolean {
    val pushed = ApiServerSettingsMirror.push(context, this)
    if (pushed) return true
    val bundle = Bundle().apply {
        putBoolean("enabled", enabled)
        putString("baseUrl", baseUrl)
        putString("authMode", authMode.wireValue)
        putString("apiKey", apiKey)
        putString("accountName", accountName)
        putString("accountPassword", accountPassword)
        putString("hostAccountId", hostAccountId)
        putBoolean("allowHttp", allowHttp)
        putInt("timeoutSeconds", timeoutSeconds)
        putBoolean("autoCheckUpdates", autoCheckUpdates)
        putString("updateChannel", updateChannel.wireValue)
    }
    return runCatching {
        context.contentResolver.call(ApiServerSettingsBridgeProvider.URI, ApiServerSettingsBridgeProvider.METHOD_SAVE, null, bundle)
            ?.getBoolean("saved", false) == true
    }.onFailure {

        XposedBridge.log("ReaMicro API settings provider mirror unavailable: ${it.message}")
    }.getOrDefault(false)
}

private fun Bundle.toSettings(): ApiServerSettings = ApiServerSettings(
    enabled = getBoolean("enabled"),
    baseUrl = getString("baseUrl").orEmpty(),
    authMode = ApiAuthMode.fromWireValue(getString("authMode")),
    apiKey = getString("apiKey").orEmpty(),
    accountName = getString("accountName").orEmpty(),
    accountPassword = getString("accountPassword").orEmpty(),
    hostAccountId = getString("hostAccountId").orEmpty(),
    allowHttp = getBoolean("allowHttp"),
    timeoutSeconds = getInt("timeoutSeconds", 8),
    autoCheckUpdates = getBoolean("autoCheckUpdates", true),
    updateChannel = ApiUpdateChannel.fromWireValue(getString("updateChannel")),
)
