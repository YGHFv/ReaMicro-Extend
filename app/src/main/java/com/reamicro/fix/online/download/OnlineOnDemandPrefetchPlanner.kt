package com.reamicro.fix.online.download

internal object OnlineOnDemandPrefetchPlanner {

    val READING_OFFSETS = listOf(1)
    val CATALOG_NEIGHBOR_OFFSETS = emptyList<Int>()

    fun readingTargets(currentIndex: Int, chapterCount: Int): List<Int> =
        targets(currentIndex, chapterCount, READING_OFFSETS)

    fun catalogNeighborTargets(targetIndex: Int, chapterCount: Int): List<Int> =
        targets(targetIndex, chapterCount, CATALOG_NEIGHBOR_OFFSETS)

    fun itemRefIndexFromCfi(rawCfi: String): Int? {
        val step = Regex("""^epubcfi\(/\d+/(\d+)(?=[/\[),!])""")
            .find(rawCfi)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        return step.takeIf { it >= 0 && it % 2 == 0 }
    }

    fun itemRefPositions(indices: List<Int>): Map<Int, Int> {
        val positions = linkedMapOf<Int, Int>()
        val ambiguous = mutableSetOf<Int>()
        indices.forEachIndexed { position, index ->
            if (index < 0 || index % 2 != 0 || index in ambiguous) return@forEachIndexed
            if (positions.putIfAbsent(index, position) != null) {
                positions.remove(index)
                ambiguous += index
            }
        }
        return positions
    }

    fun hostSpinePositionFromCfi(rawCfi: String, positions: Map<Int, Int>): Int? =
        itemRefIndexFromCfi(rawCfi)?.let(positions::get)

    private fun targets(anchorIndex: Int, chapterCount: Int, offsets: List<Int>): List<Int> {
        if (anchorIndex < 0 || chapterCount <= 0 || anchorIndex >= chapterCount) return emptyList()
        return offsets.map { anchorIndex + it }.filter { it in 0 until chapterCount }.distinct()
    }
}
