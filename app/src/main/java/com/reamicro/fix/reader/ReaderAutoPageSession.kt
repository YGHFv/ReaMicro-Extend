package com.reamicro.fix.reader

/** A request waits for visible reader content; only foreground reading consumes its duration. */
internal class ReaderAutoPageSession(clock: () -> Long) {
    enum class Transition { NONE, STARTED, RESUMED, PAUSED, FINISHED }

    private val timer = ReaderAutoPageTimer(clock)
    var requested = false
        private set
    private var started = false
    val running: Boolean get() = requested && !timer.paused
    val paused: Boolean get() = requested && timer.paused

    fun request(durationMillis: Long) {
        timer.start(durationMillis, startPaused = true)
        requested = true
        started = false
    }

    fun visibility(readerVisible: Boolean, activityResumed: Boolean): Transition {
        if (!requested) return Transition.NONE
        if (!readerVisible || !activityResumed) {
            if (timer.paused) return Transition.NONE
            timer.pause()
            return Transition.PAUSED
        }
        if (!timer.paused) {
            if (!timer.expired()) return Transition.NONE
            stop()
            return Transition.FINISHED
        }
        timer.resume()
        if (timer.expired()) {
            stop()
            return Transition.FINISHED
        }
        return if (started) Transition.RESUMED else {
            started = true
            Transition.STARTED
        }
    }

    fun expired(): Boolean = running && timer.expired()
    fun remainingMillis(): Long? = timer.remainingMillis()

    fun stop() {
        requested = false
        started = false
        timer.clear()
    }
}
