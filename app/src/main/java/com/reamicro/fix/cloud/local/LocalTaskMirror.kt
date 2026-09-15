package com.reamicro.fix.cloud.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reamicro.fix.cloud.api.ApiServerSettingsBridgeProvider
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject

/**
 * 本地任务配置的「宿主进程 → 模块进程」镜像。
 *
 * 设置页跑在阅微进程，闹钟唤醒后的执行跑在模块进程。两者的 SharedPreferences 与 Android
 * Keystore 都按应用（UID）隔离：宿主写下的配置模块读不到，宿主加密的 token 模块也解不开
 * （Keystore 密钥按 UID 生成）。所以每次配置变更都要把配置显式下发一次，由模块用自己的
 * 密钥重新加密落盘。
 *
 * 为什么用广播而不是 ContentProvider：模块 App 没有 LAUNCHER activity，装完可能从未被
 * 启动过，一直处于 stopped 状态，而 **stopped 应用的 provider 无法被解析**——调用方拿到的是
 * `Unknown authority`（这正是 API 设置镜像长期失败的原因）。带
 * [Intent.FLAG_INCLUDE_STOPPED_PACKAGES] 的广播则可以投递给 stopped 应用，并顺带解除该状态。
 * 云端通知走的也是这条路径。
 */
object LocalTaskMirror {
    const val ACTION = "com.reamicro.fix.LOCAL_TASK_MIRROR"
    const val EXTRA_PAYLOAD = "payload"
    const val EXTRA_RUN_DUE = "runDue"
    const val MODULE_PACKAGE = "com.reamicro.fix"
    const val RECEIVER_CLASS = "com.reamicro.fix.cloud.local.LocalTaskMirrorReceiver"
    const val PAYLOAD_ACCOUNTS = "accounts"

    /**
     * 把本地任务配置同步给模块进程。返回是否成功投出。
     *
     * [runDue] 决定模块收到后**要不要顺带跑一遍到期任务**，默认不跑：
     * - 用户刚在设置页点保存/启用时传 false。那一刻宿主与模块可能同时在发请求，实机见过
     *   「操作过于频繁，请稍后再重试」——配置刚落地就抢跑没有意义，交给模块自己的节奏即可。
     * - 用户打开阅微时传 true（见 `ReaMicroHookEntry`）：这是用户唯一能预期「任务该跑一跑了」
     *   的时刻，由模块进程静默更新配置并执行。
     */
    fun push(context: Context, runDue: Boolean = false, onComplete: (() -> Unit)? = null): Boolean {
        val appContext = context.applicationContext
        val payload = runCatching { LocalTaskStore { appContext }.mirrorPayload() }.getOrNull() ?: return false
        return runCatching {
            appContext.sendOrderedBroadcast(
                Intent(ACTION)
                    .setClassName(MODULE_PACKAGE, RECEIVER_CLASS)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(EXTRA_PAYLOAD, payload.toString())
                    .putExtra(EXTRA_RUN_DUE, runDue),
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent?) {
                        runCatching {
                            val result = appContext.contentResolver.call(
                                ApiServerSettingsBridgeProvider.URI,
                                ApiServerSettingsBridgeProvider.METHOD_LOCAL_TASK_SNAPSHOT,
                                null, null,
                            )
                            val snapshot = result?.getString(ApiServerSettingsBridgeProvider.RESULT_SNAPSHOT)
                            if (snapshot != null) LocalTaskStore { appContext }.applySnapshot(JSONObject(snapshot))
                        }.onFailure { XposedBridge.log("ReaMicro local task snapshot unavailable: ${it.message}") }
                        onComplete?.invoke()
                    }
                },
                null, 0, null, null,
            )
            true
        }.onFailure {
            XposedBridge.log("ReaMicro local task mirror broadcast failed: ${it.message}")
        }.getOrDefault(false)
    }
}
