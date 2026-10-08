package com.reamicro.fix.migration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PackageUpgradeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) LegacyAndroidWakeCleanup.run(context)
    }
}
