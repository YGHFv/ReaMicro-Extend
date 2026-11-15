package com.reamicro.fix.online.download

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** Snapshot the existing book, then copy only when there is a completed update to publish. */
internal class OnlineUpdateStaging(
    private val original: File,
    private val checkCurrent: () -> Unit,
) {
    private data class Stamp(val size: Long, val modified: String, val key: String?, val directory: Boolean)
    private val snapshot = inventory()

    private fun inventory(): Map<String, Stamp> {
        checkCurrent()
        check(original.isDirectory) { "待更新图书目录已不存在" }
        val root = original.toPath()
        return Files.walk(root).use { paths ->
            val files = linkedMapOf<String, Stamp>()
            paths.forEach { path ->
                checkCurrent()
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                check(!attrs.isSymbolicLink && (attrs.isDirectory || attrs.isRegularFile)) { "图书目录包含不支持的文件" }
                files[root.relativize(path).toString()] =
                    Stamp(attrs.size(), attrs.lastModifiedTime().toString(), attrs.fileKey()?.toString(), attrs.isDirectory)
            }
            files
        }
    }

    fun requireUnchanged() {
        check(snapshot == inventory()) { "图书在下载期间已被编辑或补全，请重新更新" }
    }

    fun copyTo(staged: File) {
        requireUnchanged()
        check(!staged.exists()) { "更新暂存目录已存在" }
        check(staged.mkdirs()) { "无法创建更新暂存目录" }
        snapshot.forEach { (relative, stamp) ->
            checkCurrent()
            if (relative.isEmpty()) return@forEach
            val target = File(staged, relative)
            if (stamp.directory) {
                check(target.isDirectory || target.mkdirs()) { "无法创建更新暂存目录" }
            } else {
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                File(original, relative).inputStream().buffered().use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCurrent()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                check(target.length() == stamp.size) { "图书复制期间发生变化" }
            }
        }
        requireUnchanged()
    }
}
