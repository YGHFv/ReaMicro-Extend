package com.reamicro.fix.cloud.local

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.reamicro.fix.logging.ModuleLogBuffer
import com.reamicro.fix.xposed.XposedBridge

class LocalTaskMirrorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LocalTaskMirror.ACTION || !isOrderedBroadcast) return
        val app = context.applicationContext
        ModuleLogBuffer.attach(app)
        if (Build.VERSION.SDK_INT < 34) {

            val pending = goAsync()
            if (!LocalTaskBridgeRuntime.enqueue {
                try {
                    val granted = LocalTaskBridgeRuntime.grantLegacyVisibility(app)
                    pending.setResult(if (granted) LocalTaskBridgeRuntime.LEGACY_READY else Activity.RESULT_CANCELED,
                        if (granted) null else "legacy_provider_unavailable", null)
                } finally { pending.finish() }
            }) {
                pending.setResult(Activity.RESULT_CANCELED, "busy", null)
                pending.finish()
            }
            return
        }

        val uid = runCatching { sentFromUid }.getOrDefault(-1)
        if (!LocalTaskBridgeRuntime.trustedUid(app, uid)) {
            setResult(Activity.RESULT_CANCELED, "unauthorized", null)
            XposedBridge.logError("ReaMicro local task bridge rejected unauthenticated sender")
            return
        }
        val request = runCatching { intent.extras?.let(LocalTaskBridgeRuntime::request) }.getOrNull()
        if (request == null) {
            setResult(Activity.RESULT_CANCELED, "invalid_request", null)
            return
        }
        val pending = goAsync()
        if (!LocalTaskBridgeRuntime.enqueue {
            try {
                val reply = LocalTaskBridgeRuntime.execute(app, request)
                pending.setResult(if (reply.success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                    reply.error, LocalTaskBridgeRuntime.replyBundle(reply))
            } catch (_: Exception) {
                pending.setResult(Activity.RESULT_CANCELED, "processing_failed", null)
            } finally { pending.finish() }
        }) {
            pending.setResult(Activity.RESULT_CANCELED, "busy", null)
            pending.finish()
        }
    }
}
