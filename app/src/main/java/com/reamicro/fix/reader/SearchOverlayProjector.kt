package com.reamicro.fix.reader

class SearchOverlayProjector(set: SearchHighlightSet, keywordOverride: String? = null) {
    data class PaintRange(val start: Int, val end: Int, val active: Boolean)
    private data class Target(val match: SearchHighlightSet.Match,
                              val start: SearchCfiAddress.Point, val end: SearchCfiAddress.Point)
    private val targets = set.matches.mapNotNull { match ->
        val start = SearchCfiAddress.parse(match.draft.startCfi) ?: return@mapNotNull null
        val end = SearchCfiAddress.parse(match.draft.endCfi) ?: return@mapNotNull null
        Target(match, start, end)
    }.groupBy { it.start.spineKey }
    private val keyword = keywordOverride ?: set.matches.firstOrNull { it.active }?.draft?.quote
        ?: set.matches.firstOrNull()?.draft?.quote.orEmpty()

    private data class Window(val content: String, val start: Int, val end: Int)
    private var passiveCache = LinkedHashMap<Window, List<Int>>()
    fun reusePassiveScans(previous: SearchOverlayProjector) {
        if (keyword == previous.keyword) passiveCache = previous.passiveCache
    }
    private fun occurrences(content: String, start: Int, end: Int): List<Int> {
        val key = Window(content, start, end)
        synchronized(passiveCache) {
            passiveCache[key]?.let { return it }
            val window = content.substring(start, end)
            val offsets = ArrayList<Int>()
            var index = window.indexOf(keyword, ignoreCase = true)
            while (index >= 0) {
                offsets += start + index
                index = window.indexOf(keyword, index + keyword.length, ignoreCase = true)
            }

            if (content.length <= 1_000_000 && offsets.size <= 4096) {
                passiveCache[key] = offsets
                if (passiveCache.size > 4) passiveCache.remove(passiveCache.keys.first())
            }
            return offsets
        }
    }
    fun project(location: String, content: String, windowStart: Int, windowEnd: Int,
                renderedLength: Int): List<PaintRange> {
        if (keyword.isEmpty() || content.isEmpty() || renderedLength <= 0) return emptyList()
        val node = SearchCfiAddress.parse(location) ?: return emptyList()

        val from = (windowStart.toLong() - node.offset).coerceIn(0, content.length.toLong()).toInt()
        val to = minOf((windowEnd.toLong() - node.offset).coerceIn(from.toLong(), content.length.toLong()),
            from.toLong() + renderedLength).toInt()
        if (from >= to) return emptyList()
        val ranges = ArrayList<PaintRange>()
        fun add(start: Int, end: Int, active: Boolean) {
            val left = maxOf(from, start); val right = minOf(to, end)
            if (left < right) ranges += PaintRange(left - from, right - from, active)
        }
        for (target in targets[node.spineKey].orEmpty()) {
            val sameStart = SearchCfiAddress.sameNode(target.start, node)
            val sameEnd = SearchCfiAddress.sameNode(target.end, node)
            if (!sameStart && !sameEnd) continue
            val quote = target.match.draft.quote
            if (quote.isEmpty()) continue

            val expected = if (sameStart) target.start.offset - node.offset
                else target.end.offset - node.offset - quote.length
            val start = expected
            fun matchesAt(position: Int): Boolean {
                val left = maxOf(from, position, 0)
                val right = minOf(to.toLong(), position.toLong() + quote.length, content.length.toLong()).toInt()
                return left < right && left - position >= 0 &&
                    content.regionMatches(left, quote, left - position, right - left, ignoreCase = true)
            }

            if (!matchesAt(start)) continue
            add(start, (start.toLong() + quote.length).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), target.match.active)
        }

        val scanStart = (from.toLong() - keyword.length + 1).coerceAtLeast(0).toInt()
        val scanEnd = minOf(content.length.toLong(), to.toLong() + keyword.length - 1).toInt()
        for (index in occurrences(content, scanStart, scanEnd)) add(index, index + keyword.length, false)
        val active = ranges.filter { it.active }.distinctBy { it.start to it.end }
        val passive = ranges.filterNot { it.active }.distinctBy { it.start to it.end }
            .filter { p -> active.none { a -> p.start < a.end && a.start < p.end } }
        return passive + active
    }
}
