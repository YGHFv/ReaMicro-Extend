package com.reamicro.fix.discover

/** 三列 × 七行。展示批次与书源自己的分页大小分开，不能把书源页码当展示页码。 */
internal const val DISCOVER_GRID_BATCH_SIZE = 21

/** 已取回的书全部保留；只有 visibleCount 以内的书交给 UI，其余留给下一展示批次。 */
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

/**
 * 网格每次展示到下一个 21 本边界；列表仍按书源原始页加载。
 * 不改写书源 URL/offset，按原始页码顺序取书并稳定去重，溢出数据留在缓存中。
 * 空页才代表结束；失败不推进失败页的页码，重试不会跳过书籍。
 * 限制单次补齐请求数，防止异常书源反复返回重复页而一直阻塞后台线程。
 */
internal fun loadDiscoverBookPage(
    current: DiscoverBookPage = DiscoverBookPage(),
    grid: Boolean,
    isCancelled: () -> Boolean = { false },
    fetch: (Int) -> DiscoverLoadState,
): DiscoverBookPageResult? {
    val target = if (grid) {
        (current.visibleCount / DISCOVER_GRID_BATCH_SIZE + 1) * DISCOVER_GRID_BATCH_SIZE
    } else {
        // 列表优先展示上次网格预取的剩余数据；没有缓冲时请求一个原始页。
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
