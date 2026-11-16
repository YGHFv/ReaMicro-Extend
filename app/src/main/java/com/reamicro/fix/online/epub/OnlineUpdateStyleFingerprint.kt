package com.reamicro.fix.online.epub

import com.reamicro.fix.settings.OnlineEpubStyleKind
import com.reamicro.fix.settings.OnlineEpubStyleSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Changes to selected styles or their files require migration; checking never renders images. */
internal object OnlineUpdateStyleFingerprint {
    const val PATH = "OEBPS/reamicro-style-inputs.sha256"

    fun of(settings: OnlineEpubStyleSettings): String {
        val selected = OnlineEpubStyleKind.entries.mapNotNull(settings::selected)
        val inputs = buildString {
            // 样式渲染修复后，下次更新仅同步本地资源，不重新下载已有章节。
            append("style-inputs-v4\n").append(settings.headerScope.name).append('\n')
            selected.forEach { style ->
                append(style).append('\n')
                listOf(style.assetPath, style.fontFamily)
                    .filter { it.isNotBlank() && (it.contains('/') || it.contains(File.separatorChar)) }
                    .distinct().forEach { value ->
                        val path = File(value).toPath()
                        append(path).append(':')
                        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                            val attrs = Files.readAttributes(
                                path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
                            )
                            append(attrs.size()).append(':').append(attrs.lastModifiedTime())
                                .append(':').append(attrs.fileKey())
                        } else append("missing")
                        append('\n')
                    }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(inputs.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun matches(bookDir: File, fingerprint: String): Boolean =
        File(bookDir, PATH).let { it.isFile && it.length() == 64L && it.readText() == fingerprint }
}
