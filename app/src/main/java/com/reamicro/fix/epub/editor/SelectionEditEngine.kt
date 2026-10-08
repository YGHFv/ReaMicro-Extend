package com.reamicro.fix.epub.editor

internal fun editSelectionProjection(plan: SelectionSourcePlan, draft: String): SelectionSourceEdit {
    val value = draft.replace("\r\n", "\n").replace('\r', '\n')
    if (value == plan.editorText) return SelectionSourceEdit(plan.source, emptyList())
    var codeUnit = 0
    while (codeUnit < value.length) {
        val c = value[codeUnit++]
        if (c.isHighSurrogate()) {
            require(codeUnit < value.length && value[codeUnit].isLowSurrogate()) { "输入含不完整的 Unicode 字符，未写入" }
            codeUnit++
        } else require(!c.isLowSurrogate()) { "输入含不完整的 Unicode 字符，未写入" }
    }
    val projection = plan.projection
    val old = projection.text
    fun boundary(i: Int): Boolean = selectionUnicodeBoundary(old, i) &&
        (i <= 0 || i >= old.length || projection.starts[i] < 0 ||
            projection.starts[i] != projection.starts[i - 1] || projection.ends[i] != projection.ends[i - 1])
    val changes = selectionTextChanges(old, value, SelectionDiffBudget(), ::boundary)
    val patches = arrayListOf<SelectionSourcePatch>()
    val removedBreaks = hashSetOf<Int>()
    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\n", "<br/>")
    fun moveTrailingBreaks(at: Int, text: String): String {
        if (!plan.renderedStreams || at !in plan.blockBreaks || projection.starts[at] >= 0 ||
            !text.endsWith('\n')) return text
        val next = (at + 1 until old.length).firstOrNull { projection.starts[it] >= 0 }
            ?: error("段尾换行会被宿主裁掉，请同时选中下一段再编辑（未写入）")
        val clean = text.trimEnd('\n')
        val extra = text.length - clean.length
        patches += SelectionSourcePatch(projection.starts[next], projection.starts[next], "<br/>".repeat(extra))
        return clean
    }
    fun insert(at: Int, input: String) {
        val text = if (input.any { it != '\n' }) moveTrailingBreaks(at, input) else input
        if (text.isEmpty()) return
        var extraBoundary = false
        val target = when {
            at < old.length && projection.starts[at] >= 0 -> projection.starts[at]

            text.all { it == '\n' } && (at until old.length).any { projection.starts[it] >= 0 } ->
                projection.starts[(at until old.length).first { projection.starts[it] >= 0 }]
            at > 0 && projection.ends[at - 1] >= 0 -> {
                extraBoundary = at < old.length && at in plan.blockBreaks && text.endsWith('\n')
                projection.ends[at - 1]
            }
            else -> {
                require(text.all { it == '\n' }) { "不能向选区外的段落插入文字，请将该段一并选中（未写入）" }
                val prior = (at - 1 downTo 0).firstOrNull { projection.ends[it] >= 0 }
                    ?: error("选区缺少可插入的原文位置")
                extraBoundary = true
                projection.ends[prior]
            }
        }
        patches += SelectionSourcePatch(target, target, escape(text) + if (extraBoundary) "<br/>" else "")
    }
    data class UnitSpan(val from: Int, val until: Int, val start: Int, val end: Int)
    for (change in changes) {
        val units = arrayListOf<UnitSpan>()
        var at = change.from
        while (at < change.until) {
            if (at in plan.blockBreaks) removedBreaks += at
            if (projection.starts[at] < 0) { at++; continue }
            val begin = at++
            while (at < change.until && !boundary(at)) at++
            units += UnitSpan(begin, at, projection.starts[begin], projection.ends[at - 1])
        }
        val piece = value.substring(change.newFrom, change.newUntil)
        if (units.isEmpty()) { insert(change.from, piece); continue }
        var newAt = 0
        for ((index, unit) in units.withIndex()) {
            val end = if (index == units.lastIndex) piece.length else
                if (newAt < piece.length) newAt + Character.charCount(Character.codePointAt(piece, newAt)) else newAt
            val replacement = piece.substring(newAt, end)
            if (replacement != old.substring(unit.from, unit.until)) {
                val persisted = if (unit.until !in removedBreaks) moveTrailingBreaks(unit.until, replacement) else replacement
                val keepsBlockBreak = !plan.renderedStreams && unit.until in plan.blockBreaks &&
                    projection.starts[unit.until] < 0 && replacement.endsWith('\n')
                patches += SelectionSourcePatch(unit.start, unit.end, escape(persisted) +
                    if (keepsBlockBreak) "<br/>" else "")
            }
            newAt = end
        }
    }
    val ordered = patches.sortedWith(compareBy<SelectionSourcePatch> { it.start }.thenBy { it.end })
    val composer = SelectionPatchComposer(plan.source)
    composer.apply(ordered)
    var delta = 0
    val offsets = arrayListOf<SelectionOffsetMapping>()
    for (patch in ordered) {
        if (patch.end > patch.start) offsets += SelectionOffsetMapping(patch.start, patch.end, patch.start + delta)
        delta += patch.replacement.length - (patch.end - patch.start)
    }
    composer.setOffsets(offsets)

    val deletions = ordered.filter { it.replacement.isEmpty() && it.end > it.start }.map { it.start until it.end }
    if (deletions.isNotEmpty()) {
        val nonempty = ordered.filter { it.replacement.isNotEmpty() }
        val expanded = selectionEmptyElementRanges(plan.source, deletions).filter { range ->
            nonempty.none { it.start <= range.last && it.end >= range.first }
        }.map { SelectionSourcePatch(composer.mapOriginal(it.first), composer.mapOriginal(it.last + 1, beforeInsertion = true), "") }
        composer.apply(expanded)
    }
    val original = SelectionMarkupIndex(plan.source)
    cleanSelectionElements(composer, original, ordered)
    mergeSelectionBlocks(composer, original, plan, removedBreaks, value.isEmpty())
    cleanSelectionSourceRows(composer)
    return composer.result()
}

