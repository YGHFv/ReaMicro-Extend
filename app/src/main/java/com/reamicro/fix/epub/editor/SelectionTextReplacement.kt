package com.reamicro.fix.epub.editor

internal fun replaceUniqueSelectionText(content: String, oldText: String, newText: String): String? {
    if (oldText.isBlank()) return null
    fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    for (candidate in listOf(escape(oldText), oldText).distinct()) {
        val first = content.indexOf(candidate)
        if (first < 0) continue
        if (content.indexOf(candidate, first + candidate.length) >= 0) return null
        return content.replaceRange(first, first + candidate.length, escape(newText))
    }
    return null
}
