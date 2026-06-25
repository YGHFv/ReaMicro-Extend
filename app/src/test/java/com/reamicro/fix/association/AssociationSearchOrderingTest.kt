package com.reamicro.fix.association

import com.reamicro.fix.association.model.BookSearchResult
import com.reamicro.fix.association.model.BookSource
import org.junit.Assert.assertEquals
import org.junit.Test

class AssociationSearchOrderingTest {
    @Test
    fun exactTitleMatchesMoveFirstAndKeepArrivalOrder() {
        val ordered = listOf(
            result("a", "The Book Side Story", BookSource.YouShu),
            result("b", "The Book", BookSource.WanFengLi),
            result("c", "Another Match", BookSource.Ciweimao),
            result("d", "the-book", BookSource.FanQie),
        ).orderExactTitleMatchesFirst("The Book")

        assertEquals(listOf("b", "d", "a", "c"), ordered.map { it.sourceBookId })
    }

    private fun result(
        id: String,
        title: String,
        source: BookSource,
    ): BookSearchResult =
        BookSearchResult(
            title = title,
            author = "author-$id",
            source = source,
            sourceBookId = id,
        )
}
