package com.reamicro.fix.epub.editor

internal fun selectionSameChapterPage(
    mapping: Map<Int, Pair<Int, Int>>, available: Set<Int>,
    spine: Int, oldLocalPage: Int, contained: Set<Int>,
): Int? {
    val candidates = mapping.entries.filter { it.key in available && it.value.first == spine }
    return candidates.firstOrNull { it.key in contained }?.key
        ?: candidates.minByOrNull { kotlin.math.abs(it.value.second.toLong() - oldLocalPage.toLong()) }?.key
}
