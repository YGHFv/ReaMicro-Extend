package com.reamicro.fix.online.epub

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 成书字体嵌入。
 *
 * 样式里选中的字体是设备上的字体文件路径，阅微解析 EPUB 时读不到设备字体，因此把字体复制进
 * `OEBPS/Fonts/` 并在 CSS 里用 `@font-face` 引用。同一字体全书只嵌一份，按内容哈希去重。
 */
internal object OnlineEpubFontEmbedder {
    /** 计算某个字体文件对应的嵌入信息；文件不存在或不是字体时返回 null。 */
    fun faceFor(path: String): OnlineEpubFontFace? {
        val file = File(path.trim())
        if (!file.isFile) return null
        val extension = file.extension.lowercase(Locale.ROOT)
        val format = FORMATS[extension] ?: return null
        val family = "rm-font-" + contentHash(file)
        return OnlineEpubFontFace(
            family = family,
            href = "../Fonts/$family.$extension",
            format = format,
        )
    }

    /** 嵌入文件在书目录中的相对路径（相对 OEBPS）。 */
    fun manifestHref(face: OnlineEpubFontFace): String = "Fonts/" + face.href.substringAfterLast('/')

    fun mediaType(face: OnlineEpubFontFace): String =
        when (face.href.substringAfterLast('.').lowercase(Locale.ROOT)) {
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "font/ttf"
        }

    /** 把字体登记进 content.opf 的 manifest；已登记则原样返回。 */
    fun mergeManifest(original: String, faces: Collection<OnlineEpubFontFace>): String {
        if (!original.contains("</manifest>", ignoreCase = true)) return original
        val existing = Regex("""(?is)<item\b[^>]*\bhref\s*=\s*["']Fonts/([^"']+)["'][^>]*/?>""")
            .findAll(original)
            .map { it.groupValues[1] }
            .toSet()
        val additions = faces
            .distinctBy { it.family }
            .filter { manifestHref(it).removePrefix("Fonts/") !in existing }
            .joinToString("\n") { face ->
                """    <item id="${face.family}" href="${manifestHref(face)}" media-type="${mediaType(face)}"/>"""
            }
        if (additions.isBlank()) return original
        return original.replaceFirst(Regex("""(?i)</manifest>"""), "$additions\n  </manifest>")
    }

    private fun contentHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    private val FORMATS = mapOf(
        "ttf" to "truetype",
        "otf" to "opentype",
        "ttc" to "truetype",
        "woff" to "woff",
        "woff2" to "woff2",
    )
}
