package com.reamicro.fix.notification

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Protected by android.permission.DUMP; host cloud notifications use the ordinary endpoints. */
class RootTaskProgressReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra(CloudTaskNotifications.EXTRA_PHASE).isNullOrBlank()) return
        if (CloudTaskNotifications.post(context.applicationContext, intent, "root-progress")) resultCode = Activity.RESULT_OK
    }
}

class RootTaskProgressActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deliver(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        deliver(intent)
    }

    private fun deliver(intent: Intent?) {
        if (intent != null && !intent.getStringExtra(CloudTaskNotifications.EXTRA_PHASE).isNullOrBlank()) {
            CloudTaskNotifications.post(applicationContext, intent, "root-progress-wake")
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
