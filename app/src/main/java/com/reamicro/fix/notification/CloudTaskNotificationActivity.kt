package com.reamicro.fix.notification

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.reamicro.fix.logging.ModuleAndroidLog
import com.reamicro.fix.logging.ModuleLogState

class CloudTaskNotificationActivity : Activity() {
    private var pending: Intent? = null

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
        if (!CloudTaskNotifications.hasPermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pending = Intent(intent)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
                ModuleAndroidLog.legacy(LOG_TAG, "cloud task notification permission requested")
                return false
            }
            return true
        }
        CloudTaskNotifications.post(applicationContext, intent, source = "activity")
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_POST_NOTIFICATIONS) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        val intent = pending
        pending = null
        if (granted && intent != null) {
            CloudTaskNotifications.post(applicationContext, intent, source = "activity-granted")
        } else {
            ModuleAndroidLog.legacy(LOG_TAG, "cloud task notification permission denied after request")
        }
        finishWithoutAnimation()
    }

    private fun finishWithoutAnimation() {
        finish()
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val LOG_TAG = "ReaMicroNotify"
        const val REQUEST_POST_NOTIFICATIONS = 4401
    }
}
