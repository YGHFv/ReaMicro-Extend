package com.reamicro.fix.cloud.api

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.reamicro.fix.cloud.local.LocalTaskRecord
import com.reamicro.fix.cloud.local.LocalTaskStore
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject

/** 把宿主进程中的 API 配置镜像到模块进程，供系统闹钟静默唤醒时读取。 */
class ApiServerSettingsBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val caller = callingPackage
        if (caller !in ALLOWED_CALLERS) {
            XposedBridge.log("ReaMicro API settings mirror rejected caller=$caller method=$method")
            return Bundle().apply { putBoolean("saved", false) }
        }
        val appContext = context?.applicationContext ?: return null
        if (method == METHOD_LOCAL_TASK_RECORDS) {
            // 后台（模块进程）跑出来的执行记录，供设置页与前台记录合并展示。
            // 模块处于 stopped 时本调用不可达，调用方需容忍失败。
            val accountId = extras?.getString("accountId").orEmpty()
            return Bundle().apply { putString("records", localTaskRecordsJson(appContext, accountId)) }
        }
        if (method != METHOD_SAVE) return Bundle().apply { putBoolean("saved", false) }
        val settings = extras?.toSettings() ?: return null
        ApiServerSettingsStore { appContext }.save(settings)
        val persisted = ApiServerSettingsStore { appContext }.get()
        val saved = persisted.enabled == settings.enabled &&
            persisted.baseUrl == settings.baseUrl.trim().removeSuffix("/") &&
            persisted.hostAccountId == settings.hostAccountId.trim()
        if (saved) CloudTaskWakeScheduler.schedule(appContext)
        XposedBridge.log(
            "ReaMicro API settings mirror caller=$caller saved=$saved enabled=${persisted.enabled} " +
                "baseUrl=${persisted.baseUrl.isNotBlank()}",
        )
        return Bundle().apply { putBoolean("saved", saved) }
    }

    private fun localTaskRecordsJson(context: android.content.Context, accountId: String): String {
        val array = JSONArray()
        runCatching {
            LocalTaskStore { context }.records(accountId).take(MAX_MIRRORED_RECORDS).forEach { record ->
                array.put(
                    JSONObject()
                        .put("at", record.at)
                        .put("taskType", record.taskType)
                        .put("result", record.result)
                        .put("message", record.message),
                )
            }
        }.onFailure {
            XposedBridge.log("ReaMicro local task records read failed: ${it.message}")
        }
        return array.toString()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.reamicro.fix.api-settings"
        const val METHOD_SAVE = "save"
        /** 读取模块进程侧的本地任务执行记录（后台唤醒跑出来的那些）。 */
        const val METHOD_LOCAL_TASK_RECORDS = "local-task-records"
        const val EXTRA_ACCOUNT_ID = "accountId"
        const val RESULT_RECORDS = "records"
        private const val MAX_MIRRORED_RECORDS = 100
        val URI: Uri = Uri.parse("content://$AUTHORITY")
        private val ALLOWED_CALLERS = setOf("app.zhendong.reamicro", "app.zhendong.reamicro.fix", "com.reamicro.fix")
    }
}

/**
 * 读取模块进程侧的本地任务执行记录。
 *
 * 模块 App 没有 LAUNCHER，未启动过时处于 stopped 状态，provider 不可达（调用方会拿到
 * `Unknown authority`）——这是预期内的，返回空列表即可；一旦闹钟或镜像广播启动过模块，
 * 这里就能读到后台跑出来的记录。
 */
fun readModuleLocalTaskRecords(context: android.content.Context, accountId: String): List<LocalTaskRecord> {
    if (accountId.isBlank()) return emptyList()
    val bundle = runCatching {
        context.contentResolver.call(
            ApiServerSettingsBridgeProvider.URI,
            ApiServerSettingsBridgeProvider.METHOD_LOCAL_TASK_RECORDS,
            null,
            Bundle().apply { putString(ApiServerSettingsBridgeProvider.EXTRA_ACCOUNT_ID, accountId) },
        )
    }.getOrNull() ?: return emptyList()
    val raw = bundle.getString(ApiServerSettingsBridgeProvider.RESULT_RECORDS).orEmpty()
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        LocalTaskRecord(
            at = item.optLong("at", 0L),
            taskType = item.optString("taskType"),
            result = item.optString("result"),
            message = item.optString("message"),
        )
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
    return runCatching {
        context.contentResolver.call(ApiServerSettingsBridgeProvider.URI, ApiServerSettingsBridgeProvider.METHOD_SAVE, null, bundle)
            ?.getBoolean("saved", false) == true
    }.onFailure {
        // 模块 App 没有 LAUNCHER，未启动过时处于 stopped 状态，其 provider 无法被解析，
        // 这里就会拿到 "Unknown authority"。属于预期内的降级（本地任务已改走广播镜像，
        // 不再依赖这条通道），所以只记一条提示，不当作故障。
        XposedBridge.log("ReaMicro API settings mirror unavailable (module not started yet): ${it.message}")
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
