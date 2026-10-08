package com.reamicro.fix.cloud.local

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import com.reamicro.fix.cloud.api.ApiServerSettingsBridgeProvider
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object LocalTaskBridgeRuntime {
    const val ID = "bridge_request_id"
    const val OPERATION = "bridge_operation"
    const val ACCOUNT = "bridge_account_id"
    const val SNAPSHOT = "snapshot"
    const val ERROR = "bridge_error"
    const val LEGACY_READY = 1
    const val PROVIDER_EXCHANGE = "local-task-exchange"
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(8), { runnable -> Thread(runnable, "ReaMicroLocalTaskBridge").apply { isDaemon = true } })
        .apply { allowCoreThreadTimeOut(true) }

    fun enqueue(block: () -> Unit): Boolean = runCatching { worker.execute(block); true }.getOrDefault(false)

    fun trustedUid(context: Context, uid: Int): Boolean {
        if (uid < 0) return false
        val packages = runCatching { context.packageManager.getPackagesForUid(uid)?.toList().orEmpty() }.getOrDefault(emptyList())
        return LocalTaskBridgeProtocol.authorized(uid, Process.myUid(), packages)
    }

    fun grantLegacyVisibility(context: Context): Boolean {
        var granted = false
        val uri = ApiServerSettingsBridgeProvider.URI.buildUpon().appendPath("bridge").build()
        for (pkg in LocalTaskBridgeProtocol.allowedPackages - context.packageName) {
            runCatching {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                granted = true
            }
        }
        return granted
    }

    fun requestBundle(request: LocalTaskBridgeRequest): Bundle = Bundle().apply {
        putString(ID, request.id)
        putString(OPERATION, request.operation)
        request.payload?.let { putString(LocalTaskMirror.EXTRA_PAYLOAD, it) }
        request.accountId?.let { putString(ACCOUNT, it) }
    }

    fun request(bundle: Bundle): LocalTaskBridgeRequest = LocalTaskBridgeRequest(
        bundle.getString(ID).orEmpty(), bundle.getString(OPERATION).orEmpty(),
        bundle.getString(LocalTaskMirror.EXTRA_PAYLOAD), bundle.getString(ACCOUNT),
    )

    fun replyBundle(reply: LocalTaskBridgeReply): Bundle = Bundle().apply {
        putString(ID, reply.id)
        reply.error?.let { putString(ERROR, it) }
        reply.snapshot?.let { putString(SNAPSHOT, it) }
    }

    fun reply(bundle: Bundle?): LocalTaskBridgeReply = LocalTaskBridgeReply(
        bundle?.getString(ID).orEmpty(), bundle?.getString(SNAPSHOT), bundle?.getString(ERROR),
    )

    fun execute(context: Context, request: LocalTaskBridgeRequest): LocalTaskBridgeReply {
        val store = LocalTaskStore { context.applicationContext }
        return LocalTaskBridgeProtocol.execute(request, true, object : LocalTaskBridgeBackend {
            override fun apply(accountId: String, tasks: JSONObject, token: String, clearedAt: Long) =
                store.applyMirror(accountId, tasks, token, clearedAt)
            override fun snapshot(accountId: String?, recordLimit: Int): String {
                return store.snapshotPayload(accountId, recordLimit).toString()
            }
        })
    }
}

data class LocalTaskMirrorResult(val success: Boolean, val error: String? = null, val operation: String? = null, internal val snapshot: String? = null) {
    val message: String get() = if (success) {
        if (operation == LocalTaskBridgeProtocol.READ) "任务记录已读取；不代表任务执行成功"
        else "配置已保存到模块；ROOT 同步及任务执行结果需另行确认"
    } else when (error) {
        "credential_failed" -> "凭据加解密失败，未确认同步；请重新登录后重试"
        null -> "未收到有效同步回执"
        "timeout" -> "模块未在时限内确认；配置可能已保存，请重试同步"
        "unauthorized" -> "模块未能验证发送方身份，请重启阅微后重试"
        "payload_too_large", "snapshot_too_large" -> "同步数据过大，未完成确认"
        "busy" -> "配置同步繁忙，请稍后重试"
        "no_reply" -> "模块没有返回有效回执，请确认安装版本并重启阅微"
        "legacy_provider_unavailable" -> "旧版系统的兼容桥接不可达，请检查模块状态"
        else -> "本地配置同步未确认，请稍后重试"
    }
}
