package com.reamicro.fix.ui

import com.reamicro.fix.cloud.local.*
import com.reamicro.fix.hook.CloudAutomationTaskSpec
import java.util.Locale

internal fun applyModuleTaskEdits(
    task: LocalTask,
    spec: CloudAutomationTaskSpec?,
    fields: Map<String, () -> String>,
): LocalTask? {
    if (spec != null && spec.taskType != task.taskType) return null
    val text = { label: String -> fields[label]?.invoke()?.trim().orEmpty() }
    val timeOfDay = if (spec?.rewardTriggered != true && spec?.merchant != true) {
        val parts = text("timeOfDay").split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()
        val minute = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size != 2 || hour == null || minute == null || hour !in 0..23 || minute !in 0..59) return null
        String.format(Locale.ROOT, "%02d:%02d", hour, minute)
    } else task.timeOfDay
    val duration = if (spec?.autoRead == true) {
        text("durationMinutes").toIntOrNull()?.takeIf { it in 1..720 } ?: return null
    } else task.durationMinutes
    if (spec?.autoRead == true && AutoReadTimeLock.minutesOfDay(timeOfDay) < duration) return null
    val drawLimit = if (spec?.rewardTriggered == true) {
        text("dailyDrawLimit").toIntOrNull()?.takeIf { it in 0..20 } ?: return null
    } else task.dailyDrawLimit
    val books = if (spec?.autoRead == true) {
        val lines = text("books").lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val parsed = ArrayList<LocalTaskBook>(lines.size)
        for (line in lines) {
            val id = line.substringBefore('|').trim().toLongOrNull() ?: return null
            if (id <= 0L) return null
            parsed += LocalTaskBook(id, line.substringAfter('|', "").trim())
        }
        parsed
    } else task.books
    val blessing = if (spec != null && spec.blessingOptions.isNotEmpty()) {
        spec.resolveBlessingChoice(text("blessingType"))
    } else task.blessingType
    val forbiddenPawnPropIds = if (spec?.taskType == "pawn") {
        CloudTaskLocalRunner.parseForbiddenPawnPropIds(text("forbiddenPawnPropIds"))
    } else task.forbiddenPawnPropIds
    fun nonnegative(value: String): Long? = if (value.isBlank()) 0L else value.toLongOrNull()?.takeIf { it >= 0L }
    val principal = if (spec?.merchant == true) nonnegative(text("merchantPrincipal")) ?: return null else task.merchantPrincipal
    val transport = if (spec?.merchant == true) nonnegative(text("merchantTransportId")) ?: return null else task.merchantTransportId
    return task.copy(
        timeOfDay = timeOfDay,
        durationMinutes = duration,
        dailyDrawLimit = drawLimit,
        books = books,
        forbiddenPawnPropIds = forbiddenPawnPropIds,
        blessingType = blessing,
        merchantCityCode = if (spec?.merchant == true) text("merchantCityCode") else task.merchantCityCode,
        merchantPrincipal = principal,
        merchantTransportId = transport,
    )
}