private fun cleanSelectionElements(
    composer: SelectionPatchComposer, original: SelectionMarkupIndex, changes: List<SelectionSourcePatch>,
) {
    if (!original.valid) return
    val changed = changes.filter { it.end > it.start }
    if (changed.isEmpty()) return
    val current = SelectionMarkupIndex(composer.text)
    if (!current.valid) return
    val patches = arrayListOf<SelectionSourcePatch>()
    var covered = -1
    for (element in original.elements) {
        if (element.start < covered || !composer.originalRangeKept(element.start, element.openEnd)) continue

        var low = 0; var high = changed.size
        while (low < high) { val mid = (low + high) ushr 1
            if (changed[mid].end <= element.openEnd) low = mid + 1 else high = mid
        }
        if (low >= changed.size || changed[low].start >= element.closeStart) continue
        val now = current.atStart(composer.mapOriginal(element.start)) ?: continue
        if (now.name != element.name) continue
        val replacement = current.emptyReplacement(now) ?: continue
        if (current.source.substring(now.start, now.end) != replacement) {
            patches += SelectionSourcePatch(now.start, now.end, replacement)
            covered = element.end
        }
    }
    composer.apply(patches)
}

private fun mergeSelectionBlocks(
    composer: SelectionPatchComposer, original: SelectionMarkupIndex, plan: SelectionSourcePlan,
    breaks: Set<Int>, allDeleted: Boolean,
) {
    if (breaks.isEmpty() || allDeleted) return
    val projection = plan.projection
    require(original.valid) { "章节含隐式或不完整标签，不能安全合并段落；请保留分隔换行（未写入）" }
    val parents = hashMapOf<Int, Int>()
    fun root(id: Int): Int {
        var at = id
        while (parents[at] != null && parents[at] != at) at = parents.getValue(at)
        return at
    }
    for (position in breaks.sorted()) {
        val before = (position - 1 downTo 0).firstOrNull { projection.starts[it] >= 0 && projection.text[it] != '\n' }
        val after = (position + 1 until projection.text.length).firstOrNull { projection.starts[it] >= 0 && projection.text[it] != '\n' }

        if (before == null || after == null) continue
        val origin = plan.blockOrigins[position]
        val left = if (origin != null) original.atStart(origin.first)
            ?: error("不能合并缺少源码锚点的阅读容器；请保留换行")
            else original.blockAt(projection.starts[before]) ?: continue
        val right = if (origin != null) original.atStart(origin.second)
            ?: error("不能合并缺少源码锚点的阅读容器；请保留换行")
            else original.blockAt(projection.starts[after]) ?: continue
        require(origin == null || left.id != right.id) { "这是同一容器中的独立渲染流，不能作为普通段落合并" }
        if (left.id == right.id) continue
        val a = root(left.id); val b = root(right.id)
        parents.putIfAbsent(a, a); parents[b] = a
        parents.putIfAbsent(left.id, a); parents.putIfAbsent(right.id, a)
    }
    val current = SelectionMarkupIndex(composer.text)
    require(current.valid) { "修改后的标签结构无法核实，未写入" }
    val patches = arrayListOf<SelectionSourcePatch>()
    for (group in parents.keys.groupBy { root(it) }.values) {
        val survivors = group.mapNotNull { id ->
            val old = original.elements[id]
            if (!composer.originalRangeKept(old.start, old.openEnd)) null else
                current.atStart(composer.mapOriginal(old.start))?.takeIf { current.hasText(it) }
        }.distinctBy { it.start }.sortedBy { it.start }
        survivors.zipWithNext { left, right -> patches += current.merge(left, right) }
    }
    composer.apply(patches)
}

