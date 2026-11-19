package com.reamicro.fix.notification

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.reamicro.fix.logging.ModuleLogState

class CloudTaskNotificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handle(intent)) finishWithoutAnimation()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handle(intent)) finishWithoutAnimation()
    }

    private fun handle(intent: Intent?): Boolean {
        ModuleLogState.applyFromIntent(intent)
        if (intent?.action != CloudTaskNotifications.ACTION_POST) return true
        if (!intent.getStringExtra(CloudTaskNotifications.EXTRA_PHASE).isNullOrBlank()) return true
        // The main UI owns permission requests. A background wake must never open a prompt.
        CloudTaskNotifications.post(applicationContext, intent, source = "activity")
        return true
    }

    private fun finishWithoutAnimation() {
        finish()
        overridePendingTransition(0, 0)
    }

}
