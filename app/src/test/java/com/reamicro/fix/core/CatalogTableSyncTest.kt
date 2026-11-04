package com.reamicro.fix.core

import org.junit.Assert.*
import org.junit.Test

class CatalogTableSyncTest {
    private fun row(order: Long, title: String = "chapter") = listOf<Any>(7L, order, title, "Text/$order.xhtml", "", "cfi:$order", 0L)
    private fun stored(id: Long, order: Long, title: String = "chapter") = CatalogStoredRow(id, row(order, title))

    @Test fun unchangedCatalogHasNoWrites() {
        assertEquals(0, CatalogTableSync.plan(listOf(stored(10, 1), stored(20, 2)), listOf(row(1), row(2))).count)
    }

    @Test fun appendKeepsExistingPrimaryKeys() {
        val changes = CatalogTableSync.plan(listOf(stored(10, 1)), listOf(row(1), row(2)))
        assertEquals(listOf(row(2)), changes.added)
        assertTrue(changes.changed.isEmpty())
        assertTrue(changes.removed.isEmpty())
    }

    @Test fun changedChapterKeepsItsPrimaryKey() {
        val changes = CatalogTableSync.plan(listOf(stored(10, 1)), listOf(row(1, "new")))
        assertEquals(listOf(stored(10, 1, "new")), changes.changed)
        assertEquals(1, changes.count)
    }

    @Test fun removalOnlyDeletesObsoleteRows() {
        assertEquals(listOf(20L), CatalogTableSync.plan(listOf(stored(10, 1), stored(20, 2)), listOf(row(1))).removed)
    }

    @Test fun damagedDuplicateRowsKeepOneStableId() {
        val changes = CatalogTableSync.plan(listOf(stored(10, 1), stored(20, 1)), listOf(row(1)))
        assertEquals(listOf(20L), changes.removed)
        assertEquals(1, changes.count)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyReplacement() {
        CatalogTableSync.plan(listOf(stored(10, 1)), emptyList())
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsAmbiguousNewCatalog() {
        CatalogTableSync.plan(emptyList(), listOf(row(1), row(1, "duplicate")))
    }

    private class Sql(private val rows: List<List<Any>>) : CatalogSql {
        val writes = mutableListOf<Pair<String, List<Any>>>()
        override fun execute(sql: String, args: List<Any>) { writes.add(sql to args) }
        override fun query(sql: String, args: List<Any>, numeric: Set<Int>): List<List<Any>> {
            assertTrue(sql.contains("WHERE book_id=?"))
            assertEquals(listOf(7L), args)
            assertEquals(setOf(0, 1, 2, 7), numeric)
            return rows
        }
    }

    @Test fun identicalSyncDoesNotInvalidateAnyTable() {
        val sql = Sql(listOf(listOf(10L) + row(1)))
        assertEquals(0, CatalogTableSync.sync(sql, CatalogTableSync.Table.CHAPTER, 7L, listOf(row(1))))
        assertTrue(sql.writes.isEmpty())
    }

    @Test fun generatedSqlIsBoundedByBookAndId() {
        val sql = Sql(listOf(listOf(10L) + row(1), listOf(20L) + row(2)))
        assertEquals(3, CatalogTableSync.sync(sql, CatalogTableSync.Table.CHAPTER, 7L, listOf(row(1, "new"), row(3))))
        assertTrue(sql.writes[0].first.endsWith("WHERE id=? AND book_id=?"))
        assertEquals(listOf(20L, 7L), sql.writes[0].second)
        assertTrue(sql.writes[1].first.endsWith("WHERE id=? AND book_id=?"))
        assertEquals(listOf(10L, 7L), sql.writes[1].second.takeLast(2))
        assertFalse(sql.writes[2].first.contains("REPLACE"))
    }

    @Test(expected = IllegalArgumentException::class) fun refusesCrossBookCatalog() {
        CatalogTableSync.sync(Sql(emptyList()), CatalogTableSync.Table.CHAPTER, 8L, listOf(row(1)))
    }
}
