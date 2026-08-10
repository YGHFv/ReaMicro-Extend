package com.reamicro.fix.online.download

internal object OnlineOnDemandPrefetchPlanner {
    // 阅读中只预取下一章；目录跳转完成后由 Statistics 统一触发，避免同一时刻并发请求邻近章节。
    val READING_OFFSETS = listOf(1)
    val CATALOG_NEIGHBOR_OFFSETS = emptyList<Int>()

    fun readingTargets(currentIndex: Int, chapterCount: Int): List<Int> =
        targets(currentIndex, chapterCount, READING_OFFSETS)

    fun catalogNeighborTargets(targetIndex: Int, chapterCount: Int): List<Int> =
        targets(targetIndex, chapterCount, CATALOG_NEIGHBOR_OFFSETS)

    /** EPUB CFI 的 itemref 步进为 2：/6/2 是第 1 章，/6/12 是第 6 章。 */
    fun chapterIndexFromCfi(rawCfi: String, chapterCount: Int): Int? {
        if (chapterCount <= 0) return null
        val itemRefStep = Regex("""epubcfi\(/\d+/(\d+)""")
            .find(rawCfi)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: return null
        if (itemRefStep < 2 || itemRefStep % 2 != 0) return null
        return (itemRefStep / 2 - 1).takeIf { it in 0 until chapterCount }
    }

    fun hostSpineIndexFromCfi(rawCfi: String): Int? {
        val itemRefStep = Regex("""epubcfi\(/\d+/(\d+)""")
            .find(rawCfi)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: return null
        return (itemRefStep - 2).takeIf { it >= 0 && itemRefStep % 2 == 0 }
    }

    private fun targets(anchorIndex: Int, chapterCount: Int, offsets: List<Int>): List<Int> {
        if (anchorIndex < 0 || chapterCount <= 0 || anchorIndex >= chapterCount) return emptyList()
        return offsets.map { anchorIndex + it }.filter { it in 0 until chapterCount }.distinct()
    }
}
