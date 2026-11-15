package com.reamicro.fix.cloud.local

import android.app.Activity
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.reamicro.fix.cloud.api.ApiServerSettingsBridgeProvider
import com.reamicro.fix.xposed.HookRuntime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

object LocalTaskMirror {
    const val ACTION = "com.reamicro.fix.LOCAL_TASK_MIRROR"
    const val EXTRA_PAYLOAD = "payload"
    const val MODULE_PACKAGE = "com.reamicro.fix"
    const val RECEIVER_CLASS = "com.reamicro.fix.cloud.local.LocalTaskMirrorReceiver"
    const val PAYLOAD_ACCOUNTS = "accounts"
    private val activationRevision = AtomicLong()
    @Volatile private var confirmedActivation: Boolean? = null

    internal fun activationConfirmed(enabled: Boolean): Boolean = confirmedActivation == enabled

    fun push(context: Context, onComplete: (() -> Unit)? = null): Boolean =
        exchange(context, LocalTaskBridgeProtocol.SYNC, null) { onComplete?.invoke() }

    fun pushWithResult(context: Context, onResult: (LocalTaskMirrorResult) -> Unit): Boolean =
        exchange(context, LocalTaskBridgeProtocol.SYNC, null, onResult = onResult)

    fun refresh(context: Context, accountId: String? = null, onResult: (LocalTaskMirrorResult) -> Unit): Boolean =
        exchange(context, LocalTaskBridgeProtocol.READ, accountId, onResult = onResult)

    fun setModuleEnabled(context: Context, enabled: Boolean, onResult: (LocalTaskMirrorResult) -> Unit): Boolean {
        val revision = activationRevision.incrementAndGet()
        confirmedActivation = null
        return exchange(context, LocalTaskBridgeProtocol.ACTIVATE, null,
            JSONObject().put("moduleEnabled", enabled).toString()) { result ->
            if (activationRevision.get() == revision) confirmedActivation = if (result.success) enabled else null
            onResult(result)
        }
    }

    private fun exchange(context: Context, operation: String, accountId: String?, controlPayload: String? = null,
                         onResult: (LocalTaskMirrorResult) -> Unit): Boolean {
        val lease = com.reamicro.fix.reload.ModuleResources.lease() ?: return false
        val app = context.applicationContext
        val main = com.reamicro.fix.reload.ModuleResources.handler(Looper.getMainLooper())
        val completion = LocalTaskBridgeCompletion()
        val timerToken = Any()
        val id = UUID.randomUUID().toString()
        val transport = if (Build.VERSION.SDK_INT >= 34) "ordered-broadcast" else "legacy-provider"
        val started = SystemClock.elapsedRealtime()
        fun deliver(result: LocalTaskMirrorResult) {
            if (result.success) HookRuntime.logAlways("ReaMicro local task bridge $operation acknowledged transport=$transport ms=${SystemClock.elapsedRealtime() - started}")
            else HookRuntime.logError("ReaMicro local task bridge $operation unconfirmed transport=$transport code=${result.error}")
            main.post {
                try {
                    runCatching { onResult(result) }.onFailure { HookRuntime.logError("ReaMicro local task bridge UI callback failed") }
                } finally { lease.close() }
            }
        }
        fun fail(code: String) {
            if (!completion.claim()) return
            main.removeCallbacksAndMessages(timerToken)
            deliver(LocalTaskMirrorResult(false, code))
        }
        fun accept(reply: LocalTaskBridgeReply) {
            val expectedActivation = if (operation == LocalTaskBridgeProtocol.ACTIVATE)
                JSONObject(controlPayload!!).getBoolean("moduleEnabled") else null
            val error = LocalTaskBridgeProtocol.validateReply(id, reply, expectedActivation)
            if (error != null) { fail(error); return }
            if (!completion.claim()) return
            main.removeCallbacksAndMessages(timerToken)
            if (!LocalTaskBridgeRuntime.enqueue {
                val applied = runCatching {
                    if (operation != LocalTaskBridgeProtocol.ACTIVATE) {
                        applyLocalTaskSyncReceipt(LocalTaskStore { app }, operation, reply.snapshot!!)
                    }
                }.isSuccess
                deliver(LocalTaskMirrorResult(applied, if (applied) null else "snapshot_apply_failed", operation,
                    if (applied) reply.snapshot else null))
            }) deliver(LocalTaskMirrorResult(false, "busy"))
        }
        val timeout = if (operation == LocalTaskBridgeProtocol.ACTIVATE) 120_000L else 15_000L
        main.postAtTime({ fail("timeout") }, timerToken, SystemClock.uptimeMillis() + timeout)
        val queued = LocalTaskBridgeRuntime.enqueue dispatch@ {
            if (completion.isClaimed()) return@dispatch
            try {
                val payload = if (operation == LocalTaskBridgeProtocol.SYNC) LocalTaskStore { app }.mirrorPayload().toString() else controlPayload
                if (payload != null && !LocalTaskBridgeProtocol.fits(payload)) { fail("payload_too_large"); return@dispatch }
                val request = LocalTaskBridgeRequest(id, operation, payload, accountId)
                if (app.packageName == MODULE_PACKAGE && app.applicationInfo.uid == Process.myUid()) {
                    accept(LocalTaskBridgeRuntime.execute(app, request))
                    return@dispatch
                }
                val intent = Intent(ACTION).setClassName(MODULE_PACKAGE, RECEIVER_CLASS)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

                if (Build.VERSION.SDK_INT >= 34) intent.putExtras(LocalTaskBridgeRuntime.requestBundle(request))
                val replyReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent?) {
                        if (completion.isClaimed()) return
                        if (Build.VERSION.SDK_INT < 34) {
                            if (resultCode != LocalTaskBridgeRuntime.LEGACY_READY) { fail("no_reply"); return }
                            if (!LocalTaskBridgeRuntime.enqueue legacy@ {
                                if (completion.isClaimed()) return@legacy
                                val reply = runCatching {
                                    val bundle = app.contentResolver.call(ApiServerSettingsBridgeProvider.URI,
                                        LocalTaskBridgeRuntime.PROVIDER_EXCHANGE, null, LocalTaskBridgeRuntime.requestBundle(request))
                                    LocalTaskBridgeRuntime.reply(bundle)
                                }.getOrElse { fail("legacy_provider_unavailable"); return@legacy }
                                accept(reply)
                            }) fail("busy")
                        } else {
                            if (resultCode != Activity.RESULT_OK) {
                                val known = setOf("unauthorized", "busy", "processing_failed", "invalid_request", "invalid_payload",
                                    "unsupported_operation", "payload_too_large", "snapshot_too_large", "invalid_snapshot",
                                    "credential_failed")
                                fail(resultData?.takeIf { it in known } ?: "no_reply")
                                return
                            }
                            runCatching { LocalTaskBridgeRuntime.reply(getResultExtras(false)) }
                                .onSuccess(::accept).onFailure { fail("invalid_receipt") }
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
                    app.sendOrderedBroadcast(intent, null, options, replyReceiver, main, Activity.RESULT_CANCELED, null, null)
                } else {
                    app.sendOrderedBroadcast(intent, null, replyReceiver, main, Activity.RESULT_CANCELED, null, null)
                }
            } catch (_: LocalTaskCredentialException) {
                fail("credential_failed")
            } catch (_: Exception) { fail("dispatch_failed") }
        }
        if (!queued) fail("busy")
        return queued
    }
}
