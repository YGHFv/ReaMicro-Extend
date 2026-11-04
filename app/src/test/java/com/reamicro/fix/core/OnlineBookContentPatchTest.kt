package com.reamicro.fix.core

import org.junit.Assert.*
import org.junit.Test

class OnlineBookContentPatchTest {
    private val patch = OnlineBookContentPatch(7, 1, "uuid", 500, 123456, "source-id", "code", "fallback")
    private class Sql(val cover: String?) : CatalogSql {
        val writes = mutableListOf<Pair<String, List<Any>>>()
        override fun execute(sql: String, args: List<Any>) { writes.add(sql to args) }
        override fun query(sql: String, args: List<Any>, numeric: Set<Int>): List<List<Any>> {
            assertEquals(listOf(7L, 1L, "uuid"), args)
            return cover?.let { listOf(listOf(it)) }.orEmpty()
        }
    }

    @Test fun preservesLatestCoverAndNeverUpdatesReadingColumns() {
        val sql = Sql("manually-edited")
        patch.apply(sql)
        val (update, args) = sql.writes.first()
        assertEquals("manually-edited", args[4])
        listOf("epubcfi", "chapter", "progress", "total", "finished", "pinned_at", "publisher", "cloud_id").forEach {
            assertFalse(update.contains(it))
        }
        assertEquals(listOf(7L, 1L, "uuid"), args.takeLast(3))
    }

    @Test fun missingBookCannotBeRecreatedByLateUpdate() {
        val sql = Sql(null)
        patch.apply(sql)
        assertTrue(sql.writes.isEmpty())
    }

    @Test fun missingCoverMarksNativeCoverRevisionDirty() {
        val sql = Sql("")
        patch.apply(sql)
        assertEquals("fallback", sql.writes.first().second[4])
        assertEquals(listOf(7L, 123456L, 1L, 123456L), sql.writes.last().second)
    }

    @Test fun syncConflictLeavesEpubAndBookmarkFlagsUntouched() {
        val sql = Sql("cover")
        patch.apply(sql)
        val updates = sql.writes.last().first.substringAfter("DO UPDATE SET")
        assertFalse(updates.contains("epub_dirty"))
        assertFalse(updates.contains("bookmarks_dirty"))
        assertEquals(listOf(7L, 123456L, 0L, 0L), sql.writes.last().second)
    }
}
