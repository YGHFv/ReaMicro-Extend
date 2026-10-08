package com.reamicro.fix.hook

internal class SearchUpdateThrottle(private val intervalMs: Long) {
    private var lastSize = 0
    private var lastTime: Long? = null

    fun accept(size: Int, now: Long, done: Boolean): Boolean {
        if (!done && (size == lastSize || lastTime?.let { now - it < intervalMs } == true)) return false
        lastSize = size
        lastTime = now
        return true
    }
}
