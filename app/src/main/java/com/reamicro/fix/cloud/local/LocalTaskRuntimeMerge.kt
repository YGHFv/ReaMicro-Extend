package com.reamicro.fix.cloud.local

import org.json.JSONObject

/** Configuration generations and runtime writes advance independently. */
internal object LocalTaskRuntimeMerge {
    const val UPDATED_AT = "runtimeUpdatedAt"

    // A writer's captured configuration is context, never a persisted runtime field.
    const val CONFIG_CONTEXT = "_runtimeConfigUpdatedAt"
    const val ACCOUNT_CONTEXT = "_runtimeAccountGeneration"

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

    private fun copyRuntime(
        target: JSONObject, source: JSONObject, includeNext: Boolean, replaceMissing: Boolean = false,
        includeHistory: Boolean = true,
    ): Boolean {
        var written = false
        for (key in LocalTaskStore.RUNTIME_STATE_KEYS) {
            if (key == UPDATED_AT || (!includeNext && key == LocalTaskStore.KEY_NEXT_RUN_AT)) continue
            if (!includeHistory && key in setOf(LocalTaskStore.KEY_LAST_RUN_AT, LocalTaskStore.KEY_LAST_MESSAGE)) continue
            if (source.has(key)) {
                target.put(key, source.get(key))
                written = true
            } else if (replaceMissing) target.remove(key)
        }
        return written
    }

    fun patch(current: JSONObject, state: JSONObject, now: Long = System.currentTimeMillis()) {
        val configuration = if (state.has(CONFIG_CONTEXT)) state.optLong(CONFIG_CONTEXT)
            else current.optLong(LocalTaskStore.KEY_CONFIG_UPDATED_AT)
        val includeNext = current.optBoolean("enabled") &&
            current.optLong(LocalTaskStore.KEY_CONFIG_UPDATED_AT) <= configuration
        // Checkpoints may carry the run's initial history alongside new progress.
        // Preserve later completed history while still recording actual new counters.
        val includeHistory = !state.has(LocalTaskStore.KEY_LAST_RUN_AT) ||
            state.optLong(LocalTaskStore.KEY_LAST_RUN_AT) >= current.optLong(LocalTaskStore.KEY_LAST_RUN_AT)
        val written = copyRuntime(current, state, includeNext, includeHistory = includeHistory)
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

    fun schedule(current: JSONObject, taskType: String, now: Long) {
        val task = localTaskFromJson(taskType, current)
        val candidate = when {
            !task.enabled -> 0L
            taskType == AutoReadTimeLock.TASK_TYPE ->
                AutoReadTimeLock.nextDailyAt(task.timeOfDay, task.durationMinutes, now)
            else -> now
        }
        current.put(LocalTaskStore.KEY_NEXT_RUN_AT,
            if (task.enabled) AutoReadTimeLock.clampNextAt(task, current, candidate, now) else 0L)
        stamp(current, now)
    }

    fun snapshot(current: JSONObject?, incoming: JSONObject): JSONObject {
        if (current == null) return JSONObject(incoming.toString()).also { disabledNext(it) }
        val newerConfiguration = incoming.optLong(LocalTaskStore.KEY_CONFIG_UPDATED_AT) >
            current.optLong(LocalTaskStore.KEY_CONFIG_UPDATED_AT)
        val newerRuntime = revision(incoming) > revision(current)
        val merged = JSONObject((if (newerConfiguration) incoming else current).toString())

        if (newerConfiguration) {
            // The incoming schedule belongs to its new configuration. Preserve newer
            // execution history/counters without restoring the old configuration's schedule.
            if (!newerRuntime) {
                copyRuntime(merged, current, includeNext = false, replaceMissing = true)
                merged.remove(UPDATED_AT)
                if (current.has(UPDATED_AT)) merged.put(UPDATED_AT, current.get(UPDATED_AT))
            }
        } else if (newerRuntime) {
            copyRuntime(merged, incoming,
                includeNext = incoming.optLong(LocalTaskStore.KEY_CONFIG_UPDATED_AT) ==
                    current.optLong(LocalTaskStore.KEY_CONFIG_UPDATED_AT), replaceMissing = true)
            merged.remove(UPDATED_AT)
            if (incoming.has(UPDATED_AT)) merged.put(UPDATED_AT, incoming.get(UPDATED_AT))
        }
        disabledNext(merged)
        // Snapshot arrival is not a runtime event; retain the source's revision.
        return merged
    }
}
