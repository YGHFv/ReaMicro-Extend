package com.reamicro.fix.ui

/** Pure state machine: no Android resources, UI, or PackageManager dependencies. */
internal data class LauncherIconAlias(val key: String, val defaultEnabled: Boolean)
internal data class LauncherIconChoice(val key: String, val hidden: Boolean)
internal data class LauncherIconChange(val key: String, val enabled: Boolean)
internal enum class LauncherComponentState { DEFAULT, ENABLED, DISABLED, DISABLED_USER, DISABLED_UNTIL_USED }

/** [confirmed] is the last stable choice; [pending] is a durable, unfinished request. */
internal data class LauncherIconRecord(
    val confirmed: LauncherIconChoice,
    val pending: LauncherIconChoice? = null,
    val configured: Boolean = true,
)

internal interface LauncherIconComponents {
    fun read(key: String): LauncherComponentState
    /** Modern Android applies the whole list atomically; legacy Android preserves list order. */
    fun apply(changes: List<LauncherIconChange>)
}

internal interface LauncherIconPreferences {
    fun read(): LauncherIconRecord
    /** Must synchronously report whether the entire record was persisted. */
    fun write(record: LauncherIconRecord): Boolean
}

internal enum class LauncherIconCondition { VISIBLE, HIDDEN, MULTIPLE, UNKNOWN }
internal enum class LauncherIconIssue { READ_FAILED, APPLY_FAILED, SAVE_FAILED, RECOVERY_FAILED, PENDING, MULTIPLE }

internal data class LauncherIconView(
    val choice: LauncherIconChoice,
    val enabledKeys: Set<String>?,
    val issue: LauncherIconIssue?,
) {
    val condition: LauncherIconCondition
        get() = when (enabledKeys?.size) {
            null -> LauncherIconCondition.UNKNOWN
            0 -> LauncherIconCondition.HIDDEN
            1 -> LauncherIconCondition.VISIBLE
            else -> LauncherIconCondition.MULTIPLE
        }
}

internal data class LauncherIconResult(
    val view: LauncherIconView,
    val success: Boolean,
    val error: Exception? = null,
)

