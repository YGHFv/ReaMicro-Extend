package com.reamicro.fix.online.epub

internal object OnlineChapterImageMarkup {

    const val MARKER_OPEN = "@@RM_IMG_SRC@@"
    const val MARKER_CLOSE = "@@RM_IMG_END@@"

    private val IMG_TAG =
        Regex("""(?is)<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["'][^>]*>""")
    private val MARKER =
        Regex(Regex.escape(MARKER_OPEN) + "(.*?)" + Regex.escape(MARKER_CLOSE))

    fun preserve(html: String): String =
        IMG_TAG.replace(html) { match ->
            val url = normalizeUrl(match.groupValues[1])
            if (url.isBlank()) " " else "\n$MARKER_OPEN$url$MARKER_CLOSE\n"
        }

    fun imageUrls(content: String): List<String> =
        MARKER.findAll(content)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    fun markerUrl(line: String): String? =
        MARKER.matchEntire(line.trim())?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

    fun hasImages(content: String): Boolean = MARKER.containsMatchIn(content)

    private fun normalizeUrl(raw: String): String =
        raw.trim()
            .replace("&amp;", "&")
            .replace("&#38;", "&")
            .replace("&#x26;", "&", ignoreCase = true)
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
}
