package com.reamicro.fix.cloud.api

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Binder
import com.reamicro.fix.xposed.HookRuntime

class ApiServerSettingsBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val appContext = context?.applicationContext ?: return null
        val caller = callingPackage
        if (caller !in ALLOWED_CALLERS || !ApiServerBridgeAccess.trustedUid(appContext, Binder.getCallingUid())) {
            HookRuntime.log("ReaMicro API settings mirror rejected caller=$caller method=$method")
            return Bundle().apply { putBoolean("saved", false) }
        }
        if (method != METHOD_SAVE) return Bundle().apply { putBoolean("saved", false) }
        val settings = extras?.toSettings() ?: return null
        val identity = Binder.clearCallingIdentity()
        try {
            val store = ApiServerSettingsStore { appContext }
            store.save(settings)
            val persisted = store.get()
            // 回执必须核对完整配置，避免加解密失败仍被视为同步成功。
            val saved = persisted == settings.copy(
                baseUrl = settings.baseUrl.trim().removeSuffix("/"),
                accountName = settings.accountName.trim(),
                hostAccountId = settings.hostAccountId.trim(),
                timeoutSeconds = settings.timeoutSeconds.coerceIn(5, 60),
            )
            HookRuntime.log(
                "ReaMicro API settings mirror caller=$caller saved=$saved enabled=${persisted.enabled} " +
                    "baseUrl=${persisted.baseUrl.isNotBlank()}",
            )
            return Bundle().apply { putBoolean("saved", saved) }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
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
        private val ALLOWED_CALLERS = com.reamicro.fix.core.HostPackages.all + "com.reamicro.fix"
    }
}

fun ApiServerSettings.mirrorToModule(context: android.content.Context): Boolean {
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
    val saved = runCatching {
        context.contentResolver.call(ApiServerSettingsBridgeProvider.URI, ApiServerSettingsBridgeProvider.METHOD_SAVE, null, bundle)
            ?.getBoolean("saved", false) == true
    }.onFailure {

        HookRuntime.log("ReaMicro API settings provider mirror unavailable: ${it.message}")
    }.getOrDefault(false)
    // 广播发送成功不代表配置已保存；未取得回执时，下次宿主恢复继续核对。
    if (!saved) ApiServerSettingsMirror.push(context, this)
    return saved
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
