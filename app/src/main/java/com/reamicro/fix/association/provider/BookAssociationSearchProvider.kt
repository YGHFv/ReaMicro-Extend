package com.reamicro.fix.association.provider

import com.reamicro.fix.association.model.BookSearchResult
import com.reamicro.fix.association.model.BookSource

interface BookAssociationSearchProvider {
    val source: BookSource

    fun search(keyword: String, limit: Int = 20): List<BookSearchResult>
}
