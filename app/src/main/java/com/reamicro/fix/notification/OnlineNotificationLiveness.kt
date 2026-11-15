package com.reamicro.fix.notification

/** Progress or a matching host acknowledgement renews only the corresponding task. */
internal class OnlineNotificationLiveness(private val timeoutMs: Long = 90_000L) {
    private data class Lease(val key: String, var seenAt: Long)
    private val leases = linkedMapOf<Int, Lease>()

    fun track(id: Int, key: String, now: Long) { leases[id] = Lease(key, now) }
    fun acknowledge(id: Int, key: String, now: Long) {
        leases[id]?.takeIf { it.key == key }?.seenAt = now
    }
    fun remove(id: Int) { leases.remove(id) }
    fun clear() { leases.clear() }
    fun expired(now: Long): List<Int> = leases.filterValues { now - it.seenAt >= timeoutMs }.keys.toList()
}
