package com.reamicro.fix.hook

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.CancellationException

/** Native request objects can resume on another thread and have structural data-class equality. */
internal class NativeCoverRequestOwnership {
    private class RequestKey(request: Any, queue: ReferenceQueue<Any>? = null) :
        WeakReference<Any>(request, queue) {
        private val identityHash = System.identityHashCode(request)
        override fun hashCode(): Int = identityHash
        override fun equals(other: Any?): Boolean =
            this === other || other is RequestKey && get()?.let { it === other.get() } == true
    }

    private class Entry(
        val withCurrent: (() -> Unit) -> Unit,
        val bind: (Any) -> Unit,
    ) {
        @Volatile var closed = false
        fun run(action: () -> Unit) {
            withCurrent {
                checkActive()
                action()
                checkActive()
            }
        }
        private fun checkActive() {
            if (closed) throw CancellationException("关联封面请求已结束")
        }
    }

    private val queue = ReferenceQueue<Any>()
    private val requests = HashMap<RequestKey, Entry>()

    fun register(
        request: Any,
        withCurrent: (() -> Unit) -> Unit,
        bind: (Any) -> Unit,
    ): AutoCloseable {
        val entry = Entry(withCurrent, bind)
        synchronized(requests) {
            drain()
            check(RequestKey(request) !in requests) { "关联封面请求已登记" }
            requests[RequestKey(request, queue)] = entry
        }
        // Keep a weak tombstone until the native request is collected. A late continuation
        // must remain rejected even after the waiting module coroutine has already ended.
        return AutoCloseable { entry.closed = true }
    }

    fun check(request: Any?): Boolean {
        val entry = find(request) ?: return false
        entry.run {}
        return true
    }

    fun bind(request: Any?, builder: Any): Boolean {
        val entry = find(request) ?: return false
        entry.run { entry.bind(builder) }
        return true
    }

    fun clear() = synchronized(requests) {
        requests.values.forEach { it.closed = true }
        requests.clear()
        drain()
    }

    private fun find(request: Any?): Entry? {
        if (request == null) return null
        return synchronized(requests) {
            drain()
            requests[RequestKey(request)]
        }
    }

    /** Caller holds the map monitor. Entries never retain their native request key strongly. */
    private fun drain() {
        while (true) {
            val reference = queue.poll() as? RequestKey ?: return
            requests.remove(reference)
        }
    }
}
