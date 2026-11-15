package com.reamicro.fix.epub.editor

import java.util.concurrent.ConcurrentHashMap

/** Read chapter fallbacks only for visible rows or a requested complete search index. */
internal class EpubChapterLabelIndex(
    toc: Map<String, List<String>>,
    private val readTitle: (String, () -> Unit) -> String,
) {
    val tocNames: Map<String, List<String>> =
        toc.mapValues { (_, names) -> names.filter(String::isNotBlank).distinct().toList() }
    private val fallbacks = ConcurrentHashMap<String, List<String>>()

    fun names(path: String, checkCancelled: () -> Unit = {}): List<String> {
        checkCancelled()
        tocNames[path]?.takeIf { it.isNotEmpty() }?.let { return it }
        return fallbacks.computeIfAbsent(path) {
            val title = readTitle(path, checkCancelled)
            checkCancelled()
            if (title.isBlank()) emptyList() else listOf(title)
        }
    }

    fun complete(paths: List<String>, checkCancelled: () -> Unit = {}): Map<String, List<String>> =
        paths.associateWith { names(it, checkCancelled) }
}
