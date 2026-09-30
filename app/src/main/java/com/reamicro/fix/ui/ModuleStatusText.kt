package com.reamicro.fix.ui

import android.content.Context
import androidx.annotation.StringRes
import com.reamicro.fix.R

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
    val hint = hintRes?.let { context.getString(it) } ?: option.hint
    return context.getString(R.string.pawn_item_with_hint, option.label, hint)
}