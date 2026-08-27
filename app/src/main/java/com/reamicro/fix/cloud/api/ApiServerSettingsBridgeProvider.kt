package com.reamicro.fix.cloud.api

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/** 把宿主进程中的 API 配置镜像到模块进程，供系统闹钟静默唤醒时读取。 */
class ApiServerSettingsBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_SAVE || callingPackage !in ALLOWED_CALLERS) return null
        val settings = extras?.toSettings() ?: return null
        ApiServerSettingsStore { context?.applicationContext }.save(settings)
        CloudTaskWakeScheduler.schedule(context?.applicationContext ?: return null)
        return Bundle().apply { putBoolean("saved", true) }
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

fun ApiServerSettings.mirrorToModule(context: android.content.Context) {
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
    }
    runCatching { context.contentResolver.call(ApiServerSettingsBridgeProvider.URI, ApiServerSettingsBridgeProvider.METHOD_SAVE, null, bundle) }
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
)
