package com.reamicro.fix.notification

/** A delayed permission Activity or service Intent must not replace a newer result. */
internal object OnlineNotificationEventOrder {
    const val EXTRA_ISSUED_AT = "reamicro_notification_issued_at"
    private data class Event(val issuedAt: Long, val key: String)
    private val received = LinkedHashMap<Int, Event>()

    @Synchronized
    fun accept(id: Int, issuedAt: Long, key: String = ""): Boolean {
        if (issuedAt <= 0L) return true // Compatibility with a host process loaded before an update.
        val previous = received[id]
        if (issuedAt < (previous?.issuedAt ?: Long.MIN_VALUE)) return false
        received.remove(id)
        received[id] = Event(issuedAt, key.ifBlank { previous?.key.orEmpty() })
        while (received.size > 256) received.remove(received.keys.first())
        return true
    }

    @Synchronized
    fun version(id: Int): Long? = received[id]?.issuedAt

    @Synchronized
    fun matches(id: Int, key: String): Boolean =
        received[id]?.key?.takeIf(String::isNotBlank)?.let { it == key } ?: true

    /** A user request is not a new progress event; an already-issued final result may arrive later. */
    @Synchronized
    fun acceptsStopRequest(id: Int, key: String): Boolean = matches(id, key)
}
