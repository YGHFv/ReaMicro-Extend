package com.reamicro.fix.cloud.local

import android.content.Context
import android.content.Intent
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
    const val MODULE_PACKAGE = "com.reamicro.fix"
    const val RECEIVER_CLASS = "com.reamicro.fix.cloud.local.LocalTaskMirrorReceiver"
    const val PAYLOAD_ACCOUNTS = "accounts"

    /** 把本地任务配置同步给模块进程。返回是否成功投出。 */
    fun push(context: Context): Boolean {
        val appContext = context.applicationContext
        val payload = runCatching { LocalTaskStore { appContext }.mirrorPayload() }.getOrNull() ?: return false
        // 即便载荷为空也要投：用户可能把最后一个任务停用或删掉了，模块需要收到这个变化
        // 才会停手（applyMirror 是整体替换）。一次广播的开销可以忽略。
        return runCatching {
            appContext.sendBroadcast(
                Intent(ACTION)
                    .setClassName(MODULE_PACKAGE, RECEIVER_CLASS)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(EXTRA_PAYLOAD, payload.toString()),
            )
            true
        }.onFailure {
            XposedBridge.log("ReaMicro local task mirror broadcast failed: ${it.message}")
        }.getOrDefault(false)
    }
}
