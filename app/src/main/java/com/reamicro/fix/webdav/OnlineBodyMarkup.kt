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
}
