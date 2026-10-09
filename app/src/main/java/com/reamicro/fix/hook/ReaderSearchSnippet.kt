package com.reamicro.fix.hook

import com.reamicro.fix.hook.reader.SearchSnippet

private val searchSnippetWhitespace = Regex("\\s+")

internal fun searchSnippet(text: String, start: Int, end: Int, compactRadius: Int): SearchSnippet {
    val from = (start - compactRadius).coerceAtLeast(0)
    val to = (end + compactRadius).coerceAtMost(text.length)
    val prefix = if (from > 0) "\u2026" else ""
    val suffix = if (to < text.length) "\u2026" else ""
    // 分别折叠命中前后的空白，让扩充预览后的高亮偏移仍与显示文本一致。
    val before = text.substring(from, start).replace(searchSnippetWhitespace, " ").trimStart()
    val match = text.substring(start, end).replace(searchSnippetWhitespace, " ")
    val after = text.substring(end, to).replace(searchSnippetWhitespace, " ").trimEnd()
    val matchStart = prefix.length + before.length
    return SearchSnippet(prefix + before + match + after + suffix, matchStart, matchStart + match.length)
}
