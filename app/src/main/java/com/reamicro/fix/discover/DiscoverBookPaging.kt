package com.reamicro.fix.discover

internal const val DISCOVER_GRID_BATCH_SIZE = 21

internal data class DiscoverBookPage(
    val books: List<DiscoverBook> = emptyList(),
    val visibleCount: Int = 0,
    val sourcePage: Int = 0,
    val sourceHasMore: Boolean = true,
) {
    val state: DiscoverLoadState.Loaded
        get() = DiscoverLoadState.Loaded(books.take(visibleCount))
    val hasMore: Boolean
        get() = visibleCount < books.size || sourceHasMore
}

internal data class DiscoverBookPageResult(
    val page: DiscoverBookPage,
    val error: String? = null,
)

internal fun loadDiscoverBookPage(
    current: DiscoverBookPage = DiscoverBookPage(),
    grid: Boolean,
    isCancelled: () -> Boolean = { false },
    fetch: (Int) -> DiscoverLoadState,
): DiscoverBookPageResult? {
    val target = if (grid) {
        (current.visibleCount / DISCOVER_GRID_BATCH_SIZE + 1) * DISCOVER_GRID_BATCH_SIZE
    } else {

        maxOf(current.books.size, current.visibleCount + 1)
    }
    val books = current.books.toMutableList()
    val keys = books.mapTo(HashSet()) { it.key }
    var sourcePage = current.sourcePage
    var sourceHasMore = current.sourceHasMore
    var error: String? = null
    var requests = 0
    while (books.size < target && sourceHasMore && requests < MAX_SOURCE_PAGES_PER_BATCH) {
        if (isCancelled()) return null
        val next = sourcePage + 1
        val result = runCatching { fetch(next) }.getOrElse {
            DiscoverLoadState.Failed(it.message.orEmpty().ifBlank { it.javaClass.simpleName })
        }
        if (isCancelled()) return null
        requests += 1
        if (result !is DiscoverLoadState.Loaded) {
            error = (result as? DiscoverLoadState.Failed)?.message ?: "书源未返回书单"
            break
        }
        sourcePage = next
        sourceHasMore = result.books.isNotEmpty()
        result.books.forEach { if (keys.add(it.key)) books += it }
        if (!grid) break
    }
    if (isCancelled()) return null
    val visibleCount = if (grid) minOf(target, books.size) else books.size
    return DiscoverBookPageResult(
        DiscoverBookPage(books.toList(), visibleCount, sourcePage, sourceHasMore),
        error,
    )
}

private const val MAX_SOURCE_PAGES_PER_BATCH = 4
