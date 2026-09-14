package com.reamicro.fix.notification

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reamicro.fix.logging.ModuleLogBuffer
import com.reamicro.fix.logging.ModuleLogState

/**
 * 接收阅微进程投来的云端任务消息，在模块自己的进程里发通知。
 * 与在线补全下载通知同一套路径，只是云任务消息是一次性事件，不需要前台服务和进度条。
 *
 * **这里要如实回报结果**：宿主用 `sendOrderedBroadcast` 投递，只有本接收器真正把通知发出去
 * 才把结果码置成 [Activity.RESULT_OK]，宿主据此才回执给服务器。此前宿主只看「广播发出去了」
 * 就回执，于是模块进程被系统冻结、接收器根本没跑的时候，服务器也显示「已发送」，而设备上
 * 什么都没有。
 */
class CloudTaskNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ModuleLogState.applyFromIntent(intent)
        ModuleLogBuffer.attach(context.applicationContext)
        if (intent.action != CloudTaskNotifications.ACTION_POST) return
        val posted = CloudTaskNotifications.post(context.applicationContext, intent, source = "receiver")
        // 非有序广播时这一步只会被系统记一条日志，不会抛异常。
        if (posted) setResultCode(Activity.RESULT_OK)
    }
}
