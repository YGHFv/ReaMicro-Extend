package com.reamicro.fix.hook

import android.content.Context
import android.os.Looper
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.reload.ModuleResources
import java.util.WeakHashMap

/** Compose reads an immutable snapshot; initial file parsing happens once on a worker. */
internal object OnlineSourceSettingsLoads {
    private data class State(var loading: Boolean = false, var failed: Boolean = false)
    private val owners = WeakHashMap<ReaMicroSettingsHook, State>()
    private val main = ModuleResources.handler(Looper.getMainLooper())
    private val observer = OnlineSourceStore.observeChanges {
        val hooks = synchronized(owners) {
            owners.values.forEach { it.failed = false }
            owners.keys.toList()
        }
        main.post { hooks.forEach { it.bumpOnlineSourceVersion() } }
    }

    init {
        ModuleResources.cleanup { observer.close(); synchronized(owners) { owners.clear() } }
    }

    fun list(owner: ReaMicroSettingsHook, context: Context?): List<OnlineSourceEntry> {
        context ?: return emptyList()
        val state = synchronized(owners) { owners.getOrPut(owner) { State() } }
        OnlineSourceStore.cached(context)?.let { return it }
        synchronized(owners) {
            if (state.loading || state.failed) return emptyList()
            state.loading = true
        }
        try {
            ModuleResources.thread({
                val result = runCatching { OnlineSourceStore.list(context.applicationContext) }
                synchronized(owners) { state.loading = false; state.failed = result.isFailure }
                main.post { owner.bumpOnlineSourceVersion() }
            }, "ReaMicroSourceList").start()
        } catch (_: IllegalStateException) {
            synchronized(owners) { state.loading = false; state.failed = true }
        }
        return emptyList()
    }
}

/** A late login callback cannot turn a switch on after the user has turned it off. */
internal object OnlineSourceEnableRequests {
    private val versions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val serial = java.util.concurrent.atomic.AtomicLong()
    fun begin(sourceId: String): Long = serial.incrementAndGet().also { versions[sourceId] = it }
    fun current(sourceId: String, token: Long): Boolean = versions[sourceId] == token
}

internal object OnlineSourceImports {
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val executor = ModuleResources.pool(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(4)) { runnable ->
        Thread(runnable, "ReaMicroSourceImport").apply { isDaemon = true }
    }
    init { ModuleResources.cleanup { pending.clear() } }

    fun submit(key: String, action: () -> Unit): Boolean {
        if (!pending.add(key)) return false
        return try {
            executor.execute { try { action() } finally { pending.remove(key) } }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            pending.remove(key)
            false
        }
    }
}
