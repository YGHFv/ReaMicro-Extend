package com.reamicro.fix.webdav

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 校验在线章节正文，避免把接口错误响应或下载占位文字写成真实正文。
 */
internal object OnlineChapterContentValidator {
    fun downloadedFailureReason(content: String): String? {
        val text = content.trim().removePrefix("\uFEFF").trim()
        if (text.isBlank()) return "章节正文为空"
        placeholderFailureReason(text)?.let { return it }
        parseJson(text)?.let { return jsonFailureReason(it) }
        return null
    }

    fun storedXhtmlFailureReason(xhtml: String): String? {
        if (xhtml.isBlank()) return "章节文件为空"
        val bodyHtml = XHTML_BODY_REGEX.find(xhtml)?.groupValues?.getOrNull(1)
            ?: XHTML_HEAD_REGEX.replace(xhtml, " ")
        // 纯插图章节正文里可能没有文字，只有 <img>，不能据此判定为空。
        val hasImage = XHTML_IMAGE_REGEX.containsMatchIn(bodyHtml)
        val paragraphs = XHTML_PARAGRAPH_REGEX.findAll(bodyHtml)
            .map { match -> match.groupValues[1] }
            .toList()
        val contentHtml = if (paragraphs.isNotEmpty()) {
            paragraphs.joinToString("\n")
        } else {
            XHTML_CHAPTER_NUMBER_REGEX.replace(bodyHtml, " ")
                .let { XHTML_HEADING_REGEX.replace(it, " ") }
        }
        val bodyText = contentHtml
            .replace(XHTML_COMMENT_REGEX, " ")
            .replace(XHTML_LINE_BREAK_REGEX, "\n")
            .replace(XHTML_TAG_REGEX, " ")
            .decodeOnlineHtmlEntities()
            .lineSequence()
            .map { line -> line.replace(INLINE_WHITESPACE_REGEX, " ").trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val reason = downloadedFailureReason(bodyText)
        return if (reason == "章节正文为空" && hasImage) null else reason
    }

    private fun placeholderFailureReason(text: String): String? {
        val compact = text.replace(INLINE_WHITESPACE_REGEX, " ").trim()
        return when {
            compact.startsWith("本章下载失败，已按在线源重试") -> "检测到历史下载失败占位正文"
            compact.startsWith("本章正在后台下载中") -> "检测到历史后台下载占位正文"
            compact.startsWith("本章尚未下载，当前在线源今日额度已用完") -> "检测到日限额未完成占位正文"
            compact.contains("批量请求重试") && compact.contains("后仍失败") -> summarize(compact)
            compact.startsWith("批量API返回空响应") -> summarize(compact)
            else -> null
        }
    }

    private fun parseJson(text: String): Any? = when (text.firstOrNull()) {
        '{' -> runCatching { JSONObject(text) }.getOrNull()
        '[' -> runCatching { JSONArray(text) }.getOrNull()
        else -> null
    }

    private fun jsonFailureReason(root: Any): String? {
        if (hasChapterContent(root)) return null
        val message = findErrorMessage(root)
        val objectRoot = root as? JSONObject
        return when {
            objectRoot != null && signalsFailure(objectRoot) ->
                message?.let { "接口返回失败：${summarize(it)}" } ?: "接口返回失败状态且没有章节正文"
            objectRoot?.has("data") == true && objectRoot.isNull("data") ->
                message?.let { "接口未返回正文：${summarize(it)}" } ?: "接口返回 data=null"
            message != null -> "接口未返回正文：${summarize(message)}"
            else -> "接口返回 JSON，但未找到 content/text 正文"
        }
    }

    private fun hasChapterContent(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> false
        is JSONObject -> {
            val keys = value.keys().asSequence().toList()
            keys.any { key ->
                key.lowercase(Locale.ROOT) in CONTENT_KEYS && hasMeaningfulText(value.opt(key))
            } || keys.any { key ->
                key.lowercase(Locale.ROOT) !in ERROR_MESSAGE_KEYS && hasChapterContent(value.opt(key))
            }
        }
        is JSONArray -> (0 until value.length()).any { index -> hasChapterContent(value.opt(index)) }
        else -> false
    }

    private fun hasMeaningfulText(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> false
        is String -> value.trim().let { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        is JSONObject -> value.keys().asSequence().any { key -> hasMeaningfulText(value.opt(key)) }
        is JSONArray -> (0 until value.length()).any { index -> hasMeaningfulText(value.opt(index)) }
        else -> false
    }

    private fun signalsFailure(root: JSONObject): Boolean {
        if (root.has("success") && !root.optBoolean("success", true)) return true
        if (!root.has("code") || root.isNull("code")) return false
        val code = root.opt("code")
        return when (code) {
            is Number -> code.toDouble() != 0.0
            is String -> code.trim().let { it.isNotBlank() && it != "0" }
            else -> false
        }
    }

    private fun findErrorMessage(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val keys = value.keys().asSequence().toList()
            keys.asSequence()
                .filter { it.lowercase(Locale.ROOT) in ERROR_MESSAGE_KEYS }
                .mapNotNull { key -> value.opt(key).asMessageText() }
                .firstOrNull()
                ?: keys.asSequence().mapNotNull { key -> findErrorMessage(value.opt(key)) }.firstOrNull()
        }
        is JSONArray -> (0 until value.length()).asSequence()
            .mapNotNull { index -> findErrorMessage(value.opt(index)) }
            .firstOrNull()
        else -> null
    }

    private fun Any?.asMessageText(): String? = when (this) {
        null, JSONObject.NULL -> null
        is String -> trim().takeIf { it.isNotBlank() }
        is Number, is Boolean -> toString()
        else -> null
    }

    private fun summarize(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim().take(MAX_REASON_LENGTH)

    private val CONTENT_KEYS = setOf("content", "text")
    private val ERROR_MESSAGE_KEYS = setOf("msg", "message", "error")
    private val XHTML_BODY_REGEX = Regex("""(?is)<body\b[^>]*>([\s\S]*?)</body>""")
    private val XHTML_HEAD_REGEX = Regex("""(?is)<head\b[^>]*>[\s\S]*?</head>""")
    private val XHTML_PARAGRAPH_REGEX = Regex("""(?is)<p\b[^>]*>([\s\S]*?)</p>""")
    private val XHTML_CHAPTER_NUMBER_REGEX = Regex(
        """(?is)<(?:div|span)\b[^>]*class\s*=\s*["'][^"']*\bte-chapter-number\b[^"']*["'][^>]*>[\s\S]*?</(?:div|span)>""",
    )
    private val XHTML_HEADING_REGEX = Regex("""(?is)<h1\b[^>]*>[\s\S]*?</h1>""")
    private val XHTML_IMAGE_REGEX = Regex("""(?is)<img\b""")
    private val XHTML_COMMENT_REGEX = Regex("""(?s)<!--.*?-->""")
    private val XHTML_LINE_BREAK_REGEX = Regex("""(?i)<br\s*/?>|</(?:p|div|section|article|li)\s*>""")
    private val XHTML_TAG_REGEX = Regex("""(?s)<[^>]+>""")
    private val INLINE_WHITESPACE_REGEX = Regex("""[ \t\r\f\u00A0]+""")
    private const val MAX_REASON_LENGTH = 240
}
