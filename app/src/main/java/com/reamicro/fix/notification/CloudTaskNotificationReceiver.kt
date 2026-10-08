package com.reamicro.fix.notification

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reamicro.fix.logging.ModuleLogBuffer
import com.reamicro.fix.logging.ModuleLogState

class CloudTaskNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ModuleLogState.applyFromIntent(intent)
        ModuleLogBuffer.attach(context.applicationContext)
        if (intent.action != CloudTaskNotifications.ACTION_POST) return
        val posted = CloudTaskNotifications.post(context.applicationContext, intent, source = "receiver")

        if (posted) setResultCode(Activity.RESULT_OK)
    }
}
