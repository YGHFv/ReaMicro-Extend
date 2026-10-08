package com.reamicro.fix.hook

import java.io.File

internal object ReaderSearchPresentation {
    private val contentDirectories = setOf("OEBPS", "OPS", "EPUB")

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

    fun body(chapter: String, snippet: String): String =
        if (chapter.isBlank()) snippet else chapter.trim() + "\n" + snippet

    fun snippetOffset(chapter: String): Int = if (chapter.isBlank()) 0 else chapter.trim().length + 1
}
