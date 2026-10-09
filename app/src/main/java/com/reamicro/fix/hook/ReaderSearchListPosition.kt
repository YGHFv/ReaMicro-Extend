package com.reamicro.fix.hook

internal data class ReaderSearchListPosition(
    val bookKey: String,
    val keyword: String,
    val index: Int,
    val offset: Int,
) {
    fun forSearch(book: String, query: String): ReaderSearchListPosition? =
        takeIf { bookKey == book && keyword == query }
}
