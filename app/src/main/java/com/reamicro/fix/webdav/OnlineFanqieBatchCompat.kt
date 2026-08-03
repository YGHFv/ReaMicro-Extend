package com.reamicro.fix.webdav

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal const val ONLINE_FANQIE_BATCH_MAX_CHAPTERS = 30
internal const val ONLINE_FANQIE_BATCH_HOST = "api.yuezhi.me"
internal const val ONLINE_FANQIE_BATCH_ENDPOINT = "/api/fanqie/books/{{bookId}}/chapters/batch"

internal data class OnlineFanqieBatchChapterRef(
    val bookId: String,
    val itemId: String,
)

internal class OnlineFanqieBatchProtocolException(message: String) : IllegalStateException(message)

internal fun supportsOnlineFanqieBatch(
    sourceUrl: String,
    endpointTemplate: String,
    batchSize: Int,
): Boolean {
    val source = runCatching { URI(sourceUrl.trim()) }.getOrNull() ?: return false
    return source.scheme.equals("https", ignoreCase = true) &&
        source.host.equals(ONLINE_FANQIE_BATCH_HOST, ignoreCase = true) &&
        source.userInfo == null &&
        source.port in setOf(-1, 443) &&
        source.path.orEmpty() in setOf("", "/") &&
        source.query == null &&
        source.fragment == null &&
        endpointTemplate == ONLINE_FANQIE_BATCH_ENDPOINT &&
        batchSize in 1..ONLINE_FANQIE_BATCH_MAX_CHAPTERS
}

internal fun parseOnlineFanqieBatchChapterRef(chapterUrl: String): OnlineFanqieBatchChapterRef? {
    val uri = runCatching { URI(chapterUrl.trim()) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) ||
        !uri.host.equals(ONLINE_FANQIE_BATCH_HOST, ignoreCase = true) ||
        uri.userInfo != null ||
        uri.port !in setOf(-1, 443) ||
        uri.fragment != null
    ) return null
    val match = Regex("^/v1/books/(\\d+)/chapters/(\\d+)/?$").matchEntire(uri.path.orEmpty()) ?: return null
    return OnlineFanqieBatchChapterRef(
        bookId = match.groupValues[1],
        itemId = match.groupValues[2],
    )
}

internal fun buildOnlineFanqieBatchEndpoint(
    sourceUrl: String,
    endpointTemplate: String,
    bookId: String,
): String {
    require(bookId.matches(Regex("\\d+"))) { "番茄批量请求缺少有效 bookId" }
    require(supportsOnlineFanqieBatch(sourceUrl, endpointTemplate, 1)) { "番茄批量配置无效" }
    val resolved = URI(sourceUrl.trim().trimEnd('/') + "/")
        .resolve(endpointTemplate.replace("{{bookId}}", bookId).trimStart('/'))
    require(resolved.scheme.equals("https", ignoreCase = true) &&
        resolved.host.equals(ONLINE_FANQIE_BATCH_HOST, ignoreCase = true)
    ) { "番茄批量端点越过允许的主机" }
    return resolved.toString()
}

internal fun buildOnlineFanqieBatchBody(itemIds: List<String>): String {
    val normalized = itemIds.map(String::trim).filter(String::isNotBlank).distinct()
    require(normalized.isNotEmpty()) { "番茄批量请求没有章节 ID" }
    require(normalized.size <= ONLINE_FANQIE_BATCH_MAX_CHAPTERS) {
        "番茄批量请求单次最多 $ONLINE_FANQIE_BATCH_MAX_CHAPTERS 章"
    }
    require(normalized.all { it.matches(Regex("\\d+")) }) { "番茄批量请求包含无效章节 ID" }
    return JSONObject()
        .put("item_ids", JSONArray(normalized))
        .put("novel_text_type", 1)
        .toString()
}

internal fun parseOnlineFanqieBatchResponse(raw: String): Map<String, String> {
    val root = runCatching { JSONObject(raw) }
        .getOrElse { throw OnlineFanqieBatchProtocolException("番茄批量响应不是 JSON") }
    if (!root.optBoolean("ok", false)) {
        val detail = root.optString("detail", "")
            .ifBlank { root.optString("message", "") }
            .ifBlank { "番茄批量响应未标记成功" }
        if (detail.contains("circuit is open", ignoreCase = true)) {
            throw OnlineSourceHttpException(statusCode = 503, detail = detail)
        }
        throw OnlineFanqieBatchProtocolException(detail)
    }
    val chapters = root.optJSONArray("chapters")
        ?: throw OnlineFanqieBatchProtocolException("番茄批量响应缺少 chapters")
    val result = linkedMapOf<String, String>()
    for (index in 0 until chapters.length()) {
        val chapter = chapters.optJSONObject(index) ?: continue
        val itemId = chapter.optString("itemId", "").trim()
        if (itemId.isBlank() || result.containsKey(itemId)) continue
        val content = chapter.optString("contentHtml", "").ifBlank {
            chapter.optString("content", "")
        }
        if (content.isBlank()) continue
        if (chapter.optString("contentHtml", "").isBlank()) {
            chapter.put("contentHtml", content)
        }
        result[itemId] = chapter.toString()
    }
    return result
}
