package com.reamicro.fix.online.search

import java.net.URI
import java.net.URLDecoder

/** 已失效的起点封面代理按其原始书号直连阅文；仅识别这个代理接口，不推断其他平台的书号。 */
internal fun directOnlineCoverUrl(value: String): String {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return value
    if (uri.scheme !in listOf("https", "http") || !uri.host.equals("shenmoxs.top", true) ||
        uri.path != "/cover" || uri.userInfo != null) return value
    val parameters = runCatching {
        uri.rawQuery.orEmpty().split('&').associate { part ->
            URLDecoder.decode(part.substringBefore('='), "UTF-8") to
                URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }
    }.getOrNull() ?: return value
    val bookId = parameters["bookId"]?.takeIf { it.matches(Regex("[0-9]{5,15}")) } ?: return value
    return "https://bookcover.yuewen.com/qdbimg/349573/$bookId/600"
}
