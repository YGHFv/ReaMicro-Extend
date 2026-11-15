package com.reamicro.fix.online.epub

import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal object OnlineEpubFontEmbedder {

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

    fun manifestHref(face: OnlineEpubFontFace): String = "Fonts/" + face.href.substringAfterLast('/')

    fun mediaType(face: OnlineEpubFontFace): String =
        when (face.href.substringAfterLast('.').lowercase(Locale.ROOT)) {
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "font/ttf"
        }

    fun mergeManifest(original: String, faces: Collection<OnlineEpubFontFace>): String =
        OnlineEpubManifest.merge(original, faces.distinctBy { it.family }.map { face ->
            OnlineEpubManifest.Item(face.family, manifestHref(face), mediaType(face))
        })

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
