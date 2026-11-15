package com.reamicro.fix.association

import com.reamicro.fix.association.model.BookSearchResult
import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.association.model.ManualAssociationDraft
import com.reamicro.fix.association.model.withAllowedAssociationPlatform
import com.reamicro.fix.association.provider.AssociationSearchProviderRegistry
import com.reamicro.fix.association.provider.BookAssociationSearchProvider
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.TaskCancellation
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReferenceArray

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
        val window = SearchWindow(activeProviders.size, PROVIDER_WAIT_TIMEOUT_MS)
        return try {
            startProviders(window, activeProviders, keyword, limitPerSource) { _, _, _ -> }
            window.await()
            window.cancellation.check()
            orderedResults(window.results).orderExactTitleMatchesFirst(keyword)
        } finally {
            window.close()
        }
    }

    /**
     * Closing cancels workers and stops callback admission without waiting for an
     * already admitted callback. Callers keep their owner/generation check.
     */
    fun searchProgressively(
        keyword: String,
        limitPerSource: Int = 10,
        maxWaitMs: Long = PROVIDER_WAIT_TIMEOUT_MS,
        onProviderResults: (BookSource, List<BookSearchResult>, Long) -> Unit,
        onComplete: (List<BookSearchResult>, Long) -> Unit = { _, _ -> },
    ): AutoCloseable {
        val activeProviders = activeProviders()
        if (activeProviders.isEmpty()) {
            onComplete(emptyList(), 0L)
            return AutoCloseable { }
        }
        val window = SearchWindow(activeProviders.size, maxWaitMs)
        try {
            startProviders(window, activeProviders, keyword, limitPerSource, onProviderResults)
            ModuleResources.thread {
                try {
                    window.await()
                    if (window.complete()) {
                        window.cancellation.cancel()
                        runCatching {
                            onComplete(
                                orderedResults(window.results).orderExactTitleMatchesFirst(keyword),
                                window.elapsedMs(),
                            )
                        }
                    }
                } catch (_: CancellationException) {
                    // Caller cancellation ends the search without a completion callback.
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    window.close()
                }
            }.apply {
                name = "ReaMicroSearch-complete"
                isDaemon = true
                start()
            }
        } catch (failure: Throwable) {
            window.close()
            throw failure
        }
        return window
    }

    private fun startProviders(
        window: SearchWindow,
        activeProviders: List<BookAssociationSearchProvider>,
        keyword: String,
        limitPerSource: Int,
        onProviderResults: (BookSource, List<BookSearchResult>, Long) -> Unit,
    ) {
        activeProviders.forEachIndexed { index, provider ->
            ModuleResources.thread {
                val providerStartedAt = System.nanoTime()
                val interruption = window.cancellation.register(Thread.currentThread()::interrupt)
                try {
                    window.cancellation.run {
                        val providerResults = provider.search(keyword, providerFetchLimit(limitPerSource))
                            .sanitizePlatforms()
                            .rankForKeyword(keyword)
                            .take(limitPerSource.coerceAtLeast(1))
                        if (window.publish(index, providerResults) && providerResults.isNotEmpty()) {
                            onProviderResults(provider.source, providerResults,
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - providerStartedAt))
                        }
                    }
                } catch (_: CancellationException) {
                    // Expected when a query expires, its owner leaves, or a parent is cancelled.
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (failure: Throwable) {
                    if (window.acceptsCallbacks()) runCatching { onProviderError(provider.source, failure) }
                } finally {
                    interruption.close()
                    window.latch.countDown()
                }
            }.apply {
                name = "ReaMicroSearch-${provider.source.id}"
                isDaemon = true
                start()
            }
        }
    }

    private class SearchWindow(size: Int, maxWaitMs: Long) : AutoCloseable {
        val cancellation = TaskCancellation.current()?.child() ?: TaskCancellation()
        val results = AtomicReferenceArray<List<BookSearchResult>>(size)
        val latch = CountDownLatch(size)
        private val startedAt = System.nanoTime()
        private val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(maxWaitMs.coerceAtLeast(0L))
        private var open = true

        fun elapsedMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        @Synchronized fun acceptsCallbacks(): Boolean =
            open && !cancellation.isCancelled && System.nanoTime() - startedAt < timeoutNanos

        @Synchronized fun publish(index: Int, value: List<BookSearchResult>): Boolean {
            if (!acceptsCallbacks()) return false
            results.set(index, value)
            return true
        }

        fun await() {
            val interruption = cancellation.register(Thread.currentThread()::interrupt)
            try {
                cancellation.check()
                latch.await((timeoutNanos - (System.nanoTime() - startedAt)).coerceAtLeast(0L), TimeUnit.NANOSECONDS)
                cancellation.check()
            } finally {
                interruption.close()
            }
        }

        @Synchronized fun complete(): Boolean {
            if (!open || cancellation.isCancelled) return false
            open = false
            return true
        }

        override fun close() {
            synchronized(this) { open = false }
            // Do not hold the callback admission lock while disconnecting or interrupting IO.
            cancellation.cancel()
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
            maxOf(limit, minOf(MAX_PROVIDER_FETCH_LIMIT.toLong(), limit.toLong() * PROVIDER_FETCH_MULTIPLIER + 1).toInt())
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
