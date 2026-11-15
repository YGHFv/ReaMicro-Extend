package com.reamicro.fix.hook
/** Keep in-flight request copies recognizable; bound retained URLs and clear on module reload. */
internal class CoverSources(private val capacity: Int = 1_024) {
    private val sources = LinkedHashMap<String, Unit>(64, 0.75f, true)

    init { require(capacity > 0) }

    @Synchronized fun remember(value: Any?) {
        if (value is String && value.isNotBlank()) {
            sources[value] = Unit
            while (sources.size > capacity) {
                sources.keys.iterator().also { it.next(); it.remove() }
            }
        }
    }

    @Synchronized fun contains(value: Any?): Boolean = value is String && sources[value] != null
    @Synchronized fun clear() = sources.clear()
}
