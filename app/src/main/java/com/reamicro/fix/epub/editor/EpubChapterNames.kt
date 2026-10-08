package com.reamicro.fix.epub.editor
import java.io.File

internal object EpubChapterNames {
    fun toc(root: File, paths: List<String>, checkCancelled: () -> Unit = {}): Map<String, List<String>> {
        val names = linkedMapOf<String, MutableSet<String>>()
        for (path in EpubNavigation.paths(root, paths, checkCancelled)) {
            checkCancelled()
            val rows = runCatching { EpubNavigation.read(root, path, paths, checkCancelled) }
                .getOrElse { checkCancelled(); emptyList() }
            for (row in rows) {
                checkCancelled()
                val target = row.path?.takeIf(::usesScriptaEpubEditor) ?: continue
                if (row.title != "[未命名]") names.getOrPut(target) { linkedSetOf() }.add(row.title.take(240))
            }
        }
        return names.mapValues { it.value.toList() }
    }
}
