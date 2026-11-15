package com.reamicro.fix.reader

/** Duration clock for automatic reading; the caller supplies elapsed realtime. */
internal class ReaderAutoPageTimer(private val clock: () -> Long) {
    private var deadline: Long? = null
    private var pausedRemaining: Long? = null
    var paused: Boolean = false
        private set

    fun start(durationMillis: Long, startPaused: Boolean = false) {
        require(durationMillis >= 0L)
        deadline = if (durationMillis == 0L || startPaused) null else clock() + durationMillis
        pausedRemaining = if (startPaused && durationMillis != 0L) durationMillis else null
        paused = startPaused
    }

    fun remainingMillis(): Long? =
        if (paused) pausedRemaining else deadline?.let { (it - clock()).coerceAtLeast(0L) }

    fun expired(): Boolean = !paused && remainingMillis() == 0L

    fun pause() {
        if (paused) return
        pausedRemaining = remainingMillis()
        paused = true
    }

    fun resume() {
        if (!paused) return
        deadline = pausedRemaining?.let { clock() + it }
        pausedRemaining = null
        paused = false
    }

    fun clear() {
        deadline = null
        pausedRemaining = null
        paused = false
    }
}
