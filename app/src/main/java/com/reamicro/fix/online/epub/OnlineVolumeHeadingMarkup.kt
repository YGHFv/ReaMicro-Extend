package com.reamicro.fix.online.epub

internal data class OnlineVolumeHeading(
    val number: String,
    val title: String,
)

internal object OnlineVolumeHeadingMarkup {

    fun parse(rawTitle: String): OnlineVolumeHeading {
        val clean = rawTitle.trim()
        if (clean.isEmpty()) return OnlineVolumeHeading("", "")
        NUMBERED_LABEL.find(clean)?.let { return clean.headingAt(it, keepInnerSpace = false) }
        LATIN_LABEL.find(clean)?.let { return clean.headingAt(it, keepInnerSpace = true) }
        SPECIAL_LABEL.find(clean)?.let { return clean.headingAt(it, keepInnerSpace = false) }
        return OnlineVolumeHeading("", clean)
    }

    fun single(titleHtml: String): String =
        "<div class=\"te-volume-page\">" +
            ORNAMENT +
            "<h1 class=\"te-volume-title\"><span class=\"te-volume-name\">$titleHtml</span></h1>" +
            "</div>"

    fun split(numberHtml: String, titleHtml: String): String =
        "<div class=\"te-volume-page\">" +
            ORNAMENT +
            "<h1 class=\"te-volume-title\">" +
            "<span class=\"te-volume-number\">$numberHtml</span>" +
            "<span class=\"te-volume-name\">$titleHtml</span>" +
            "</h1>" +
            "</div>"

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
