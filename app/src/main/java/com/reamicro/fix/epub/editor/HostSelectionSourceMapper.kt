package com.reamicro.fix.epub.editor

import com.reamicro.fix.core.HostEpubSourceBindings
import com.reamicro.fix.core.HostHighlightTextBindings
import com.reamicro.fix.core.HostHtmlParser
import com.reamicro.fix.core.HostHtmlNormalization
internal class HostSelectionSourceMapper(private val loader: ClassLoader) {
    private val parser = HostHtmlParser(loader)
    internal val bindings get() = requireNotNull(HostEpubSourceBindings.current)
    private var sourcePositions: HostSelectionSourcePositions? = null
    private var reading: HostReadingContext? = null
    private fun readingContext(document: Any, slices: List<SelectionRenderedSlice>) = HostReadingContext(
        document, bindings.required("document.builder", document),
        (bindings.required("sheets.rules", bindings.required("document.sheets", document)) as List<*>).toList(),
        bindings.required("document.directory", document) as String,
        bindings.method("document.fullScreen").invoke(document) as Boolean, bindings.rootStyle(), slices,
        bindings.imageMode(document),
    )
    fun bindReadingDocument(document: Any, controller: Any) {
        val nodes = bindings.required("selection.nodes", controller) as Map<*, *>
        val selection = bindings.method("selection.get").invoke(controller) ?: error("选区已关闭")
        val endpoints = listOf(bindings.required("snapshot.anchor", selection), bindings.required("snapshot.focus", selection))
        val ids = endpoints.map { bindings.required("endpoint.nodeId", it) as String }
        val ordered = nodes.values.filterNotNull().sortedWith { a, b ->
            numberComparison(bindings.required("selectable.cfi", a), bindings.required("selectable.cfi", b))
        }
        val indices = ids.map { id -> ordered.indexOfFirst { bindings.value("selectable.id", it) == id } }
        require(indices.all { it >= 0 }) { "阅读页选区片段已失效" }
        val slices = ordered.subList(indices.min(), indices.max() + 1).map { node ->
            SelectionRenderedSlice(anchorPoint(bindings.required("selectable.cfi", node)),
                requireNotNull(HostHighlightTextBindings.current).text(bindings.required("selectable.text", node)))
        }
        reading = readingContext(document, slices)
    }
    fun bindSearchDocument(document: Any) { reading = readingContext(document, emptyList()) }
    fun checkReadingContext(document: Any? = null) {
        val context = reading ?: error("缺少真实阅读页渲染上下文，未写入")
        require((document == null || document === context.document) &&
            bindings.rootStyle() === context.rootStyle) {
            "阅读页或排版设置已变化，请重新选择（未写入）"
        }
    }

    fun checkReadingDocuments(documents: Collection<*>) {
        checkReadingContext()
        val original = reading!!.document
        require(documents.any { it === original }) { "章节渲染已更新，请重新选择（未写入）" }
    }

    fun verifyReadingSlices(map: SelectionSourceMap) {
        val context = reading ?: error("缺少真实阅读页选区快照")
        require(context.slices.isNotEmpty()) { "阅读页选区快照为空" }
        context.slices.forEach { map.verifyRenderedSlice(it) }
    }

