package com.reamicro.fix.epub.editor

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest

internal class EpubWriteNotConfirmed(message: String, cause: Throwable? = null) : IOException(message, cause)

internal object VerifiedFileIO {
    data class Digest(val size: Long, val sha256: String)
    fun digest(file: File): Digest {
        require(file.isFile) { "目标文件不存在：${file.name}" }
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n); count += n
            }
        }
        check(file.length() == count) { "校验期间文件长度变化" }
        return Digest(count, hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }
    fun requireMatches(file: File, expected: Digest) {
        try {
            check(digest(file) == expected) { "读取结果与预期字节不一致" }
        } catch (error: Exception) {
            throw EpubWriteNotConfirmed("写入已发生，但 ${file.name} 的写后校验未通过。请重新打开检查，不要重复提交。", error)
        }
    }
    fun copyAtomic(source: File, target: File): Digest {
        val expected = digest(source)
        if (source.canonicalFile == target.canonicalFile) {
            requireMatches(target, expected)
            return expected
        }
        val oldTarget = target.takeIf { it.exists() }?.let(::digest)
        val parent = requireNotNull(target.parentFile)
        require(parent.isDirectory) { "目标文件夹不存在" }
        val temp = File.createTempFile(".reamicro-copy-", ".tmp", parent)
        try {
            source.inputStream().use { input -> temp.outputStream().use { output ->
                input.copyTo(output); output.fd.sync()
            } }
            check(digest(temp) == expected) { "复制期间源文件发生变化，未替换目标" }
            check(if (oldTarget == null) !target.exists() else digest(target) == oldTarget) {
                "目标文件已被其他操作修改，未覆盖"
            }
            try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            requireMatches(target, expected)
            return expected
        } finally { temp.delete() }
    }
    fun requireMissing(file: File) {
        if (file.exists()) throw EpubWriteNotConfirmed("删除未通过检查：${file.name} 仍然存在")
    }
}
