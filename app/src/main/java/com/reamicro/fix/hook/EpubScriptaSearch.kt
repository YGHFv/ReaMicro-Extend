package com.reamicro.fix.hook

import com.reamicro.fix.epub.editor.EpubTextSnapshot
import com.reamicro.fix.epub.editor.EpubTextFiles
import com.reamicro.fix.epub.editor.usesScriptaEpubEditor
import java.io.File
import java.util.Locale

internal enum class EpubSearchScope {
    CURRENT,
    HTML,
}

internal data class EpubSearchRange(val start: Int, val end: Int)

internal data class EpubSearchDocument(
    val path: String,
    val name: String,
    val text: String,
    val snapshot: EpubTextSnapshot?,
    val matches: List<EpubSearchRange>,
)

internal data class EpubReplaceResult(val text: String, val count: Int)

internal object EpubScriptaSearch {
    fun loadDocuments(
        root: File,
        currentPath: String,
        currentText: String,
        scope: EpubSearchScope,
        query: String,
        regex: Boolean,
        textOnly: Boolean,
    ): List<EpubSearchDocument> {
        val paths = when (scope) {
            EpubSearchScope.CURRENT -> listOf(currentPath)
            EpubSearchScope.HTML -> textFiles(root).filter(::isHtml).map { relativePath(root, it) }
        }.distinct()
        return paths.mapNotNull { path ->
            if (path == currentPath) {
                document(path, currentPath.substringAfterLast('/'), currentText, null, query, regex, textOnly)
            } else {
                try {
                    val file = resolve(root, path)
                    val loaded = EpubTextFiles.load(file)
                    document(path, file.name, loaded.text, loaded.snapshot, query, regex, textOnly)
                } catch (error: Exception) {
                    throw IllegalStateException("无法读取 $path，本次全书操作已取消", error)
                }
            }
        }.filter { it.matches.isNotEmpty() }
    }

    fun replace(text: String, query: String, replacement: String, regex: Boolean, textOnly: Boolean): EpubReplaceResult {
        val source = source(text, textOnly)
        val ranges = mapRanges(findRanges(source.text, query, regex), source.map, text.length)
        if (ranges.isEmpty()) return EpubReplaceResult(text, 0)
        val result = StringBuilder(text.length + replacement.length * ranges.size)
        var cursor = 0
        ranges.forEach { range ->
            result.append(text, cursor, range.start)
            result.append(replacement)
            cursor = range.end
        }
        result.append(text, cursor, text.length)
        return EpubReplaceResult(result.toString(), ranges.size)
    }

    fun ranges(text: String, query: String, regex: Boolean, textOnly: Boolean): List<EpubSearchRange> {
        val source = source(text, textOnly)
        return mapRanges(findRanges(source.text, query, regex), source.map, text.length)
    }

    private fun document(
        path: String,
        name: String,
        text: String,
        snapshot: EpubTextSnapshot?,
        query: String,
        regex: Boolean,
        textOnly: Boolean,
    ): EpubSearchDocument = EpubSearchDocument(
        path = path,
        name = name,
        text = text,
        snapshot = snapshot,
        matches = ranges(text, query, regex, textOnly),
    )

    private data class SearchSource(val text: String, val map: IntArray?)

    private fun source(content: String, textOnly: Boolean): SearchSource {
        if (!textOnly) return SearchSource(content, null)
        val output = StringBuilder(content.length)
        val mapping = ArrayList<Int>(content.length)
        var inTag = false
        var spacePending = false
        content.forEachIndexed { index, character ->
            when {
                character == '<' && !inTag -> {
                    inTag = true
                    spacePending = output.isNotEmpty() && output.last() != ' ' && output.last() != '\n'
                }
                inTag && character == '>' -> {
                    inTag = false
                    if (spacePending) {
                        output.append(' ')
                        mapping += index
                        spacePending = false
                    }
                }
                inTag && character == '\n' -> {
                    output.append('\n')
                    mapping += index
                    spacePending = false
                }
                !inTag -> {
                    output.append(character)
                    mapping += index
                }
            }
        }
        return SearchSource(output.toString(), mapping.toIntArray())
    }

    private fun findRanges(value: String, query: String, regex: Boolean): List<EpubSearchRange> {
        if (query.isEmpty()) return emptyList()
        if (!regex) {

            val source = value
            val needle = query
            val result = ArrayList<EpubSearchRange>()
            var offset = 0
            while (true) {
                val index = source.indexOf(needle, offset, ignoreCase = true)
                if (index < 0) break
                result += EpubSearchRange(index, index + needle.length)
                offset = index + needle.length
            }
            return result
        }
        val pattern = try { Regex(query, RegexOption.IGNORE_CASE) }
        catch (_: Exception) { throw IllegalArgumentException("正则无效") }
        val result = ArrayList<EpubSearchRange>()
        var offset = 0
        while (offset <= value.length) {
            val match = pattern.find(value, offset) ?: break
            if (match.value.isNotEmpty()) {
                result += EpubSearchRange(match.range.first, match.range.last + 1)
            }
            val next = match.range.last + 1
            offset = if (next > offset) next else offset + 1
        }
        return result
    }

    private fun mapRanges(
        ranges: List<EpubSearchRange>,
        map: IntArray?,
        contentLength: Int,
    ): List<EpubSearchRange> {
        if (map == null) return ranges
        return ranges.mapNotNull { range ->
            if (map.isEmpty()) return@mapNotNull null
            val startIndex = range.start.coerceIn(0, map.lastIndex)
            val endIndex = (range.end - 1).coerceIn(range.start, map.lastIndex)
            val start = map[startIndex].coerceIn(0, contentLength)
            val end = (map[endIndex] + 1).coerceIn(start, contentLength)
            EpubSearchRange(start, end)
        }
    }

    private fun textFiles(root: File): List<File> =
        com.reamicro.fix.epub.editor.EpubStorageFiles(root).visibleFiles()
            .filter { usesScriptaEpubEditor(it.name) }
            .sortedBy { relativePath(root, it).lowercase(Locale.ROOT) }

    private fun isHtml(file: File): Boolean = file.extension.lowercase(Locale.ROOT) in setOf("html", "htm", "xhtml")

    private fun resolve(root: File, path: String): File = File(root, path).canonicalFile.also { file ->
        val rootPath = root.canonicalFile.toPath()
        require(file.toPath().startsWith(rootPath) && file.isFile) { "文件不存在" }
    }
    private fun relativePath(root: File, file: File): String = file.canonicalFile.path
        .removePrefix(root.canonicalFile.path)
        .trimStart(File.separatorChar)
        .replace(File.separatorChar, '/')

}
