package com.reamicro.fix.core

/** Null means the native search is cleared; removing extension rows must preserve that state. */
internal fun mergeHomeSearchResults(
    native: Map<*, *>?,
    changes: Map<Int, List<*>?>,
): Map<Any?, Any?>? {
    val merged = LinkedHashMap<Any?, Any?>()
    native?.forEach { (key, value) -> merged[key] = value }
    changes.forEach { (type, results) ->
        when {
            results == null -> Unit
            results.isEmpty() -> merged.remove(type)
            else -> merged[type] = results
        }
    }
    return if (native == null && merged.isEmpty()) null else merged
}
