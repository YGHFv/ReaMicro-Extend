package com.reamicro.fix.hook

import com.reamicro.fix.epub.editor.*
import com.reamicro.fix.hook.reader.*
import com.reamicro.fix.reader.SearchHighlightMarkDraft
import com.reamicro.fix.xposed.XposedHelpers
import java.util.IdentityHashMap

internal class ReaderSearchScrollResolver(private val reader: ReaderHook) {
    private data class Span(val key: String?, val from: Int, val until: Int)
    private data class Flow(val id: Any, val point: SelectionCfiPoint, val text: String,
        val spans: List<Span>, val dom: Any, val element: Any)
    private val cache = IdentityHashMap<Any, Flow>()
    fun clear() = cache.clear()
    fun resolve(elements: List<Any>, result: FullTextSearchResult, index: Int,
        mapper: HostSelectionSourceMapper, source: SelectionSourceMap): ResolvedSearchTarget {
        val begin = mapper.point(mapper.hostCfi(requireNotNull(result.startCfi)))
        val end = mapper.point(mapper.hostCfi(requireNotNull(result.endCfi)))
        source.plan(begin, end, result.matchText)
        val a = elements.indexOfFirst { belongs(it, begin, mapper) }
        val b = elements.indexOfLast { belongs(it, end, mapper) }
        require(a >= 0 && b >= a) { "滚动阅读元素中没有目标原文" }
        val flows = elements.subList(a, b + 1).map { cache[it] ?: project(it, mapper).also { f ->
            if (cache.size >= 64) cache.clear()
            cache[it] = f
        } }
        fun offset(flow: Flow, p: SelectionCfiPoint): Int {
            val span = flow.spans.singleOrNull { it.key == p.key } ?: error("滚动文本节点无法无损定位")
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
        val layout = reader.callNoArg(flows.first().dom, "getTextLayout") ?: error("滚动文本尚未完成测量")
        val line = (XposedHelpers.callMethod(layout, "getLineForOffset", from) as Number).toInt()
        var y = (XposedHelpers.callMethod(layout, "getLineTop", line) as Number).toFloat()
        val lineHeight = ((XposedHelpers.callMethod(layout, "getLineBottom", line) as Number).toFloat() - y).toInt().coerceAtLeast(1)

        val element = flows.first().element
        val paddingValues = listOf(
            element.javaClass.getMethod("boxPadding").invoke(element),
            element.javaClass.getMethod("boxMargin", Boolean::class.javaPrimitiveType).invoke(element, false),
        )
        for (padding in paddingValues) {
            val top = padding.javaClass.methods.first { it.name.startsWith("calculateTopPadding") && it.parameterCount == 0 }
                .apply { isAccessible = true }.invoke(padding) as Number
            val window = reader.classLoader.loadClass("org.epub.UIEpubWindow").getField("INSTANCE").get(null)
            y += (XposedHelpers.callMethod(window, "px", top) as Number).toFloat()
        }
        return ResolvedSearchTarget(result.copy(cfi = start.toString(), startCfi = start.toString(), endCfi = finish.toString()),
            start, finish, ranges, 3, reader.callNoArg(flows.first().element, "getId"), y.toInt(), lineHeight)
    }
    private fun belongs(element: Any, point: SelectionCfiPoint, mapper: HostSelectionSourceMapper): Boolean {
        val id = reader.callNoArg(element, "getId") ?: return false
        val root = mapper.anchorPoint(id)
        return point.spine == root.spine && point.itemRef == root.itemRef && point.bodyIndex == root.bodyIndex &&
            point.steps.take(root.steps.size) == root.steps
    }
    fun canResolve(elements: List<Any>, result: FullTextSearchResult, mapper: HostSelectionSourceMapper): Boolean {
        val start = result.startCfi?.let { mapper.point(mapper.hostCfi(it)) } ?: return false
        val end = result.endCfi?.let { mapper.point(mapper.hostCfi(it)) } ?: return false
        return elements.any { belongs(it, start, mapper) } && elements.any { belongs(it, end, mapper) }
    }
    private fun project(element: Any, mapper: HostSelectionSourceMapper): Flow {
        val root = reader.callNoArg(element, "getNode") ?: error("滚动元素缺少源码节点")
        val dom = reader.callNoArg(element, "buildContentDom") ?: error("滚动内容无法渲染")
        val id = reader.callNoArg(dom, "getLocation")!!
        val point = mapper.anchorPoint(id)
        val ext = reader.classLoader.loadClass("org.epub.utils.NodeExtKt")
        val getStep = ext.getMethod("getSteps", reader.classLoader.loadClass("com.fleeksoft.ksoup.nodes.Node"))
        fun path(node: Any): String? {
            var n: Any? = node
            val steps = arrayListOf<Int>()
            while (n != null && n !== root && steps.size < 256) {
                steps += (reader.callNoArg(getStep.invoke(null, n), "getIndex") as Number).toInt()
                n = reader.callNoArg(n, "parent")
            }
            return if (n === root) (point.steps + steps.asReversed()).joinToString("/") else null
        }
        val text = StringBuilder(); val spans = arrayListOf<Span>()
        fun visit(node: Any, depth: Int) {
            require(depth < 256)
            when (reader.callString(node, "getTag")) {
                "#comment", "#doctype", "rt" -> Unit
                "#text" -> {
                    val value = reader.callString(node, "getText")
                    val from = text.length; text.append(value)
                    spans += Span(reader.callNoArg(node, "getNode")?.let(::path), from, text.length)
                }
                "br" -> text.append('\n')
                else -> (reader.callNoArg(node, "getChildren") as? List<*>).orEmpty().filterNotNull().forEach { visit(it, depth + 1) }
            }
        }
        (reader.callNoArg(dom, "getChildren") as? List<*>).orEmpty().filterNotNull().forEach { visit(it, 0) }
        val actual = reader.callString(reader.callNoArg(dom, "getContent"), "getText")
        require(text.toString().trimEnd('\n') == actual) { "滚动元素含特殊排版，不能猜测高亮位置" }
        return Flow(id, point, actual, spans, dom, element)
    }
}
