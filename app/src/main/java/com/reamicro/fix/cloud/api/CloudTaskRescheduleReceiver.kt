package com.reamicro.fix.cloud.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reamicro.fix.logging.ModuleLogBuffer

/**
 * 模块进程侧：接受宿主的「重排唤醒」请求。
 *
 * 为什么需要它：阅微进程（前台心跳、云端轮询、设置页）也要驱动下一次唤醒，但闹钟与
 * [NextWakeHint] 必须由**同一个进程、同一份存储**产出——否则会出现宿主排的模糊闹钟与模块排的
 * 精确闹钟并存、以及"下次任务时刻刷新了、唤起时刻停在旧值"这类对不上的现象。
 * 所以宿主不自己排，只把请求（连同它知道的云任务时刻）转给这里，由模块算。
 *
 * 这个接收器**只排程、不执行任务**：用户打开阅微时该不该顺手跑一轮由
 * `LocalTaskMirror.EXTRA_RUN_DUE` 决定，两者是不同的语义，混在一起会让"改个配置"也触发一次执行。
 */
class CloudTaskRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != CloudTaskWakeScheduler.ACTION_RESCHEDULE) return
        val appContext = context.applicationContext
        ModuleLogBuffer.attach(appContext)
        CloudTaskWakeScheduler.schedule(
            appContext,
            if (intent.hasExtra(CloudTaskWakeScheduler.EXTRA_NEXT_TASK_AT)) {
                intent.getLongExtra(CloudTaskWakeScheduler.EXTRA_NEXT_TASK_AT, 0L)
            } else null,
        )
    }
}
