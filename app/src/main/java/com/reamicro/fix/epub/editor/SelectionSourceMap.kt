package com.reamicro.fix.epub.editor

internal data class SelectionCfiPoint(
    val spine: Int, val itemRef: Int, val steps: List<Int>, val offset: Int, val offsetIndex: Int = 1, val bodyIndex: Int = 4,
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

    val editorText: String = quote,
    val projection: SelectionSourceText,

    val blockBreaks: Set<Int> = emptySet(),
    val projectedFrom: Int = 0,
    val projectedUntil: Int = 0,

    val blockOrigins: Map<Int, Pair<Int, Int>> = emptyMap(),
    val renderedStreams: Boolean = false,
) {
    fun replace(newText: String): String = edit(newText).text
    fun edit(newText: String): SelectionSourceEdit {
        require(!newText.contains('\u0000')) { "替换文本包含无效字符" }

        if (newText == editorText) return SelectionSourceEdit(source, emptyList())
        return editSelectionProjection(this, newText)
    }
}

internal class SelectionSourceMap @JvmOverloads constructor(
    private val source: String,
    private val text: String,
    private val starts: IntArray,
    private val ends: IntArray,
    private val anchors: Map<String, Int>,
    private val runEnds: Map<String, Int>,
    private val structuralBreaks: Set<Int> = emptySet(),
    private val textNodes: Map<String, IntRange> = emptyMap(),
    private val nodeEnds: Map<String, Int> = emptyMap(),
    private val blockBreaks: Set<Int> = structuralBreaks,
    private val blockOrigins: Map<Int, Pair<Int, Int>> = emptyMap(),
    private val strictReadingRuns: Boolean = false,
    private val bodyIndex: Int = 4,
) {

    fun searchText(): String = text
    private val indexedSearchRuns: List<SelectionTextRun> by lazy {
        textNodes.map { (key, range) ->
            SelectionTextRun(range.first, range.last + 1, key.split('/').map(String::toInt))
        }.sortedBy { it.from }
    }
    fun searchRuns(): List<SelectionTextRun> = indexedSearchRuns

    fun sourceEndOffset(point: SelectionCfiPoint): Int? {
        if (point.bodyIndex != bodyIndex || point.offsetIndex != 1 || point.offset < 0) return null
        val base = anchors[point.key] ?: return null
        val limit = runEnds[point.key] ?: return null
        val position = base.toLong() + point.offset
        if (position > limit || position > text.length) return null
        if (point.offset == 0) return starts.getOrNull(base)?.takeIf { it >= 0 }
        return ends.getOrNull(position.toInt() - 1)?.takeIf { it >= 0 }
    }

    fun pointAtSourceEndOffset(offset: Int, spine: Int, itemRef: Int): SelectionCfiPoint? {
        val index = ends.indexOfLast { it == offset && it >= 0 }
        if (index < 0) return null
        val run = textNodes.entries.firstOrNull { index in it.value } ?: return null
        return SelectionCfiPoint(spine, itemRef, run.key.split('/').map(String::toInt), index - run.value.first + 1, bodyIndex = bodyIndex)
    }

    fun verifyRenderedSlice(slice: SelectionRenderedSlice) {
        val point = slice.start
        val range = textNodes[point.key] ?: error("选区不属于可回写的宿主阅读流")
        require(point.bodyIndex == bodyIndex && point.offsetIndex == 1 && point.offset >= 0) { "选区包含宿主生成的排版内容" }
        val from = range.first.toLong() + point.offset
        val until = from + slice.text.length
        require(from >= range.first && until <= range.last.toLong() + 1) { "阅读页片段长度与原文渲染流不一致" }
        fun objects(value: String) = value.replace('�', '￼')
        require(objects(text.substring(from.toInt(), until.toInt())) == objects(slice.text)) {
            "真实阅读页与源文件渲染流不一致，请刷新后重新选择；特殊排版请使用结构编辑器"
        }
    }

    fun sourceOffset(point: SelectionCfiPoint): Int? {
        if (point.bodyIndex != bodyIndex || point.offsetIndex != 1 || point.offset < 0) return null
        val base = anchors[point.key] ?: return null
        val limit = runEnds[point.key] ?: return null
        val position = base.toLong() + point.offset
        if (position > limit || position >= text.length) return null

        for (i in position.toInt() until text.length) {
            if (starts[i] == -2) return null
            if (starts[i] >= 0) return starts[i]
        }
        return null
    }

    fun sourceOffsetAfterNode(point: SelectionCfiPoint): Int? {
        if (point.bodyIndex != bodyIndex || point.offsetIndex != 1 || point.offset != 0) return null
        val end = nodeEnds[point.key] ?: return null
        if (end !in 0..text.length) return null
        for (i in end until text.length) {
            if (starts[i] == -2) return null
            if (starts[i] >= 0) return starts[i]
        }
        return source.length
    }

    fun pointAtSourceOffset(offset: Int, spine: Int, itemRef: Int): SelectionCfiPoint? {
        var index = -1
        var nodeKey: String? = null
        var nodeStart = 0

        search@ for ((key, range) in textNodes) {
            for (i in range) {
                if (starts[i] < 0 || ends[i] <= starts[i]) continue
                index = i
                nodeKey = key
                nodeStart = range.first
                if (ends[i] > offset) break@search
            }
        }
        if (index < 0 || nodeKey == null) return null
        while (index > nodeStart && starts[index] == starts[index - 1] && ends[index] == ends[index - 1]) index--
        if (index > nodeStart && text[index].isLowSurrogate() && text[index - 1].isHighSurrogate()) index--
        return SelectionCfiPoint(spine, itemRef, nodeKey.split("/").map { it.toInt() }, index - nodeStart, bodyIndex = bodyIndex)
    }

    fun verifyEditedText(plan: SelectionSourcePlan, draft: String, updated: SelectionSourceMap) {
        require(plan.source == source && plan.projectedUntil <= text.length)

        fun visible(value: String) = value.filter {
            (if (strictReadingRuns) it != '\n' && it != '\r' else !it.isWhitespace()) &&
                it != '\ufffc' && it != '\ufffd'
        }
        val expected = visible(text.substring(0, plan.projectedFrom)) + visible(draft) +
            visible(text.substring(plan.projectedUntil))
        require(expected == visible(updated.text)) { "写回后的宿主可见文字与编辑内容不一致，未写入" }
        if (strictReadingRuns) {
            val normalizedDraft = draft.replace("\r\n", "\n").replace('\r', '\n')
            val wanted = text.substring(0, plan.projectedFrom) + normalizedDraft + text.substring(plan.projectedUntil)
            fun lines(value: String) = value.split('\n').filter { it.isNotEmpty() }

            require(lines(wanted) == lines(updated.text)) { "写回后的段落/换行与编辑内容不一致，未写入" }
            if (normalizedDraft.count { it == '\n' } > plan.editorText.count { it == '\n' }) {
                require(updated.text.count { it == '\n' } >= wanted.count { it == '\n' }) {
                    "新增换行被宿主裁掉，请在下一段开头插入换行（未写入）"
                }
            }
        }
    }
    fun plan(start: SelectionCfiPoint, end: SelectionCfiPoint, quote: String): SelectionSourcePlan {
        require(quote.isNotBlank()) { "没有可编辑的选区文本" }
        require(start.spine == end.spine && start.itemRef == end.itemRef && start.bodyIndex == end.bodyIndex) { "暂不支持跨章节修改，请分章节选择" }
        fun position(point: SelectionCfiPoint): Int {
            require(point.bodyIndex == bodyIndex && point.offsetIndex == 1 && point.steps.isNotEmpty() && point.steps.all { it > 0 && it < Int.MAX_VALUE }) {
                "选区包含排版生成的内容，不能直接写回原文件"
            }
            require(!strictReadingRuns || point.key in textNodes) { "选区不是宿主文本流锚点，未写入" }
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

        val selectedStarts = IntArray(projected.length)
        val selectedEnds = IntArray(projected.length)
        val selectedBreaks = hashSetOf<Int>()
        var visibleIndex = 0
        for (i in a until b) {
            if (text[i] == '\ufffc' || text[i] == '\ufffd') continue
            require(visibleIndex < projected.length) { "选区换行映射不一致，未写入" }
            selectedStarts[visibleIndex] = starts[i]
            selectedEnds[visibleIndex] = ends[i]
            if (i in blockBreaks) selectedBreaks += visibleIndex
            visibleIndex++
        }
        require(visibleIndex == projected.length) { "选区换行映射不一致，未写入" }
        val projection = SelectionSourceText(projected, selectedStarts, selectedEnds)
        return SelectionSourcePlan(source, ranges, quote, start, end, editorText = projected,
            projection = projection, blockBreaks = selectedBreaks, projectedFrom = a, projectedUntil = b,
            blockOrigins = blockOrigins.filterKeys { it in a until b }.mapKeys { it.key - a },
            renderedStreams = strictReadingRuns)
    }
}

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
