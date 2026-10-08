package com.reamicro.fix.epub.editor

internal fun selectionLargeChanges(
    old: String, new: String, from: Int, oldEnd: Int, newEnd: Int,
    budget: SelectionDiffBudget, boundary: (Int) -> Boolean,
): List<SelectionTextChange> {
    val coarse = listOf(SelectionTextChange(from, oldEnd, from, newEnd))
    if (budget.depth >= 24) return coarse
    var anchors = selectionMyersAnchors(old, new, from, oldEnd, newEnd, budget)
    if (anchors == null) {
        data class Line(val from: Int, val until: Int, val value: String)
        fun lines(text: String, end: Int): List<Line> {
            val out = arrayListOf<Line>(); var at = from
            while (at < end) {
                val next = text.indexOf('\n', at).let { if (it < 0 || it >= end) end else it + 1 }
                if (next - at >= 4) out += Line(at, next, text.substring(at, next))
                at = next
            }
            return out
        }
        val a = lines(old, oldEnd).groupBy { it.value }
        val b = lines(new, newEnd).groupBy { it.value }
        val candidates = b.values.filter { it.size == 1 && a[it[0].value]?.size == 1 }.map {
            val left = a.getValue(it[0].value)[0]; val right = it[0]
            SelectionTextChange(left.from, left.until, right.from, right.until)
        }.sortedBy { it.newFrom }

        val tails = arrayListOf<Int>(); val prior = IntArray(candidates.size) { -1 }
        for (i in candidates.indices) {
            var low = 0; var high = tails.size
            while (low < high) { val mid = (low + high) ushr 1
                if (candidates[tails[mid]].from < candidates[i].from) low = mid + 1 else high = mid
            }
            if (low > 0) prior[i] = tails[low - 1]
            if (low == tails.size) tails += i else tails[low] = i
        }
        val selected = arrayListOf<SelectionTextChange>()
        var at = tails.lastOrNull() ?: -1
        while (at >= 0) { selected += candidates[at]; at = prior[at] }
        anchors = selected.asReversed()
        if (anchors.isEmpty()) {
            val aa = (from until oldEnd).filter { old[it] == '\n' }
            val bb = (from until newEnd).filter { new[it] == '\n' }
            anchors = aa.zip(bb) { x, y -> SelectionTextChange(x, x + 1, y, y + 1) }
        }
    }
    val safe = anchors.mapNotNull { anchor ->
        var a = anchor.from; var b = anchor.until; var c = anchor.newFrom; var d = anchor.newUntil
        while (a < b && (!boundary(a) || !selectionUnicodeBoundary(new, c))) { a++; c++ }
        while (b > a && (!boundary(b) || !selectionUnicodeBoundary(new, d))) { b--; d-- }
        if (a < b) SelectionTextChange(a, b, c, d) else null
    }
    if (safe.isEmpty()) return coarse
    val result = arrayListOf<SelectionTextChange>()
    var oldAt = from; var newAt = from
    fun gap(a: Int, b: Int) {
        if (oldAt == a && newAt == b) return
        budget.depth++
        try {
            val base = oldAt; val target = newAt
            result += selectionTextChanges(old.substring(oldAt, a), new.substring(newAt, b), budget) { boundary(base + it) }
                .map { SelectionTextChange(base + it.from, base + it.until, target + it.newFrom, target + it.newUntil) }
        } finally { budget.depth-- }
    }
    for (anchor in safe) { gap(anchor.from, anchor.newFrom); oldAt = anchor.until; newAt = anchor.newUntil }
    gap(oldEnd, newEnd)
    return result
}

private fun selectionMyersAnchors(
    old: String, new: String, from: Int, oldEnd: Int, newEnd: Int, budget: SelectionDiffBudget,
): List<SelectionTextChange>? {
    val n = oldEnd - from; val m = newEnd - from
    val limit = minOf(256, n + m)
    if (kotlin.math.abs(n - m) > limit || budget.work <= 0) return null
    val offset = limit + 1
    val v = IntArray(2 * limit + 3)
    val traces = arrayListOf<IntArray>()
    for (d in 0..limit) {
        for (k in -d..d step 2) {
            if (--budget.work < 0) return null
            var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1]))
                v[offset + k + 1] else v[offset + k - 1] + 1
            var y = x - k
            while (x < n && y < m && old[from + x] == new[from + y]) {
                if (--budget.work < 0) return null
                x++; y++
            }
            v[offset + k] = x
            if (x >= n && y >= m) {
                val matches = arrayListOf<SelectionTextChange>()
                x = n; y = m
                for (distance in d downTo 1) {
                    val previous = traces[distance - 1]
                    val diagonal = x - y
                    val before = if (diagonal == -distance || (diagonal != distance &&
                        previous[offset + diagonal - 1] < previous[offset + diagonal + 1])) diagonal + 1 else diagonal - 1
                    val px = previous[offset + before]; val py = px - before
                    val endX = x; val endY = y
                    while (x > px && y > py) { x--; y-- }
                    if (x < endX) matches += SelectionTextChange(from + x, from + endX, from + y, from + endY)
                    x = px; y = py
                }
                if (x > 0 && y > 0) matches += SelectionTextChange(from, from + x, from, from + y)
                return matches.asReversed()
            }
        }
        traces += v.clone()
    }
    return null
}