internal class LauncherIconController(
    private val aliases: List<LauncherIconAlias>,
    private val components: LauncherIconComponents,
    private val preferences: LauncherIconPreferences,
) {
    private val keys = aliases.map { it.key }.toSet()
    private val defaultChoice = LauncherIconChoice(aliases.single { it.defaultEnabled }.key, hidden = false)
    private var lastChoice: LauncherIconChoice? = null
    private var lastIssue: LauncherIconIssue? = null

    init {
        require(keys.size == aliases.size && aliases.isNotEmpty()) { "Launcher alias keys must be unique" }
    }

    private data class Snapshot(val enabled: Set<String>, val allDefault: Boolean) {
        fun matches(choice: LauncherIconChoice): Boolean =
            enabled == if (choice.hidden) emptySet<String>() else setOf(choice.key)
    }

    private class SaveFailure : IllegalStateException("Could not persist launcher icon state")

    /** Read-only refresh. Unknown/multiple/pending states remain visible instead of being hidden by prefs. */
    @Synchronized
    fun inspect(): LauncherIconResult = withState { record, snapshot ->
        result(
            snapshot, preferred(record),
            lastIssue ?: if (record.pending != null) LauncherIconIssue.PENDING else null,
            success = snapshot.enabled.size <= 1 && record.pending == null && lastIssue == null,
        )
    }

    /**
     * Recover an interrupted request. Otherwise preserve a unique actual choice; use saved intent
     * to repair missing/multiple entries or a reset to manifest defaults. Normal APK updates do not
     * necessarily reset component overrides.
     */
    @Synchronized
    fun restore(): LauncherIconResult = withState { record, snapshot ->
        val target = record.pending ?: when {
            record.configured && (snapshot.allDefault || record.confirmed.hidden || snapshot.enabled.size != 1) ->
                record.confirmed
            else -> actualChoice(snapshot, record.confirmed)
        }
        change(record, snapshot, target, recovering = record.pending != null)
    }

    /**
     * Change only the style; hidden stays whatever the user last intended.
     *
     * Hidden is a durable intent, not a momentary component reading: while hidden, picking a color
     * updates the restore target without lighting any alias. A prefs-vs-component mismatch (e.g. the
     * system reset entries to defaults) is repaired by [restore], never by re-showing a hidden icon.
     */
    @Synchronized
    fun selectStyle(key: String): LauncherIconResult {
        require(key in keys) { "Unknown launcher icon: $key" }
        return withState { record, snapshot ->
            change(record, snapshot, LauncherIconChoice(key, hidden = preferred(record).hidden))
        }
    }

    @Synchronized
    fun setHidden(hidden: Boolean): LauncherIconResult = withState { record, snapshot ->
        change(record, snapshot, actualChoice(snapshot, preferred(record)).copy(hidden = hidden))
    }

    private fun readSnapshot(): Snapshot {
        // One failed query makes the entire observation unknown. Never silently drop that component.
        val states = aliases.associate { it.key to components.read(it.key) }
        val enabled = aliases.filter { alias ->
            when (states.getValue(alias.key)) {
                LauncherComponentState.DEFAULT -> alias.defaultEnabled
                LauncherComponentState.ENABLED -> true
                else -> false
            }
        }.mapTo(linkedSetOf()) { it.key }
        return Snapshot(enabled, states.values.all { it == LauncherComponentState.DEFAULT })
    }

    private fun preferred(record: LauncherIconRecord): LauncherIconChoice =
        if (lastIssue != null) lastChoice ?: record.confirmed else record.confirmed

    private fun actualChoice(snapshot: Snapshot, fallback: LauncherIconChoice): LauncherIconChoice =
        LauncherIconChoice(snapshot.enabled.singleOrNull() ?: fallback.key, snapshot.enabled.isEmpty())

    private fun withState(action: (LauncherIconRecord, Snapshot) -> LauncherIconResult): LauncherIconResult {
        var fallback = lastChoice ?: defaultChoice
        val (record, snapshot) = try {
            val saved = preferences.read()
            val normalized = saved.copy(
                confirmed = if (saved.confirmed.key in keys) saved.confirmed else saved.confirmed.copy(key = defaultChoice.key),
                pending = saved.pending?.takeIf { it.key in keys },
            )
            if (lastChoice == null) fallback = normalized.confirmed
            normalized to readSnapshot()
        } catch (failure: Exception) {
            lastIssue = LauncherIconIssue.READ_FAILED
            return result(null, fallback, lastIssue, success = false, error = failure)
        }
        return action(record, snapshot)
    }

    private fun changes(snapshot: Snapshot?, target: LauncherIconChoice): List<LauncherIconChange> {
        // Enabling the target must precede disabling the old entry on API 26-32 and during rollback.
        val ordered = if (target.hidden) aliases else
            aliases.filter { it.key == target.key } + aliases.filter { it.key != target.key }
        return ordered.mapNotNull { alias ->
            val enabled = !target.hidden && alias.key == target.key
            if (snapshot != null && (alias.key in snapshot.enabled) == enabled) null
            else LauncherIconChange(alias.key, enabled)
        }
    }

    private fun apply(snapshot: Snapshot?, target: LauncherIconChoice) {
        val updates = changes(snapshot, target)
        if (updates.isNotEmpty()) components.apply(updates)
    }

    private fun save(record: LauncherIconRecord) {
        if (!preferences.write(record)) throw SaveFailure()
    }

    private fun change(
        record: LauncherIconRecord,
        snapshot: Snapshot,
        target: LauncherIconChoice,
        recovering: Boolean = false,
    ): LauncherIconResult {
        val committed = LauncherIconRecord(target)
        if (snapshot.matches(target)) {
            // Hidden style changes need only one preferences commit, and must not enable an alias.
            return try {
                if (record != committed || lastIssue != null) save(committed)
                lastIssue = null
                result(snapshot, target, null, success = true)
            } catch (failure: Exception) {
                // SharedPreferences.commit(false) may still change its in-memory map.
                suppressInto(failure) { save(record) }
                lastIssue = LauncherIconIssue.SAVE_FAILED
                result(readAfterFailure(failure), record.confirmed, lastIssue, success = false, error = failure)
            }
        }

        val previous = if (recovering) record.confirmed else actualChoice(snapshot, preferred(record))
        try {
            // A process killed after this commit can finish the request from restore().
            save(LauncherIconRecord(previous, pending = target))
        } catch (failure: Exception) {
            // Do not touch PackageManager if the journal could not be durably written.
            suppressInto(failure) { save(LauncherIconRecord(previous)) }
            lastIssue = LauncherIconIssue.SAVE_FAILED
            return result(readAfterFailure(failure), previous, lastIssue, success = false, error = failure)
        }

        return try {
            apply(snapshot, target)
            val verified = readSnapshot()
            check(verified.matches(target)) { "PackageManager did not apply the requested launcher state" }
            save(committed)
            lastIssue = null
            result(verified, target, null, success = true)
        } catch (failure: Exception) {
            // Before rollback, journal the PREVIOUS stable choice, not the failed new choice.
            // If rollback is itself interrupted, restore() must not retry the rejected selection.
            suppressInto(failure) { save(LauncherIconRecord(previous, pending = previous)) }
            suppressInto(failure) { apply(readAfterFailure(failure), previous) }
            val actual = readAfterFailure(failure)
            val restored = actual?.matches(previous) == true
            val saved = try {
                save(LauncherIconRecord(previous, pending = if (restored) null else previous))
                true
            } catch (saveError: Exception) {
                if (saveError !== failure) failure.addSuppressed(saveError)
                false
            }
            lastIssue = when {
                !restored || !saved -> LauncherIconIssue.RECOVERY_FAILED
                failure is SaveFailure -> LauncherIconIssue.SAVE_FAILED
                else -> LauncherIconIssue.APPLY_FAILED
            }
            result(actual, previous, lastIssue, success = false, error = failure)
        }
    }

    private fun readAfterFailure(failure: Exception): Snapshot? = try {
        readSnapshot()
    } catch (readError: Exception) {
        if (readError !== failure) failure.addSuppressed(readError)
        null
    }

    private fun suppressInto(failure: Exception, action: () -> Unit) {
        try {
            action()
        } catch (secondary: Exception) {
            if (secondary !== failure) failure.addSuppressed(secondary)
        }
    }

    private fun result(
        snapshot: Snapshot?,
        fallback: LauncherIconChoice,
        issue: LauncherIconIssue?,
        success: Boolean,
        error: Exception? = null,
    ): LauncherIconResult {
        val choice = snapshot?.let { actualChoice(it, fallback) } ?: fallback
        if (snapshot != null && snapshot.enabled.size <= 1) lastChoice = choice
        val effectiveIssue = when {
            snapshot == null -> issue ?: LauncherIconIssue.READ_FAILED
            snapshot.enabled.size > 1 -> LauncherIconIssue.MULTIPLE
            else -> issue
        }
        return LauncherIconResult(LauncherIconView(choice, snapshot?.enabled?.toSet(), effectiveIssue), success, error)
    }
}