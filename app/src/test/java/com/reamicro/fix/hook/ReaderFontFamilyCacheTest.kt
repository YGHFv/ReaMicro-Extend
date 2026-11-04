package com.reamicro.fix.hook

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ReaderFontFamilyCacheTest {
    @Test fun manyFontVersionsStayBounded() {
        val cache = ReaderFontFamilyCache<Any>()
        repeat(10_000) { version ->
            cache.getOrLoad("/fonts/song.ttf|100|$version") { Any() }
            assertTrue(cache.size() <= 8)
        }
        assertEquals(8, cache.size())
    }

    @Test fun hitsRefreshLruAndDoNotReload() {
        val cache = ReaderFontFamilyCache<Any>(2)
        val a = Any()
        val b = Any()
        cache.getOrLoad("a") { a }
        cache.getOrLoad("b") { b }
        assertSame(a, cache.getOrLoad("a") { error("cache miss") })
        cache.getOrLoad("c") { Any() }
        assertSame(a, cache.getOrLoad("a") { error("hot entry evicted") })
        val replacement = Any()
        assertSame(replacement, cache.getOrLoad("b") { replacement })
    }

    @Test fun failedLoadsCanRetry() {
        val cache = ReaderFontFamilyCache<Any>()
        assertNull(cache.getOrLoad("missing") { null })
        assertEquals(0, cache.size())
        val family = Any()
        assertSame(family, cache.getOrLoad("missing") { family })
    }

    @Test fun evictionAndClearNeverCloseFamiliesHeldByTheHost() {
        class Family : AutoCloseable {
            var closed = false
            override fun close() { closed = true }
        }
        val cache = ReaderFontFamilyCache<Family>(1)
        val first = Family()
        val second = Family()
        cache.getOrLoad("first") { first }
        cache.getOrLoad("second") { second }
        cache.clear()
        assertFalse(first.closed)
        assertFalse(second.closed)
        assertEquals(0, cache.size())
    }

    @Test fun inFlightLoadCannotRepopulateClearedCache() {
        val cache = ReaderFontFamilyCache<Any>()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val old = Any()
            val result = executor.submit<Any?> {
                cache.getOrLoad("font") {
                    entered.countDown()
                    check(resume.await(10, TimeUnit.SECONDS))
                    old
                }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            cache.clear()
            val current = Any()
            cache.getOrLoad("font") { current }
            resume.countDown()
            assertSame(old, result.get(10, TimeUnit.SECONDS))
            assertSame(current, cache.getOrLoad("font") { error("lost current family") })
            assertEquals(1, cache.size())
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test(expected = IllegalArgumentException::class) fun zeroCapacityIsRejected() {
        ReaderFontFamilyCache<Any>(0)
    }
}
