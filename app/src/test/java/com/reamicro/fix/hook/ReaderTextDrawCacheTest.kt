package com.reamicro.fix.hook

import java.lang.ref.WeakReference
import org.junit.Assert.*
import org.junit.Test

class ReaderTextDrawCacheTest {
    @Test fun emptyRangesAreRememberedWithoutCreatingDrawObjects() {
        val cache = ReaderTextDrawCache<Any>()
        val node = Any()
        val layout = Any()
        var scans = 0
        repeat(10_000) {
            assertNull(cache.getOrBuild(node, layout, "book|theme|rules") { scans++; null })
        }
        assertEquals(1, scans)
        assertTrue(cache.snapshot().isEmpty())
    }

    @Test fun unchangedLayoutReusesStateAndReplacementDropsOldState() {
        val cache = ReaderTextDrawCache<Any>()
        val node = Any()
        val layout = Any()
        val state = Any()
        assertSame(state, cache.getOrBuild(node, layout, "v1") { state })
        assertSame(state, cache.getOrBuild(node, layout, "v1") { error("unnecessary rebuild") })
        assertNull(cache.getOrBuild(node, Any(), "v1") { null })
        assertTrue(cache.snapshot().isEmpty())
    }

    @Test fun tokenChangesInvalidateEmptyAndPositiveResults() {
        val cache = ReaderTextDrawCache<Any>()
        val node = Any()
        val layout = Any()
        cache.getOrBuild(node, layout, "disabled") { null }
        val state = Any()
        assertSame(state, cache.getOrBuild(node, layout, "enabled") { state })
        assertNull(cache.getOrBuild(node, layout, "new-theme") { null })
        assertTrue(cache.snapshot().isEmpty())
    }

    @Test fun disableOrDetachRemovesTheNodeAndClearRemovesAllNodes() {
        val cache = ReaderTextDrawCache<Any>()
        val nodes = List(2) { Any() }
        val layout = Any()
        nodes.forEach { cache.getOrBuild(it, layout, "v1") { Any() } }
        assertEquals(2, cache.snapshot().size)
        cache.remove(nodes[0])
        assertEquals(listOf(nodes[1]), cache.snapshot().map { it.first })
        cache.clear()
        assertTrue(cache.snapshot().isEmpty())
    }

    @Test fun runtimeClearDuringBuildDoesNotReadmitOldState() {
        val cache = ReaderTextDrawCache<Any>()
        val state = Any()
        assertSame(state, cache.getOrBuild(Any(), Any(), "v1") { cache.clear(); state })
        assertTrue(cache.snapshot().isEmpty())
    }

    @Test fun cachedLayoutIsWeakAndRebuiltAfterItIsCleared() {
        val cache = ReaderTextDrawCache<Any>()
        val node = Any()
        val layout = Any()
        val old = Any()
        cache.getOrBuild(node, layout, "v1") { old }
        // 确定性模拟引用被回收，避免依赖 System.gc 的时序。
        val entries = cache.javaClass.getDeclaredField("entries").apply { isAccessible = true }
            .get(cache) as Map<*, *>
        val entry = entries[node]!!
        val reference = entry.javaClass.getDeclaredField("layout").apply { isAccessible = true }
            .get(entry) as WeakReference<*>
        reference.clear()
        assertTrue(cache.snapshot().isEmpty())
        val next = Any()
        assertSame(next, cache.getOrBuild(node, layout, "v1") { next })
    }
}
