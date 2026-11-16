package com.reamicro.fix.epub.editor

import com.reamicro.fix.core.HostHighlightDomBindings
internal data class SelectionRenderedSlice(val start: SelectionCfiPoint, val text: String)

internal class HostReadingContext(
    val document: Any, val builder: Any, val rules: List<*>, val directory: String,
    val fullScreen: Boolean, val rootStyle: Any, val slices: List<SelectionRenderedSlice>,
    val imageMode: Any?,
)

internal class HostReadingProjection(
    private val host: HostSelectionSourceMapper, @Suppress("UNUSED_PARAMETER") loader: ClassLoader,
    private val context: HostReadingContext,
) {
    private val bindings get() = host.bindings
    private val dom get() = requireNotNull(HostHighlightDomBindings.current)
    private fun children(target: Any): List<Any> = when {
        dom.type("dom").isInstance(target) || dom.type("parsed").isInstance(target) -> dom.children(target)
        else -> dom.paragraphChildren(target)
    }
    private fun key(cfi: Any) = host.anchorPoint(cfi).key
    fun map(source: String, document: Any, positions: NormalizedSelectionPositions): SelectionSourceMap {
        host.checkReadingContext()
        val sheets = bindings.constructor("sheets").newInstance(context.rules)
        val body = bindings.body(document)
        val builder = requireNotNull(bindings.method("builder.copy").invoke(context.builder))
        val bodyStep = bindings.method("node.step").invoke(null, body)
        bindings.field("builder.body").set(builder, bodyStep)
        val rendered = bindings.parseBody(body, builder, sheets, context.directory, context.fullScreen, context.imageMode)
        val entityCache = hashMapOf<String, String>()
        fun range(node: Any) = host.sourceRange(node)
        val text = StringBuilder()
        val starts = SelectionInts()
        val ends = SelectionInts()
        val anchors = linkedMapOf<String, Int>()
        val limits = linkedMapOf<String, Int>()
        val runs = linkedMapOf<String, IntRange>()
        val nodeEnds = linkedMapOf<String, Int>()
        val breaks = hashSetOf<Int>()
        val origins = hashMapOf<Int, Pair<Int, Int>>()
        var priorContainer: Int? = null
        fun add(value: String, start: Int = -2, end: Int = -2) {
            text.append(value)
            repeat(value.length) { starts.add(start); ends.add(end) }
        }
        fun paragraph(content: Any): SelectionSourceText {
            val nodes = ArrayList<Triple<Any, Int, Int>>()
            val projected = com.reamicro.fix.reader.HostParagraphProjection.project(content,
                onNode = { node, start, end ->
                    if (dom.tag(node) in setOf("#text", "br")) nodes += Triple(node, start, end)
                })
            val sourceStarts = IntArray(projected.text.length) { -2 }
            val sourceEnds = IntArray(projected.text.length) { -2 }
            for ((node, start, originalEnd) in nodes) {
                val end = minOf(originalEnd, projected.text.length)
                if (start >= end) continue
                val sourceNode = bindings.required("parsed.source", node)
                if (dom.tag(node) == "br") {
                    runCatching { range(sourceNode) }.getOrNull()?.let { (a, b) ->
                        for (i in start until end) { sourceStarts[i] = a; sourceEnds[i] = b }
                    }
                    continue
                }
                if (!bindings.horizontal(node)) continue
                val value = projected.text.substring(start, end)
                val mapped = runCatching {
                    val (a, b) = range(sourceNode)
                    mapSelectionTextNode(source.substring(a, b), a, value) { candidate ->
                        entityCache.getOrPut(candidate) { host.unescape(candidate) }
                    }
                }.getOrNull() ?: continue
                mapped.starts.copyInto(sourceStarts, start)
                mapped.ends.copyInto(sourceEnds, start)
            }
            return SelectionSourceText(projected.text, sourceStarts, sourceEnds)
        }
        fun visit(dom: Any, depth: Int) {
            require(depth < 256) { "章节嵌套过深" }
            val location = bindings.location(dom)
            val nodeKey = key(location)
            if (this.dom.type("dom").isInstance(dom)) {
                val parent = this.dom.field("parent").get(dom)
                val container = parent?.let { this.dom.field("element").get(it) }?.let { runCatching { range(it).first }.getOrNull() }

                val separator = if (text.isNotEmpty()) text.length.also { add("\n", -1, -1); breaks += it } else null
                val from = text.length
                val projected = paragraph(dom)
                text.append(projected.text)
                starts.addAll(projected.starts); ends.addAll(projected.ends)
                if (text.length == from) {
                    if (separator != null) {
                        text.setLength(separator); starts.removeLast(); ends.removeLast(); breaks -= separator
                    }
                    return
                }
                require(nodeKey !in runs) { "宿主生成了重复阅读流锚点，不能安全回写" }
                anchors[nodeKey] = from
                limits[nodeKey] = text.length
                runs[nodeKey] = from until text.length
                nodeEnds[nodeKey] = text.length
                if (separator != null) origins[separator] = (priorContainer ?: -1) to (container ?: -1)
                priorContainer = container
            } else {

                anchors.putIfAbsent(nodeKey, text.length)
                val nested = children(dom)
                for (child in nested) visit(child, depth + 1)
                limits.putIfAbsent(nodeKey, text.length)

                nodeEnds[nodeKey] = text.length
            }
        }
        visit(rendered, 0)
        host.checkReadingContext()
        return SelectionSourceMap(source, text.toString(), starts.toArray(), ends.toArray(),
            anchors, limits, breaks, runs, nodeEnds, breaks, origins, strictReadingRuns = true)
    }
}
