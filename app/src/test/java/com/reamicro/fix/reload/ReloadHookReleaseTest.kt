package com.reamicro.fix.reload

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ReloadHookReleaseTest {
    @Test fun mainCleanupReturnsWhileReloadThreadOwnsTheFrameworkLock() {
        val release = ReloadHookRelease()
        val frameworkLock = Any()
        val done = CountDownLatch(1)
        val invokedOn = AtomicReference<Thread>()
        synchronized(frameworkLock) {
            val main = Thread {
                release.release(defer = true) {
                    synchronized(frameworkLock) { invokedOn.set(Thread.currentThread()) }
                }
                done.countDown()
            }
            main.start()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            main.join(3_000)
            assertFalse(main.isAlive)
            assertNull(invokedOn.get())
            release.drain()
            assertSame(Thread.currentThread(), invokedOn.get())
        }
    }

    @Test fun activeGenerationReleasesImmediately() {
        val release = ReloadHookRelease()
        var calls = 0
        release.release(defer = false) { calls++ }
        assertEquals(1, calls)
        release.drain()
        assertEquals(1, calls)
    }

    @Test fun oneFailedUnhookDoesNotSkipOtherQueuedHandles() {
        val release = ReloadHookRelease()
        val first = IllegalStateException("第一个卸载失败")
        val second = IllegalArgumentException("第二个卸载失败")
        var calls = 0
        release.release(defer = true) { throw first }
        release.release(defer = true) { calls++ }
        release.release(defer = true) { throw second }
        assertSame(first, assertThrows(IllegalStateException::class.java) { release.drain() })
        assertEquals(1, calls)
        assertArrayEquals(arrayOf(second), first.suppressed)
        release.drain()
        assertEquals(1, calls)
    }
}
