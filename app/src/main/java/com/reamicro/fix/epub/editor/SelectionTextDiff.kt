package com.reamicro.fix.epub.editor

internal data class SelectionTextChange(val from: Int, val until: Int, val newFrom: Int, val newUntil: Int)

internal class SelectionDiffBudget(var cells: Int = 1_048_576, var work: Int = 4_194_304, var depth: Int = 0)

internal fun selectionTextChanges(
    old: String, new: String, budget: SelectionDiffBudget,
    oldBoundary: (Int) -> Boolean,
): List<SelectionTextChange> {
    if (old == new) return emptyList()
    fun newBoundary(at: Int) = selectionUnicodeBoundary(new, at)
    var prefix = old.commonPrefixWith(new).length
    while (prefix > 0 && (!oldBoundary(prefix) || !newBoundary(prefix))) prefix--
    var suffix = 0
    while (suffix < old.length - prefix && suffix < new.length - prefix &&
        old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) suffix++
    while (suffix > 0 && (!oldBoundary(old.length - suffix) || !newBoundary(new.length - suffix))) suffix--
    val oldEnd = old.length - suffix
    val newEnd = new.length - suffix
    val coarse = listOf(SelectionTextChange(prefix, oldEnd, prefix, newEnd))
    val n = oldEnd - prefix
    val m = newEnd - prefix
    val cells = (n.toLong() + 1) * (m.toLong() + 1)
    if (n == 0 || m == 0) return coarse
    if (cells > 262_144 || cells > budget.cells)
        return selectionLargeChanges(old, new, prefix, oldEnd, newEnd, budget, oldBoundary)
    budget.cells -= cells.toInt()

    fun substitution(i: Int, j: Int): Int {
        val a = old[prefix + i]; val b = new[prefix + j]
        return if (a == b) 0 else if (a == '\n' || b == '\n') 3 else 1
    }
    val width = m + 1
    val costs = IntArray(cells.toInt())
    for (i in 0..n) costs[i * width + m] = n - i
    for (j in 0..m) costs[n * width + j] = m - j
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
        val changed = costs[(i + 1) * width + j + 1] + substitution(i, j)
        costs[i * width + j] = minOf(changed,
            costs[(i + 1) * width + j] + 1, costs[i * width + j + 1] + 1)
    }
    val changes = arrayListOf<SelectionTextChange>()
    var cursor = prefix
    var newCursor = prefix
    var i = 0
    var j = 0
    while (i < n && j < m) {
        if (old[prefix + i] == new[prefix + j]) {
            val beginI = i
            val beginJ = j
            while (i < n && j < m && old[prefix + i] == new[prefix + j]) { i++; j++ }
            var a = prefix + beginI
            var c = prefix + beginJ
            var b = prefix + i
            var d = prefix + j

            while (a < b && (!oldBoundary(a) || !newBoundary(c))) { a++; c++ }
            while (b > a && (!oldBoundary(b) || !newBoundary(d))) { b--; d-- }
            if (a < b) {
                if (cursor < a || newCursor < c) changes += SelectionTextChange(cursor, a, newCursor, c)
                cursor = b; newCursor = d
            }
        } else {
            val cost = costs[i * width + j]
            when {
                cost == costs[(i + 1) * width + j + 1] + substitution(i, j) -> { i++; j++ }
                cost == costs[(i + 1) * width + j] + 1 -> i++
                else -> j++
            }
        }
    }
    if (cursor < oldEnd || newCursor < newEnd) changes += SelectionTextChange(cursor, oldEnd, newCursor, newEnd)
    return changes
}

internal fun selectionUnicodeBoundary(value: String, index: Int): Boolean =
    index <= 0 || index >= value.length ||
        !(value[index - 1].isHighSurrogate() && value[index].isLowSurrogate())
