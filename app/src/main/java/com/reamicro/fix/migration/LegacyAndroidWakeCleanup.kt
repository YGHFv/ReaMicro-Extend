package com.reamicro.fix.migration

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import com.reamicro.fix.logging.ModuleAndroidLog
import java.io.File

/**
 * Upgrade-only tombstone, NOT a wake implementation. It cannot create an alarm or a job.
 * Legacy identifiers are kept here solely to cancel objects created by previously installed APKs.
 */
object LegacyAndroidWakeCleanup {
    private const val PREFS = "root_enhancement_migrations"
    @Synchronized
    fun run(context: Context) {
        val app = context.applicationContext
        if (app.packageName != "com.reamicro.fix") return
        val state = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (state.getBoolean("android_wake_removed_v1", false)) return
        val result = runCatching {
            cancelLegacyAlarms(app)
            checkNotNull(app.getSystemService(JobScheduler::class.java)) { "Job service unavailable for cleanup" }.let { jobs ->
                // Only this app's retired wake jobs, never other jobs or other packages.
                listOf(260915, 260917, 260931).forEach(jobs::cancel)
            }
            listOf("reamicro-task-wake", "device-job-diagnostic").forEach { name ->
                app.deleteSharedPreferences(name)
                check(!File(app.applicationInfo.dataDir, "shared_prefs/$name.xml").exists()) {
                    "Legacy preference cleanup incomplete"
                }
            }
            listOf("next-wake-at", "device-diagnostics/alarm.json", "device-diagnostics/job.json").forEach { name ->
                val file = File(app.filesDir, name)
                check(!file.exists() || file.delete()) { "Legacy hint cleanup incomplete" }
            }
            check(state.edit().putBoolean("android_wake_removed_v1", true).remove("error").commit())
        }
        result.onFailure {
            // No implicit Root request or task-mode change. Retry on a later process startup.
            state.edit().putString("error", it.javaClass.simpleName).apply()
            ModuleAndroidLog.error("ReaMicroUpgrade", "Retired wake cleanup pending: ${it.javaClass.simpleName}")
        }
    }
    /** Older injected versions could have created the same alarm under the host UID.
     * Cancel only the exact module-targeted PendingIntents; never touch the host's jobs/data. */
    @Synchronized
    fun runForHost(context: Context) {
        val app = context.applicationContext
        if (app.packageName != "app.zhendong.reamicro") return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("host_wake_removed_v1", false)) return
        runCatching {
            cancelLegacyAlarms(app)
            app.deleteSharedPreferences("reamicro-task-wake")
            prefs.edit().putBoolean("host_wake_removed_v1", true).commit()
        }.onFailure { ModuleAndroidLog.error("ReaMicroUpgrade", "Host legacy cleanup pending: ${it.javaClass.simpleName}") }
    }

    private fun cancelLegacyAlarms(app: Context) {
    val alarms = app.getSystemService(AlarmManager::class.java)
    listOf(
        260827 to "com.reamicro.fix.CLOUD_TASK_HEARTBEAT",
        260829 to "com.reamicro.fix.ROOT_WAKE_ASSIST",
        260830 to "com.reamicro.fix.LOCAL_WAKE_PROBE",
    ).forEach { (request, action) ->
        val intent = Intent(action)
            .setClassName("com.reamicro.fix", "com.reamicro.fix.cloud.api.CloudTaskHeartbeatReceiver")
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        PendingIntent.getBroadcast(app, request, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            checkNotNull(alarms) { "Alarm service unavailable for legacy cleanup" }.cancel(it)
            it.cancel()
        }
    }
    }

    fun completed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("android_wake_removed_v1", false)
}
