package com.reamicro.fix.cloud.local

import org.json.JSONObject

/** Configuration generations and runtime writes advance independently. */
internal object LocalTaskRuntimeMerge {
    const val UPDATED_AT = "runtimeUpdatedAt"
    const val DRAW_SCHEDULE_VERSION = 2
    private val drawTriggerKeys = setOf("drawPending", "drawRewardLoreId", "drawScheduleVersion")

    // A writer's captured configuration is context, never a persisted runtime field.
    const val CONFIG_CONTEXT = "_runtimeConfigUpdatedAt"
    const val ACCOUNT_CONTEXT = "_runtimeAccountGeneration"
    const val DRAW_CONTEXT = "_runtimeDrawSchedule"

    fun drawScheduleContext(task: LocalTask): String = if (task.drawAtCustomTime) task.timeOfDay else ""

    private fun drawScheduleContext(task: JSONObject): String =
        if (task.optBoolean("drawAtCustomTime")) task.optString("timeOfDay", "00:05") else ""

    private fun revision(state: JSONObject): Long =
        maxOf(state.optLong(UPDATED_AT), state.optLong(LocalTaskStore.KEY_LAST_RUN_AT), 0L)

    fun stamp(state: JSONObject, now: Long) {
        val previous = revision(state)
        state.put(UPDATED_AT, maxOf(now, if (previous == Long.MAX_VALUE) previous else previous + 1L))
    }

    private fun disabledNext(state: JSONObject): Boolean {
        if (state.optBoolean("enabled") || state.optLong(LocalTaskStore.KEY_NEXT_RUN_AT) == 0L) return false
        state.put(LocalTaskStore.KEY_NEXT_RUN_AT, 0L)
        return true
    }

    private fun mergePawnCatalog(target: JSONObject, vararg sources: JSONObject) {
        val key = CloudTaskLocalRunner.KEY_PAWN_PROP_CATALOG
        val merged = JSONObject(target.optJSONObject(key)?.toString() ?: "{}")
        for (source in sources) {
            val catalog = source.optJSONObject(key) ?: continue
            catalog.keys().forEach { id -> merged.put(id, catalog.get(id)) }
        }
        if (merged.length() > 0) target.put(key, merged)
    }

    private fun copyRuntime(
        target: JSONObject, source: JSONObject, includeNext: Boolean, replaceMissing: Boolean = false,
        includeHistory: Boolean = true, includeDrawTrigger: Boolean = true,
    ): Boolean {
        var written = false
        for (key in LocalTaskStore.RUNTIME_STATE_KEYS) {
            if (key == UPDATED_AT || key == CloudTaskLocalRunner.KEY_PAWN_PROP_CATALOG ||
                (!includeNext && key == LocalTaskStore.KEY_NEXT_RUN_AT)) continue
            if (!includeDrawTrigger && key in drawTriggerKeys) continue
            if (!includeHistory && key in setOf(LocalTaskStore.KEY_LAST_RUN_AT, LocalTaskStore.KEY_LAST_MESSAGE)) continue
            if (source.has(key)) {
                target.put(key, source.get(key))
                written = true
            } else if (replaceMissing) target.remove(key)
        }
        return written
    }

    fun patch(current: JSONObject, state: JSONObject, now: Long = System.currentTimeMillis()) {
        // Catalog discovery must not make stale execution counters look newer than Root's progress.
        mergePawnCatalog(current, state)
        val configuration = if (state.has(CONFIG_CONTEXT)) state.optLong(CONFIG_CONTEXT)
            else current.optLong(LocalTaskStore.KEY_CONFIG_UPDATED_AT)
        val includeNext = current.optBoolean("enabled") &&
            current.optLong(LocalTaskStore.KEY_CONFIG_UPDATED_AT) <= configuration
        // Checkpoints may carry the run's initial history alongside new progress.
        // Preserve later completed history while still recording actual new counters.
        val includeHistory = !state.has(LocalTaskStore.KEY_LAST_RUN_AT) ||
            state.optLong(LocalTaskStore.KEY_LAST_RUN_AT) >= current.optLong(LocalTaskStore.KEY_LAST_RUN_AT)
        val sameDrawTrigger = current.optLong(LocalTaskStore.KEY_CONFIG_UPDATED_AT) <= configuration ||
            (state.has(DRAW_CONTEXT) && state.optString(DRAW_CONTEXT) == drawScheduleContext(current))
        val written = copyRuntime(current, state, includeNext, includeHistory = includeHistory,
            includeDrawTrigger = sameDrawTrigger)
        val disabled = disabledNext(current)
        // A supplied timestamp cannot turn an old state into a newly written runtime.
        if (written || disabled) stamp(current, now)
    }

