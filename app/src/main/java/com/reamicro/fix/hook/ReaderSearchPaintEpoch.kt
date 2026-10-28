package com.reamicro.fix.hook

import com.reamicro.fix.core.B2NativeUi
import com.reamicro.fix.core.HostNativeUiAbi
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostStaticMethod
import com.reamicro.fix.xposed.XposedBridge

internal class ReaderSearchPaintEpoch(loader: ClassLoader) {
    // B4 将状态工厂合并到原生门面，不能再从已删除的 Compose 文件类查找。
    private val state = if (HostNativeUiAbi.supports(HostSymbols.profileId)) {
        B2NativeUi(loader).mutableState(0L)
    } else loader.hostStaticMethod(
        "androidx.compose.runtime.SnapshotStateKt__SnapshotStateKt",
        "mutableStateOf", Any::class.java,
    ).invoke(null, 0L)
    private val get = loader.hostClass("androidx.compose.runtime.State").hostMethod("getValue")
    private val set = loader.hostClass("androidx.compose.runtime.MutableState").hostMethod("setValue", Any::class.java)
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
        XposedBridge.logError("ReaMicro search paint observer unavailable", it)
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
        XposedBridge.logError("ReaMicro search paint invalidation unavailable", it)
        false
    }
}
