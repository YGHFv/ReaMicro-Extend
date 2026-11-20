package com.reamicro.fix.ui

internal data class MultiSelectOption(val value: String, val label: String, val color: Int? = null)

internal fun multiSelectSummary(options: List<MultiSelectOption>, selected: Set<String>): String {
    val locked = options.count { it.value in selected }
    return when {
        options.isEmpty() -> "暂无可选期物"
        locked == 0 -> "未锁定任何项（全部可典当）"
        else -> "已锁定 $locked/${options.size} 项"
    }
}