    fun complete(current: JSONObject, configuration: Long, state: JSONObject, now: Long = System.currentTimeMillis()) {
        if (state.has(LocalTaskStore.KEY_LAST_RUN_AT) &&
            state.optLong(LocalTaskStore.KEY_LAST_RUN_AT) < current.optLong(LocalTaskStore.KEY_LAST_RUN_AT)) {
            if (disabledNext(current)) stamp(current, now)
            return
        }
        patch(current, JSONObject(state.toString()).put(CONFIG_CONTEXT, configuration), now)
    }

    fun schedule(current: JSONObject, taskType: String, now: Long, previous: JSONObject? = null) {
        val task = localTaskFromJson(taskType, current)
        val sameDrawTrigger = previous != null && drawScheduleContext(current) == drawScheduleContext(previous)
        if (taskType == "yeshe_draw_card" && previous != null && !sameDrawTrigger) {
            // A changed trigger replaces its pending run; completed draws still count toward today's limit.
            current.put("drawPending", false)
        }
        val candidate = when {
            !task.enabled -> 0L
            taskType == AutoReadTimeLock.TASK_TYPE ->
                AutoReadTimeLock.nextDailyAt(task.timeOfDay, task.durationMinutes, now)
            taskType == "yeshe_draw_card" && sameDrawTrigger && previous.optBoolean("enabled") &&
                previous.optLong(LocalTaskStore.KEY_NEXT_RUN_AT) > 0L &&
                (task.drawAtCustomTime || current.optBoolean("drawPending")) ->
                previous.optLong(LocalTaskStore.KEY_NEXT_RUN_AT)
            taskType == "yeshe_draw_card" && !current.optBoolean("drawPending") ->
                if (task.drawAtCustomTime) nextDailyRunAt(task.timeOfDay, now) else 0L
            else -> now
        }
        current.put(LocalTaskStore.KEY_NEXT_RUN_AT,
            if (task.enabled) AutoReadTimeLock.clampNextAt(task, current, candidate, now) else 0L)
        if (taskType == "yeshe_draw_card") current.put("drawScheduleVersion", DRAW_SCHEDULE_VERSION)
        // Config revisions own this schedule; saving config must not outrank newer execution progress.
    }

    fun snapshot(current: JSONObject?, incoming: JSONObject): JSONObject {
        if (current == null) return JSONObject(incoming.toString()).also { disabledNext(it) }
        val newerConfiguration = incoming.optLong(LocalTaskStore.KEY_CONFIG_UPDATED_AT) >
            current.optLong(LocalTaskStore.KEY_CONFIG_UPDATED_AT)
        val newerRuntime = revision(incoming) > revision(current)
        val sameDrawTrigger = drawScheduleContext(current) == drawScheduleContext(incoming)
        val merged = JSONObject((if (newerConfiguration) incoming else current).toString())

        if (newerConfiguration) {
            // The incoming schedule belongs to its new configuration. Preserve newer
            // execution history/counters without restoring the old configuration's schedule.
            if (!newerRuntime) {
                copyRuntime(merged, current, includeNext = false, replaceMissing = true,
                    includeDrawTrigger = sameDrawTrigger)
                merged.remove(UPDATED_AT)
                if (current.has(UPDATED_AT)) merged.put(UPDATED_AT, current.get(UPDATED_AT))
            }
        } else if (newerRuntime) {
            copyRuntime(merged, incoming,
                includeNext = incoming.optLong(LocalTaskStore.KEY_CONFIG_UPDATED_AT) ==
                    current.optLong(LocalTaskStore.KEY_CONFIG_UPDATED_AT), replaceMissing = true,
                includeDrawTrigger = sameDrawTrigger)
            merged.remove(UPDATED_AT)
            if (incoming.has(UPDATED_AT)) merged.put(UPDATED_AT, incoming.get(UPDATED_AT))
        }
        if (newerRuntime) mergePawnCatalog(merged, current, incoming)
        else mergePawnCatalog(merged, incoming, current)
        disabledNext(merged)
        // Snapshot arrival is not a runtime event; retain the source's revision.
        return merged
    }
}
