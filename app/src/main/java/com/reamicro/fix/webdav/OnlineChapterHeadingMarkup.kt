package com.reamicro.fix.webdav

internal object OnlineChapterHeadingMarkup {
    fun single(titleHtml: String): String =
        "<h1 class=\"te-chapter-title\">$titleHtml</h1>"

    fun split(numberHtml: String, titleHtml: String): String =
        "<div class=\"te-chapter-heading\">" +
            "<div class=\"te-chapter-number\">$numberHtml</div>" +
            "<h1 class=\"te-chapter-title\">$titleHtml</h1>" +
            "</div>"

    fun migrateSplit(contentHtml: String): String? {
        val numberMatch = CHAPTER_NUMBER_SPAN.find(contentHtml)
        val parts = if (numberMatch != null) {
            val subtitle = contentHtml.removeRange(numberMatch.range)
                .replaceFirst(LEADING_BREAK, "")
            numberMatch.groupValues[1] to subtitle
        } else {
            val breakMatch = BREAK.find(contentHtml) ?: return null
            contentHtml.substring(0, breakMatch.range.first) to
                contentHtml.substring(breakMatch.range.last + 1)
        }
        if (parts.first.isBlank() || parts.second.isBlank()) return null
        return split(parts.first, parts.second)
    }

    private val CHAPTER_NUMBER_SPAN =
        Regex("""<span[^>]*class=["'][^"']*te-chapter-number[^"']*["'][^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
    private val LEADING_BREAK = Regex("""^\s*<br\s*/?>\s*""", RegexOption.IGNORE_CASE)
    private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
}
