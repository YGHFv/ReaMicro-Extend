package com.reamicro.fix.online

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONObject

internal data class OnlineParagraphCommentRuntimePayload(
    val sourceId: String,
    val bookId: String,
    val itemId: String,
    val paraIndex: Int,
    val paragraphText: String,
    val paragraphHash: String,
    val runtimeId: String,
)

/**
 * 将在线段评上下文放入宿主 CommentAnnotation.href。
 *
 * 载荷使用 URL-safe Base64 封装 JSON，既不会与 EPUB 原生 href 混淆，也不会受
 * CommentAnnotation 自身的 U+001F 字段分隔符影响。
 */
internal object OnlineParagraphCommentRuntimePayloadCodec {
    const val PREFIX = "reamicro-online-comment:"

    fun encode(payload: OnlineParagraphCommentRuntimePayload): String {
        val json = JSONObject()
            .put("sourceId", payload.sourceId)
            .put("bookId", payload.bookId)
            .put("itemId", payload.itemId)
            .put("paraIndex", payload.paraIndex)
            .put("paragraphText", payload.paragraphText)
            .put("paragraphHash", payload.paragraphHash)
            .put("runtimeId", payload.runtimeId)
            .toString()
        return PREFIX + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.toByteArray(StandardCharsets.UTF_8))
    }

    fun decode(value: String): OnlineParagraphCommentRuntimePayload? =
        runCatching {
            if (!value.startsWith(PREFIX)) return null
            val encoded = value.removePrefix(PREFIX)
            if (encoded.isBlank()) return null
            val root = JSONObject(
                String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8),
            )
            val sourceId = root.optString("sourceId")
            val bookId = root.optString("bookId")
            val itemId = root.optString("itemId")
            val paraIndex = root.optInt("paraIndex", -1)
            val paragraphText = root.optString("paragraphText")
            val paragraphHash = root.optString("paragraphHash")
                .ifBlank { paragraphText.onlineParagraphHash() }
            val runtimeId = root.optString("runtimeId")
            if (
                sourceId.isBlank() ||
                bookId.isBlank() ||
                itemId.isBlank() ||
                paraIndex < 0 ||
                runtimeId.isBlank()
            ) {
                return null
            }
            OnlineParagraphCommentRuntimePayload(
                sourceId = sourceId,
                bookId = bookId,
                itemId = itemId,
                paraIndex = paraIndex,
                paragraphText = paragraphText,
                paragraphHash = paragraphHash,
                runtimeId = runtimeId,
            )
        }.getOrNull()
}
