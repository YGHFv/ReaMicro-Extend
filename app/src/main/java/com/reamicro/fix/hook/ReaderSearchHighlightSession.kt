package com.reamicro.fix.hook

import com.reamicro.fix.reader.SearchCfiAddress
import com.reamicro.fix.reader.SearchHighlightMarkDraft
import com.reamicro.fix.reader.SearchHighlightPlanner
import com.reamicro.fix.reader.SearchHighlightSet
import com.reamicro.fix.reader.SearchOverlayProjector
import com.reamicro.fix.hook.reader.*
import com.reamicro.fix.xposed.HookRuntime
import java.util.concurrent.ConcurrentHashMap

internal class ReaderSearchHighlightSession(val marker: Any, val plan: SearchHighlightSet, keyword: String? = null, owner: Any? = null, private val ownerGeneration: Long = -1) {
    private val ownerRequired = owner != null
    private val ownerRef = java.lang.ref.WeakReference(owner)
    private val projector = SearchOverlayProjector(plan, keyword)
    @Volatile private var resolvedTemplate: Any? = null
    private val colors = ConcurrentHashMap<String, Long>()

    fun isCurrent(reader: ReaderHook): Boolean {
        if (!ownerRequired) return true
        val owner = ownerRef.get() ?: return false
        return (reader.currentEpubStrong ?: reader.currentEpubRef?.get()) === owner && reader.searchStateGeneration == ownerGeneration
    }

    fun reusePassiveScans(previous: ReaderSearchHighlightSession?) {
        previous?.let { projector.reusePassiveScans(it.projector) }
    }
    fun resolvedMarker(reader: ReaderHook): Any? = resolvedTemplate ?: reader.createResolvedSearchHighlightMark(marker)
        ?.also { resolvedTemplate = it }
    fun paintOverlays(reader: ReaderHook, contentDom: Any?, visibleWindow: Any?, renderedLength: Int): List<Any> =
        runCatching {
            if (!isCurrent(reader)) return@runCatching emptyList()
            contentDom ?: return@runCatching emptyList()
            val symbols = requireNotNull(com.reamicro.fix.core.HostReaderRecordBindings.current)
            val location = symbols.field("dom.location").get(contentDom)?.toString().orEmpty()
            val content = symbols.text(contentDom)
            val base = SearchCfiAddress.parse(location)?.offset ?: 0
            val start = visibleWindow?.let { symbols.field("window.start").getInt(it) } ?: base
            val end = visibleWindow?.let { symbols.field("window.endExclusive").getInt(it) } ?: (base + content.length)
            val ranges = projector.project(location, content, start, end, renderedLength)
            if (ranges.isEmpty()) return@runCatching emptyList()
            val resolved = resolvedMarker(reader) ?: return@runCatching emptyList()
            ranges.mapNotNull { range ->
                val textRange = reader.textRange(range.start, range.end) ?: return@mapNotNull null
                val token = if (range.active) MARK_COLOR_RED else MARK_COLOR_YELLOW
                val color = colors[token] ?: reader.markColorTokenToColor(token)?.also { colors[token] = it }
                    ?: return@mapNotNull null
                symbols.overlay(resolved, textRange, MARK_STYLE_FILL, color)
            }
        }.getOrElse { error ->
            HookRuntime.logError("Search overlay paint bridge failed", error)
            emptyList()
        }
}

internal fun ReaderHook.createSearchHighlightSession(mark: Any,
    ranges: List<SearchHighlightMarkDraft> = emptyList(), owner: Any? = currentEpubStrong ?: currentEpubRef?.get(),
    generation: Long = searchStateGeneration): ReaderSearchHighlightSession? {
    val id = searchResultHighlightMarkId(mark) ?: return null
    val symbols = requireNotNull(com.reamicro.fix.core.HostReaderRecordBindings.current)
    fun text(role: String) = symbols.field("mark.$role").get(mark) as String
    val active = if (ranges.isNotEmpty()) ranges else listOfNotNull(SearchHighlightPlanner.markDraft(
        (id - SEARCH_HIGHLIGHT_MARK_ID_BASE).toInt(), text("chapter"),
        text("startCfi"), null, text("endCfi"), text("quote"),
        SEARCH_HIGHLIGHT_MARK_ID_BASE))

    return ReaderSearchHighlightSession(mark, SearchHighlightSet(active, id),
        (searchNavigationState ?: lastSearchState)?.keyword, owner, generation).also {
        it.reusePassiveScans(currentSearchPaintSession())
    }
}

internal fun ReaderHook.currentSearchPaintSession(): ReaderSearchHighlightSession? =
    activeSearchHighlightSession?.takeIf { it.isCurrent(this) }

internal fun ReaderHook.appendSearchResolvedMarker(original: List<*>): List<Any> {
    val clean = original.filterNotNull().filterNot { resolved ->
        transientHighlightResolvedMarkId(resolved)?.let { id ->
            SearchHighlightPlanner.isHighlightId(id, SEARCH_HIGHLIGHT_MARK_ID_BASE, SEARCH_HIGHLIGHT_MARK_ID_RANGE)
        } == true
    }
    val marker = currentSearchPaintSession()?.resolvedMarker(this) ?: return clean
    return clean + marker
}
