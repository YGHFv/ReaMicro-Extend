package com.reamicro.fix.online.epub

/**
 * 章节标题标记。
 *
 * 结构对齐 TEpub-Editor 的标准接口：`h1.te-chapter-title > span.te-chapter-number + span.te-chapter-name`，
 * 内置样式库里的标题样式据此编写。
 */
internal object OnlineChapterHeadingMarkup {
    fun single(titleHtml: String): String =
        "<h1 class=\"te-chapter-title\"><span class=\"te-chapter-name\">$titleHtml</span></h1>"

    fun split(numberHtml: String, titleHtml: String): String =
        "<h1 class=\"te-chapter-title\">" +
            "<span class=\"te-chapter-number\">$numberHtml</span>" +
            "<span class=\"te-chapter-name\">$titleHtml</span>" +
            "</h1>"

    /**
     * 把历史版本的标题正文拆成序号与标题。
     *
     * 覆盖 `序号<br>标题`、`<span class="te-chapter-number">序号</span><br>标题` 两种旧写法；
     * 拆不出来时返回 null，由调用方按单行标题处理。
     */
    fun migrateSplit(contentHtml: String): String? {
        if (contentHtml.contains("te-chapter-name")) return null
        val numberMatch = CHAPTER_NUMBER_INLINE.find(contentHtml)
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
        return split(parts.first.trim(), parts.second.trim())
    }

    /**
     * 把旧的 `div.te-chapter-heading` 双层结构整体改写成新的 h1 结构。
     *
     * 在 [migrateSplit] 之前执行：旧结构里序号在 h1 之外，单看 h1 内容拆不出来。
     */
    fun migrateLegacyHeadingBlock(html: String): String =
        LEGACY_HEADING_BLOCK.replace(html) { match ->
            val number = match.groupValues[1].trim()
            val title = match.groupValues[2].trim()
            when {
                number.isNotBlank() && title.isNotBlank() -> split(number, title)
                title.isNotBlank() -> single(title)
                number.isNotBlank() -> single(number)
                else -> match.value
            }
        }

    private val CHAPTER_NUMBER_INLINE =
        Regex("""<span[^>]*class=["'][^"']*te-chapter-number[^"']*["'][^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
    private val LEADING_BREAK = Regex("""^\s*<br\s*/?>\s*""", RegexOption.IGNORE_CASE)
    private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val LEGACY_HEADING_BLOCK = Regex(
        """<div[^>]*class=["'][^"']*te-chapter-heading[^"']*["'][^>]*>\s*""" +
            """(?:<div[^>]*class=["'][^"']*te-chapter-number[^"']*["'][^>]*>([\s\S]*?)</div>\s*)?""" +
            """<h1[^>]*>([\s\S]*?)</h1>\s*</div>""",
        RegexOption.IGNORE_CASE,
    )
}
