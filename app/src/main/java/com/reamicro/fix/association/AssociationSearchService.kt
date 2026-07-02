package com.reamicro.fix.association

import com.reamicro.fix.association.model.BookSearchResult
import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.association.model.ManualAssociationDraft
import com.reamicro.fix.association.model.withAllowedAssociationPlatform
import com.reamicro.fix.association.provider.AssociationSearchProviderRegistry
import com.reamicro.fix.association.provider.BookAssociationSearchProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Runs all enabled association providers in parallel and normalizes their results before
 * they are injected into the host BookPublish screen.
 */
class AssociationSearchService(
    private val providersProvider: () -> List<BookAssociationSearchProvider> = {
        AssociationSearchProviderRegistry.providers()
    },
    private val enabledSourcesProvider: () -> Set<BookSource>? = { null },
    private val manualAssociationService: ManualAssociationService = ManualAssociationService(),
    private val onProviderError: (BookSource, Throwable) -> Unit = { _, _ -> },
) {
    constructor(
        providers: List<BookAssociationSearchProvider>,
        enabledSourcesProvider: () -> Set<BookSource>? = { null },
        manualAssociationService: ManualAssociationService = ManualAssociationService(),
        onProviderError: (BookSource, Throwable) -> Unit = { _, _ -> },
    ) : this(
        providersProvider = { providers },
        enabledSourcesProvider = enabledSourcesProvider,
        manualAssociationService = manualAssociationService,
        onProviderError = onProviderError,
    )

    fun search(keyword: String, limitPerSource: Int = 10): List<BookSearchResult> {
        val activeProviders = activeProviders()
        if (activeProviders.isEmpty()) return emptyList()
        // Preserve provider order in the final merge while still letting slow/failing sources
        // run independently.
        val resultsByProvider = AtomicReferenceArray<List<BookSearchResult>>(activeProviders.size)
        val latch = CountDownLatch(activeProviders.size)
        activeProviders.forEachIndexed { index, provider ->
            Thread {
                try {
                    runCatching {
                        provider.search(keyword, providerFetchLimit(limitPerSource))
                    }.onSuccess { providerResults ->
                        resultsByProvider.set(
                            index,
                            providerResults.sanitizePlatforms()
                                .rankForKeyword(keyword)
                                .take(limitPerSource.coerceAtLeast(1)),
                        )
                    }.onFailure {
                        onProviderError(provider.source, it)
                    }
                } finally {
                    latch.countDown()
                }
            }.apply {
                name = "ReaMicroSearch-${provider.source.id}"
                isDaemon = true
                start()
            }
        }
        latch.await(PROVIDER_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return orderedResults(resultsByProvider).orderExactTitleMatchesFirst(keyword)
    }

    fun searchProgressively(
        keyword: String,
        limitPerSource: Int = 10,
        maxWaitMs: Long = PROVIDER_WAIT_TIMEOUT_MS,
        onProviderResults: (BookSource, List<BookSearchResult>, Long) -> Unit,
        onComplete: (List<BookSearchResult>, Long) -> Unit = { _, _ -> },
    ) {
        val startedAt = System.currentTimeMillis()
        val activeProviders = activeProviders()
        if (activeProviders.isEmpty()) {
            onComplete(emptyList(), 0L)
            return
        }
        val resultsByProvider = AtomicReferenceArray<List<BookSearchResult>>(activeProviders.size)
        val latch = CountDownLatch(activeProviders.size)
        activeProviders.forEachIndexed { index, provider ->
            Thread {
                val providerStartedAt = System.currentTimeMillis()
                try {
                    runCatching {
                        provider.search(keyword, providerFetchLimit(limitPerSource))
                            .sanitizePlatforms()
                            .rankForKeyword(keyword)
                            .take(limitPerSource.coerceAtLeast(1))
                    }.onSuccess { providerResults ->
                        resultsByProvider.set(index, providerResults)
                        if (providerResults.isNotEmpty()) {
                            runCatching {
                                onProviderResults(
                                    provider.source,
                                    providerResults,
                                    System.currentTimeMillis() - providerStartedAt,
                                )
                            }.onFailure {
                                onProviderError(provider.source, it)
                            }
                        }
                    }.onFailure {
                        onProviderError(provider.source, it)
                    }
                } finally {
                    latch.countDown()
                }
            }.apply {
                name = "ReaMicroSearch-${provider.source.id}"
                isDaemon = true
                start()
            }
        }
        Thread {
            latch.await(maxWaitMs, TimeUnit.MILLISECONDS)
            runCatching {
                onComplete(
                    orderedResults(resultsByProvider).orderExactTitleMatchesFirst(keyword),
                    System.currentTimeMillis() - startedAt,
                )
            }
        }.apply {
            name = "ReaMicroSearch-complete"
            isDaemon = true
            start()
        }
    }

    fun searchWithManualCandidate(
        keyword: String,
        manualDraft: ManualAssociationDraft?,
        limitPerSource: Int = 10,
    ): List<BookSearchResult> {
        val results = search(keyword, limitPerSource).toMutableList()
        if (manualDraft != null && manualAssociationService.validate(manualDraft).isValid) {
            results.add(0, manualAssociationService.buildCandidate(manualDraft).toSearchResult())
        }
        return results.distinctBy { it.stableId }
    }

    private fun activeProviders(): List<BookAssociationSearchProvider> {
        val enabledSources = enabledSourcesProvider() ?: return providers
        val providers = providersProvider()
        return providers.filter { it.source in enabledSources }
    }

    private val providers: List<BookAssociationSearchProvider>
        get() = providersProvider()

    private fun List<BookSearchResult>.sanitizePlatforms(): List<BookSearchResult> =
        mapNotNull { it.withAllowedAssociationPlatform() }.distinctBy { it.stableId }

    private fun List<BookSearchResult>.rankForKeyword(keyword: String): List<BookSearchResult> {
        val normalizedKeyword = keyword.normalizedAssociationSearchKey()
        if (normalizedKeyword.isBlank()) return this
        return mapIndexed { index, result -> index to result }
            .sortedWith(
                compareBy<Pair<Int, BookSearchResult>> { (_, result) ->
                    result.searchRank(normalizedKeyword)
                }.thenBy { it.first },
            )
            .map { it.second }
    }

    private fun BookSearchResult.searchRank(normalizedKeyword: String): Int {
        val titleKey = title.normalizedAssociationSearchKey()
        val authorKey = author.normalizedAssociationSearchKey()
        return when {
            titleKey == normalizedKeyword -> 0
            titleKey.contains(normalizedKeyword) -> 1
            normalizedKeyword.contains(titleKey) && titleKey.isNotBlank() -> 2
            authorKey == normalizedKeyword -> 3
            authorKey.contains(normalizedKeyword) -> 4
            normalizedKeyword.contains(authorKey) && authorKey.isNotBlank() -> 5
            else -> 6
        }
    }

    private fun providerFetchLimit(limitPerSource: Int): Int =
        limitPerSource.coerceAtLeast(1).let { limit ->
            maxOf(limit, minOf(MAX_PROVIDER_FETCH_LIMIT, limit * PROVIDER_FETCH_MULTIPLIER + 1))
        }

    private fun orderedResults(resultsByProvider: AtomicReferenceArray<List<BookSearchResult>>): List<BookSearchResult> =
        buildList {
            for (index in 0 until resultsByProvider.length()) {
                addAll(resultsByProvider.get(index).orEmpty())
            }
        }.distinctBy { it.stableId }

    private companion object {
        const val PROVIDER_WAIT_TIMEOUT_MS = 4_500L
        const val PROVIDER_FETCH_MULTIPLIER = 3
        const val MAX_PROVIDER_FETCH_LIMIT = 20
    }
}
