package com.reamicro.fix.hook

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 空结果也记忆，避免逐帧重算；值不得反向持有节点或布局，布局只在当次绘制栈上使用。 */
internal class ReaderTextDrawCache<T : Any> {
    private class Entry<T>(layout: Any, val token: String, val state: T?) {
        val layout = WeakReference(layout)
    }
    private val entries = WeakHashMap<Any, Entry<T>>()
    private var generation = 0L

    fun getOrBuild(node: Any, layout: Any, token: String, build: () -> T?): T? {
        val ticket = synchronized(this) {
            entries[node]?.takeIf { it.layout.get() === layout && it.token == token }
                ?.let { return it.state }
            generation
        }
        val state = build()
        synchronized(this) {
            if (ticket == generation) entries[node] = Entry(layout, token, state)
        }
        return state
    }

    @Synchronized fun snapshot(): List<Pair<Any, T>> =
        entries.entries.mapNotNull { (node, entry) ->
            entry.state?.takeIf { entry.layout.get() != null }?.let { node to it }
        }

    @Synchronized fun remove(node: Any) { entries.remove(node) }
    @Synchronized fun clear() { generation++; entries.clear() }
}
