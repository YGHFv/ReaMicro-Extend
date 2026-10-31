package com.reamicro.fix.notification

import com.reamicro.fix.online.download.OnlineChapterUpdateOutcome

internal data class BookshelfUpdateReport(
    val books: List<OnlineChapterUpdateOutcome>,
    val total: Int,
    val automatic: Boolean,
    val error: String? = null,
) {
    val title: String get() = when {
        error != null -> "书架更新未完成"
        books.size < total -> "书架更新已停止"
        automatic -> "书架自动更新完成"
        else -> "书架批量更新完成"
    }

    val summary: String get() {
        val updated = books.count { it.added > 0 }
        val chapters = books.sumOf { it.added.toLong() }
        val unfinished = books.count { !it.success } + (total - books.size).coerceAtLeast(0)
        return buildString {
            append("$updated 本新增 $chapters 章，已检查 ${books.size}/$total 本")
            if (unfinished > 0) append("，$unfinished 本未完成")
            val changed = books.filter { it.added > 0 }
            if (changed.isNotEmpty()) {
                append("；")
                append(changed.joinToString("，") { book ->
                    "${clean(book.title).ifBlank { "未命名图书" }} +${book.added}章"
                })
            }
        }
    }

    val details: String get() = buildString {
        append(summary)
        books.forEach { book ->
            append('\n')
            append(clean(book.title).ifBlank { "未命名图书" })
            append("：新增 ${book.added} 章")
            if (book.retried > 0) append("，补回 ${book.retried} 章")
            if (book.failed > 0) append("，${book.failed} 章失败")
            if (book.error != null) append("，未完成（${clean(book.error)}）")
            else if (book.skipped) append("，已完结，跳过")
            else if (book.added == 0 && book.failed == 0 && book.retried == 0) append("，已是最新")
        }
        if (total > books.size) append("\n剩余 ${total - books.size} 本未检查")
        if (total == 0 && error == null) append("\n当前书架没有可更新的在线图书")
        error?.let { append("\n${clean(it)}") }
    }

    private fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()
}
