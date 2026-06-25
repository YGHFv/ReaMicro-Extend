package com.reamicro.fix.association

import com.reamicro.fix.association.model.BookSearchResult
import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.association.model.ManualAssociationDraft
import com.reamicro.fix.association.provider.BookAssociationSearchProvider
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationSearchServiceTest {
    @Test
    fun searchIgnoresFailingProvidersAndDeduplicatesResults() {
        val service = AssociationSearchService(
            providers = listOf(
                resultProvider("1"),
                failingProvider(),
                resultProvider("1"),
                resultProvider("2"),
            ),
        )

        val results = service.search("keyword")

        assertEquals(listOf("1", "2"), results.map { it.sourceBookId })
    }

    @Test
    fun searchWithManualCandidatePrependsValidManualDraft() {
        val service = AssociationSearchService(providers = listOf(resultProvider("remote")))

        val results = service.searchWithManualCandidate(
            keyword = "keyword",
            manualDraft = ManualAssociationDraft(
                title = "手动书",
                author = "手动作者",
                source = BookSource.QiDian,
            ),
        )

        assertEquals("手动书", results.first().title)
        assertEquals("remote", results[1].sourceBookId)
    }

    @Test
    fun searchProgressivelyEmitsFastProviderBeforeSlowProviderFinishes() {
        val service = AssociationSearchService(
            providers = listOf(
                delayedProvider("slow", delayMs = 900),
                resultProvider("fast"),
            ),
        )
        val fastResultSeen = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val receivedIds = Collections.synchronizedList(mutableListOf<String>())

        service.searchProgressively(
            keyword = "keyword",
            maxWaitMs = 1_500,
            onProviderResults = { _, results, _ ->
                receivedIds.addAll(results.map { it.sourceBookId })
                if (results.any { it.sourceBookId == "fast" }) {
                    fastResultSeen.countDown()
                }
            },
            onComplete = { _, _ -> completed.countDown() },
        )

        assertTrue(fastResultSeen.await(300, TimeUnit.MILLISECONDS))
        assertEquals(listOf("fast"), receivedIds.toList())
        assertTrue(completed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun searchRanksExactTitleIntoLimitedResultsAfterFetchingExtraRows() {
        val keyword = "我真没想重生啊"
        val provider = object : BookAssociationSearchProvider {
            override val source: BookSource = BookSource.WanFengLi

            override fun search(keyword: String, limit: Int): List<BookSearchResult> =
                (1..10).map { index ->
                    result(
                        title = "作者命中结果$index",
                        author = keyword,
                        id = "author-$index",
                        displaySourceName = BookSource.SFAcg.displayName,
                        source = source,
                    )
                }.plus(
                    result(
                        title = keyword,
                        author = "柳岸花又明",
                        id = "1015648531",
                        displaySourceName = BookSource.QiDian.displayName,
                        source = source,
                    ),
                ).take(limit)
        }
        val service = AssociationSearchService(providers = listOf(provider))

        val results = service.search(keyword, limitPerSource = 10)

        assertEquals(10, results.size)
        assertEquals(keyword, results.first().title)
        assertTrue(results.any { it.sourceBookId == "1015648531" })
    }

    @Test
    fun searchMovesExactTitleMatchesAheadOfEarlierNonExactProviders() {
        val service = AssociationSearchService(
            providers = listOf(
                staticProvider(
                    source = BookSource.Ciweimao,
                    result(
                        title = "The Book Side Story",
                        author = "author",
                        id = "non-exact",
                        displaySourceName = BookSource.Ciweimao.displayName,
                        source = BookSource.Ciweimao,
                    ),
                ),
                staticProvider(
                    source = BookSource.FanQie,
                    result(
                        title = "The Book",
                        author = "author",
                        id = "exact",
                        displaySourceName = BookSource.FanQie.displayName,
                        source = BookSource.FanQie,
                    ),
                ),
            ),
        )

        val results = service.search("The Book", limitPerSource = 10)

        assertEquals(listOf("exact", "non-exact"), results.map { it.sourceBookId })
    }

    private fun resultProvider(id: String): BookAssociationSearchProvider = object : BookAssociationSearchProvider {
        override val source: BookSource = BookSource.Ciweimao

        override fun search(keyword: String, limit: Int): List<BookSearchResult> = listOf(
            BookSearchResult(
                title = "title-$id",
                author = "author-$id",
                source = source,
                sourceBookId = id,
            ),
        )
    }

    private fun failingProvider(): BookAssociationSearchProvider = object : BookAssociationSearchProvider {
        override val source: BookSource = BookSource.Ciyuanji

        override fun search(keyword: String, limit: Int): List<BookSearchResult> {
            error("network failed")
        }
    }

    private fun delayedProvider(id: String, delayMs: Long): BookAssociationSearchProvider = object : BookAssociationSearchProvider {
        override val source: BookSource = BookSource.YouShu

        override fun search(keyword: String, limit: Int): List<BookSearchResult> {
            Thread.sleep(delayMs)
            return listOf(
                BookSearchResult(
                    title = "title-$id",
                    author = "author-$id",
                    source = source,
                    sourceBookId = id,
                ),
            )
        }
    }

    private fun staticProvider(
        source: BookSource,
        vararg results: BookSearchResult,
    ): BookAssociationSearchProvider = object : BookAssociationSearchProvider {
        override val source: BookSource = source

        override fun search(keyword: String, limit: Int): List<BookSearchResult> =
            results.toList().take(limit)
    }

    private fun result(
        title: String,
        author: String,
        id: String,
        displaySourceName: String,
        source: BookSource,
    ): BookSearchResult =
        BookSearchResult(
            title = title,
            author = author,
            source = source,
            sourceBookId = id,
            displaySourceName = displaySourceName,
        )
}
