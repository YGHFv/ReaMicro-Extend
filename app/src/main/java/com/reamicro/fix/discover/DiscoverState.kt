package com.reamicro.fix.discover

import android.content.Context
import com.reamicro.fix.online.OnlineSourceStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object DiscoverState {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
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

    private val cache = ConcurrentHashMap<String, DiscoverBookPage>()

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

    private fun bump() {
        version += 1
        onChanged?.invoke()
    }

    fun invalidate() {
        requestToken.incrementAndGet()
        loadingMore = false
        cache.clear()
        version += 1
    }

    fun refreshSources(context: Context?) {
        requestToken.incrementAndGet()
        loadingMore = false
        restoreLayout(context)
        restoreFilters(context)
        restoreSelection(context)
        val resolved = OnlineSourceStore.list(context)
            .map { source ->
                DiscoverSource(
                    source,
                    DiscoverRepository.parseKinds(source),
                    DiscoverRepository.parseFilter(source),
                )
            }
            .filter { it.hasKinds }
        sources = resolved

        val current = resolveSelection(
            selection.takeIf { it != DiscoverSelection.NONE } ?: storedSelection ?: selection,
            resolved,
        )
        val changed = current != selection
        selection = current
        filterKind = buildFilterKind(current.sourceId)
        val cached = cache[selectionKey(current)]
        state = cached?.state ?: DiscoverLoadState.Idle
        hasMore = cached?.hasMore ?: false
        bump()

        if (cached == null && current != DiscoverSelection.NONE && (changed || state == DiscoverLoadState.Idle)) {
            load(current, context)
        } else {
            fillGridBatch(context)
        }
    }

    fun select(sourceId: String, kindTitle: String, context: Context?) {

        requestToken.incrementAndGet()
        loadingMore = false
        val next = DiscoverSelection(sourceId, kindTitle)
        selection = next
        persistSelection(context)
        val cached = cache[selectionKey(next)]
        if (cached != null) {
            state = cached.state
            hasMore = cached.hasMore
            bump()
            fillGridBatch(context)
            return
        }
        load(next, context)
    }

    fun reload(context: Context?) {
        val current = selection
        if (current == DiscoverSelection.NONE) return
        cache.remove(selectionKey(current))
        load(current, context)
    }

    fun selectSource(sourceId: String, context: Context?) {
        val entry = sources.firstOrNull { it.source.id == sourceId } ?: return
        val kind = entry.kinds.firstOrNull() ?: return
        filterKind = buildFilterKind(sourceId)
        select(sourceId, kind.title, context)
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

    fun applyFilterSelection(sourceId: String, selections: Map<String, String>, context: Context?) {
        val source = sources.firstOrNull { it.source.id == sourceId } ?: return
        val filter = source.filter ?: return
        val resolved = filter.filterSelectionsResolved(selections)
        filterSelectionsBySource[sourceId] = resolved
        persistFilters(context)
        val kind = buildFilterKind(sourceId) ?: return
        filterKind = kind
        select(sourceId, kind.title, context)
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

    fun setLayout(context: Context?, next: DiscoverLayout) {
        if (next == layout) return
        requestToken.incrementAndGet()
        loadingMore = false
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
        if (sourceId.isNotBlank() && kindTitle.isNotBlank()) {
            storedSelection = DiscoverSelection(sourceId, kindTitle)
        }
    }

    private fun persistSelection(context: Context?) {
        val app = context?.applicationContext ?: return
        runCatching {
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SELECTION_SOURCE, selection.sourceId)
                .putString(KEY_SELECTION_KIND, selection.kindTitle)
                .apply()
        }
    }

    private fun load(target: DiscoverSelection, context: Context?) {
        val source = sources.firstOrNull { it.source.id == target.sourceId } ?: return
        val kind = resolveKind(source, target.kindTitle) ?: return
        val key = selectionKey(target)
        val token = requestToken.incrementAndGet()
        val grid = layout == DiscoverLayout.GRID
        state = DiscoverLoadState.Loading
        hasMore = false
        loadingMore = false
        bump()
        executor.execute {
            val result = loadDiscoverBookPage(
                grid = grid,
                isCancelled = { token != requestToken.get() },
                fetch = { page -> DiscoverRepository.loadBooks(source.source, kind, page) },
            ) ?: return@execute
            if (token != requestToken.get() || selection != target) return@execute
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

    fun loadMore(context: Context?) {
        val target = selection
        if (target == DiscoverSelection.NONE || loadingMore || !hasMore) return
        if (state !is DiscoverLoadState.Loaded) return
        val source = sources.firstOrNull { it.source.id == target.sourceId } ?: return
        val kind = resolveKind(source, target.kindTitle) ?: return
        val key = selectionKey(target)
        val current = cache[key] ?: return
        val token = requestToken.incrementAndGet()
        val grid = layout == DiscoverLayout.GRID
        loadingMore = true
        bump()
        executor.execute {
            val result = loadDiscoverBookPage(
                current = current,
                grid = grid,
                isCancelled = { token != requestToken.get() },
                fetch = { page -> DiscoverRepository.loadBooks(source.source, kind, page) },
            ) ?: return@execute

            if (token != requestToken.get() || selection != target) return@execute
            val page = result.page
            cache[key] = page
            state = page.state
            hasMore = page.hasMore
            loadingMore = false
            bump()
            com.reamicro.fix.xposed.XposedBridge.logAlways(
                "[ReaMicro] discover loadMore applied page=${page.sourcePage} " +
                    "total=${page.visibleCount} buffered=${page.books.size - page.visibleCount}" +
                    (result.error?.let { " error=$it" } ?: ""),
            )
        }
    }

    private fun resolveSelection(
        current: DiscoverSelection,
        available: List<DiscoverSource>,
    ): DiscoverSelection {
        if (available.isEmpty()) return DiscoverSelection.NONE
        val matched = available.firstOrNull { it.source.id == current.sourceId }
        if (matched != null) {
            if (matched.kinds.any { it.title == current.kindTitle }) return current

            if (buildFilterKind(matched.source.id)?.title == current.kindTitle) return current

            return DiscoverSelection(matched.source.id, matched.kinds.first().title)
        }
        val first = available.first()
        return DiscoverSelection(first.source.id, first.kinds.first().title)
    }

    private fun resolveKind(source: DiscoverSource, kindTitle: String): DiscoverKind? =
        source.kinds.firstOrNull { it.title == kindTitle }
            ?: filterKind?.takeIf { source.source.id == selection.sourceId && it.title == kindTitle }

    private fun selectionKey(selection: DiscoverSelection): String =
        "${selection.sourceId}|${selection.kindTitle}"

    private const val PREFS_NAME = "reamicro_discover"

    private const val KEY_LAYOUT = "book_layout"

    private const val KEY_SELECTION_SOURCE = "selection_source"

    private const val KEY_SELECTION_KIND = "selection_kind"

    private const val KEY_FILTERS = "filter_selections_v1"
}