    fun hostCfi(raw: String): Any = bindings.cfi(raw)
    internal fun sourceRange(node: Any) = requireNotNull(sourcePositions).range(node)
    internal fun unescape(value: String) = requireNotNull(sourcePositions).unescape(value)
    fun point(cfi: Any): SelectionCfiPoint {
        require(bindings.hasOffset(cfi)) { "选区缺少字符位置" }
        return anchorPoint(cfi)
    }
    fun anchorPoint(cfi: Any): SelectionCfiPoint = SelectionCfiPoint(
        bindings.stepIndex(bindings.required("cfi.spine", cfi)),
        bindings.stepIndex(bindings.required("cfi.item", cfi)), bindings.cfiSteps(cfi),
        bindings.offsetValue(cfi), bindings.offsetIndex(cfi), bindings.stepIndex(bindings.required("cfi.body", cfi)),
    )
    fun rangeOverlapsSelection(range: Any, start: Any, end: Any): Boolean {
        val pageStart = bindings.required("range.start", range)
        val pageEnd = bindings.required("range.end", range)
        if (numberComparison(start, end) >= 0 || numberComparison(pageStart, pageEnd) >= 0) return false
        return numberComparison(start, pageEnd) < 0 && numberComparison(end, pageStart) > 0
    }
    private fun numberComparison(left: Any, right: Any): Int = bindings.compare(left, right)
    fun viewportSourceOffset(source: SelectionSourceMap, cfi: Any): Int? {
        val point = anchorPoint(cfi)
        val afterNode = !bindings.hasOffset(cfi) && bindings.biasBefore(cfi) == false
        return if (afterNode) source.sourceOffsetAfterNode(point) else source.sourceOffset(point)
    }
    fun cfiForPoint(template: Any, point: SelectionCfiPoint): Any {
        val steps = point.steps.map { bindings.step(it) }
        val offset = bindings.offset(point.offsetIndex, point.offset)
        require(bindings.stepIndex(bindings.required("cfi.spine", template)) == point.spine &&
            bindings.stepIndex(bindings.required("cfi.item", template)) == point.itemRef) { "CFI 章节身份不一致" }
        val bias = if (bindings.hasOffset(template)) bindings.biasBefore(template)?.let(bindings::bias) else null
        val result = bindings.record("cfi", mapOf(
            "spine" to bindings.required("cfi.spine", template), "item" to bindings.required("cfi.item", template),
            "body" to bindings.step(point.bodyIndex), "steps" to steps, "offset" to offset, "bias" to bias,
        ))
        require(this.point(result) == point) { "宿主 CFI 往返校验失败" }
        return result
    }
    fun map(source: String, baseUri: String): SelectionSourceMap {
        require(source.length <= 4_000_000) { "章节超过当前选区映射的安全范围，请使用图书结构编辑器" }
        val normalized = HostHtmlNormalization.normalize(source)
        val positions = NormalizedSelectionPositions(source, normalized, HostHtmlNormalization::normalize)
        val document = parser.parse(source, baseUri)
        val body = bindings.body(document)
        sourcePositions = HostSelectionSourcePositions(loader, normalized, body, positions)
        reading?.let { return HostReadingProjection(this, loader, it).map(source, document, positions) }
        val pathMethod = bindings.method("node.steps")
        val text = StringBuilder()
        val starts = SelectionInts()
        val ends = SelectionInts()
        val anchors = linkedMapOf<String, Int>()
        val textNodes = linkedMapOf<String, IntRange>()
        val nodeEnds = hashMapOf<String, Int>()
        val groups = hashMapOf<String, String>()
        val groupEnds = hashMapOf<String, Int>()
        val structuralBreaks = hashSetOf<Int>()
        val blockBreaks = hashSetOf<Int>()
        val entityCache = hashMapOf<String, String>()
        fun add(value: String, start: Int = -1, end: Int = -1) {
            for (c in value) { text.append(c); starts.add(start); ends.add(end) }
        }
        fun blockBreak() {
            if (text.isEmpty()) return
            if (text.last() != '\n') {
                structuralBreaks += text.length
                add("\n")
            }

            blockBreaks += text.lastIndex
        }
        fun key(node: Any): String {
            val steps = pathMethod.invoke(null, node) as List<*>
            return steps.joinToString("/") { bindings.stepIndex(requireNotNull(it)).toString() }
        }
        fun visit(node: Any, inheritedGroup: String, depth: Int) {
            require(depth < 256) { "章节嵌套过深" }
            val name = bindings.nodeName(node).lowercase(java.util.Locale.ROOT)
            val nodeKey = key(node)
            if (name in ignored) return
            val block = name in blocks

            if (block) blockBreak()
            val group = if (block) nodeKey else inheritedGroup
            anchors[nodeKey] = text.length
            groups[nodeKey] = group
            val before = text.length
            when {
                name == "#text" -> {
                    val expected = bindings.text(node).trim(' ', '\n')
                    if (expected.isNotEmpty()) {
                        val mapped = runCatching {
                            val (a, b) = sourceRange(node)
                            mapSelectionTextNode(source.substring(a, b), a, expected) { candidate ->
                                entityCache.getOrPut(candidate) { unescape(candidate) }
                            }
                        }.getOrNull()
                        if (mapped == null) add(expected, -2, -2)
                        else {
                            text.append(mapped.text)
                            starts.addAll(mapped.starts); ends.addAll(mapped.ends)
                            if (text.length > before) textNodes[nodeKey] = before until text.length
                        }
                    }
                }
                name == "#cdata" -> add(bindings.text(node).trim(' ', '\n'), -2, -2)
                name == "br" -> {
                    val (a, b) = sourceRange(node)
                    add("\n", a, b)
                }
                name in objects -> add("\ufffc")
                else -> {
                    val children = bindings.children(node)
                    for (child in children) if (child != null) visit(child, group, depth + 1)
                }
            }
            if (block) {
                if (text.length > before) blockBreak()
                groupEnds[group] = text.length
            }
            nodeEnds[nodeKey] = text.length
        }
        visit(body, "0", 0)
        val runEnds = groups.mapValues { (_, group) -> groupEnds[group] ?: text.length }
        return SelectionSourceMap(source, text.toString(), starts.toArray(), ends.toArray(), anchors, runEnds, structuralBreaks, textNodes, nodeEnds, blockBreaks)
    }

