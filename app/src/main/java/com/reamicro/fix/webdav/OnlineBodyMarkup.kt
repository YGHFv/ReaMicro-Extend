package com.reamicro.fix.webdav

/** 转场替换方案：[replace] 处改写为转场，[drop] 处整段删除（连续省略号合并时用）。 */
internal data class OnlineTransitionPlan(
    val replace: Set<Int>,
    val drop: Set<Int>,
)

/**
 * 正文元素标记。
 *
 * 插图与分割线的结构对齐 TEpub-Editor 的标准接口，内置的插图样式、分割样式据此编写：
 * - 插图 `figure.te-illustration > img.te-illustration-image + figcaption.te-illustration-caption`
 * - 分割 `p.te-divider-line`（同时保留旧 class `fg1`，兼容按 `p.fg1` 编写的样式）
 */
internal object OnlineBodyMarkup {
    fun paragraph(textHtml: String): String = "<p class=\"te-paragraph\">$textHtml</p>"

    fun divider(textHtml: String): String = "<p class=\"te-divider-line fg1\">$textHtml</p>"

    /**
     * 按分割样式自带的结构渲染转场。
     *
     * 菱形、渐隐线等样式把效果挂在 `te-transition--*` 修饰类上，只输出默认的 `p.te-divider-line`
     * 会让样式完全不生效；图片转场则要把 markup 里的 src 换成书内实际路径。
     */
    fun transition(markup: String, textHtml: String, imageHref: String?): String {
        val clean = markup.trim()
        if (clean.isBlank()) {
            return if (imageHref != null) dividerImage(imageHref) else divider(textHtml)
        }
        val bound = if (imageHref != null) clean.replace(MARKUP_IMAGE_SRC, "src=\"$imageHref\"") else clean
        return bound.lineSequence().joinToString("") { it.trim() }
    }

    /** 选了装饰图的分割样式：用图片替换原来的省略号/符号行。 */
    fun dividerImage(srcHtml: String): String =
        "<div class=\"te-divider-image\"><img class=\"te-divider-img\" src=\"$srcHtml\" alt=\"\"/></div>"

    /** 章节头图，结构与 TEpub 的头图接口一致。 */
    fun header(srcHtml: String): String =
        "<figure class=\"te-header-figure\"><img class=\"te-header-image\" src=\"$srcHtml\" alt=\"\"/></figure>"

    fun illustration(srcHtml: String, captionHtml: String = ""): String =
        "<figure class=\"te-illustration\">" +
            "<img class=\"te-illustration-image\" src=\"$srcHtml\" alt=\"\"/>" +
            (if (captionHtml.isBlank()) "" else "<figcaption class=\"te-illustration-caption\">$captionHtml</figcaption>") +
            "</figure>"

    /** 把历史版本生成的插图与分割线改写成标准结构；已是新结构时原样返回。 */
    fun migrateLegacyBody(html: String): String {
        var result = LEGACY_ILLUSTRATION.replace(html) { match ->
            illustration(match.groupValues[1])
        }
        result = LEGACY_DIVIDER.replace(result) { match ->
            divider(match.groupValues[1].trim())
        }
        return result
    }

    /**
     * 规划哪些段落该变成转场。
     *
     * 规则：只有前后都还有正文的省略号段落才算转场 —— 章节开头和结尾的孤立省略号是排版装饰或残留，
     * 变成分割线反而突兀；连续多段省略号视作同一处转场，合并成一条分割线。
     *
     * @param symbols 逐段标记该段是否为纯省略号/符号段。
     */
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
            // 组是极大连续段，因此 index>0 必然意味着前一段是正文，end<最后一段同理。
            if (index > 0 && end < symbols.size - 1) {
                replace += index
                for (inner in index + 1..end) drop += inner
            }
            index = end + 1
        }
        return OnlineTransitionPlan(replace, drop)
    }

    /**
     * 把正文里的转场改写成当前分割样式的结构。
     *
     * 覆盖两种情况：一是早期下载留下的普通 `<p>……</p>`，二是已经是 `p.te-divider-line`
     * 但结构对不上当前样式（菱形、渐隐线等把效果挂在 `te-transition--*` 上，结构不对就退回显示原符号）。
     */
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
        // 从后往前改，前面匹配的区间才不会被移位。
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
    /** 正文里可能是转场的块：普通段落、已有的分割线段落，以及图片转场的 div。 */
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
