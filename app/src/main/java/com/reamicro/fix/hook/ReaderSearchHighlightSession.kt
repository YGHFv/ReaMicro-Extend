package com.reamicro.fix.hook

import com.reamicro.fix.reader.SearchCfiAddress
import com.reamicro.fix.reader.SearchHighlightMarkDraft
import com.reamicro.fix.reader.SearchHighlightPlanner
import com.reamicro.fix.reader.SearchHighlightSet
import com.reamicro.fix.reader.SearchOverlayProjector
import com.reamicro.fix.hook.reader.*
import com.reamicro.fix.xposed.XposedBridge
import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentHashMap

internal class ReaderSearchHighlightSession(val marker: Any, val plan: SearchHighlightSet, keyword: String? = null, owner: Any? = null, private val ownerGeneration: Long = -1) {
    private val ownerRequired = owner != null
    private val ownerRef = java.lang.ref.WeakReference(owner)
    private val projector = SearchOverlayProjector(plan, keyword)
    @Volatile private var resolvedTemplate: Any? = null
    @Volatile private var overlayConstructor: Constructor<*>? = null
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
            val location = reader.callNoArg(contentDom, "getLocation")?.toString().orEmpty()
            val content = reader.callString(reader.callNoArg(contentDom, "getContent"), "getText")
            val base = SearchCfiAddress.parse(location)?.offset ?: 0
            val start = (reader.callNoArg(visibleWindow, "getStart") as? Number)?.toInt() ?: base
            val end = (reader.callNoArg(visibleWindow, "getEndExclusive") as? Number)?.toInt() ?: (base + content.length)
            val ranges = projector.project(location, content, start, end, renderedLength)
            if (ranges.isEmpty()) return@runCatching emptyList()
            val resolved = resolvedMarker(reader) ?: return@runCatching emptyList()
            val constructor = overlayConstructor ?: reader.classLoader.loadClass("org.epub.ui.ContentMarkOverlay")
                .declaredConstructors.first { it.parameterTypes.size in 4..5 }
                .apply { isAccessible = true }.also { overlayConstructor = it }
            ranges.mapNotNull { range ->
                val textRange = reader.textRange(range.start, range.end) ?: return@mapNotNull null
                val token = if (range.active) MARK_COLOR_RED else MARK_COLOR_YELLOW
                val color = colors[token] ?: reader.markColorTokenToColor(token)?.also { colors[token] = it }
                    ?: return@mapNotNull null
                if (constructor.parameterTypes.size == 5) {
                    constructor.newInstance(resolved, textRange, MARK_STYLE_FILL, color, null)
                } else {
                    constructor.newInstance(resolved, textRange, MARK_STYLE_FILL, color)
                }
            }
        }.getOrElse { error ->
            XposedBridge.logError("Search overlay paint bridge failed", error)
            emptyList()
        }
}

internal fun ReaderHook.createSearchHighlightSession(mark: Any,
    ranges: List<SearchHighlightMarkDraft> = emptyList(), owner: Any? = currentEpubStrong ?: currentEpubRef?.get(),
    generation: Long = searchStateGeneration): ReaderSearchHighlightSession? {
    val id = searchResultHighlightMarkId(mark) ?: return null
    val active = if (ranges.isNotEmpty()) ranges else listOfNotNull(SearchHighlightPlanner.markDraft(
        (id - SEARCH_HIGHLIGHT_MARK_ID_BASE).toInt(), callString(mark, "getChapter"),
        callString(mark, "getStartCfi"), null, callString(mark, "getEndCfi"), callString(mark, "getQuote"),
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
