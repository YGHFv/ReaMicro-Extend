package com.reamicro.fix.hook

import java.util.Locale

internal object StructureFileSearch {
    private fun normalize(value: String) =
        value.trim().replace('\\', '/').removePrefix("./").lowercase(Locale.ROOT)

    fun contentNames(fileName: String, chapterTitle: String = "", cover: Boolean = false, banner: Boolean = false): List<String> {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val stem = fileName.substringBeforeLast('.', fileName)
        val caption = when (extension) {
            "html", "htm", "xhtml" -> "${extension.uppercase(Locale.ROOT)}内容"
            "opf" -> "元数据"
            "ncx" -> "目录结构"
            "css" -> "层叠样式表"
            "ttf", "otf" -> "字体"
            "svg", "png", "jpg", "jpeg", "gif", "webp" -> if (cover) "封面" else if (banner) "横幅" else "图片"
            "xml" -> if (stem == "container") "容器文件" else "未知文件"
            else -> if (stem == "bookmarks") "书签文件" else "未知格式$extension"
        }
        return listOf(caption, chapterTitle).filter { it.isNotBlank() }
    }

    fun matches(paths: List<String>, query: String, labels: Map<String, List<String>> = emptyMap()): List<String> {
        val needle = normalize(query)
        if (needle.isEmpty()) return emptyList()
        return paths.distinct().mapNotNull { path ->
            val full = normalize(path)
            val name = full.substringAfterLast('/')
            val aliases = labels[path].orEmpty().map(::normalize)
            val rank = when {
                full == needle -> 0
                name == needle -> 1
                name.substringBeforeLast('.', name) == needle -> 2
                needle in aliases -> 3
                name.startsWith(needle) -> 4
                name.contains(needle) -> 5
                aliases.any { needle in it } -> 6
                full.contains(needle) -> 7
                else -> return@mapNotNull null
            }
            path to rank
        }.sortedWith(compareBy<Pair<String, Int>> { it.second }
            .thenBy { normalize(it.first) }.thenBy { it.first }).map { it.first }
    }

    fun resolve(paths: List<String>, query: String, labels: Map<String, List<String>> = emptyMap()): String? {
        val needle = normalize(query)
        if (needle.isEmpty()) return null
        val matches = matches(paths, query, labels)
        if ('/' in needle) matches.filter { normalize(it) == needle }.singleOrNull()?.let { return it }
        val exact = matches.filter { normalize(it).substringAfterLast('/') == needle }
        if (exact.isNotEmpty()) return exact.singleOrNull()
        val stems = matches.filter {
            val name = normalize(it).substringAfterLast('/')
            name.substringBeforeLast('.', name) == needle
        }
        if (stems.isNotEmpty()) return stems.singleOrNull()
        val aliases = matches.filter { path -> labels[path].orEmpty().any { normalize(it) == needle } }
        if (aliases.isNotEmpty()) return aliases.singleOrNull()
        return matches.singleOrNull()
    }
}
