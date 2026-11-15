package com.reamicro.fix.reader

internal object HighlightOwnedRanges {
    const val TAG = "reamicro.highlight.owned-style"
    fun isOwned(tag: String): Boolean = tag == TAG || tag.startsWith("$TAG|")

    fun <T> keepHostRanges(
        ranges: List<T>,
        tag: (T) -> String,
    ): List<T> = ranges.filterNot { isOwned(tag(it)) }
}
