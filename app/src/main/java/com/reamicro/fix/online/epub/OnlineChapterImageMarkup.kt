package com.reamicro.fix.online.epub

/**
 * 在线书源正文里的插图适配：
 * 番茄等 JSON 书源的 `contentHtml` 会带 `<img src="...">`，而模块把正文归一化成纯文本时会把所有标签删掉，
 * 导致插图彻底丢失。这里在删标签之前把 `<img>` 换成能穿过纯文本管线的标记，等到生成章节 XHTML 时再还原成
 * 真正的图片元素（配合下载嵌入到 EPUB 的 OEBPS/Images 里），从而实现“下载时自动下载插图”。
 */
internal object OnlineChapterImageMarkup {
    // 采用正文里几乎不可能自然出现的 ASCII 标记，避免和文字/HTML/实体冲突，也不会被去空白、去标签步骤吃掉。
    const val MARKER_OPEN = "@@RM_IMG_SRC@@"
    const val MARKER_CLOSE = "@@RM_IMG_END@@"

    private val IMG_TAG =
        Regex("""(?is)<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["'][^>]*>""")
    private val MARKER =
        Regex(Regex.escape(MARKER_OPEN) + "(.*?)" + Regex.escape(MARKER_CLOSE))

    /** 把 HTML 里的 `<img>` 换成独立成行的标记，交给后续纯文本归一化保留下来。 */
    fun preserve(html: String): String =
        IMG_TAG.replace(html) { match ->
            val url = normalizeUrl(match.groupValues[1])
            if (url.isBlank()) " " else "\n$MARKER_OPEN$url$MARKER_CLOSE\n"
        }

    /** 按出现顺序取出正文里的插图 URL（去重），供下载与嵌入使用。 */
    fun imageUrls(content: String): List<String> =
        MARKER.findAll(content)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    /** 若该行是插图标记则返回其 URL，否则返回 null。 */
    fun markerUrl(line: String): String? =
        MARKER.matchEntire(line.trim())?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

    /** 是否含有插图标记。 */
    fun hasImages(content: String): Boolean = MARKER.containsMatchIn(content)

    private fun normalizeUrl(raw: String): String =
        raw.trim()
            .replace("&amp;", "&")
            .replace("&#38;", "&")
            .replace("&#x26;", "&", ignoreCase = true)
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
}
