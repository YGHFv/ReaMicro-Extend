package com.reamicro.fix.hook

internal object ReaderVirtualPageLoadPolicy {
    // 宿主相邻页预取为 10 页；额外留出手势翻页余量，但不能按旧窗口页码补齐数千页。
    private const val MAX_UNMAPPED_DISTANCE = 32L

    fun accepts(requested: Int, mappedPages: Set<Int>): Boolean {
        if (mappedPages.isEmpty() || requested in mappedPages) return true
        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        for (page in mappedPages) {
            first = minOf(first, page)
            last = maxOf(last, page)
        }
        return requested.toLong() >= first.toLong() - MAX_UNMAPPED_DISTANCE &&
            requested.toLong() <= last.toLong() + MAX_UNMAPPED_DISTANCE
    }
}
