package com.reamicro.fix.ui

import android.content.Context
import androidx.annotation.StringRes
import com.reamicro.fix.R
import com.reamicro.fix.cloud.api.CloudTaskWakeStatus

/** Present raw diagnostic state in the current language, rather than caching translated text. */
internal fun moduleRootTitle(context: Context, status: RootWakeController.Status): String =
    context.getString(R.string.root_status_title, context.getString(status.stateResource()))

internal fun moduleRootMessage(context: Context, status: RootWakeController.Status): String = when {
    !status.rootAvailable -> context.getString(R.string.root_unavailable_description)
    status.watchdogRunning && !status.watchdogInstalled -> context.getString(R.string.root_running_without_script)
    status.watchdogRunning -> context.getString(R.string.root_running_description, RootWakeController.MAX_SLEEP_SECONDS / 60)
    status.watchdogInstalled -> context.getString(R.string.root_installed_description)
    else -> context.getString(R.string.root_inactive_description)
}

@StringRes
internal fun moduleWakeSummaryResource(status: CloudTaskWakeStatus): Int = when {
    status.healthy -> R.string.wake_healthy
    !status.notificationAllowed -> R.string.permission_notification_denied
    !status.exactAlarmAllowed && !status.batteryUnrestricted -> R.string.wake_alarm_battery_denied
    !status.exactAlarmAllowed -> R.string.wake_alarm_denied
    else -> R.string.wake_battery_denied
}

internal fun moduleWakeSummary(context: Context, status: CloudTaskWakeStatus): String =
    context.getString(moduleWakeSummaryResource(status))

internal fun moduleWakeDetails(context: Context, status: CloudTaskWakeStatus): List<String> = listOf(
    context.getString(if (status.exactAlarmAllowed) R.string.wake_alarm_allowed_detail else R.string.wake_alarm_denied_detail),
    context.getString(if (status.batteryUnrestricted) R.string.wake_battery_allowed_detail else R.string.wake_battery_denied_detail),
    context.getString(if (status.notificationAllowed) R.string.wake_notification_allowed_detail else R.string.wake_notification_denied_detail),
)

/** Raw option data survives language changes and pending multi-select edits. */
internal data class ModulePawnOption(
    val value: String,
    val label: String,
    val color: Int?,
    val hint: String,
)

internal fun modulePawnSelectionSummary(context: Context, options: List<ModulePawnOption>, picked: Set<String>): String {
    val locked = options.count { it.value in picked }
    return when {
        options.isEmpty() -> context.getString(R.string.pawn_empty)
        locked == 0 -> context.getString(R.string.pawn_none_locked)
        else -> context.getString(R.string.pawn_selection_summary, locked, options.size)
    }
}

internal fun modulePawnOptionLabel(context: Context, option: ModulePawnOption): String {
    if (option.hint.isBlank()) return option.label
    // These are persisted item IDs, not translated display labels.
    val hintRes = when (option.value) {
        "11", "13", "18" -> R.string.pawn_hint_inheritance
        "12", "14", "15" -> R.string.pawn_hint_blessing
        "16" -> R.string.pawn_hint_alternative
        "17" -> R.string.pawn_hint_treasure
        "name:青圭" -> R.string.pawn_hint_recruitment
        else -> null
    }
    val hint = hintRes?.let { context.getString(it, option.label) } ?: option.hint
    return context.getString(R.string.pawn_item_with_hint, option.label, hint)
}