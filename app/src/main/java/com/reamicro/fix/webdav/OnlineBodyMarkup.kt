package com.reamicro.fix.webdav

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
}
