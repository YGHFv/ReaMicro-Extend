package com.reamicro.fix.epub.editor

internal fun selectionWindowOffset(
    visibleSlot: Int, rebuiltSlot: Int, slots: Set<Int>, minimum: Int?, maximum: Int?,
): Int? {
    if (visibleSlot !in 0..9999 || rebuiltSlot !in slots) return null
    val delta = visibleSlot - rebuiltSlot
    if (slots.any { it.toLong() + delta !in 0L..9999L }) return null
    if (minimum != null && minimum.toLong() + delta !in 0L..9999L) return null
    if (maximum != null && maximum.toLong() + delta !in 0L..9999L) return null
    return delta
}

internal fun shiftSelectionWindowArguments(args: Array<Any?>, delta: Int): Array<Any?> {
    require(args.size == 10)
    fun shifted(value: Any?): Int {
        val next = (value as Number).toLong() + delta
        require(next in 0L..9999L)
        return next.toInt()
    }
    val result = args.copyOf()
    result[1] = (args[1] as Map<*, *>).entries.associateTo(linkedMapOf()) { (key, value) ->
        shifted(key) to value
    }
    result[2] = (args[2] as Map<*, *>).entries.associateTo(linkedMapOf()) { (key, value) ->
        key to shifted(value)
    }
    result[4] = (args[4] as Map<*, *>).entries.associateTo(linkedMapOf()) { (key, value) ->
        shifted(key) to value
    }
    result[7] = args[7]?.let(::shifted)
    result[8] = args[8]?.let(::shifted)
    return result
}
