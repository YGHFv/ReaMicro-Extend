package com.reamicro.fix.reader

internal object HighlightOwnedRanges {
    const val TAG = "reamicro.highlight.owned-style"
    fun isOwned(tag: String): Boolean = tag == TAG || tag.startsWith("$TAG|")

    fun <T> keepHostRanges(
        ranges: List<T>,
        tag: (T) -> String,
        legacyOwned: (T) -> Boolean,
    ): List<T> {
        // 新标记只删除明确属于模块的范围，不能按颜色相等删除宿主自己的样式。
        val hasOwnership = ranges.any { isOwned(tag(it)) }
        return ranges.filterNot { isOwned(tag(it)) || (!hasOwnership && legacyOwned(it)) }
    }
}
