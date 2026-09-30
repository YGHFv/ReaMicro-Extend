package com.reamicro.fix.epub.editor

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/** Bounded, read-only list decoration; never loads/hashes a complete chapter. */
internal object EpubChapterTitlePreview {
    const val MAX_BYTES = 64 * 1024
    private val ignored = Regex("<!--.*?-->|<(script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val title = Regex("<(?:[\\w.-]+:)?title\\b[^>]*>(.*?)</(?:[\\w.-]+:)?title\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val heading = Regex("<(?:[\\w.-]+:)?h([1-3])\\b[^>]*>(.*?)</(?:[\\w.-]+:)?h\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tags = Regex("<[^>]*>")
    private val entities = Regex("&(#x[0-9a-fA-F]+|#X[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")
    private val whitespace = Regex("\\s+")
    private val encoding = Regex("""encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun read(file: File, checkCancelled: () -> Unit = {}): String =
        file.inputStream().use { read(it, checkCancelled) }

    fun read(input: InputStream, checkCancelled: () -> Unit = {}): String {
        val bytes = ByteArray(MAX_BYTES)
        var count = 0
        while (count < bytes.size) {
            checkCancelled()
            val n = input.read(bytes, count, minOf(4096, bytes.size - count))
            if (n <= 0) break
            count += n
        }
        checkCancelled()
        val charset = when {
            count >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            count >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> {
                val declaration = String(bytes, 0, minOf(count, 256), Charsets.US_ASCII)
                val name = encoding.find(declaration)?.groupValues?.get(1)
                runCatching { name?.let(Charset::forName) }.getOrNull() ?: Charsets.UTF_8
            }
        }
        return extract(String(bytes, 0, count, charset))
    }

    fun extract(prefix: String): String {
        val clean = ignored.replace(prefix, "")
        val fromTitle = title.findAll(clean).map { plain(it.groupValues[1]) }.firstOrNull { it.isNotBlank() }
        return fromTitle ?: heading.findAll(clean).map { plain(it.groupValues[2]) }
            .firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun plain(markup: String): String {
        val text = entities.replace(tags.replace(markup, "")) { match ->
            val entity = match.groupValues[1]
            when (entity) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> {
                    val code = if (entity.startsWith("#x", true)) entity.substring(2).toIntOrNull(16)
                    else entity.substring(1).toIntOrNull()
                    if (code != null && Character.isValidCodePoint(code) && code !in 0xd800..0xdfff) {
                        String(Character.toChars(code))
                    } else match.value
                }
            }
        }
        return whitespace.replace(text.replace('\u00a0', ' '), " ").trim().take(240)
    }
}
