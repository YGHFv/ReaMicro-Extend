package com.reamicro.fix.epub.editor

/** 2.3.2 uses Node.siblingIndex()+1 paths, NOT standard EPUB-CFI odd/even node numbering. */
internal data class SelectionCfiPoint(
    val spine: Int, val itemRef: Int, val steps: List<Int>, val offset: Int, val offsetIndex: Int = 1,
) {
    val key: String get() = steps.joinToString("/")
}
internal data class SelectionSourceText(val text: String, val starts: IntArray, val ends: IntArray)
internal data class SelectionSourcePatch(val start: Int, val end: Int, val replacement: String)
internal data class SelectionSourcePlan(
    val source: String,
    val ranges: List<IntRange>,
    val quote: String,
    val start: SelectionCfiPoint,
    val end: SelectionCfiPoint,
) {
    fun replace(newText: String): String {
        require(!newText.contains('\u0000')) { "替换文本包含无效字符" }
        val escaped = newText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br/>")
        val editRanges = if (newText.isEmpty()) selectionEmptyElementRanges(source, ranges) else ranges
        val patches = editRanges.mapIndexed { index, range ->
            SelectionSourcePatch(range.first, range.last + 1, if (index == 0) escaped else "")
        }
        return buildString(source.length + escaped.length) {
            var cursor = 0
            for (patch in patches) {
                require(patch.start >= cursor && patch.end <= source.length)
                append(source, cursor, patch.start)
                append(patch.replacement)
                cursor = patch.end
            }
            append(source, cursor, source.length)
        }
    }
}

/** A verified rendered-text projection into the ORIGINAL serialized source. No DOM serialization. */
internal class SelectionSourceMap(
    private val source: String,
    private val text: String,
    private val starts: IntArray,
    private val ends: IntArray,
    private val anchors: Map<String, Int>,
    private val runEnds: Map<String, Int>,
    private val structuralBreaks: Set<Int> = emptySet(),
) {
    fun plan(start: SelectionCfiPoint, end: SelectionCfiPoint, quote: String): SelectionSourcePlan {
        require(quote.isNotBlank()) { "没有可编辑的选区文本" }
        require(start.spine == end.spine && start.itemRef == end.itemRef) { "暂不支持跨章节修改，请分章节选择" }
        fun position(point: SelectionCfiPoint): Int {
            require(point.offsetIndex == 1 && point.steps.isNotEmpty() && point.steps.all { it > 0 && it < Int.MAX_VALUE }) {
                "选区包含排版生成的内容，不能直接写回原文件"
            }
            val base = anchors[point.key] ?: error("选区节点已变化，请刷新本章后重新选择")
            val limit = runEnds[point.key] ?: error("选区节点范围缺失")
            require(point.offset >= 0 && point.offset.toLong() + base <= limit) { "选区字符位置与当前章节不一致" }
            return base + point.offset
        }
        val a = position(start)
        val b = position(end)
        require(a < b && b <= text.length) { "选区起止位置无效" }
        fun visible(value: String) = value.filter { it != '\ufffc' && it != '\ufffd' }.replace("\r\n", "\n")
        val expected = visible(quote)
        val projected = visible(text.substring(a, b))
        val withoutStructuralBreaks = visible(buildString {
            for (i in a until b) if (i !in structuralBreaks) append(text[i])
        })
        require(expected == projected || expected == withoutStructuralBreaks) {
            "选区原文与源文件锚点不一致，可能已修改或包含特殊排版；未写入"
        }
        // Never split a surrogate pair or part of a multi-codepoint HTML entity.
        fun checkBoundary(index: Int) {
            if (index <= 0 || index >= text.length) return
            require(!(text[index - 1].isHighSurrogate() && text[index].isLowSurrogate())) { "选区截断了 Unicode 字符" }
            require(!(starts[index] >= 0 && starts[index] == starts[index - 1] && ends[index] == ends[index - 1])) {
                "选区截断了 HTML 实体，请选择完整字符"
            }
        }
        checkBoundary(a); checkBoundary(b)
        require((a until b).none { starts[it] == -2 }) {
            "选区包含无法无损映射的内容，请使用图书结构编辑器；未写入"
        }
        val ranges = mutableListOf<IntRange>()
        for (i in a until b) {
            if (starts[i] < 0 || ends[i] <= starts[i] || text[i] == '\ufffc' || text[i] == '\ufffd') continue
            val next = starts[i] until ends[i]
            val prior = ranges.lastOrNull()
            if (prior != null && next.first <= prior.last + 1) {
                require(next.first >= prior.first) { "解析节点顺序与原文件不一致，未写入" }
                ranges[ranges.lastIndex] = prior.first..maxOf(prior.last, next.last)
            } else {
                require(prior == null || next.first > prior.last) { "解析节点顺序异常" }
                ranges += next
            }
        }
        require(ranges.isNotEmpty()) { "选区只有排版占位符，不能写回" }
        return SelectionSourcePlan(source, ranges, quote, start, end)
    }
}

/** Match the exact TextNode.text().trim(' ', '\\n') value produced by the host. */
internal fun mapSelectionTextNode(
    raw: String,
    sourceStart: Int,
    expected: String,
    decodeEntity: (String) -> String,
): SelectionSourceText {
    val chars = StringBuilder()
    val begins = ArrayList<Int>()
    val limits = ArrayList<Int>()
    fun add(value: String, start: Int, end: Int) {
        for (c in value) { chars.append(c); begins += sourceStart + start; limits += sourceStart + end }
    }
    var i = 0
    while (i < raw.length) {
        if (raw[i] == '&') {
            var j = i + 1
            while (j < raw.length && j - i < 64 && (raw[j].isLetterOrDigit() || raw[j] == '#')) j++
            if (j < raw.length && raw[j] == ';') j++
            val candidate = raw.substring(i, j)
            val decoded = decodeEntity(candidate)
            if (decoded != candidate) {
                var suffix = 0
                while (suffix < decoded.length && suffix < candidate.length - 1 &&
                    decoded[decoded.lastIndex - suffix] == candidate[candidate.lastIndex - suffix]) suffix++
                val consumed = candidate.length - suffix
                val value = decoded.substring(0, decoded.length - suffix)
                if (value.isNotEmpty()) { add(value, i, i + consumed); i += consumed; continue }
            }
        }
        if (raw[i] == '\r') {
            val end = if (i + 1 < raw.length && raw[i + 1] == '\n') i + 2 else i + 1
            add("\n", i, end); i = end
        } else { add(raw[i].toString(), i, i + 1); i++ }
    }
    fun normalized(nbsp: Boolean): SelectionSourceText {
        val value = StringBuilder()
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        fun whitespace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000c' || (nbsp && c == '\u00a0')
        var at = 0
        while (at < chars.length) {
            val first = at
            if (whitespace(chars[at])) {
                while (at < chars.length && whitespace(chars[at])) at++
                value.append(' '); starts += begins[first]; ends += limits[at - 1]
            } else {
                value.append(chars[at]); starts += begins[at]; ends += limits[at]; at++
            }
        }
        var from = 0; var to = value.length
        while (from < to && (value[from] == ' ' || value[from] == '\n')) from++
        while (to > from && (value[to - 1] == ' ' || value[to - 1] == '\n')) to--
        return SelectionSourceText(value.substring(from, to),
            starts.subList(from, to).toIntArray(), ends.subList(from, to).toIntArray())
    }
    val first = normalized(true)
    val mapped = if (first.text == expected) first else normalized(false).takeIf { it.text == expected }
    require(mapped != null) { "文本节点的实体/空白映射与宿主不一致" }
    return mapped
}