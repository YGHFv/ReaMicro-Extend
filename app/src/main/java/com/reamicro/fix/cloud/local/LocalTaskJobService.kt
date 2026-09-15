package com.reamicro.fix.cloud.local

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import com.reamicro.fix.cloud.api.CloudTaskNotificationPoller
import com.reamicro.fix.logging.ModuleAndroidLog
import com.reamicro.fix.logging.ModuleLogBuffer
import java.util.concurrent.atomic.AtomicBoolean

class LocalTaskJobService : JobService() {
    private var cancellation: AtomicBoolean? = null
    private var worker: Thread? = null

    override fun onStartJob(parameters: JobParameters): Boolean {
        ModuleLogBuffer.attach(applicationContext)
        val cancelled = AtomicBoolean(false)
        cancellation = cancelled
        worker = Thread {
            try {
                runCatching { LocalTaskRunner.runDue(applicationContext) }
                    .onFailure { ModuleAndroidLog.error("ReaMicroLocalTask", "本地任务失败：${it.message}") }
                if (!cancelled.get()) {
                    runCatching { CloudTaskNotificationPoller.pollBlocking(applicationContext, parameters.extras.getString("source").orEmpty()) }
                        .onFailure { ModuleAndroidLog.error("ReaMicroLocalTask", "云端通知同步失败：${it.message}") }
                }
            } finally {
                if (!cancelled.get()) jobFinished(parameters, false)
            }
        }.apply { name = "ReaMicroLocalTaskJob"; start() }
        return true
    }

    override fun onStopJob(parameters: JobParameters): Boolean {
        cancellation?.set(true)
        worker?.interrupt()
        return true
    }

    companion object {
        private const val JOB_ID = 260915
        fun enqueue(context: Context, source: String) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, LocalTaskJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(60_000L)
                .setExtras(PersistableBundle().apply { putString("source", source) })
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                ModuleAndroidLog.error("ReaMicroLocalTask", "后台任务未被系统接受，将由后续闹钟重试")
            }
        }
    }
}
