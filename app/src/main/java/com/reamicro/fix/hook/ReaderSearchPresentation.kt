package com.reamicro.fix.hook

import java.io.File

internal object ReaderSearchPresentation {
    private val contentDirectories = setOf("OEBPS", "OPS", "EPUB")
    private val whitespace = Regex("\\s+")
    private val contentFilename = Regex("(?i).*\\.(xhtml|html|htm|xml|txt)$")

    data class Titles(val volume: String, val chapter: String)
    data class Section(val volume: String, val start: Int, val endExclusive: Int)

    fun titles(parts: List<String>, directChapter: String = "", filename: String = ""): Titles {
        val nodes = parts.map { it.replace(whitespace, " ").trim() }.filter { it.isNotBlank() }
        val direct = directChapter.replace(whitespace, " ").trim()
        fun chapterLabel(value: String): String = value.takeUnless {
            it.isBlank() || it == filename || contentFilename.matches(it)
        } ?: "未命名章节"
        // 卷标来自目录父节点，章节来自叶节点；不按文字、空格或分隔符猜测层级。
        return Titles(nodes.dropLast(1).joinToString(" "), chapterLabel(nodes.lastOrNull() ?: direct))
    }

    fun sections(volumes: List<String>): List<Section> = buildList {
        var start = 0
        while (start < volumes.size) {
            val volume = volumes[start]
            var end = start + 1
            while (end < volumes.size && volumes[end] == volume) end++
            add(Section(volume, start, end))
            start = end
        }
    }

    fun listIndex(volumes: List<String>, resultIndex: Int): Int? {
        if (resultIndex !in volumes.indices) return null
        return resultIndex + sections(volumes).count { it.start <= resultIndex }
    }

    fun status(query: String, keyword: String, searching: Boolean, count: Int, error: String?): String? = when {
        error != null -> error
        keyword.isBlank() || query.trim() != keyword -> null
        searching -> "搜索中，已找到 $count 处"
        else -> "共找到 $count 处"
    }

    fun path(file: File, root: File?): String {
        val target = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)

        val container = generateSequence(target.parentFile) { it.parentFile }.take(16)
            .firstOrNull { File(it, "META-INF/container.xml").isFile }
        val base = container ?: root?.let { runCatching { it.canonicalFile }.getOrNull() }
        val relative = base?.let {
            runCatching {
                val result = target.relativeTo(it).invariantSeparatorsPath
                result.takeIf { p -> p.isNotBlank() && p != ".." && !p.startsWith("../") }
            }.getOrNull()
        }
        return compactPath(relative ?: target.invariantSeparatorsPath, relative != null)
    }

    internal fun compactPath(raw: String, relative: Boolean): String {
        val parts = raw.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
        val content = parts.indexOfFirst { it in contentDirectories }
        if (content >= 0) return parts.drop(content).joinToString("/")
        if (relative && ".." !in parts) return parts.joinToString("/")
        return parts.lastOrNull().orEmpty()
    }

}
