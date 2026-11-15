package com.reamicro.fix.hook

/** All UUID aliases of one consent expire and are consumed together, within the same account. */
internal class ModuleImportDecisionMemory<T : Any>(
    private val ttlMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Key(val uid: Long, val generation: Long, val uuid: String, val title: String)
    private data class Entry<T>(val value: T, val created: Long)
    private val entries = LinkedHashMap<Key, Entry<T>>()

    @Synchronized
    fun remember(uid: Long, generation: Long, uuid: String, title: String, value: T) {
        clearExpired()
        val target = key(uid, generation, uuid, title)
        val replaced = entries[target]
        if (replaced != null && replaced.value !== value) entries.entries.removeAll { it.value === replaced }
        val existing = entries.values.firstOrNull { it.value === value }
        entries[target] = existing ?: Entry(value, now())
    }

    @Synchronized
    fun consume(uid: Long, generation: Long, uuid: String, title: String): T? {
        clearExpired()
        val entry = entries[key(uid, generation, uuid, title)] ?: return null
        entries.entries.removeAll { it.value === entry }
        return entry.value
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun forget(value: T) {
        entries.entries.removeAll { it.value.value === value }
    }

    private fun key(uid: Long, generation: Long, uuid: String, title: String) =
        Key(uid, generation, uuid.trim(), title.trim().replace(Regex("\\s+"), " "))

    private fun clearExpired() {
        val at = now()
        entries.entries.removeAll { at - it.value.created > ttlMs }
    }
}
