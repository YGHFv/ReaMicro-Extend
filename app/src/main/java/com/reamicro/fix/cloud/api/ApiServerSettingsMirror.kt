package com.reamicro.fix.cloud.api

import android.content.Context
import android.content.Intent
import com.reamicro.fix.xposed.XposedBridge
import org.json.JSONObject

/**
 * API 服务器配置的「宿主进程 → 模块进程」镜像。
 *
 * 为什么必须镜像：闹钟唤醒后跑在**模块进程**，它要用 baseUrl / 凭据去服务器拉消息并回执；
 * 而两个进程的 SharedPreferences 与 Android Keystore 都按应用（UID）隔离——宿主写下的配置
 * 模块读不到，宿主加密的 API Key / 密码模块也解不开（Keystore 密钥按 UID 生成）。所以每次
 * 配置变更都要显式下发一次，由模块用自己的密钥重新加密落盘。
 *
 * 为什么用广播而不是 ContentProvider：模块 App 没有 launcher activity，装完可能从未被启动过，
 * 一直处于 stopped 状态，而 **stopped 应用的 provider 无法被解析**，调用方拿到的是
 * `Unknown authority`（实测宿主侧报 `Can't resolve content provider com.reamicro.fix.api-settings`，
 * 于是模块进程的配置一直是空的、模块侧轮询与回执从来没跑起来过）。带
 * [Intent.FLAG_INCLUDE_STOPPED_PACKAGES] 的广播则可以投递给 stopped 应用，并顺带解除该状态。
 * 本地任务（[com.reamicro.fix.cloud.local.LocalTaskMirror]）与通知已走这条路，本类补齐 API 配置。
 *
 * 载荷里带的是**明文**凭据：模块进程只能用自己 UID 的 Keystore 重新加密，收到密文没有意义。
 * 广播是显式指定组件的，只有模块自己的接收器会收到。
 */
object ApiServerSettingsMirror {
    const val ACTION = "com.reamicro.fix.API_SETTINGS_MIRROR"
    const val EXTRA_PAYLOAD = "payload"
    const val MODULE_PACKAGE = "com.reamicro.fix"
    const val RECEIVER_CLASS = "com.reamicro.fix.cloud.api.ApiServerSettingsMirrorReceiver"

    /** 把 API 配置同步给模块进程。返回是否成功投出。 */
    fun push(context: Context, settings: ApiServerSettings): Boolean {
        val appContext = context.applicationContext
        // 即便配置是「未启用」也要投：模块需要收到这个变化才会停手。
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

/** 序列化成镜像载荷。凭据以明文出现，见 [ApiServerSettingsMirror] 的说明。 */
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

/** 从镜像载荷还原。缺字段一律走 [ApiServerSettings] 的默认值，便于新旧版本互不阻塞。 */
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
