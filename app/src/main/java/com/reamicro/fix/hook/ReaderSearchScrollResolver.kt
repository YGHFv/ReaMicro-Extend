package com.reamicro.fix.hook

import com.reamicro.fix.core.HostEpubSourceBindings
import com.reamicro.fix.core.HostHighlightDomBindings
import com.reamicro.fix.core.HostHighlightTextBindings
import com.reamicro.fix.core.HostScrollContent
import com.reamicro.fix.epub.editor.*
import com.reamicro.fix.hook.reader.*
import com.reamicro.fix.reader.SearchHighlightMarkDraft
import java.util.IdentityHashMap

internal class ReaderSearchScrollResolver(@Suppress("UNUSED_PARAMETER") reader: ReaderHook) {
    private val host get() = requireNotNull(HostEpubSourceBindings.current)
    private val dom get() = requireNotNull(HostHighlightDomBindings.current)
    private data class Span(val key: String, val from: Int, val until: Int)
    private data class Flow(val id: Any, val point: SelectionCfiPoint, val text: String,
        val spans: List<Span>, val content: Any, val element: Any, val structured: Boolean)
    private val cache = IdentityHashMap<Any, List<Flow>>()
    fun clear() = cache.clear()
    fun resolve(elements: List<Any>, result: FullTextSearchResult, index: Int,
        mapper: HostSelectionSourceMapper, source: SelectionSourceMap): ResolvedSearchTarget {
        val begin = mapper.point(mapper.hostCfi(requireNotNull(result.startCfi)))
        val end = mapper.point(mapper.hostCfi(requireNotNull(result.endCfi)))
        source.plan(begin, end, result.matchText)
        val a = elements.indexOfFirst { belongs(it, begin, mapper) }
        val b = elements.indexOfLast { belongs(it, end, mapper) }
        require(a >= 0 && b >= a) { "滚动阅读元素中没有目标原文" }
        val projected = elements.subList(a, b + 1).flatMap { cache[it] ?: project(it, mapper).also { f ->
            if (cache.size >= 64) cache.clear()
            cache[it] = f
        } }
        val first = projected.indices.singleOrNull { i -> projected[i].spans.any { it.key == begin.key } }
            ?: error("滚动正文起点缺失或重复")
        val last = projected.indices.singleOrNull { i -> projected[i].spans.any { it.key == end.key } }
            ?: error("滚动正文终点缺失或重复")
        require(last >= first) { "滚动正文顺序已变化" }
        val flows = projected.subList(first, last + 1)
        fun offset(flow: Flow, p: SelectionCfiPoint): Int {
            val candidates = flow.spans.filter { it.key == p.key }
            val span = candidates.singleOrNull()
                ?: error(
                    "滚动文本节点无法无损定位 key=${p.key} offset=${p.offset} " +
                        "spans=${flow.spans.take(12).joinToString { "${it.key ?: "null"}:${it.from}-${it.until}" }}",
                )
            require(p.offset in 0..(span.until - span.from))
            return span.from + p.offset
        }
        val from = offset(flows.first(), begin)
        val until = offset(flows.last(), end)
        val pieces = flows.mapIndexed { i, f ->
            f.text.substring(if (i == 0) from else 0, if (i == flows.lastIndex) until else f.text.length)
        }
        require(result.matchText == pieces.joinToString("") || result.matchText == pieces.joinToString("\n")) {
            "滚动渲染与搜索原文不一致，请重新搜索"
        }
        val ranges = flows.mapIndexed { i, f ->
            val p = f.point.copy(offset = f.point.offset + if (i == 0) from else 0)
            val q = f.point.copy(offset = f.point.offset + if (i == flows.lastIndex) until else f.text.length)
            SearchHighlightMarkDraft(SEARCH_HIGHLIGHT_MARK_ID_BASE + index, result.chapterTitle,
                mapper.cfiForPoint(f.id, p).toString(), mapper.cfiForPoint(f.id, q).toString(), pieces[i])
        }.filter { it.quote.isNotEmpty() }
        val start = mapper.cfiForPoint(flows.first().id, flows.first().point.copy(offset = flows.first().point.offset + from))
        val finish = mapper.cfiForPoint(flows.last().id, flows.last().point.copy(offset = flows.last().point.offset + until))
        val layout = host.required("scroll.layout", flows.first().content)
        val geometry = host.required("scroll.paragraph", layout)
        val line = (host.method("scroll.line").invoke(geometry, from) as Number).toInt()
        val y = (host.method("scroll.lineTop").invoke(geometry, line) as Number).toFloat()
        val lineHeight = ((host.method("scroll.lineBottom").invoke(geometry, line) as Number).toFloat() - y).toInt().coerceAtLeast(1)
        // Initial position only; arrival uses displayed glyph bounds. Structured layouts
        // obtain their exact paragraph offset from the native anchor callback.
        return ResolvedSearchTarget(result.copy(cfi = start.toString(), startCfi = start.toString(), endCfi = finish.toString()),
            start, finish, ranges, 3, host.required("scroll.element.id", flows.first().element),
            if (flows.first().structured) 0 else y.toInt(), lineHeight)
    }

