package com.reamicro.fix.reader

internal data class HighlightParagraphWindow<T>(val elements: List<T>, val currentIndex: Int)

internal fun <T : Any> highlightParagraphWindow(
    current: T,
    radius: Int,
    previous: (T) -> T?,
    next: (T) -> T?,
): HighlightParagraphWindow<T> {
    val before = ArrayList<T>()
    var cursor = current
    for (index in 0 until radius) {
        cursor = previous(cursor) ?: break
        before.add(cursor)
    }
    val elements = ArrayList<T>(before.size + radius + 1)
    elements.addAll(before.asReversed())
    elements.add(current)
    cursor = current
    for (index in 0 until radius) {
        cursor = next(cursor) ?: break
        elements.add(cursor)
    }
    return HighlightParagraphWindow(elements, before.size)
}
