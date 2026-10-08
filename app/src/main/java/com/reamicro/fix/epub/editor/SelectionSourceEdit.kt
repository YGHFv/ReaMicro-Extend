package com.reamicro.fix.epub.editor

internal data class SelectionOffsetMapping(val from: Int, val until: Int, val newOffset: Int)

internal data class SelectionSourceEdit(
    val text: String,
    val patches: List<SelectionSourcePatch>,
    val offsets: List<SelectionOffsetMapping> = emptyList(),
) {
    private val shifts: IntArray by lazy {
        IntArray(patches.size + 1).also { values ->
            patches.forEachIndexed { i, patch ->
                values[i + 1] = values[i] + patch.replacement.length - (patch.end - patch.start)
            }
        }
    }
    fun translateOffset(oldOffset: Int): Int {
        require(oldOffset >= 0)
        var low = 0
        var high = offsets.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val mapping = offsets[middle]
            when {
                oldOffset < mapping.from -> high = middle - 1
                oldOffset >= mapping.until -> low = middle + 1
                else -> return mapping.newOffset.coerceIn(0, text.length)
            }
        }
        return translateBoundary(oldOffset)
    }

    fun translateBoundary(oldOffset: Int, beforeInsertion: Boolean = false): Int {
        require(oldOffset >= 0)
        var low = 0
        var high = patches.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (patches[middle].start < oldOffset ||
                (!beforeInsertion && patches[middle].start == oldOffset)) low = middle + 1 else high = middle
        }
        if (beforeInsertion && low < patches.size && patches[low].start == oldOffset)
            return (oldOffset + shifts[low]).coerceIn(0, text.length)
        val index = low - 1
        if (index >= 0 && oldOffset < patches[index].end)
            return (patches[index].start + shifts[index]).coerceIn(0, text.length)
        return (oldOffset + shifts[low]).coerceIn(0, text.length)
    }
}

internal fun holdSelectionPagerTransition(
    sameSession: Boolean, samePage: Boolean, now: Long, deadline: Long,
): Boolean = sameSession && !samePage && now < deadline
