package com.reamicro.fix.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCopyCompatTest {
    @Test
    fun preservesNewContentDownloadedFieldWhileUpdatingBackupMetadata() {
        val original = CurrentBook(
            id = 1L,
            uuid = "uuid",
            uid = 2L,
            title = "title",
            subtitle = "subtitle",
            author = "author",
            cover = "old-cover",
            size = 3L,
            uri = "uri",
            group = "group",
            created = 4L,
            cfiVersion = 5,
            embeddedFonts = 1,
            epubcfi = "cfi",
            chapter = "chapter",
            progress = 0.5f,
            total = 6L,
            finished = 7L,
            updated = 8L,
            pinnedAt = 9L,
            cloudId = 10L,
            backupType = 11,
            backupId = "old-id",
            backupCode = "old-code",
            publisher = "old-publisher",
            contentDownloaded = true,
        )

        val copied = BookCopyCompat.copy(
            original,
            BookCopyPatch(
                cover = "new-cover",
                size = 30L,
                updated = 80L,
                backupType = 12,
                backupId = "new-id",
                backupCode = "new-code",
                publisher = "new-publisher",
            ),
        ) as CurrentBook

        assertEquals("new-cover", copied.cover)
        assertEquals(30L, copied.size)
        assertEquals(80L, copied.updated)
        assertEquals(12, copied.backupType)
        assertEquals("new-id", copied.backupId)
        assertEquals("new-code", copied.backupCode)
        assertEquals("new-publisher", copied.publisher)
        assertTrue(copied.contentDownloaded)
        assertEquals(9L, copied.pinnedAt)
    }

    @Test
    fun supportsLegacyLayoutWithoutEmbeddedFontsOrPinnedAt() {
        val original = LegacyBook(
            id = 1L,
            uuid = "uuid",
            uid = 2L,
            title = "title",
            subtitle = "subtitle",
            author = "author",
            cover = "cover",
            size = 3L,
            uri = "uri",
            group = "group",
            created = 4L,
            cfiVersion = 5,
            epubcfi = "cfi",
            chapter = "chapter",
            progress = 0.5f,
            total = 6L,
            finished = 7L,
            updated = 8L,
            cloudId = 9L,
            backupType = 10,
            backupId = "old-id",
            backupCode = "old-code",
            publisher = "old-publisher",
        )

        val copied = BookCopyCompat.copy(
            original,
            BookCopyPatch(title = "new-title", backupType = 20, publisher = "new-publisher"),
        ) as LegacyBook

        assertEquals("new-title", copied.title)
        assertEquals(20, copied.backupType)
        assertEquals("new-publisher", copied.publisher)
        assertFalse(copied.title == original.title)
    }

    private data class CurrentBook(
        val id: Long,
        val uuid: String,
        val uid: Long,
        val title: String,
        val subtitle: String,
        val author: String,
        val cover: String,
        val size: Long,
        val uri: String,
        val group: String,
        val created: Long,
        val cfiVersion: Int,
        val embeddedFonts: Int,
        val epubcfi: String,
        val chapter: String,
        val progress: Float,
        val total: Long,
        val finished: Long,
        val updated: Long,
        val pinnedAt: Long,
        val cloudId: Long,
        val backupType: Int,
        val backupId: String,
        val backupCode: String,
        val publisher: String,
        val contentDownloaded: Boolean,
    )

    private data class LegacyBook(
        val id: Long,
        val uuid: String,
        val uid: Long,
        val title: String,
        val subtitle: String,
        val author: String,
        val cover: String,
        val size: Long,
        val uri: String,
        val group: String,
        val created: Long,
        val cfiVersion: Int,
        val epubcfi: String,
        val chapter: String,
        val progress: Float,
        val total: Long,
        val finished: Long,
        val updated: Long,
        val cloudId: Long,
        val backupType: Int,
        val backupId: String,
        val backupCode: String,
        val publisher: String,
    )
}
