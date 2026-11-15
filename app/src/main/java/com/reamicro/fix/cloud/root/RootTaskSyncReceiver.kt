package com.reamicro.fix.cloud.root

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.reamicro.fix.logging.ModuleAndroidLog
import java.util.concurrent.atomic.AtomicBoolean

class RootTaskSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != RootTaskRepository.SYNC_ACTION || !RootTaskBridge.isEnabled(context)) return
        val pending = goAsync()
        val done = AtomicBoolean(false)
        val main = com.reamicro.fix.reload.ModuleResources.handler(Looper.getMainLooper())
        val finish = Runnable { if (done.compareAndSet(false, true)) pending.finish() }
        // The sender uses FLAG_RECEIVER_FOREGROUND. Finish within its broadcast
        // budget even if SU authorization or the serialized bridge is still pending.
        if (!main.postDelayed(finish, 8_000L)) finish.run()
        try {
            RootTaskBridge.requestSync(context.applicationContext) {
                main.removeCallbacks(finish)
                finish.run()
            }
        } catch (error: Exception) {
            main.removeCallbacks(finish)
            finish.run()
            ModuleAndroidLog.error("ReaMicroRoot", "Root completion sync failed: ${error.javaClass.simpleName}")
        }
    }
}
