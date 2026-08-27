package com.reamicro.fix.cloud.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 系统闹钟唤醒入口；只做一次短连接，不启动可见界面或常驻服务。 */
class CloudTaskHeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(CloudTaskWakeScheduler.ACTION_WAKE, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        Thread {
            try {
                CloudTaskNotificationPoller.pollBlocking(context.applicationContext, source = "alarm")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
