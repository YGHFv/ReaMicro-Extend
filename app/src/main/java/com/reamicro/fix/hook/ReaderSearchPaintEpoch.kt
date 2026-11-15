package com.reamicro.fix.hook

import com.reamicro.fix.xposed.HookRuntime

internal class ReaderSearchPaintEpoch(loader: ClassLoader) {
    private val ui = requireNotNull(com.reamicro.fix.core.HostUiBindings.current).also { require(it.loader === loader) }
    private val state = requireNotNull(ui.call("state", 0L))
    private val get = ui.method("stateGet")
    private val set = ui.method("stateSet")
    private var epoch = 0L
    private data class Seen(val owner: java.lang.ref.WeakReference<Any>, var epoch: Long)
    private val seen = arrayListOf<Seen>()
    fun version(): Long = (get.invoke(state) as Number).toLong()
    fun observe(owner: Any): Boolean {
        val value = (get.invoke(state) as Number).toLong()
        synchronized(seen) {
            seen.removeAll { it.owner.get() == null }
            val previous = seen.firstOrNull { it.owner.get() === owner }
            val changed = previous?.epoch?.let { it != value } ?: (value != 0L)
            if (previous != null) previous.epoch = value
            else { if (seen.size >= 64) seen.removeAt(0); seen += Seen(java.lang.ref.WeakReference(owner), value) }
            return changed
        }
    }
    fun invalidate() { set.invoke(state, ++epoch) }
}

internal fun ReaderHook.observeSearchPaint(owner: Any): Boolean {
    if (searchPaintUnavailable) return false
    return runCatching {
        val epoch = searchPaintEpoch ?: ReaderSearchPaintEpoch(classLoader).also { searchPaintEpoch = it }
        epoch.observe(owner)
    }.getOrElse {
        searchPaintUnavailable = true
        HookRuntime.logError("ReaMicro search paint observer unavailable", it)
        false
    }
}
internal fun ReaderHook.invalidateSearchPaint(): Boolean {
    if (searchPaintUnavailable) return false
    return runCatching {
        val epoch = searchPaintEpoch ?: ReaderSearchPaintEpoch(classLoader).also { searchPaintEpoch = it }
        epoch.invalidate()
        true
    }.getOrElse {
        searchPaintUnavailable = true
        HookRuntime.logError("ReaMicro search paint invalidation unavailable", it)
        false
    }
}
