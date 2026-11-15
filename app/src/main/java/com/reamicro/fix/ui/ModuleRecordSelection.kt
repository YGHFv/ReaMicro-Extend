package com.reamicro.fix.ui

import com.reamicro.fix.cloud.local.LocalTaskRecord
import com.reamicro.fix.cloud.local.localTaskRecordIdentity

internal data class ModuleRecordRow(
    val key: String,
    val accountId: String,
    val record: LocalTaskRecord,
)

/** Occurrences distinguish identical historical records without changing the stored schema. */
internal fun moduleRecordRows(records: List<Pair<String, LocalTaskRecord>>): List<ModuleRecordRow> {
    val occurrences = mutableMapOf<String, Int>()
    return records.map { (accountId, record) ->
        val identity = "${accountId.length}:$accountId:${localTaskRecordIdentity(record)}"
        val occurrence = occurrences.getOrDefault(identity, 0)
        occurrences[identity] = occurrence + 1
        ModuleRecordRow("$identity:$occurrence", accountId, record)
    }
}
