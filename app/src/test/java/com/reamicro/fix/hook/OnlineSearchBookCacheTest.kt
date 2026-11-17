package com.reamicro.fix.hook

import org.junit.Assert.*
import org.junit.Test

class OnlineSearchBookCacheTest {
    @Test fun progressRedrawRetainsTheBookAndItsDownloadTarget() {
        val cache = OnlineSearchBookCache<Any>()
        var registrations = 0
        val create = { registrations++; Any() }
        val book = cache.get("source/0", 1, "r1", listOf("书名", "作者"), create)
        repeat(10) {
            assertSame(book, cache.get("source/0", 1, "r1", listOf("书名", "作者"), create))
        }
        assertEquals(1, registrations)
    }

    @Test fun newQuerySourceRevisionOrMetadataReplacesTheBook() {
        val cache = OnlineSearchBookCache<Any>()
        val first = cache.get("source/0", 1, "r1", "书名") { Any() }
        val metadata = cache.get("source/0", 1, "r1", "书名 / 完结") { Any() }
        assertNotSame(first, metadata)
        val revision = cache.get("source/0", 1, "r2", "书名 / 完结") { Any() }
        assertNotSame(metadata, revision)
        assertNotSame(revision, cache.get("source/0", 2, "r2", "书名 / 完结") { Any() })
    }

    @Test fun newQueryDropsAllOldRowsAndCloseDropsTheCurrentRows() {
        val cache = OnlineSearchBookCache<Any>()
        val other = cache.get("source/1", 1, "r1", "另一本书") { Any() }
        cache.get("source/0", 2, "r1", "书名") { Any() }
        val current = cache.get("source/1", 2, "r1", "另一本书") { Any() }
        assertNotSame(other, current)
        cache.clear()
        assertNotSame(current, cache.get("source/1", 2, "r1", "另一本书") { Any() })
    }
}
