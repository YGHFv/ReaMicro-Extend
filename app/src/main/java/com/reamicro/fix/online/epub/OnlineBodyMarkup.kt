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
        val rendered = bound.lineSequence().joinToString("") { it.trim() }
        // 自定义模板也留下可识别标记，后续切换样式可以完整覆盖。
        return rendered.replaceFirst(Regex("""<([A-Za-z][\w:-]*)\b"""), "<$1 data-rm-transition=\"true\"")
    }

    fun dividerImage(srcHtml: String): String =
        "<div class=\"te-divider-image\"><img class=\"te-divider-img\" src=\"$srcHtml\" alt=\"\"/></div>"

    fun header(srcHtml: String): String =
        "<figure class=\"te-header-figure\"><img class=\"te-header-image\" src=\"$srcHtml\" alt=\"\"/></figure>"

    fun illustration(srcHtml: String, captionHtml: String = "", markup: String = ""): String {
        if (markup.isBlank()) return "<figure class=\"te-illustration\">" +
            "<img class=\"te-illustration-image\" src=\"$srcHtml\" alt=\"\"/>" +
            (if (captionHtml.isBlank()) "" else "<figcaption class=\"te-illustration-caption\">$captionHtml</figcaption>") +
            "</figure>"
        if (markup.contains("te-annotation-toggle")) {
            val id = "rm-illustration-" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(srcHtml.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
            // 模板里的示例正文和示例图注不能写入真实图书，生成当前插图自己的引用。
            return """<div data-rm-illustration="true"><input id="$id" class="te-annotation-toggle" type="checkbox" />""" +
                """<p class="te-paragraph"><label class="te-annotation-trigger" for="$id"><a id="$id-ref" class="duokan-footnote" href="#$id-note" epub:type="noteref" role="doc-noteref">〔查看插图〕</a></label></p>""" +
                """<aside id="$id-note" class="te-annotation-popup duokan-footnote-item" epub:type="footnote" role="doc-footnote"><label class="te-annotation-backdrop" for="$id"></label><figure class="te-annotation-figure"><label class="te-annotation-close" for="$id">×</label><img class="te-annotation-image" src="$srcHtml" alt=""/>""" +
                (if (captionHtml.isBlank()) "" else """<figcaption class="te-annotation-caption">$captionHtml</figcaption>""") +
                """<a class="duokan-footnote-back" href="#$id-ref">返回</a></figure></aside></div>"""
        }
        var template = markup.trim()
        val captions = OnlineMarkupRanges.elements(template).filter { it.tag == "figcaption" }
        template = OnlineMarkupRanges.replace(template, captions.map {
            it to if (captionHtml.isBlank()) "" else
                "<figcaption class=\"te-illustration-caption\">$captionHtml</figcaption>"
        })
        if (captions.isEmpty() && captionHtml.isNotBlank()) {
            val figure = OnlineMarkupRanges.elements(template).firstOrNull { it.tag == "figure" }
            if (figure != null) template = template.substring(0, figure.contentEnd) +
                "<figcaption class=\"te-illustration-caption\">$captionHtml</figcaption>" +
                template.substring(figure.contentEnd)
        }
        template = template.replace(MARKUP_IMAGE_SRC, "src=\"$srcHtml\"")
            .replace(Regex("""\balt="[^"]*""""), """alt="" """.trim())
        return "<div data-rm-illustration=\"true\">$template</div>"
    }

    fun migrateIllustrations(html: String, markup: String): String {
        val elements = OnlineMarkupRanges.elements(html)
        val roots = elements.filter { it.attribute("data-rm-illustration") != null || it.hasClass("te-illustration") }
            .filter { element -> elements.none { parent ->
                element.inside(parent) && parent.attribute("data-rm-illustration") != null
            } }
        return OnlineMarkupRanges.replace(html, roots.mapNotNull { root ->
            val image = elements.firstOrNull { it.inside(root) && it.tag == "img" } ?: return@mapNotNull null
            val source = image.attribute("src") ?: return@mapNotNull null
            val caption = elements.firstOrNull { it.inside(root) && it.tag == "figcaption" }
                ?.let { html.substring(it.contentStart, it.contentEnd) }.orEmpty()
            root to illustration(source, caption, markup)
        })
    }

    fun migrateHeader(html: String, header: String): String {
        val old = OnlineMarkupRanges.elements(html).filter { it.hasClass("te-header-figure") }
        val clean = OnlineMarkupRanges.replace(html, old.map { it to "" })
        if (header.isBlank()) return clean
        val body = OnlineMarkupRanges.elements(clean).firstOrNull { it.tag == "body" } ?: return clean
        return clean.substring(0, body.contentStart) + header + clean.substring(body.contentStart)
    }

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
        val elements = OnlineMarkupRanges.elements(html)
        fun explicit(element: OnlineMarkupRanges.Element): Boolean =
            element.attribute("data-rm-transition") != null ||
                listOf("te-divider-line", "fg1", "te-transition", "te-divider-image", "divider-line")
                    .any(element::hasClass)
        val candidates = elements.filter { it.tag == "p" || explicit(it) }
        val blocks = candidates.filter { element ->
            candidates.none { parent -> element.inside(parent) && explicit(parent) }
        }
        val texts = blocks.map { html.substring(it.contentStart, it.contentEnd).replace(TAGS, "").trim() }
        val symbols = blocks.mapIndexed { index, element ->
            explicit(element) ||
                (texts[index].isNotEmpty() && isDivider(texts[index]))
        }
        val plan = planTransitions(symbols)
        val replacements = blocks.mapIndexedNotNull { index, element ->
            when {
                // 已有分割元素无论位于章首、章尾还是空模板，都必须覆盖。
                explicit(element) -> element to transition(markup, texts[index].ifBlank { "※※※" }, imageHref)
                index in plan.drop -> element to ""
                index in plan.replace -> {
                    val replacement = transition(markup, texts[index].ifBlank { "※※※" }, imageHref)
                    element to replacement
                }
                else -> null
            }
        }
        return OnlineMarkupRanges.replace(html, replacements)
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

    private val TAGS = Regex("""<[^>]*>""")
}
