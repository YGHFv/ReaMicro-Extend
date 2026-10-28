package com.reamicro.fix.hook.reader

import com.reamicro.fix.online.search.decodeOnlineHtmlEntities
import java.io.File
import java.util.Locale
import com.reamicro.fix.hook.reader.*

internal fun List<String>.indexOfFirstIndexed(predicate: (Int, String) -> Boolean): Int {
    forEachIndexed { index, value ->
        if (predicate(index, value)) return index
    }
    return -1
}

internal fun Map<String, List<String>>.titlePathForHref(href: String): List<String> {
    if (href.isBlank()) return emptyList()
    get(href)?.let { return it }
    get(href.substringBefore('#'))?.let { return it }
    return entries.firstOrNull { (key, _) -> sameSearchContentPath(href, key) }?.value.orEmpty()
}

internal fun Map<String, List<String>>.titlePathForFile(root: File, file: File): List<String> {
    val relative = normalizePath(relativePath(root, file))
    return titlePathForHref(relative)
}

internal fun List<String>.dedupeAdjacent(): List<String> {
    val result = ArrayList<String>(size)
    forEach { value ->
        if (result.lastOrNull() != value) result.add(value)
    }
    return result
}

internal fun File.isCoverImageFile(): Boolean =
    isFile && extension.lowercase(Locale.ROOT) in COVER_IMAGE_EXTENSIONS

internal fun String.normalizeChapterTitle(): String =
    replace(Regex("<[^>]+>"), " ")
        .decodeBasicHtmlEntities()
        .replace(Regex("\\s+"), " ")
        .trim()

internal fun String.decodeBasicHtmlEntities(): String =
    decodeOnlineHtmlEntities()

internal fun File.isTextContentFile(): Boolean =
    isFile && extension.lowercase() in setOf("xhtml", "html", "htm", "xml", "txt")

internal fun File.canonicalFileSafe(): File? = runCatching { canonicalFile }.getOrNull()
