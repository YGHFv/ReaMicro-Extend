package com.reamicro.fix.hook

internal class ReaderRefreshCoordinator : AutoCloseable {
    class Lease internal constructor(val key: String, internal val generation: Long, internal val presentation: Long)
    private var generation = 0L
    private var presentation = 0L
    private val leases = mutableMapOf<String, Lease>()
    private var active: Lease? = null
    private var closed = false
    private val worker = LatestSearchWorker("ReaMicroOnDemandRefresh")

    @Synchronized fun generation(): Long = generation
    @Synchronized fun presentation(): Long = presentation
    @Synchronized fun isGeneration(value: Long): Boolean = !closed && value == generation
    @Synchronized fun isPresentation(value: Long): Boolean = !closed && value == presentation
    @Synchronized fun inGeneration(value: Long, action: () -> Unit): Boolean {
        if (closed || value != generation) return false
        action()
        return true
    }
    @Synchronized fun claim(key: String): Lease? {
        if (closed || key in leases) return null
        return Lease(key, generation, presentation).also { leases[key] = it }
    }
    @Synchronized fun isCurrent(lease: Lease): Boolean =
        !closed && lease.generation == generation && lease.presentation == presentation && leases[lease.key] === lease

    @Synchronized fun release(lease: Lease) {
        if (leases[lease.key] === lease) leases.remove(lease.key)
        if (active === lease) active = null
    }

    fun submit(lease: Lease, block: (() -> Boolean) -> Unit): Boolean {
        try {
            val ticket = worker.submitIf({
                synchronized(this) {
                    if (!isCurrent(lease) || active === lease) false else {
                        active?.let(::release)
                        active = lease
                        true
                    }
                }
            }) { ticket ->
                val current = { ticket.isCurrent() && isCurrent(lease) }
                try { if (current()) block(current) } finally { release(lease) }
            }
            return ticket != null
        } catch (error: Throwable) {
            release(lease)
            com.reamicro.fix.xposed.HookRuntime.logError("ReaMicro reader refresh start failed", error)
            return false
        }
    }

    fun cancelActiveIf(predicate: (Lease) -> Boolean) {
        worker.cancelIf {
            synchronized(this) {
                val old = active
                if (old == null || !predicate(old)) false else {
                    release(old)
                    true
                }
            }
        }
    }

    fun suspendPresentation() = invalidate(bookChanged = false)

    fun reset() = invalidate(bookChanged = true)

    private fun invalidate(bookChanged: Boolean) {
        worker.cancelIf {
            synchronized(this) {
                if (bookChanged) generation++
                presentation++
                leases.clear()
                active = null
                true
            }
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            generation++
            presentation++
            leases.clear()
            active = null
        }
        worker.close()
    }
}