    private fun belongs(element: Any, point: SelectionCfiPoint, mapper: HostSelectionSourceMapper): Boolean {
        val id = host.value("scroll.element.id", element) ?: return false
        val root = mapper.anchorPoint(id)
        return point.spine == root.spine && point.itemRef == root.itemRef && point.bodyIndex == root.bodyIndex &&
            point.steps.take(root.steps.size) == root.steps
    }
    fun canResolve(elements: List<Any>, result: FullTextSearchResult, mapper: HostSelectionSourceMapper): Boolean {
        val start = result.startCfi?.let { mapper.point(mapper.hostCfi(it)) } ?: return false
        val end = result.endCfi?.let { mapper.point(mapper.hostCfi(it)) } ?: return false
        return elements.any { belongs(it, start, mapper) } && elements.any { belongs(it, end, mapper) }
    }
    private fun project(element: Any, mapper: HostSelectionSourceMapper): List<Flow> {
        val rendered = HostScrollContent.render(host, element)
        return rendered.paragraphs.map { projectParagraph(it, element, rendered.structured, mapper) }
    }
    private fun projectParagraph(content: Any, element: Any, structured: Boolean, mapper: HostSelectionSourceMapper): Flow {
        val id = host.location(content)
        val point = mapper.anchorPoint(id)
        fun path(node: Any) = (host.method("node.steps").invoke(null, node) as List<*>)
            .joinToString("/") { host.stepIndex(requireNotNull(it)).toString() }
        val nodes = ArrayList<Triple<Any, Int, Int>>()
        val projected = com.reamicro.fix.reader.HostParagraphProjection.project(content,
            onNode = { node, start, end ->
                if (dom.tag(node) == "#text") nodes += Triple(node, start, end)
            })
        val actual = requireNotNull(HostHighlightTextBindings.current).text(requireNotNull(dom.field("content").get(content)))
        require(projected.text == actual) { "滚动元素含特殊排版，不能猜测高亮位置" }
        val spans = arrayListOf<Span>()
        for ((node, from, originalEnd) in nodes) {
            val end = minOf(originalEnd, actual.length)
            if (from >= end) continue
            val value = actual.substring(from, end)
            val sourceNode = host.required("parsed.source", node)
            val expected = host.text(sourceNode).trim(' ', '\n')
            val start = value.indexOf(expected)
            if (expected.isNotEmpty() && start >= 0 && start == value.lastIndexOf(expected)) {
                spans += Span(path(sourceNode), from + start, from + start + expected.length)
            }
        }
        return Flow(id, point, actual, spans, content, element, structured)
    }
}
