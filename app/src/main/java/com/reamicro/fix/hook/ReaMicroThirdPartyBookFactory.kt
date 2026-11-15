package com.reamicro.fix.hook

import com.reamicro.fix.core.HostAssociationBindings


import com.reamicro.fix.association.model.BookSearchResult
import com.reamicro.fix.association.model.ManualAssociationCandidate

class ReaMicroThirdPartyBookFactory(
    private val classLoader: ClassLoader,
) {
    fun create(candidate: ManualAssociationCandidate, coverFallback: String = ""): Any {
        val cover = candidate.coverUrl.ifBlank { coverFallback }
        return createThirdPartyBook(
            title = candidate.title,
            author = candidate.author,
            alias = candidate.title,
            intro = candidate.intro,
            cover = cover,
            publisher = candidate.source.displayName,
            words = candidate.words,
            detail = "",
            rating = 0.0,
            status = candidate.status,
        )
    }

    fun create(result: BookSearchResult): Any {
        return createThirdPartyBook(
            title = result.title,
            author = result.author,
            alias = result.title,
            intro = result.intro,
            cover = result.coverUrl,
            publisher = result.displaySourceName,
            words = result.words,
            detail = result.detailUrl,
            rating = 0.0,
            status = result.status.ifBlank { result.tags.joinToString(" / ") },
        )
    }

    fun createThirdPartyGroup(publisher: String, books: List<Any>): Any {
        return host.create("group", mapOf("publisher" to publisher, "books" to books))
    }
    private val host get() = requireNotNull(HostAssociationBindings.current) { "Association contract unavailable" }
    private fun createThirdPartyBook(
        title: String,
        author: String,
        alias: String,
        intro: String,
        cover: String,
        publisher: String,
        words: String,
        detail: String,
        rating: Double,
        status: String,
    ): Any {
        return host.create("candidate", mapOf(
            "title" to title, "author" to author, "alias" to alias, "subtitle" to "", "original" to "",
            "intro" to intro, "cover" to cover, "translator" to "", "publisher" to publisher,
            "publishDate" to "", "price" to "", "isbn" to "", "words" to words,
            "detail" to detail, "rating" to rating, "status" to status,
        ))
    }
}
