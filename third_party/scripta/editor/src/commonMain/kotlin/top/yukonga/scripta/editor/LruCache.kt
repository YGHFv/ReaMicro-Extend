package top.yukonga.scripta.editor

internal class LruCache<K, V>(private val maxSize: Int) {
    private class Node<K, V>(val key: K, var value: V) {
        var prev: Node<K, V>? = null
        var next: Node<K, V>? = null
    }

    private val map = HashMap<K, Node<K, V>>()
    private var head: Node<K, V>? = null
    private var tail: Node<K, V>? = null

    private fun unlink(n: Node<K, V>) {
        val p = n.prev
        val nx = n.next
        if (p != null) p.next = nx else head = nx
        if (nx != null) nx.prev = p else tail = p
        n.prev = null
        n.next = null
    }

    private fun appendToTail(n: Node<K, V>) {
        n.prev = tail
        n.next = null
        val t = tail
        if (t != null) t.next = n else head = n
        tail = n
    }

    private fun touch(n: Node<K, V>) {
        if (tail === n) return
        unlink(n)
        appendToTail(n)
    }

    operator fun get(key: K): V? {
        val n = map[key] ?: return null
        touch(n)
        return n.value
    }

    operator fun set(key: K, value: V) {
        val existing = map[key]
        if (existing != null) {
            existing.value = value
            touch(existing)
            return
        }
        val n = Node(key, value)
        map[key] = n
        appendToTail(n)
        if (map.size > maxSize) {
            head?.let { evict ->
                unlink(evict)
                map.remove(evict.key)
            }
        }
    }

    fun getOrPut(key: K, defaultValue: () -> V): V {
        get(key)?.let { return it }
        val v = defaultValue()
        set(key, v)
        return v
    }

    val size: Int get() = map.size
}
