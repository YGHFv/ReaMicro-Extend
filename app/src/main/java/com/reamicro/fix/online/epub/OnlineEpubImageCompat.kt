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
): String = OnlineEpubManifest.merge(original, images.map { image ->
    val idSuffix = stableOnlineImageFileStem(image.fileName).removePrefix("online_img_").take(16)
    OnlineEpubManifest.Item("online-img-$idSuffix", "Images/${image.fileName}", image.mimeType)
})
