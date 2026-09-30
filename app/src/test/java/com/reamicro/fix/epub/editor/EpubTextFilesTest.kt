package com.reamicro.fix.epub.editor

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CancellationException

class EpubTextFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file(text: String = "<p>原文</p>"): File =
        temporary.newFile().apply { writeText(text) }

    @Test fun routingIsStrictAndCaseInsensitive() {
        listOf("toc", "OEBPS/toc.ncx", "toc.xml", "a.TOC", "nav.xhtml", "a.HTML", "a.XHTML")
            .forEach { assertTrue(it, usesScriptaEpubEditor(it)) }
        listOf("content.opf", "a.css", "a.xml", "cover.jpg", "a.svg", "font.ttf", "a.js", "a.html.bak")
            .forEach { assertFalse(it, usesScriptaEpubEditor(it)) }
    }

    @Test fun pathTraversalAndSiblingPrefixAreRejected() {
        val root = temporary.newFolder("book")
        temporary.newFolder("book-other")
        File(temporary.root, "book-other/x.html").writeText("secret")
        assertThrows(IllegalArgumentException::class.java) {
            EpubTextFiles.resolve(root, "../book-other/x.html")
        }
        assertThrows(IllegalArgumentException::class.java) { EpubTextFiles.resolve(root, ".") }
    }

    @Test fun symlinkOutsideRootIsRejected() {
        val root = temporary.newFolder("root")
        val other = file()
        Files.createSymbolicLink(File(root, "x.html").toPath(), other.toPath())
        assertThrows(IllegalArgumentException::class.java) { EpubTextFiles.resolve(root, "x.html") }
    }

    @Test fun utf8ChineseAndEmojiRoundTrip() {
        val f = file("<p>中文😀</p>\r\n")
        val loaded = EpubTextFiles.load(f)
        val text = loaded.text.replace("中文", "修改")
        val saved = EpubTextFiles.save(f, text, loaded.snapshot)
        assertEquals(text, f.readText())
        assertEquals(saved.fingerprint.sha256, EpubTextFiles.load(f).snapshot.fingerprint.sha256)
    }

    @Test fun bomAndUtf16ArePreserved() {
        for ((charset, bom) in listOf(
            StandardCharsets.UTF_8 to byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()),
            StandardCharsets.UTF_16LE to byteArrayOf(0xff.toByte(), 0xfe.toByte()),
            StandardCharsets.UTF_16BE to byteArrayOf(0xfe.toByte(), 0xff.toByte()),
        )) {
            val f = temporary.newFile()
            val original = "<p>中文😀</p>\r\n"
            f.writeBytes(bom + original.toByteArray(charset))
            val loaded = EpubTextFiles.load(f)
            assertEquals(original, loaded.text)
            EpubTextFiles.save(f, loaded.text + "尾", loaded.snapshot)
            assertArrayEquals(bom + (original + "尾").toByteArray(charset), f.readBytes())
        }
    }

    @Test fun xmlEncodingDeclarationIsRespected() {
        val f = temporary.newFile()
        val text = "<?xml version=\"1.0\" encoding=\"GB18030\"?>\n<nav>中文</nav>"
        f.writeBytes(text.toByteArray(Charset.forName("GB18030")))
        val loaded = EpubTextFiles.load(f)
        assertEquals(text, loaded.text)
        EpubTextFiles.save(f, text + "章节", loaded.snapshot)
        assertEquals(text + "章节", f.readText(Charset.forName("GB18030")))
    }

    @Test fun malformedInputDoesNotSilentlyReplaceBytes() {
        val f = temporary.newFile().apply { writeBytes(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(Exception::class.java) { EpubTextFiles.load(f) }
        assertArrayEquals(byteArrayOf(0xc3.toByte(), 0x28), f.readBytes())
    }

    @Test fun externalSameSizeEditIsDetectedByHash() {
        val f = file("aaa")
        val original = EpubTextFiles.load(f)
        f.writeText("bbb")
        f.setLastModified(original.snapshot.fingerprint.modified)
        assertThrows(IllegalArgumentException::class.java) {
            EpubTextFiles.save(f, "my changes", original.snapshot)
        }
        assertEquals("bbb", f.readText())
    }

    @Test fun failedEncodingLeavesOriginalAndNoTemporaryFile() {
        val f = file("original")
        val original = EpubTextFiles.load(f)
        assertThrows(Exception::class.java) {
            EpubTextFiles.save(f, "dangling\uD800", original.snapshot)
        }
        assertEquals("original", f.readText())
        assertFalse(temporary.root.listFiles()!!.any { it.name.startsWith(".reamicro-scripta-") })
    }

    @Test fun cancelledSaveLeavesOriginal() {
        val f = file()
        val loaded = EpubTextFiles.load(f)
        assertThrows(CancellationException::class.java) {
            EpubTextFiles.save(f, "replacement", loaded.snapshot) { throw CancellationException() }
        }
        assertEquals(loaded.text, f.readText())
    }

    @Test fun memoryBudgetPreventsOversizedLoad() {
        val f = file("12345")
        assertThrows(IllegalArgumentException::class.java) { EpubTextFiles.load(f, 4) }
        assertEquals("12345", f.readText())
    }

    @Test fun emptyFileCanBeEdited() {
        val f = file("")
        val loaded = EpubTextFiles.load(f)
        EpubTextFiles.save(f, "正文", loaded.snapshot)
        assertEquals("正文", f.readText())
    }

    @Test fun repeatedSavesUseUpdatedFingerprint() {
        val f = file("a")
        val first = EpubTextFiles.load(f)
        val second = EpubTextFiles.save(f, "bb", first.snapshot)
        EpubTextFiles.save(f, "ccc", second)
        assertEquals("ccc", f.readText())
    }

    @Test fun largeSingleLineExceedsOldFiveMegabyteLimit() {
        val text = "<p>" + "a".repeat(6 * 1024 * 1024) + "</p>"
        val f = file(text)
        val loaded = EpubTextFiles.load(f, 16L * 1024 * 1024)
        assertEquals(text.length, loaded.text.length)
        EpubTextFiles.save(f, loaded.text + "\n", loaded.snapshot)
        assertEquals(text.length + 1L, f.length())
    }
}
