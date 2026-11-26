package com.reamicro.fix.ui

/** [detail] says what the option is for, shown under its [label]. */
internal data class MultiSelectOption(
    val value: String,
    val label: String,
    val color: Int? = null,
    val detail: String = "",
)

internal fun multiSelectSummary(options: List<MultiSelectOption>, selected: Set<String>): String {
    val locked = options.count { it.value in selected }
    return when {
        options.isEmpty() -> "暂无可选期物"
        locked == 0 -> "未锁定任何项（全部可典当）"
        else -> "已锁定 $locked/${options.size} 项"
    }
}
