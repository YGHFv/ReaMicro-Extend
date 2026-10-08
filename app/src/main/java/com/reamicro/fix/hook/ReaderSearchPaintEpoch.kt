package com.reamicro.fix.hook

internal class ReaderSearchPaintEpoch(loader: ClassLoader) {
    private val facade = loader.loadClass("androidx.compose.runtime.SnapshotStateKt")
    private val policyType = loader.loadClass("androidx.compose.runtime.SnapshotMutationPolicy")
    private val state = facade.getMethod("mutableStateOf", Any::class.java, policyType).invoke(null, 0L,
        facade.getMethod("structuralEqualityPolicy").invoke(null))
    private val get = loader.loadClass("androidx.compose.runtime.State").getMethod("getValue")
    private val set = loader.loadClass("androidx.compose.runtime.MutableState").getMethod("setValue", Any::class.java)
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
    }.getOrElse { searchPaintUnavailable = true; com.reamicro.fix.xposed.XposedBridge.log("ReaMicro search paint observer unavailable: ${it.javaClass.simpleName}"); false }
}
internal fun ReaderHook.invalidateSearchPaint(): Boolean {
    if (searchPaintUnavailable) return false
    return runCatching {
        val epoch = searchPaintEpoch ?: ReaderSearchPaintEpoch(classLoader).also { searchPaintEpoch = it }
        epoch.invalidate()
        true
    }.getOrElse { searchPaintUnavailable = true; com.reamicro.fix.xposed.XposedBridge.log("ReaMicro search paint invalidation unavailable: ${it.javaClass.simpleName}"); false }
}
