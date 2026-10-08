package com.reamicro.fix.hook

internal class ReaderRefreshCoordinator {
    class Lease internal constructor(val key: String, internal val generation: Long)
    private var generation = 0L
    private val leases = mutableMapOf<String, Lease>()
    private var active: Lease? = null
    private val worker = LatestSearchWorker("ReaMicroOnDemandRefresh")

    @Synchronized fun generation(): Long = generation
    @Synchronized fun isGeneration(value: Long): Boolean = value == generation
    @Synchronized fun claim(key: String): Lease? {
        if (key in leases) return null
        return Lease(key, generation).also { leases[key] = it }
    }
    @Synchronized fun isCurrent(lease: Lease): Boolean =
        lease.generation == generation && leases[lease.key] === lease

    @Synchronized fun release(lease: Lease) {
        if (leases[lease.key] === lease) leases.remove(lease.key)
        if (active === lease) active = null
    }

    @Synchronized fun submit(lease: Lease, block: (() -> Boolean) -> Unit): Boolean {
        if (!isCurrent(lease) || active === lease) return false
        active?.let(::release)
        active = lease
        worker.submit { ticket ->
            val current = { ticket.isCurrent() && isCurrent(lease) }
            try { if (current()) block(current) } finally { release(lease) }
        }
        return true
    }

    @Synchronized fun cancelActiveIf(predicate: (Lease) -> Boolean) {
        val old = active ?: return
        if (!predicate(old)) return
        release(old)
        worker.cancel()
    }

    @Synchronized fun reset() {
        generation++
        leases.clear()
        active = null
        worker.cancel()
    }
}
