package com.reamicro.fix.epub.editor

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class VerifiedFileIOTest {
    @Test fun replacementPreservesExactBytesIncludingEncodingAndNewlines() {
        val directory = Files.createTempDirectory("epub-copy").toFile()
        try {
            val source = directory.resolve("source.xhtml")
            val target = directory.resolve("target.xhtml")
            val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
                "中文\r\n正文\r\n".toByteArray(Charsets.UTF_8)
            source.writeBytes(bytes)
            target.writeText("原内容", Charsets.UTF_8)
            VerifiedFileIO.copyAtomic(source, target)
            assertArrayEquals(bytes, target.readBytes())
        } finally {
            directory.listFiles().orEmpty().forEach { it.delete() }
            directory.delete()
        }
    }

    @Test fun concurrentTargetEditIsPreservedWhenCommitWasDelayed() {
        val directory = Files.createTempDirectory("epub-conflict").toFile()
        try {
            val source = directory.resolve("source.xhtml").apply { writeText("新内容", Charsets.UTF_8) }
            val target = directory.resolve("target.xhtml").apply { writeText("原内容", Charsets.UTF_8) }
            assertThrows(IllegalStateException::class.java) {
                VerifiedFileIO.copyAtomic(source, target, commit = { action ->
                    target.writeText("并发修改", Charsets.UTF_8)
                    action()
                })
            }
            assertEquals("并发修改", target.readText(Charsets.UTF_8))
            assertEquals(2, directory.listFiles()!!.size)
        } finally {
            directory.listFiles().orEmpty().forEach { it.delete() }
            directory.delete()
        }
    }
}
