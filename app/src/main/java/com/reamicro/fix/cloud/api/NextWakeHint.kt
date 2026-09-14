package com.reamicro.fix.cloud.api

import android.content.Context
import com.reamicro.fix.notification.CloudTaskNotifications
import java.io.File

/**
 * 「下一次该唤醒模块的时刻」的落盘提示，给 root 看门狗读。
 *
 * 看门狗是 root 侧的一个常驻 shell 循环（见 `RootWakeController.watchdogScript`）。让它每 15 分钟
 * 无条件广播一次是浪费——大部分唤醒都无事可做。改成：模块每次排完闹钟就把触发时刻写进这个文件，
 * 看门狗睡到那个时刻才广播，于是唤醒次数与真实任务时刻对齐。
 *
 * 文件放在模块自己的 `filesDir` 下：root 能读，普通应用读不到。写只发生在**模块进程**——
 * 阅微进程里 `context.filesDir` 是阅微的目录，看门狗读不到，也没必要读。
 */
object NextWakeHint {
    const val FILE_NAME = "next-wake-at"

    fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    /** 记下下一次唤醒时刻（毫秒）。非模块进程或写失败都静默忽略——这是纯优化提示，不是关键路径。 */
    fun write(context: Context, triggerAtMillis: Long) {
        val appContext = context.applicationContext
        if (appContext.packageName != CloudTaskNotifications.MODULE_PACKAGE_NAME) return
        if (triggerAtMillis <= 0L) return
        runCatching { file(appContext).writeText((triggerAtMillis / 1000L).toString()) }
    }

    /** 读回下一次唤醒时刻（毫秒）；没有或读不到时返回 0。 */
    fun read(context: Context): Long =
        runCatching { file(context).readText().trim().toLongOrNull()?.times(1000L) ?: 0L }.getOrDefault(0L)
}