    private companion object {
        val ignored = setOf("#comment", "#doctype", "script", "style", "head", "noscript", "template", "rt", "rp")
        val objects = setOf("img", "svg", "image", "video", "audio", "object")
        val blocks = setOf("body", "p", "div", "section", "article", "main", "aside", "header", "footer",
            "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "dl", "dd", "dt", "blockquote",
            "pre", "table", "tr", "td", "th", "figure", "figcaption", "nav", "hr")
    }
}

internal class SelectionInts {
    private var array = IntArray(256)
    private var size = 0
    fun add(value: Int) {
        if (size == array.size) array = array.copyOf(array.size * 2)
        array[size++] = value
    }
    fun removeLast() { require(size > 0); size-- }
    fun addAll(values: IntArray) { for (v in values) add(v) }
    fun toArray(): IntArray = array.copyOf(size)
}

internal class NormalizedSelectionPositions(
    private val original: String,
    private val normalized: String,
    normalizeToken: (String) -> String,
) {
    private data class Segment(val from: Int, val to: Int, val originalFrom: Int, val originalTo: Int)
    private val segments: List<Segment>
    init {
        if (original == normalized) segments = listOf(Segment(0, original.length, 0, original.length))
        else {
            val result = arrayListOf<Segment>()
            val rebuilt = StringBuilder()
            var i = 0
            while (i < original.length) {
                val start = i
                if (original.startsWith("<!--", i)) {
                    val end = original.indexOf("-->", i + 4)
                    require(end >= 0) { "章节注释未闭合" }; i = end + 3
                } else if (original[i] == '<' && i + 1 < original.length &&
                    (original[i + 1].isLetter() || original[i + 1] in "/!?")) {
                    var quote: Char? = null
                    i++
                    while (i < original.length) {
                        val c = original[i++]
                        if (quote != null) { if (c == quote) quote = null }
                        else if (c == '\'' || c == '"') quote = c
                        else if (c == '>') break
                    }
                } else {
                    i++
                    while (i < original.length && original[i] != '<') i++
                }
                val token = original.substring(start, i)
                val converted = normalizeToken(token)
                val from = rebuilt.length
                rebuilt.append(converted)
                result += Segment(from, rebuilt.length, start, i)
            }
            require(rebuilt.toString() == normalized) { "章节预处理无法无损映射，未修改文件" }
            segments = result
        }
    }
    fun original(start: Int, end: Int): Pair<Int, Int> {
        require(start >= 0 && end >= start && end <= normalized.length) { "源位置越界" }
        val segment = segments.firstOrNull { start >= it.from && end <= it.to }
            ?: error("选区文本跨越解析器合并边界")
        require(segment.to - segment.from == segment.originalTo - segment.originalFrom) {
            "选区落在解析器生成的标签中"
        }
        val a = segment.originalFrom + start - segment.from
        val b = segment.originalFrom + end - segment.from
        require(original.substring(a, b) == normalized.substring(start, end))
        return a to b
    }
}
