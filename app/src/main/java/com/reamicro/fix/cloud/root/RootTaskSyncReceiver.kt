package com.reamicro.fix.cloud.root

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.reamicro.fix.logging.ModuleAndroidLog
import java.util.concurrent.atomic.AtomicBoolean

class RootTaskSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != RootTaskRepository.SYNC_ACTION || !RootTaskBridge.isEnabled(context)) return
        val pending = goAsync()
        val done = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        val finish = Runnable { if (done.compareAndSet(false, true)) pending.finish() }
        main.postDelayed(finish, 8_000L)
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
