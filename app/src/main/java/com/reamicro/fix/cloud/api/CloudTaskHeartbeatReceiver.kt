package com.reamicro.fix.cloud.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reamicro.fix.cloud.local.LocalTaskJobService
import com.reamicro.fix.cloud.ksu.KsuTaskRepository
import com.reamicro.fix.logging.ModuleLogBuffer

/** 系统闹钟唤醒入口；只重排并提交后台作业，网络执行不占用广播生命周期。 */
class CloudTaskHeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in setOf(
                CloudTaskWakeScheduler.ACTION_WAKE,
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                KsuTaskRepository.SYNC_ACTION,
            )) return
        val appContext = context.applicationContext
        // 闹钟唤醒是后台路径上最早拿到 Context 的地方之一，日志落盘位置在这里绑定。
        ModuleLogBuffer.attach(appContext)
        // 开机、改时间和换时区都先重排，避免旧的 RTC 闹钟落在过去；随后立即拉取一次。
        CloudTaskWakeScheduler.schedule(appContext)
        LocalTaskJobService.enqueue(appContext, action)
    }
}
