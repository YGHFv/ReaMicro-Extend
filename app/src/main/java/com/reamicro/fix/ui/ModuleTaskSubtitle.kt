package com.reamicro.fix.ui

import android.content.Context
import com.reamicro.fix.R
import com.reamicro.fix.cloud.local.LocalTask
import com.reamicro.fix.cloud.local.LocalTaskRecord
import com.reamicro.fix.cloud.local.LocalTaskStore
import com.reamicro.fix.cloud.local.AutoReadTimeLock
import com.reamicro.fix.hook.CloudAutomationTaskSpec
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formats an already read task without reading Activity state or rereading its account records. */
internal fun moduleTaskSubtitle(
    context: Context,
    task: LocalTask,
    spec: CloudAutomationTaskSpec?,
    state: JSONObject,
    records: List<LocalTaskRecord>,
    effectiveNextRunAt: Long = task.nextRunAt,
): String {
    fun text(id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    fun time(at: Long) =
        SimpleDateFormat("MM-dd HH:mm:ss", context.resources.configuration.locales[0]).format(Date(at))
    val nextLine = when {
        !task.enabled -> text(R.string.status_not_enabled)
        spec?.rewardTriggered == true -> text(R.string.task_wish_trigger)
        else -> {
            val next = if (effectiveNextRunAt > 0L) time(effectiveNextRunAt) else text(R.string.status_pending_schedule)
            if (spec?.merchant == true) {
                val tripEnd = if (state.has(LocalTaskStore.KEY_MERCHANT_END_TIME)) {
                    state.optLong(LocalTaskStore.KEY_MERCHANT_END_TIME).takeIf { it > 0L }?.let(::time)
                } else {
                    records.firstOrNull { it.taskType == task.taskType }?.detail
                        ?.let { runCatching { JSONObject(it) }.getOrNull() }
                        ?.optString("行程")?.substringAfter(" → ", "")?.takeIf { it.isNotBlank() }
                }
                val poll = text(R.string.task_next_check, next)
                if (tripEnd == null) poll else text(R.string.task_merchant_next, poll, tripEnd)
            } else text(R.string.task_next_run, next)
        }
    }
    return buildString {
        if (task.taskType == AutoReadTimeLock.TASK_TYPE) {
            append(text(R.string.task_auto_read_config, task.durationMinutes, task.timeOfDay))
            append('\n')
        }
        if (!spec?.blessingOptions.isNullOrEmpty()) {
            val label = when (task.blessingType.trim().uppercase(Locale.ROOT)) {
                "LUCK" -> text(R.string.blessing_luck)
                "SAFETY" -> text(R.string.blessing_safety)
                "WEALTH" -> text(R.string.blessing_wealth)
                "" -> text(R.string.blessing_none)
                else -> task.blessingType
            }
            append(text(R.string.task_blessing_summary, label))
            append('\n')
        }
        append(nextLine)
        if (task.lastMessage.isNotBlank()) {
            append('\n')
            append(text(R.string.task_last_execution, task.lastMessage))
        }
    }
}
