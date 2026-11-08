package com.reamicro.fix.hook

import android.view.Window
import android.view.WindowManager
import java.util.WeakHashMap

/** Native route exit and entry overlap; the last secure page restores the previous window flag. */
internal object HostSecurePageLease {
    private data class State(var count: Int, val alreadySecure: Boolean)
    private val windows = WeakHashMap<Window, State>()

    fun acquire(window: Window): () -> Unit {
        synchronized(windows) {
            val state = windows[window]
            if (state != null) state.count++
            else {
                val secure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                windows[window] = State(1, secure)
                if (!secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        var released = false
        return {
            synchronized(windows) {
                if (!released) {
                    released = true
                    val state = windows[window]
                    if (state != null && --state.count == 0) {
                        windows.remove(window)
                        if (!state.alreadySecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }
    }
}
