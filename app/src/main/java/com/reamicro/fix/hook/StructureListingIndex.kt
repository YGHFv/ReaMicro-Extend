package com.reamicro.fix.hook

/** Build all list lookup tables in one IO task before publishing the immutable snapshot. */
internal class StructureListingIndex<T : Any>(
    entries: List<T>,
    path: (T) -> String,
    group: (T) -> String,
    labels: (T) -> List<String>,
) {
    val entries: List<T> = entries.toList()
    val byPath: Map<String, T>
    val paths: List<String>
    val groups: Map<String, List<T>>
    val labels: Map<String, List<String>>

    init {
        val lookup = LinkedHashMap<String, T>(entries.size)
        val folders = linkedMapOf<String, MutableList<T>>()
        val names = LinkedHashMap<String, List<String>>(entries.size)
        for (entry in this.entries) {
            val key = path(entry)
            check(lookup.put(key, entry) == null) { "Duplicate structure path: $key" }
            folders.getOrPut(group(entry)) { ArrayList() }.add(entry)
            names[key] = labels(entry).toList()
        }
        byPath = lookup
        paths = lookup.keys.toList()
        groups = folders
        this.labels = names
    }
}
