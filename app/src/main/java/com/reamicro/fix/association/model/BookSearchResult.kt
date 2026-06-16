package com.reamicro.fix.association.model

data class BookSearchResult(
    val title: String,
    val author: String,
    val source: BookSource,
    val sourceBookId: String,
    val coverUrl: String = "",
    val detailUrl: String = "",
    val intro: String = "",
    val words: String = "",
    val status: String = "",
    val displaySourceName: String = source.displayName,
    val tags: List<String> = emptyList(),
) {
    val stableId: String = "${source.id}:$sourceBookId"
}
