package com.reamicro.fix.webdav

/** 卷标题拆分结果：number 为卷序号，title 为卷名，二者都可能为空。 */
internal data class OnlineVolumeHeading(
    val number: String,
    val title: String,
)

/**
 * 卷首页标题识别与标记生成。
 *
 * 识别规则与章节标题保持一致：把「第x卷 卷标题」「第x小节卷标题」「序 卷标题」等各种卷标
 * 拆成序号与卷名两行展示；只有序号而没有卷名时仅展示序号。
 */
internal object OnlineVolumeHeadingMarkup {
    /** 拆分卷标题，未识别出卷标时整串作为卷名。 */
    fun parse(rawTitle: String): OnlineVolumeHeading {
        val clean = rawTitle.trim()
        if (clean.isEmpty()) return OnlineVolumeHeading("", "")
        NUMBERED_LABEL.find(clean)?.let { return clean.headingAt(it, keepInnerSpace = false) }
        LATIN_LABEL.find(clean)?.let { return clean.headingAt(it, keepInnerSpace = true) }
        SPECIAL_LABEL.find(clean)?.let { return clean.headingAt(it, keepInnerSpace = false) }
        return OnlineVolumeHeading("", clean)
    }

    /** 仅有一行文字（只有卷名或只有序号）时的卷首页正文。 */
    fun single(titleHtml: String): String =
        "<div class=\"te-volume-page\">" +
            ORNAMENT +
            "<h1 class=\"te-volume-title\"><span class=\"te-volume-name\">$titleHtml</span></h1>" +
            "</div>"

    /** 序号与卷名分行展示的卷首页正文。 */
    fun split(numberHtml: String, titleHtml: String): String =
        "<div class=\"te-volume-page\">" +
            ORNAMENT +
            "<h1 class=\"te-volume-title\">" +
            "<span class=\"te-volume-number\">$numberHtml</span>" +
            "<span class=\"te-volume-name\">$titleHtml</span>" +
            "</h1>" +
            "</div>"

    /** 以匹配到的卷标为界拆分，序号内部空白按语言习惯压缩。 */
    private fun String.headingAt(match: MatchResult, keepInnerSpace: Boolean): OnlineVolumeHeading {
        val number = match.groupValues[1].trim()
            .replace(INNER_WHITESPACE, if (keepInnerSpace) " " else "")
        val rest = substring(match.range.last + 1)
            .trim()
            .trimStart { it.isWhitespace() || it in SEPARATOR_CHARS }
        return OnlineVolumeHeading(number, rest.trim())
    }

    private const val ORNAMENT = "<div class=\"te-volume-ornament\">※</div>"
    private const val SEPARATOR_CHARS = "　:：/／、，,。.!！?？-—_；;]"
    private val INNER_WHITESPACE = Regex("""[\s　]+""")
    private val NUMBERED_LABEL = Regex(
        "^(第[\\s　]*[0-9０-９一二三四五六七八九十百千万〇零两]+[\\s　]*" +
            "(?:小节|小章|章|节|卷|部|篇|册|季|回|集|辑|幕))",
    )
    private val LATIN_LABEL = Regex(
        "^((?:volume|vol\\.?|part|book|section|episode)[\\s　]*[0-9０-９]+)",
        RegexOption.IGNORE_CASE,
    )
    private val SPECIAL_LABEL = Regex(
        "^(番外篇|番外|外传|后日谈|后记|序章|序言|序幕|序曲|序篇|楔子|前言|引子|终章|尾声|序)",
    )
}
