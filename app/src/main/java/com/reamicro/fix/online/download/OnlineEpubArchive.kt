package com.reamicro.fix.online.download

import com.reamicro.fix.reload.TaskCancellation
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

internal fun unzipOnlineCompletionEpub(
    file: File,
    checkCurrent: () -> Unit = TaskCancellation::checkCurrent,
): File {
    checkCurrent()
    val parent = requireNotNull(file.absoluteFile.parentFile)
    val output = Files.createTempDirectory(parent.toPath(), ".online-unzip-").toFile().canonicalFile
    try {
        val paths = hashSetOf<String>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCurrent()
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(name)) { "EPUB 使用了绝对文件路径" }
                val target = File(output, name).canonicalFile
                require(target != output && target.toPath().startsWith(output.toPath())) { "EPUB 文件路径超出暂存目录" }
                require(paths.add(target.path)) { "EPUB 文件路径重复：${entry.name}" }
                if (entry.isDirectory) {
                    check(target.isDirectory || target.mkdirs()) { "EPUB 目录创建失败" }
                    checkCurrent()
                    require(zip.read() == -1) { "EPUB 目录记录含有文件内容" }
                } else {
                    val directory = requireNotNull(target.parentFile)
                    check(directory.isDirectory || directory.mkdirs()) { "EPUB 目录创建失败" }
                    target.outputStream().buffered().use { sink ->
                        while (true) {
                            checkCurrent()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            if (count > 0) sink.write(buffer, 0, count)
                        }
                    }
                }
                // On failure/cancellation, close the archive immediately; closeEntry
                // would otherwise drain the remaining compressed entry before returning.
                zip.closeEntry()
            }
        }
        checkCurrent()
        return output
    } catch (failure: Throwable) {
        output.deleteRecursively()
        throw failure
    }
}
