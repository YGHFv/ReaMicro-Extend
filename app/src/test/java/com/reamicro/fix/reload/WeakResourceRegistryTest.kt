package com.reamicro.fix.reload

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WeakResourceRegistryTest {
    @Test fun collectedRegistrationsDoNotAccumulate() {
        val registry = WeakResourceRegistry<Any>()
        val live = List(16) { Any().also { registry.add(it) } }
        repeat(100_000) {
            val value = Any()
            val reference = registry.add(value)
            reference.clear()
            assertTrue(reference.enqueue())
            java.lang.ref.Reference.reachabilityFence(value)
        }
        assertEquals(live.size, registry.size())
        assertEquals(live.toSet(), registry.drain().toSet())
        assertEquals(0, registry.size())
    }

    @Test fun allLiveResourcesSurviveEvenAboveTypicalCacheLimits() {
        val registry = WeakResourceRegistry<Any>()
        val live = List(4096) { Any().also { registry.add(it) } }
        assertEquals(live.size, registry.size())
        assertEquals(live.toSet(), registry.drain().toSet())
        assertTrue(registry.drain().isEmpty())
    }

    @Test fun equalButDistinctResourcesAreNotMerged() {
        data class Resource(val name: String)
        val a = Resource("same")
        val b = Resource("same")
        val registry = WeakResourceRegistry<Resource>()
        registry.add(a)
        registry.add(b)
        val drained = registry.drain()
        assertEquals(2, drained.size)
        assertTrue(drained.any { it === a })
        assertTrue(drained.any { it === b })
    }

    @Test fun lateQueueEntriesDoNotRemoveNewRegistrationsAfterDrain() {
        val registry = WeakResourceRegistry<Any>()
        val previous = registry.add(Any())
        registry.drain()
        val current = Any()
        registry.add(current)
        previous.clear()
        previous.enqueue()
        assertEquals(listOf(current), registry.drain())
    }

    @Test fun concurrentRegistrationDoesNotLoseLiveResources() {
        val registry = WeakResourceRegistry<Any>()
        val values = List(2000) { Any() }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = values.chunked(500).map { part -> executor.submit { part.forEach { registry.add(it) } } }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(values.toSet(), registry.drain().toSet())
        } finally {
            executor.shutdownNow()
        }
    }
}
