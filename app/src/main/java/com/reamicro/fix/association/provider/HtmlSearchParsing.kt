package com.reamicro.fix.association.provider

internal fun String.cleanHtmlText(): String = replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
    .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
    .replace(Regex("<[^>]+>"), "")
    .htmlDecode()
    .replace(Regex("\\s+"), " ")
    .trim()

internal fun String.htmlDecode(): String = replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&nbsp;", " ")

internal fun String.absoluteUrl(baseUrl: String): String = when {
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true) -> this
    startsWith("//") -> "https:$this"
    startsWith("/") -> baseUrl.trimEnd('/') + this
    else -> baseUrl.trimEnd('/') + "/" + this
}
