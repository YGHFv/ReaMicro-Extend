package com.reamicro.fix.online.epub

internal data class OnlineTransitionPlan(
    val replace: Set<Int>,
    val drop: Set<Int>,
)

internal object OnlineBodyMarkup {
    fun paragraph(textHtml: String): String = "<p class=\"te-paragraph\">$textHtml</p>"

    fun divider(textHtml: String): String = "<p class=\"te-divider-line fg1\">$textHtml</p>"

    fun transition(markup: String, textHtml: String, imageHref: String?): String {
        val clean = markup.trim()
        if (clean.isBlank()) {
            return if (imageHref != null) dividerImage(imageHref) else divider(textHtml)
        }
        val bound = if (imageHref != null) clean.replace(MARKUP_IMAGE_SRC, "src=\"$imageHref\"") else clean
        return bound.lineSequence().joinToString("") { it.trim() }
    }

    fun dividerImage(srcHtml: String): String =
        "<div class=\"te-divider-image\"><img class=\"te-divider-img\" src=\"$srcHtml\" alt=\"\"/></div>"

    fun header(srcHtml: String): String =
        "<figure class=\"te-header-figure\"><img class=\"te-header-image\" src=\"$srcHtml\" alt=\"\"/></figure>"

    fun illustration(srcHtml: String, captionHtml: String = ""): String =
        "<figure class=\"te-illustration\">" +
            "<img class=\"te-illustration-image\" src=\"$srcHtml\" alt=\"\"/>" +
            (if (captionHtml.isBlank()) "" else "<figcaption class=\"te-illustration-caption\">$captionHtml</figcaption>") +
            "</figure>"

    fun migrateLegacyBody(html: String): String {
        var result = LEGACY_ILLUSTRATION.replace(html) { match ->
            illustration(match.groupValues[1])
        }
        result = LEGACY_DIVIDER.replace(result) { match ->
            divider(match.groupValues[1].trim())
        }
        return result
    }

    fun planTransitions(symbols: List<Boolean>): OnlineTransitionPlan {
        val replace = HashSet<Int>()
        val drop = HashSet<Int>()
        var index = 0
        while (index < symbols.size) {
            if (!symbols[index]) {
                index += 1
                continue
            }
            var end = index
            while (end + 1 < symbols.size && symbols[end + 1]) end += 1

            if (index > 0 && end < symbols.size - 1) {
                replace += index
                for (inner in index + 1..end) drop += inner
            }
            index = end + 1
        }
        return OnlineTransitionPlan(replace, drop)
    }

    fun migrateTransitions(
        html: String,
        isDivider: (String) -> Boolean,
        markup: String,
        imageHref: String?,
    ): String {
        val blocks = TRANSITION_CANDIDATE.findAll(html).toList()
        if (blocks.size < 3) return html
        val texts = blocks.map { it.groupValues[2].replace(TAGS, "").trim() }
        val symbols = blocks.mapIndexed { index, match ->
            TRANSITION_CLASS.containsMatchIn(match.groupValues[1]) ||
                (texts[index].isNotEmpty() && isDivider(texts[index]))
        }
        val plan = planTransitions(symbols)
        if (plan.replace.isEmpty() && plan.drop.isEmpty()) return html
        val builder = StringBuilder(html)

        for (index in blocks.indices.reversed()) {
            val match = blocks[index]
            when (index) {
                in plan.drop -> builder.replace(match.range.first, match.range.last + 1, "")
                in plan.replace -> {
                    val replacement = transition(markup, texts[index].ifBlank { "※※※" }, imageHref)
                    if (replacement != match.value) {
                        builder.replace(match.range.first, match.range.last + 1, replacement)
                    }
                }
            }
        }
        return builder.toString()
    }

    private val LEGACY_ILLUSTRATION = Regex(
        """<div[^>]*class=["'][^"']*online-illustration[^"']*["'][^>]*>\s*""" +
            """<img[^>]*src=["']([^"']*)["'][^>]*/?>\s*(?:</img>)?\s*</div>""",
        RegexOption.IGNORE_CASE,
    )
    private val LEGACY_DIVIDER = Regex(
        """<p[^>]*class=["'][^"']*(?<![-\w])divider-line\b[^"']*["'][^>]*>([\s\S]*?)</p>""",
        RegexOption.IGNORE_CASE,
    )
    private val MARKUP_IMAGE_SRC = Regex("""src="[^"]*"""")

    private val TRANSITION_CANDIDATE = Regex(
        """<(?:p|div)\b([^>]*)>([\s\S]*?)</(?:p|div)>""",
        RegexOption.IGNORE_CASE,
    )
    private val TRANSITION_CLASS = Regex(
        """\b(?:te-divider-line|fg1|te-transition|te-divider-image)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TAGS = Regex("""<[^>]*>""")
}
