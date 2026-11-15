package com.reamicro.fix.online.download

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Per-download disk staging. No book-length collection of image byte arrays is retained. */
internal class OnlineChapterImageCache(
    directory: File,
    private val maximumBytes: Long = 512L * 1024 * 1024,
    private val minimumFreeBytes: Long = 32L * 1024 * 1024,
) {
    data class Entry(val url: String, val file: File, val mimeType: String) {
        val fileName: String get() = file.name
    }

    private val root = directory.canonicalFile
    private val entries = linkedMapOf<String, Entry>()
    private var storedBytes = 0L

    @Synchronized
    fun get(url: String): Entry? = entries[url]?.takeIf { it.file.isFile }

    @Synchronized
    fun store(
        url: String, fileName: String, mimeType: String, bytes: ByteArray,
        checkCurrent: () -> Unit,
    ): Entry {
        checkCurrent()
        get(url)?.let { return it }
        require(fileName.matches(Regex("online_img_[0-9a-f]+\\.[a-zA-Z0-9]{1,8}"))) { "图片缓存文件名无效" }
        require(bytes.isNotEmpty() && bytes.size <= MAXIMUM_IMAGE_BYTES) { "在线图片为空或超过 16 MiB" }
        check(storedBytes + bytes.size <= maximumBytes) { "本次图书图片缓存超过 ${maximumBytes / 1024 / 1024} MiB" }
        check(root.isDirectory || root.mkdirs()) { "无法创建图片暂存目录" }
        check(root.usableSpace - bytes.size >= minimumFreeBytes) { "剩余空间不足，已停止缓存图片" }
        val target = File(root, fileName)
        val temporary = File(root, ".${UUID.randomUUID()}.partial")
        try {
            temporary.outputStream().buffered().use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    checkCurrent()
                    val count = minOf(16 * 1024, bytes.size - offset)
                    output.write(bytes, offset, count)
                    offset += count
                }
            }
            checkCurrent()
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val entry = Entry(url, target, mimeType)
            entries[url] = entry
            storedBytes += bytes.size
            return entry
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val MAXIMUM_IMAGE_BYTES = 16 * 1024 * 1024
    }
}
