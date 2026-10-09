package com.reamicro.fix.hook.reader

import android.content.Intent
import com.reamicro.fix.tts.ReadAloudIntents
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

internal fun ReadAloudTextPart.mergeWith(next: ReadAloudTextPart): ReadAloudTextPart =
    ReadAloudTextPart(
        text = listOf(text, next.text).joinToString("").trim(),
        highlightText = listOf(highlightText, next.highlightText).joinToString("").trim(),
        startOffset = minOf(startOffset, next.startOffset),
        endOffset = maxOf(endOffset, next.endOffset),
    )

internal fun Char.isReadAloudWhitespace(): Boolean =
    this == ' ' || this == '\t' || this == '\u000B' || this == '\u000C' || this == '\n' || this == '\r'

internal fun Intent.putReadAloudChunkExtras(chunk: List<ReadAloudSegment>): Intent =
    putStringArrayListExtra(ReadAloudIntents.EXTRA_TEXTS, ArrayList(chunk.map { it.text }))
        .putStringArrayListExtra(ReadAloudIntents.EXTRA_HIGHLIGHT_TEXTS, ArrayList(chunk.map { it.highlightText }))
        .putStringArrayListExtra(ReadAloudIntents.EXTRA_TITLES, ArrayList(chunk.map { it.chapterTitle }))
        .putIntegerArrayListExtra(ReadAloudIntents.EXTRA_CHAPTER_INDICES, ArrayList(chunk.map { it.chapterIndex }))
        .putStringArrayListExtra(ReadAloudIntents.EXTRA_START_CFIS, ArrayList(chunk.map { it.startCfi }))
        .putStringArrayListExtra(ReadAloudIntents.EXTRA_END_CFIS, ArrayList(chunk.map { it.endCfi }))

internal fun Intent.setReadAloudService(): Intent =
    setClassName(ReadAloudIntents.MODULE_PACKAGE_NAME, ReadAloudIntents.SERVICE_CLASS_NAME)

internal fun Intent.setReadAloudCommandActivity(): Intent =
    setClassName(ReadAloudIntents.MODULE_PACKAGE_NAME, ReadAloudIntents.COMMAND_ACTIVITY_CLASS_NAME)

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
