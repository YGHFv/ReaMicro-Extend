package com.reamicro.fix.core

import java.io.File
import java.util.zip.ZipFile

/** 动态加载文件在写入内容前设为只读，通过已打开的文件描述符完成写入。 */
internal object HostNativeLibraryFile {
    fun extract(apk: File, entryName: String, target: File): File = ZipFile(apk).use { zip ->
        val entry = zip.getEntry(entryName) ?: error("module apk missing $entryName")
        val expectedSize = entry.size
        require(expectedSize > 0) { "Empty native library: $entryName" }
        if (target.isFile && target.length() == expectedSize) {
            // 重试时也检查已有文件，不能把上次留下的可写 SO 直接交给 System.load。
            makeReadOnly(target)
            return@use target
        }
        val directory = requireNotNull(target.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create native library directory" }
        val temp = File.createTempFile(target.name, ".tmp", directory)
        try {
            temp.outputStream().use { output ->
                makeReadOnly(temp)
                zip.getInputStream(entry).use { input ->
                    check(input.copyTo(output) == expectedSize) { "Incomplete native library: $entryName" }
                }
            }
            check(temp.renameTo(target)) { "Cannot install ${target.name}" }
            target
        } finally {
            temp.delete()
        }
    }

    private fun makeReadOnly(file: File) {
        check(file.setReadOnly() && !file.canWrite()) { "Cannot make native library read-only: ${file.name}" }
    }
}
