package com.reamicro.fix.hook

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

internal class ReaderIdentityCache<T : Any>(private val capacity: Int = 128) {
    private class Key(value: Any, queue: ReferenceQueue<Any>? = null) : WeakReference<Any>(value, queue) {
        private val identityHash = System.identityHashCode(value)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean =
            this === other || other is Key && get()?.let { it === other.get() } == true
    }

    private val queue = ReferenceQueue<Any>()
    private val entries = LinkedHashMap<Key, T?>(16, 0.75f, true)
    private var generation = 0L

    init {
        require(capacity > 0)
    }

    fun getOrBuild(value: Any, build: () -> T?): T? {
        val key = Key(value)
        val ticket = synchronized(this) {
            drainQueue()
            if (entries.containsKey(key)) return entries[key]
            generation
        }
        val result = build()
        synchronized(this) {
            drainQueue()
            if (ticket == generation) {
                entries[Key(value, queue)] = result
                while (entries.size > capacity) {
                    entries.keys.iterator().run { next(); remove() }
                }
            }
        }
        return result
    }

    @Synchronized
    fun clear() {
        generation++
        entries.clear()
        drainQueue()
    }

    private fun drainQueue() {
        while (true) entries.remove(queue.poll() ?: return)
    }
}
