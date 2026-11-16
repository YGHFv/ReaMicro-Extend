package com.reamicro.fix.cloud.webdav

import com.reamicro.fix.online.download.*
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class OnlineBookSyncMarkerTest {
    private fun book(parent: File, uuid: String, count: Int = 2): File = File(parent, uuid).apply {
        File(this, "META-INF").mkdirs()
        File(this, "OEBPS/Text").mkdirs()
        File(this, "META-INF/container.xml").writeText("<container/>", Charsets.UTF_8)
        File(this, "OEBPS/content.opf").writeText("<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"uuid\"><metadata><identifier id=\"uuid\">$uuid</identifier></metadata><manifest/><spine/></package>", Charsets.UTF_8)
        File(this, "OEBPS/toc.ncx").writeText("<ncx count=\"$count\"/>", Charsets.UTF_8)
        val chapters = (1..count).map {
            File(this, "OEBPS/Text/$it.xhtml").writeText("PRIVATE-CHAPTER-CONTENT-$it", Charsets.UTF_8)
            OnlineOnDemandChapter("id$it", "https://source/chapter/$it", "第 $it 章", "", "Text/$it.xhtml",
                OnlineChapterState.READY, 100L)
        }
        File(this, "OEBPS/${OnlineOnDemandMetadataStore.FILE_NAME}").writeText(
            OnlineOnDemandMetadataCodec.encode(OnlineOnDemandMetadata("source-id", "https://source/book",
                "书名", OnlineBookDownloadMode.FULL, chapters)), Charsets.UTF_8)
    }
    @Test fun markerKeepsIdentityAndPathsExcludesContentAndIgnoresDownloadState() {
        val temp = Files.createTempDirectory("online-marker-test").toFile()
        try {
            val book = book(temp, "same-uuid")
            val output = File(temp, "marker.epub")
            assertTrue(OnlineBookSyncMarker.write(book, "same-uuid", output))
            val original = output.readBytes()
            ZipFile(output).use { zip ->
                val entries = zip.entries().asSequence().associate { e -> e.name to zip.getInputStream(e).readBytes().toString(Charsets.UTF_8) }
                assertTrue(entries.getValue(OnlineBookSyncMarker.PATH).contains("same-uuid"))
                assertTrue(entries.getValue("OEBPS/content.opf").contains("same-uuid"))
                assertFalse(entries.values.any { "PRIVATE-CHAPTER-CONTENT" in it })
                val metadata = OnlineOnDemandMetadataCodec.decode(entries.getValue("OEBPS/${OnlineOnDemandMetadataStore.FILE_NAME}"))
                assertTrue(metadata.chapters.all { it.state == OnlineChapterState.PENDING })
                assertEquals(listOf("id1", "id2"), metadata.chapters.map { it.sourceChapterId })
            }
            File(book, "OEBPS/Text/1.xhtml").writeText("UPDATED-PRIVATE-CONTENT", Charsets.UTF_8)
            OnlineBookSyncMarker.write(book, "same-uuid", output)
            assertArrayEquals(original, output.readBytes())
        } finally { temp.deleteRecursively() }
    }
    @Test fun receiverKeepsMatchingLocalChaptersAndRejectsDifferentBookIdentity() {
        val temp = Files.createTempDirectory("online-marker-merge").toFile()
        try {
            val local = book(File(temp, "local"), "same-uuid")
            val incoming = File(temp, "incoming/same-uuid")
            OnlineBookSyncMarker.entries(local, "same-uuid")!!.forEach { (path, data) ->
                File(incoming, path).apply { parentFile.mkdirs(); writeBytes(data) }
            }
            OnlineBookSyncMarker.mergeLocalContent(incoming, local, "same-uuid")
            assertEquals("PRIVATE-CHAPTER-CONTENT-1", File(incoming, "OEBPS/Text/1.xhtml").readText(Charsets.UTF_8))
            assertTrue(OnlineBookSyncMarker.metadata(incoming)!!.chapters.all { it.state == OnlineChapterState.READY })
            assertThrows(IllegalArgumentException::class.java) {
                OnlineBookSyncMarker.mergeLocalContent(incoming, local, "other-uuid")
            }
        } finally { temp.deleteRecursively() }
    }
    @Test fun olderThousandChapterMarkerCannotTruncateLocal1010Chapters() {
        val temp = Files.createTempDirectory("online-marker-older").toFile()
        try {
            val local = book(File(temp, "local"), "same-uuid", 1010)
            val remote = book(File(temp, "remote"), "same-uuid", 1000)
            val incoming = File(temp, "incoming/same-uuid")
            OnlineBookSyncMarker.entries(remote, "same-uuid")!!.forEach { (path, data) ->
                File(incoming, path).apply { parentFile.mkdirs(); writeBytes(data) }
            }
            OnlineBookSyncMarker.mergeLocalContent(incoming, local, "same-uuid")
            assertEquals(1010, OnlineBookSyncMarker.metadata(incoming)!!.chapters.size)
            assertEquals("PRIVATE-CHAPTER-CONTENT-1010", File(incoming, "OEBPS/Text/1010.xhtml").readText(Charsets.UTF_8))
            assertEquals(File(local, "OEBPS/toc.ncx").readText(Charsets.UTF_8),
                File(incoming, "OEBPS/toc.ncx").readText(Charsets.UTF_8))
            assertEquals("same-uuid", OnlineBookSyncMarker.uuid(incoming))
        } finally { temp.deleteRecursively() }
    }
    @Test fun newerCatalogAddsPendingChaptersWithoutOverwritingLocalCorrections() {
        val temp = Files.createTempDirectory("online-marker-newer").toFile()
        try {
            val local = book(File(temp, "local"), "same-uuid", 2)
            val remote = book(File(temp, "remote"), "same-uuid", 3)
            File(local, "OEBPS/Text/1.xhtml").writeText("LOCAL-CORRECTED-CONTENT", Charsets.UTF_8)
            val incoming = File(temp, "incoming/same-uuid")
            OnlineBookSyncMarker.entries(remote, "same-uuid")!!.forEach { (path, data) ->
                File(incoming, path).apply { parentFile.mkdirs(); writeBytes(data) }
            }
            OnlineBookSyncMarker.mergeLocalContent(incoming, local, "same-uuid")
            assertEquals("LOCAL-CORRECTED-CONTENT", File(incoming, "OEBPS/Text/1.xhtml").readText(Charsets.UTF_8))
            assertEquals(listOf(OnlineChapterState.READY, OnlineChapterState.READY, OnlineChapterState.PENDING),
                OnlineBookSyncMarker.metadata(incoming)!!.chapters.map { it.state })
        } finally { temp.deleteRecursively() }
    }
    @Test fun equalCountsWithDifferentIdentityOrOrderKeepLocalCatalogForSourceRefresh() {
        val temp = Files.createTempDirectory("online-marker-divergent").toFile()
        try {
            val local = book(File(temp, "local"), "same-uuid")
            val remote = book(File(temp, "remote"), "same-uuid")
            val original = OnlineBookSyncMarker.metadata(remote)!!
            for (chapters in listOf(original.chapters.reversed(),
                original.chapters.map { it.copy(sourceChapterId = "changed-${it.sourceChapterId}") })) {
                File(remote, "OEBPS/${OnlineOnDemandMetadataStore.FILE_NAME}").writeText(
                    OnlineOnDemandMetadataCodec.encode(original.copy(chapters = chapters)), Charsets.UTF_8)
                val incoming = File(temp, "incoming/same-uuid")
                OnlineBookSyncMarker.entries(remote, "same-uuid")!!.forEach { (path, data) ->
                    File(incoming, path).apply { parentFile.mkdirs(); writeBytes(data) }
                }
                OnlineBookSyncMarker.mergeLocalContent(incoming, local, "same-uuid")
                assertEquals(listOf("id1", "id2"), OnlineBookSyncMarker.metadata(incoming)!!.chapters.map { it.sourceChapterId })
                assertEquals("PRIVATE-CHAPTER-CONTENT-1", File(incoming, "OEBPS/Text/1.xhtml").readText(Charsets.UTF_8))
            }
        } finally { temp.deleteRecursively() }
    }
    @Test fun progressOnlyOmitsRemoteCatalogAndPreservesExistingLocalBook() {
        val temp = Files.createTempDirectory("online-marker-progress").toFile()
        try {
            val local = book(File(temp, "local"), "same-uuid", 3)
            val remote = book(File(temp, "remote"), "same-uuid", 2)
            val entries = OnlineBookSyncMarker.entries(remote, "same-uuid", progressOnly = true)!!
            assertFalse(entries.keys.any { it in setOf("OEBPS/Text/1.xhtml", "OEBPS/Text/2.xhtml") })
            assertTrue(entries.getValue("OEBPS/content.opf").toString(Charsets.UTF_8).contains("same-uuid"))
            assertFalse(entries.values.any { "https://source/chapter/" in it.toString(Charsets.UTF_8) })
            assertTrue(OnlineOnDemandMetadataCodec.decode(entries.getValue("OEBPS/${OnlineOnDemandMetadataStore.FILE_NAME}")
                .toString(Charsets.UTF_8)).chapters.isEmpty())
            val incoming = File(temp, "incoming/same-uuid")
            entries.forEach { (path, data) -> File(incoming, path).apply { parentFile.mkdirs(); writeBytes(data) } }
            assertNotNull(OnlineBookSyncMarker.metadata(incoming))
            assertTrue(OnlineBookSyncMarker.needsSourceCatalog(incoming))
            OnlineBookSyncMarker.mergeLocalContent(incoming, local, "same-uuid")
            assertFalse(OnlineBookSyncMarker.needsSourceCatalog(incoming))
            assertEquals(3, OnlineBookSyncMarker.metadata(incoming)!!.chapters.size)
            assertEquals("PRIVATE-CHAPTER-CONTENT-3", File(incoming, "OEBPS/Text/3.xhtml").readText(Charsets.UTF_8))
            assertEquals("same-uuid", OnlineBookSyncMarker.uuid(incoming))
        } finally { temp.deleteRecursively() }
    }
}
