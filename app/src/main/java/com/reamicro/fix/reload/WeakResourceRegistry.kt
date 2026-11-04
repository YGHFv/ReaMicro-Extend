package com.reamicro.fix.reload

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** 回收失效的登记项，但不限制存活资源数量，避免热重载漏清仍在排队的回调。 */
internal class WeakResourceRegistry<T : Any> {
    private val queue = ReferenceQueue<T>()
    private val references = HashSet<WeakReference<T>>()

    @Synchronized fun add(value: T): WeakReference<T> {
        prune()
        return WeakReference(value, queue).also { references.add(it) }
    }

    @Synchronized fun drain(): List<T> {
        prune()
        return references.mapNotNull { it.get() }.also { references.clear() }
    }

    @Synchronized fun size(): Int {
        prune()
        return references.size
    }

    private fun prune() {
        while (true) references.remove(queue.poll() ?: return)
    }
}
