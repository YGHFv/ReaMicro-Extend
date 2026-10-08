package com.reamicro.fix.online.epub

import java.security.MessageDigest
import java.util.Locale

internal data class OnlineEpubImageManifestItem(
    val fileName: String,
    val mimeType: String,
)

internal fun stableOnlineImageFileStem(url: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
    return "online_img_" + digest.take(10).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal fun onlineEpubImageManifestItem(fileName: String): OnlineEpubImageManifestItem? {
    val safeName = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
    if (safeName.isBlank()) return null
    val mimeType = when (safeName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> return null
    }
    return OnlineEpubImageManifestItem(safeName, mimeType)
}

internal fun mergeOnlineEpubImageManifest(
    original: String,
    images: Collection<OnlineEpubImageManifestItem>,
): String {
    if (!original.contains("</manifest>", ignoreCase = true)) return original
    val existingHrefs = Regex("""(?is)<item\b[^>]*\bhref\s*=\s*["']Images/([^"']+)["'][^>]*/?>""")
        .findAll(original)
        .map { it.groupValues[1] }
        .toMutableSet()
    val additions = images
        .distinctBy { it.fileName }
        .filter { it.fileName !in existingHrefs }
        .joinToString("\n") { image ->
            val idSuffix = stableOnlineImageFileStem(image.fileName).removePrefix("online_img_").take(16)
            "    <item id=\"online-img-$idSuffix\" href=\"Images/${image.fileName.xmlEscapeCompat()}\" media-type=\"${image.mimeType}\"/>"
        }
    if (additions.isBlank()) return original
    return original.replaceFirst(
        Regex("""(?i)</manifest>"""),
        "$additions\n  </manifest>",
    )
}

private fun String.xmlEscapeCompat(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