private fun cleanSelectionSourceRows(composer: SelectionPatchComposer) {
    val edit = composer.result()
    val source = composer.text
    val touched = arrayListOf<Int>()
    var delta = 0
    for (patch in edit.patches) {
        val at = patch.start + delta
        if (patch.replacement.isEmpty() && patch.end > patch.start) touched += at
        else if (patch.replacement.startsWith("<span ")) {

            val anchors = SelectionMarkupIndex(patch.replacement)
            val roots = anchors.elements.filter { it.parent < 0 }
            if (anchors.valid && roots.isNotEmpty() && roots.all { node ->
                node.name == "span" && node.attrs.isNotEmpty() &&
                    node.attrs.all { it.name in setOf("id", "name") } && anchors.emptyReplacement(node) != null
            } && roots.joinToString("") { anchors.source.substring(it.start, it.end) } == patch.replacement) {
                touched += at
                val end = at + patch.replacement.length
                val lineEnd = source.indexOf('\n', end)
                if (lineEnd >= 0 && source.substring(end, lineEnd).isBlank()) touched += lineEnd + 1
            }
        }
        delta += patch.replacement.length - (patch.end - patch.start)
    }
    if (touched.isEmpty()) return
    touched.sort()
    val index = SelectionMarkupIndex(source)
    if (!index.valid) return
    val preserved = index.elements.filter { it.preserved }
    val patches = arrayListOf<SelectionSourcePatch>()
    var cursor = 0
    var begin = -1
    fun finish(end: Int) {
        if (begin >= 0 && touched.any { it in begin..end }) patches += SelectionSourcePatch(begin, end, "")
        begin = -1
    }
    while (cursor < source.length) {
        val next = source.indexOf('\n', cursor).let { if (it < 0) source.length else it + 1 }
        val blank = source.substring(cursor, next).isBlank() && preserved.none { cursor < it.end && next > it.start }
        if (blank) { if (begin < 0) begin = cursor } else finish(cursor)
        cursor = next
    }
    finish(source.length)
    composer.apply(patches)
}
