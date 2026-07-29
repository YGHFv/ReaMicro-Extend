package com.reamicro.fix.webdav

internal object OnlineOnDemandPrefetchPlanner {
    // 阅读中仅预取前方章节，避免回读和目录跳转同时触发多个请求压垮在线源。
    val READING_OFFSETS = listOf(1, 2)
    val CATALOG_NEIGHBOR_OFFSETS = listOf(-1, 1)

    fun readingTargets(currentIndex: Int, chapterCount: Int): List<Int> =
        targets(currentIndex, chapterCount, READING_OFFSETS)

    fun catalogNeighborTargets(targetIndex: Int, chapterCount: Int): List<Int> =
        targets(targetIndex, chapterCount, CATALOG_NEIGHBOR_OFFSETS)

    private fun targets(anchorIndex: Int, chapterCount: Int, offsets: List<Int>): List<Int> {
        if (anchorIndex < 0 || chapterCount <= 0 || anchorIndex >= chapterCount) return emptyList()
        return offsets.map { anchorIndex + it }.filter { it in 0 until chapterCount }.distinct()
    }
}
