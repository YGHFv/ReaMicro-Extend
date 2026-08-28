package com.reamicro.fix.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reamicro.fix.logging.ModuleLogState

/**
 * 接收阅微进程投来的云端任务消息，在模块自己的进程里发通知。
 * 与在线补全下载通知同一套路径，只是云任务消息是一次性事件，不需要前台服务和进度条。
 */
class CloudTaskNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ModuleLogState.applyFromIntent(intent)
        if (intent.action != CloudTaskNotifications.ACTION_POST) return
        CloudTaskNotifications.post(context.applicationContext, intent, source = "receiver")
    }
}
