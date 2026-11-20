package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread

import com.reamicro.fix.core.HostAssociationBindings
import com.reamicro.fix.reload.ModuleResources


import android.app.Activity
import android.os.Looper
import com.reamicro.fix.association.AssociationBookIdentity
import com.reamicro.fix.association.AssociationOwnedState
import com.reamicro.fix.association.AssociationSearchService
import com.reamicro.fix.association.model.BookSearchResult
import com.reamicro.fix.association.normalizedAssociationSearchKey
import com.reamicro.fix.association.normalizedAssociationTitleKey
import com.reamicro.fix.association.orderAssociationMatches
import com.reamicro.fix.association.provider.AssociationSearchProviderRegistry
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

class AssociationSearchHook(
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot,
) {
    private val host get() = requireNotNull(HostAssociationBindings.current) { "Association contract unavailable" }
    private val thirdPartyBookFactory = ReaMicroThirdPartyBookFactory()
    private val searchGeneration = AtomicInteger(0)
    private val searchResultsLock = Any()
    private var activeSearch: AutoCloseable? = null
    @Volatile private var publishFlowRef: WeakReference<Any>? = null
    private data class Owner(
        val activity: WeakReference<Activity>, val identity: AccountHostRuntime.Identity,
        val flow: WeakReference<Any>, val book: AssociationBookIdentity,
        val title: String, val author: String,
    )
    @Volatile private var observedOwner: Owner? = null
    private val publishing = ThreadLocal<Boolean>()
    @Volatile private var latestSearchResults: List<BookSearchResult> = emptyList()
    @Volatile private var holdSearchingGeneration: Int = 0

    private val associationSearchService = AssociationSearchService(
        providersProvider = {
            AssociationSearchProviderRegistry.providers(activityProvider()?.applicationContext)
        },
        enabledSourcesProvider = {
            val snapshot = settingsProvider()
            if (!snapshot.canRunAssociation) {
                emptySet()
            } else {
                val context = activityProvider()?.applicationContext
                snapshot.enabledAssociationSearchSources(
                    AssociationSearchProviderRegistry.searchSourceGroups(context),
                )
            }
        },
        onProviderError = { source, error ->
            HookRuntime.log("$LOG_PREFIX ${source.displayName} search failed: ${error.message}")
        },
    )

    fun install() {
        AssociationNetworkHook.install()
        HookInstallReport.install("AssociationSearchHook", "dynamicPublishState") {
            val host = host
            val handles = mutableListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
            try {
                handles += HookRuntime.hookMethod(host.method("get"), object : HookCallback() {
                    override fun after(param: Call) {
                        val state = param.result ?: return
                        val flow = param.thisObject ?: return
                        if (publishing.get() == true || param.throwable != null || !host.type("state").isInstance(state)) return
                        // Existing pages can supply their native flow after hot reload, on main.
                        if (publishFlowRef?.get() == null && Looper.myLooper() == Looper.getMainLooper() &&
                            captureOwner(flow, state) != null) captureFlow(flow)
                        if (publishFlowRef?.get() === flow) observeBook(flow, state)
                    }
                })
                fun observer(nextIndex: Int, compareAndSet: Boolean) = object : HookCallback() {
                    override fun after(param: Call) {
                        if (publishing.get() == true || param.throwable != null || (compareAndSet && param.result != true)) return
                        val flow = param.thisObject ?: return
                        val next = param.args?.getOrNull(nextIndex) ?: return
                        if (!host.type("state").isInstance(next)) return
                        if (publishFlowRef?.get() == null && Looper.myLooper() == Looper.getMainLooper() &&
                            captureOwner(flow, next) != null) captureFlow(flow)
                        if (publishFlowRef?.get() !== flow) return
                        observeBook(flow, next)
                        val owner = observedOwner ?: return
                        if (isOwnerCurrent(owner) && matchesBook(owner, next)) runCatching {
                            updateState(flow, owner) { reconcileSearchState(it, owner) }
                        }.onFailure { HookRuntime.logError("ReaMicro association source refresh stopped", it) }
                    }
                }
                handles += HookRuntime.hookMethod(host.method("cas"), observer(1, true))
                handles += HookRuntime.hookMethod(host.method("set"), observer(0, false))
                val vmCapture = object : HookCallback() {
                    override fun after(param: Call) {
                        if (param.throwable != null || publishing.get() == true ||
                            Looper.myLooper() != Looper.getMainLooper()) return
                        val vm = param.thisObject ?: return
                        if (!host.type("vm").isInstance(vm)) return
                        val flow = host.field("vm.flow").get(vm) ?: return
                        val state = host.method("get").invoke(flow) ?: return
                        if (captureOwner(flow, state) == null) return
                        if (publishFlowRef?.get() !== flow) captureFlow(flow)
                        observeBook(flow, state)
                    }
                }
                host.vmConstructors().forEach { handles += HookRuntime.hookMethod(it, vmCapture) }
                handles += HookRuntime.hookMethod(host.method("current"), vmCapture)
                handles += HookRuntime.hookMethod(host.method("cleared"), object : HookCallback() {
                    override fun after(param: Call) {
                        val vm = param.thisObject ?: return
                        if (host.field("vm.flow").get(vm) === publishFlowRef?.get()) {
                            publishFlowRef = null; observedOwner = null
                            clearLatestSearchResults()
                        }
                    }
                })
                ModuleResources.cleanup {
                    publishFlowRef = null; observedOwner = null
                    clearLatestSearchResults()
                    publishing.remove()
                }
            } catch (error: Throwable) {
                handles.asReversed().forEach { it.unhook() }
                throw error
            }
        }
    }

    private fun bookIdentity(state: Any): AssociationBookIdentity? = runCatching {
        val book = host.property("state", "book", state) ?: return@runCatching null
        AssociationBookIdentity((host.property("book", "uid", book) as Number).toLong(),
            (host.property("book", "id", book) as Number).toLong(), host.property("book", "uuid", book) as String)
    }.getOrNull()

    private fun captureOwner(flow: Any, state: Any): Owner? {
        if (!ModuleResources.accepting || !host.type("state").isInstance(state)) return null
        val activity = activityProvider()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return null
        val identity = AccountHostRuntime.storageSnapshot(activity) ?: return null
        val key = bookIdentity(state)?.takeIf { it.uid == identity.accountId } ?: return null
        val book = host.property("state", "book", state) ?: return null
        val title = (host.property("book", "title", book) as String).trim()
        val author = (host.property("book", "author", book) as String).trim()
        if (!AccountHostRuntime.isCurrentStorage(identity, activity)) return null
        return Owner(WeakReference(activity), identity, WeakReference(flow), key, title, author)
    }

    private fun sameOwner(a: Owner, b: Owner): Boolean =
        a.activity.get() === b.activity.get() && a.flow.get() === b.flow.get() &&
            a.identity.accountId == b.identity.accountId && a.identity.generation == b.identity.generation &&
            a.book == b.book && a.title == b.title && a.author == b.author

    private fun isOwnerCurrent(owner: Owner): Boolean {
        val activity = owner.activity.get() ?: return false
        return ModuleResources.accepting && isObservedOwner(owner) &&
            activityProvider() === activity && !activity.isFinishing && !activity.isDestroyed &&
            AccountHostRuntime.isCurrentStorage(owner.identity, activity)
    }

    // Cache sections must not acquire the account lock: native CAS observers run under it.
    private fun isObservedOwner(owner: Owner): Boolean =
        observedOwner === owner && publishFlowRef?.get() === owner.flow.get()

    private fun matchesBook(owner: Owner, state: Any): Boolean {
        if (bookIdentity(state) != owner.book) return false
        val book = host.property("state", "book", state) ?: return false
        return (host.property("book", "title", book) as String).trim() == owner.title &&
            (host.property("book", "author", book) as String).trim() == owner.author
    }

    private fun captureFlow(flow: Any) {
        if (publishFlowRef?.get() === flow) return
        val previous = synchronized(searchResultsLock) {
            if (publishFlowRef?.get() === flow) return
            val previous = invalidateSearchLocked()
            observedOwner = null
            publishFlowRef = WeakReference(flow)
            previous
        }
        closeSearch(previous)
    }

    private fun observeBook(flow: Any, state: Any) {
        val next = captureOwner(flow, state)
        if (next == null) {
            val previous = synchronized(searchResultsLock) {
                if (publishFlowRef?.get() === flow && observedOwner != null) {
                    observedOwner = null
                    invalidateSearchLocked()
                } else null
            }
            closeSearch(previous)
            return
        }
        val previous = synchronized(searchResultsLock) {
            if (observedOwner?.let { sameOwner(it, next) } == true) return
            if (publishFlowRef?.get() !== flow) return
            val previous = invalidateSearchLocked()
            observedOwner = next
            previous
        }
        closeSearch(previous)
        next.activity.get()?.runOnModuleUiThread {
            if (!isOwnerCurrent(next)) return@runOnModuleUiThread
            val settings = settingsProvider()
            if (next.title.isNotBlank() && settings.canRunAssociation && settings.canRunAssociationSearch) refreshSearchResults(flow, next)
            else injectSearchGroups(flow, next)
        }
    }

    private fun updateState(flow: Any, owner: Owner, reducer: (Any) -> Any): Boolean {
        val activity = owner.activity.get() ?: return false
        val previousPublishing = publishing.get()
        publishing.set(true)
        return try {
            AssociationOwnedState.update(
                isCurrent = { isOwnerCurrent(owner) && owner.flow.get() === flow },
                read = { host.method("get").invoke(flow)?.takeIf(host.type("state")::isInstance) },
                matches = { matchesBook(owner, it) },
                reduce = reducer,
                compareAndSet = { before, after -> host.method("cas").invoke(flow, before, after) == true },
                commit = { action -> AccountHostRuntime.withCurrentStorage(owner.identity, activity) { action() } },
            )
        } finally {
            if (previousPublishing == null) publishing.remove() else publishing.set(previousPublishing)
        }
    }

    private fun refreshSearchResults(flow: Any, owner: Owner) {
        if (!isOwnerCurrent(owner)) return
        val (generation, previous) = synchronized(searchResultsLock) {
            if (!isObservedOwner(owner)) return
            val previous = invalidateSearchLocked()
            val generation = searchGeneration.get().also { holdSearchingGeneration = it }
            generation to previous
        }
        closeSearch(previous)
        injectSearchGroups(flow, owner)
        setSearchingState(flow, owner, true)
        if (!isOwnerCurrent(owner) || generation != searchGeneration.get()) return
        val search = associationSearchService.searchProgressively(
            keyword = owner.title,
            limitPerSource = 10,
            maxWaitMs = SOURCE_SEARCH_WAIT_TIMEOUT_MS,
            onProviderResults = { source, results, elapsedMs ->
                if (!isOwnerCurrent(owner) || generation != searchGeneration.get()) return@searchProgressively
                synchronized(searchResultsLock) {
                    if (!isObservedOwner(owner) || generation != searchGeneration.get()) return@searchProgressively
                    latestSearchResults = (latestSearchResults + results).distinctBy { it.stableId }
                        .orderAssociationMatches(owner.title, owner.author)
                }
                owner.activity.get()?.runOnModuleUiThread {
                    if (isOwnerCurrent(owner) && generation == searchGeneration.get()) injectSearchGroups(flow, owner)
                }
                HookRuntime.log("$LOG_PREFIX ${source.displayName} source results: ${results.size} in ${elapsedMs}ms")
            },
            onComplete = { results, elapsedMs ->
                if (!isOwnerCurrent(owner) || generation != searchGeneration.get()) return@searchProgressively
                HookRuntime.log("$LOG_PREFIX association source search window: ${results.size} results in ${elapsedMs}ms")
                owner.activity.get()?.runOnModuleUiThread {
                    if (!isOwnerCurrent(owner) || generation != searchGeneration.get()) return@runOnModuleUiThread
                    synchronized(searchResultsLock) {
                        if (!isObservedOwner(owner) || generation != searchGeneration.get()) return@runOnModuleUiThread
                        holdSearchingGeneration = 0
                    }
                    setSearchingState(flow, owner, false)
                }
            },
        )
        val accepted = synchronized(searchResultsLock) {
            if (isObservedOwner(owner) && generation == searchGeneration.get()) {
                activeSearch = search
                true
            } else false
        }
        if (!accepted) closeSearch(search)
    }

    private fun clearLatestSearchResults() {
        val previous = synchronized(searchResultsLock) { invalidateSearchLocked() }
        closeSearch(previous)
    }

    /** Called with searchResultsLock; closing network work happens after releasing it. */
    private fun invalidateSearchLocked(): AutoCloseable? {
        searchGeneration.incrementAndGet()
        holdSearchingGeneration = 0
        latestSearchResults = emptyList()
        return activeSearch.also { activeSearch = null }
    }

    private fun closeSearch(search: AutoCloseable?) {
        runCatching { search?.close() }
            .onFailure { HookRuntime.logError("$LOG_PREFIX association search cancellation failed", it) }
    }

    private fun injectSearchGroups(flow: Any, owner: Owner) {
        runCatching { updateState(flow, owner) { reconcileSearchState(it, owner) } }
            .onFailure { HookRuntime.logError("ReaMicro association source injection stopped", it) }
    }

    private fun buildSearchGroups(owner: Owner): List<SearchGroup> {
        val snapshot = settingsProvider()
        if (!snapshot.canRunAssociationSearch || !isOwnerCurrent(owner)) return emptyList()
        val results = synchronized(searchResultsLock) {
            if (!isObservedOwner(owner)) return emptyList()
            latestSearchResults
        }
        val groupedBooks = linkedMapOf<String, MutableList<Any>>()
        results.orderAssociationMatches(owner.title, owner.author)
            .filter { snapshot.isSearchSourceEnabled(it.source) }
            .forEach { result ->
                runCatching {
                    groupedBooks.getOrPut(groupPublisher(result.source.displayName)) { mutableListOf() }
                        .add(thirdPartyBookFactory.create(result))
                }.onFailure { HookRuntime.log("$LOG_PREFIX failed to build source result: ${it.message}") }
            }
        return groupedBooks.map { (publisher, books) -> SearchGroup(publisher, books) }
    }

    private fun appendSearchGroups(state: Any, groups: List<SearchGroup>, owner: Owner): Any = runCatching {
        // Native search/association states use null before their candidate list is ready.
        val thirdPartyRaw = host.property("state", "thirdPartyBooks", state) as? List<*> ?: emptyList<Any>()
        val thirdPartyNotOurs = thirdPartyRaw.filterNotNull().filter { group ->
            !isSearchGroupPublisher(host.property("group", "publisher", group) as? String)
        }
        val searchGroups = groups.map { thirdPartyBookFactory.createThirdPartyGroup(it.publisher, it.books) }
        val next = (thirdPartyNotOurs + searchGroups).sortThirdPartyGroupsByMatch(owner.title, owner.author)
        if (thirdPartyRaw == next) state else host.copyState(state, mapOf("thirdPartyBooks" to next))
    }.getOrElse {
        HookRuntime.log("$LOG_PREFIX append association source groups failed: ${it.stackTraceToString()}")
        state
    }

    private fun setSearchingState(flow: Any, owner: Owner, searching: Boolean) {
        runCatching { updateState(flow, owner) { state ->
            if (host.property("state", "relating", state) == true || host.property("state", "isRelateSuccess", state) == true ||
                readSearching(state) == searching) state else copySearching(state, searching)
        } }.onFailure { HookRuntime.logError("ReaMicro association search indicator stopped", it) }
    }

    private fun reconcileSearchState(state: Any, owner: Owner): Any {
        if (!isOwnerCurrent(owner) || !matchesBook(owner, state) ||
            host.property("state", "relating", state) == true || host.property("state", "isRelateSuccess", state) == true) return state
        var next = appendSearchGroups(state, buildSearchGroups(owner), owner)
        val hold = synchronized(searchResultsLock) {
            isObservedOwner(owner) && holdSearchingGeneration == searchGeneration.get() && owner.title.isNotBlank()
        }
        if (hold && !readSearching(next)) next = copySearching(next, true)
        return next
    }

    private fun readSearching(state: Any): Boolean = host.property("state", "searching", state) as Boolean
    private fun copySearching(state: Any, searching: Boolean): Any = host.copyState(state, mapOf("searching" to searching))

    private fun List<Any>.sortThirdPartyGroupsByMatch(keyword: String, author: String): List<Any> {
        val normalizedKeyword = keyword.normalizedAssociationTitleKey()
        val normalizedAuthor = author.normalizedAssociationSearchKey()
        if (normalizedKeyword.isBlank() || isEmpty()) return this
        return mapIndexed { index, thirdParty ->
            val publisher = runCatching {
                host.property("group", "publisher", thirdParty) as? String
            }.getOrNull().orEmpty()
            val books = runCatching {
                host.property("group", "books", thirdParty) as? List<*>
            }.getOrNull().orEmpty().filterNotNull()
            val sortedBooks = books.sortThirdPartyBooksByMatch(normalizedKeyword, normalizedAuthor)
            val sortedGroup = if (sortedBooks == books) {
                thirdParty
            } else {
                thirdPartyBookFactory.createThirdPartyGroup(publisher, sortedBooks)
            }
            val bestRank = sortedBooks.minOfOrNull { book ->
                thirdPartyBookRank(book, normalizedKeyword, normalizedAuthor)
            } ?: Int.MAX_VALUE
            IndexedThirdPartyGroup(index, sortedGroup, bestRank)
        }.sortedWith(
            compareBy<IndexedThirdPartyGroup> { it.bestRank }
                .thenBy { it.index },
        ).map { it.group }
    }

    private fun List<Any>.sortThirdPartyBooksByMatch(
        normalizedKeyword: String,
        normalizedAuthor: String,
    ): List<Any> =
        mapIndexed { index, book -> index to book }
            .sortedWith(
                compareBy<Pair<Int, Any>> { (_, book) ->
                    thirdPartyBookRank(book, normalizedKeyword, normalizedAuthor)
                }.thenBy { it.first },
            )
            .map { it.second }

    private fun thirdPartyBookRank(
        book: Any,
        normalizedKeyword: String,
        normalizedAuthor: String,
    ): Int {
        val title = runCatching {
            host.property("candidate", "title", book) as? String
        }.getOrNull().orEmpty()
        val titleMatches = title.normalizedAssociationTitleKey() == normalizedKeyword
        val author = runCatching {
            host.property("candidate", "author", book) as? String
        }.getOrNull().orEmpty()
        val authorMatches = normalizedAuthor.isNotBlank() &&
            author.normalizedAssociationSearchKey() == normalizedAuthor
        return when {
            titleMatches && authorMatches -> 0
            titleMatches -> 1
            authorMatches -> 2
            else -> 3
        }
    }

    private fun groupPublisher(sourceName: String): String =
        "$SEARCH_GROUP_PREFIX${sourceName.ifBlank { "未知来源" }}"

    private fun isSearchGroupPublisher(publisher: String?): Boolean =
        publisher?.startsWith(SEARCH_GROUP_PREFIX) == true

    private data class SearchGroup(
        val publisher: String,
        val books: List<Any>,
    )

    private data class IndexedThirdPartyGroup(
        val index: Int,
        val group: Any,
        val bestRank: Int,
    )

    private companion object {
        const val SEARCH_GROUP_PREFIX = "关联补全-"
        const val SOURCE_SEARCH_WAIT_TIMEOUT_MS = 4_500L
        const val LOG_PREFIX = "ReaMicro LSP"
    }
}
