package com.reamicro.fix.cloud.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reamicro.fix.cloud.local.LocalTaskRunner

/** 系统闹钟唤醒入口；只做一次短连接，不启动可见界面或常驻服务。 */
class CloudTaskHeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in setOf(
                CloudTaskWakeScheduler.ACTION_WAKE,
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
            )) return
        val appContext = context.applicationContext
        // 开机、改时间和换时区都先重排，避免旧的 RTC 闹钟落在过去；随后立即拉取一次。
        CloudTaskWakeScheduler.schedule(appContext)
        val pending = goAsync()
        Thread {
            try {
                if (action == CloudTaskWakeScheduler.ACTION_WAKE || action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    // 云端任务由服务器执行，模块进程只跑不依赖服务器的本地任务。
                    runCatching { LocalTaskRunner.runDue(appContext) }
                }
                CloudTaskNotificationPoller.pollBlocking(appContext, source = action)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
