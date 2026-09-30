package com.reamicro.fix.migration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Cancels retired Android work on upgrade; never schedules anything or starts Root. */
class PackageUpgradeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) LegacyAndroidWakeCleanup.run(context)
    }
}
