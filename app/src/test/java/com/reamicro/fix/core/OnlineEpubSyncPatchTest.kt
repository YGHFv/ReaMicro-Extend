package com.reamicro.fix.core

import java.sql.DriverManager
import org.junit.Assert.*
import org.junit.Test

class OnlineEpubSyncPatchTest {
    private fun database(check: (CatalogSql) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { s ->
                s.execute("CREATE TABLE books(id INTEGER PRIMARY KEY,uid INTEGER,uuid TEXT,updated INTEGER,epubcfi TEXT,chapter TEXT,progress INTEGER)")
                s.execute("CREATE TABLE book_sync_state(book_id INTEGER PRIMARY KEY,metadata_updated_at INTEGER,epub_dirty INTEGER," +
                    "cover_dirty INTEGER,bookmarks_dirty INTEGER,epub_revision INTEGER,cover_revision INTEGER,bookmarks_revision INTEGER," +
                    "epub_sha256 TEXT,cover_source_sha256 TEXT,bookmarks_sha256 TEXT)")
                s.execute("INSERT INTO books VALUES(7,123,'same-book',100,'original-cfi','chapter-1',42)")
            }
            val sql = object : CatalogSql {
                override fun execute(sql: String, args: List<Any>) {
                    db.prepareStatement(sql).use { s -> args.forEachIndexed { i, v -> s.setObject(i + 1, v) }; s.execute() }
                }
                override fun query(sql: String, args: List<Any>, numeric: Set<Int>): List<List<Any>> {
                    db.prepareStatement(sql).use { s ->
                        args.forEachIndexed { i, v -> s.setObject(i + 1, v) }
                        s.executeQuery().use { r ->
                            val rows = arrayListOf<List<Any>>()
                            while (r.next()) rows += (1..r.metaData.columnCount).map { i ->
                                if (i - 1 in numeric) r.getLong(i) else r.getString(i)
                            }
                            return rows
                        }
                    }
                }
            }
            check(sql)
        }
    }
    @Test fun repeatedContentCommitKeepsIdentityAndIncrementsAtSameMillisecond() = database { sql ->
        assertTrue(OnlineEpubSyncPatch.apply(sql, 123, "same-book", 200))
        assertTrue(OnlineEpubSyncPatch.apply(sql, 123, "same-book", 200))
        assertTrue(OnlineEpubSyncPatch.apply(sql, 123, "same-book", 150))
        assertEquals(listOf(1L, 202L), sql.query("SELECT epub_dirty,epub_revision FROM book_sync_state", emptyList(), setOf(0, 1)).single())
        assertEquals(listOf(7L, 123L, "same-book", "original-cfi", "chapter-1", 42L),
            sql.query("SELECT id,uid,uuid,epubcfi,chapter,progress FROM books", emptyList(), setOf(0, 1, 5)).single())
    }
    @Test fun preservesOtherDirtyBitsHashesAndDoesNotClearNewRevisionAfterOldUpload() = database { sql ->
        sql.execute("INSERT INTO book_sync_state VALUES(7,100,0,1,1,100,9,11,'old-epub','cover-hash','marks-hash')", emptyList())
        OnlineEpubSyncPatch.apply(sql, 123, "same-book", 200)
        sql.execute("UPDATE book_sync_state SET epub_dirty=CASE WHEN epub_revision=? THEN 0 ELSE epub_dirty END WHERE book_id=7", listOf(100L))
        assertEquals(listOf(1L, 1L, 1L, 9L, 11L, "old-epub", "cover-hash", "marks-hash"),
            sql.query("SELECT epub_dirty,cover_dirty,bookmarks_dirty,cover_revision,bookmarks_revision,epub_sha256,cover_source_sha256,bookmarks_sha256 FROM book_sync_state",
                emptyList(), setOf(0, 1, 2, 3, 4)).single())
    }
    @Test fun wrongAccountOrDeletedBookMakesNoChanges() = database { sql ->
        assertFalse(OnlineEpubSyncPatch.apply(sql, 999, "same-book", 200))
        assertFalse(OnlineEpubSyncPatch.apply(sql, 123, "deleted-book", 200))
        assertTrue(sql.query("SELECT book_id FROM book_sync_state", emptyList(), setOf(0)).isEmpty())
        assertEquals(100L, sql.query("SELECT updated FROM books", emptyList(), setOf(0)).single().single())
    }
}
