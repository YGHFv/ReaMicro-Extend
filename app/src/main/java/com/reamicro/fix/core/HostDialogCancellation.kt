package com.reamicro.fix.core

import java.util.IdentityHashMap

/** The host has inlined both dismiss flags; retain its gesture handling and classify its callback. */
internal class HostDialogCancellation {
    @Volatile private var cancelable = true
    @Volatile private var outsideCancelable = true

    fun setCancelable(flag: Boolean) { cancelable = flag }

    fun setCanceledOnTouchOutside(flag: Boolean) {
        outsideCancelable = flag
        if (flag) cancelable = true
    }

    fun allowsBack() = cancelable
    fun allowsDismiss() = cancelable && (touch.get() !== this || outsideCancelable)

    fun track(dismiss: Any) = synchronized(ownership) {
        check(callbacks[dismiss].let { it == null || it === this })
        callbacks[dismiss] = this
    }

    fun release() = synchronized(ownership) {
        callbacks.entries.removeAll { it.value === this }
        windows.entries.removeAll { it.value.owner === this }
    }

    fun boundWindows(): List<Any> = synchronized(ownership) {
        windows.entries.filter { it.value.owner === this }.map { it.key }
    }

    companion object {
        private data class Render(val owner: HostDialogCancellation, val dismiss: Any)
        private val ownership = Any()
        private val callbacks = IdentityHashMap<Any, HostDialogCancellation>()
        private val windows = IdentityHashMap<Any, Render>()
        private val render = ThreadLocal<Render>()
        private val touch = ThreadLocal<HostDialogCancellation>()

        /** Only a callback explicitly registered by this module generation can enter this scope. */
        fun enterRender(dismiss: Any): AutoCloseable? {
            val owner = synchronized(ownership) { callbacks[dismiss] } ?: return null
            return scope(render, Render(owner, dismiss))
        }

        fun bindWindow(window: Any, dismiss: Any) {
            val active = render.get() ?: return
            if (active.dismiss !== dismiss) return
            synchronized(ownership) { windows[window] = active }
        }

        fun enterTouch(window: Any, dismiss: Any): AutoCloseable? {
            val active = synchronized(ownership) { windows[window] } ?: return null
            if (active.dismiss !== dismiss) return null
            return scope(touch, active.owner)
        }

        fun clear() {
            synchronized(ownership) {
                windows.clear()
                callbacks.clear()
            }
            render.remove()
            touch.remove()
        }

        private fun <T> scope(local: ThreadLocal<T>, value: T): AutoCloseable {
            val previous = local.get()
            val result = object : AutoCloseable {
                private var closed = false
                override fun close() {
                    if (closed) return
                    closed = true
                    if (previous == null) local.remove() else local.set(previous)
                }
            }
            local.set(value)
            return result
        }
    }
}
