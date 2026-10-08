package com.reamicro.fix.hook

internal class ReaderHighlightImageCache<V : Any>(
    private val clock: () -> Long,
    private val submit: (() -> Unit) -> Unit,
    private val deliver: (() -> Unit) -> Unit,
    private val load: (String, V?) -> V?,
    private val weight: (V) -> Long,
    private val changed: (String) -> Unit,
    private val isInUse: (String) -> Boolean = { false },
    private val retryMs: Long = 5_000L,
    private val maxEntries: Int = 64,
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val maxPending: Int = 32,
) {
    private data class Entry<V>(val value: V?, val checkedAt: Long, var usedAt: Long)
    private val entries = LinkedHashMap<String, Entry<V>>(16, 0.75f, true)
    private val pending = HashMap<String, Long>()
    private var generation = 0L
    private var admissionBlockedUntil = 0L

    @Synchronized
    fun get(path: String): V? {
        val entry = entries[path]
        val previous = entry?.value
        val now = clock()
        entry?.usedAt = now
        if (entry != null && ((previous != null && path.startsWith("asset://")) ||
                now - entry.checkedAt < retryMs)) return previous
        // 容量压力使用全局退避，不能用另一个会被淘汰的失败条目记录，避免多图循环穿透。
        if (previous == null && now < admissionBlockedUntil) return null
        if (path in pending || pending.size >= maxPending) return previous
        val ticket = generation
        pending[path] = ticket
        try {
            submit {
                if (isCurrent(path, ticket)) {
                    val result = runCatching { load(path, previous) }.getOrNull()
                    deliver { complete(path, ticket, previous, result) }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            pending.remove(path)
        }
        return previous
    }

    @Synchronized
    fun clear() {
        generation++
        entries.clear()
        pending.clear()
        admissionBlockedUntil = 0L
    }

    @Synchronized
    private fun isCurrent(path: String, ticket: Long): Boolean =
        generation == ticket && pending[path] == ticket

    private fun complete(path: String, ticket: Long, previous: V?, result: V?) {
        synchronized(this) {
            // 清理前启动的解码不能重新填充缓存，也不能唤醒已经退出的阅读节点。
            if (!isCurrent(path, ticket)) return
            pending.remove(path)
            val now = clock()
            val resultBytes = result?.let(weight) ?: 0L
            var bytes = resultBytes + entries.filterKeys { it != path }.values.sumOf { it.value?.let(weight) ?: 0L }
            var count = entries.size + if (path in entries) 0 else 1
            val evictions = ArrayList<String>()
            for ((key, entry) in entries) {
                if (bytes <= maxBytes && count <= maxEntries) break
                if (key == path || (entry.value != null && (now - entry.usedAt < retryMs || isInUse(key)))) continue
                evictions.add(key)
                bytes -= entry.value?.let(weight) ?: 0L
                count--
            }
            if (bytes > maxBytes || count > maxEntries) {
                // 保住近期绘制使用的图片，拒绝本次准入而不是让它们互相淘汰并请求重绘。
                admissionBlockedUntil = now + retryMs
                entries[path]?.let { entries[path] = it.copy(checkedAt = now) }
                return
            }
            evictions.forEach(entries::remove)
            entries[path] = Entry(result, now, now)
        }
        if (result !== previous) changed(path)
    }
}
