package com.reamicro.fix.discover

import android.content.Context
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.TaskCancellation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger

internal object DiscoverState {

    private val executor = com.reamicro.fix.reload.ModuleResources.pool(1) { runnable ->
        Thread(runnable, "ReaMicroDiscover").apply { isDaemon = true }
    }

    @Volatile
    var version: Int = 0
        private set

    @Volatile
    var sources: List<DiscoverSource> = emptyList()
        private set

    @Volatile
    var selection: DiscoverSelection = DiscoverSelection.NONE
        private set

    @Volatile
    var state: DiscoverLoadState = DiscoverLoadState.Idle
        private set

    @Volatile
    var layout: DiscoverLayout = DiscoverLayout.LIST
        private set

    @Volatile
    var refreshing: Boolean = false
        private set

    private data class CacheKey(val sourceId: String, val revision: String, val kind: DiscoverKind)
    private val cache = object : LinkedHashMap<CacheKey, DiscoverBookPage>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, DiscoverBookPage>?): Boolean = size > 16
    }
    @Volatile var initialized = false
        private set
    private var application: Context? = null
    private var activeCancellation: TaskCancellation? = null
    private var activeTask: FutureTask<Unit>? = null

    @Volatile
    var hasMore: Boolean = false
        private set

    @Volatile
    var loadingMore: Boolean = false
        private set

    private val requestToken = AtomicInteger(0)

    @Volatile
    private var layoutRestored = false

    @Volatile
    private var storedSelection: DiscoverSelection? = null

    @Volatile
    private var selectionRestored = false

    @Volatile
    var filterKind: DiscoverKind? = null
        private set

    private val filterSelectionsBySource = ConcurrentHashMap<String, Map<String, String>>()

    @Volatile
    private var filtersRestored = false

    @Volatile
    var onChanged: (() -> Unit)? = null

    init {
        val observer = OnlineSourceStore.observeChanges { invalidate() }
        val modeObserver = com.reamicro.fix.online.OnlineParserMode.observeChanges { invalidate() }
        ModuleResources.cleanup {
            observer.close()
            modeObserver.close()
            synchronized(this) {
                cancelRequest()
                cache.clear()
                sources = emptyList()
                onChanged = null
                application = null
            }
        }
    }

    private fun bump() {
        version += 1
        onChanged?.invoke()
    }

    private fun cancelRequest() {
        requestToken.incrementAndGet()
        activeCancellation?.cancel()
        activeCancellation = null
        activeTask?.cancel(true)
        activeTask = null
        executor.purge()
        loadingMore = false
    }

    private fun finishRequest(token: Int): Boolean {
        if (token != requestToken.get()) return false
        activeCancellation = null
        activeTask = null
        return true
    }

    private fun executeRequest(block: (Int) -> Unit) {
        cancelRequest()
        val token = requestToken.get()
        val cancellation = TaskCancellation()
        val task = FutureTask<Unit> {
            try {
                cancellation.run { block(token) }
            } catch (error: Throwable) {
                synchronized(this) {
                    if (!finishRequest(token)) return@synchronized
                    refreshing = false
                    loadingMore = false
                    if (!cancellation.isCancelled) {
                        initialized = true
                        state = DiscoverLoadState.Failed(error.message ?: "加载失败，请重试")
                        bump()
                    }
                }
            }
        }
        activeCancellation = cancellation
        activeTask = task
        try { executor.execute(task) } catch (error: java.util.concurrent.RejectedExecutionException) {
            activeCancellation = null
            activeTask = null
            refreshing = false
            loadingMore = false
            state = DiscoverLoadState.Failed("加载已停止，请重新打开")
            bump()
        }
    }

    @Synchronized fun invalidate() {
        cancelRequest()
        cache.clear()
        initialized = false
        refreshing = false
        sources = emptyList()
        state = DiscoverLoadState.Idle
        hasMore = false
        bump()
        application?.let(::refreshSources)
    }

    @Synchronized fun ensureSources(context: Context?) {
        if (!initialized && !refreshing) refreshSources(context)
    }

    @Synchronized fun refreshSources(context: Context?) {
        val app = context?.applicationContext ?: return
        if (refreshing) return
        application = app
        refreshing = true
        bump()
        executeRequest { token ->
            val resolved = OnlineSourceStore.list(app).map { source ->
                TaskCancellation.checkCurrent()
                DiscoverSource(source, DiscoverRepository.parseKinds(source), DiscoverRepository.parseFilter(source))
            }.filter { it.hasKinds }
            synchronized(this) {
                if (!finishRequest(token)) return@synchronized
                restoreLayout(app)
                restoreFilters(app)
                restoreSelection(app)
                sources = resolved
                initialized = true
                refreshing = false
                val current = resolveSelection(
                    selection.takeIf { it != DiscoverSelection.NONE } ?: storedSelection ?: selection,
                    resolved,
                )
                selection = current
                filterKind = buildFilterKind(current.sourceId)
                val validIds = resolved.mapTo(HashSet()) { it.source.id }
                filterSelectionsBySource.keys.retainAll(validIds)
                val cached = selectionKey(current)?.let(cache::get)
                state = cached?.state ?: DiscoverLoadState.Idle
                hasMore = cached?.hasMore ?: false
                bump()
                if (cached == null && current != DiscoverSelection.NONE) load(current, app)
                else fillGridBatch(app)
            }
        }
    }

    @Synchronized fun select(sourceId: String, kindTitle: String, context: Context?, kindKey: String = "") {
        val source = sources.firstOrNull { it.source.id == sourceId } ?: return
        val candidate = DiscoverSelection(sourceId, kindTitle, kindKey)
        val kind = resolveKind(source, candidate) ?: return
        if (!kind.isBookList) return
        cancelRequest()
        val next = candidate.copy(kindKey = kind.key)
        selection = next
        persistSelection(context)
        val cached = selectionKey(next)?.let(cache::get)
        if (cached != null) {
            state = cached.state
            hasMore = cached.hasMore
            bump()
            fillGridBatch(context)
            return
        }
        load(next, context)
    }

    @Synchronized fun reload(context: Context?) {
        if (!initialized || sources.isEmpty()) { refreshSources(context); return }
        val current = selection
        if (current == DiscoverSelection.NONE) return
        selectionKey(current)?.let(cache::remove)
        load(current, context)
    }

    /** A control action replaces only its source's kinds and invalidates affected book pages. */
    @Synchronized fun updateKinds(sourceId: String, revision: String, kinds: List<DiscoverKind>, context: Context?) {
        val previous = sources.firstOrNull { it.source.id == sourceId && it.source.revision == revision } ?: return
        if (!OnlineSourceStore.isCurrent(previous.source)) return
        sources = sources.map { if (it === previous) it.copy(kinds = kinds.toList()) else it }
        cache.keys.removeAll { it.sourceId == sourceId }
        if (selection.sourceId == sourceId) {
            cancelRequest()
            selection = resolveSelection(selection, sources)
            persistSelection(context)
            bump()
            load(selection, context)
        } else bump()
    }

    @Synchronized fun selectSource(sourceId: String, context: Context?) {
        val entry = sources.firstOrNull { it.source.id == sourceId } ?: return
        val kind = entry.kinds.firstOrNull { it.isBookList }
        filterKind = buildFilterKind(sourceId)
        if (kind != null) select(sourceId, kind.title, context, kind.key) else {
            cancelRequest()
            selection = DiscoverSelection(sourceId, "", "")
            state = DiscoverLoadState.Loaded(emptyList())
            hasMore = false
            bump()
        }
    }

    fun filterSelection(sourceId: String): Map<String, String> {
        val filter = sources.firstOrNull { it.source.id == sourceId }?.filter ?: return emptyMap()
        val stored = filterSelectionsBySource[sourceId].orEmpty()
        return filter.defaultSelection().mapValues { (groupName, default) ->
            val chosen = stored[groupName] ?: return@mapValues default
            val group = filter.groups.firstOrNull { it.name == groupName }
            if (group != null && group.options.any { it.title == chosen }) chosen else default
        }
    }

    @Synchronized fun applyFilterSelection(sourceId: String, selections: Map<String, String>, context: Context?) {
        val source = sources.firstOrNull { it.source.id == sourceId } ?: return
        val filter = source.filter ?: return
        val resolved = filter.filterSelectionsResolved(selections)
        filterSelectionsBySource[sourceId] = resolved
        persistFilters(context)
        val kind = buildFilterKind(sourceId) ?: return
        filterKind = kind
        select(sourceId, kind.title, context, kind.key)
    }

    fun kindsFor(source: DiscoverSource): List<DiscoverKind> {
        val extra = filterKind ?: return source.kinds
        if (selection.sourceId != source.source.id) return source.kinds
        return listOf(extra) + source.kinds
    }

    private fun buildFilterKind(sourceId: String): DiscoverKind? {
        val source = sources.firstOrNull { it.source.id == sourceId } ?: return null
        val filter = source.filter ?: return null
        val stored = filterSelectionsBySource[sourceId] ?: return null
        val resolved = filter.filterSelectionsResolved(stored)
        val url = filter.buildUrl(resolved)
        if (url.isBlank()) return null
        return DiscoverKind(title = filter.selectionTitle(resolved), url = url, urls = listOf(url))
    }

    private fun DiscoverFilter.filterSelectionsResolved(stored: Map<String, String>): Map<String, String> =
        defaultSelection().mapValues { (groupName, default) ->
            val chosen = stored[groupName] ?: return@mapValues default
            val group = groups.firstOrNull { it.name == groupName }
            if (group != null && group.options.any { it.title == chosen }) chosen else default
        }

    private fun restoreFilters(context: Context?) {
        if (filtersRestored) return
        val app = context?.applicationContext ?: return
        filtersRestored = true
        val text = runCatching {
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_FILTERS, null)
        }.getOrNull() ?: return
        runCatching {
            val root = org.json.JSONObject(text)
            root.keys().forEach { sourceId ->
                val node = root.optJSONObject(sourceId) ?: return@forEach
                val selections = mutableMapOf<String, String>()
                node.keys().forEach { group ->
                    val title = node.optString(group, "")
                    if (group.isNotBlank() && title.isNotBlank()) selections[group] = title
                }
                if (selections.isNotEmpty()) filterSelectionsBySource[sourceId] = selections
            }
        }
    }

    private fun persistFilters(context: Context?) {
        val app = context?.applicationContext ?: return
        runCatching {
            val root = org.json.JSONObject()
            filterSelectionsBySource.forEach { (sourceId, selections) ->
                val node = org.json.JSONObject()
                selections.forEach { (group, title) -> node.put(group, title) }
                root.put(sourceId, node)
            }
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_FILTERS, root.toString())
                .apply()
        }
    }

    @Synchronized fun setLayout(context: Context?, next: DiscoverLayout) {
        if (next == layout) return
        if (!refreshing) cancelRequest()
        layout = next
        val app = context?.applicationContext
        if (app != null) {
            runCatching {
                app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAYOUT, next.name)
                    .apply()
            }
        }
        bump()
        if (refreshing) return
        if (state is DiscoverLoadState.Loading) {
            load(selection, context)
        } else {
            fillGridBatch(context)
        }
    }

    private fun fillGridBatch(context: Context?) {
        val loaded = state as? DiscoverLoadState.Loaded ?: return
        if (layout == DiscoverLayout.GRID && hasMore &&
            loaded.books.size % DISCOVER_GRID_BATCH_SIZE != 0
        ) {
            loadMore(context)
        }
    }

    fun toggleLayout(context: Context?): DiscoverLayout {
        val next = layout.toggled()
        setLayout(context, next)
        return next
    }

    private fun restoreLayout(context: Context?) {
        if (layoutRestored) return
        val app = context?.applicationContext ?: return
        layoutRestored = true
        layout = DiscoverLayout.fromStorage(
            runCatching {
                app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAYOUT, null)
            }.getOrNull(),
        )
    }

    private fun restoreSelection(context: Context?) {
        if (selectionRestored) return
        val app = context?.applicationContext ?: return
        selectionRestored = true
        val prefs = runCatching { app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }.getOrNull() ?: return
        val sourceId = runCatching { prefs.getString(KEY_SELECTION_SOURCE, null) }.getOrNull().orEmpty()
        val kindTitle = runCatching { prefs.getString(KEY_SELECTION_KIND, null) }.getOrNull().orEmpty()
        val kindKey = runCatching { prefs.getString(KEY_SELECTION_KIND_KEY, null) }.getOrNull().orEmpty()
        if (sourceId.isNotBlank() && kindTitle.isNotBlank()) {
            storedSelection = DiscoverSelection(sourceId, kindTitle, kindKey)
        }
    }

    private fun persistSelection(context: Context?) {
        val app = context?.applicationContext ?: return
        runCatching {
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SELECTION_SOURCE, selection.sourceId)
                .putString(KEY_SELECTION_KIND, selection.kindTitle)
                .putString(KEY_SELECTION_KIND_KEY, selection.kindKey)
                .apply()
        }
    }

    private fun load(target: DiscoverSelection, context: Context?) {
        val source = sources.firstOrNull { it.source.id == target.sourceId } ?: return
        val kind = resolveKind(source, target)?.takeIf { it.isBookList }
        if (kind == null) {
            state = DiscoverLoadState.Loaded(emptyList())
            hasMore = false
            loadingMore = false
            bump()
            return
        }
        val key = selectionKey(target) ?: return
        val grid = layout == DiscoverLayout.GRID
        state = DiscoverLoadState.Loading
        hasMore = false
        loadingMore = false
        bump()
        executeRequest { token ->
            val result = loadDiscoverBookPage(
                grid = grid,
                isCancelled = { token != requestToken.get() },
                fetch = { page ->
                    OnlineSourceStore.requireCurrent(source.source)
                    DiscoverRepository.loadBooks(source.source, kind, page)
                },
            ) ?: return@executeRequest
            synchronized(this) {
                if (!finishRequest(token) || selection != target || !OnlineSourceStore.isCurrent(source.source)) return@synchronized
                val page = result.page
                if (result.error != null && page.books.isEmpty()) {
                    state = DiscoverLoadState.Failed(result.error)
                    hasMore = false
                } else {
                    cache[key] = page
                    state = page.state
                    hasMore = page.hasMore
                }
                bump()
            }
        }
    }

    @Synchronized fun loadMore(context: Context?) {
        val target = selection
        if (target == DiscoverSelection.NONE || loadingMore || !hasMore) return
        if (state !is DiscoverLoadState.Loaded) return
        val source = sources.firstOrNull { it.source.id == target.sourceId } ?: return
        val kind = resolveKind(source, target) ?: return
        val key = selectionKey(target) ?: return
        val current = cache[key] ?: return
        val grid = layout == DiscoverLayout.GRID
        executeRequest { token ->
            val result = loadDiscoverBookPage(
                current = current,
                grid = grid,
                isCancelled = { token != requestToken.get() },
                fetch = { page ->
                    OnlineSourceStore.requireCurrent(source.source)
                    DiscoverRepository.loadBooks(source.source, kind, page)
                },
            ) ?: return@executeRequest
            synchronized(this) {
                if (!finishRequest(token) || selection != target || !OnlineSourceStore.isCurrent(source.source)) return@synchronized
                val page = result.page
                cache[key] = page
                state = page.state
                hasMore = page.hasMore
                loadingMore = false
                bump()
            }
            val page = result.page
            com.reamicro.fix.xposed.HookRuntime.logAlways(
                "[ReaMicro] discover loadMore applied page=${page.sourcePage} " +
                    "total=${page.visibleCount} buffered=${page.books.size - page.visibleCount}" +
                    (result.error?.let { " error=$it" } ?: ""),
            )
        }
        loadingMore = activeTask != null
        bump()
    }

    private fun resolveSelection(
        current: DiscoverSelection,
        available: List<DiscoverSource>,
    ): DiscoverSelection {
        if (available.isEmpty()) return DiscoverSelection.NONE
        val matched = available.firstOrNull { it.source.id == current.sourceId }
        if (matched != null) {
            matched.kinds.firstOrNull { it.isBookList && it.key == current.kindKey }?.let {
                return current.copy(kindTitle = it.title)
            }
            if (current.kindKey.isBlank()) matched.kinds.firstOrNull { it.isBookList && it.title == current.kindTitle }?.let {
                return current.copy(kindKey = it.key)
            }

            buildFilterKind(matched.source.id)?.takeIf { it.key == current.kindKey ||
                current.kindKey.isBlank() && it.title == current.kindTitle }?.let { return current.copy(kindKey = it.key) }

            val kind = matched.kinds.firstOrNull { it.isBookList }
            return DiscoverSelection(matched.source.id, kind?.title.orEmpty(), kind?.key.orEmpty())
        }
        val first = available.first()
        val kind = first.kinds.firstOrNull { it.isBookList }
        return DiscoverSelection(first.source.id, kind?.title.orEmpty(), kind?.key.orEmpty())
    }

    private fun resolveKind(source: DiscoverSource, target: DiscoverSelection): DiscoverKind? =
        (listOfNotNull(buildFilterKind(source.source.id)) + source.kinds).firstOrNull {
            if (target.kindKey.isNotBlank()) it.key == target.kindKey else it.title == target.kindTitle
        }

    private fun selectionKey(selection: DiscoverSelection): CacheKey? {
        val source = sources.firstOrNull { it.source.id == selection.sourceId } ?: return null
        val kind = resolveKind(source, selection) ?: return null
        return CacheKey(source.source.id, source.source.revision, kind)
    }

    private const val PREFS_NAME = "reamicro_discover"

    private const val KEY_LAYOUT = "book_layout"

    private const val KEY_SELECTION_SOURCE = "selection_source"

    private const val KEY_SELECTION_KIND = "selection_kind"
    private const val KEY_SELECTION_KIND_KEY = "selection_kind_key"

    private const val KEY_FILTERS = "filter_selections_v1"
}
